#!/usr/bin/env bash
# yu17-swaption-terms.sh — the phase-2 proof: a swaption's option terms are what make it the
# instrument it is, and they survive to the risk file.
#
# The property, sharper than the swap one. Book a EUROPEAN and a BERMUDAN payer swaption that are
# identical in every other respect — same account, same direction, same notional, same strike, same
# underlying dates, same conventions, same expiry. They are indistinguishable:
#
#   * to the position model, which sees neither of them at all;
#   * to a swap-shaped record, which has no column that differs;
#   * to any consumer reading a file that does not publish the exercise style.
#
# And they are not the same instrument. A Bermudan can be exercised on a schedule of dates and a
# European only on one, so it is worth materially more. Publishing them identically would be
# stating something false about one of them, with no error anywhere.
#
# So this proves, at one consensus sequence:
#   1. both bookings were SEQUENCED THROUGH CONSENSUS — the applied sequence moves by exactly two
#   2. the contracts artifact distinguishes them, on the exercise style and nothing else
#   3. every column describing the UNDERLYING swap is byte-identical between the two rows
#   4. a swaption carries SWAPTION with its expiry, and a swap in the same file carries SWAP with
#      those columns empty — one artifact, two products
#   5. neither reaches the netted position extract
#   6. the artifact still rebuilds byte-identically from the stored cut alone
#
# Usage: ./yu17-swaption-terms.sh   (assumes scripts/yu15/start-cluster-kind.sh has run and the
#                                    gateway is forwarded on 18110)
set -euo pipefail

VERBOSE=0
case "${1:-}" in -v|--verbose) VERBOSE=1; shift ;; esac
vlog() { [ "${VERBOSE}" = 1 ] && printf '%s\n' "$@" >&2 || true; }

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
EOD_MASTER_SECRET="${EOD_MASTER_SECRET:-kind-local-dev-token-secret-not-a-real-credential}"

ACCOUNT=22214
SEED_TICKER="${SEED_TICKER:-AAPL}"
NOTIONAL=25000000             # 25mm underlying; identical on both legs
STRIKE="0.0415"               # identical on both legs — only the style differs
EXPIRY="2027-02-15"
EFFECTIVE="2027-02-15"        # the option expires into the swap on its effective date
MATURITY="2032-02-15"
CONVENTIONS="USD-SOFR-1Y-ACT360"

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

extract_pod() { ${K} get pod -l app=risk-extract -o jsonpath='{.items[0].metadata.name}'; }

applied_seq() {
  ${K} exec "order-matcher-cluster-${1}" -- wget -qO- localhost:8080/health 2>/dev/null \
    | python3 -c 'import sys,json;print(json.load(sys.stdin).get("applied", -1))' 2>/dev/null || echo -1
}

# The applied sequence, read only once ALL THREE members agree on it. Sampling one member races
# with catch-up: a member that has just restored from a snapshot reports the position it restored
# to while the others are already past it, and a "+2" delta measured across that gap is a statement
# about replication lag, not about how many commands were sequenced. (Observed here: member 0 at
# 22927 with engineApplied -1 while 1 and 2 were at 22929.) This is the same quiesce rule the
# cross-member digest follows.
quiesced_seq() {
  local tries=0 a b c
  while (( tries < 60 )); do
    a="$(applied_seq 0)"; b="$(applied_seq 1)"; c="$(applied_seq 2)"
    if [[ "${a}" =~ ^[0-9]+$ && "${a}" == "${b}" && "${b}" == "${c}" ]]; then
      printf '%s' "${a}"; return 0
    fi
    tries=$((tries + 1)); sleep 2
  done
  fail "the three members never agreed on an applied sequence (last: ${a:-?} ${b:-?} ${c:-?})"
}

json_field() { python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('$1',''))"; }

# `|| true` and the 000 guards below: under `set -e` a curl that fails to CONNECT aborts at the
# assignment, before the status guard runs. 000 means NO ANSWER, which is not a refusal.
book_swaption() { # book_swaption <style> <clientOrderId> -> "<code> <body>"
  local body
  body="$(curl -s -w '\n%{http_code}' --max-time 25 -X POST "${MATCHER_URL}/swaptions" \
    -H 'Content-Type: application/json' \
    -d "{\"clientOrderId\":\"${2}\",\"accountId\":${ACCOUNT},\"payReceive\":\"Pay\",
         \"notional\":${NOTIONAL},\"fixedRate\":${STRIKE},\"effectiveDate\":\"${EFFECTIVE}\",
         \"maturityDate\":\"${MATURITY}\",\"conventions\":\"${CONVENTIONS}\",
         \"expiryDate\":\"${EXPIRY}\",\"exerciseStyle\":\"${1}\"}" || true)"
  [[ -n "${body}" ]] || { echo "000 {}"; return 0; }
  echo "$(echo "${body}" | tail -1) $(echo "${body}" | sed '$d' | tr -d '\n')"
}

step "0. preflight — the rig, and a build that knows what a swaption is"
curl -sf --max-time 10 "${MATCHER_URL}/ready" >/dev/null \
  || fail "gateway not reachable at ${MATCHER_URL} (port-forward svc/order-matcher 18110:18110?)"
[[ "$(${K} get pod -l app=order-matcher-cluster -o name | wc -l | tr -d ' ')" == "3" ]] \
  || fail "need 3 cluster members"
POD="$(extract_pod)"; [[ -n "${POD}" ]] || fail "no risk-extract pod"
# The rig can be a commit behind its own tree, and phase 1 also carries SwapConventions — so probe
# for a class that exists ONLY in phase 2's build. A marker shared with the previous phase would
# report a phase-1 image as current.
MARKER=/opt/app/classes/finos/traderx/ordermatcher/lmax/SwapConventions.class
${K} exec order-matcher-cluster-0 -- test -f "${MARKER}" \
  || fail "the running member image predates YU17 entirely; rebuild and roll"
# `grep -qa` on the class file, not `strings`: the runtime image carries no binutils either (the
# same shape as the missing javap — see this proof's sibling), so a strings-based probe reports
# "phase-1 build" for every image including a correct one. The style name is a constant-pool UTF-8
# entry, so grep finds it directly.
${K} exec order-matcher-cluster-0 -- sh -c "grep -qa BERMUDAN ${MARKER}" \
  || fail "the running member image has no BERMUDAN in SwapConventions — it is a YU17 PHASE-1 build; rebuild and roll"
# …and the probe must be able to say no.
if ${K} exec order-matcher-cluster-0 -- sh -c "grep -qa NOTASTYLE ${MARKER}"; then
  fail "the phase-2 build check matches a string that cannot be there — it would pass against any image"
fi
curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
  -d "{\"accountId\":${ACCOUNT},\"tickers\":\"${SEED_TICKER}\",\"price\":150.00}" >/dev/null \
  || fail "seed failed — account ${ACCOUNT} would not be enabled and every booking would be refused"
echo "[ok] gateway ready, 3 members, a phase-2 image, account ${ACCOUNT} enabled"

step "1. the boundary refuses an unknown exercise style, and never sequences it"
BEFORE_BAD="$(quiesced_seq)"
[[ "${BEFORE_BAD}" =~ ^[0-9]+$ ]] || fail "applied sequence unreadable"
read -r BAD_CODE BAD_BODY <<<"$(book_swaption "Asian" "yu17-badstyle-$(date -u +%s)")"
[[ "${BAD_CODE}" == "000" ]] && fail "the booking got NO answer (curl 000) — the gateway or forward is down"
[[ "${BAD_CODE}" == "400" ]] \
  || fail "an unknown exerciseStyle returned HTTP ${BAD_CODE}, expected 400: ${BAD_BODY}"
AFTER_BAD="$(quiesced_seq)"
[[ "${AFTER_BAD}" == "${BEFORE_BAD}" ]] \
  || fail "the sequence moved ${BEFORE_BAD} -> ${AFTER_BAD}: an unrepresentable term reached consensus"
echo "[ok] 'Asian' refused 400 and the consensus sequence never moved (${BEFORE_BAD})"

step "2. book the pair: payer European and payer Bermudan, identical in every other term"
RUN_ID="$(date -u +%s)"
SEQ_BEFORE="$(quiesced_seq)"
[[ "${SEQ_BEFORE}" =~ ^[0-9]+$ ]] || fail "applied sequence unreadable before the pair"
read -r EUR_CODE EUR_BODY <<<"$(book_swaption "European" "yu17-eur-${RUN_ID}")"
[[ "${EUR_CODE}" == "000" ]] && fail "European booking got NO answer (curl 000)"
[[ "${EUR_CODE}" == "200" ]] || fail "European swaption returned HTTP ${EUR_CODE}: ${EUR_BODY}"
read -r BER_CODE BER_BODY <<<"$(book_swaption "Bermudan" "yu17-ber-${RUN_ID}")"
[[ "${BER_CODE}" == "000" ]] && fail "Bermudan booking got NO answer (curl 000)"
[[ "${BER_CODE}" == "200" ]] || fail "Bermudan swaption returned HTTP ${BER_CODE}: ${BER_BODY}"
EUR_ID="$(echo "${EUR_BODY}" | json_field contractId)"
BER_ID="$(echo "${BER_BODY}" | json_field contractId)"
[[ "${EUR_ID}" =~ ^SWPT-[0-9]+$ ]] || fail "European contract id '${EUR_ID}' is not SWPT-<seq>"
[[ "${BER_ID}" =~ ^SWPT-[0-9]+$ ]] || fail "Bermudan contract id '${BER_ID}' is not SWPT-<seq>"
[[ "${EUR_ID}" != "${BER_ID}" ]] || fail "both legs got ${EUR_ID} — they are not distinct contracts"
SEQ_AFTER="$(quiesced_seq)"
[[ "$((SEQ_AFTER - SEQ_BEFORE))" == "2" ]] \
  || fail "the sequence moved ${SEQ_BEFORE} -> ${SEQ_AFTER}; two bookings must be exactly two sequenced commands"
echo "[ok] ${EUR_ID} European and ${BER_ID} Bermudan, sequenced through consensus (+2)"

step "3. book an ordinary swap too, so the artifact has to carry both products"
SWAP_CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 25 -X POST "${MATCHER_URL}/swaps" \
  -H 'Content-Type: application/json' \
  -d "{\"clientOrderId\":\"yu17-plain-${RUN_ID}\",\"accountId\":${ACCOUNT},\"payReceive\":\"Receive\",
       \"notional\":${NOTIONAL},\"fixedRate\":${STRIKE},\"effectiveDate\":\"${EFFECTIVE}\",
       \"maturityDate\":\"${MATURITY}\",\"conventions\":\"${CONVENTIONS}\"}" || true)"
[[ "${SWAP_CODE}" == "000" ]] && fail "the swap booking got NO answer (curl 000)"
[[ "${SWAP_CODE}" == "200" ]] || fail "the plain swap returned HTTP ${SWAP_CODE}"
echo "[ok] a plain swap booked alongside, on the same account and the same terms"

step "4. run the real EOD chain so the extract fires"
BEFORE_READY="$(${K} logs "${POD}" --tail=-1 | grep -c 'RISK-EXTRACT-READY' || true)"
[[ "${BEFORE_READY}" =~ ^[0-9]+$ ]] || fail "could not count prior RISK-EXTRACT-READY lines"
TOKEN="$(${K} exec deploy/trade-processor -- sh -c 'curl -fsS -X POST http://localhost:18091/auth/dev-token \
  -H "X-Auth-Master-Secret: '"${EOD_MASTER_SECRET}"'" \
  -H "Content-Type: application/json" \
  -d "{\"subject\":\"yu17-swaption-proof\",\"accounts\":[],\"admin\":true,\"ttlSeconds\":900}"' 2>/dev/null)"
[[ -n "${TOKEN}" ]] || fail "could not mint an admin token from trade-processor"
CLOSE="$(${K} exec deploy/trade-processor -- sh -c \
  "curl -fsS -X POST 'http://localhost:18091/eod/session/close' -H 'Authorization: Bearer ${TOKEN}'" 2>/dev/null)"
CLOSE_STATUS="$(echo "${CLOSE}" | json_field status)"
# The gate holds the WHOLE session at DRAFT on one flagged instrument (FR-EOD23), and option marks
# off the simulated random-walk underlying clear even the 200% band on MOST closes (measured
# 2026-08-17: 4 of 5). That is not a property of swaptions and not under this proof's control —
# see yu17-swap-netting.sh step 4 for the full account. Resolve it the operator's way: override
# each flagged mark at its own observed close, publish (ADR-026: a correction is a new version,
# never an in-place edit), then assert. MISSING still fails — no mark at all is a price-chain gap,
# not a strict band.
if [[ "${CLOSE_STATUS}" != "PUBLISHED" ]]; then
  SESSION_DATE="$(echo "${CLOSE}" | json_field sessionDate)"
  [[ -n "${SESSION_DATE}" ]] || fail "close returned ${CLOSE_STATUS} and no sessionDate: ${CLOSE}"
  FLAGGED="$(echo "${CLOSE}" | python3 -c 'import json,sys
for p in json.load(sys.stdin)["instruments"]:
    if p.get("flagged"):
        print("%s\t%s\t%s" % (p["security"], p["quality"], p.get("closingPrice")))')"
  [[ -n "${FLAGGED}" ]] \
    || fail "close returned ${CLOSE_STATUS} with nothing flagged — publication was blocked by something else"
  echo "   session ${SESSION_DATE} is ${CLOSE_STATUS}; $(echo "${FLAGGED}" | wc -l | tr -d ' ') instrument(s) flagged"
  while IFS=$'\t' read -r SEC QUAL PX; do
    [[ "${PX}" != "None" && -n "${PX}" ]] \
      || fail "${SEC} is ${QUAL} with no mark — the EOD chain could not price it at all"
    echo "   overriding ${SEC} (${QUAL}) at its own observed close ${PX}"
    ${K} exec deploy/trade-processor -- sh -c \
      "curl -fsS -X POST 'http://localhost:18091/eod/prices/${SESSION_DATE}/override' \
        -H 'Authorization: Bearer ${TOKEN}' -H 'Content-Type: application/json' \
        -d '{\"security\":\"${SEC}\",\"price\":${PX},\"reason\":\"yu17-swaption-terms: accepted the observed mark\"}'" \
      </dev/null >/dev/null 2>&1 || fail "override of ${SEC} was rejected"
  done <<< "${FLAGGED}"
  CLOSE="$(${K} exec deploy/trade-processor -- sh -c \
    "curl -fsS -X POST 'http://localhost:18091/eod/prices/${SESSION_DATE}/publish' \
      -H 'Authorization: Bearer ${TOKEN}'" </dev/null 2>/dev/null)"
  CLOSE_STATUS="$(echo "${CLOSE}" | json_field status)"
fi
[[ "${CLOSE_STATUS}" == "PUBLISHED" ]] || fail "session close did not publish (status '${CLOSE_STATUS}')"
for _ in $(seq 1 60); do
  AFTER_READY="$(${K} logs "${POD}" --tail=-1 | grep -c 'RISK-EXTRACT-READY' || true)"
  [[ "${AFTER_READY}" -gt "${BEFORE_READY}" ]] && break
  sleep 2
done
[[ "${AFTER_READY:-0}" -gt "${BEFORE_READY}" ]] || { ${K} logs "${POD}" --tail=30; fail "no RISK-EXTRACT-READY"; }
READY="$(${K} logs "${POD}" --tail=-1 | grep 'RISK-EXTRACT-READY' | tail -1 | sed 's/^RISK-EXTRACT-READY //')"
N="$(echo "${READY}" | json_field consensusSequence)"
CUT_SHA="$(echo "${READY}" | json_field cutSha256)"
URI="$(echo "${READY}" | json_field uri)"
CONTRACTS_URI="$(echo "${READY}" | json_field contractsUri)"
CONTRACTS_SCHEMA="$(echo "${READY}" | json_field contractsSchema)"
[[ "${N}" =~ ^[0-9]+$ ]] || fail "announcement carried no consensusSequence: ${READY}"
[[ "${CONTRACTS_SCHEMA}" == "2" ]] \
  || fail "contracts artifact is schema '${CONTRACTS_SCHEMA}', expected 2 (the option columns)"
echo "[ok] extract at N=${N}, contracts schema ${CONTRACTS_SCHEMA}, cut ${CUT_SHA:0:12}…"

step "5. all three members rendered the identical state at N"
SHAS=""
for i in 0 1 2; do
  LINE="$(${K} logs "order-matcher-cluster-${i}" --tail=-1 | grep "RISK-EXTRACT-CUT seq=${N} " | tail -1)"
  [[ -n "${LINE}" ]] || fail "member ${i} never rendered a cut at seq=${N}"
  SHA="$(echo "${LINE}" | sed -n 's/.*sha256=\([0-9a-f]\{64\}\).*/\1/p')"
  [[ "${SHA}" =~ ^[0-9a-f]{64}$ ]] || fail "member ${i} cut line carried no 64-hex sha256: ${LINE}"
  echo "  member ${i}: ${SHA}"
  SHAS="${SHAS}${SHA}"$'\n'
done
[[ "$(printf '%s' "${SHAS}" | sort -u | wc -l | tr -d ' ')" == "1" ]] \
  || fail "members disagree on the cut at seq=${N}"
[[ "$(printf '%s' "${SHAS}" | head -1)" == "${CUT_SHA}" ]] || fail "announced cutSha256 does not match"
echo "[ok] one portfolio at ${N}, agreed by all three members"

step "6. THE HEADLINE — the two swaptions differ on the exercise style and NOTHING else"
CONTRACTS_FILE="${CONTRACTS_URI#file:}"
${K} exec "${POD}" -- test -f "${CONTRACTS_FILE}" || fail "contracts artifact ${CONTRACTS_FILE} does not exist"
CSV="$(${K} exec "${POD}" -- cat "${CONTRACTS_FILE}")"
[[ -n "${CSV}" ]] || fail "contracts artifact is empty"
EUR_ROW="$(printf '%s\n' "${CSV}" | grep "^${EUR_ID}," || true)"
BER_ROW="$(printf '%s\n' "${CSV}" | grep "^${BER_ID}," || true)"
[[ -n "${EUR_ROW}" ]] || fail "${EUR_ID} is absent from the artifact"
[[ -n "${BER_ROW}" ]] || fail "${BER_ID} is absent from the artifact"
echo "  ${EUR_ROW}"
echo "  ${BER_ROW}"
python3 - "${EUR_ROW}" "${BER_ROW}" "${EXPIRY}" "${NOTIONAL}" "${STRIKE}" <<'PY' \
  || fail "the two swaption rows do not differ in exactly the exercise style"
import sys
eur, ber, expiry, notional, strike = sys.argv[1:6]
e, b = eur.strip().split(","), ber.strip().split(",")
assert len(e) == 16 and len(b) == 16, f"expected 16 columns, got {len(e)}/{len(b)}"
# Columns 1..12 describe the UNDERLYING and the counterparty; 13 is the product; 14 the expiry.
for i in list(range(1, 15)):
    assert e[i] == b[i], f"column {i} differs: {e[i]} vs {b[i]} — these are not identical underlyings"
assert e[15] == "EUROPEAN", f"exerciseStyle {e[15]} != EUROPEAN"
assert b[15] == "BERMUDAN", f"exerciseStyle {b[15]} != BERMUDAN"
assert e[13] == "SWAPTION" and b[13] == "SWAPTION", "productType must be SWAPTION"
assert e[14] == expiry, f"expiryDate {e[14]} != {expiry}"
assert e[3] == notional, f"notional {e[3]} is not the UNDERLYING notional {notional}"
assert float(e[4]) == float(strike), f"fixedRate {e[4]} is not the strike {strike}"
assert e[2] == "PAY_FIXED", "a payer swaption pays fixed on the underlying"
PY
echo "[ok] fourteen identical columns, one different: the style is the whole instrument"

step "7. one artifact, two products — the swap beside them carries SWAP and empty option columns"
SWAP_ROWS="$(printf '%s\n' "${CSV}" | grep "^SW-.*,SWAP,," || true)"
[[ -n "${SWAP_ROWS}" ]] \
  || fail "no SW- row with productType SWAP and empty option columns — the two products are not sharing one file"
SWAPTION_ROWS="$(printf '%s\n' "${CSV}" | grep -c "^SWPT-.*,SWAPTION," || true)"
[[ "${SWAPTION_ROWS}" -ge 2 ]] || fail "expected at least 2 SWAPTION rows, found ${SWAPTION_ROWS}"
echo "  $(printf '%s\n' "${SWAP_ROWS}" | tail -1)"
echo "[ok] SWAP rows leave expiryDate and exerciseStyle empty; ${SWAPTION_ROWS} SWAPTION rows fill them"

step "8. neither product reached the netted position extract"
POSITIONS_FILE="${URI#file:}"
POSITIONS_CSV="$(${K} exec "${POD}" -- cat "${POSITIONS_FILE}")"
POS_SCHEMA="$(printf '%s\n' "${POSITIONS_CSV}" | sed -n 's/^# traderx-risk-extract schema=\([0-9]*\)$/\1/p')"
[[ "${POS_SCHEMA}" == "3" ]] || fail "netted extract schema is '${POS_SCHEMA}', must stay at 3 (D3)"
LEAKED="$(printf '%s\n' "${POSITIONS_CSV}" | grep -c "^[0-9]*,SWPT\?-" || true)"
[[ "${LEAKED}" == "0" ]] || fail "${LEAKED} OTC row(s) leaked into the netted position extract"
POS_ROWS="$(printf '%s\n' "${POSITIONS_CSV}" | grep -c "^[0-9]" || true)"
[[ "${POS_ROWS}" -gt 0 ]] || fail "the netted extract has no rows at all — 'no OTC rows' would be vacuous"
echo "[ok] netted extract still schema 3, ${POS_ROWS} position rows, 0 swap or swaption rows"

step "9. the artifact rebuilds byte-identically from the stored cut alone"
CUT_FILE="${POSITIONS_FILE%.csv}.cut"
${K} exec "${POD}" -- test -f "${CUT_FILE}" || fail "stored cut ${CUT_FILE} is missing"
${K} exec "${POD}" -- java -cp '/opt/app/classes:/opt/app/lib/*' \
  finos.traderx.ordermatcher.cluster.RiskExtractMain \
  --rebuild "${CUT_FILE}" /tmp/rb.csv /tmp/rb-contracts.csv >/dev/null \
  || fail "rebuild from the stored cut failed"
${K} exec "${POD}" -- cmp "${CONTRACTS_FILE}" /tmp/rb-contracts.csv \
  || fail "the rebuilt contracts artifact differs — the option terms are not reproducible"
echo "[ok] both artifacts reproduce from ${CUT_FILE##*/} alone"

step "10. negative controls — the style assertion can fail"
# (a) Feed the step-6 comparison the SAME row twice: two Europeans must be rejected, or the check
#     cannot tell a Bermudan from a European and step 6 proves nothing.
if python3 - "${EUR_ROW}" "${EUR_ROW}" "${EXPIRY}" "${NOTIONAL}" "${STRIKE}" <<'PY' 2>/dev/null
import sys
eur, ber = sys.argv[1], sys.argv[2]
e, b = eur.strip().split(","), ber.strip().split(",")
for i in list(range(1, 15)):
    assert e[i] == b[i]
assert e[15] == "EUROPEAN"
assert b[15] == "BERMUDAN"
PY
then
  fail "the style check accepted two EUROPEAN rows as a European/Bermudan pair — it proves nothing"
fi
# (b) The leak detector must see an OTC row in the netted file if one were there.
if ! printf '%s\n%s\n' "${POSITIONS_CSV}" "${ACCOUNT},SWPT-9,SWAPTION,1,1" | grep -q "^[0-9]*,SWPT\?-"; then
  fail "the leak grep cannot match a swaption row even when present — step 8 proves nothing"
fi
echo "[ok] identical styles rejected, leak detector demonstrably sees an OTC row"

echo
echo "[PASS] yu17-swaption-terms: a European and a Bermudan payer swaption identical in fourteen"
echo "       columns are two contracts distinguished by the one term that makes them different"
echo "       instruments, carried in the same artifact as a plain swap at N=${N} and reproducible"
echo "       from the stored cut."
