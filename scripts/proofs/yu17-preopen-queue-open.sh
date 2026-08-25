#!/usr/bin/env bash
# yu17-preopen-queue-open.sh — format-8 §1.3-§1.6 / ADR-069: PRE_OPEN QUEUES, it does not trade;
# the open releases in INSERTION ORDER (scope §5 row 5; decisions b and g of §7).
#
# THE CLAIM, on one fresh ticker seeded @150 with two accounts:
#   1. PRE_OPEN, then three orders — BUY A1, BUY A2 (same account, same price), SELL S (other
#      account, crossing). All three are ACKED, and NOTHING TRADES while they are queued.
#   2. queueDepth reads 3 on the member's /health (scope §1.7), and the read model shows the
#      orders QUEUED (decision g: a pre-open order must not read as missing or as a live rest).
#   3. OPEN releases them IN INSERTION ORDER inside ONE apply: A1 rests, A2 rests, S crosses A1
#      because A1 was first at that level. So A1 FILLS and A2 STILL RESTS — reverse the release
#      order and A2 fills instead, which is exactly what makes this an order assertion and not a
#      count. The release issues NO new order refs (§1.3: the ref is assigned at SEQUENCING, before
#      the gate, so a queued order already holds it) and books exactly one match.
#   4. decision (b): PRE_OPEN -> CLOSED with a non-empty queue CANCELS the queue.
#
# THE ASSERTION THAT MATTERS IS A STILLNESS ASSERTION, and that is the class the live feed adapter
# broke three proofs in on 2026-08-24. It is NOT hand-rolled here: it comes from
# lib-consensus-readings.sh's assert_order_effects, which brackets the trade counter (feed-proof —
# a PRICE_TICK books no trade) by the order-ref generator (feed-proof for the same reason), so
# "0 trades" cannot mean "the window closed before my orders applied" and cannot be satisfied by
# another writer's window. Do not replace it with a traderx_cluster_trades delta.
#
# ARMS (chip-1 convention: EXPECT=after is the default, so the suite states the POST-MINT claim):
#   EXPECT=before (current build): POST /session 404s and /health has no queueDepth — both
#     RECORDED, never asserted, because "the API is missing" is not the defect. The proof PROCEEDS
#     and records the observable one: the three orders are taken as live, S CROSSES A1 immediately,
#     and the trade counter moves +2 inside the window the claim says must be still.
#   EXPECT=after  (format-8 build): the sequence above.
#
# WHAT THIS PROOF COSTS THE EPOCH (both arms): one match on a throwaway ticker, and the position
# rows it creates are deleted on the way out — the same posture as yu17-band-follows-market and
# yu17-retick-determinism. It rolls nothing, kills nothing and wipes nothing.
set -euo pipefail
CTX="${CTX:-kind-traderx-yu12-cluster}"; NS="${NS:-traderx}"
K=(kubectl --context "${CTX}" -n "${NS}")
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
EXPECT="${EXPECT:-after}"
ACCT="${ACCT:-22214}"; ACCT2="${ACCT2:-52355}"   # both in reference data (yu17-band-follows-market)
TICKER="${TICKER:-PQO$(date +%H%M%S)}"
SEED_PX="${SEED_PX:-150}"
fail() { echo "[FAIL] $*" >&2; exit 1; }
ok() { echo "[ok] $*"; }
red() { echo "[RED] $*  <- EXPECTED RED until the format-8 mint (design §5)"; }
field() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$2',''))" <<<"$1"; }
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/lib-consensus-readings.sh"

health_field() { # health_field <member-ordinal> <key>
  "${K[@]}" exec "order-matcher-cluster-${1}" -- wget -qO- localhost:8080/health 2>/dev/null \
    | python3 -c "import sys,json;print(json.load(sys.stdin).get('$2',''))" 2>/dev/null || echo ""
}
set_phase() { curl -s -m20 -w ' %{http_code}' -X POST "${MATCHER_URL}/session" \
  -H 'Content-Type: application/json' -d "{\"phase\":\"$1\"}" 2>/dev/null | awk '{c=$NF; $NF=""; print c, $0}'; }
order() { # order <account> <side> <qty> <price> <tag>
  curl -s -m20 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
    -d "{\"accountId\":$1,\"ticker\":\"${TICKER}\",\"side\":\"$2\",\"quantity\":$3,\"limitPrice\":$4,\"clientOrderId\":\"${TICKER}-$5-$$\"}"; }
cancel() { curl -s -m20 -X POST "${MATCHER_URL}/cancel" -H 'Content-Type: application/json' -d "{\"orderRef\":$1}"; }
digest() { "${K[@]}" exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
  | awk '/^traderx_book_open_orders/ {d=$2} /^traderx_book_order_hash/ {h=$2} END {print d, h}'; }
# The read model's status for one orderRef. Row ids are "<epoch>-<orderRef>". This is the ONE place
# the read model is the right assertion end: decision (g) is a claim about what reaches the console,
# so the engine counters cannot answer it. Every core assertion above stays on the members' own
# counters (prove-cluster-engine-change §3).
rm_status() { # rm_status <orderRef>
  "${K[@]}" exec deploy/trade-processor -- sh -c "wget -qO- 'http://localhost:18091/accounts/$2/orders?status=all' 2>/dev/null" \
    | python3 -c "
import sys,json
for r in json.load(sys.stdin):
    if str(r.get('id','')).endswith('-$1'): print(r.get('status','')); break
else: print('')
" 2>/dev/null || echo ""
}

CLEANUP_REFS=(); PHASE_TOUCHED=0
cleanup() {
  local r
  (( PHASE_TOUCHED == 1 )) && { set_phase OPEN >/dev/null 2>&1 || true; }
  for r in ${CLEANUP_REFS[@]+"${CLEANUP_REFS[@]}"}; do cancel "${r}" >/dev/null 2>&1 || true; done
  # The crossed throwaway position has no EOD price and would halt its accounts' P&L
  # (yu15-risk-extract reads that as a real halt). Same convention as yu17-band-follows-market.
  "${K[@]}" exec deploy/eod-price-db -c mariadb -- mariadb -utraderx -ptraderx traderx -N -B \
    -e "DELETE FROM positions WHERE security='${TICKER}';" >/dev/null 2>&1 \
    || echo "    (throwaway position rows not cleared; seed-proof-fixtures.sh will)"
}
trap cleanup EXIT

echo "=== yu17-preopen-queue-open, EXPECT=${EXPECT}, ticker ${TICKER} ==="
for m in 0 1 2; do "${K[@]}" get pod "order-matcher-cluster-${m}" -o jsonpath='{.spec.containers[0].image}{"\n"}'; done | sort -u

# --- 0. the phase must be readable and OPEN -----------------------------------------------------
PHASE0="$(health_field 0 phase)"
echo "    member-0 /health phase: '${PHASE0:-<absent>}' queueDepth: '$(health_field 0 queueDepth)'"
if [[ -z "${PHASE0}" ]]; then
  # Recorded, not asserted, on BOTH arms: an API-shaped red is worth nothing at the mint. Both the
  # phase field and the phase command are re-asserted at the end, after the stillness verdict.
  red "the member's /health carries no phase/queueDepth: scope §1.7's one-request question cannot be asked on this build"
else
  [[ "${PHASE0}" == "OPEN" ]] || fail "the venue must start OPEN for this proof (decision a); it reads '${PHASE0}'"
  ok "the venue reads phase=OPEN"
fi

# --- 1. the control: this ticker trades, at this price, before anything is halted ----------------
# Without it, an "after" arm in which nothing trades is indistinguishable from a dead ticker, an
# unseeded price or a collared limit — the vacuous form of "nothing traded while queued".
SEEDED="$(curl -s -m20 -o /dev/null -w '%{http_code}' -X POST "${MATCHER_URL}/seed" \
  -H 'Content-Type: application/json' -d "{\"accountId\":${ACCT},\"tickers\":\"${TICKER}\",\"price\":${SEED_PX}}")"
[[ "${SEEDED}" == 2* ]] || fail "seed of ${TICKER} @${SEED_PX} did not take (HTTP ${SEEDED})"
[[ "$(curl -s -m20 -o /dev/null -w '%{http_code}' -X POST "${MATCHER_URL}/seed" \
  -H 'Content-Type: application/json' -d "{\"accountId\":${ACCT2},\"tickers\":\"${TICKER}\",\"price\":${SEED_PX}}")" == 2* ]] \
  || fail "seed for ${ACCT2} did not take"
CTRL="$(order "${ACCT}" Buy 10 "${SEED_PX}" ctrl)"
[[ "$(field "${CTRL}" kind)" == "1" ]] \
  || fail "the control BUY @${SEED_PX} did not rest on an OPEN venue (${CTRL}): the ticker or the price is the problem, and nothing below would discriminate"
CTRL_REF="$(field "${CTRL}" orderRef)"
C="$(cancel "${CTRL_REF}")"
[[ "$(field "${C}" canceled)" == "True" || "$(field "${C}" canceled)" == "true" ]] || fail "control cancel failed: ${C}"
ok "control: a BUY @${SEED_PX} rests and cancels while OPEN — the ticker is live and the price is inside the band"

# --- 2. PRE_OPEN --------------------------------------------------------------------------------
RESP="$(set_phase PRE_OPEN)"; CODE="${RESP%% *}"
echo "    POST /session {\"phase\":\"PRE_OPEN\"} -> HTTP ${CODE} ${RESP#* }"
if [[ "${CODE}" == "2"* ]]; then
  PHASE_TOUCHED=1
  P="$(health_field 0 phase)"; [[ "${P}" == "PRE_OPEN" ]] || fail "the command answered ${CODE} but member-0 reads phase='${P}'"
  ok "the venue is PRE_OPEN"
else
  red "POST /session is ABSENT on this build (HTTP ${CODE}) — recorded, NOT asserted. Proceeding to the observable defect."
fi

# --- 3. three orders into the halted book -------------------------------------------------------
REFS0="$(quiesced_order_refs)"; T0="$(quiesced_trades)"
A1="$(order "${ACCT}"  Buy  10 "${SEED_PX}" a1)"; A1_REF="$(field "${A1}" orderRef)"
A2="$(order "${ACCT}"  Buy  10 "${SEED_PX}" a2)"; A2_REF="$(field "${A2}" orderRef)"
S="$( order "${ACCT2}" Sell 10 "${SEED_PX}" s )"; S_REF="$( field "${S}"  orderRef)"
CLEANUP_REFS=("${A1_REF}" "${A2_REF}" "${S_REF}")
echo "    A1 BUY  -> kind=$(field "${A1}" kind) ref=${A1_REF} status=$(field "${A1}" status)"
echo "    A2 BUY  -> kind=$(field "${A2}" kind) ref=${A2_REF} status=$(field "${A2}" status)"
echo "    S  SELL -> kind=$(field "${S}" kind) ref=${S_REF} status=$(field "${S}" status)"
for pair in "A1=${A1}" "A2=${A2}" "S=${S}"; do
  [[ "$(field "${pair#*=}" kind)" != "2" ]] || fail "${pair%%=*} was REJECTED (${pair#*=}) — a rejection is not a queue, and nothing below discriminates"
done
sleep 2
REFS1="$(quiesced_order_refs)"; T1="$(quiesced_trades)"
QD="$(health_field 0 queueDepth)"
echo "    after submission: order_refs ${REFS0} -> ${REFS1}, trades ${T0} -> ${T1}, queueDepth '${QD:-<absent>}'"

case "${EXPECT}" in
  before)
    # THE OBSERVABLE DEFECT, recorded as a measurement rather than asserted away: the crossing SELL
    # was taken as live and matched A1 on the spot, inside the window the claim says is still.
    assert_order_effects "${REFS0}" "${REFS1}" 3 "${T0}" "${T1}" 2 "the pre-mint build's immediate fill"
    red "THE OBSERVABLE DEFECT: the three orders were taken as LIVE. S crossed A1 immediately — trades ${T0} -> ${T1} (+2 legs, one match) across a window in which exactly our 3 orders were sequenced. The claim says nothing may trade while queued." ;;
  after)
    # THE CORE ASSERTION, and it runs BEFORE the queueDepth check on purpose: a missing /health
    # field is an API-shaped red, and this proof's red must be "the orders traded", not "the gauge
    # is absent". Ordering is what guarantees the reader sees the behaviour first.
    # From the library, never hand-rolled: see the header.
    assert_order_effects "${REFS0}" "${REFS1}" 3 "${T0}" "${T1}" 0 "orders queued during PRE_OPEN"
    [[ "${QD}" == "3" ]] || fail "nothing traded, but queueDepth reads '${QD:-<absent>}', not 3 — EXPECTED RED until the format-8 mint (design §5): scope §1.7 requires the depth beside the phase, and without it a halt is not a named log position"
    ok "nothing traded while queued: 3 orders sequenced, 0 trade legs, queueDepth 3"
    # decision (g): the queued order must be visible OUTSIDE the engine, and as QUEUED — not
    # missing, and not indistinguishable from a live resting order.
    ST="$(rm_status "${A1_REF}" "${ACCT}")"
    [[ "${ST}" == "QUEUED" ]] \
      || fail "decision (g): the read model shows order ${A1_REF} as '${ST:-<absent>}', not QUEUED. A pre-open order that reads NEW is indistinguishable from a live resting one to anyone watching, which is the whole reason STATUS_QUEUED was put in scope"
    ok "decision (g): the read model renders order ${A1_REF} as QUEUED" ;;
  *) fail "EXPECT must be before|after" ;;
esac

# --- 4. the open ---------------------------------------------------------------------------------
if (( PHASE_TOUCHED == 1 )); then
  RESP="$(set_phase OPEN)"; [[ "${RESP%% *}" == "2"* ]] || fail "the OPEN command answered ${RESP%% *}"
  sleep 3
  REFS2="$(quiesced_order_refs)"; T2="$(quiesced_trades)"
  echo "    at the open: order_refs ${REFS1} -> ${REFS2}, trades ${T1} -> ${T2}, queueDepth '$(health_field 0 queueDepth)'"
  # NO new refs: §1.3 assigns the ref at SEQUENCING, before the gate, so the release replays orders
  # that already hold theirs. A release that issues fresh refs has re-sequenced the queue, which
  # would break cross-epoch ref monotonicity and the client's ack correlation both.
  assert_order_effects "${REFS1}" "${REFS2}" 0 "${T1}" "${T2}" 2 "the release at the open"
  [[ "$(health_field 0 queueDepth)" == "0" ]] || fail "the queue did not drain at the open (queueDepth $(health_field 0 queueDepth))"
  ok "the open released the queue in ONE apply: 0 new order refs, exactly one match, queue drained"

  # INSERTION ORDER, which is the part a count cannot see. A1 and A2 are identical BUYs from the
  # same account at the same price; only their queue position separates them. Released A1-then-A2,
  # the SELL takes A1 (first at the level) and A2 survives. Released the other way, A2 fills.
  ST1="$(rm_status "${A1_REF}" "${ACCT}")"; ST2="$(rm_status "${A2_REF}" "${ACCT}")"
  echo "    read model: A1(${A1_REF})=${ST1:-<absent>}  A2(${A2_REF})=${ST2:-<absent>}"
  [[ "${ST1}" == "FILLED" ]] \
    || fail "insertion order: A1 (queued FIRST) reads '${ST1:-<absent>}', not FILLED. If A2 filled instead, the open released the queue in the wrong order — §1.5 makes release order a DECISION (insertion = sequencing = the FIFO the book already derives), not an accident"
  [[ "${ST2}" == "NEW" ]] \
    || fail "insertion order: A2 (queued SECOND) reads '${ST2:-<absent>}', expected NEW — it should have rested unfilled behind A1"
  ok "insertion order held: A1 (first in) FILLED, A2 (second in) still resting"
  CLEANUP_REFS=("${A2_REF}")
else
  red "the open is NOT EXERCISED on this build: there is no queue to release, and no phase command to release it with"
  # Clean up what the pre-mint build left resting: S crossed A1, so A2 is the survivor.
  CLEANUP_REFS=("${A2_REF}")
fi

# --- 5. decision (b): PRE_OPEN -> CLOSED cancels the queue ---------------------------------------
if [[ "${EXPECT}" == "after" ]]; then
  RESP="$(set_phase PRE_OPEN)"; [[ "${RESP%% *}" == "2"* ]] || fail "second PRE_OPEN answered ${RESP%% *}"
  PHASE_TOUCHED=1
  Q="$(order "${ACCT}" Buy 10 "$(python3 -c "print(f'{${SEED_PX} - 5:.3f}')")" b)"
  Q_REF="$(field "${Q}" orderRef)"
  [[ "$(field "${Q}" kind)" != "2" ]] || fail "the order to be queued was rejected: ${Q}"
  [[ "$(health_field 0 queueDepth)" == "1" ]] || fail "queueDepth is $(health_field 0 queueDepth), not 1, with one order queued"
  RESP="$(set_phase CLOSED)"; [[ "${RESP%% *}" == "2"* ]] || fail "the CLOSED command answered ${RESP%% *}"
  sleep 2
  [[ "$(health_field 0 queueDepth)" == "0" ]] \
    || fail "decision (b): the queue still holds $(health_field 0 queueDepth) order(s) after PRE_OPEN -> CLOSED. A halt that pending client orders can block is not a halt (§7b)"
  ST="$(rm_status "${Q_REF}" "${ACCT}")"
  [[ "${ST}" == "CANCELED" ]] \
    || fail "decision (b): queued order ${Q_REF} reads '${ST:-<absent>}' after the close, not CANCELED — the queue must be cancelled, each order with a session reason, never silently dropped"
  ok "decision (b): PRE_OPEN -> CLOSED cancelled the queue (order ${Q_REF} CANCELED, depth 0)"
  RESP="$(set_phase OPEN)"; [[ "${RESP%% *}" == "2"* ]] || fail "could not reopen the venue (HTTP ${RESP%% *})"
  PHASE_TOUCHED=0
  ok "the venue is OPEN again"
else
  red "decision (b) is NOT EXERCISED on this build: there is no queue for a close to cancel"
fi

# Deferred from steps 0 and 2: the phase surface must EXIST. Asserted last so the stillness verdict
# above is always the red a reader sees first, and an API-shaped red can never stand in for it.
if [[ "${EXPECT}" == "after" ]]; then
  [[ -n "${PHASE0}" ]] || fail "the member's /health reports no phase/queueDepth — EXPECTED RED until the format-8 mint (design §5), scope §1.7"
  (( PHASE_TOUCHED == 1 )) || [[ "${CODE}" == "2"* ]] \
    || fail "POST /session answered ${CODE} — EXPECTED RED until the format-8 mint (design §5): TYPE_SESSION_CONTROL and its gateway route do not exist on this build"
fi

for r in ${CLEANUP_REFS[@]+"${CLEANUP_REFS[@]}"}; do
  C="$(cancel "${r}")"
  [[ "$(field "${C}" canceled)" == "True" || "$(field "${C}" canceled)" == "true" ]] || fail "cleanup cancel of ${r} did not take: ${C}"
done
CLEANUP_REFS=()
for i in $(seq 1 45); do
  D0="$(digest 0)"; D1="$(digest 1)"; D2="$(digest 2)"
  if [[ "${D0}" =~ ^[0-9]+\ -?[0-9]+$ && "${D0}" == "${D1}" && "${D1}" == "${D2}" ]]; then
    ok "all three members agree on the book digest: ${D0}"
    [[ "${EXPECT}" == "before" ]] && { echo; echo "[RED] yu17-preopen-queue-open: the red half is BANKED. EXPECTED RED until the format-8 mint (design §5)."; }
    exit 0
  fi
  sleep 2
done
fail "members never agreed on the book digest: [${D0}] [${D1}] [${D2}]"
