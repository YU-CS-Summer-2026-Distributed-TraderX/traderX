#!/usr/bin/env bash
# ADR-070: build the resampled TAQ replay extract — IN BIGQUERY, so no byte of the corpus ever
# reaches a laptop or a node. A BigQuery external table over the parquet tree (hive-partitioned,
# columnar: only price/event_type/symbol/dt are read) computes the exact per-window medians and
# EXPORT DATA writes them bucket-internally; the only thing that crosses the network is the
# ~2.7 MiB of resampled rows this script assembles into the ~300 KB extract and uploads back.
#
# Measured 2026-08-26: the full 23-symbol x 40-day resample ran in ~50s and scanned ~5 GiB —
# inside BigQuery's free tier, effectively $0 (vs ~29 GiB / ~2h / ~$3.50 for a laptop pull of the
# partitions, the approach this replaced). ADR-070 decision 1's "computed once, in-region, at
# zero egress" is literally true on this path.
#
# Needs: gcloud + bq authenticated as an identity with bigquery.jobs.create on traderx-501015
# (yaakov.traderx@gmail.com; exkjell@ has almost no access to 501015 — set
# CLOUDSDK_CORE_ACCOUNT rather than switching the shared gcloud config), python3 (stdlib only).
set -euo pipefail

BUCKET="${BUCKET:-gs://traderx-501015-tick-store}"
EXTRACT_URI="${EXTRACT_URI:-${BUCKET}/replay/taq-replay-2025-02/extract-v1.json.gz}"
ROWS_URI="${BUCKET}/replay/taq-replay-2025-02/rows"
WORK="${WORK:-${TMPDIR:-/tmp}/taq-replay-build}"
WINDOW="${WINDOW:-195}"
COMPRESSION="${COMPRESSION:-13}"
# THE REPLAYED UNIVERSE — 100 symbols, widened from 23 on 2026-08-26.
#
# Built as a union of two sets, and it has to stay a union: taking the ranking alone would DROP
# thirteen names that are already replaying on the rig (IBM, GS, UBS, DB, COF, DFS, FIS, FNF and
# the five ETFs), silently reverting each to the synthetic walk.
#
#   * the 23 incumbents, so no name that is on the tape today comes off it;
#   * the 77 most liquid S&P 500 names not already in that set.
#
# Liquidity is ranked by DAY-1 PARTITION BYTE SIZE under
# gs://.../ticks/source=taq/dt=2025-02-03/symbol=<T>/ — a proxy, and a defensible one: the parquet
# is one row per print, so bytes are prints. One recursive listing of that single day (20,402
# objects) ranks all 10,081 tape symbols with no query and no download. The cut lands at LUV,
# 3.9 MiB against a 1.48 MiB all-symbol mean.
#
# EXCLUDED, and neither exclusion is a preference:
#
#   * the dual-class roots GOOG/GOOGL, BRK, BF and CMCS/CMCSA. The ingest dropped TAQ's SYM_SUFFIX,
#     so those partitions merge two securities and a median over them is a price for no security
#     that exists — issues/open/tick-store-drops-taq-sym-suffix-and-merges-share-classes.md.
#   * AMD and ANET, because BigQuery CANNOT READ THEM. Both carry 41 truncated copies of one
#     dt=2025-03-11 object (no PAR1 footer), and one unreadable file fails the whole external-table
#     scan — issues/open/two-symbols-are-unreadable-on-the-oom-retry-day.md. BA and AFL take their
#     places in the ranking.
#
# FNMA is excluded by the corpus itself (OTC, not in TAQ). All of them keep the walk.
SYMBOLS="${SYMBOLS:-AAL,AAPL,ADBE,AEP,AFL,AMCR,AMZN,APA,AVGO,BA,BAC,BAX,BEN,BKR,BMY,C,CARR,CCL,CMG,COF,COP,CSCO,CSX,CTRA,CVX,D,DAL,DB,DFS,DIS,DOC,DOW,DVN,F,FCX,FIS,FITB,FNF,FOXA,GILD,GLD,GLW,GM,GS,HAL,HBAN,HPE,HPQ,HST,IBM,INTC,IPG,IVZ,IWM,JPM,KEY,KHC,KMI,KO,KR,LRCX,LUV,MCHP,MDLZ,META,MO,MRK,MRNA,MS,MSFT,MU,NCLH,NEE,NEM,NFLX,NKE,NVDA,O,OXY,PFE,PG,PYPL,QQQ,RF,SBUX,SLB,SPY,T,TFC,TSLA,UBS,UPS,USB,VFC,VTI,VZ,WFC,WMB,WMT,XOM}"
HERE="$(cd "$(dirname "$0")" && pwd)"

mkdir -p "${WORK}/rows"
# sed, not ${var//,/\',\'}: inside double quotes that substitution keeps the backslashes, the SQL
# gets 'AAPL\',\'MSFT\'…, BigQuery parses \' as an escaped quote, the IN list matches nothing and
# the export is 21 header-only files — which the assembler refuses loudly (measured 2026-08-26).
SYMLIST="'$(printf '%s' "${SYMBOLS}" | sed "s/,/','/g")'"

cat > "${WORK}/def.json" <<'EOF'
{"sourceFormat":"PARQUET",
 "sourceUris":["gs://traderx-501015-tick-store/ticks/source=taq/*"],
 "hivePartitioningOptions":{"mode":"AUTO",
   "sourceUriPrefix":"gs://traderx-501015-tick-store/ticks/"}}
EOF

# Exact median (PERCENTILE_CONT, midpoint on even counts — the same statistic pandas' median
# gives), regular hours only, one row per (symbol, day, window). `ts` is ET wall-clock tz-naive
# in the store, so the 09:30/16:00 bounds compare in the same naive space.
#
# ROUND TO 3dp, NOT 4 — the venue's price grid. The equity book's tickPx is 1000 ($0.001), and
# every proof fixture derives its order prices FROM the published reference, so a 4dp median
# makes the venue refuse the fixture's own hold order INVALID (measured 2026-08-26: seed-proof-
# fixtures held AAPL at 229.8112, off-grid, and the whole suite ran against a partly-seeded rig).
# 3dp matches Px.SCALE's edge rounding and round3 everywhere else in the publisher.
cat > "${WORK}/resample.sql" <<EOF
EXPORT DATA OPTIONS(
  uri='${ROWS_URI}/win-*.csv',
  format='CSV', overwrite=true, header=true) AS
SELECT DISTINCT
  symbol, dt,
  DIV(TIMESTAMP_DIFF(ts, TIMESTAMP(CONCAT(dt, ' 09:30:00')), SECOND), ${WINDOW}) AS win,
  ROUND(PERCENTILE_CONT(price, 0.5) OVER (PARTITION BY symbol, dt,
    DIV(TIMESTAMP_DIFF(ts, TIMESTAMP(CONCAT(dt, ' 09:30:00')), SECOND), ${WINDOW})), 3) AS median_price
FROM taq
WHERE event_type = 'trade'
  AND symbol IN (${SYMLIST})
  AND ts >= TIMESTAMP(CONCAT(dt, ' 09:30:00'))
  AND ts <  TIMESTAMP(CONCAT(dt, ' 16:00:00'))
EOF

echo "[bq] resampling ${SYMBOLS//,/ } at ${WINDOW}s windows (in-region, EXPORT DATA -> ${ROWS_URI})"
bq --project_id=traderx-501015 --location=us-east1 query --use_legacy_sql=false --quiet \
   --external_table_definition="taq::${WORK}/def.json" "$(cat "${WORK}/resample.sql")"

echo "[fetch] resampled rows (a few MiB — the only bytes that leave the bucket)"
rm -f "${WORK}/rows/"*.csv
gcloud storage cp "${ROWS_URI}/*" "${WORK}/rows/" >/dev/null

python3 "${HERE}/build-taq-replay-extract.py" \
  --rows "${WORK}/rows" --out "${WORK}/extract-v1.json.gz" \
  --window "${WINDOW}" --compression "${COMPRESSION}"

echo "[upload] ${EXTRACT_URI}"
gcloud storage cp "${WORK}/extract-v1.json.gz" "${EXTRACT_URI}"
echo "[ok] done; the rows/ prefix in the bucket is scratch and safe to delete"
