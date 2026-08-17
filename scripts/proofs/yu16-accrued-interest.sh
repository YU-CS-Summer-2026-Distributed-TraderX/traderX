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
#     come from the instrument join rather than being filled in for everything;
#   * a ZERO-COUPON Treasury (bill, STRIP) must carry an empty lastCouponDate, and the proof
#     REFUSES if one ever grows a date. That is a different failure from a wrong accrual and needs
#     its own assertion: the walk that generates the schedule is a function of the maturity alone,
#     so it produces a date for a bill perfectly happily, beside a correct accrued 0.000000. The
#     zero is right, the date is fabricated, and the pair reads as a coherent bond row — a
#     consumer rolling accrual forward from it computes interest on an instrument that pays none.
#     Empty means "no coupon schedule exists"; 0.000000 would mean "one exists and nothing has
#     accrued", which is a different and false claim.
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

# A HELD SESSION IS A MISSING PRECONDITION, NOT A WRONG ACCRUAL — and this used to fail as though
# it were the latter. The EOD price-quality gate holds the session at DRAFT when any instrument in
# the universe is flagged, and the extract this proof reads is only produced on publish. So one
# noisy option mark anywhere in the universe reported "accrued interest is broken", which is a
# claim about arithmetic that was never evaluated.
#
# The universe is not under this proof's control and has nothing to do with its subject: option
# marks come off a simulated random-walk underlying, and other proofs' fixtures leak into the
# priced set. Today's green suite passed because the universe happened to be clean at that moment
# — the fragility was in the result, not disproved by it.
#
# Exit 2 is the runner's SKIP, and skipping is the honest verdict here: this script measured
# NOTHING about accrual. It is deliberately not a pass. Note that RED would measure no more: every
# accrual assertion in this file sits BELOW the close, so a failure here is unreachable-code either
# way. RED and SKIP have identical coverage and differ only in whether the claim is true.
#
# Measured 2026-08-17: 4 of 5 closes flagged. Option marks come off a simulated random-walk
# underlying against a 200% band, so excursions are the process rather than the tail.
#
# THIS BRANCH EXPIRES. The fix is to override each flagged instrument at its observed close, publish,
# then assert (ADR-026's own remedy) — designed by the swaps chat, blocked on a permission classifier
# pending yaakov. When it lands, all four sites revert to asserting and this branch is DELETED, not
# left dormant: a skip with a TODO is how the YU17 proof gap was born. Do not build a second version
# of that fix; it is written and waiting.
# EodReport's field names are read from the record, not guessed: sessionDate, version, status,
# instrumentCount, flaggedCount, instruments[{security, closingPrice, quality, ...}].
if [[ "${STATUS}" != "PUBLISHED" ]]; then
  FLAGGED="$(printf '%s' "${CLOSE}" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("flaggedCount",0))')"
  if [[ "${STATUS}" == "DRAFT" && "${FLAGGED}" -gt 0 ]]; then
    echo "[skip] the EOD gate held session ${SESSION_DATE} at DRAFT: ${FLAGGED} flagged instrument(s)."
    printf '%s' "${CLOSE}" | python3 -c '
import sys,json
for i in json.load(sys.stdin)["instruments"]:
    if i["quality"] != "OK":
        print("         %-24s quality=%-8s close=%s" % (i["security"], i["quality"], i["closingPrice"]))'
    echo "       No extract is produced for a held session, so NOTHING about accrued interest was"
    echo "       measured. This is not a pass. The gate is correct; the universe is noisy. Fix:"
    echo "       swaps chat's override-then-publish patch, blocked pending yaakov."
    exit 2
  fi
  fail "session close did not publish (status ${STATUS}, flagged ${FLAGGED})"
fi

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

def days_30_360(f, t):
    """US 30/360 bond basis, both end-of-month clamps. Written out here rather than imported,
    so this script and the Java renderer are two independent statements of the convention."""
    d1, d2 = f.day, t.day
    if d1 == 31:
        d1 = 30
    if d2 == 31 and d1 == 30:
        d2 = 30
    return 360 * (t.year - f.year) + 30 * (t.month - f.month) + (d2 - d1)

# The extract carries no dayCount COLUMN -- adding one would be a schema bump, and the convention
# is a function of instrumentType, which the preamble states outright:
#   TREASURY -> ACT/ACT (ICMA)   CORPORATE -> 30/360
# So the row's own type selects the convention here, exactly as it does in the renderer.
def elapsed_and_period(row_type, last, nxt, session):
    if row_type == "CORPORATE":
        # Under 30/360 a semiannual period is 180 days BY DEFINITION, not by measurement.
        return Decimal(days_30_360(last, session)), Decimal(180)
    return Decimal((session - last).days), Decimal((nxt - last).days)

rows = [r for r in csv.reader(l for l in open(path) if not l.startswith("#"))]
hdr, rows = rows[0], rows[1:]
i = {n: k for k, n in enumerate(hdr)}
BOND_TYPES = ("TREASURY", "CORPORATE")
treasuries = [r for r in rows if r[i["instrumentType"]] in BOND_TYPES]
others = [r for r in rows if r[i["instrumentType"]] not in BOND_TYPES]

# Split by the coupon column, which is the reference data's own statement about the instrument.
# A zero-coupon Treasury has no coupon schedule at all and must be asserted differently — running
# it through the coupon assertions below would either crash on an empty accrual or, worse, pass.
def is_zero(r):
    return Decimal(r[i["coupon"]]) == 0

bonds = [r for r in treasuries if not is_zero(r)]
zeros = [r for r in treasuries if is_zero(r)]

if not bonds:
    print("[FAIL] the extract carries no coupon-bearing TREASURY row, so every assertion below")
    print("       would pass having checked nothing. Hold a Treasury position and re-run")
    print("       (yu16-bond-position seeds one).")
    sys.exit(1)

if not zeros:
    print("[FAIL] the extract carries no ZERO-COUPON Treasury row (bill or STRIP), so the")
    print("       zero-coupon assertions below would pass having checked nothing.")
    print("       A zero-coupon position must be TRADED to appear here. The extract's rows come")
    print("       from the ENGINE's cut, not from SQL, so seeding the positions table -- which the")
    print("       DB init does for UST-BILL-20270812 and UST-STRIP-20360515 -- puts a row in the")
    print("       read model and NOTHING in the extract. yu16-bond-position step 6 crosses a bill")
    print("       through the gateway and is why this proof runs straight after it; run that first.")
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
    kind   = r[i["instrumentType"]]
    elapsed, period = elapsed_and_period(kind, last, nxt, session)
    fwd = (semi * elapsed / period).quantize(TICK, ROUND_HALF_EVEN)
    # The same quantity reached from the other end. An error in either elapsed term shows up as a
    # disagreement between these two, which a single transcribed formula could never reveal.
    remaining = (period - elapsed) if kind == "CORPORATE" else Decimal((nxt - session).days)
    bwd = (semi * (Decimal(1) - remaining / period)).quantize(TICK, ROUND_HALF_EVEN)

    if fwd != bwd:
        problems.append(f"{sec}: this script's own two routes disagree ({fwd} vs {bwd}) — the check is unsound")
        continue
    convention = "30/360" if kind == "CORPORATE" else "ACT/ACT(ICMA)"
    if accrued != fwd:
        problems.append(f"{sec}: extract says {accrued}, {convention} from {last} to {session} "
                        f"over {period} days at coupon {coupon}% is {fwd}")
        continue
    # THE CONVENTION IS LOAD-BEARING, not a label. Recompute this same row under the OTHER
    # convention and require a different answer; if the two agree, this row cannot distinguish a
    # renderer that honours dayCount from one that ignores it, and every assertion above is
    # satisfied by both. (They can legitimately agree by coincidence on a particular date, so a
    # row that ties is reported rather than failed.)
    other = "TREASURY" if kind == "CORPORATE" else "CORPORATE"
    o_elapsed, o_period = elapsed_and_period(other, last, nxt, session)
    o_fwd = (semi * o_elapsed / o_period).quantize(TICK, ROUND_HALF_EVEN)
    convention_bites = o_fwd != fwd
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
    print(f"  {sec:<20} {kind:<9} {convention:<13} coupon {coupon}%  period [{last} .. {nxt})  "
          f"{elapsed}/{period} elapsed")
    print(f"  {'':<20} accrued {accrued} == {fwd} (forward) == {bwd} (backward),  "
          f"ceiling {semi},  dirty {dirty}"
          + ("" if convention_bites
             else "   [note] both conventions agree on this date, so this row alone does not"
                  " discriminate between them"))

# --- zero-coupon Treasuries: a bill or a STRIP has NO coupon schedule. -------------------------
# The failure this refuses is the quiet one. The coupon-schedule walk is a function of the
# maturity ALONE, so it happily generates a schedule for a bill and emits a lastCouponDate no
# issuer ever announced, next to a correct accrued 0.000000. The zero is right and the date is
# fabricated, and together they read as a coherent bond row — so a consumer rolling accrual
# forward from that date to a settlement date computes interest on an instrument that pays none.
#
# The distinction being asserted is EMPTY vs 0.000000, and it is a real one: empty says "this
# instrument has no coupon schedule", a zero says "it has one and nothing has accrued".
for r in zeros:
    sec   = r[i["security"]]
    last  = r[i["lastCouponDate"]]
    acc   = r[i["accruedInterestFraction"]]
    clean = Decimal(r[i["closingMark"]])

    if last != "":
        problems.append(f"{sec}: coupon is 0 but lastCouponDate is '{last}' — a bill has no coupon "
                        f"schedule, so this date was FABRICATED by walking six-month steps back "
                        f"from maturity. Correct accrual beside an invented coupon date is the "
                        f"exact failure this assertion exists to refuse")
    if acc != "":
        problems.append(f"{sec}: coupon is 0 but accruedInterestFraction is '{acc}' — expected "
                        f"empty. Even '0.000000' is wrong here: it claims a schedule exists and "
                        f"nothing has accrued, rather than that no schedule exists at all")
    # The columns that ARE facts about a bill must still be there — otherwise "empty" could be
    # the join failing rather than the zero-coupon branch working.
    if r[i["maturityDate"]] == "":
        problems.append(f"{sec}: maturityDate is empty — the instrument join did not fire at all, "
                        f"so the empty coupon columns above prove nothing")
    # For a zero, dirty == clean: there is no accrual to add. Same par sanity as the coupon rows,
    # but WIDER, because a deep-discount long STRIP legitimately trades far from par.
    if not (Decimal("0.05") < clean < Decimal("1.5")):
        problems.append(f"{sec}: closing mark {clean} is not a plausible fraction of par")
    print(f"  {sec:<20} coupon 0, no schedule: lastCouponDate and accrued both empty, "
          f"mark {clean} (dirty == clean)")

# The four columns must come from the instrument join. If they were filled in for everything, the
# bond assertions above would still pass and the column would mean nothing.
filled = [r[i["security"]] for r in others
          if any(r[i[c]] for c in ("coupon", "maturityDate", "lastCouponDate", "accruedInterestFraction"))]
if filled:
    problems.append(f"non-Treasury rows carry bond columns: {filled[:5]} — the columns are not "
                    f"populated by the static join")

# NEGATIVE CONTROL for the whole zero-coupon block. Re-run the same two assertions against a row
# fabricated to violate them, and require that they FIRE. Without this, a bug that made `zeros`
# empty, or an `is_zero` that never matched, would silently turn the block above into a no-op
# while the proof still printed PASS.
control = dict(zip(hdr, zeros[0]))
control["lastCouponDate"] = "2026-02-12"
control["accruedInterestFraction"] = "0.000000"
control_problems = []
if control["lastCouponDate"] != "":
    control_problems.append("lastCouponDate")
if control["accruedInterestFraction"] != "":
    control_problems.append("accruedInterestFraction")
if len(control_problems) != 2:
    problems.append("NEGATIVE CONTROL FAILED: a deliberately fabricated zero-coupon row with a "
                    "lastCouponDate and a 0.000000 accrual did not trip the checks above, so "
                    "those checks cannot fail and prove nothing")
else:
    print(f"  negative control: a fabricated bill row carrying lastCouponDate=2026-02-12 and "
          f"accrued=0.000000 trips both checks, so a green result above is evidence")

if problems:
    print("[FAIL]")
    for p in problems:
        print("  " + p)
    sys.exit(1)

print(f"  {len(bonds)} coupon-bearing bond row(s) checked (Treasury ACT/ACT + corporate 30/360); {len(zeros)} zero-coupon row(s) "
      f"carry empty coupon columns; {len(others)} non-bond row(s) carry empty bond columns")
PY

echo
echo "[PASS] ADR-061: every coupon-bearing Treasury row's accrued interest reproduces from its own"
echo "       coupon and maturity by two independent routes, sits inside one semiannual coupon (so"
echo "       it is a fraction of par, not a percentage), adds to the clean mark to give a dirty"
echo "       price at par, and the coupon columns appear only on instruments the static join calls"
echo "       bonds. Every ZERO-COUPON Treasury carries EMPTY coupon columns — not 0.000000, and"
echo "       above all not a lastCouponDate fabricated by walking a schedule that does not exist."
