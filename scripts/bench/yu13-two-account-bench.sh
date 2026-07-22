#!/usr/bin/env bash
# yu13-two-account-bench.sh — the rig for the STP before/after comparison.
#
# WHY THIS EXISTS. Every benchmark this project has ever run used ONE account with
# SIDES=alternate, i.e. buys and sells from the same account. Every fill in the whole corpus was a
# self-trade. Under any self-trade-prevention policy those load shapes book ZERO fills, so no
# post-STP number can be compared against any of them. This script is the replacement rig: the same
# order sequence (same sides, ticker, quantities, prices, batch structure) with the ONLY change
# being accountId, so a before/after delta isolates STP rather than the load shape.
#
# IT ALSO WIPES THE EPOCH, DELIBERATELY. The members mount PersistentVolumeClaims, so a rolling
# StatefulSet restart RECOVERS from the archive: the leader, the epoch and the resting book all
# survive an image change. (The 7-22 brief assumed a member roll clears the ~107k orphaned resting
# orders — it does not; only deleting the PVCs does.) A benchmark started against a 107k standing
# book is not comparable with one started against an empty book, so both sides of the comparison
# start from a wiped epoch. That is what makes "same rig" true rather than asserted.
#
# Usage:
#   ./yu13-two-account-bench.sh --image traderx/cluster-node:yu15-pre --label pre-STP
#   ./yu13-two-account-bench.sh --image traderx/cluster-node:yu15-stp --label post-STP
#   ...--no-roll   reuse the cluster as-is (second run against the same image)
set -euo pipefail

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
IN_CLUSTER_URL="http://order-matcher.${NS}.svc.cluster.local:18110"
BENCH_POD="${BENCH_POD:-bench-load}"

IMAGE=""
LABEL="run"
ROLL=1
# kind on a Docker VM has real run-to-run variance (a 30s run measured 22.8k and then 5.3k
# orders/s on the SAME image), so a single run cannot support a before/after claim. Three runs,
# report all three, compare medians.
RUNS="${RUNS:-3}"
SECS="${SECS:-30}"
BATCH="${BATCH:-1000}"
CONC="${CONC:-48}"
TICKER="${TICKER:-JPM}"
QTY="${QTY:-500}"
# limitPrice is a DOLLAR price (the gateway multiplies by 1e6), and both sides of every pair use
# it, so any value crosses. batch-load.mjs's own default of 1_000_000 is "deep in the money" for a
# ~$150 market — but on the cluster tier it also means $500,000,000 of notional per fill against
# CREDIT_LIMIT_TICKS = Long.MAX/4, which walls BOTH accounts after exactly 4,611 fills each.
# Measured: a 30s run booked 9,222 fills and then rejected all 296,000 remaining orders with the
# whole cluster still reporting 2xx. $1.00 moves that wall out to ~4.6e9 fills.
LIMIT="${LIMIT:-1}"
# Two of the seven real SQL accounts. Real ones matter: trades.accountid has a foreign key to
# accounts, so a synthetic id books fine in the cluster and is then silently dropped by
# trade-processor (the second instance of that failure class in this project).
ACCT_A="${ACCT_A:-42422}"
ACCT_B="${ACCT_B:-22214}"
PRICE="${PRICE:-1.00}"   # matches LIMIT=1000000 ticks, so orders sit on the band anchor

while [[ $# -gt 0 ]]; do
  case "$1" in
    --image) IMAGE="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    --no-roll) ROLL=0; shift ;;
    --secs) SECS="$2"; shift 2 ;;
    --runs) RUNS="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

PF_PID=""
PF_PORT="${MATCHER_URL##*:}"
stop_pf() {
  if [[ -n "${PF_PID}" ]]; then kill "${PF_PID}" 2>/dev/null || true; wait "${PF_PID}" 2>/dev/null || true; fi
  PF_PID=""
}
start_pf() {
  stop_pf
  ${K} port-forward svc/order-matcher "${PF_PORT}:18110" >/dev/null 2>&1 &
  PF_PID=$!
  local tries=0
  until curl -sf --max-time 5 "${MATCHER_URL}/ready" >/dev/null 2>&1; do
    tries=$((tries + 1))
    [[ ${tries} -lt 90 ]] || fail "gateway never became reachable through a fresh port-forward"
    kill -0 "${PF_PID}" 2>/dev/null || { ${K} port-forward svc/order-matcher "${PF_PORT}:18110" >/dev/null 2>&1 & PF_PID=$!; }
    sleep 2
  done
}
trap stop_pf EXIT

# One member's counters, straight off its own metrics endpoint. applied = consensus log position,
# trades = the engine's own trade counter (YU13 books BOTH sides of a match, so this counts fills).
member_counters() { # -> "<applied> <trades> <nextOrderRef> <openOrders> <orderHash>"
  ${K} exec "order-matcher-cluster-$1" -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null || curl -s http://localhost:8080/metrics' \
    | awk '/^traderx_cluster_applied/{a=$2} /^traderx_cluster_trades/{t=$2} \
           /^traderx_cluster_next_order_ref/{n=$2} \
           /^traderx_book_open_orders/{d=$2} /^traderx_book_order_hash/{h=$2} END{print a, t, n, d, h}'
}

gw_metric() { curl -sf "${MATCHER_URL}/metrics" | awk -v k="$1" '$0 ~ k {print $2}' | head -1; }

if [[ ${ROLL} -eq 1 ]]; then
  [[ -n "${IMAGE}" ]] || fail "--image is required unless --no-roll"
  step "1. roll members + gateway to ${IMAGE} with a WIPED epoch"
  kind load docker-image "${IMAGE}" --name "${CTX#kind-}" >/dev/null
  ${K} set image statefulset/order-matcher-cluster "$(${K} get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].name}')=${IMAGE}" >/dev/null
  ${K} set image deployment/cluster-gateway "$(${K} get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].name}')=${IMAGE}" >/dev/null
  ${K} scale statefulset/order-matcher-cluster --replicas=0 >/dev/null
  ${K} wait --for=delete pod -l app=order-matcher-cluster --timeout=180s >/dev/null 2>&1 || true
  for i in 0 1 2; do ${K} delete pvc "data-order-matcher-cluster-${i}" --wait=true >/dev/null; done
  ${K} scale statefulset/order-matcher-cluster --replicas=3 >/dev/null
  ${K} rollout status statefulset/order-matcher-cluster --timeout=300s
  ${K} rollout restart deployment/cluster-gateway >/dev/null
  ${K} rollout status deployment/cluster-gateway --timeout=300s
fi

start_pf

step "2. seed both accounts, the ticker and its price (all through the sequenced ingress)"
for acct in "${ACCT_A}" "${ACCT_B}"; do
  out=$(curl -sf -X POST "${MATCHER_URL}/seed" -H 'content-type: application/json' \
    -d "{\"accountId\":${acct},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}") \
    || fail "seed of account ${acct} failed"
  echo "  account ${acct}: ${out}"
  [[ "${out}" == *'"seeded":true'* ]] || fail "account ${acct} not seeded: ${out}"
done

step "3. copy the load generator into ${BENCH_POD}"
${K} cp "$(dirname "$0")/batch-load.mjs" "${BENCH_POD}:/tmp/batch-load.mjs"

for run in $(seq 1 "${RUNS}"); do

step "4. run ${run}/${RUNS}: baseline counters (all three members)"
declare -a C0
for m in 0 1 2; do C0[$m]="$(member_counters "$m")"; echo "  member ${m}: ${C0[$m]}"; done
GW_FILL0="$(gw_metric 'event="fill"')"; GW_ACC0="$(gw_metric 'event="accepted"')"
T0=$(python3 -c 'import time; print(time.time())')

step "5. two-account load: ${SECS}s, batch=${BATCH} conc=${CONC} ticker=${TICKER} qty=${QTY} accounts=${ACCT_A}/${ACCT_B}"
GEN_OUT=$(${K} exec "${BENCH_POD}" -- env \
  MATCHER_URL="${IN_CLUSTER_URL}" SIDES=alternate ACCOUNTS="${ACCT_A},${ACCT_B}" \
  TICKERS="${TICKER}" QTY="${QTY}" LIMIT="${LIMIT}" \
  node /tmp/batch-load.mjs --batch "${BATCH}" --conc "${CONC}" --secs "${SECS}" | tail -3)
echo "${GEN_OUT}"

# Quiesce before sampling. The load stops at the gateway, but the members are still applying the
# committed tail, and they do it at different rates — sampling immediately made all three disagree
# on nextOrderRef and the book hash, which reads exactly like a determinism failure and is not one.
# A cross-member digest comparison is only meaningful once every member has stopped moving.
step "6. quiesce (members finish applying the committed tail)"
prev=""; stable=0
for _ in $(seq 1 120); do
  now="$(for m in 0 1 2; do member_counters "$m" | cut -d' ' -f1; done | tr '\n' ' ')"
  if [[ "${now}" == "${prev}" ]]; then stable=$((stable + 1)); else stable=0; fi
  prev="${now}"
  [[ ${stable} -ge 2 ]] && break
  sleep 1
done
[[ ${stable} -ge 2 ]] || fail "members never quiesced: ${prev}"
echo "  quiesced at applied = ${prev}"
# Counters are sampled AFTER the drain, so the window must include it: rates are total work over
# load-plus-drain. Slightly conservative, and identical on both sides of the comparison.
T1=$(python3 -c 'import time; print(time.time())')

step "6b. post-run counters"
declare -a C1
for m in 0 1 2; do C1[$m]="$(member_counters "$m")"; echo "  member ${m}: ${C1[$m]}"; done
GW_FILL1="$(gw_metric 'event="fill"')"; GW_ACC1="$(gw_metric 'event="accepted"')"

step "7. RESULT — ${LABEL} (run ${run}/${RUNS})"
ELAPSED=$(python3 -c "print(${T1} - ${T0})")
python3 - "$ELAPSED" "${C0[0]}" "${C1[0]}" "${C0[1]}" "${C1[1]}" "${C0[2]}" "${C1[2]}" <<'PY'
import sys
elapsed = float(sys.argv[1])
rows = [(sys.argv[2 + 2 * i].split(), sys.argv[3 + 2 * i].split()) for i in range(3)]
print(f"  elapsed          {elapsed:.1f}s")
for m, (a, b) in enumerate(rows):
    applied = int(b[0]) - int(a[0])
    trades = int(b[1]) - int(a[1])
    orders = int(b[2]) - int(a[2])
    resting = int(b[3])
    # An order that neither filled nor rests was rejected. Load it bears watching: the harness's
    # own 2xx rate cannot see this, because egress acks are best-effort and drop under flood.
    unaccounted = orders - trades - resting
    print(f"  member {m}: orders {orders:>9} ({orders/elapsed:>9,.0f}/s)  "
          f"fills {trades:>9} ({trades/elapsed:>9,.0f}/s)  "
          f"applied {applied:>9} ({applied/elapsed:>9,.0f}/s)  "
          f"resting {resting:>7}  neither {unaccounted:>8}")
tails = [tuple(b[2:]) for _, b in rows]
print("  member agreement (nextRef, resting, bookHash):",
      "IDENTICAL" if len(set(tails)) == 1 else f"DIVERGED {tails}")
PY
echo "  gateway accepted delta: $(( GW_ACC1 - GW_ACC0 ))   fill-ack delta: $(( GW_FILL1 - GW_FILL0 ))"
echo "  gateway STP-cancel acks: $(gw_metric 'event="self_trade_prevented"' || echo 'n/a (pre-STP image)')"

done
