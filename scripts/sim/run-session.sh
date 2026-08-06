#!/usr/bin/env bash
# run-session.sh — drive a compressed trading session against the live cluster book, so the EOD risk
# extract has a real day behind it instead of a benchmark's residue.
#
# The order matcher is meant to be a machine executing all day with a snapshot cut from its state.
# Until now the only things that drove it were throughput benchmarks and a 120-order demo, which is
# why the extract reads flat and symmetric -- every costBasis exactly 200.000000, accounts holding
# exact mirror images of each other, everything netting to zero firm-wide. This runs distinct
# participants instead: a market maker, a momentum taker, a mean-reversion taker, and one
# institutional parent order sliced by the EXISTING YU08 execution-algo-engine.
#
# It does NOT make the prices real -- no market data is involved. It makes the price FORMATION real:
# the mark moves because someone lifted the offer and consumed depth. See session.mjs's header.
#
#   bash scripts/sim/run-session.sh                          # 10 minutes, 12 symbols
#   bash scripts/sim/run-session.sh --minutes 45 --symbols 20 --seed 7
#   PARENT_SYMBOL=IBM PARENT_QTY=12000 bash scripts/sim/run-session.sh
#
# Every other flag is passed straight through to session.mjs (--rate, --levels, --quote-size, ...).
#
# WHAT THIS SCRIPT OWNS, AND WHY EACH PIECE IS HERE
#   * The algo engine. It is scaled to 0 on this rig ON PURPOSE: its traffic moves next_order_ref
#     under the counter-exact proofs (yu13-readmodel-effect-end asserts the counter moves by exactly
#     2; the algo engine was observed moving it by 24 mid-proof). This scales it up for the parent
#     order and the EXIT trap scales it back to 0 -- including on Ctrl-C. A session that leaves it
#     running fails a suite about a system that is fine.
#   * The port-forwards, all three of them, killed by PID on exit. Not `pkill -f port-forward`:
#     these worktrees are shared by several lanes and that pattern kills someone else's tunnels.
#   * The symbol universe, read off the live price-publisher Deployment rather than duplicated here.
#     A security with no published price halts its holder's EOD P&L, and yu15-risk-extract asserts
#     halted=0 -- so an off-universe symbol fails a proof about a system that is behaving perfectly.
#
# AFTER THE SESSION, cut the EOD and check the extract:
#   bash scripts/proofs/yu15-risk-extract.sh
# Run it with the rig QUIET. The extract's step 4 asserts quiesceWitnessSequence == N+1, which is
# exactly the claim "nothing was sequenced during the build" -- any live agent traffic breaks it,
# correctly. This script exits before it prints that instruction, so simply do not start another.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && cd .. && pwd)"
CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"

MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
POSITIONS_URL="${POSITIONS_URL:-http://localhost:18090}"
PRICES_URL="${PRICES_URL:-http://localhost:18100}"

# Read --minutes for this script's own scheduling; everything is forwarded to node untouched.
MINUTES=10
for ((i = 1; i <= $#; i++)); do
  if [[ "${!i}" == "--minutes" ]]; then j=$((i + 1)); MINUTES="${!j:-10}"; fi
done

FORWARD_PIDS=()
PARENT_PID=""
ALGO_SCALED=0
cleanup() {
  if [[ "${ALGO_SCALED}" == "1" ]]; then
    echo "[sim] scaling execution-algo-engine back to 0 (it poisons the counter-exact proofs)"
    ${K} scale deploy/execution-algo-engine --replicas=0 >/dev/null 2>&1
  fi
  # The parent submitter is asleep until T+PARENT_DELAY. Kill it before waiting, or a Ctrl-C in the
  # first seconds of a session hangs this trap on its own `sleep`.
  [[ -n "${PARENT_PID}" ]] && kill "${PARENT_PID}" 2>/dev/null
  for pid in "${FORWARD_PIDS[@]:-}"; do [[ -n "${pid}" ]] && kill "${pid}" 2>/dev/null; done
  wait 2>/dev/null
}
trap cleanup EXIT INT TERM

# --- forwards ---------------------------------------------------------------------------------
# Start ours, then VERIFY by curl rather than by process: if another lane already holds a port, our
# bind fails harmlessly and the existing tunnel serves us just as well. Unreachable is the failure
# worth reporting, not "we did not start it".
echo "[sim] port-forwards: order-matcher 18110, position-service 18090, price-publisher 18100"
for pf in "svc/order-matcher 18110:18110" "svc/position-service 18090:18090" "svc/price-publisher 18100:18100"; do
  # shellcheck disable=SC2086
  ${K} port-forward ${pf} >/dev/null 2>&1 &
  FORWARD_PIDS+=("$!")
  disown 2>/dev/null || true
done
for _ in $(seq 1 30); do
  ready=1
  curl -sf -m3 -o /dev/null "${MATCHER_URL}/ready" || ready=0
  curl -sf -m3 -o /dev/null "${POSITIONS_URL}/positions/42422" || ready=0
  curl -sf -m3 -o /dev/null "${PRICES_URL}/prices/IBM" || ready=0
  [[ "${ready}" == "1" ]] && break
  sleep 2
done
[[ "${ready:-0}" == "1" ]] || {
  echo "[fail] forwards never all came up. Is the rig running?"
  echo "       bash scripts/yu15/start-cluster-kind.sh"
  exit 1
}

# --- fixtures ---------------------------------------------------------------------------------
# An account and a security exist on this tier only once SEQUENCED; without this every order comes
# back UNKNOWN_ACCOUNT / UNKNOWN_SECURITY. Idempotent, and it also re-anchors the marks that long
# runs drift (yu10-fix-session once rejected 1410 of 1426 orders purely from drifted marks).
echo "[sim] seeding accounts + securities (idempotent)"
bash "${ROOT}/scripts/yu15/seed-proof-fixtures.sh" >/dev/null 2>&1 \
  || { echo "[fail] seed-proof-fixtures.sh failed -- run it directly to see why"; exit 1; }

TICKERS="$(${K} get deploy price-publisher \
  -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="PRICE_TICKERS")].value}' 2>/dev/null)"
[[ -n "${TICKERS}" ]] || { echo "[fail] could not read PRICE_TICKERS off price-publisher"; exit 1; }

# --- the institutional parent ------------------------------------------------------------------
# Sliced by the YU08 engine, not by this script. The engine prices each child off price-publisher's
# reference x (1 +/- ALGO_LIMIT_OFFSET_BPS), which is the same level the session's book starts at,
# so the children genuinely interact with the maker's quotes rather than resting somewhere alone.
PARENT_ACCOUNT="${PARENT_ACCOUNT:-62654}"
PARENT_SYMBOL="${PARENT_SYMBOL:-${TICKERS%%,*}}"
PARENT_QTY="${PARENT_QTY:-6000}"
PARENT_SIDE="${PARENT_SIDE:-Buy}"
SECS=$((MINUTES * 60))
PARENT_DELAY=$((SECS * 15 / 100))                      # let the maker quote a book first
PARENT_BUCKET=$((SECS * 55 / 100 / 8)); ((PARENT_BUCKET < 5)) && PARENT_BUCKET=5
PARENT_DUR=$((PARENT_BUCKET * 8))

echo "[sim] scaling execution-algo-engine to 1 for the parent order"
${K} scale deploy/execution-algo-engine --replicas=1 >/dev/null 2>&1
ALGO_SCALED=1
${K} rollout status deploy/execution-algo-engine --timeout=300s >/dev/null 2>&1 \
  || echo "[warn] algo engine never became ready; the session runs without the parent order"

submit_parent() {
  sleep "${PARENT_DELAY}"
  # In-cluster via exec, so no fourth port-forward can die mid-schedule.
  out="$(${K} exec deploy/execution-algo-engine -- sh -c "curl -s -m30 -X POST \
    http://localhost:18120/algo/orders -H 'Content-Type: application/json' -d '{
      \"accountId\":${PARENT_ACCOUNT},\"security\":\"${PARENT_SYMBOL}\",\"side\":\"${PARENT_SIDE}\",
      \"quantity\":${PARENT_QTY},\"algoType\":\"TWAP\",
      \"durationSeconds\":${PARENT_DUR},\"bucketSeconds\":${PARENT_BUCKET}}'" 2>/dev/null)"
  case "${out}" in
    *parentOrderId*) echo "[sim] parent accepted: ${PARENT_ACCOUNT} ${PARENT_SIDE} ${PARENT_QTY} ${PARENT_SYMBOL}"
                     echo "      TWAP ${PARENT_DUR}s in $((PARENT_DUR / PARENT_BUCKET)) buckets of ${PARENT_BUCKET}s" ;;
    *) echo "[warn] parent order was not accepted: ${out:-no response}" ;;
  esac
}
echo "[sim] parent order queued: ${PARENT_SIDE} ${PARENT_QTY} ${PARENT_SYMBOL} for ${PARENT_ACCOUNT}, T+${PARENT_DELAY}s"
submit_parent &
PARENT_PID=$!

# --- the session ------------------------------------------------------------------------------
PRICE_TICKERS="${TICKERS}" MATCHER_URL="${MATCHER_URL}" POSITIONS_URL="${POSITIONS_URL}" \
  PRICES_URL="${PRICES_URL}" PARENT_ACCOUNT="${PARENT_ACCOUNT}" \
  node "${ROOT}/scripts/sim/session.mjs" "$@"
rc=$?

# Ground truth from the members: depth left resting at the cut is an acceptance criterion for the
# extract, and the gateway cannot answer it -- the book is the members' replicated state.
echo
for i in 0 1 2; do
  ${K} exec "order-matcher-cluster-${i}" -- sh -c 'wget -qO- http://localhost:8080/metrics' 2>/dev/null \
    | awk -v m="${i}" '/^traderx_book_open_orders/ {o=$2} /^traderx_cluster_trades/ {t=$2}
                       /^traderx_book_order_hash/ {h=$2} END {printf "[sim] member %s  open=%s trades=%s bookHash=%s\n", m, o, t, h}'
done

echo
echo "[sim] next: cut the day and check the extract, with the rig quiet --"
echo "        bash scripts/proofs/yu15-risk-extract.sh"
echo "      then confirm realism cost no determinism --"
echo "        bash scripts/yu15/run-proofs.sh"
echo "      (that suite mints fresh epochs of its own, so run it AFTER inspecting the extract)"
exit "${rc}"
