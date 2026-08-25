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
# is what makes this discriminating; a check that passes on both proves nothing.
#
# EVERY COUNTER READING HERE IS A DELTA AGAINST A CAPTURED BASELINE, and that is a REPAIR, not a
# style preference. Until 2026-08-25 this script asserted `R1 >= 1 && C1 >= 1` on the ABSOLUTE
# readings of traderx_band_reanchors / _stranded_cancels; R0/C0 were captured and never compared to
# anything. The rig read reanchors=1 / stranded_cancels=3 BEFORE the proof did anything, so the
# assertion was already satisfied on arrival — a mandatory regression guard for exactly the
# mechanism the format-8 mint changes (scope §5, last row) that could not fail, printing
# "member-0 counters did not move" as its failure text for a movement test it never performed.
# Both predicates now come from lib-consensus-readings.sh, whose selftest carries this vacuity as a
# standing red arm (`old vacuity: nothing moved`, on the real 1/3 readings).
#
# Counter choice, for the same reason: neither band counter nor the trade counter can be moved by
# the FEED (bandSlot is reached only from order placement/replace; a PRICE_TICK books no trade), but
# both are global over ORDER writers — so the trade delta is bracketed by the order-ref generator,
# and the assertion is "exactly our four orders were sequenced, and they had exactly this effect".
set -euo pipefail
CTX="${CTX:-kind-traderx-yu12-cluster}"; NS="${NS:-traderx}"
K=(kubectl --context "${CTX}" -n "${NS}")
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
EXPECT="${EXPECT:?EXPECT=before|after}"
ACCT="${ACCT:-22214}"; ACCT2="${ACCT2:-52355}"   # both accounts exist in reference data: a position on an unmapped account breaks the EOD risk extract
TICKER="${TICKER:-BND$(date +%H%M%S)}"
fail() { echo "[FAIL] $*" >&2; exit 1; }
ok() { echo "[ok] $*"; }
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/lib-consensus-readings.sh"

seed() { curl -s -m20 -o /dev/null -w '%{http_code}' -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
  -d "{\"accountId\":$1,\"tickers\":\"${TICKER}\",\"price\":$2}"; }
order() { # account side price -> body
  curl -s -m20 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
    -d "{\"accountId\":$1,\"ticker\":\"${TICKER}\",\"side\":\"$2\",\"quantity\":10,\"limitPrice\":$3,\"clientOrderId\":\"${TICKER}-$2-$3\"}"; }
# (the local trades()/metric() readers of traderx_cluster_trades and the band counters are gone:
#  every counter reading now comes from lib-consensus-readings.sh, as a delta. See the header.)
digest() { "${K[@]}" exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
  | awk '/^traderx_book_open_orders/ {d=$2} /^traderx_book_order_hash/ {h=$2} END {print d, h}'; }
ref_of() { python3 -c "import sys,json;print(json.load(sys.stdin)['orderRef'])" <<<"$1"; }
field() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$2',''))" <<<"$1"; }

echo "=== ADR-066 band-follows-market, EXPECT=${EXPECT}, ticker ${TICKER} ==="
for m in 0 1 2; do "${K[@]}" get pod "order-matcher-cluster-${m}" -o jsonpath='{.spec.containers[0].image}{"\n"}'; done | sort -u
# PER MEMBER, and that is not belt-and-braces. The band counters are NOT replicated state: they are
# plain in-process fields on MatchingEngine (:106), never written to the snapshot, so a member's
# ABSOLUTE reading is a function of how much log THAT PROCESS has applied since it started.
# Measured 2026-08-25 while writing this repair: members 0 and 1 (78m old) read reanchors=2 where
# member 2 (restarted 17m earlier, restored and replayed) read 4 — on a cluster that was in perfect
# agreement on the book digest. Cross-member EQUALITY of the absolutes is therefore a false
# assertion, and it is a second, independent reason the old `R1 >= 1` could never mean anything.
# The delta across one scenario IS replica-identical, because every member applies the same commands.
BAND0=(); for m in 0 1 2; do BAND0+=("$(band_counters "${m}")"); done
R0="${BAND0[0]%% *}"; C0="${BAND0[0]##* }"
REFS0="$(quiesced_order_refs)"; T0="$(quiesced_trades)"; O0="$(digest 0)"; O0="${O0%% *}"
echo "    baseline: band(m0,m1,m2)=[${BAND0[0]}] [${BAND0[1]}] [${BAND0[2]}] trades=${T0} order_refs=${REFS0}"

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
BAND1="$(band_counters 0)"; R1="${BAND1%% *}"; C1="${BAND1##* }"
REFS1="$(quiesced_order_refs)"; T1="$(quiesced_trades)"
echo "    after:    band_reanchors=${R1} stranded_cancels=${C1} trades=${T1} order_refs=${REFS1}"
echo "    deltas:   reanchors $((R1-R0))  stranded $((C1-C0))  trades $((T1-T0))  order_refs $((REFS1-REFS0))"

case "${EXPECT}" in
  before)
    [[ "${REAL_KIND}" == "2" && "${REAL_REASON}" == "PRICE_COLLAR" ]] \
      || fail "pre-change build should REFUSE 385 against a band pinned at 180; got ${REAL}"
    [[ "$(field "${TAKE}" kind)" == "2" ]] || fail "pre-change build accepted the 385 taker: ${TAKE}"
    # Four orders sequenced (a refused ORDER_NEW consumes a ref on apply, before any verdict), and
    # NOTHING traded. The band must not have moved either — on this build there is no re-anchor to
    # make, and a non-zero delta here would mean a foreign writer's order, not ours, moved it.
    assert_order_effects "${REFS0}" "${REFS1}" 4 "${T0}" "${T1}" 0 "the pinned-band (pre-change) arm"
    WANT_REANCHOR=0; WANT_STRAND=0
    assert_band_effects "${R0}" "${C0}" "${R1}" "${C1}" "${WANT_REANCHOR}" "${WANT_STRAND}" "the pinned-band (pre-change) arm"
    WANT_OPEN=$((O0 + 1))   # the stray bid still rests
    ok "pre-change: 385 REFUSED PRICE_COLLAR both ways, nothing traded, band never moved — the defect, reproduced" ;;
  after)
    [[ "${REAL_KIND}" != "2" ]] || fail "post-change build refused 385 against the market at 388: ${REAL}"
    [[ "$(field "${TAKE}" kind)" != "2" ]] || fail "post-change build refused the 385 taker: ${TAKE}"
    # Four orders sequenced and exactly one match. traderx_cluster_trades counts one leg per side,
    # so one match is +2 (yu13-stp-and-replace reads "6 vs 8" as one wash trade for the same reason).
    assert_order_effects "${REFS0}" "${REFS1}" 4 "${T0}" "${T1}" 2 "the re-indexed 385"
    # EXACTLY one re-anchor and EXACTLY one stranded cancel: the SELL @385 re-centres the band on
    # 388 and strands the 180 bid. The SELL @480 re-enters bandSlot but computes the SAME base and
    # returns without incrementing (MatchingEngine:862-863); the BUY @385 lands inside the new band.
    # So the scenario's count is a property of the scenario — `>= 1` would pass on a band that moved
    # twice, which the design says it must not.
    WANT_REANCHOR=1; WANT_STRAND=1
    assert_band_effects "${R0}" "${C0}" "${R1}" "${C1}" "${WANT_REANCHOR}" "${WANT_STRAND}" "the ADR-066 re-anchor"
    WANT_OPEN=${O0}   # stray cancelled; the 385 rested and was then taken
    ok "post-change: 385 ACCEPTED and TRADED (${T0} -> ${T1}), reanchors +$((R1-R0)) stranded +$((C1-C0))" ;;
  *) fail "EXPECT must be before|after" ;;
esac

# Three members, one answer. Retried: followers apply the committed tail moments later.
for i in $(seq 1 60); do
  D0="$(digest 0)"; D1="$(digest 1)"; D2="$(digest 2)"
  if [[ "${D0}" =~ ^[0-9]+\ -?[0-9]+$ && "${D0}" == "${D1}" && "${D1}" == "${D2}" ]]; then
    ok "all three members agree on the book digest: ${D0}"
    [[ "${D0%% *}" == "${WANT_OPEN}" ]] || fail "expected ${WANT_OPEN} open order(s) on the rig's books (was ${O0}), digest says ${D0%% *}"
    [[ "$(trades_booked 1)" == "${T1}" && "$(trades_booked 2)" == "${T1}" ]] || fail "members disagree on trades: $(trades_booked 0) $(trades_booked 1) $(trades_booked 2)"
    # The crossed BND position has no EOD price and would halt its accounts' P&L (yu15-risk-extract
    # reads that as a real halt). Same convention as the other throwaway prefixes in
    # seed-proof-fixtures.sh, which also clears BND… on the next fresh epoch.
    sleep 3
    "${K[@]}" exec deploy/eod-price-db -c mariadb -- mariadb -utraderx -ptraderx traderx -N -B \
      -e "DELETE FROM positions WHERE security='${TICKER}';" 2>/dev/null || echo "    (throwaway position rows not cleared; seed-proof-fixtures.sh will)"
    # Every member applied the same commands, so every member's DELTA must be the scenario's —
    # which is the reading the absolutes cannot give (see the baseline note above). Printing them
    # for context, as this line used to, lets a member that never re-anchored go by unnoticed.
    for m in 1 2; do
      B="$(band_counters "${m}")"
      echo "    member-${m}: band_reanchors=${B%% *} stranded_cancels=${B##* } (baseline [${BAND0[${m}]}])"
      assert_band_effects "${BAND0[${m}]%% *}" "${BAND0[${m}]##* }" "${B%% *}" "${B##* }" \
        "${WANT_REANCHOR}" "${WANT_STRAND}" "the ADR-066 re-anchor on member ${m}"
    done
    exit 0
  fi
  sleep 2
done
fail "members never agreed on the book digest: [${D0}] [${D1}] [${D2}]"
