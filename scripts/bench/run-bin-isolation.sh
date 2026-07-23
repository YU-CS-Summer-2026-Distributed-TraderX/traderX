#!/usr/bin/env bash
# run-bin-isolation.sh — Phase 0 isolation proof for the compiled binary generator.
#
# Starts BinEcho (a drain-only ACK target) on loopback and blasts BinGen at it, so the reported number
# is the GENERATOR's own offer ceiling with NO cluster in the path. Deliverable: "this generator
# sustains N/s offered against an echo, N >> 12k" — the whole point being that any later cluster number
# is known to sit below the generator's offer rate. A ceiling measured with a generator that caps first
# is worthless (that is the 12k lesson).
#
#   SESSIONS=32 SECS=20 BATCH=256 bash scripts/bench/run-bin-isolation.sh
set -euo pipefail
cd "$(dirname "$0")"

PORT="${PORT:-18140}"
SESSIONS="${SESSIONS:-32}"
SECS="${SECS:-20}"
BATCH="${BATCH:-256}"
OUT="results/$(date -u +%Y%m%dT%H%M%SZ)-bin-isolation-${SESSIONS}conn-${SECS}s.log"
mkdir -p results

echo "compiling BinEcho.java BinGen.java (javac $(javac -version 2>&1))"
javac -d . BinEcho.java BinGen.java

echo "starting echo on :$PORT"
PORT="$PORT" java BinEcho >"$OUT.echo" 2>&1 &
ECHO_PID=$!
trap 'kill "$ECHO_PID" 2>/dev/null || true' EXIT

# Wait for the echo port to actually accept before blasting.
for _ in $(seq 1 50); do
  if nc -z localhost "$PORT" 2>/dev/null; then break; fi
  sleep 0.1
done

echo "generator -> loopback echo (:$PORT), $SESSIONS conn, ${SECS}s, batch $BATCH, mode ${MODE:-blast}"
GATEWAYS="localhost:$PORT" SESSIONS="$SESSIONS" SECS="$SECS" BATCH="$BATCH" \
  MODE="${MODE:-blast}" TOTAL="${TOTAL:-0}" RATE="${RATE:-50}" \
  java BinGen | tee "$OUT"

echo
echo "echo drain heartbeat (tail):"
tail -n 4 "$OUT.echo" || true
echo "result log: $OUT"
