#!/usr/bin/env bash
# yu17-book-retick.sh — format-8 §2.3: the empty-book re-derivation, end to end across a decade
# crossing (design §5 row 4).
#
# THE SEQUENCE (same on both arms — a check that differs between arms proves nothing):
#   1. mint a fresh ticker through the CONTROL PATH (reference-data POST /stocks -> NATS delta ->
#      gateway sequences register + enable) — deliberately NO price tick: this is the §1.1 window
#      the provisional grid exists for.
#   2. BUY @${ANCHOR_PX} -> rests: the book was created with NO reference, on the provisional
#      global grid, anchored by the limit itself (the old rule kept as the floor, ADR-066 d.1).
#   3. cancel -> the book empties.
#   4. /seed a PRICE_TICK at ${SEED_PX} (~\$1.15): the reference has crossed decades relative to
#      the grid the book froze at creation.
#   5. SELL @${PROBE_PX} (~20x the reference; non-crossing — the book is empty).
#   6. BUY @${IN_PX} (inside ±\$0.655 of the reference) -> rests; cancel.
#
#   EXPECT=before (current build): step 2 rests but /bbo has NO "tickPx" surface; step 5 is
#     ACCEPTED via an ADR-066 re-anchor (traderx_band_reanchors +1) — the grid stays frozen for
#     the epoch, only the band moved, and the 20x order rests. Measured red 2026-08-25 on
#     :yu17-markwait2 (RTK201029: rest@100, cancel, seed 1.15, SELL@22 kind 1, reanchors 0->1).
#   EXPECT=after (format-8 build): step 2 shows /bbo "tickPx":1000 (provisional); step 5 finds the
#     book EMPTY, re-derives tick 1000 -> 10 (traderx_book_reticks +1 — the metric name this proof
#     defines as the mint's contract), and is refused kind 2 PRICE_COLLAR; step 6 rests and /bbo
#     shows "tickPx":10 — the book admits at the new scale while refusing at the old, and reticks
#     does NOT move again (the counter counts re-derivations that CHANGED a tick).
#
# DETONATOR (mint chip's obligation, design §7 V4 — not runnable from this script): the after arm
# must FAIL, on exactly the reticks/tickPx assertions, against a build with the empty-admission
# re-derivation deliberately omitted. Without that run this proof is unproven.
#
# WHICH GATE ANSWERED: engine ack reason byte 22 throughout. NON-DESTRUCTIVE: resting probes on a
# ticker this run minted (nothing else quotes or rests it), cancelled on the way out including on
# failure; no trade is booked. The minted ticker stays registered — same convention as every
# time-derived throwaway prefix (DUP…, RM…, BND…), cleared at the next fresh epoch.
#
# Prerequisites: forwards to the gateway (18110) and reference-data (18085) — both owned by
# run-proofs.sh's FORWARDS block.
set -euo pipefail
CTX="${CTX:-kind-traderx-yu12-cluster}"; NS="${NS:-traderx}"
K=(kubectl --context "${CTX}" -n "${NS}")
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
REF_URL="${REF_URL:-http://localhost:18085}"
EXPECT="${EXPECT:-after}"
ACCT="${ACCT:-22214}"
TICKER="${TICKER:-RTK$(date +%H%M%S)}"
# Literal prices are CORRECT here, unlike the collar proofs' live-derived ones: this proof owns
# its ticker's whole price history (minted un-ticked, then seeded at SEED_PX by this script), so
# nothing external can walk the reference out from under the probes.
ANCHOR_PX="${ANCHOR_PX:-100.00}"
SEED_PX="${SEED_PX:-1.15}"
PROBE_PX="${PROBE_PX:-22.00}"   # ~20x SEED_PX; must stay inside today's ±$65.54 (before-arm) and
IN_PX="${IN_PX:-1.10}"          # outside ±$0.655 (after-arm); IN_PX inside ±$0.655 of SEED_PX
fail() { echo "[FAIL] $*" >&2; exit 1; }
ok() { echo "[ok] $*"; }
field() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$2',''))" <<<"$1"; }
metric() { "${K[@]}" exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
  | awk -v k="$2" 'index($1, k"{")==1 || $1==k {print $2}'; }
# The ticker's whole /bbo row (JSON), "" if absent — the tickPx assertions read keys off this.
bbo_json() { "${K[@]}" exec order-matcher-cluster-0 -- sh -c 'wget -qO- http://localhost:8080/bbo 2>/dev/null' \
  | python3 -c "
import sys,json
for b in json.load(sys.stdin)['books']:
    if b['ticker']=='${TICKER}':
        print(json.dumps(b)); break
"; }
order() { # side price
  curl -s -m20 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${ACCT},\"ticker\":\"${TICKER}\",\"side\":\"$1\",\"quantity\":10,\"limitPrice\":$2,\"clientOrderId\":\"${TICKER}-$1-$2\"}"; }
CLEANUP_REFS=()
cleanup() { local r; for r in ${CLEANUP_REFS[@]+"${CLEANUP_REFS[@]}"}; do
  curl -s -m20 -X POST "${MATCHER_URL}/cancel" -H 'Content-Type: application/json' -d "{\"orderRef\":${r}}" >/dev/null || true
done; }
trap cleanup EXIT
cancel_ok() { local C; C="$(curl -s -m20 -X POST "${MATCHER_URL}/cancel" -H 'Content-Type: application/json' -d "{\"orderRef\":$1}")"
  [[ "$(field "${C}" canceled)" == "True" || "$(field "${C}" canceled)" == "true" ]] || fail "cancel of $1 did not take: ${C}"; }
digest() { "${K[@]}" exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
  | awk '/^traderx_book_open_orders/ {d=$2} /^traderx_book_order_hash/ {h=$2} END {print d, h}'; }

echo "=== yu17-book-retick, EXPECT=${EXPECT}, ticker ${TICKER} ==="

echo "--- 1. mint ${TICKER} via the control path (register + enable, NO tick)"
CREATED="$(curl -s -m8 -o /dev/null -w '%{http_code}' -X POST "${REF_URL}/stocks" -H 'Content-Type: application/json' \
  -d "{\"ticker\":\"${TICKER}\",\"companyName\":\"Retick proof ${TICKER}\"}")"
[[ "${CREATED}" == 2* ]] || fail "reference-data POST /stocks answered ${CREATED} — is the 18085 forward up?"
APPLIED=0
for i in $(seq 1 30); do
  SNAP="$(curl -s -m8 "${MATCHER_URL}/risk/control/snapshot" 2>/dev/null)"
  [[ "${SNAP}" == *"${TICKER}"* ]] && { APPLIED=1; break; }
  sleep 2
done
[[ "${APPLIED}" == "1" ]] || fail "the control feed never applied ${TICKER} (60s) — CONTROL_FEED_SUBSCRIBER off, or NATS down; this is a precondition failure, not a verdict about the grid"
ok "control feed applied ${TICKER} (sequenced register + enable, no price)"

echo "--- 2. BUY @${ANCHOR_PX} on the never-ticked book"
O1="$(order Buy "${ANCHOR_PX}")"
[[ "$(field "${O1}" kind)" == "1" ]] || fail "the anchoring BUY @${ANCHOR_PX} did not rest: ${O1}"
REF1="$(field "${O1}" orderRef)"; CLEANUP_REFS+=("${REF1}")
ROW="$(bbo_json)"
[[ -n "${ROW}" && "${ROW}" == *'"bid"'* ]] || fail "no /bbo row with a bid for ${TICKER} after the rest (got '${ROW}')"
case "${EXPECT}" in
  before)
    [[ "${ROW}" != *'"tickPx"'* ]] || fail "current build should have NO tickPx surface, but /bbo says: ${ROW} — already on the format-8 build? run EXPECT=after"
    ok "rests on the (invisible) global grid — no tickPx surface today: ${ROW}" ;;
  after)
    [[ "${ROW}" == *'"tickPx":1000'* ]] || fail "no tickPx:1000 on the /bbo row — EXPECTED RED until the format-8 mint (design §5): the tickPx surface and re-derivation ship with the mint; got: ${ROW}"
    ok "rests on the provisional grid, visibly: ${ROW}" ;;
  *) fail "EXPECT must be before|after" ;;
esac

echo "--- 3. cancel (the book empties)"
cancel_ok "${REF1}"; CLEANUP_REFS=()

echo "--- 4. seed a tick at ${SEED_PX} (the reference crosses decades)"
SEEDED="$(curl -s -m20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
  -d "{\"accountId\":${ACCT},\"tickers\":\"${TICKER}\",\"price\":${SEED_PX}}")"
[[ "${SEEDED}" == *'"seeded":true'* ]] || fail "seed @${SEED_PX} did not take: ${SEEDED}"

R0="$(metric 0 traderx_band_reanchors)"; T0="$(metric 0 traderx_book_reticks || true)"
echo "--- 5. SELL @${PROBE_PX} (~20x the reference) — the decade-crossing probe"
O2="$(order Sell "${PROBE_PX}")"
O2_KIND="$(field "${O2}" kind)"; O2_REASON="$(field "${O2}" reason)"
echo "    -> kind=${O2_KIND} reason=${O2_REASON:-<none>}"
[[ "${O2_KIND}" != "3" && "${O2_KIND}" != "4" ]] || fail "the probe CROSSED — a trade entered the epoch: ${O2}"
R1="$(metric 0 traderx_band_reanchors)"; T1="$(metric 0 traderx_book_reticks || true)"
case "${EXPECT}" in
  before)
    [[ "${O2_KIND}" == "1" ]] || fail "current build should ACCEPT the 20x probe (band re-anchor, grid frozen); got kind=${O2_KIND} reason=${O2_REASON:-<none>}"
    CLEANUP_REFS+=("$(field "${O2}" orderRef)")
    [[ "${R0}" =~ ^[0-9]+$ && "${R1}" =~ ^[0-9]+$ && "${R1}" -gt "${R0}" ]] \
      || fail "the old mechanism should have answered: traderx_band_reanchors did not move (${R0} -> ${R1})"
    [[ -z "${T1}" ]] || fail "current build should have no traderx_book_reticks counter, read '${T1}'"
    ok "the defect, live: 20x @${PROBE_PX} RESTS via re-anchor (band_reanchors ${R0} -> ${R1}); the grid never moved"
    cancel_ok "$(field "${O2}" orderRef)"; CLEANUP_REFS=() ;;
  after)
    [[ "${O2_KIND}" == "2" ]] || { CLEANUP_REFS+=("$(field "${O2}" orderRef)"); fail "format-8 build must REFUSE the 20x probe after re-deriving; got kind=${O2_KIND} ${O2}"; }
    [[ "${O2_REASON}" == "PRICE_COLLAR" ]] || fail "refused by the WRONG gate: reason byte says ${O2_REASON:-<none>}, not PRICE_COLLAR"
    [[ "${T0}" =~ ^[0-9]+$ && "${T1}" =~ ^[0-9]+$ ]] || fail "traderx_book_reticks unreadable ('${T0}' -> '${T1}') — the mint must export it beside traderx_band_reanchors"
    [[ "${T1}" -eq $((T0 + 1)) ]] || fail "the empty admission must re-derive EXACTLY once: traderx_book_reticks ${T0} -> ${T1}"
    ok "decade crossed: re-derived (reticks ${T0} -> ${T1}) and the 20x probe is refused PRICE_COLLAR" ;;
esac

echo "--- 6. BUY @${IN_PX} (inside the new band) must rest"
O3="$(order Buy "${IN_PX}")"
[[ "$(field "${O3}" kind)" == "1" ]] || fail "inside-band BUY @${IN_PX} did not rest: ${O3}"
CLEANUP_REFS+=("$(field "${O3}" orderRef)")
if [[ "${EXPECT}" == "after" ]]; then
  ROW="$(bbo_json)"
  [[ "${ROW}" == *'"tickPx":10'* ]] || fail "after the re-derivation /bbo must show tickPx:10; got: ${ROW}"
  T2="$(metric 0 traderx_book_reticks)"
  [[ "${T2}" -eq "${T1}" ]] || fail "an admission that does not change the tick must not count as a retick (${T1} -> ${T2})"
  ok "admits at the new scale, visibly (tickPx 10), and the counter counts only CHANGED ticks"
else
  ok "inside-band BUY rests (both builds; the discriminators were steps 2 and 5)"
fi
cancel_ok "$(field "${O3}" orderRef)"; CLEANUP_REFS=()

ROW="$(bbo_json)"
[[ "${ROW}" != *'"bid"'* && "${ROW}" != *'"ask"'* ]] || fail "the ${TICKER} book is not empty after cleanup: ${ROW}"
ok "book restored empty (ticker stays registered — throwaway prefix, cleared at the next fresh epoch)"

for i in $(seq 1 30); do
  D0="$(digest 0)"; D1="$(digest 1)"; D2="$(digest 2)"
  if [[ "${D0}" =~ ^[0-9]+\ -?[0-9]+$ && "${D0}" == "${D1}" && "${D1}" == "${D2}" ]]; then
    ok "all three members agree on the book digest: ${D0}"
    exit 0
  fi
  sleep 2
done
fail "members never agreed on the book digest: [${D0}] [${D1}] [${D2}]"
