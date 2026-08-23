#!/usr/bin/env bash
# yu17-band-follows-market.sh — ADR-066: a book's price band follows the market, not its first order.
#
# The MSFT shape from issues/.../a-books-price-band-is-anchored-by-its-first-order.md, on a fresh
# ticker so nothing on the rig has anchored it:
#   1. seed T @ 180, BUY T @ 180          -> accepted, rests (both builds anchor the band here)
#   2. seed T @ 388                        -> the market moved; the resting 180 bid is now far off it
#   3. SELL T @ 385                        -> pre-change:  REFUSED PRICE_COLLAR (band pinned at 180)
#                                             post-change: ACCEPTED; the 180 bid is CANCELLED,
#                                                          reason PRICE_COLLAR, band re-centred on 388
#   4. SELL T @ 480                        -> refused on BOTH builds (outside ±65.5 of the market):
#                                             the collar still collars — falsification arm.
#   5. BUY T @ 385 (other account)         -> post-change: CROSSES the resting 385 (the re-indexed
#                                             order is genuinely matchable); trades +1 on every member,
#                                             and the book ends EMPTY — the 180 bid is gone.
#                                             pre-change: refused; the book still holds the 180 bid.
#
# Assertion end is the engine's own per-member counters and book digest, never a bridged read model.
# The stranded cancel's reason byte (PRICE_COLLAR) is asserted in LimitOrderBookTest, not here.
#
# EXPECT=before asserts the old verdict, EXPECT=after the new one. The SAME sequence on both builds
# is what makes this discriminating; a check that passes on both proves nothing. Stranded cancels are
# read from the engine's own per-member counters (traderx_band_reanchors / _stranded_cancels), not
# from a bridged read model, and the three members must agree on the book digest afterwards.
set -euo pipefail
CTX="${CTX:-kind-traderx-yu12-cluster}"; NS="${NS:-traderx}"
K=(kubectl --context "${CTX}" -n "${NS}")
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
EXPECT="${EXPECT:?EXPECT=before|after}"
ACCT="${ACCT:-22214}"; ACCT2="${ACCT2:-52355}"   # both accounts exist in reference data: a position on an unmapped account breaks the EOD risk extract
TICKER="${TICKER:-BND$(date +%H%M%S)}"
fail() { echo "[FAIL] $*" >&2; exit 1; }
ok() { echo "[ok] $*"; }

seed() { curl -s -m20 -o /dev/null -w '%{http_code}' -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
  -d "{\"accountId\":$1,\"tickers\":\"${TICKER}\",\"price\":$2}"; }
order() { # account side price -> body
  curl -s -m20 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
    -d "{\"accountId\":$1,\"ticker\":\"${TICKER}\",\"side\":\"$2\",\"quantity\":10,\"limitPrice\":$3,\"clientOrderId\":\"${TICKER}-$2-$3\"}"; }
trades() { metric "$1" traderx_cluster_trades; }
metric() { "${K[@]}" exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' | awk -v k="$2" 'index($1, k"{")==1 || $1==k {print $2}'; }
digest() { "${K[@]}" exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
  | awk '/^traderx_book_open_orders/ {d=$2} /^traderx_book_order_hash/ {h=$2} END {print d, h}'; }
ref_of() { python3 -c "import sys,json;print(json.load(sys.stdin)['orderRef'])" <<<"$1"; }
field() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$2',''))" <<<"$1"; }

echo "=== ADR-066 band-follows-market, EXPECT=${EXPECT}, ticker ${TICKER} ==="
for m in 0 1 2; do "${K[@]}" get pod "order-matcher-cluster-${m}" -o jsonpath='{.spec.containers[0].image}{"\n"}'; done | sort -u
R0=$(metric 0 traderx_band_reanchors || true); C0=$(metric 0 traderx_band_stranded_cancels || true)
T0=$(trades 0); O0="$(digest 0)"; O0="${O0%% *}"
echo "    member-0 before: band_reanchors=${R0:-<absent>} stranded_cancels=${C0:-<absent>} trades=${T0}"

[[ "$(seed "${ACCT}" 180)" == 2* ]] || fail "seed @180 did not take"
[[ "$(seed "${ACCT2}" 180)" == 2* ]] || fail "seed @180 for ${ACCT2} did not take"
STRAY="$(order "${ACCT}" Buy 180)"
[[ "$(field "${STRAY}" kind)" != "2" ]] || fail "the anchoring bid @180 was refused: ${STRAY}"
STRAY_REF="$(ref_of "${STRAY}")"
ok "BUY @180 rests as orderRef ${STRAY_REF} (band anchored at 180 on both builds)"

[[ "$(seed "${ACCT}" 388)" == 2* ]] || fail "seed @388 did not take"
ok "market moved: ${TICKER} seeded @388"

REAL="$(order "${ACCT2}" Sell 385)"
REAL_KIND="$(field "${REAL}" kind)"; REAL_REASON="$(field "${REAL}" reason)"
echo "    SELL @385 -> kind=${REAL_KIND} reason=${REAL_REASON:-} ${REAL}"
FAR="$(order "${ACCT2}" Sell 480)"
echo "    SELL @480 -> $(field "${FAR}" kind) $(field "${FAR}" reason) (falsification arm)"
[[ "$(field "${FAR}" kind)" == "2" && "$(field "${FAR}" reason)" == "PRICE_COLLAR" ]] \
  || fail "SELL @480 (92 off the market) should be PRICE_COLLAR on every build: ${FAR}"
ok "SELL @480 is still refused PRICE_COLLAR: the collar still collars"

TAKE="$(order "${ACCT}" Buy 385)"
echo "    BUY @385 -> kind=$(field "${TAKE}" kind) reason=$(field "${TAKE}" reason) ${TAKE}"
sleep 2
R1=$(metric 0 traderx_band_reanchors || true); C1=$(metric 0 traderx_band_stranded_cancels || true)
T1=$(trades 0)
echo "    member-0 after: band_reanchors=${R1:-<absent>} stranded_cancels=${C1:-<absent>} trades=${T1}"

case "${EXPECT}" in
  before)
    [[ "${REAL_KIND}" == "2" && "${REAL_REASON}" == "PRICE_COLLAR" ]] \
      || fail "pre-change build should REFUSE 385 against a band pinned at 180; got ${REAL}"
    [[ "$(field "${TAKE}" kind)" == "2" ]] || fail "pre-change build accepted the 385 taker: ${TAKE}"
    [[ "${T1}" == "${T0}" ]] || fail "pre-change build booked a trade: ${T0} -> ${T1}"
    WANT_OPEN=$((O0 + 1))   # the stray bid still rests
    ok "pre-change: 385 REFUSED PRICE_COLLAR both ways, nothing traded — the defect, reproduced" ;;
  after)
    [[ "${REAL_KIND}" != "2" ]] || fail "post-change build refused 385 against the market at 388: ${REAL}"
    [[ "$(field "${TAKE}" kind)" != "2" ]] || fail "post-change build refused the 385 taker: ${TAKE}"
    # traderx_cluster_trades counts one leg per side, so one match is +2 (yu13-stp-and-replace
    # reads "6 vs 8" as one wash trade for the same reason).
    [[ "${T1}" == "$((T0 + 2))" ]] || fail "the re-indexed 385 did not trade exactly once: trades ${T0} -> ${T1}"
    [[ "${R1:-0}" -ge 1 && "${C1:-0}" -ge 1 ]] || fail "member-0 counters did not move: reanchors=${R1} stranded=${C1}"
    WANT_OPEN=${O0}   # stray cancelled; the 385 rested and was then taken
    ok "post-change: 385 ACCEPTED and TRADED (${T0} -> ${T1}), reanchors=${R1} stranded=${C1}" ;;
  *) fail "EXPECT must be before|after" ;;
esac

# Three members, one answer. Retried: followers apply the committed tail moments later.
for i in $(seq 1 60); do
  D0="$(digest 0)"; D1="$(digest 1)"; D2="$(digest 2)"
  if [[ "${D0}" =~ ^[0-9]+\ -?[0-9]+$ && "${D0}" == "${D1}" && "${D1}" == "${D2}" ]]; then
    ok "all three members agree on the book digest: ${D0}"
    [[ "${D0%% *}" == "${WANT_OPEN}" ]] || fail "expected ${WANT_OPEN} open order(s) on the rig's books (was ${O0}), digest says ${D0%% *}"
    [[ "$(trades 1)" == "${T1}" && "$(trades 2)" == "${T1}" ]] || fail "members disagree on trades: $(trades 0) $(trades 1) $(trades 2)"
    # The crossed BND position has no EOD price and would halt its accounts' P&L (yu15-risk-extract
    # reads that as a real halt). Same convention as the other throwaway prefixes in
    # seed-proof-fixtures.sh, which also clears BND… on the next fresh epoch.
    sleep 3
    "${K[@]}" exec deploy/eod-price-db -c mariadb -- mariadb -utraderx -ptraderx traderx -N -B \
      -e "DELETE FROM positions WHERE security='${TICKER}';" 2>/dev/null || echo "    (throwaway position rows not cleared; seed-proof-fixtures.sh will)"
    for m in 1 2; do echo "    member-${m}: band_reanchors=$(metric "${m}" traderx_band_reanchors || echo '<absent>') stranded_cancels=$(metric "${m}" traderx_band_stranded_cancels || echo '<absent>')"; done
    exit 0
  fi
  sleep 2
done
fail "members never agreed on the book digest: [${D0}] [${D1}] [${D2}]"
