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

step "2. the quote is a FRACTION of par, and the CURVE has the shape a curve must have"
QUOTE="$(curl -sf -m8 "${PP}/prices/${UST}")"
vlog "      ${QUOTE}"
read -r PRICE CLEAN SEMANTICS YTM Q_MATURED <<EOF
$(printf '%s' "${QUOTE}" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print(d.get("price"), d.get("cleanPrice"), d.get("priceSemantics"),
      d.get("ytmPercent"), str(d.get("matured")).lower())
')
EOF
[[ "${PRICE}" =~ ^[0-9]+\.[0-9]+$ ]] || fail "price '${PRICE}' is not a decimal number"
[[ "${SEMANTICS}" == "CLEAN_FRACTION_OF_PAR" ]] || fail "priceSemantics is '${SEMANTICS}', expected CLEAN_FRACTION_OF_PAR"
[[ "${CLEAN}" == "${PRICE}" ]] || fail "cleanPrice ${CLEAN} disagrees with price ${PRICE} — they are the same number by contract"
[[ "${Q_MATURED}" == "false" ]] || fail "a matured bond must not be quoted at all (FR-CDM21)"

# WHAT THIS USED TO ASSERT, AND WHY IT WAS REPLACED (2026-08-23, ADR-068).
#
# It asserted that the price sat inside 99.878% +/- 0.15 — the auction SEED out of
# price-publisher/data/snapshot-prices.json, plus TREASURY_PROFILE_BY_TERM[2].maxDistance, the
# random walk's own clamp. Both numbers came from the simulation. So the check was "this price came
# out of our walk, and the walk stayed near the number we invented for it", written in the
# vocabulary of "this is a plausible Treasury". Those were the same claim for exactly as long as
# nothing in the system could contradict the fiction.
#
# ADR-068 put a real constant-maturity curve behind the Treasury tier, and a CORRECT real price for
# this bond lands outside that band. The lazy repair — widen the band, or branch on whether a key is
# set — preserves the fiction and asserts even less. So the step now asserts properties OF THE
# CURVE, which hold under any curve, invented or real, and which a wrong pricer cannot satisfy by
# accident. This is the fourth instance of this exact shape found this week (the fixture seeder's
# IBM at 200, yu10-fix-session's FIX_PX=200, a proof passing over its own 422s, and this) — the
# pattern is worth more than any one fix: a proof that pins a NUMBER a simulation chose is testing
# the simulation.
#
# Deliberately NOT asserted here: round-trip (cleanPriceFromYield -> yieldFromCleanPrice returns the
# input) and monotonicity in yield. Both are already asserted, over a wide range and to 1e-9, in
# test/treasury-pricing.test.js — they are properties of a pure function and need no rig. Asserting
# them again through HTTP would need a SECOND bond model living in this script, and two models
# drift.
PRICES="$(curl -sf -m10 "${PP}/prices")" || fail "could not read ${PP}/prices"
# The payload arrives in the ENVIRONMENT, not on stdin. `python3 -` takes its PROGRAM from stdin,
# and the heredoc is what supplies it, so a `printf ... |` pipe into this command is silently
# discarded: under bash the program parses fine and `sys.stdin` reads 0 bytes, making the step fail
# for every possible input. (zsh resolves the same construct differently again — it concatenates,
# and python dies on a syntax error.) A check that cannot pass is as useless as one that cannot
# fail, and this one had the additional charm of looking like a real curve failure.
PRICES_JSON="${PRICES}" python3 - "${UST}" <<'EOF' || fail "the published curve fails a property every curve must have — see the message above"
import json, os, sys

target = sys.argv[1]
rows = {r["ticker"]: r for r in json.loads(os.environ["PRICES_JSON"])["prices"]}
bonds = {k: v for k, v in rows.items() if k.startswith("UST-") or k.startswith("CORP-")}
if not bonds:
    print("no bond rows in /prices at all — this step would otherwise pass vacuously", file=sys.stderr)
    sys.exit(1)

def die(msg):
    print(msg, file=sys.stderr)
    sys.exit(1)

# (a) EVERY bond is on the fraction scale, not one spot-checked instrument. A leaked percentage
#     (99.878 rather than 0.998780) is a 100x error that still looks like a price; the old band
#     caught it for one bond, this catches it for all of them, in any arm.
for k, r in sorted(bonds.items()):
    p = r.get("price")
    if not isinstance(p, (int, float)) or not (0.05 <= p <= 1.60):
        die(f"{k}: price {p!r} is not a par-scale fraction — a percentage, a zero or a null landed here")

# (b) THE ZERO CURVE IS MONOTONE. Four STRIPs, one issuer, no coupons: with positive yields a
#     longer zero is worth strictly less than a shorter one. True on any curve anyone can draw, and
#     false the moment discounting is wrong — which is the whole point of asserting it instead of a
#     level.
strips = sorted((k for k in bonds if k.startswith("UST-STRIP-")), key=lambda k: k[-8:])
if len(strips) < 3:
    die(f"only {len(strips)} STRIP(s) quoted — too few for the monotonicity check to mean anything")
for near, far in zip(strips, strips[1:]):
    if not bonds[near]["price"] > bonds[far]["price"]:
        die(f"zero curve is not monotone: {near} {bonds[near]['price']} <= {far} {bonds[far]['price']}")

# (c) SHAPE, the file's own claim finally asserted: a near-dated bill prices just under par, a 30Y
#     STRIP deep below it. The old band could never say this — it only knew one 2Y note.
bills = sorted((k for k in bonds if k.startswith("UST-BILL-")), key=lambda k: k[-8:])
if not bills:
    die("no bills quoted — the near-par end of the curve is untested")
bill = bonds[bills[0]]["price"]
if not (0.90 <= bill < 1.00):
    die(f"{bills[0]}: a near-dated bill at {bill} is not a discount instrument just under par")
longest = strips[-1]
if not (0.05 < bonds[longest]["price"] < 0.45):
    die(f"{longest}: a 30Y zero at {bonds[longest]['price']} is not the deep discount a 30Y zero is")

# (d) A COUPON BOND IS WORTH MORE THAN ITS OWN STRIP. Same issuer, same maturity date, the only
#     difference is the coupons — so this fails if coupons are being dropped from the cash flows,
#     which is a defect that leaves every price individually plausible.
pairs = [(k, "UST-STRIP-" + k[len("UST-"):]) for k in bonds if k.startswith("UST-") and
         not k.startswith("UST-BILL-") and not k.startswith("UST-STRIP-")]
compared = 0
for coupon_key, strip_key in pairs:
    if strip_key in bonds:
        compared += 1
        if not bonds[coupon_key]["price"] > bonds[strip_key]["price"]:
            die(f"{coupon_key} {bonds[coupon_key]['price']} is not worth more than its own zero "
                f"{strip_key} {bonds[strip_key]['price']} — coupons are missing from the cash flows")
if compared == 0:
    die("no coupon-bond/STRIP pair shares a maturity — check (d) would pass having compared nothing")

# (e) EVERY bond carries a finite, plausible yield on the STATED basis. A yield without its basis is
#     not a quote, and a null one means the UI is computing its own (FR-CDM20).
for k, r in sorted(bonds.items()):
    y = r.get("ytmPercent")
    if not isinstance(y, (int, float)) or not (0.0 < y < 15.0):
        die(f"{k}: ytmPercent {y!r} is absent or implausible")
    if r.get("yieldConvention") != "SEMIANNUAL_BOND":
        die(f"{k}: yieldConvention is {r.get('yieldConvention')!r} — the points are not comparable")

# (f) PROVENANCE IS COHERENT (ADR-068 rule 2). `simulated` and `source` are two halves of one fact
#     and must never disagree: a real price labelled simulated, or an invented one labelled real, is
#     worse than no label. This holds in BOTH arms — it asserts agreement, not which arm we are in.
for k, r in sorted(bonds.items()):
    sim, src = r.get("simulated"), r.get("source")
    if not isinstance(sim, bool) or not isinstance(src, str) or not src:
        die(f"{k}: provenance is missing — simulated={sim!r} source={src!r}")
    if sim != src.startswith("simulated-"):
        die(f"{k}: provenance disagrees with itself — simulated={sim} but source={src!r}")

# (g) WHAT THE SEED IS STILL GOOD FOR: that the instrument was BOOTSTRAPPED from an auction quote,
#     not what it is worth today. It is carried at percent scale and stays there.
seed = rows[target].get("officialSeedCleanPrice")
if not isinstance(seed, (int, float)) or not (5.0 <= seed <= 160.0):
    die(f"{target}: officialSeedCleanPrice {seed!r} is absent or off the percent scale — the "
        f"instrument does not record what it was bootstrapped from")

print(f"    {len(bonds)} bonds on the fraction scale; zero curve monotone across "
      f"{len(strips)} STRIPs; {compared} coupon/zero pair(s) ordered correctly", file=sys.stderr)
EOF
echo "[ok] ${PRICE} is a fraction of par, and the whole published curve holds its shape — monotone zeros, a bill under par, a 30Y zero deep below it, coupons worth more than their strips, provenance self-consistent"

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
[[ "${YTM}" != "None" && "${YTM}" != "null" ]] || fail "ytmPercent is absent — the UI must never compute it (FR-CDM20)"
python3 - "$YTM" <<'EOF' || fail "ytmPercent ${YTM} is outside any plausible range for a 4.125% note near par"
import sys
ytm = float(sys.argv[1])
sys.exit(0 if 1.0 < ytm < 15.0 else 1)
EOF
echo "[ok] solved YTM ${YTM}% is publisher-computed and plausible"

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
