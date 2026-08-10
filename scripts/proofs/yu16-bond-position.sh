#!/usr/bin/env bash
# yu16-bond-position.sh — proves a Treasury trades through the UNCHANGED deterministic engine and
# lands in the read model with its bond arithmetic intact.
#
# The property: quantity is USD face, price is a fraction of par, the contract multiplier is 1,
# and therefore position value is face x fraction with no divisor anywhere (ADR-057). The failure
# this guards is silent: a percentage stored instead of a fraction still books, still shows a
# position, and is wrong by exactly 100x — no error, no log line, just a number a risk engine
# would believe.
#
# It also proves the boundary rejects a malformed face amount BEFORE consensus (FR-CDM16), and
# runs a negative control so a green run cannot be a green run of nothing.
#
# Usage: ./yu16-bond-position.sh   (assumes scripts/yu15/start-cluster-kind.sh has run, the
#                                   gateway is forwarded on 18110, trade-processor is up)
set -euo pipefail

VERBOSE=0
case "${1:-}" in -v|--verbose) VERBOSE=1; shift ;; esac
vlog() { [ "${VERBOSE}" = 1 ] && printf '%s\n' "$@" >&2 || true; }

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"

UST="${UST:-UST-20310630}"     # the 5Y, deliberately NOT the one the pricing proof walks
FRACTION="0.996650"            # 99.665% of par, the auction seed for this bond
FACE=100000                    # USD face; a legal amount (>=100, multiple of 100)
SELLER=42422
BUYER=22214
EXPECTED_VALUE="99665.000000"  # face x fraction, exactly — the whole point of the convention

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }
sql() { vlog "      SQL: $(printf '%s' "$1" | tr '\n' ' ' | tr -s ' ')"; ${K} exec deploy/eod-price-db -- mariadb -utraderx -ptraderx traderx -sN -e "$1" 2>&1 \
          | { grep -v "Using a password on the command line" || true; }; }

order() { # order <side> <account> <quantity> -> HTTP code on stdout
  curl -s -o /dev/null -w '%{http_code}' --max-time 20 -X POST "${MATCHER_URL}/orders" \
    -H 'Content-Type: application/json' \
    -d "{\"accountId\":${2},\"ticker\":\"${UST}\",\"side\":\"${1}\",\"quantity\":${3},\"limitPrice\":${FRACTION}}"
}

step "0. preflight — the rig, the schema width, and a clean slate for this bond"
curl -sf --max-time 10 "${MATCHER_URL}/ready" >/dev/null \
  || fail "gateway not reachable at ${MATCHER_URL} (port-forward svc/order-matcher 18110:18110?)"
${K} get deploy trade-processor >/dev/null 2>&1 || fail "trade-processor is not deployed"
# A DECIMAL(18,3) price column would round 0.996650 to 0.997 and this proof would report a wrong
# number as a pass. Verify the migration actually widened the columns — do not trust it.
SCALE="$(sql "SELECT numeric_scale FROM information_schema.columns
              WHERE table_schema='traderx' AND table_name='trades' AND column_name='price';")"
[[ "${SCALE}" =~ ^[0-9]+$ ]] || fail "could not read trades.price scale (got '${SCALE}') — is the DB reachable?"
[[ "${SCALE}" -ge 6 ]] || fail "trades.price has scale ${SCALE}; a bond fraction needs 6 (run 900-migrations.sql)"
POS_SCALE="$(sql "SELECT numeric_scale FROM information_schema.columns
                  WHERE table_schema='traderx' AND table_name='positions' AND column_name='averagecostbasis';")"
[[ "${POS_SCALE}" -ge 6 ]] || fail "positions.averagecostbasis has scale ${POS_SCALE}; needs 6"
# Start from a known population so a later count is evidence rather than coincidence.
sql "DELETE FROM trades WHERE security='${UST}'; DELETE FROM positions WHERE security='${UST}';"
RESIDUE="$(sql "SELECT COUNT(*) FROM trades WHERE security='${UST}';")"
[[ "${RESIDUE}" == "0" ]] || fail "clear did not take: ${RESIDUE} row(s) remain for ${UST}"
echo "[ok] gateway ready, price columns at scale ${SCALE}/${POS_SCALE}, no ${UST} rows to start"

step "1. the boundary rejects a malformed face amount before consensus"
# The APPLIED SEQUENCE is a cluster MEMBER's number, not the gateway's — the gateway's /health
# answers {"connected":true} and has no opinion about consensus. Reading it there would have made
# this step assert nothing; ask a member directly.
applied_seq() {
  # Port 8080 is the MEMBER's health; 18110 is the gateway's REST port and answers nothing here.
  ${K} exec order-matcher-cluster-0 -- wget -qO- localhost:8080/health 2>/dev/null \
    | python3 -c 'import sys,json;print(json.load(sys.stdin).get("applied", -1))' 2>/dev/null || echo -1
}
BEFORE_SEQ="$(applied_seq)"
[[ "${BEFORE_SEQ}" =~ ^[0-9]+$ && "${BEFORE_SEQ}" -ge 0 ]] || fail "could not read the applied sequence from order-matcher-cluster-0 (got '${BEFORE_SEQ}') — an idle-vs-unreachable rig would make step 1 meaningless"
SMALL="$(order Buy ${BUYER} 50)"
[[ "${SMALL}" == "422" ]] || fail "face 50 returned HTTP ${SMALL}, expected 422 (FR-CDM16 minimum)"
ODD="$(order Buy ${BUYER} 150)"
[[ "${ODD}" == "422" ]] || fail "face 150 returned HTTP ${ODD}, expected 422 (FR-CDM16 increment)"
AFTER_SEQ="$(applied_seq)"
[[ "${AFTER_SEQ}" =~ ^[0-9]+$ && "${AFTER_SEQ}" -ge 0 ]] || fail "could not re-read the applied sequence (got '${AFTER_SEQ}')"
[[ "${AFTER_SEQ}" == "${BEFORE_SEQ}" ]] \
  || fail "the applied sequence moved ${BEFORE_SEQ} -> ${AFTER_SEQ}: a rejected order reached consensus"
echo "[ok] face 50 and 150 both 422, and the consensus sequence never moved (${BEFORE_SEQ})"

step "2. cross ${FACE} face at ${FRACTION} of par through the real engine"
curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
  -d "{\"accountId\":${SELLER},\"tickers\":\"${UST}\",\"price\":${FRACTION}}" >/dev/null \
  || fail "seed failed for ${UST}"
for side_account in "Sell:${SELLER}" "Buy:${BUYER}"; do
  side="${side_account%%:*}"; acct="${side_account##*:}"
  code="$(order "${side}" "${acct}" "${FACE}")"
  vlog "      POST /orders ${side} acct=${acct} face=${FACE} px=${FRACTION} -> HTTP ${code}"
  [[ "${code}" == "200" ]] || fail "${side} order returned HTTP ${code} — a legal bond order was refused"
done
sleep 6
echo "[ok] both sides accepted; the cross is booked on the cluster"

step "3. the fill reached SQL with the fraction intact"
TRADE_ROWS="$(sql "SELECT COUNT(*) FROM trades WHERE security='${UST}';")"
[[ "${TRADE_ROWS}" =~ ^[0-9]+$ ]] || fail "trade count unreadable ('${TRADE_ROWS}')"
[[ "${TRADE_ROWS}" -ge 2 ]] || fail "expected both sides of the cross in SQL, found ${TRADE_ROWS} row(s)"
STORED_PRICE="$(sql "SELECT DISTINCT price FROM trades WHERE security='${UST}' LIMIT 1;")"
[[ -n "${STORED_PRICE}" ]] || fail "no stored price for ${UST}"
# The 100x guard, stated as a property: a fraction is < 2, a percentage is ~99.7. Both are
# plausible-looking numbers, which is why this is asserted rather than read.
python3 - "$STORED_PRICE" "$FRACTION" <<'EOF' || fail "stored price ${STORED_PRICE} is not the fraction ${FRACTION} — a percentage would land here and look plausible"
import sys
stored, expected = float(sys.argv[1]), float(sys.argv[2])
sys.exit(0 if abs(stored - expected) < 1e-9 else 1)
EOF
echo "[ok] ${TRADE_ROWS} trade rows; price stored as ${STORED_PRICE} (fraction, not ${FRACTION%.*}${FRACTION#0.} as a percentage)"

step "4. the position values as face x fraction, with the multiplier at 1"
QTY="$(sql "SELECT quantity FROM positions WHERE security='${UST}' AND accountid=${BUYER};")"
BASIS="$(sql "SELECT averagecostbasis FROM positions WHERE security='${UST}' AND accountid=${BUYER};")"
[[ "${QTY}" =~ ^-?[0-9]+$ ]] || fail "buyer position quantity unreadable ('${QTY}') — no position row means the fill never booked"
[[ "${QTY}" == "${FACE}" ]] || fail "buyer holds ${QTY} face, expected ${FACE}"
[[ -n "${BASIS}" ]] || fail "buyer position has no cost basis"
VALUE="$(python3 -c "print('%.6f' % (${QTY} * float('${BASIS}')))")"
[[ "${VALUE}" == "${EXPECTED_VALUE}" ]] \
  || fail "position value ${VALUE} != ${EXPECTED_VALUE} (face x fraction). A 100x error lands here."
# The seller is the mirror image: short the same face at the same basis.
SELLER_QTY="$(sql "SELECT quantity FROM positions WHERE security='${UST}' AND accountid=${SELLER};")"
[[ "${SELLER_QTY}" == "-${FACE}" ]] || fail "seller holds ${SELLER_QTY}, expected -${FACE}"
echo "[ok] buyer ${QTY} face @ ${BASIS} = \$${VALUE}; seller ${SELLER_QTY} face — multiplier 1, no divisor"

step "5. negative control — the value assertion can fail"
# An assertion never observed failing is a hypothesis. Feed step 4's check the percentage form of
# the same price and require it to be rejected.
BAD_VALUE="$(python3 -c "print('%.6f' % (${QTY} * float('${BASIS}') * 100))")"
[[ "${BAD_VALUE}" != "${EXPECTED_VALUE}" ]] \
  || fail "the value check cannot distinguish a fraction from a percentage — it proves nothing"
echo "[ok] the same position priced as a percentage would be \$${BAD_VALUE}, which the step-4 assertion rejects"

echo
echo "[PASS] yu16-bond-position: face x fraction through the unchanged engine, boundary rejection pre-consensus, 100x error detectable"
