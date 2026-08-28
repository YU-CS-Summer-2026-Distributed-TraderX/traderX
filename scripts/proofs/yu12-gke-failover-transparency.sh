#!/usr/bin/env bash
# yu12-gke-failover-transparency.sh — proves failover is TRANSPARENT to in-flight clients as a
# pass/fail correctness claim: while a live REST order stream is running, the LEADER is killed,
# and at the end the cluster holds EXACTLY the orders the clients were acked for — zero lost,
# zero duplicated — verified against the orders' OWN read-model rows, never at the clients' 200s.
# (The bench `failover-client-probe.mjs` measures the outage's TIMING; this script is the
# correctness verdict the handoff names as missing.)
#
# ============================================================================================
# ADR-072 EXPOSURE, FIXED 2026-08-27. This proof was the most exposed of the five, because the
# tape did not merely contaminate a reading here — IT FALSIFIED THE PROOF'S PREMISE.
#
# STEPS, NOT LINE NUMBERS (a header insertion renumbers the file; a stale number sends the next
# reader to the wrong assertion).
#
#   step 0  THE QUIET-CLUSTER GUARD. It sampled traderx_cluster_next_order_ref and
#           traderx_book_open_orders, slept 5s, and required both UNCHANGED — "the cluster is
#           quiet, so the equality below is meaningful". Since ADR-072 the venue is never quiet
#           again: measured on the GKE bench 2026-08-27, that window moves the ref counter by ~30.
#           This guard now fails on EVERY run, on a correct cluster.
#
#           IT WAS NOT A CONTAMINATED READING. It was a true statement about a property the system
#           no longer has. There is nothing to retry into and nothing to widen — a tolerance here
#           would delete the guard, and the guard is what the old verdict rested on. So the
#           METHODOLOGY changed, not the tolerance: the venue is not quiet, but OUR SLICE OF IT is,
#           and that is what the equality actually needed. The guard now asserts no other OPERATOR
#           writer is active, on counters replayed flow cannot move by construction.
#
#   step 2  `BOOKED == ACKED`, where BOOKED was the venue-wide traderx_book_open_orders delta.
#           The tape rests and fills orders on 23 symbols throughout, so this delta is mostly
#           foreign. It fails in the ACCUSATORY direction and prints one of two sentences — "N
#           LOST" or "N DUPLICATED" — about a failover that lost and duplicated nothing. MEASURED
#           on the GKE bench 2026-08-27, two consecutive runs: the venue moved +570 while this proof
#           booked 567 ("3 DUPLICATED"), then +452 while it booked 498 ("46 LOST"). The same
#           assertion on the same correct venue accuses the gateway of OPPOSITE defects on
#           consecutive runs — the contamination is not even a consistent bias.
#
#           REPLACED BY THE IDENTITY CLAIM, which is what "zero lost, zero duplicated" always
#           meant and what a count could only ever proxy: the set of orderRefs the clients were
#           ACKED for must equal, exactly, the set of refs RESTING on this proof's minted ticker.
#           A lost order is an acked ref that is not resting; a duplicate is a resting ref no
#           client was acked for. Both are named individually rather than summed into a number,
#           and — because the ticker is minted and the tape never trades it — neither can be the
#           tape. Strictly stronger than the count: it says WHICH.
#
#   step 3  "no member bounced except the leader this proof killed" — the step title claimed it,
#           the code only PRINTED restartCounts and asserted nothing. Same shape as
#           yu13-otel-trace-join's stale header. It is now asserted, on pod UIDs captured before
#           the kill: exactly one member is a new pod, and it is the one this proof killed.
#
# WHY THE REF DELTA IS STILL NOT THE VERDICT (unchanged since 2026-08-16, and now doubly true):
# traderx_cluster_next_order_ref counts refs HANDED OUT, and the ref is consumed in
# MatchingEngineClusteredService (`event.orderRef = (int) nextOrderRef++`) BEFORE the engine
# answers idempotently in MatchingEngine.onNewOrder — so an idempotent retry, the very mechanism
# this proof exists to test, burns a ref by design and books nothing. THIS IS ALSO WHY NO EXACT
# OPERATOR-REF DELTA APPEARS BELOW: the whole point of this run is to provoke retries across an
# election, so `operator_refs == acked` would be wrong here even though the counter is clean. The
# operator ref delta is printed as a FLOOR and labelled context, never asserted as the verdict.
#
# THE TAPE MUST BE LIVE: step 0 gates the REPORTED rate against ADR-072's 5-20/s band, the last
# step gates the OBSERVED rate (submitted delta / elapsed), which no config field can fake.
#
# THIS PROOF KILLS THE CLUSTER LEADER and now says so first: DESTRUCTIVE=1 is required.
# ============================================================================================
#
# The accounting that makes this falsifiable:
#   * every client order carries a UNIQUE clientOrderId, and a client RETRIES an unacknowledged
#     send with the SAME clientOrderId (the ClOrdId ledger makes the retry idempotent — that is
#     the mechanism under test, not a proof convenience);
#   * ZERO LOST:       an acked orderRef that is not resting on the ticker;
#   * ZERO DUPLICATED: a resting orderRef no client was ever acked for;
#   * at least one order must have been in flight across the kill window (the stream is asserted
#     to have straddled the failover, otherwise the run proves nothing and says so).
#
# WHY GKE. A leader kill is an election; election behaviour on kind's starved CPUs is not the
# system's behaviour. No timing is asserted here, but the scenario itself (kill under load,
# catch-up, gateway re-home) only means something on real hardware.
#
# Usage: DESTRUCTIVE=1 ./yu12-gke-failover-transparency.sh   (GKE cluster up, tape live)
set -euo pipefail

CTX="${CTX:-gke_traderx-505400_us-east1-b_traderx-bench}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
IMAGE="${IMAGE:-us-east1-docker.pkg.dev/traderx-505400/traderx/cluster-node:yu17-6374c110}"
GW_SVC="${GW_SVC:-order-matcher-gw}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18320}"
ACCT="${ACCT:-42422}"
# MINTED, and the identity claim in step 2 depends on it: the tape trades 23 real symbols and never
# this one, so the open set on this ticker is exactly what this proof put there.
TICKER="${TICKER:-FOT$(date +%H%M%S)}"
PRICE="${PRICE:-110.00}"
STREAM_SECONDS="${STREAM_SECONDS:-90}"
KILL_AFTER="${KILL_AFTER:-20}"
DESTRUCTIVE="${DESTRUCTIVE:-0}"

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-consensus-readings.sh"
. "${here}/lib-gke-replay-gates.sh"

member_metric() { ${K} exec "order-matcher-cluster-$1" -c cluster-node -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' | awk -v m="^$2" '$0 ~ m {print $2}'; }
# CONTEXT ONLY, and labelled as such at every use. The venue-wide depth is what the old verdict
# rested on; it is kept because "the venue moved N while we booked M" is exactly the qualification
# a future green needs, and deleting it would hide that.
#
# WRITTEN TO `_agreed`'s SIGNATURE — a reader taking a MEMBER ORDINAL. This file used to hand-roll
# its own retry around a space-joined triple; the readers at risk from the ADR-072 sampling defect
# are exactly the ones that hand-roll their comparison instead of calling the library, which has
# always retried. The retry is load-bearing on this tier: three sequential execs at ~0.35s against
# a 6/s tape read as disagreement (measured 5 of 20 coherent, GKE bench 2026-08-27).
venue_depth() { member_metric "$1" traderx_book_open_orders 2>/dev/null; }
opens_agreed() { _agreed venue_depth "the venue-wide open-order depth"; }
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

if [[ "${SELFTEST:-0}" == "1" ]]; then gates_selftest; exit $?; fi

require_destructive \
  "KILLS THE CLUSTER LEADER (step 1) mid-stream, on what may be a shared rig." \
  "a ${STREAM_SECONDS}s client order stream straddling a leader kill -> every acked orderRef resting
                and nothing else resting -> exactly one member bounced." \
  "STREAM_SECONDS=${STREAM_SECONDS}"

# ---------------------------------------------------------------------------------------------
step "0. preflight: divergence rule, live tape, read model, and OUR slice of the venue quiet"
require_uniform_image "${IMAGE}"
require_tape_live
require_read_model "${ACCT}"
PRESSURE0="$(pressure_row)"
RUN_T0=${SECONDS}
start_pf
curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
  -d "{\"accountId\":${ACCT},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}" >/dev/null || fail "seed failed"

# THE OPERATOR-SCOPED QUIET GUARD, replacing the venue-wide one that ADR-072 made permanently red.
# What the old guard actually needed was "nothing OTHER THAN ME is writing orders", and these two
# counters say exactly that: replayed flow is excluded at the writer by account range, so anything
# that moves them is an operator — the algo engine, another lane's proof, or a human with curl.
# Measured on the GKE bench 2026-08-27: the operator twins agreed on 20 of 20 unretried samples
# while the venue counters agreed on 5 of 20, so this guard is also the only one of the two that
# can be sampled coherently at all.
OPR_Q0="$(quiesced_order_refs)"; OPT_Q0="$(quiesced_trades)"
sleep 5
OPR_Q1="$(quiesced_order_refs)"; OPT_Q1="$(quiesced_trades)"
[[ "${OPR_Q0}" == "${OPR_Q1}" && "${OPT_Q0}" == "${OPT_Q1}" ]] \
  || fail "another OPERATOR writer is active on this rig (operator refs ${OPR_Q0} -> ${OPR_Q1}, operator
  trades ${OPT_Q0} -> ${OPT_Q1}) with no traffic from this proof. The tape is excluded from these counters by
  construction, so this is a second operator — the algo engine, another lane's proof, or a person
  with curl. The identity claim below is scoped to a minted ticker and would survive it, but the
  ref FLOOR reported alongside it would not, so this refuses rather than reporting a number it
  cannot attribute."
OPEN0="$(opens_agreed)"
LDR="$(leader)" || fail "no leader"
# UIDs BEFORE the kill: step 3's claim is about which pods were replaced, and a restartCount alone
# cannot see a pod that was DELETED and recreated (the new pod's count is 0, same as never-bounced).
# A PLAIN INDEXED array, not `declare -A`: macOS ships bash 3.2, where `declare -A` is an invalid
# option — and it does not fail cleanly, it degrades to an INDEXED array while printing a usage
# line to stderr, so the script keeps running with a subtly different variable. Ordinals 0-2 index
# perfectly well without it.
UID0=()
for m in 0 1 2; do UID0[${m}]="$(member_pod_uid "${m}")"; done
echo "[ok] leader is member ${LDR}; our slice quiet at operator refs ${OPR_Q0}, trades ${OPT_Q0}"
echo "     venue depth (context, the tape moves this): ${OPEN0}"

step "1. stream orders; kill the leader mid-stream; keep streaming"
# One sequential client: each order gets a fresh clientOrderId, and an unacknowledged send is
# RETRIED with the SAME id until acked. Sequential on purpose — the accounting must be exact.
# The ACK line now carries the orderRef, which is what step 2's identity claim is built from.
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
    [[ "${out:-}" == *'"orderRef"'* ]] \
      && echo "ACK ${cid} $(date +%s) tries=$((tries+1)) ref=$(sed -n 's/.*"orderRef":\([0-9]*\).*/\1/p' <<<"${out}")"
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
ACKED_REFS="$(awk '$1=="ACK"{sub(/^ref=/,"",$5); print $5}' "${STREAM_LOG}" | sort -n | tr '\n' ' ' | sed 's/ *$//')"
echo "  stream done: ${ACKED} acked, ${RETRIED} needed retries, ${GAVEUP} gave up"
[[ "${GAVEUP}" == "0" ]] || fail "${GAVEUP} orders were never acknowledged even after retries — the outage was not transparent"
[[ "${ACKED}" -ge 10 ]] || fail "only ${ACKED} orders acked — not a meaningful stream"
[[ "${STRADDLED}" -ge 1 ]] \
  || fail "no order was acked after the kill: the stream did not straddle the failover, this run proves nothing"
# ANTI-VACUITY ON THE PROBE ITSELF. An ACK line whose ref failed to parse would contribute an empty
# field, and a short refs list compared against a short open set could agree for the wrong reason.
[[ "$(wc -w <<<"${ACKED_REFS}" | tr -d ' ')" == "${ACKED}" ]] \
  || fail "parsed $(wc -w <<<"${ACKED_REFS}" | tr -d ' ') orderRefs out of ${ACKED} ACK lines. The identity claim below compares
  ref SETS, so a ref that did not parse would silently weaken it into a comparison of fewer things."

step "2. the verdict: exactly the acked orders are resting — none lost, none duplicated"
${K} wait --for=condition=Ready "pod/order-matcher-cluster-${LDR}" --timeout=600s >/dev/null
# THE IDENTITY CLAIM. Set equality on a MINTED ticker, so no other writer on the venue can be in
# it. Retry absorbs read-model lag only: the assertion still fails on the last reading, with the
# missing and unexpected refs named individually, so a venue that genuinely lost or duplicated an
# order goes red rather than being waited into green.
await_open_set "${ACCT}" "${TICKER}" "${ACKED_REFS}" \
  "failover was NOT transparent" >/dev/null
echo "  all ${ACKED} acked orderRefs are resting on ${TICKER}, and nothing else is"
# CONTEXT, NEVER THE VERDICT — this is the reading the old assertion used, kept so every green
# carries the scale of the foreign flow it passed under.
OPEN1="$(opens_agreed)"
OPR_A="$(quiesced_order_refs)"
echo "  venue depth ${OPEN0} -> ${OPEN1} ($(( OPEN1 - OPEN0 )) net, mostly the tape's) for ${ACKED} orders of ours"
echo "  operator refs ${OPR_Q1} -> ${OPR_A} (+$(( OPR_A - OPR_Q1 ))) — a FLOOR, not a verdict: a retry burns a ref"
echo "    by design (allocated on apply, before the idempotency check), so the surplus over ${ACKED} is"
echo "    the ${RETRIED} retried send(s) plus any un-acked one."
(( OPR_A - OPR_Q1 >= ACKED )) \
  || fail "the operator ref generator moved by $(( OPR_A - OPR_Q1 )) for ${ACKED} acked orders. Refs are allocated on
  apply, so fewer refs than acks means orders were acked without being sequenced — the one direction
  this floor can catch, and the serious one."
NEWLDR="$(leader)" || fail "no leader after failover"
echo "  new leader is member ${NEWLDR} (was ${LDR})"

step "3. no member bounced except the leader this proof killed"
# ASSERTED, not printed. The step title has claimed this since the file was written; the code only
# echoed restartCounts. A deleted-and-recreated pod has restartCount 0, exactly like one that never
# bounced, so the reading has to be the pod UID captured in step 0.
BOUNCED=""
for m in 0 1 2; do
  now="$(member_pod_uid "${m}")"
  [[ -n "${now}" ]] || fail "member ${m} has no pod after the failover"
  [[ "${now}" != "${UID0[$m]}" ]] && BOUNCED+="${m} "
done
[[ "${BOUNCED}" == "${LDR} " ]] \
  || fail "members replaced across this run: [${BOUNCED:-none}], expected exactly the leader this proof
  killed (member ${LDR}). A member that bounced on its own during a failover is a second fault, and
  the transparency claim above was measured across both."
echo "  exactly member ${LDR} is a new pod; members $(tr -d ' ' <<<"$(for m in 0 1 2; do [[ "${m}" != "${LDR}" ]] && printf '%s,' "${m}"; done)" | sed 's/,$//') kept theirs"
${K} get pods -l app=order-matcher-cluster \
  -o jsonpath='{range .items[*]}{.metadata.name}={.status.containerStatuses[0].restartCount} {end}' | sed 's/^/  /'
echo

step "4. the write pressure this run actually ran under"
assert_observed_rate "$(( SECONDS - RUN_T0 ))" "failover transparency"
print_pressure "${PRESSURE0}" "$(pressure_row)"

rm -f "${STREAM_LOG}"
echo
echo "[PASS] failover transparency: a leader kill under a live order stream lost zero and"
echo "       duplicated zero orders — all ${ACKED} acked orderRefs are resting on ${TICKER} and"
echo "       nothing else is; ${RETRIED} in-flight send(s) were made whole by idempotent retry;"
echo "       exactly the killed member was replaced."
echo
echo "       NOT SHOWN, so that a reader citing this banner is not citing more than was tested:"
echo "         * the identity claim is scoped to account ${ACCT}. An order double-booked under a"
echo "           DIFFERENT account would not appear in this open set and would not be caught."
echo "         * the operator-quiet guard ran at step 0 only. It establishes that no second"
echo "           operator was writing THEN, not that none arrived during the ${STREAM_SECONDS}s stream —"
echo "           which would move the operator ref floor reported above."
echo "         * no TIMING claim. This is the correctness verdict; the outage duration is the"
echo "           bench probe's (failover-client-probe.mjs) and is not asserted anywhere here."
