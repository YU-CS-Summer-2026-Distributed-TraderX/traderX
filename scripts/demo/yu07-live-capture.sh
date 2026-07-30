#!/usr/bin/env bash
# YU07 — PROOF: capture TraderX's OWN live ticks off the running cluster, zero hot-path impact
# (a passive NATS subscriber on pricing.* + /accounts/*/trades — FR-TS01/02, NFR-TS01). Captures to a
# local Parquet store (no GCS needed), then a DuckDB query reads back the real captured rows.
#
# Prereq: nats-broker port-forward to :4222 (against a running cluster with the price feed):
#   kubectl port-forward -n traderx svc/nats-broker 4222:4222 --context kind-traderx-state-014
# + the tick-store venv (self-check setup). Usage: bash yu07-live-capture.sh [seconds]
set -uo pipefail
SECS=${1:-25}
NATS=${NATS_URL:-nats://localhost:4222}
OUT=${TICKSTORE_OUT_DIR:-/tmp/yu07-live-$(date +%s)}
TS=${TICK_STORE_DIR:-$(cd "$(dirname "$0")/../../specs/YU07-historical-tick-store/generation/runtime-overrides/tick-store" && pwd)}
cd "$TS" || { echo "tick-store dir not found: $TS"; exit 1; }
[ -d .venv ] && . .venv/bin/activate

echo "── LIVE CAPTURE off the cluster (pricing.* + trades → Parquet) ──"
printf "   %-30s %s\n" "subscribing to NATS" "$NATS"
printf "   %-30s %ss (flush every 10s), out=%s\n" "capturing" "$SECS" "$OUT"
# short flush interval so we get a partition within the run. macOS has no `timeout`, so run in the
# background and SIGTERM it (capture flushes on the interval, so partitions land before we stop it).
NATS_URL="$NATS" TICKSTORE_OUT_DIR="$OUT" TICKSTORE_FLUSH_INTERVAL_SECONDS=10 \
  python3 capture.py >/tmp/yu07-capture.log 2>&1 &
CPID=$!
sleep "$SECS"
kill "$CPID" 2>/dev/null; wait "$CPID" 2>/dev/null || true
grep -iE "connect|subscrib|flush|captur|error|traceback" /tmp/yu07-capture.log | tail -5 | sed 's/^/      /'

echo "   ── DuckDB over the captured store ──"
python3 - "$OUT" <<'PY'
import sys, glob, duckdb
out = sys.argv[1]
files = glob.glob(f"{out}/**/*.parquet", recursive=True)
if not files:
    print("      no Parquet captured — is the price feed publishing? check /tmp/yu07-capture.log"); sys.exit(0)
con = duckdb.connect()
q = f"read_parquet('{out}/**/*.parquet', hive_partitioning=true)"
tot = con.execute(f"SELECT count(*), count(DISTINCT symbol) FROM {q}").fetchone()
print(f"      captured rows={tot[0]}  symbols={tot[1]}  files={len(files)}")
for src, et, r in con.execute(f"SELECT source, event_type, count(*) FROM {q} GROUP BY source, event_type ORDER BY 3 DESC").fetchall():
    print(f"      source={src:<5} event_type={et:<11} rows={r}")
print("      sample rows:")
for sym, et, price, ts in con.execute(f"SELECT symbol, event_type, price, ts FROM {q} ORDER BY seq LIMIT 4").fetchall():
    print(f"        {sym:<6} {et:<11} price={price} ts={ts}")
print("   → real cluster ticks captured to the unified schema ✔")
PY
