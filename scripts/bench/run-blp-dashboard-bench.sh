#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:18110}"
WARMUP_ORDERS="${WARMUP_ORDERS:-500000}"
MEASURED_ORDERS="${MEASURED_ORDERS:-40000000}"
RING_SIZE="${RING_SIZE:-65536}"
WAIT_STRATEGY="${WAIT_STRATEGY:-yielding}"

trigger_url="${BASE_URL}/system/benchmarks/blp/run?warmupOrders=${WARMUP_ORDERS}&measuredOrders=${MEASURED_ORDERS}&ringSize=${RING_SIZE}&waitStrategy=${WAIT_STRATEGY}"
status_url="${BASE_URL}/system/benchmarks/blp"

echo "[bench] triggering pure BLP benchmark"
curl -fsS -X POST "${trigger_url}"
echo

while true; do
  status_json="$(curl -fsS "${status_url}")"
  echo "[bench] ${status_json}"
  if printf '%s' "${status_json}" | grep -q '"phase":"complete"\|"phase":"failed"'; then
    break
  fi
  sleep 1
done
