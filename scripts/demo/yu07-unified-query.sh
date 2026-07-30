#!/usr/bin/env bash
# YU07 — PROOF (the money demo): live-captured ticks and normalized NYSE TAQ data land in ONE unified
# Parquet schema, and a SINGLE DuckDB query reads across both by `source` (SC-TS04, FR-TS06/07).
# Dependency-free: no cluster, no GCS, no external TAQ file — uses the component's own capture mapping
# + the real TAQ sample rows bundled in its tests. Writes to a local temp dir.
#
# Prereq: the tick-store venv from the self-check (python3 -m venv .venv && pip install -r requirements.txt).
# Usage: bash yu07-unified-query.sh
set -uo pipefail
TS=${TICK_STORE_DIR:-$(cd "$(dirname "$0")/../../specs/YU07-historical-tick-store/generation/runtime-overrides/tick-store" && pwd)}
cd "$TS" || { echo "tick-store dir not found: $TS"; exit 1; }
[ -d .venv ] && . .venv/bin/activate

python3 - <<'PY'
import os, sys, tempfile, duckdb
sys.path.insert(0, "."); sys.path.insert(0, "tests")
import capture, ingest_taq_quotes, ingest_taq_trades
from test_ingest_taq_quotes import SAMPLE_CQ_CSV   # real Feb-2025 TAQ CQ sample rows

# a TAQ Consolidated-Trades (CT) sample for IBM, so VWAP(IBM) spans live AND historical trades
SAMPLE_CT_IBM = """DATE,TIME_M,EX,SYM_ROOT,SYM_SUFFIX,TR_SCOND,SIZE,PRICE,TR_STOPIND,TR_CORR,TR_SEQNUM,TR_SOURCE,TR_RF
2025-02-11,9:30:00.100000000,K,IBM,,@,200,136.40,N,00,9001,C,
2025-02-11,9:30:01.200000000,N,IBM,,@,400,136.55,N,00,9002,C,
"""

out = tempfile.mkdtemp(prefix="yu07-ticks-")
con = duckdb.connect()
seq = capture.SeqCounter()

# 1) LIVE capture path: map TraderX pricing + trade messages -> unified rows
live = [
    capture.price_tick_to_row("pricing.IBM", {"price": 136.50, "asOf": "2025-02-11T14:00:00.000Z"}, seq.next()),
    capture.trade_to_row({"security": "IBM", "quantity": 100, "price": 136.60, "updated": "2025-02-11T14:01:00.000Z"}, seq.next()),
    capture.trade_to_row({"security": "IBM", "quantity": 300, "price": 136.80, "updated": "2025-02-11T14:02:00.000Z"}, seq.next()),
]
capture.write_batch(con, live, out)
print(f"   {'live capture (pricing.* + trades)':<36} {len(live)} rows  source=live")

# 2) TAQ quotes: normalize a real Consolidated-Quotes CSV sample -> same schema
csvq = os.path.join(out, "cq.csv"); open(csvq, "w").write(SAMPLE_CQ_CSV)
nq = ingest_taq_quotes.ingest(con, csvq, out)
print(f"   {'TAQ normalize (Consolidated Quotes)':<36} {nq} rows  source=taq event_type=quote")

# 3) TAQ trades: normalize a Consolidated-Trades CSV sample -> same schema (FR-TS08)
csvt = os.path.join(out, "ct.csv"); open(csvt, "w").write(SAMPLE_CT_IBM)
nt = ingest_taq_trades.ingest(con, csvt, out)
print(f"   {'TAQ normalize (Consolidated Trades)':<36} {nt} rows  source=taq event_type=trade")

# 3) ONE query over BOTH sources — the unified store
print("   ── one DuckDB query over the whole store (source=live + source=taq) ──")
rows = con.execute(f"""
    SELECT source, event_type, count(*) AS rows, count(DISTINCT symbol) AS symbols
    FROM read_parquet('{out}/**/*.parquet', hive_partitioning=true)
    GROUP BY source, event_type ORDER BY source, event_type
""").fetchall()
for src, et, r, s in rows:
    print(f"      source={src:<5} event_type={et:<11} rows={r}  symbols={s}")

# 4) a real VWAP on the live trades, same store (event_type='trade')
vwap = con.execute(f"""
    SELECT symbol, sum(price*size)/sum(size) AS vwap, sum(size) AS vol
    FROM read_parquet('{out}/**/*.parquet', hive_partitioning=true)
    WHERE event_type='trade' GROUP BY symbol
""").fetchone()
print(f"   {'VWAP(IBM) across the store':<34} {vwap[1]:.4f}  (volume {vwap[2]})")
print(f"   → live + TAQ, one schema, one query ✔")
PY
