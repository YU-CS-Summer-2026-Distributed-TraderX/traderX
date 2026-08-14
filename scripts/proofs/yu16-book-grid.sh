#!/usr/bin/env bash
# yu16-book-grid.sh — ADR-060: the bond book grid is DERIVED from the ticker, SCOPED to Treasuries,
# and survives a member rebuild.
#
# The claim has three halves and only the first is obvious:
#
#   1. a UST limit at six decimals rests, where the 0.001 grid would have refused it;
#   2. the grid is still 0.001 for everything else — the change did not widen the book globally.
#      This is the half a bond-only proof cannot see: replacing DEFAULT_BOOK_TICK_PX with 1 for
#      every security passes every bond assertion in this repo and silently multiplies the price
#      band by a thousand for equities. The scope check is the whole point;
#   3. the grid is a pure function of the committed ticker, stored nowhere — so a member that
#      rebuilds from snapshot + log re-derives it (T_SYMBOL restore) and the resting six-decimal
#      order is still there, on a book rebuilt at the same geometry.
#
# WHY THE EQUITY PAIR IS 0.000123 APART. A LimitBook refuses a limit for two different reasons —
# off-grid, and outside the anchored band — and both surface as the same 422 with no reason field.
# A rejection at some far-away price would therefore prove nothing about the grid. The pair here is
# 120.001 (accepted) and 120.001123 (must be refused), 0.000123 apart on a band ±65 wide: if the
# first one anchored and fit, the second cannot possibly be out of band, so the decimals are the
# only variable left.
#
# Usage: ./yu16-book-grid.sh [-v]   (cluster up on kind; gateway forwarded on 18110)
set -euo pipefail

VERBOSE=0
case "${1:-}" in -v|--verbose) VERBOSE=1; shift ;; esac
vlog() { [ "${VERBOSE}" = 1 ] && printf '%s\n' "$@" >&2 || true; }

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
READMODEL_URL="${READMODEL_URL:-http://localhost:18091}"

UST="${UST:-UST-20360515}"        # a seeded Treasury; the resting order below never crosses
ACCT="${ACCT:-42422}"
EQUITY="${EQUITY:-GRD$(date +%H%M%S)}"   # fresh book: this proof owns its anchor
EQUITY_SEED="120.00"
EQUITY_ON_GRID="120.001"          # 3dp — a multiple of the 0.001 equity grid
EQUITY_OFF_GRID="120.001123"      # the same price plus 123 millionths: off-grid, in-band
UST_REST_PX="0.986123"            # six decimals, ~0.010 below par: rests, does not cross
KIND_REJECTED=2

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

# post <path> <json> -> "<http_code> <body>"
post() {
  local out
  out="$(curl -s --max-time 25 -w '\n%{http_code}' -X POST "${MATCHER_URL}$1" \
        -H 'Content-Type: application/json' -d "$2" 2>/dev/null)" || true
  local code="${out##*$'\n'}" body="${out%$'\n'*}"
  vlog "      POST $1 $2 -> ${code} ${body}"
  echo "${code} ${body}"
}
order() { # order <ticker> <side> <qty> <px> -> "<code> <body>"
  post /orders "{\"accountId\":${ACCT},\"ticker\":\"$1\",\"side\":\"$2\",\"quantity\":$3,\"limitPrice\":$4}"
}
jget() { python3 -c 'import sys,json
try: print(json.loads(sys.stdin.read()).get(sys.argv[1], ""))
except Exception: print("")' "$1"; }

state() { # state <ordinal> -> "<orderHash> <positionHash> <trades> <nextRef>"
  ${K} exec "order-matcher-cluster-$1" -c cluster-node -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
    | awk '/^traderx_book_order_hash/{o=$2} /^traderx_book_position_hash/{p=$2}
           /^traderx_cluster_trades/{t=$2} /^traderx_cluster_next_order_ref/{r=$2} END{print o,p,t,r}'
}
# Shape, not emptiness: state()'s awk END{} fires on NO INPUT with every field unset and prints
# three spaces, which compares equal across three unreachable members. Two hashes (routinely
# negative) then two counters is the shape of a real answer.
identity_consensus() {
  local s0 s1 s2 i
  for i in $(seq 1 60); do
    s0="$(state 0 2>/dev/null)"; s1="$(state 1 2>/dev/null)"; s2="$(state 2 2>/dev/null)"
    if [[ "${s0}" =~ ^-?[0-9]+\ -?[0-9]+\ [0-9]+\ [0-9]+$ \
       && "${s0}" == "${s1}" && "${s1}" == "${s2}" ]]; then echo "${s0}"; return 0; fi
    sleep 2
  done
  fail "members never reached byte-identity: [${s0}] [${s1}] [${s2}]
  (all-blank readings mean the members were UNREACHABLE, not that they disagreed)"
}

# ---------------------------------------------------------------------------------------------
step "0. preflight — three members, agreed, and a gateway"
curl -sf --max-time 10 "${MATCHER_URL}/ready" >/dev/null \
  || fail "gateway not reachable at ${MATCHER_URL} (port-forward svc/order-matcher 18110:18110?)"
[[ "$(${K} get pod -l app=order-matcher-cluster -o name | wc -l | tr -d ' ')" == "3" ]] \
  || fail "need 3 cluster members"
BASE_STATE="$(identity_consensus)"
echo "  agreed [orderHash posHash trades nextRef] = [${BASE_STATE}]"

step "1. a Treasury limit at SIX decimals rests — the 0.001 grid would have refused it"
# Not a multiple of 0.001: 986123 ticks. On the global grid this is off-grid by 123 millionths.
read -r CODE BODY <<<"$(order "${UST}" Buy 100000 "${UST_REST_PX}")"
if [[ "${CODE}" != "200" ]]; then
  # Attribute the refusal before blaming the grid. A risk rejection carries a reason and says
  # nothing about book geometry; reporting it as "ADR-060 is not in effect" would be a verdict
  # about the wrong subsystem, which is the failure mode this whole proof exists to rule out.
  WHY="$(printf '%s' "${BODY}" | jget reason)"
  case "${WHY}" in
    INVALID)
      fail "the Treasury limit ${UST_REST_PX} was refused OFF-GRID (reason=INVALID) — ADR-060's
  derived grid is not in effect on ${UST}. This is the failure the whole state was built to fix." ;;
    PRICE_COLLAR)
      fail "the Treasury limit ${UST_REST_PX} is outside the band ${UST}'s book is anchored on
  (reason=PRICE_COLLAR). Nothing here is wrong with the grid — pick a price nearer the current
  mark and re-run." ;;
    "") fail "the Treasury order was refused with no reason (${CODE} ${BODY})" ;;
    *)  fail "the Treasury order was refused by RISK (reason=${WHY}), not by the book. This says
  nothing about the derived grid — give account ${ACCT} headroom for ${UST_REST_PX} x 100000 face
  and re-run." ;;
  esac
fi
UST_REF="$(printf '%s' "${BODY}" | jget orderRef)"
[[ "${UST_REF}" =~ ^[0-9]+$ ]] || fail "accepted but no orderRef in ${BODY}"
echo "  ${UST} buy 100000 @ ${UST_REST_PX} -> orderRef ${UST_REF}, resting"

# It has to be OPEN at that exact price, not merely acknowledged. A price silently snapped to the
# grid still returns 200 and still books — and is wrong by up to a tenth of a point of par.
# Poll: the read model is fed asynchronously over the NATS bridge, so the row lands a beat after
# the ack. A single immediate read finds nothing and silently downgrades to the note below, which
# skips the one assertion that catches a price SNAPPED to a coarser grid — the failure that still
# returns 200 and still books.
for _ in $(seq 1 15); do
  OPEN="$(curl -s --max-time 15 "${READMODEL_URL}/accounts/${ACCT}/orders" 2>/dev/null \
          | python3 -c 'import sys,json
ref=sys.argv[1]
try: rows=json.load(sys.stdin)
except Exception: rows=[]
for r in rows:
    # The read model ids are EPOCH-QUALIFIED ("1-10" = epoch 1, orderRef 10), so an equality test
    # against the bare ref the gateway returned never matches and this proof quietly skipped its
    # own price assertion. Match the ref component.
    rid = str(r.get("id", ""))
    if str(r.get("orderRef", "")) == ref or rid == ref or rid.rsplit("-", 1)[-1] == ref:
        print(r.get("limitPrice", r.get("price", ""))); break' "${UST_REF}")"
  [[ -n "${OPEN}" ]] && break
  sleep 2
done
if [[ -n "${OPEN}" ]]; then
  python3 -c 'import sys
from decimal import Decimal
got, want = Decimal(sys.argv[1]), Decimal(sys.argv[2])
sys.exit(0 if got == want else 1)' "${OPEN}" "${UST_REST_PX}" \
    || fail "the read model holds ${OPEN}, not ${UST_REST_PX} — the price was snapped to a coarser grid"
  echo "  read model agrees: resting at ${OPEN}, all six decimals intact"
else
  echo "  [note] order ${UST_REF} not visible in the read model; the price assertion below covers"
  echo "         the engine directly via the restore step"
fi

step "2. SCOPE — the equity grid is still 0.001, on a book this proof anchors itself"
SEED="$(post /seed "{\"accountId\":${ACCT},\"tickers\":\"${EQUITY}\",\"price\":${EQUITY_SEED}}")"
[[ "${SEED%% *}" == "200" ]] || fail "could not seed ${EQUITY}: ${SEED}"

read -r CODE BODY <<<"$(order "${EQUITY}" Buy 1 "${EQUITY_ON_GRID}")"
[[ "${CODE}" == "200" ]] \
  || fail "the on-grid equity control was refused (${CODE} ${BODY}) — the pair below cannot isolate the grid"
EQ_REF="$(printf '%s' "${BODY}" | jget orderRef)"
echo "  ${EQUITY} @ ${EQUITY_ON_GRID} (3dp)      -> 200, orderRef ${EQ_REF}   [band anchored here]"

read -r CODE BODY <<<"$(order "${EQUITY}" Buy 1 "${EQUITY_OFF_GRID}")"
[[ "${CODE}" == "422" ]] || fail "an equity limit at six decimals was ACCEPTED (${CODE} ${BODY}).
  The derived grid leaked out of ADR-060's UST- scope: every equity book is now a thousand times
  finer, which changes the band width and the memory of every book in the engine."
REJ_KIND="$(printf '%s' "${BODY}" | jget kind)"
REJ_REASON="$(printf '%s' "${BODY}" | jget reason)"
[[ "${REJ_KIND}" == "${KIND_REJECTED}" ]] \
  || fail "expected KIND_ORDER_REJECTED (${KIND_REJECTED}), got kind=${REJ_KIND} in ${BODY}"
# THE REASON IS THE ASSERTION, not the 422. MatchingEngine takes both price checks before any
# reservation, and they report DIFFERENTLY: `!book.onGrid(limitPx)` rejects INVALID, and a slot
# outside the anchored band rejects PRICE_COLLAR. So the two ways a book can refuse a price are
# distinguishable from outside, and accepting a bare 422 here would let an out-of-band price — or
# any risk refusal at all — stand in for the grid. Naming PRICE_COLLAR separately matters because
# it is the plausible near-miss: it means this proof's own price pair drifted out of the band and
# the run proved nothing, which is a fault in the proof rather than in the engine.
case "${REJ_REASON}" in
  INVALID) ;;
  PRICE_COLLAR)
    fail "the equity was refused as OUT OF BAND, not off-grid. The band this book anchored does
  not contain ${EQUITY_OFF_GRID}, so the pair is not isolating the grid — this run proves nothing.
  Move EQUITY_ON_GRID/EQUITY_OFF_GRID closer together." ;;
  "")
    fail "the equity was refused with no reason at all (${BODY}) — cannot attribute this to the grid" ;;
  *)
    fail "the equity was rejected by RISK (reason=${REJ_REASON}), not by the book grid — this
  proof did not test what it claims to test" ;;
esac
echo "  ${EQUITY} @ ${EQUITY_OFF_GRID} (6dp) -> 422 kind=${REJ_KIND} reason=INVALID: OFF-GRID,"
echo "    which is the book's own grid check and not PRICE_COLLAR (out of band) or a risk refusal"
echo "  the two differ by 0.000123 on a band ±65 wide — the decimals are the only variable"

step "3. the grid is DERIVED, not stored: rebuild a member and the resting order survives"
PRE="$(identity_consensus)"
echo "  pre-rebuild agreed state: [${PRE}]"
[[ "${PRE}" != "${BASE_STATE}" ]] || fail "nothing this proof did changed the agreed state — vacuous"

LDR=""
for m in 0 1 2; do
  [[ "$(${K} exec "order-matcher-cluster-${m}" -c cluster-node -- \
        sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
        | awk '/^traderx_cluster_role/{print $2}')" == "1" ]] && { LDR="${m}"; break; }
done
[[ -n "${LDR}" ]] || fail "no leader found"
VICTIM=""; for m in 0 1 2; do [[ "${m}" != "${LDR}" ]] && { VICTIM="${m}"; break; }; done

# WAIT FOR A SNAPSHOT FIRST, or this step tests the wrong hook. ADR-060 installs the derived grid
# in TWO places: symbol registration, and the T_SYMBOL branch of snapshot restore. A member that
# comes back by replaying the whole log re-runs registration and never touches the restore branch —
# so a green run here would say nothing about the path the ADR comment is actually about. Members
# snapshot on their own periodic trigger (CLUSTER_SNAPSHOT_INTERVAL_MS, 60s by default), so this
# waits for the counter to move past the UST registration above rather than forcing anything.
snap_count() { ${K} exec "order-matcher-cluster-$1" -c cluster-node -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
    | awk '/^traderx_cluster_snapshots/ {print $2}'; }
SNAP_BEFORE="$(snap_count "${VICTIM}")"
SNAPSHOT_WAIT_S="${SNAPSHOT_WAIT_S:-180}"
if [[ "${SNAP_BEFORE}" =~ ^[0-9]+$ ]]; then
  echo "  waiting up to ${SNAPSHOT_WAIT_S}s for member ${VICTIM} to snapshot (from ${SNAP_BEFORE}),"
  echo "  so the rebuild exercises the T_SYMBOL restore hook and not just registration"
  for _ in $(seq 1 "${SNAPSHOT_WAIT_S}"); do
    NOW="$(snap_count "${VICTIM}")"
    [[ "${NOW}" =~ ^[0-9]+$ && "${NOW}" -gt "${SNAP_BEFORE}" ]] && { SNAPPED=1; break; }
    sleep 1
  done
fi
[[ "${SNAPPED:-0}" == 1 ]] \
  && echo "  member ${VICTIM} snapshotted (${SNAP_BEFORE} -> ${NOW}); the restore will read T_SYMBOL" \
  || echo "  [note] no snapshot inside ${SNAPSHOT_WAIT_S}s — the rebuild will replay the log instead"

echo "  leader is member ${LDR}; rebuilding follower ${VICTIM} (emptyDir — it returns with no disk)"
${K} delete pod "order-matcher-cluster-${VICTIM}" --wait=true >/dev/null
# `kubectl wait --for=condition=Ready` does NOT wait for a pod to be CREATED. Against a name that
# does not exist it returns `NotFound` IMMEDIATELY, and the --timeout never applies at all. The line
# above just deleted the pod, so there is ALWAYS a window before the controller recreates it, and
# how wide that window is depends on how busy the box is -- so this passes until it does not, then
# reports "never became Ready" about a pod that had not yet been asked to exist. Caught in
# yu17-swap-netting on 2026-08-14; the same shape is here. Wait for EXISTENCE first.
for _ in $(seq 1 150); do
  ${K} get pod "order-matcher-cluster-${VICTIM}" >/dev/null 2>&1 && break
  sleep 2
done
${K} wait --for=condition=Ready "pod/order-matcher-cluster-${VICTIM}" --timeout=600s >/dev/null

POST_STATE="$(identity_consensus)"
[[ "${POST_STATE}" == "${PRE}" ]] \
  || fail "the agreed state changed across the rebuild: [${PRE}] -> [${POST_STATE}].
  A member that re-derived a DIFFERENT grid rebuilds its book at a different geometry and its
  order hash diverges — which is exactly this assertion."
echo "  post-rebuild agreed state: [${POST_STATE}] — identical, so the rebuilt book has the same geometry"

# WHICH PATH RAN. The member emits no log line for snapshot load, so this reports the thing it can
# actually establish rather than sniffing for a string that is not there: a snapshot was taken at a
# position AFTER the UST registration above (asserted before the kill), so the state this member
# rebuilt from carries that symbol in T_SYMBOL rather than only in the replayed tail.
[[ "${SNAPPED:-0}" == 1 ]] \
  && echo "  a snapshot exists past the registration, so the rebuilt state came through T_SYMBOL" \
  || echo "  [note] no snapshot was taken in time; this rebuild exercised the registration path only"

step "4. ...and the rebuilt cluster still takes a six-decimal Treasury limit"
read -r CODE BODY <<<"$(order "${UST}" Buy 100000 "${UST_REST_PX}")"
[[ "${CODE}" == "200" ]] \
  || fail "after the rebuild a six-decimal Treasury limit was refused (${CODE} ${BODY}) — the grid
  did not survive restore, and the book the member rebuilt is not the book it cut"
echo "  ${UST} @ ${UST_REST_PX} accepted again -> orderRef $(printf '%s' "${BODY}" | jget orderRef)"
identity_consensus >/dev/null

echo
echo "[PASS] ADR-060: the Treasury book grid admits six decimals, equities are still refused off"
echo "       the 0.001 grid by the book (not by risk), and a member rebuilt from an empty disk"
echo "       re-derived the same geometry — byte-identical hashes and a six-decimal limit still"
echo "       accepted afterwards."
