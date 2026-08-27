#!/usr/bin/env bash
# ADR-072: build the SAMPLED PRINT extract — the real trades that become replayed order flow.
#
# Sibling of build-taq-replay-extract.sh (ADR-070's median reference series) and built the same
# way, in BigQuery, so no byte of the corpus reaches a laptop or a node. Where that one takes ONE
# median per 195s window, this one takes K REAL PRINTS per window, chosen at evenly spaced RANKS
# within the window (rank, not time, so a quiet window is sampled as thoroughly as a busy one).
#
# THE SAMPLE'S UNIVERSE MUST BE A SUBSET OF THE MEDIAN EXTRACT'S, and this script enforces that
# by reading the extract out of the bucket rather than trusting a second copy of a list: a symbol
# sampled here but absent there would submit real replayed prices against a synthetic-walk collar
# reference, a defect neither artifact can show on its own. (print-replay.js refuses at runtime
# too, and says so on /health, so a stale pair cannot ship silently either.)
#
# It defaults to the WHOLE extract, but the operative universe at runtime is narrower: the
# publisher only carries a tape price for PRICE_TICKERS ∩ extract, and the replayer will not
# submit orders for a symbol with no tape reference. Pass SYMBOLS= to sample exactly that set,
# which is what keeps the achieved rate inside ADR-072's band; widening PRICE_TICKERS is a reason
# to re-run this, and /health.printReplay.symbols is the reading that shows the gap.
#
# TAKE IT FROM THE DECLARED PRICE_TICKERS, NOT FROM /prices. A quote's `source` only reads
# taq-replay-2025-02 after that ticker's first tick, and the publish loop ticks a FRACTION of the
# universe per interval (PRICE_PUBLISH_BATCH_RATIO), so a publisher that has just rolled reports a
# universe that is still filling in. Measured 2026-08-26: two reads a few minutes apart returned
# 23 symbols and then 19, and the 19 silently became the built artifact.
#
#   SYMBOLS="$(kubectl -n traderx get deploy price-publisher \
#     -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="PRICE_TICKERS")].value}')" \
#     bash scripts/yu17/build-taq-print-sample.sh
#
# Non-tape entries in that list (Treasuries, corporates, GOOGL, FNMA) are dropped automatically:
# the sample is intersected with the extract below.
#
# THE 43-OBJECTS-PER-SYMBOL-DAY IN THE RAW TREE IS RE-SHARDING, NOT DUPLICATION. Measured
# 2026-08-26 after a report that seven symbols carried "42 duplicate copies" of dt=2025-03-11 and
# that any uniform sampler would over-weight that day 42x. Row counts for 2025-03-11 against its
# two neighbouring days, all seven: AAL 1.17, AAPL 0.92, ADBE 0.84, AEP 0.89, AMCR 1.03, AMZN 0.91,
# APA 0.95. No day is duplicated at the row level. The 43 is a count of parquet OBJECTS — the OOM
# retry re-sharded the day across more files, and the external table reads their union.
# COUNT OBJECTS AND YOU MEASURE THE WRITER'S RETRY HISTORY; COUNT ROWS AND YOU MEASURE THE DATA.
# (This sampler is immune either way: it takes a fixed K per (symbol, day, window), so a day with
# ten times the prints contributes exactly as many orders as a quiet one — by construction.)
#
# Needs: gcloud + bq authenticated with bigquery.jobs.create on traderx-501015
# (yaakov.traderx@gmail.com — set CLOUDSDK_CORE_ACCOUNT rather than switching the shared config),
# python3 (stdlib only).
set -euo pipefail

BUCKET="${BUCKET:-gs://traderx-501015-tick-store}"
EXTRACT_URI="${EXTRACT_URI:-${BUCKET}/replay/taq-replay-2025-02/extract-v1.json.gz}"
SAMPLE_URI="${SAMPLE_URI:-${BUCKET}/replay/taq-replay-2025-02/prints-v1.bin.gz}"
ROWS_URI="${BUCKET}/replay/taq-replay-2025-02/print-rows"
WORK="${WORK:-${TMPDIR:-/tmp}/taq-print-sample-build}"
WINDOW="${WINDOW:-195}"
# Prints per window per symbol. The replayed order RATE is what this actually sets:
#   rate = symbols x SLOTS / (WINDOW / compression)      [compression 13 => 15s of wall per window]
# Prints per window per symbol. DERIVED, not picked: the thing ADR-072 actually specifies is an
# order RATE (5-20/s), and slots is what delivers it for a given universe --
#     rate = symbols x slots x compression / window
# so SLOTS is solved for TARGET_RATE below once the symbol list is known. The runtime can sample
# DOWN from it (PRINT_REPLAY_STRIDE), never up.
#
# 6/s rather than the top of the band because the artifact has to fit a Secret, and its size is a
# function of the RATE, not of the symbol count -- the arithmetic is in build-taq-print-sample.py.
# Measured 2026-08-26 against the 1 MiB cap: 12.3/s = 1.43 MB, 26.7/s = 2.87 MB, 6.7/s = 853 KB.
TARGET_RATE="${TARGET_RATE:-6}"
HERE="$(cd "$(dirname "$0")" && pwd)"

mkdir -p "${WORK}/rows"

echo "[symbols] reading the replayed universe out of ${EXTRACT_URI}"
gcloud storage cp "${EXTRACT_URI}" "${WORK}/extract-v1.json.gz" >/dev/null
# Intersected with the extract, always: PRICE_TICKERS carries Treasuries, corporates and the two
# deliberate tape exclusions, none of which the corpus can sample. An empty intersection is fatal
# rather than an empty artifact.
SYMBOLS="$(python3 -c '
import gzip, json, sys
ex = json.loads(gzip.open(sys.argv[1]).read())
want = [t.strip().upper() for t in sys.argv[2].split(",") if t.strip()] or sorted(ex["prices"])
keep = [t for t in sorted(set(want)) if t in ex["prices"]]
if not keep:
    sys.exit("none of the requested symbols are in the median extract")
print(",".join(keep))' "${WORK}/extract-v1.json.gz" "${SYMBOLS:-}")"
# Solve slots for the target rate. Integer, at least 1 -- a universe wide enough to overshoot the
# band at one print per window is a reason to raise PRINT_REPLAY_STRIDE at runtime, not to build
# a fractional slot.
SLOTS="${SLOTS:-$(python3 -c '
import gzip, json, sys
ex = json.loads(gzip.open(sys.argv[1]).read())
n = len(sys.argv[2].split(","))
print(max(1, round(float(sys.argv[3]) * float(ex["windowSeconds"]) / (float(ex["compression"]) * n))))
' "${WORK}/extract-v1.json.gz" "${SYMBOLS}" "${TARGET_RATE}")}"
echo "[symbols] ${SYMBOLS//,/ }"
echo "[sizing] ${SLOTS} print(s) per window for a target of ${TARGET_RATE}/s"
# sed, not ${var//,/\',\'}: inside double quotes that substitution keeps the backslashes and
# BigQuery then parses \' as an escaped quote, so the IN list matches nothing and the export is
# header-only files. Same trap, same fix, as the median builder records.
SYMLIST="'$(printf '%s' "${SYMBOLS}" | sed "s/,/','/g")'"

cat > "${WORK}/def.json" <<'EOF'
{"sourceFormat":"PARQUET",
 "sourceUris":["gs://traderx-501015-tick-store/ticks/source=taq/*"],
 "hivePartitioningOptions":{"mode":"AUTO",
   "sourceUriPrefix":"gs://traderx-501015-tick-store/ticks/"}}
EOF

# ROUND TO 3dp: the equity book's tickPx is 1000 ($0.001), so a 4dp price is OFF-GRID and the
# venue refuses it INVALID. The median builder learned this the expensive way; a replayed order is
# the same order the engine judges, so it obeys the same grid.
#
# The pick is `first print of each rank slot`, deterministic under the ORDER BY. A window with
# fewer prints than SLOTS yields fewer rows — the assembler leaves those slots EMPTY rather than
# forward-filling, because a forward-filled print is not a print that happened.
cat > "${WORK}/sample.sql" <<EOF
EXPORT DATA OPTIONS(
  uri='${ROWS_URI}/prints-*.csv',
  format='CSV', overwrite=true, header=true) AS
WITH t AS (
  SELECT symbol, dt,
    DIV(TIMESTAMP_DIFF(ts, TIMESTAMP(CONCAT(dt, ' 09:30:00')), SECOND), ${WINDOW}) AS win,
    price,
    ROW_NUMBER() OVER (PARTITION BY symbol, dt,
      DIV(TIMESTAMP_DIFF(ts, TIMESTAMP(CONCAT(dt, ' 09:30:00')), SECOND), ${WINDOW})
      ORDER BY ts, price, size) AS rn,
    COUNT(*) OVER (PARTITION BY symbol, dt,
      DIV(TIMESTAMP_DIFF(ts, TIMESTAMP(CONCAT(dt, ' 09:30:00')), SECOND), ${WINDOW})) AS n
  FROM taq
  WHERE event_type = 'trade'
    AND symbol IN (${SYMLIST})
    AND ts >= TIMESTAMP(CONCAT(dt, ' 09:30:00'))
    AND ts <  TIMESTAMP(CONCAT(dt, ' 16:00:00'))
)
SELECT symbol, dt, win, DIV((rn - 1) * ${SLOTS}, n) AS slot,
       ROUND(ANY_VALUE(price HAVING MIN rn), 3) AS price
FROM t
GROUP BY symbol, dt, win, slot
EOF

# CLEAR THE EXPORT PREFIX FIRST. `EXPORT DATA ... overwrite=true` overwrites the files it writes,
# not the prefix: a previous run that produced MORE shards leaves the extras behind, and the
# assembler then unions two different samples into one plane. Measured 2026-08-26 — a 23-symbol
# 4-slot run assembled as "100 symbols x 4 slots, 33% filled" and only the Secret size guard
# caught it, because a partly-filled plane is otherwise a perfectly plausible artifact.
echo "[bq] clearing ${ROWS_URI}"
gcloud storage rm -r "${ROWS_URI}" >/dev/null 2>&1 || true

echo "[bq] sampling ${SLOTS} prints per ${WINDOW}s window (in-region, EXPORT DATA -> ${ROWS_URI})"
CLOUDSDK_CORE_ACCOUNT="${CLOUDSDK_CORE_ACCOUNT:-yaakov.traderx@gmail.com}" \
  bq --project_id=traderx-501015 --location=us-east1 query --use_legacy_sql=false --quiet \
     --external_table_definition="taq::${WORK}/def.json" "$(cat "${WORK}/sample.sql")"

echo "[fetch] sampled rows (the only bytes that leave the bucket)"
rm -f "${WORK}/rows/"*.csv
gcloud storage cp "${ROWS_URI}/*" "${WORK}/rows/" >/dev/null

python3 "${HERE}/build-taq-print-sample.py" \
  --rows "${WORK}/rows" --extract "${WORK}/extract-v1.json.gz" \
  --out "${WORK}/prints-v1.bin.gz" --window "${WINDOW}" --slots "${SLOTS}"

echo "[upload] ${SAMPLE_URI}"
gcloud storage cp "${WORK}/prints-v1.bin.gz" "${SAMPLE_URI}"
echo "[ok] done; the print-rows/ prefix in the bucket is scratch and safe to delete"
