#!/usr/bin/env bash
# yu16-accrued-interest.sh — ADR-061: the accrued interest the risk extract ships is arithmetically
# right, in the same unit as the mark, and derived only for the instruments that have coupons.
#
# WHY THIS NEEDS ITS OWN PROOF. yu15-risk-extract asserts the fixture is REPRODUCIBLE — that
# rebuilding it from the stored cut yields the same bytes. Reproducibility is silent about
# correctness: a wrong accrual is reproduced exactly as faithfully as a right one, on every member,
# forever. And the failure mode here is the quiet kind — an accrual emitted in percentage units
# instead of fractions of par is off by 100x and still looks like a plausible bond number.
#
# WHAT IT ASSERTS, AND WHAT MAKES EACH ONE MORE THAN A TRANSCRIPTION OF THE JAVA:
#
#   * the number is recomputed here TWO ways — forward from the last coupon date and backward from
#     the next one. Those are different algebraic routes to the same quantity, so a slip in either
#     the extract's elapsed term or in this script's copy of it breaks the agreement;
#   * the coupon schedule is checked as a PROPERTY (a whole number of six-month steps back from
#     maturity, bracketing the session date), not as a repeated calculation;
#   * the unit is checked by a BOUND: accrued interest cannot exceed one semiannual coupon. In
#     fraction-of-par units a 4.375% note accrues at most 0.021875. The same number in percentage
#     units is 2.1875 and fails the bound — so the bound is what pins ADR-057's unit, and it holds
#     without this script having to know which unit the renderer chose;
#   * a non-bond row must carry EMPTY coupon columns, so the four appended columns are proven to
#     come from the instrument join rather than being filled in for everything.
#
# Usage: ./yu16-accrued-interest.sh [-v]   (cluster up on kind; runs the real EOD chain)
set -euo pipefail

VERBOSE=0
case "${1:-}" in -v|--verbose) VERBOSE=1; shift ;; esac
vlog() { [ "${VERBOSE}" = 1 ] && printf '%s\n' "$@" >&2 || true; }

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
EXPECTED_SCHEMA="${EXPECTED_SCHEMA:-3}"

# The dev-token master secret is a Secret and the two rigs hold different values; a wrong one
# fails as an opaque 401 with nothing pointing at the secret. Read it from whichever rig CTX
# names, and fall back to the kind literal only if the lookup finds nothing.
MASTER="${EOD_MASTER_SECRET:-$(${K} get secret auth-secrets \
  -o 'jsonpath={.data.dev-token-master-secret}' 2>/dev/null | base64 -d 2>/dev/null)}"
MASTER="${MASTER:-kind-local-dev-token-secret-not-a-real-credential}"

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

step "0. preflight — the producer and the whole EOD chain"
POD="$(${K} get pod -l app=risk-extract -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)"
[[ -n "${POD}" ]] || fail "no risk-extract pod — this proof reads the fixture that pod delivers"
for d in price-publisher trade-processor position-service; do
  ${K} get deploy "${d}" >/dev/null 2>&1 || fail "${d} is not deployed — the EOD chain cannot run"
done
echo "[ok] producer=${POD}, EOD chain present"

step "1. run the real EOD chain and take the extract it produces"
BEFORE="$(${K} logs "${POD}" --tail=-1 | grep -c 'RISK-EXTRACT-READY' || true)"
TOKEN="$(${K} exec deploy/trade-processor -- sh -c 'curl -fsS -X POST http://localhost:18091/auth/dev-token \
  -H "X-Auth-Master-Secret: '"${MASTER}"'" -H "Content-Type: application/json" \
  -d "{\"subject\":\"yu16-accrual-proof\",\"accounts\":[],\"admin\":true,\"ttlSeconds\":900}"' 2>/dev/null)"
[[ -n "${TOKEN}" ]] || fail "could not mint an admin token from trade-processor"
CLOSE="$(${K} exec deploy/trade-processor -- sh -c \
  "curl -fsS -X POST 'http://localhost:18091/eod/session/close' -H 'Authorization: Bearer ${TOKEN}'" 2>/dev/null)"
[[ -n "${CLOSE}" ]] || fail "/eod/session/close returned nothing"
SESSION_DATE="$(printf '%s' "${CLOSE}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["sessionDate"])')"
STATUS="$(printf '%s' "${CLOSE}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["status"])')"
[[ "${STATUS}" == "PUBLISHED" ]] || fail "session close did not publish (status ${STATUS})"

for _ in $(seq 1 60); do
  AFTER="$(${K} logs "${POD}" --tail=-1 | grep -c 'RISK-EXTRACT-READY' || true)"
  [[ "${AFTER}" -gt "${BEFORE}" ]] && break
  sleep 2
done
[[ "${AFTER:-0}" -gt "${BEFORE}" ]] || { ${K} logs "${POD}" --tail=30; fail "no RISK-EXTRACT-READY"; }
READY="$(${K} logs "${POD}" --tail=-1 | grep 'RISK-EXTRACT-READY' | tail -1 | sed 's/^RISK-EXTRACT-READY //')"
URI="$(printf '%s' "${READY}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["uri"])')"
N="$(printf '%s' "${READY}"   | python3 -c 'import sys,json;print(json.load(sys.stdin)["consensusSequence"])')"
echo "[ok] session ${SESSION_DATE} PUBLISHED; extract at sequence ${N} -> ${URI}"

TMP="$(mktemp -d)"; trap 'rm -rf "${TMP}"' EXIT
if [[ "${URI}" == gs://* ]]; then
  gcloud storage cp "${URI}" "${TMP}/extract.csv" >/dev/null 2>&1 || fail "could not download ${URI}"
else
  ${K} exec "${POD}" -- sh -c "cat '${URI#file:}'" > "${TMP}/extract.csv" 2>/dev/null \
    || fail "could not read ${URI} out of ${POD}"
fi
[[ -s "${TMP}/extract.csv" ]] || fail "the fixture is empty"

step "2. schema ${EXPECTED_SCHEMA}, with the two accrual columns actually present"
grep -q "schema=${EXPECTED_SCHEMA}" "${TMP}/extract.csv" \
  || fail "fixture is not schema=${EXPECTED_SCHEMA}: $(grep -m1 '^# traderx-risk-extract' "${TMP}/extract.csv")
  (a proof that reads a fixture cannot tell WHICH build produced it unless it checks — this is
  the check; an old producer still renders a byte-reproducible file)"
HDR="$(grep -m1 '^accountId,' "${TMP}/extract.csv")" || fail "no column header row in the fixture"
[[ "${HDR}" == *",coupon,maturityDate,lastCouponDate,accruedInterestFraction" ]] \
  || fail "header does not end in the four bond columns: ${HDR}"
echo "[ok] $(grep -m1 '^# traderx-risk-extract' "${TMP}/extract.csv")"

step "3. the accrual is right, two independent ways, on every Treasury row"
python3 - "${TMP}/extract.csv" "${SESSION_DATE}" <<'PY' || exit 1
import sys, csv
from decimal import Decimal, ROUND_HALF_EVEN
from datetime import date

path, session_s = sys.argv[1], sys.argv[2]
session = date.fromisoformat(session_s)
TICK = Decimal("0.000001")

def minus_months(d, n):
    """LocalDate.minusMonths semantics: shift the month, then clamp the day to the new length."""
    m = d.month - 1 - n
    y = d.year + m // 12
    m = m % 12 + 1
    for day in range(d.day, 0, -1):
        try:
            return date(y, m, day)
        except ValueError:
            continue
    raise AssertionError("unreachable")

rows = [r for r in csv.reader(l for l in open(path) if not l.startswith("#"))]
hdr, rows = rows[0], rows[1:]
i = {n: k for k, n in enumerate(hdr)}
bonds = [r for r in rows if r[i["instrumentType"]] == "TREASURY"]
others = [r for r in rows if r[i["instrumentType"]] != "TREASURY"]

if not bonds:
    print("[FAIL] the extract carries no TREASURY row, so every assertion below would pass having")
    print("       checked nothing. Hold a Treasury position and re-run (yu16-bond-position seeds one).")
    sys.exit(1)

problems = []
for r in bonds:
    sec      = r[i["security"]]
    coupon   = Decimal(r[i["coupon"]])
    maturity = date.fromisoformat(r[i["maturityDate"]])
    last_s   = r[i["lastCouponDate"]]
    accrued  = Decimal(r[i["accruedInterestFraction"]])
    clean    = Decimal(r[i["closingMark"]])

    # At or past maturity the final coupon has paid and nothing has accrued since. Mirrored here
    # because without it the loop below would walk FORWARD past maturity and invent a coupon
    # period that does not exist — this script would then disagree with a correct extract. None of
    # the seeded notes mature before 2028, so this branch is insurance, not the common path.
    if session >= maturity:
        if last_s != maturity.isoformat() or accrued != Decimal(0):
            problems.append(f"{sec}: matured on {maturity}; expected lastCouponDate {maturity} and "
                            f"accrued 0, got {last_s} and {accrued}")
        continue

    # --- the schedule, as a property: a whole number of six-month steps back from maturity,
    #     bracketing the session date. Nothing here recomputes the accrual.
    k = 0
    last = maturity
    while last > session:
        k += 1
        last = minus_months(maturity, 6 * k)
    nxt = minus_months(maturity, 6 * (k - 1))
    if last_s != last.isoformat():
        problems.append(f"{sec}: lastCouponDate {last_s} is not {last} "
                        f"(= maturity - {6*k} months, the largest step at or before {session})")
        continue
    if not (last <= session < nxt):
        problems.append(f"{sec}: {session} is not inside the coupon period [{last}, {nxt})")
        continue

    semi   = coupon / Decimal(200)              # annual % -> fraction, halved
    period = Decimal((nxt - last).days)
    fwd = (semi * Decimal((session - last).days) / period).quantize(TICK, ROUND_HALF_EVEN)
    # The same quantity reached from the other end. An error in either elapsed term shows up as a
    # disagreement between these two, which a single transcribed formula could never reveal.
    bwd = (semi * (Decimal(1) - Decimal((nxt - session).days) / period)).quantize(TICK, ROUND_HALF_EVEN)

    if fwd != bwd:
        problems.append(f"{sec}: this script's own two routes disagree ({fwd} vs {bwd}) — the check is unsound")
        continue
    if accrued != fwd:
        problems.append(f"{sec}: extract says {accrued}, ACT/ACT(ICMA) from {last} to {session} "
                        f"over {period} days at coupon {coupon}% is {fwd}")
        continue
    # The unit, as a bound: one semiannual coupon is the most that can ever have accrued. The same
    # number in percentage units is 100x this and cannot pass.
    if not (Decimal(0) <= accrued < semi):
        problems.append(f"{sec}: accrued {accrued} outside [0, {semi}) — one semiannual coupon is "
                        f"the ceiling; a value above it means the column is not a fraction of par")
        continue
    dirty = clean + accrued
    if not (Decimal("0.5") < dirty < Decimal("1.5")):
        problems.append(f"{sec}: dirty price {dirty} = clean {clean} + accrued {accrued} is nowhere "
                        f"near par — clean and accrued are not in the same unit")
        continue
    # face x fraction, no divisor (ADR-057), on the row the risk engine actually reads.
    mv = (Decimal(r[i["quantity"]]) * clean * Decimal(r[i["contractMultiplier"]])).quantize(Decimal("0.000001"))
    if mv != Decimal(r[i["marketValue"]]):
        problems.append(f"{sec}: marketValue {r[i['marketValue']]} != face x fraction x multiplier = {mv}")
        continue
    print(f"  {sec:<14} coupon {coupon}%  period [{last} .. {nxt})  {(session-last).days}/{period} elapsed")
    print(f"  {'':<14} accrued {accrued} == {fwd} (forward) == {bwd} (backward),  "
          f"ceiling {semi},  dirty {dirty}")

# The four columns must come from the instrument join. If they were filled in for everything, the
# bond assertions above would still pass and the column would mean nothing.
filled = [r[i["security"]] for r in others
          if any(r[i[c]] for c in ("coupon", "maturityDate", "lastCouponDate", "accruedInterestFraction"))]
if filled:
    problems.append(f"non-Treasury rows carry bond columns: {filled[:5]} — the columns are not "
                    f"populated by the static join")

if problems:
    print("[FAIL]")
    for p in problems:
        print("  " + p)
    sys.exit(1)

print(f"  {len(bonds)} Treasury row(s) checked; {len(others)} non-bond row(s) carry empty bond columns")
PY

echo
echo "[PASS] ADR-061: every Treasury row's accrued interest reproduces from its own coupon and"
echo "       maturity by two independent routes, sits inside one semiannual coupon (so it is a"
echo "       fraction of par, not a percentage), adds to the clean mark to give a dirty price at"
echo "       par, and the coupon columns appear only on instruments the static join calls bonds."
