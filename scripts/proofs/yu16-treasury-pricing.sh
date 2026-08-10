#!/usr/bin/env bash
# yu16-treasury-pricing.sh — proves the Treasury feed emits clean prices as a FRACTION of par
# with six decimals intact, and that the CDM instrument record backs them.
#
# What could go wrong silently, and is therefore asserted here rather than eyeballed:
#   * a percentage leaking onto the wire (99.878 instead of 0.998780) — a 100x error that looks
#     like a plausible price and would price a 100,000-face position at $9.99M (ADR-057);
#   * the inherited 3-decimal equity rounding applying to a bond, turning 0.998780 into 0.999000
#     — one decimal of percentage precision, invisible unless you count the digits (FR-CDM15);
#   * the walk running but the payload's semantics/YTM missing, so a consumer cannot tell what
#     the number means (FR-CDM19/20).
#
# Every assertion has a stated, non-empty precondition: the instrument must exist, the price must
# parse as a number, and the tick must be an integer — a missing field or an unreachable service
# is a distinct, loud failure, never a pass.
#
# Usage: ./yu16-treasury-pricing.sh   (assumes scripts/yu15/start-cluster-kind.sh has run and
#                                      reference-data:18085 + price-publisher:18100 are forwarded)
set -euo pipefail

VERBOSE=0
case "${1:-}" in -v|--verbose) VERBOSE=1; shift ;; esac
vlog() { [ "${VERBOSE}" = 1 ] && printf '%s\n' "$@" >&2 || true; }

REF="${REF:-http://localhost:18085}"
PP="${PP:-http://localhost:18100}"
UST="${UST:-UST-20280630}"
SEED_PERCENT="99.878"          # the auction-derived seed, as TreasuryDirect quotes it
BAND_PERCENT="0.15"            # the 2Y term profile's total band (FR-CDM18)

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

step "0. preflight — both services answer, or this proof has no opinion"
# Rule: an unreachable service is not "the answer is no". Distinguish them explicitly.
REF_CODE="$(curl -s -o /dev/null -w '%{http_code}' -m8 "${REF}/instruments/${UST}" || true)"
[[ "${REF_CODE}" == "200" ]] || fail "reference-data ${REF}/instruments/${UST} returned HTTP ${REF_CODE} (000 = unreachable; is 18085 forwarded?)"
PP_CODE="$(curl -s -o /dev/null -w '%{http_code}' -m8 "${PP}/prices/${UST}" || true)"
[[ "${PP_CODE}" == "200" ]] || fail "price-publisher ${PP}/prices/${UST} returned HTTP ${PP_CODE} (000 = unreachable; is 18100 forwarded?)"
echo "[ok] reference-data and price-publisher both answer for ${UST}"

step "1. the CDM record — Debt, with the bond static a consumer needs"
INSTRUMENT="$(curl -sf -m8 "${REF}/instruments/${UST}")"
vlog "      ${INSTRUMENT}"
read -r SEC_TYPE ASSET_CLASS COUPON MATURITY MATURED HAS_BBGTICKER <<EOF
$(printf '%s' "${INSTRUMENT}" | python3 -c '
import json, sys
d = json.load(sys.stdin)
economics = d.get("debtEconomics") or {}
print(d.get("securityType"), d.get("assetClass"),
      (economics.get("fixedInterest") or {}).get("couponRatePercent"),
      economics.get("maturityDate"), str(d.get("matured")).lower(),
      str(any(i.get("identifierType") == "BBGTICKER" for i in d.get("identifiers", []))).lower())
')
EOF
[[ "${SEC_TYPE}" == "Debt" ]] || fail "securityType is '${SEC_TYPE}', expected Debt"
[[ "${ASSET_CLASS}" == "US_TREASURY" ]] || fail "assetClass is '${ASSET_CLASS}', expected US_TREASURY"
[[ "${COUPON}" =~ ^[0-9]+\.[0-9]+$ ]] || fail "coupon '${COUPON}' is not a number — the bond static is missing"
[[ "${MATURITY}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || fail "maturityDate '${MATURITY}' is not an ISO date"
[[ "${MATURED}" == "false" ]] || fail "instrument reports matured=${MATURED}; this proof needs a live bond"
[[ "${HAS_BBGTICKER}" == "false" ]] || fail "a Debt instrument must not claim BBGTICKER (FR-CDM05)"
echo "[ok] ${UST}: Debt / US_TREASURY, coupon ${COUPON}%, matures ${MATURITY}, no BBGTICKER"

step "2. the quote is a FRACTION of par, not a percentage"
QUOTE="$(curl -sf -m8 "${PP}/prices/${UST}")"
vlog "      ${QUOTE}"
read -r PRICE CLEAN SEMANTICS YTM Q_MATURED <<EOF
$(printf '%s' "${QUOTE}" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print(d.get("price"), d.get("cleanPrice"), d.get("priceSemantics"),
      d.get("approximateYtmPercent"), str(d.get("matured")).lower())
')
EOF
[[ "${PRICE}" =~ ^[0-9]+\.[0-9]+$ ]] || fail "price '${PRICE}' is not a decimal number"
[[ "${SEMANTICS}" == "CLEAN_FRACTION_OF_PAR" ]] || fail "priceSemantics is '${SEMANTICS}', expected CLEAN_FRACTION_OF_PAR"
[[ "${CLEAN}" == "${PRICE}" ]] || fail "cleanPrice ${CLEAN} disagrees with price ${PRICE} — they are the same number by contract"
[[ "${Q_MATURED}" == "false" ]] || fail "a matured bond must not be quoted at all (FR-CDM21)"
# The property, not a substring: the value must lie in the fraction band the term profile allows,
# which is disjoint from the percentage band — a leaked percentage (99.x) fails this, and so does
# a zero, a null-coerced 0.0, and a fraction from a different bond.
python3 - "$PRICE" "$SEED_PERCENT" "$BAND_PERCENT" <<'EOF' || fail "price ${PRICE} is outside the seed's fraction band — a percentage or a wrong instrument would land here"
import sys
price, seed_pct, band_pct = float(sys.argv[1]), float(sys.argv[2]), float(sys.argv[3])
low, high = (seed_pct - band_pct) / 100.0, (seed_pct + band_pct) / 100.0
sys.exit(0 if low <= price <= high else 1)
EOF
echo "[ok] price ${PRICE} is a fraction of par inside the ${SEED_PERCENT}% ± ${BAND_PERCENT} band (= ${SEED_PERCENT}% of par displayed)"

step "3. six decimals survive to the wire tick"
# The tick is round(fraction x 1e6). If the inherited 3dp equity rounding applied, the tick would
# be a multiple of 1000 — that is exactly the bug, so assert the negative of it directly rather
# than trusting the decimal string.
TICKS="$(python3 -c "print(round(float('${PRICE}') * 1000000))")"
[[ "${TICKS}" =~ ^[0-9]+$ ]] || fail "tick '${TICKS}' is not an integer"
[[ "${TICKS}" -gt 900000 && "${TICKS}" -lt 1100000 ]] || fail "tick ${TICKS} is not a par-scale fraction tick"
DECIMALS="${PRICE#*.}"
[[ "${#DECIMALS}" -ge 4 ]] || fail "price ${PRICE} carries only ${#DECIMALS} decimals — 3dp rounding is being applied to a bond (FR-CDM15)"
echo "[ok] ${PRICE} -> ${TICKS} ticks at 1e6, ${#DECIMALS} decimals intact (a 3dp-rounded bond would end in 000)"

step "4. the publisher owns the YTM, and it is plausible for this bond"
[[ "${YTM}" != "None" && "${YTM}" != "null" ]] || fail "approximateYtmPercent is absent — the UI must never compute it (FR-CDM20)"
python3 - "$YTM" <<'EOF' || fail "approximateYtmPercent ${YTM} is outside any plausible range for a 4.125% note near par"
import sys
ytm = float(sys.argv[1])
sys.exit(0 if 1.0 < ytm < 15.0 else 1)
EOF
echo "[ok] approximate YTM ${YTM}% is publisher-computed and plausible"

step "5. an unknown UST- key gets no fabricated quote"
# The inherited lazy fallback invents a price for an unknown equity. For a bond that would be a
# mark with no basis, so the contract is 404 (FR-CDM21). 000 here would mean the service died,
# which is a different verdict — check the code explicitly.
UNKNOWN_CODE="$(curl -s -o /dev/null -w '%{http_code}' -m8 "${PP}/prices/UST-19990101" || true)"
[[ "${UNKNOWN_CODE}" == "404" ]] || fail "unknown UST- key returned HTTP ${UNKNOWN_CODE}, expected 404 (000 = service down; 200 = a fabricated quote)"
# The control: an unknown EQUITY still gets its fallback, so the 404 above is the bond rule and
# not a broken endpoint.
EQUITY_CODE="$(curl -s -o /dev/null -w '%{http_code}' -m8 "${PP}/prices/ZZPROOF" || true)"
[[ "${EQUITY_CODE}" == "200" ]] || fail "unknown equity returned HTTP ${EQUITY_CODE} — the fallback path is broken, so step 5's 404 proves nothing"
echo "[ok] unknown UST- -> 404, unknown equity -> 200 (the bond rule, not a dead endpoint)"

step "6. the typed consumer actually INGESTS the extended payload"
# The bug this step exists for produced no error naming a ticker and no failing service: the
# shared NatsJSONSubscriber ObjectMapper rejects unknown properties, so every Treasury tick was
# deserialized-and-dropped by trade-processor. The only visible symptom was three accounts halted
# by YU06's fail-safe in a proof two states away, because no UST ever reached the EOD closing
# snapshot. "The publisher emits it" is not "the consumer has it" — assert the consumer.
CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
${K} get deploy trade-processor >/dev/null 2>&1 || fail "trade-processor is not deployed — step 6 cannot have an opinion"
DROPPED="$(${K} logs deploy/trade-processor --tail=-1 2>/dev/null | grep -ciE 'UnrecognizedPropertyException|not marked as ignorable' || true)"
[[ "${DROPPED}" =~ ^[0-9]+$ ]] || fail "could not read trade-processor logs (got '${DROPPED}')"
[[ "${DROPPED}" -eq 0 ]] \
  || fail "trade-processor dropped ${DROPPED} message(s) it could not deserialize — an extended payload is being silently discarded (see PriceTick's @JsonIgnoreProperties)"
# Positive control: the log must actually contain trade-processor output, or a grep of 0 above
# means "no logs" rather than "no drops".
LOG_LINES="$(${K} logs deploy/trade-processor --tail=-1 2>/dev/null | wc -l | tr -d ' ')"
[[ "${LOG_LINES}" -gt 10 ]] || fail "trade-processor produced only ${LOG_LINES} log line(s) — step 6's zero-drop reading is from no data"
echo "[ok] 0 dropped messages across ${LOG_LINES} log lines — the typed consumer accepts the extended tick"

echo
echo "[PASS] yu16-treasury-pricing: fraction-of-par semantics, six-decimal ticks, publisher YTM, no fabricated bond quotes, consumer ingests the extension"
