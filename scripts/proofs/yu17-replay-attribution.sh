#!/usr/bin/env bash
# ADR-072: replayed prints become order flow, and the attribution counters move with them.
#
# THIS IS THE RIG HALF OF THE ADMISSION TEST `scripts/proofs/lib-consensus-readings.sh` sets for
# any new consensus reading — "name a counter the new writer does not advance, and SHOW IT
# STANDING STILL ON A LIVE RIG while that writer runs". The off-rig half (anti-vacuity on a
# freshly-built service, the snapshot round trip, the fail-closed restore) is
# ReplayFlowAttributionTest; this is the half that can only be decided against a running cluster.
#
# What is asserted, and against what:
#
#   1. THE REPLAY IS A WRITER, not a configuration. price-publisher's /health.printReplay carries
#      no error and its submitted count climbs; across the same window the GLOBAL
#      traderx_cluster_next_order_ref climbs too. If the replay is off, every arm below is
#      vacuous — "the operator counter did not move" is trivially true on a quiet rig — so this
#      step is a hard precondition and it is why "a green suite that only passes because the
#      replay happens to be off is not a green suite".
#   2. THE FOUR OPERATOR COUNTERS STAND STILL across that same window, agreed by all three
#      members. This is the promise the library now makes, measured rather than argued.
#   3. ...AND THEY STILL MOVE FOR US. Four of this proof's OWN orders, on a ticker it mints, are
#      submitted INSIDE the live replay and read back through assert_order_effects: exactly four
#      refs and exactly four trade legs. Without this arm step 2 is satisfied by a counter wired
#      to a constant, which would leave every proof's bracket unfalsifiable instead of merely
#      wrong — a strictly worse outcome than the defect ADR-072 describes.
#   4. THE ORDERS ARE THE TAPE'S. The clientOrderId of the last replayed order names its symbol
#      and its absolute tape slot, so the print sample is decoded here, independently, and the
#      submitted price / side / account are required to be exactly what that slot holds. This is
#      what separates "orders are flowing" from "the orders came from February 2025".
#   5. THE REFERENCE IS STILL THE REFERENCE (ADR-072's "what does not change"). Replayed trades
#      move a book's MARK; they must not move the collar's REF, which stays the ADR-070 median.
#   6. THE DEMO CLAIM, which is the reason for the whole change: books that have printed. The ADR
#      opens on "3 of 69 books have ever printed, six trades total".
#   7. THE SIDE SAYS IT IS INVENTED. ADR-072 requires the tick-rule side to be labelled, not
#      silent — the same discipline ADR-070 applied to asOf.
#
#   EXPECT=before (pre-change build): /health carries no printReplay block and the members export
#   no operator counters. Recorded as the measured gap, so the after-arms are known to be able to
#   fail rather than assumed to be.
#
# ROLLS NOTHING. No member, no gateway, no epoch, no PVC. It mints one ticker and leaves it flat
# (every cross is reversed), and it cancels anything it left resting on the way out.
set -uo pipefail

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K=(kubectl --context "${CTX}" -n "${NS}")
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
EXPECT="${EXPECT:-after}"
# Both exist in reference data: a position on an unmapped account breaks the EOD risk extract.
ACCT="${ACCT:-22214}"; ACCT2="${ACCT2:-52355}"
TICKER="${TICKER:-ATT$(date +%H%M%S)}"
# Long enough to contain at least one replayed order for most symbols at ~6/s, short enough that
# the suite does not wait on it. Not a tolerance: nothing below is a threshold on this number.
WINDOW_S="${WINDOW_S:-20}"

fail() { echo "[FAIL] $*" >&2; exit 1; }
ok()   { echo "[ok] $*"; }
step() { echo; echo "=== $* ==="; }

here="$(cd "$(dirname "$0")" && pwd)"; . "$here/lib-consensus-readings.sh"

pub()  { "${K[@]}" exec deploy/price-publisher -- wget -qO- "http://localhost:18100$1" 2>/dev/null; }
memb() { "${K[@]}" exec "order-matcher-cluster-${1}" -- wget -qO- "http://localhost:8080$2" 2>/dev/null; }
gmetric() { # gmetric <member> <metric-name> -- the GLOBAL counters, read deliberately and only
            # to prove the replay is running. Never as a delta about our own work.
  memb "$1" /metrics | awk -v n="$2{" 'index($1,n)==1 {print $2; f=1} END{if(!f) print -1}'
}
jget() { python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(3)
for k in sys.argv[1].split('.'):
    if isinstance(d, dict) and k in d:
        d = d[k]
    else:
        sys.exit(4)
print('' if d is None else d)
" "$1"; }
order() { # order <account> <side> <price> <tag>
  curl -s -m20 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
    -d "{\"accountId\":$1,\"ticker\":\"${TICKER}\",\"side\":\"$2\",\"quantity\":10,\"limitPrice\":$3,\"clientOrderId\":\"${TICKER}-$4\"}"
}
field() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$2',''))" <<<"$1"; }

RESTING=()
cleanup() {
  for ref in "${RESTING[@]:-}"; do
    [[ -n "${ref}" ]] && curl -s -m10 -o /dev/null -X POST "${MATCHER_URL}/cancel" \
      -H 'Content-Type: application/json' -d "{\"orderRef\":${ref}}"
  done
}
trap cleanup EXIT

step "yu17-replay-attribution, EXPECT=${EXPECT}, own ticker ${TICKER}"
for m in 0 1 2; do "${K[@]}" get pod "order-matcher-cluster-${m}" \
  -o jsonpath='{.spec.containers[0].image}{"\n"}'; done | sort -u

# ---------------------------------------------------------------------------------------------
step "1. the replay is a WRITER (a quiet rig makes every arm below vacuous)"
HEALTH="$(pub /health)"
[[ -n "${HEALTH}" ]] || fail "price-publisher /health did not answer; nothing below was measured"
PR_ERR="$(printf '%s' "${HEALTH}" | jget printReplay.error)"; PR_RC=$?
if [[ ${PR_RC} -eq 4 ]]; then
  echo "    /health carries NO printReplay block at all: this build predates ADR-072."
  [[ "${EXPECT}" == "before" ]] \
    && { ok "pre-change gap recorded: no replayed order flow, so the operator counters do not exist"; exit 0; }
  fail "EXPECT=after but the publisher does not replay prints"
fi
[[ -z "${PR_ERR}" ]] || fail "the replay is OFF: ${PR_ERR}
  A suite that goes green with the replay off has not tested the thing ADR-072 shipped."
SUBMITTED0="$(printf '%s' "${HEALTH}" | jget printReplay.submitted)"
ACCEPTED0="$(printf '%s' "${HEALTH}" | jget printReplay.accepted)"
SYMS="$(printf '%s' "${HEALTH}" | jget printReplay.symbols)"
RATE="$(printf '%s' "${HEALTH}" | jget printReplay.ordersPerSecond)"
echo "    replaying ${SYMS} symbols at ~${RATE}/s, ${SUBMITTED0} orders submitted so far"

GREF0="$(gmetric 0 traderx_cluster_next_order_ref)"
GTRD0="$(gmetric 0 traderx_cluster_trades)"
OREF0="$(quiesced_order_refs)"
OTRD0="$(quiesced_trades)"
BAND0="$(band_counters 0)"; BR0="${BAND0%% *}"; BC0="${BAND0##* }"
echo "    t0  global refs=${GREF0} trades=${GTRD0} | operator refs=${OREF0} trades=${OTRD0} band=[${BAND0}]"

sleep "${WINDOW_S}"

PR1="$(pub /health)"
SUBMITTED1="$(printf '%s' "${PR1}" | jget printReplay.submitted)"
ACCEPTED1="$(printf '%s' "${PR1}" | jget printReplay.accepted)"
GREF1="$(gmetric 0 traderx_cluster_next_order_ref)"
GTRD1="$(gmetric 0 traderx_cluster_trades)"
echo "    t1  global refs=${GREF1} trades=${GTRD1}, publisher submitted ${SUBMITTED1}"

(( SUBMITTED1 > SUBMITTED0 )) \
  || fail "the publisher submitted no orders in ${WINDOW_S}s (${SUBMITTED0} -> ${SUBMITTED1}).
  Everything below would pass on a rig where nothing is happening."
# ACCEPTED, not just submitted. A refused order still consumes a ref before any verdict, so every
# counter arm below is satisfied by a replay whose every order is being REJECTED — which is exactly
# what a fresh mint produces until the account controls are re-issued (228 UNKNOWN_ACCOUNTs,
# measured 2026-08-26). The later book-depth arm would catch it, but four steps later and pointing
# at the collar; this says it where it happened.
(( ACCEPTED1 > ACCEPTED0 )) \
  || fail "the publisher submitted $(( SUBMITTED1 - SUBMITTED0 )) order(s) in ${WINDOW_S}s and the
  venue accepted NONE of them. The counters below would still move (a ref is consumed before any
  verdict), so this arm exists to stop that reading as a working replay. The reason is on
  /health.printReplay.rejectedByReason: $(printf '%s' "${PR1}" | jget printReplay.rejectedByReason)"
(( GREF1 > GREF0 )) \
  || fail "traderx_cluster_next_order_ref did not move (${GREF0} -> ${GREF1}) while the publisher
  submitted $(( SUBMITTED1 - SUBMITTED0 )) order(s). Either they are not reaching consensus, or
  this is not the counter ADR-072 says replayed flow advances — and if it does not advance it,
  there was nothing to fix and this whole proof is measuring the wrong thing."
ok "the replay advanced the GLOBAL ref generator by $(( GREF1 - GREF0 )) in ${WINDOW_S}s \
($(( SUBMITTED1 - SUBMITTED0 )) orders submitted)"

# ---------------------------------------------------------------------------------------------
step "2. the OPERATOR counters stood still across exactly that window"
OREF1="$(quiesced_order_refs)"
OTRD1="$(quiesced_trades)"
BAND1="$(band_counters 0)"; BR1="${BAND1%% *}"; BC1="${BAND1##* }"

[[ "${OREF1}" == "${OREF0}" ]] \
  || fail "traderx_cluster_operator_next_order_ref moved ${OREF0} -> ${OREF1} while only the tape
  replay was writing. This is the counter six proofs bracket their own work with; if replayed flow
  moves it, the retreat from the ref generator bought nothing and the class is open again."
assert_band_effects "${BR0}" "${BC0}" "${BR1}" "${BC1}" 0 0 \
  "the band counters under replayed flow alone"

# THE TRADE COUNTER IS THE ONE THE REPLAY CAN LEGITIMATELY MOVE, AND SAYING SO IS THE POINT.
#
# It is scoped per LEG, by the account of the leg — so a replayed order that crosses an operator's
# ALREADY-RESTING order books one leg to each side, and the operator's leg is genuinely the
# operator's. That is the counter being right, not wrong. This arm asserted equality on its first
# suite run and went red at +1, on a rig whose fixture seeding leaves resting orders on tape
# symbols; the assertion was the thing that was wrong.
#
# What the replay CANNOT do is create an operator order — which is why the ref and band arms above
# are exact equalities and this one is not. The reading here is that the replayed legs were
# EXCLUDED: if the exclusion were broken the operator delta would be the global delta, and the two
# are separated by a whole order of magnitude at 6 orders/s. The EXACT trade assertion lives in
# step 3, where this proof's own orders are the subject and their effect is knowable.
OTRD_D=$(( OTRD1 - OTRD0 )); GTRD_D=$(( GTRD1 - GTRD0 ))
(( GTRD_D > 0 )) \
  || fail "the venue booked no trade legs at all in ${WINDOW_S}s while the replay submitted
  $(( SUBMITTED1 - SUBMITTED0 )) order(s) — with nothing trading, 'the operator counter did not
  move' is a statement about a quiet rig and not about the exclusion."
(( OTRD_D < GTRD_D )) \
  || fail "traderx_cluster_operator_trades moved by ${OTRD_D} of the ${GTRD_D} leg(s) the venue
  booked while only the tape replay was writing. If the two are equal, the replayed legs are not
  being excluded at all and assert_order_effects is reading the replay as 'what MY order did'."
if (( OTRD_D > 0 )); then
  echo "    NOTE: ${OTRD_D} operator leg(s) in the window against ${GTRD_D} total — a replayed order"
  echo "          crossed an operator order that was ALREADY RESTING (the fixture seeding leaves"
  echo "          some on tape symbols). The leg is the operator's and the counter is right."
fi
ok "operator refs ${OREF0} and band [${BAND0}] unmoved, agreed by all three members, and \
${OTRD_D} of ${GTRD_D} trade legs were the operator's, while the globals moved +$(( GREF1 - GREF0 )) refs"

# ---------------------------------------------------------------------------------------------
step "3. ...and they still move for US, inside the live replay (anti-vacuity)"
# A counter wired to a constant passes step 2. It also destroys every proof that depends on it, so
# this arm is not optional decoration — it is the half that makes step 2 mean something.
PX="${PX:-180}"
for a in "${ACCT}" "${ACCT2}"; do
  code="$(curl -s -m20 -o /dev/null -w '%{http_code}' -X POST "${MATCHER_URL}/seed" \
    -H 'Content-Type: application/json' \
    -d "{\"accountId\":${a},\"tickers\":\"${TICKER}\",\"price\":${PX}}")"
  [[ "${code}" == 2* ]] || fail "seeding ${TICKER} for ${a} answered HTTP ${code}"
done

REFS_B="$(quiesced_order_refs)"; TRD_B="$(quiesced_trades)"
# Two crosses, opposite ways round, so both accounts end FLAT and this proof leaves no position
# behind. Four order-shaped commands, four trade legs (one leg per side, per the convention
# traderx_cluster_trades counts on).
R1="$(order "${ACCT}"  Buy  "${PX}" a-buy)"
R2="$(order "${ACCT2}" Sell "${PX}" b-sell)"
R3="$(order "${ACCT2}" Buy  "${PX}" b-buy)"
R4="$(order "${ACCT}"  Sell "${PX}" a-sell)"
for r in "${R1}" "${R2}" "${R3}" "${R4}"; do
  [[ "$(field "${r}" kind)" != "2" ]] || fail "one of this proof's own orders was refused: ${r}"
  RESTING+=("$(field "${r}" orderRef)")
done
REFS_A="$(quiesced_order_refs)"; TRD_A="$(quiesced_trades)"
assert_order_effects "${REFS_B}" "${REFS_A}" 4 "${TRD_B}" "${TRD_A}" 4 \
  "four of our own orders submitted while the tape replay was writing continuously"
ok "our four orders and their four legs were attributable through a window the replay was also \
writing in (operator refs ${REFS_B} -> ${REFS_A}, trades ${TRD_B} -> ${TRD_A})"

# ---------------------------------------------------------------------------------------------
step "4. the replayed orders ARE the tape's prints, at the position the clock derives"
LAST_ID="$(pub /health | jget printReplay.lastOrder.clientOrderId)"
[[ "${LAST_ID}" =~ ^taq-([A-Z.]+)-([0-9]+)$ ]] \
  || fail "the last replayed order's clientOrderId is '${LAST_ID}', which carries no symbol and no
  tape slot. The id IS the evidence here — without it nothing ties a submitted order to a print."
LAST_SYM="${BASH_REMATCH[1]}"; LAST_SLOT="${BASH_REMATCH[2]}"
LAST_BODY="$(pub /health)"
echo "    last replayed order: ${LAST_ID}"

"${K[@]}" get secret taq-print-sample -o jsonpath='{.data.prints\.bin\.gz}' 2>/dev/null \
  | base64 -d > "${TMPDIR:-/tmp}/prints-$$.bin.gz"
[[ -s "${TMPDIR:-/tmp}/prints-$$.bin.gz" ]] \
  || fail "the taq-print-sample Secret is absent or empty, so the submitted prices cannot be
  checked against the tape. A replay whose sample nobody can read is an assertion, not a proof."

python3 - "${TMPDIR:-/tmp}/prints-$$.bin.gz" "${LAST_SYM}" "${LAST_SLOT}" \
  "$(printf '%s' "${LAST_BODY}" | jget printReplay.lastOrder.limitPrice)" \
  "$(printf '%s' "${LAST_BODY}" | jget printReplay.lastOrder.side)" \
  "$(printf '%s' "${LAST_BODY}" | jget printReplay.lastOrder.accountId)" \
  "$(printf '%s' "${LAST_BODY}" | jget printReplay.stride)" <<'PY' \
  || fail "the last replayed order is not the print the tape holds at its own slot — the mismatch
  is named on the line directly above this one"
import gzip, struct, sys

path, sym, slot, px, side, account, stride = sys.argv[1:8]
slot, stride, account = int(slot), int(stride), int(account)
buf = gzip.open(path, 'rb').read()
assert buf[:5] == b'TAQP1', 'the Secret is not a TAQP1 print sample'
slots, window, session, days, symbols, scale = struct.unpack_from('<HHIHHI', buf, 5)
off = 5 + 16 + days * 10
names = []
for _ in range(symbols):
    n = buf[off]; off += 1
    names.append(buf[off:off + n].decode()); off += n
per = days * (session // window) * slots
if sym not in names:
    sys.exit(f'the replay submitted {sym}, which the print sample does not carry')
base = off + names.index(sym) * per * 4


def ticks(s):
    return struct.unpack_from('<i', buf, base + s * 4)[0] if 0 <= s < per else 0


want_px = ticks(slot) / scale
if abs(want_px - float(px)) > 1e-9:
    sys.exit(f'{sym} slot {slot}: the tape holds {want_px}, the replay submitted {px}. The order '
             'is not the print it claims to be.')
# The tick rule, re-derived independently of the publisher's implementation of it.
p = ticks(slot)
want_side = 'Buy'
for i in range(1, 17):
    q = ticks(slot - i * stride)
    if q > 0 and q != p:
        want_side = 'Buy' if p > q else 'Sell'
        break
if want_side != side:
    sys.exit(f'{sym} slot {slot}: the tick rule over the sampled series says {want_side}, the '
             f'replay submitted {side}')
if account < 900000:
    sys.exit(f'the replayed order went out on account {account}, below the range the members '
             'attribute as external. Every operator counter above is then counting replayed flow.')
print(f'    {sym} slot {slot}: price {want_px} and side {side} match the tape, account {account}')
PY
rm -f "${TMPDIR:-/tmp}/prints-$$.bin.gz"

# The slot must also be where the CLOCK says it is — a replayer frozen on one slot would satisfy
# every assertion above. Tolerance is one whole window of slots, which is generous by design: this
# is a liveness check on the clock, not a second measurement of it (yu17-taq-replay owns that).
POS="$(printf '%s' "${LAST_BODY}" | jget printReplay.slotPos)"
DRIFT=$(( POS > LAST_SLOT ? POS - LAST_SLOT : LAST_SLOT - POS ))
(( DRIFT <= 16 )) \
  || fail "the last replayed order sat at slot ${LAST_SLOT} while the clock reads ${POS} — the
  replayer is not following the tape position it derives"
ok "the submitted price and side are the tape's, at slot ${LAST_SLOT} against a clock at ${POS}"

# ---------------------------------------------------------------------------------------------
step "5. replayed trades move the MARK and not the collar's REF (ADR-072 'what does not change')"
BBO="$(memb 0 /bbo)"
python3 - "${LAST_SYM}" <<PY || fail "the tape symbol's book does not carry the reference-and-mark pair this step needs —
  the reason is named on the line directly above this one"
import json, sys
sym = sys.argv[1]
books = json.loads('''${BBO}''')['books']
row = next((b for b in books if b['ticker'] == sym), None)
if row is None:
    sys.exit(f'{sym} has no book at all on member 0, so the replay booked nothing there')
if 'ref' not in row:
    sys.exit(f'{sym} carries no collar reference — the replay is running against a book the feed '
             'is not pricing, which is the one combination ADR-072 forbids')
if 'mark' not in row:
    sys.exit(f'{sym} has a reference but has never printed; replayed flow is not reaching the book')
print(f"    {sym}: ref {row['ref']} (the ADR-070 median), mark {row['mark']} (a replayed trade)")
PY
ok "the reference and the last trade are different numbers, which is ADR-067 question 1 holding \
under replayed flow"

# ---------------------------------------------------------------------------------------------
step "6. the books are alive (the reason ADR-072 exists)"
# RESTING DEPTH, not `mark`. The first version of this arm counted books carrying a mark and it
# COULD NOT FAIL: ADR-051 stamps the mark from a market-data tick until a book first crosses, so
# every seeded instrument on the rig — every option, every Treasury, every corporate — reports one
# whether it has ever traded or not. Measured on a rig minutes old: 66 of 66. A book with a live
# bid or ask is a book somebody has an order resting in, and on this rig only replayed flow rests
# orders, so the reading discriminates (measured the same minute: 22, every one of them a tape
# symbol, and not one option, bond or non-tape equity among them).
DEPTH="$(python3 -c "
import json, sys
books = json.load(sys.stdin)['books']
alive = [b['ticker'] for b in books if 'bid' in b or 'ask' in b]
print(len(alive), len(books), ' '.join(sorted(alive)))" <<<"${BBO}")"
read -r ALIVE TOTAL TICKERS <<<"${DEPTH}"
echo "    ${ALIVE} of ${TOTAL} books carry live depth: ${TICKERS}"
echo "    (ADR-072 opens on '3 of 69 books have ever printed, six trades total')"
(( ALIVE >= 10 )) \
  || fail "only ${ALIVE} book(s) carry any resting depth. The replay is submitting orders but they
  are not reaching the book — check /health.printReplay.rejectedByReason for what is refusing them."
ok "${ALIVE} books carry live depth and the venue's trade counter stands at ${GTRD1} legs"

# ---------------------------------------------------------------------------------------------
step "7. the invented side is labelled as invented"
SIDE_RULE="$(pub /health | jget printReplay.sideRule)"
[[ "${SIDE_RULE}" == *"tick-rule"* && "${SIDE_RULE}" == *"INVENTED"* ]] \
  || fail "printReplay.sideRule is '${SIDE_RULE}'. TAQ trades carry no side; ADR-072 requires the
  approximation to be labelled where a consumer can find it, not left to an ADR nobody reads."
ok "sideRule: ${SIDE_RULE}"

REJ="$(pub /health | jget printReplay.rejectedByReason)"
echo
echo "    replayed rejections so far: ${REJ:-none}  (PRICE_COLLAR here is the collar working on a real"
echo "    print that moved too far from the tape median — ADR-072 calls that a demonstration)"

echo
echo "[PASS] yu17-replay-attribution: replayed prints are order flow, they are the tape's own prints,"
echo "       and the four operator counters excluded them while still counting ours."
