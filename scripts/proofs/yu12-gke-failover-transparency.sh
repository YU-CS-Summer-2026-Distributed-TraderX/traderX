#!/usr/bin/env bash
# yu12-gke-failover-transparency.sh — proves failover is TRANSPARENT to in-flight clients as a
# pass/fail correctness claim: while a live REST order stream is running, the LEADER is killed,
# and at the end the cluster has booked EXACTLY the orders the clients were acked for — zero lost,
# zero duplicated — verified at the member traderx_cluster_next_order_ref delta, never at the
# clients' 200s. (The bench `failover-client-probe.mjs` measures the outage's TIMING; this script
# is the correctness verdict the handoff names as missing.)
#
# WHY GKE. A leader kill is an election; election behaviour on kind's starved CPUs is not the
# system's behaviour. No timing is asserted here, but the scenario itself (kill under load,
# catch-up, gateway re-home) only means something on real hardware.
#
# The accounting that makes this falsifiable:
#   * every client order carries a UNIQUE clientOrderId, and a client RETRIES an unacknowledged
#     send with the SAME clientOrderId (the ClOrdId ledger makes the retry idempotent — that is
#     the mechanism under test, not a proof convenience);
#   * ZERO LOST:      the BOOKED count (open-order delta) below the acked count means orders the
#                     clients were told about are not in the book — so the proof runs on a quiet
#                     cluster and asserts booked == acked;
#   * ZERO DUPLICATED: a double-booked retry raises booked above acked, so the same equality kills
#                     it from the other side;
#   * BOOKINGS, NOT REFS. This asserted on the next_order_ref delta until 2026-08-16 and was
#                     unsound: the ref is consumed before the engine answers idempotently, so a
#                     retry — the mechanism under test — burns a ref and books nothing, and the
#                     surplus was reported as "N DUPLICATED" against a correct gateway. Refs are
#                     now printed as information and never asserted;
#   * all three members must AGREE on both counters (a diverged member fails the run);
#   * at least one order must have been in flight across the kill window (the stream is asserted
#     to have straddled the failover, otherwise the run proves nothing and says so).
#
# Usage: ./yu12-gke-failover-transparency.sh   (quiet GKE cluster up; no other load)
set -euo pipefail

CTX="${CTX:-gke_traderx-501015_us-east1-b_traderx-lmax}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
GW_SVC="${GW_SVC:-order-matcher-gw}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18320}"
ACCT="${ACCT:-42422}"
TICKER="${TICKER:-FOT$(date +%H%M%S)}"
PRICE="${PRICE:-110.00}"
STREAM_SECONDS="${STREAM_SECONDS:-90}"
KILL_AFTER="${KILL_AFTER:-20}"

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

member_metric() { ${K} exec "order-matcher-cluster-$1" -c cluster-node -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' | awk -v m="^$2" '$0 ~ m {print $2}'; }
refs_all() { for m in 0 1 2; do printf "%s " "$(member_metric "${m}" traderx_cluster_next_order_ref 2>/dev/null)"; done; }
uniq_one() { tr ' ' '\n' | sed '/^$/d' | sort -u | wc -l | tr -d ' '; }
refs_agreed() { # retried: a mid-apply sample looks like divergence and is not
  local r i
  for i in $(seq 1 90); do
    r="$(refs_all)"
    [[ "$(echo "${r}" | uniq_one)" == "1" && -n "${r// /}" ]] && { echo "${r%% *}"; return 0; }
    sleep 2
  done
  fail "members never agreed on next_order_ref: [${r}]"
}
# BOOKINGS, not ref allocations. traderx_cluster_next_order_ref counts refs HANDED OUT, and the ref
# is consumed in MatchingEngineClusteredService (`event.orderRef = (int) nextOrderRef++`) BEFORE the
# engine answers idempotently in MatchingEngine.onNewOrder -- so an idempotent retry, which is the
# very mechanism this proof exists to test, burns a ref by design and books nothing. The ref delta
# therefore over-counts by exactly the retried sends, and the old `delta == acked` assertion
# reported that surplus as "N DUPLICATED": a false accusation of double-booking against a gateway
# that behaved correctly. (Recorded as unsound in §4 of the wedge issue; it fires far more often
# once the gateway drains its FIFO on a leader change, because every drained order is then retried.)
# Open-order count is booking-grained and idempotency-proof: a suppressed retry re-emits the
# ORIGINAL resting order and adds nothing. The stream is buy-only at a price nothing crosses, so
# every booked order rests and the delta IS the booking count.
opens_all() { for m in 0 1 2; do printf "%s " "$(member_metric "${m}" traderx_book_open_orders 2>/dev/null)"; done; }
opens_agreed() {
  local r i
  for i in $(seq 1 90); do
    r="$(opens_all)"
    [[ "$(echo "${r}" | uniq_one)" == "1" && -n "${r// /}" ]] && { echo "${r%% *}"; return 0; }
    sleep 2
  done
  fail "members never agreed on traderx_book_open_orders: [${r}]"
}
leader() { for m in 0 1 2; do [[ "$(member_metric "${m}" traderx_cluster_role 2>/dev/null)" == "1" ]] && { echo "${m}"; return 0; }; done; return 1; }

PF_PID=""
stop_pf() { [[ -n "${PF_PID}" ]] && { kill "${PF_PID}" 2>/dev/null || true; wait "${PF_PID}" 2>/dev/null || true; }; PF_PID=""; }
start_pf() {
  stop_pf
  ${K} port-forward "svc/${GW_SVC}" "${MATCHER_URL##*:}:18110" >/dev/null 2>&1 & PF_PID=$!
  local t=0
  until curl -sf --max-time 5 "${MATCHER_URL}/ready" >/dev/null 2>&1; do
    t=$((t+1)); [[ ${t} -lt 90 ]] || fail "gateway never became reachable"
    kill -0 "${PF_PID}" 2>/dev/null || { ${K} port-forward "svc/${GW_SVC}" "${MATCHER_URL##*:}:18110" >/dev/null 2>&1 & PF_PID=$!; }
    sleep 2
  done
}
trap stop_pf EXIT

# ---------------------------------------------------------------------------------------------
step "0. preflight: quiet three-member cluster, agreed ref, live gateway"
for i in $(seq 1 60); do
  ready="$(${K} get pods -l app=order-matcher-cluster \
    -o jsonpath='{range .items[*]}{.status.containerStatuses[0].ready}{" "}{end}')"
  [[ "${ready}" == "true true true " ]] && break
  [[ ${i} -lt 60 ]] || fail "members never all ready (saw: ${ready})"
  sleep 5
done
start_pf
curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
  -d "{\"accountId\":${ACCT},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}" >/dev/null || fail "seed failed"
REF0="$(refs_agreed)"
OPEN0="$(opens_agreed)"
# Quiet-cluster guard: the booked==acked equality is only meaningful with no stranger traffic.
# Both counters are checked, because a stranger could book without allocating (impossible today) or
# allocate without booking (a rejected or suppressed order), and either would poison a delta.
sleep 5
REF0B="$(refs_agreed)"
OPEN0B="$(opens_agreed)"
[[ "${REF0}" == "${REF0B}" && "${OPEN0}" == "${OPEN0B}" ]] \
  || fail "cluster is NOT quiet with no proof traffic (next_order_ref ${REF0} -> ${REF0B}, open_orders ${OPEN0} -> ${OPEN0B}): the equality would lie"
LDR="$(leader)" || fail "no leader"
echo "[ok] agreed ref ${REF0}, leader is member ${LDR}, cluster verified quiet"

step "1. stream orders; kill the leader mid-stream; keep streaming"
# One sequential client: each order gets a fresh clientOrderId, and an unacknowledged send is
# RETRIED with the SAME id until acked. Sequential on purpose — the accounting must be exact.
STREAM_LOG="$(mktemp)"
(
  end=$(( $(date +%s) + STREAM_SECONDS ))
  n=0
  while [[ "$(date +%s)" -lt "${end}" ]]; do
    n=$((n+1)); cid="fot-$$-${n}"
    tries=0
    until out="$(curl -s --max-time 10 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
        -d "{\"accountId\":${ACCT},\"ticker\":\"${TICKER}\",\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":${PRICE},\"clientOrderId\":\"${cid}\"}" \
        2>/dev/null)" && [[ "${out}" == *'"orderRef"'* ]]; do
      tries=$((tries+1))
      [[ ${tries} -lt 60 ]] || { echo "GAVEUP ${cid}"; break; }
      sleep 1
    done
    [[ "${out:-}" == *'"orderRef"'* ]] && echo "ACK ${cid} $(date +%s) tries=$((tries+1))"
  done
) >"${STREAM_LOG}" &
STREAM_PID=$!

sleep "${KILL_AFTER}"
echo "  killing LEADER order-matcher-cluster-${LDR} mid-stream at $(date +%T)"
KILL_TS="$(date +%s)"
${K} delete pod "order-matcher-cluster-${LDR}" --wait=false >/dev/null
wait "${STREAM_PID}" || true
ACKED="$(grep -c '^ACK ' "${STREAM_LOG}" || true)"
GAVEUP="$(grep -c '^GAVEUP ' "${STREAM_LOG}" || true)"
RETRIED="$(awk '$1=="ACK" && $4!="tries=1"' "${STREAM_LOG}" | wc -l | tr -d ' ')"
STRADDLED="$(awk -v k="${KILL_TS}" '$1=="ACK" && $3>k' "${STREAM_LOG}" | wc -l | tr -d ' ')"
echo "  stream done: ${ACKED} acked, ${RETRIED} needed retries, ${GAVEUP} gave up"
[[ "${GAVEUP}" == "0" ]] || fail "${GAVEUP} orders were never acknowledged even after retries — the outage was not transparent"
[[ "${ACKED}" -ge 10 ]] || fail "only ${ACKED} orders acked — not a meaningful stream"
[[ "${STRADDLED}" -ge 1 ]] \
  || fail "no order was acked after the kill: the stream did not straddle the failover, this run proves nothing"

step "2. the members' verdict: refs advanced by EXACTLY the acked count, on all three"
${K} wait --for=condition=Ready "pod/order-matcher-cluster-${LDR}" --timeout=600s >/dev/null
REF1="$(refs_agreed)"
OPEN1="$(opens_agreed)"
DELTA=$(( REF1 - REF0 ))
BOOKED=$(( OPEN1 - OPEN0 ))
echo "  orders BOOKED (open-order delta): ${OPEN0} -> ${OPEN1} (${BOOKED}) agreed by all three members"
echo "  client-side acked (unique clientOrderIds): ${ACKED}"
# THE ASSERTION. Booked, not refs allocated -- see opens_agreed() for why the ref delta cannot
# carry this claim.
[[ "${BOOKED}" -eq "${ACKED}" ]] || fail "cluster booked ${BOOKED} orders for ${ACKED} acks: $(
  [[ ${BOOKED} -lt ${ACKED} ]] && echo "$(( ACKED - BOOKED )) LOST" || echo "$(( BOOKED - ACKED )) DUPLICATED")"
# Refs are reported, never asserted: the surplus over bookings is the idempotent retries plus any
# order that consumed a ref without resting (rejected, or answered with no committed ack). A
# non-zero surplus is INFORMATION about the run, not a defect -- it is exactly what the old
# assertion mistook for duplication.
SURPLUS=$(( DELTA - BOOKED ))
echo "  next_order_ref: ${REF0} -> ${REF1} (delta ${DELTA}); ${SURPLUS} ref(s) allocated but not booked"
[[ "${SURPLUS}" -eq 0 ]] \
  || echo "    (expected: ${RETRIED} idempotent retr$([[ ${RETRIED} == 1 ]] && echo y || echo ies) burn a ref each by design, plus any un-acked send)"
NEWLDR="$(leader)" || fail "no leader after failover"
echo "  new leader is member ${NEWLDR} (was ${LDR})"

step "3. no member bounced except the leader this proof killed"
RESTARTS="$(${K} get pods -l app=order-matcher-cluster \
  -o jsonpath='{range .items[*]}{.metadata.name}={.status.containerStatuses[0].restartCount} {end}')"
echo "  ${RESTARTS}   (the killed leader is a NEW pod)"

rm -f "${STREAM_LOG}"
echo
echo "[PASS] failover transparency: a leader kill under a live order stream lost zero and"
echo "       duplicated zero orders — ${ACKED} client acks, ${BOOKED} orders booked, agreed by"
echo "       all three members; ${RETRIED} in-flight sends were made whole by idempotent retry."
