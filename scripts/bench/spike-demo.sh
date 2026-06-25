#!/usr/bin/env bash
# spike-demo.sh — drive repeated order/fill bursts through the *currently running*
# order-matcher so the "TraderX Trades/sec — 009 vs 009b" Grafana dashboard shows a
# live spike you can actually watch.
#
# Works against whichever state is up (009 or 009b). Reuses the tuned bench driver
# (order-matcher-bench.mjs): correct served tickers + a descending price ramp that
# crosses every resting order, so each burst produces a clean wave of fills.
#
# Usage:  ./spike-demo.sh [rounds] [orders] [gap_seconds]
#   rounds  number of bursts, or 0 = loop until Ctrl-C    (default 0)
#   orders  resting Buy orders per burst                  (default 500)
#   gap     pause between bursts, seconds                 (default 4)
#
# On 009b each burst clears in ~1s → a tall, narrow spike every (gap) seconds.
# On 009  each burst drains over minutes → a long, low plateau (run rounds=1).
#
# Watch it live:  http://localhost:8080/grafana/d/traderx-trades-per-second
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

ROUNDS="${1:-0}"
ORDERS="${2:-500}"
GAP="${3:-4}"

export MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
export NATS_URL="${NATS_URL:-nats://localhost:4222}"
ACCOUNT="${ACCOUNT:-42422}"
PROM_URL="${PROM_URL:-http://localhost:9090}"
GRAFANA="${GRAFANA_URL:-http://localhost:8080/grafana/d/traderx-trades-per-second}"

if ! command -v node >/dev/null 2>&1; then
  echo "[spike] error: node not found (need Linux node; nvm + Node 22)." >&2
  exit 1
fi

# Identify the running matcher (best effort).
state="unknown — is the stack up?"
if health="$(curl -s --max-time 5 "${MATCHER_URL}/health" 2>/dev/null)"; then
  if grep -q '"lmax"' <<<"${health}"; then
    state="009b (LMAX hot path — expect a fast, tall spike)"
  else
    state="009 (poll/JPA — expect a slow ~3min plateau; try rounds=1)"
  fi
fi

echo "[spike] matcher  : ${MATCHER_URL}  ->  ${state}"
echo "[spike] dashboard: ${GRAFANA}"
echo "[spike] rounds=$([[ "${ROUNDS}" == 0 ]] && echo 'infinite (Ctrl-C to stop)' || echo "${ROUNDS}")  orders=${ORDERS}  gap=${GAP}s"
echo

# Best-effort: peak trades/sec over the last 2 minutes, straight from Prometheus.
peak() {
  curl -s --max-time 5 --data-urlencode \
    'query=max_over_time(sum(irate(traderx_order_events_total{event=~"fill|partial_fill|force_fill"}[30s]))[2m:1s])' \
    "${PROM_URL}/api/v1/query" 2>/dev/null \
    | python3 -c 'import sys,json;r=json.load(sys.stdin)["data"]["result"];print(("%.0f"%float(r[0]["value"][1])) if r else "n/a")' 2>/dev/null \
    || echo "n/a"
}

# Cancel any open orders left on the account. The bench driver was built for a freshly
# generated stack and hard-fails if the account is not clean; reused in a loop, orders
# that never crossed (their limit sits below the tickers' ambient price band) accumulate.
# Flushing before each burst keeps every round clean. Cancels do NOT count as trades.
flush_account() {
  local ids n
  ids="$(curl -s --max-time 10 "${MATCHER_URL}/orders?status=open&accountId=${ACCOUNT}" 2>/dev/null \
    | python3 -c 'import sys,json
try: d=json.load(sys.stdin)
except Exception: d=[]
[print(o["orderId"]) for o in d if o.get("orderId")]' 2>/dev/null)" || return 0
  [[ -z "${ids}" ]] && return 0
  n="$(printf '%s\n' "${ids}" | grep -c .)"
  echo "[spike] flushing ${n} leftover open order(s) on account ${ACCOUNT}…"
  printf '%s\n' "${ids}" | xargs -P 16 -I {} curl -s -o /dev/null -X POST "${MATCHER_URL}/orders/{}/cancel"
}

trap 'echo; echo "[spike] stopped after ${i:-0} burst(s)."; exit 0' INT

i=0
while :; do
  i=$((i + 1))
  echo "── burst #${i} ──────────────────────────────"
  flush_account
  BENCH_ACCOUNT="${ACCOUNT}" BENCH_ORDERS="${ORDERS}" BENCH_TICKS=6000 BENCH_TICK_RATE=25000 \
  BENCH_QTY_A=500 BENCH_QTY_B=500 \
    node order-matcher-bench.mjs --state spike-demo --out /tmp/traderx-spike/run.json \
    | grep -E '^\[bench\]' || echo "[spike] burst failed (see above) — continuing"
  echo "[spike] peak trades/sec (last 2m): $(peak)"
  if [[ "${ROUNDS}" != 0 && "${i}" -ge "${ROUNDS}" ]]; then
    break
  fi
  sleep "${GAP}"
done

echo
echo "[spike] done after ${i} burst(s)."
