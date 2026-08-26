#!/usr/bin/env bash
# PROOF: acks are matched by KEY, not by arrival order (ack-correlation fix, option B).
#
# THE CLAIM. A leader kill under a live staggered stream strands the offers the dying leader
# sequenced but never egressed (measured: the promotion destroys those acks). Under the positional
# FIFO one strand shifted every later ack onto the wrong request FOREVER: every 200 carried the ref
# of the order K positions later, the last K clients got 504, depth pinned at K through idle, and
# serial sends starved below rate K/12. Under keyed correlation the same strand must harm NOTHING:
# every answered client got ITS OWN ref, depth returns to 0 on its own (the deadline sweep), and a
# serial probe commits immediately.
#
# PER-ITEM, NOT AGGREGATE (vacuous-pass-audit rule 16): an all-at-once burst's aggregate split is
# explained equally by the offset and by innocent unanswered-heads, so every 200 here is checked
# against the ENGINE'S OWN idempotency table — resending the same clientOrderId returns the
# ORIGINAL order's ref (MatchingEngine.onNewOrder re-emits it), which is the authoritative answer
# to "whose ref was this client told?". Oracle constraints honoured from the design doc:
#   * far-off-market resting buys, so the original cannot fill/cancel (the re-emit is guarded on
#     the original still RESTING — a filled original mints a NEW ref and would manufacture a false
#     positive on the severe claim); open_orders is checked to actually hold them before resending;
#   * resend immediately, before LRU eviction;
#   * clientOrderIds always set (a blank one hashes to the engine's "no key" sentinel and the
#     whole oracle reads as a clean pass while measuring nothing).
#
# NEGATIVE CONTROL / RED ARM: run this same script against a pre-B build (e.g. :yu17-fx) — it must
# FAIL, either at the per-item check (misattributed refs) or at the oracle/probe (serial sends
# starve at K>0) or at the depth check (pinned depth). A green pre-B run means this proof is broken.
#
# A kill that strands nothing is UNINFORMATIVE, not confirming (rule 18): induction is retried up
# to MAX_ROUNDS, and if no strand was ever induced the script exits 2 loudly instead of passing.
#
# Usage (kind rig, members+gateway already on the keyed build at a fresh epoch):
#   CTX=kind-traderx-yu12-cluster bash scripts/proofs/yu17-keyed-ack-correlation.sh
set -uo pipefail

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
GW_SVC="${GW_SVC:-order-matcher-gw}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
ACCT="${ACCT:-42422}"
TICKER="${TICKER:-KAC$(date +%H%M%S)}"
PRICE="${PRICE:-110.00}"          # seeded mark
# Below the mark so it rests and can never cross (oracle constraint) — but INSIDE the book's band.
# This was 10.00 against a 110.00 seed, which worked only while the band anchored on the first limit;
# since ADR-066 the band is centred on the seeded reference (±65.5), and 10.00 is collared, so nothing
# rested and the proof reported itself vacuous. Nobody sells a fresh ticker, so any in-band bid rests.
BUY_PX="${BUY_PX:-50.00}"
ORDERS_PER_ROUND="${ORDERS_PER_ROUND:-50}"
STAGGER_S="${STAGGER_S:-0.06}"
KILL_AFTER_N="${KILL_AFTER_N:-15}"
MAX_ROUNDS="${MAX_ROUNDS:-3}"
REAP_WINDOW_S="${REAP_WINDOW_S:-20}"   # ACK_TIMEOUT(10s) + 2s + slack

fail() { echo "[FAIL] $*" >&2; exit 1; }
inconclusive() { echo "[INCONCLUSIVE] $*" >&2; exit 2; }
step() { echo; echo "=== $* ==="; }

member_metric() { ${K} exec "order-matcher-cluster-$1" -c cluster-node -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' | awk -v m="^$2" '$0 ~ m {print $2}'; }
gw_metric() { curl -sf --max-time 5 "${MATCHER_URL}/metrics" | awk -v m="$1" '$0 ~ m {print $NF}' | head -1; }
leader() { for m in 0 1 2; do [[ "$(member_metric "${m}" traderx_cluster_role 2>/dev/null)" == "1" ]] && { echo "${m}"; return 0; }; done; return 1; }
uniq_one() { tr ' ' '\n' | sed '/^$/d' | sort -u | wc -l | tr -d ' '; }
agreed() { # agreed <metric-name>  -> the single agreed value, retried through mid-apply skew
  local r i m="$1"
  for i in $(seq 1 60); do
    r="$(for n in 0 1 2; do printf "%s " "$(member_metric "${n}" "${m}" 2>/dev/null)"; done)"
    [[ "$(echo "${r}" | uniq_one)" == "1" && -n "${r// /}" ]] && { echo "${r%% *}"; return 0; }
    sleep 2
  done
  fail "members never agreed on ${m}: [${r}]"
}

PF_PID=""
stop_pf() { [[ -n "${PF_PID}" ]] && { kill "${PF_PID}" 2>/dev/null || true; wait "${PF_PID}" 2>/dev/null || true; }; PF_PID=""; }
start_pf() {
  stop_pf
  ${K} port-forward "svc/${GW_SVC}" "${MATCHER_URL##*:}:18110" >/dev/null 2>&1 & PF_PID=$!
  local t=0
  until curl -sf --max-time 5 "${MATCHER_URL}/health" >/dev/null 2>&1; do
    t=$((t+1)); [[ ${t} -lt 60 ]] || fail "gateway never became reachable through the forward"
    kill -0 "${PF_PID}" 2>/dev/null || { ${K} port-forward "svc/${GW_SVC}" "${MATCHER_URL##*:}:18110" >/dev/null 2>&1 & PF_PID=$!; }
    sleep 2
  done
}
trap stop_pf EXIT

send_order() { # send_order <cid> -> "HTTPCODE BODY" (single send, no retry: the raw answer)
  local out code body
  out="$(curl -s --max-time 15 -w '\n%{http_code}' -X POST "${MATCHER_URL}/orders" \
    -H 'Content-Type: application/json' \
    -d "{\"accountId\":${ACCT},\"ticker\":\"${TICKER}\",\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":${BUY_PX},\"clientOrderId\":\"$1\"}" 2>/dev/null)"
  code="${out##*$'\n'}"; body="${out%$'\n'*}"
  echo "${code} ${body}"
}
ref_of() { echo "$1" | grep -o '"orderRef":[0-9]*' | grep -o '[0-9]*'; }

# ---------------------------------------------------------------------------------------------
step "0. preflight: one image everywhere, quiet cluster, live gateway, baselines"
IMGS="$(${K} get pods -l app=order-matcher-cluster \
  -o jsonpath='{range .items[*]}{.spec.containers[0].image}{" "}{end}') $(${K} get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].image}')"
[[ "$(echo "${IMGS}" | uniq_one)" == "1" ]] || fail "members and gateway are on mixed images: ${IMGS} — the ack format is a wire contract; roll them together"
echo "  image everywhere: $(echo "${IMGS}" | awk '{print $1}')"
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
# QUIET MEANS "NO OTHER OPERATOR WORKLOAD", which is what this precondition always meant and what
# it can still measure. It used to read the GLOBAL ref generator and the venue-wide open-order
# count, and since ADR-072 neither is ever still: the tape replay submits ~6 orders/s and rests and
# fills them continuously. The replay does not poison the per-item accounting below — that walks
# THIS proof's own clients against the engine's idempotency table, and replayed orders are other
# clients — so the reading to keep is the operator-scoped one, which the replay cannot move.
#
# traderx_book_open_orders is dropped from the stillness half entirely rather than scoped: there is
# no operator-only book count, the replay owns most of the depth on this rig, and the thing this
# precondition exists to exclude is another ORDER WRITER, which the ref counter names exactly.
REF0="$(agreed traderx_cluster_operator_next_order_ref)"
sleep 5
[[ "$(agreed traderx_cluster_operator_next_order_ref)" == "${REF0}" ]] \
  || fail "cluster is not quiet: another OPERATOR workload is submitting orders, and it would poison
  the per-item accounting. (The ADR-072 tape replay is excluded from this counter by construction;
  if this trips, something else is writing.)"
GW_POD="$(${K} get pods -l app=cluster-gateway -o jsonpath='{.items[0].metadata.name}')"
RESTARTS0="$(${K} get pod "${GW_POD}" -o jsonpath='{.status.containerStatuses[0].restartCount}')"
UPS0="$(${K} logs "${GW_POD}" 2>/dev/null | grep -c '^GATEWAY up' || true)"
REAPED0="$(gw_metric 'pipeline_total{stage="reaped"}')"
KEYED=1
if [[ -z "${REAPED0}" ]]; then
  # No reaped stage = no deadline sweep = not a keyed build. Run the scenario anyway (the A build's
  # election-time resync makes steps 1-2 pass HONESTLY — A does repair the election trigger), and
  # step 3 then fails on the absent mechanism, which is the correct verdict, correctly attributed.
  echo "  NOTE: no reaped stage exported — a pre-keyed build. Steps 1-2 may pass (option A's bulk"
  echo "  resync repairs elections); step 3 will fail on the absent mechanism, which is the point."
  KEYED=0; REAPED0=0
fi
[[ "${REAPED0}" =~ ^[0-9]+$ ]] || fail "unreadable reaped baseline: '${REAPED0}'"
echo "  ok: quiet at ref ${REF0}, open ${OPEN0}; gateway restarts=${RESTARTS0} ups=${UPS0} reaped=${REAPED0}"

# ---------------------------------------------------------------------------------------------
step "1. induce a strand: staggered stream, leader killed mid-stream (retried if nothing strands)"
WORK="$(mktemp -d)"
STRANDED_TOTAL=0
ROUND=0
while :; do
  ROUND=$((ROUND+1))
  [[ ${ROUND} -le ${MAX_ROUNDS} ]] || break
  LDR="$(leader)" || fail "no leader before round ${ROUND}"
  echo "  round ${ROUND}: leader is member ${LDR}; launching ${ORDERS_PER_ROUND} staggered orders"
  # Collected pids, waited on BY PID: a bare `wait` would also wait on the backgrounded
  # port-forward, which never exits — the exact harness bug vacuous-pass-audit rule 15 lists.
  ROUND_PIDS=()
  for i in $(seq 1 "${ORDERS_PER_ROUND}"); do
    cid="kac-$$-r${ROUND}-${i}"
    ( send_order "${cid}" > "${WORK}/${cid}.res" ) & ROUND_PIDS+=($!)
    if [[ "${i}" == "${KILL_AFTER_N}" ]]; then
      ${K} delete pod "order-matcher-cluster-${LDR}" --wait=false >/dev/null 2>&1 & ROUND_PIDS+=($!)
    fi
    sleep "${STAGGER_S}"
  done
  for p in "${ROUND_PIDS[@]}"; do wait "${p}" 2>/dev/null || true; done
  R200=0; R504=0
  for f in "${WORK}"/kac-$$-r${ROUND}-*.res; do
    code="$(awk '{print $1}' "$f")"
    case "${code}" in
      200) R200=$((R200+1));;
      *)   R504=$((R504+1));;
    esac
  done
  echo "  round ${ROUND}: ${R200} answered 200, ${R504} not answered"
  # A strand shows as unanswered clients (their acks were destroyed / never generated for them).
  if [[ ${R504} -gt 0 ]]; then
    STRANDED_TOTAL=$((STRANDED_TOTAL+R504))
    break
  fi
  echo "  round ${ROUND} stranded nothing (kill landed between offers) — uninformative, retrying"
  sleep 10   # let the election settle before the next round
done
[[ ${STRANDED_TOTAL} -gt 0 ]] || inconclusive "no kill stranded anything across ${MAX_ROUNDS} rounds — nothing was at risk, so this run can confirm nothing; re-run"
echo "  induced: ${STRANDED_TOTAL} client(s) went unanswered across the kill"

# ---------------------------------------------------------------------------------------------
step "2. THE PER-ITEM CLAIM: every answered client was told ITS OWN ref (engine idempotency oracle)"
# The strand destroyed some acks; the orders themselves are resting in the book. Wait out the reap
# window first so the gateway is past the strand, then verify the book actually HOLDS the resting
# originals (oracle precondition: the re-emit is guarded on RESTING).
sleep "${REAP_WINDOW_S}"
ANSWERED=0; CHECKED=0; MISATTRIBUTED=0
OPEN_NOW="$(agreed traderx_book_open_orders)"
BOOKED=$((OPEN_NOW - OPEN0))
echo "  book holds ${BOOKED} new resting orders for this run (open ${OPEN0} -> ${OPEN_NOW})"
[[ ${BOOKED} -gt 0 ]] || fail "no orders rested — the oracle would have nothing to answer and this proof would be vacuous"
for f in "${WORK}"/kac-$$-*.res; do
  code="$(awk '{print $1}' "$f")"; body="$(cut -d' ' -f2- "$f")"
  [[ "${code}" == "200" ]] || continue
  ANSWERED=$((ANSWERED+1))
  cid="$(basename "$f" .res)"
  told="$(ref_of "${body}")"
  [[ "${told}" =~ ^[0-9]+$ ]] || fail "client ${cid} got 200 with no parseable orderRef: ${body}"
  oracle="$(send_order "${cid}")"
  ocode="$(echo "${oracle}" | awk '{print $1}')"
  oref="$(ref_of "${oracle}")"
  [[ "${ocode}" == "200" && "${oref}" =~ ^[0-9]+$ ]] \
    || fail "oracle resend for ${cid} got '${ocode}' — a post-strand serial send must commit under keyed correlation (starving here is the positional offset's own signature)"
  CHECKED=$((CHECKED+1))
  if [[ "${oref}" != "${told}" ]]; then
    MISATTRIBUTED=$((MISATTRIBUTED+1))
    echo "  [X] ${cid}: told ${told}, engine says ${oref} — CROSS-WIRED"
  fi
done
[[ ${CHECKED} -gt 0 ]] || fail "no answered client could be checked — empty population, nothing verified"
[[ ${CHECKED} -eq ${ANSWERED} ]] || fail "checked ${CHECKED} of ${ANSWERED} answered clients"
[[ ${MISATTRIBUTED} -eq 0 ]] \
  || fail "${MISATTRIBUTED} of ${CHECKED} answered clients carried a STRANGER'S ref — the correlation is not keyed"
echo "  ok: ${CHECKED}/${CHECKED} answered clients verified against the engine's own table; 0 cross-wired"

# ---------------------------------------------------------------------------------------------
step "3. the mechanism reading: depth returned to 0 WITHOUT a reconnect (sweep, not drain)"
DEPTH="$(gw_metric 'traderx_gateway_inflight_orders')"
[[ "${DEPTH}" =~ ^[0-9]+$ ]] || fail "unreadable in-flight depth: '${DEPTH}'"
[[ "${DEPTH}" == "0" ]] \
  || fail "in-flight depth is ${DEPTH} after ${REAP_WINDOW_S}s idle — a persisting depth is a stranded window (the positional defect's deterministic signature)"
RESTARTS1="$(${K} get pod "${GW_POD}" -o jsonpath='{.status.containerStatuses[0].restartCount}')"
UPS1="$(${K} logs "${GW_POD}" 2>/dev/null | grep -c '^GATEWAY up' || true)"
[[ "${RESTARTS1}" == "${RESTARTS0}" && "${UPS1}" == "${UPS0}" ]] \
  || fail "gateway restarted or rebuilt its session mid-proof (restarts ${RESTARTS0}->${RESTARTS1}, ups ${UPS0}->${UPS1}) — depth 0 via drain proves nothing about the sweep"
[[ "${KEYED}" == "1" ]] || fail "this build has NO keyed-correlation mechanism (no reaped stage): \
depth 0 here came from a bulk election drain, not from per-request completion plus the deadline \
sweep — the mechanism under test is absent, so this build cannot be certified by this proof"
REAPED1="$(gw_metric 'pipeline_total{stage="reaped"}')"
[[ "${REAPED1}" =~ ^[0-9]+$ ]] || fail "reaped stage unreadable on the after side: '${REAPED1}'"
[[ ${REAPED1} -gt ${REAPED0} ]] \
  || fail "reaped never moved (${REAPED0} -> ${REAPED1}) while ${STRANDED_TOTAL} clients went unanswered — the permits were freed by something else, which is not the mechanism under test"
echo "  ok: depth 0, restarts ${RESTARTS1}, ups ${UPS1} (unchanged); reaped ${REAPED0} -> ${REAPED1}"

# ---------------------------------------------------------------------------------------------
step "4. post-strand serial probe: a fresh order commits immediately with its own ref"
cid="kac-$$-probe"
res="$(send_order "${cid}")"
code="$(echo "${res}" | awk '{print $1}')"; told="$(ref_of "${res}")"
[[ "${code}" == "200" && "${told}" =~ ^[0-9]+$ ]] \
  || fail "post-strand serial probe got '${code}' — under the positional offset serial sends starve (rate < K/12); under keyed correlation they must commit"
oracle="$(send_order "${cid}")"; oref="$(ref_of "${oracle}")"
[[ "${oref}" == "${told}" ]] || fail "probe told ${told} but the engine says ${oref} — cross-wired"
echo "  ok: probe committed ref ${told}, oracle agrees"

# ---------------------------------------------------------------------------------------------
step "5. the members' verdict: agreement AT A SHARED LOG POSITION"
agreed traderx_cluster_operator_next_order_ref >/dev/null
# THE BOOK CANNOT BE COMPARED BY SAMPLING THREE MEMBERS IN SEQUENCE ANY MORE. `agreed` retries
# until three consecutive reads match, which converges only on a still log; under ADR-072's
# continuous replayed flow the book moves between the reads and the retry burns its whole budget on
# a cluster in perfect agreement. The claim is determinism — SAME LOG POSITION, SAME STATE — so
# read the position and the state together and compare members that report the same position.
#
# This is strictly stronger than the old form, which compared three states with no evidence they
# were taken at the same point in the log at all.
BOOK_AT_POS="$(for round in $(seq 1 40); do
  for n in 0 1 2; do
    ${K} exec "order-matcher-cluster-${n}" -c cluster-node -- \
      sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
      | awk -v m="${n}" '/^traderx_cluster_applied/{a=$2}
                         /^traderx_book_open_orders/{o=$2}
                         /^traderx_book_order_hash/{h=$2}
                         END{if(a!="") print a, m, o, h}'
  done
done | sort -k1,1n -k2,2n | awk '
  { if ($1 == prev_pos) { print prev_pos, prev_state, $3" "$4 } ; prev_pos=$1; prev_state=$3" "$4 }
' | head -1)"
[[ -n "${BOOK_AT_POS}" ]] \
  || fail "no two members were ever observed at the same applied position across 40 rounds, so
  cross-member book determinism could not be measured at all"
read -r POS S1A S1B S2A S2B <<<"${BOOK_AT_POS}"
[[ "${S1A} ${S1B}" == "${S2A} ${S2B}" ]] \
  || fail "two members at the SAME applied position ${POS} hold different books:
  [${S1A} ${S1B}] vs [${S2A} ${S2B}]. Identical log, identical state, or the cluster has diverged."
echo "  ok: two members at applied ${POS} hold the identical book [${S1A} ${S1B}], and all three"
echo "      agree on the operator ref counter"

echo
echo "[PASS] keyed ack correlation: ${STRANDED_TOTAL} stranded, 0 of ${CHECKED} answered clients cross-wired,"
echo "       depth self-drained to 0 with no reconnect (reaped ${REAPED0} -> ${REAPED1}), serial probe committed."
