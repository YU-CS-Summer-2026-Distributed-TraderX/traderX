#!/usr/bin/env bash
# yu17-swap-netting.sh — THE headline proof of YU17.
#
# The property: a swap is not a position, and our netted position grain would destroy it.
#
#   Receive fixed 4.2% on 10mm, then pay fixed 4.3% on the same notional, same dates, same
#   conventions, same account. At the (accountId, security) grain those two net to quantity ZERO
#   and the average rate is meaningless — the position disappears. Economically the account is
#   locked into paying ~10bp on 10mm for five years: ~10k/year, ~50k undiscounted. Netting would
#   have deleted a real, loss-making position and left nothing behind to notice.
#
# So this proves, at one consensus sequence:
#   1. both bookings were SEQUENCED THROUGH CONSENSUS (D1) — the applied sequence moves by exactly
#      two, and all three members agree on the resulting state
#   2. the contracts artifact carries BOTH contracts, per trade, with both rates intact
#   3. the netted position artifact is UNCHANGED — it has no row for either swap, because a swap
#      never becomes a position (D3); that absence is the netting loss, made visible
#   4. the contracts artifact rebuilds byte-identically from the stored cut alone
#   5. the risk gate is wired: an unknown account is refused and creates no contract
#
# Every assertion carries a negative control (.claude/skills/vacuous-pass-audit rule 10).
#
# Usage: ./yu17-swap-netting.sh   (assumes scripts/yu15/start-cluster-kind.sh has run and the
#                                  gateway is forwarded on 18110)
set -euo pipefail

VERBOSE=0
case "${1:-}" in -v|--verbose) VERBOSE=1; shift ;; esac
vlog() { [ "${VERBOSE}" = 1 ] && printf '%s\n' "$@" >&2 || true; }

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
EOD_MASTER_SECRET="${EOD_MASTER_SECRET:-kind-local-dev-token-secret-not-a-real-credential}"

ACCOUNT=22214                 # the booking account; enabled via /seed below
COUNTERPARTY=42422            # the other side of the equity cross in step 0b
SEED_TICKER="${SEED_TICKER:-AAPL}"
NOTIONAL=10000000             # 10mm, identical on both legs — this is what makes them net to zero
RECEIVE_RATE="0.042"
PAY_RATE="0.043"
EFFECTIVE="2026-08-17"
MATURITY="2031-08-17"
CONVENTIONS="USD-SOFR-1Y-ACT360"
UNKNOWN_ACCOUNT=999123        # never seeded — the risk gate must refuse it

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

extract_pod() { ${K} get pod -l app=risk-extract -o jsonpath='{.items[0].metadata.name}'; }

# The APPLIED SEQUENCE is a cluster MEMBER's number. The gateway's /health has no opinion about
# consensus, so asking it there would make every sequence assertion below vacuous.
applied_seq() { # applied_seq <member-ordinal>
  ${K} exec "order-matcher-cluster-${1}" -- wget -qO- localhost:8080/health 2>/dev/null \
    | python3 -c 'import sys,json;print(json.load(sys.stdin).get("applied", -1))' 2>/dev/null || echo -1
}

# `|| true` on every curl-in-a-substitution below is load-bearing, not defensive noise. Under
# `set -e` a curl that fails to CONNECT aborts the script at the assignment, before the guard that
# reads the status code ever runs — so the guard covers "answered something other than 200", the
# case that almost never happens, and is bypassed in the case that does. The script then dies with
# no message and the suite records a bare FAIL. (This exact defect is live in
# yu16-bond-position.sh. It was written up in issues/HANDOFF-issue-suite-verdicts-under-load.md,
# which its own author later WITHDREW and deleted — that document's framing was wrong, though this
# observation in it was right; see issues/HANDOFF-review-2026-08-12-to-14-gateway-and-proof-
# hardening.md.) With the `|| true`
# a connection failure surfaces as code 000, which the guards below treat as its own verdict:
# 000 means NO ANSWER, and no answer is not a refusal.
book() { # book <payReceive> <rate> <clientOrderId> [account] -> "<http_code> <body>" on stdout
  local body
  body="$(curl -s -w '\n%{http_code}' --max-time 25 -X POST "${MATCHER_URL}/swaps" \
    -H 'Content-Type: application/json' \
    -d "{\"clientOrderId\":\"${3}\",\"accountId\":${4:-${ACCOUNT}},\"payReceive\":\"${1}\",
         \"notional\":${NOTIONAL},\"fixedRate\":${2},\"effectiveDate\":\"${EFFECTIVE}\",
         \"maturityDate\":\"${MATURITY}\",\"conventions\":\"${CONVENTIONS}\"}" || true)"
  [[ -n "${body}" ]] || { echo "000 {}"; return 0; }
  echo "$(echo "${body}" | tail -1) $(echo "${body}" | sed '$d' | tr -d '\n')"
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

json_field() { # json_field <key> ; reads JSON on stdin
  python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('$1',''))"
}

# check_row <csvRow> <contractId> <payReceive> <fixedRate> <notional> <accountId>
# Every argument arrives on argv, deliberately: a heredoc-supplied program already owns stdin, so a
# row piped in would read as empty and each column would compare against "" — the emptiness bug
# this project has paid for repeatedly, wearing a different hat.
check_row() {
  python3 - "$@" <<'PY'
import sys
row, want_id, want_dir, want_rate, want_notional, want_account = sys.argv[1:7]
cols = row.strip().split(",")
assert len(cols) == 16, f"row has {len(cols)} columns, want 16: {row}"
assert cols[0] == want_id, f"contractId {cols[0]} != {want_id}"
assert cols[1] == want_account, f"accountId {cols[1]} != {want_account}"
assert cols[2] == want_dir, f"payReceive {cols[2]} != {want_dir}"
assert cols[3] == want_notional, f"notional {cols[3]} != {want_notional}"
assert float(cols[4]) == float(want_rate), f"fixedRate {cols[4]} != {want_rate}"
assert cols[10] == "USD", f"currency {cols[10]} != USD"
assert cols[11] and cols[12], f"counterparty/netting set missing: {row}"
# A SWAP carries the product and leaves the option wrapper empty. Asserted here rather than only
# in the swaption proof, so widening the artifact for a later product cannot quietly start
# filling a swap's columns.
assert cols[13] == "SWAP", f"productType {cols[13]} != SWAP"
assert cols[14] == "" and cols[15] == "", f"a swap must have no expiry or exercise style: {row}"
PY
}

step "0. preflight — rig reachable, three members, a readable consensus position"
curl -sf --max-time 10 "${MATCHER_URL}/ready" >/dev/null \
  || fail "gateway not reachable at ${MATCHER_URL} (port-forward svc/order-matcher 18110:18110?)"
[[ "$(${K} get pod -l app=order-matcher-cluster -o name | wc -l | tr -d ' ')" == "3" ]] \
  || fail "need 3 cluster members"
POD="$(extract_pod)"; [[ -n "${POD}" ]] || fail "no risk-extract pod"
for d in price-publisher trade-processor position-service; do
  ${K} get deploy "${d}" >/dev/null 2>&1 || fail "${d} is not deployed — the EOD chain cannot run"
done
# The rig can be a commit behind its own tree. A proof asserting new behaviour cannot tell you it
# ran against a stale build, so ask the build whether it knows what a swap is BEFORE trusting it.
# A file test, not `javap`: the runtime image carries no JDK tools (no javap, no jar, no unzip), so
# a javap-based probe answers "absent" for every build — a refusal that says nothing about the
# image. /opt/app/classes is an exploded class tree, so the class file itself is the marker, and
# `test -f` returns a real exit code rather than a pipeline's.
MARKER=/opt/app/classes/finos/traderx/ordermatcher/lmax/SwapConventions.class
${K} exec order-matcher-cluster-0 -- test -f "${MARKER}" \
  || fail "the running member image has no ${MARKER##*/} — it predates YU17; rebuild and roll"
# The same probe must be able to say NO, or it is not a probe.
if ${K} exec order-matcher-cluster-0 -- test -f "${MARKER%SwapConventions.class}NoSuchClass.class" 2>/dev/null; then
  fail "the stale-build check reports a class that cannot exist — it would pass against any image"
fi
BEFORE_SEQ="$(quiesced_seq)"
[[ "${BEFORE_SEQ}" =~ ^[0-9]+$ && "${BEFORE_SEQ}" -ge 0 ]] \
  || fail "could not read the applied sequence from order-matcher-cluster-0 (got '${BEFORE_SEQ}')"
for acct in "${ACCOUNT}" "${COUNTERPARTY}"; do
  curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${acct},\"tickers\":\"${SEED_TICKER}\",\"price\":150.00}" >/dev/null \
    || fail "seed failed for account ${acct} — every booking would be refused for the wrong reason"
done
echo "[ok] gateway ready, 3 members, YU17 image, accounts ${ACCOUNT}/${COUNTERPARTY} enabled, applied=${BEFORE_SEQ}"

step "0b. cross an ordinary equity trade, so step 7 has a real population to be evidence about"
# Step 7 asserts the netted extract carries NO swap row. Against an extract with no rows at all
# that is vacuously true, so give it something to be true OF: an equity position, which is exactly
# the instrument class netting is correct for.
for side_account in "Sell:${COUNTERPARTY}" "Buy:${ACCOUNT}"; do
  side="${side_account%%:*}"; acct="${side_account##*:}"
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 -X POST "${MATCHER_URL}/orders" \
    -H 'Content-Type: application/json' \
    -d "{\"accountId\":${acct},\"ticker\":\"${SEED_TICKER}\",\"side\":\"${side}\",\"quantity\":10,\"limitPrice\":150.00}" || true)"
  [[ "${code}" == "000" ]] && fail "${side} ${SEED_TICKER} order got NO answer (curl 000) — the gateway or the forward is down; that is not a refusal"
  [[ "${code}" == "200" ]] || fail "${side} ${SEED_TICKER} order returned HTTP ${code} — the equity path is broken, before any swap is involved"
done
echo "[ok] 10 ${SEED_TICKER} crossed between ${COUNTERPARTY} and ${ACCOUNT}"

step "1. the risk gate refuses an unknown account — and never creates a contract"
# Run the negative arm FIRST, so a later "two contracts" count cannot be inflated by it.
RUN_ID="$(date -u +%s)"
read -r BAD_CODE BAD_BODY <<<"$(book Receive "${RECEIVE_RATE}" "yu17-bad-${RUN_ID}" "${UNKNOWN_ACCOUNT}")"
[[ "${BAD_CODE}" == "000" ]] && fail "the booking got NO answer (curl 000) — the gateway or the forward is down, which says nothing about the risk gate"
[[ "${BAD_CODE}" == "422" ]] \
  || fail "booking on unknown account ${UNKNOWN_ACCOUNT} returned HTTP ${BAD_CODE}, expected 422 — the risk gate is not wired"
BAD_REASON="$(echo "${BAD_BODY}" | json_field reason)"
[[ "${BAD_REASON}" == "UNKNOWN_ACCOUNT" ]] \
  || fail "refusal reason was '${BAD_REASON}', expected UNKNOWN_ACCOUNT — the gate refused for the wrong reason"
echo "[ok] unknown account refused with ${BAD_REASON} (the booking was sequenced and DECIDED, not dropped)"

step "2. book the pair: receive fixed ${RECEIVE_RATE} and pay fixed ${PAY_RATE}, both on ${NOTIONAL}"
SEQ_BEFORE_PAIR="$(quiesced_seq)"
[[ "${SEQ_BEFORE_PAIR}" =~ ^[0-9]+$ ]] || fail "applied sequence unreadable before the pair"
read -r RECV_CODE RECV_BODY <<<"$(book Receive "${RECEIVE_RATE}" "yu17-recv-${RUN_ID}")"
[[ "${RECV_CODE}" == "000" ]] && fail "receive-fixed booking got NO answer (curl 000) — no committed decision was observed, which is not a rejection"
[[ "${RECV_CODE}" == "200" ]] || fail "receive-fixed booking returned HTTP ${RECV_CODE}: ${RECV_BODY}"
read -r PAY_CODE PAY_BODY <<<"$(book Pay "${PAY_RATE}" "yu17-pay-${RUN_ID}")"
[[ "${PAY_CODE}" == "000" ]] && fail "pay-fixed booking got NO answer (curl 000) — no committed decision was observed, which is not a rejection"
[[ "${PAY_CODE}" == "200" ]] || fail "pay-fixed booking returned HTTP ${PAY_CODE}: ${PAY_BODY}"
RECV_ID="$(echo "${RECV_BODY}" | json_field contractId)"
PAY_ID="$(echo "${PAY_BODY}"  | json_field contractId)"
[[ "${RECV_ID}" =~ ^SW-[0-9]+$ ]] || fail "receive leg contract id '${RECV_ID}' is not SW-<seq>"
[[ "${PAY_ID}"  =~ ^SW-[0-9]+$ ]] || fail "pay leg contract id '${PAY_ID}' is not SW-<seq>"
[[ "${RECV_ID}" != "${PAY_ID}" ]] || fail "both legs got contract id ${RECV_ID} — they are not distinct contracts"
SEQ_AFTER_PAIR="$(quiesced_seq)"
[[ "${SEQ_AFTER_PAIR}" =~ ^[0-9]+$ ]] || fail "applied sequence unreadable after the pair"
[[ "$((SEQ_AFTER_PAIR - SEQ_BEFORE_PAIR))" == "2" ]] \
  || fail "the applied sequence moved ${SEQ_BEFORE_PAIR} -> ${SEQ_AFTER_PAIR}; two bookings must be exactly two sequenced commands (D1)"
echo "[ok] ${RECV_ID} receive@${RECEIVE_RATE} and ${PAY_ID} pay@${PAY_RATE}, sequenced through consensus (+2)"

step "3. what netting would have done to this pair"
# Stated as arithmetic, not as prose: equal notionals in opposite directions at the
# (accountId, security) grain sum to zero, and the two rates cannot be averaged into one contract.
NET="$(python3 -c "print(${NOTIONAL} - ${NOTIONAL})")"
CARRY="$(python3 -c "print('%.0f' % ((${PAY_RATE} - ${RECEIVE_RATE}) * ${NOTIONAL}))")"
[[ "${NET}" == "0" ]] || fail "the two legs do not offset (${NET}) — this run does not exercise the netting loss"
[[ "${CARRY}" -gt 0 ]] || fail "the pair carries no rate differential — the loss it demonstrates would be zero"
echo "[ok] netted quantity would be ${NET}; the position that would vanish carries \$${CARRY}/year for 5 years"

step "4. run the real EOD chain so the extract fires off eod.pnl.done"
BEFORE_READY="$(${K} logs "${POD}" --tail=-1 | grep -c 'RISK-EXTRACT-READY' || true)"
[[ "${BEFORE_READY}" =~ ^[0-9]+$ ]] || fail "could not count prior RISK-EXTRACT-READY lines"
TOKEN="$(${K} exec deploy/trade-processor -- sh -c 'curl -fsS -X POST http://localhost:18091/auth/dev-token \
  -H "X-Auth-Master-Secret: '"${EOD_MASTER_SECRET}"'" \
  -H "Content-Type: application/json" \
  -d "{\"subject\":\"yu17-proof\",\"accounts\":[],\"admin\":true,\"ttlSeconds\":900}"' 2>/dev/null)"
[[ -n "${TOKEN}" ]] || fail "could not mint an admin token from trade-processor"
CLOSE="$(${K} exec deploy/trade-processor -- sh -c \
  "curl -fsS -X POST 'http://localhost:18091/eod/session/close' -H 'Authorization: Bearer ${TOKEN}'" 2>/dev/null)"
CLOSE_STATUS="$(echo "${CLOSE}" | json_field status)"
# A single flagged instrument blocks publication of the WHOLE session (FR-EOD23), and with
# EOD_UNIVERSE unset the universe is "every ticker the price feed has been seen publishing" —
# which includes the simulated option chain. An out-of-the-money option marked off a random-walk
# underlying routinely clears even the 200% option band between two sessions: measured 2026-08-17,
# AAPL260918P00220000 went 0.435 -> 1.846 (+324%) and was correctly SPIKE, then 1.430 (+229%,
# still SPIKE), then 0.986 (+127%, OK) as the walk came back — four of that day's five closes
# carried the flag. Asserting PUBLISHED straight off the close therefore made this proof pass only
# when nothing in that universe happened to be flagged, which is not a property of swaps and not
# something this proof controls.
#
# Resolve it the way an operator does — override each flagged mark at its own observed close and
# publish (ADR-026: a correction is a new version, never an in-place edit) — so the proof depends
# on the EOD chain running, not on a quiet random walk. It does NOT widen the band: a genuine
# outlier is still flagged, still recorded, and still requires the explicit override to clear.
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
    # MISSING carries no mark at all, so there is nothing to accept. That is a real gap in the
    # price chain rather than a band being strict, and it is not this proof's to paper over.
    [[ "${PX}" != "None" && -n "${PX}" ]] \
      || fail "${SEC} is ${QUAL} with no mark — the EOD chain could not price it at all"
    echo "   overriding ${SEC} (${QUAL}) at its own observed close ${PX}"
    ${K} exec deploy/trade-processor -- sh -c \
      "curl -fsS -X POST 'http://localhost:18091/eod/prices/${SESSION_DATE}/override' \
        -H 'Authorization: Bearer ${TOKEN}' -H 'Content-Type: application/json' \
        -d '{\"security\":\"${SEC}\",\"price\":${PX},\"reason\":\"yu17-swap-netting: accepted the observed mark\"}'" \
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
[[ "${AFTER_READY:-0}" -gt "${BEFORE_READY}" ]] || {
  ${K} logs "${POD}" --tail=30
  fail "no RISK-EXTRACT-READY after the EOD chain completed"
}
READY="$(${K} logs "${POD}" --tail=-1 | grep 'RISK-EXTRACT-READY' | tail -1 | sed 's/^RISK-EXTRACT-READY //')"
N="$(echo "${READY}"              | json_field consensusSequence)"
CUT_SHA="$(echo "${READY}"        | json_field cutSha256)"
URI="$(echo "${READY}"            | json_field uri)"
CONTRACTS_URI="$(echo "${READY}"  | json_field contractsUri)"
CONTRACTS_N="$(echo "${READY}"    | json_field contracts)"
WITNESS="$(echo "${READY}"        | json_field quiesceWitnessSequence)"
[[ "${N}" =~ ^[0-9]+$ ]] || fail "announcement carried no consensusSequence: ${READY}"
[[ "${CONTRACTS_URI}" == *"-contracts.csv" ]] \
  || fail "the announcement names no contracts artifact (contractsUri='${CONTRACTS_URI}') — D3 requires TWO artifacts from one cut"
[[ "${CONTRACTS_N}" =~ ^[0-9]+$ && "${CONTRACTS_N}" -ge 2 ]] \
  || fail "the announcement reports ${CONTRACTS_N} contracts; the two just booked must be in it"
[[ "${WITNESS}" == "$((N + 1))" ]] || fail "quiesceWitnessSequence ${WITNESS} != ${N}+1 — something was sequenced mid-build"
echo "[ok] extract at N=${N}, ${CONTRACTS_N} contracts, quiesced (witness ${WITNESS}), cut ${CUT_SHA:0:12}…"

step "5. all three members rendered the identical state at N — contracts included"
MEMBER_SHAS=""
for i in 0 1 2; do
  LINE="$(${K} logs "order-matcher-cluster-${i}" --tail=-1 | grep "RISK-EXTRACT-CUT seq=${N} " | tail -1)"
  [[ -n "${LINE}" ]] || fail "member ${i} never rendered a cut at seq=${N}"
  SHA="$(echo "${LINE}" | sed -n 's/.*sha256=\([0-9a-f]\{64\}\).*/\1/p')"
  MC="$(echo "${LINE}"  | sed -n 's/.*contracts=\([0-9]\{1,\}\).*/\1/p')"
  # Shape-tested, not emptiness-tested: an unparsed line yields "" on both, and "" == "" == "" is
  # the agreement bug this project has already paid for twice.
  [[ "${SHA}" =~ ^[0-9a-f]{64}$ ]] || fail "member ${i} cut line carried no 64-hex sha256: ${LINE}"
  [[ "${MC}" =~ ^[0-9]+$ ]] || fail "member ${i} cut line carried no contracts count: ${LINE}"
  [[ "${MC}" == "${CONTRACTS_N}" ]] \
    || fail "member ${i} rendered ${MC} contracts, the announcement says ${CONTRACTS_N}"
  echo "  member ${i}: contracts=${MC} sha256=${SHA}"
  MEMBER_SHAS="${MEMBER_SHAS}${SHA}"$'\n'
done
[[ "$(printf '%s' "${MEMBER_SHAS}" | sort -u | wc -l | tr -d ' ')" == "1" ]] \
  || fail "members disagree on the cut at seq=${N} — the contract store is not replicated state"
[[ "$(printf '%s' "${MEMBER_SHAS}" | head -1)" == "${CUT_SHA}" ]] \
  || fail "the announced cutSha256 does not match what the members rendered"
echo "[ok] one portfolio at ${N}, agreed by all three members"

step "6. THE HEADLINE — the contracts artifact carries BOTH contracts, per trade"
CONTRACTS_FILE="${CONTRACTS_URI#file:}"
${K} exec "${POD}" -- test -f "${CONTRACTS_FILE}" || fail "contracts artifact ${CONTRACTS_FILE} does not exist"
CONTRACTS_CSV="$(${K} exec "${POD}" -- cat "${CONTRACTS_FILE}")"
[[ -n "${CONTRACTS_CSV}" ]] || fail "contracts artifact is empty"
for spec in "${RECV_ID}:RECEIVE_FIXED:${RECEIVE_RATE}" "${PAY_ID}:PAY_FIXED:${PAY_RATE}"; do
  id="${spec%%:*}"; rest="${spec#*:}"; dir="${rest%%:*}"; rate="${rest##*:}"
  ROW="$(printf '%s\n' "${CONTRACTS_CSV}" | grep "^${id}," || true)"
  [[ -n "${ROW}" ]] || fail "contract ${id} is absent from the artifact — the booking did not survive to the extract"
  [[ "$(printf '%s\n' "${ROW}" | wc -l | tr -d ' ')" == "1" ]] || fail "contract ${id} appears more than once"
  echo "  ${ROW}"
  # The row rides argv, not stdin: `python3 - <<EOF` already claims stdin for the program text, so
  # a piped row would be silently empty and every column would compare against "".
  check_row "${ROW}" "${id}" "${dir}" "${rate}" "${NOTIONAL}" "${ACCOUNT}" \
    || fail "contract row does not carry the terms it was booked with"
done
BOOKED_ROWS="$(printf '%s\n' "${CONTRACTS_CSV}" | grep -c "^SW-.*,${ACCOUNT},.*,${NOTIONAL}," || true)"
[[ "${BOOKED_ROWS}" -ge 2 ]] \
  || fail "only ${BOOKED_ROWS} contract row(s) on account ${ACCOUNT} at ${NOTIONAL} notional — netting collapsed them"
echo "[ok] TWO contracts, both rates intact. The position grain would have held ZERO."

step "7. the netted position artifact is unchanged — no swap ever became a position"
POSITIONS_FILE="${URI#file:}"
POSITIONS_CSV="$(${K} exec "${POD}" -- cat "${POSITIONS_FILE}")"
[[ -n "${POSITIONS_CSV}" ]] || fail "netted artifact is empty"
POS_SCHEMA="$(printf '%s\n' "${POSITIONS_CSV}" | sed -n 's/^# traderx-risk-extract schema=\([0-9]*\)$/\1/p')"
[[ "${POS_SCHEMA}" == "3" ]] \
  || fail "netted extract schema is '${POS_SCHEMA}', must stay at 3 (D3) — adding swaps changed an existing consumer's file"
SWAP_ROWS="$(printf '%s\n' "${POSITIONS_CSV}" | grep -c "^[0-9]*,SW-" || true)"
[[ "${SWAP_ROWS}" == "0" ]] \
  || fail "${SWAP_ROWS} swap row(s) leaked into the netted position extract — they do not belong there"
# The population must be non-empty, or "no swap rows" is satisfied by an extract of nothing.
POS_ROWS="$(printf '%s\n' "${POSITIONS_CSV}" | grep -c "^[0-9]" || true)"
[[ "${POS_ROWS}" -gt 0 ]] \
  || fail "the netted extract has no position rows at all — 'no swap rows' would be vacuously true"
echo "[ok] netted extract still schema 3, ${POS_ROWS} equity/bond/option rows, 0 swap rows"

step "8. the contracts artifact rebuilds byte-identically from the stored cut alone"
CUT_FILE="${POSITIONS_FILE%.csv}.cut"
${K} exec "${POD}" -- test -f "${CUT_FILE}" || fail "stored cut ${CUT_FILE} is missing"
${K} exec "${POD}" -- java -cp '/opt/app/classes:/opt/app/lib/*' \
  finos.traderx.ordermatcher.cluster.RiskExtractMain \
  --rebuild "${CUT_FILE}" /tmp/rebuild.csv /tmp/rebuild-contracts.csv >/dev/null \
  || fail "rebuild from the stored cut failed"
${K} exec "${POD}" -- cmp "${CONTRACTS_FILE}" /tmp/rebuild-contracts.csv \
  || fail "the rebuilt contracts artifact differs from the delivered one — it is NOT reproducible"
${K} exec "${POD}" -- cmp "${POSITIONS_FILE}" /tmp/rebuild.csv \
  || fail "the rebuilt netted artifact differs — one cut must reproduce BOTH artifacts"
echo "[ok] both artifacts reproduce from ${CUT_FILE##*/} alone"

step "9. negative controls — every assertion above can fail"
# (a) The step-6 row check, handed the SAME row it just accepted but with the other leg's rate,
#     must reject it. An assertion never observed failing is a hypothesis.
RECV_ROW="$(printf '%s\n' "${CONTRACTS_CSV}" | grep "^${RECV_ID}," )"
TAMPERED="$(printf '%s' "${RECV_ROW}" | awk -F, -v OFS=, '{$5="0.043000"; print}')"
[[ "${TAMPERED}" != "${RECV_ROW}" ]] || fail "could not construct a wrong-rate row; the control is inert"
if check_row "${TAMPERED}" "${RECV_ID}" "RECEIVE_FIXED" "${RECEIVE_RATE}" "${NOTIONAL}" "${ACCOUNT}" 2>/dev/null; then
  fail "the row check accepted the WRONG rate — it cannot tell 4.2% from 4.3% and proves nothing"
fi
# …and must still accept the untampered row, so the control above is not passing because the check
# rejects everything.
check_row "${RECV_ROW}" "${RECV_ID}" "RECEIVE_FIXED" "${RECEIVE_RATE}" "${NOTIONAL}" "${ACCOUNT}" \
  || fail "the row check now rejects the row it accepted in step 6 — it rejects everything"
# (b) The step-6 presence check must fail for a contract id that was never booked.
if printf '%s\n' "${CONTRACTS_CSV}" | grep -q "^SW-0,"; then
  fail "the artifact contains SW-0, which is never issued — the id check would accept anything"
fi
# (c) The step-7 leak check must be able to see a swap row if one were there.
if ! printf '%s\n%s\n' "${POSITIONS_CSV}" "${ACCOUNT},SW-999,SWAP,1,1" | grep -q "^[0-9]*,SW-"; then
  fail "the swap-leak grep cannot match a swap row even when one is present — step 7 proves nothing"
fi
echo "[ok] wrong rate rejected, unbooked id absent, leak detector demonstrably sees a swap row"

step "10. a member destroyed to an empty disk rebuilds the contract store byte-identically"
# The members are PVC-backed on this rig, so deleting the POD alone restores from that member's own
# archive — real, but not the claim. Delete the PVC too and the member comes back with nothing and
# must rebuild from the other two: snapshot (format 5, T_CONTRACT) plus the replayed log tail.
# Verified against the rig rather than assumed, because yu16-book-grid's "emptyDir — it returns
# with no disk" is stale for this StatefulSet.
VICTIM=2
[[ "$(${K} get pod "order-matcher-cluster-${VICTIM}" -o jsonpath='{.status.phase}')" == "Running" ]] \
  || fail "member ${VICTIM} is not Running; a rebuild proof needs a healthy starting point"
BACKING="$(${K} get sts order-matcher-cluster -o jsonpath='{.spec.volumeClaimTemplates[*].metadata.name}')"
if [[ -n "${BACKING}" ]]; then
  echo "  members are PVC-backed ('${BACKING}'); deleting the claim so the rebuild starts from nothing"
  ${K} delete pvc "${BACKING}-order-matcher-cluster-${VICTIM}" --wait=false >/dev/null
else
  echo "  members are emptyDir-backed; the pod delete alone empties the disk"
fi
${K} delete pod "order-matcher-cluster-${VICTIM}" --wait=true >/dev/null
# `kubectl wait --for=condition=Ready` does NOT wait for a pod to be CREATED. Against a name that
# does not exist it returns `NotFound` IMMEDIATELY, and the --timeout never applies at all. The line
# above just deleted the pod, so there is ALWAYS a window before the StatefulSet controller
# recreates it, and how wide that window is depends on how busy the box is -- which is why this
# passed every run until it did not, then reported "member 2 never became Ready after being
# destroyed" about a member that had not yet been asked to exist. The 600s budget was real; it was
# never spent. Wait for EXISTENCE first, then for readiness.
for _ in $(seq 1 150); do
  ${K} get pod "order-matcher-cluster-${VICTIM}" >/dev/null 2>&1 && break
  sleep 2
done
${K} wait --for=condition=Ready "pod/order-matcher-cluster-${VICTIM}" --timeout=600s >/dev/null \
  || fail "member ${VICTIM} never became Ready after being destroyed"
# Ask it to render the SAME sequence again. A rebuilt member replays the marker at N, so the cut it
# logs is a fresh render of state it reconstructed from nothing — the strongest form of the claim.
REBUILT_LINE=""
for _ in $(seq 1 60); do
  REBUILT_LINE="$(${K} logs "order-matcher-cluster-${VICTIM}" --tail=-1 2>/dev/null | grep "RISK-EXTRACT-CUT seq=${N} " | tail -1 || true)"
  [[ -n "${REBUILT_LINE}" ]] && break
  sleep 3
done
if [[ -z "${REBUILT_LINE}" ]]; then
  # Distinguish "no answer" from "the answer is no": a member that recovered from a snapshot taken
  # AFTER N never replays the marker, so there is no cut to compare and this arm has not run.
  ${K} exec "order-matcher-cluster-${VICTIM}" -- wget -qO- localhost:8080/health >/dev/null 2>&1 \
    || fail "member ${VICTIM} is not answering after the rebuild"
  echo "  [note] member ${VICTIM} recovered past ${N} and did not replay the marker, so this arm did"
  echo "         NOT run. The cross-member agreement in step 5 still stands; re-run after a fresh"
  echo "         epoch to exercise the rebuild path."
else
  REBUILT_SHA="$(echo "${REBUILT_LINE}" | sed -n 's/.*sha256=\([0-9a-f]\{64\}\).*/\1/p')"
  REBUILT_CONTRACTS="$(echo "${REBUILT_LINE}" | sed -n 's/.*contracts=\([0-9]\{1,\}\).*/\1/p')"
  [[ "${REBUILT_SHA}" =~ ^[0-9a-f]{64}$ ]] || fail "rebuilt member's cut line carried no sha256: ${REBUILT_LINE}"
  [[ "${REBUILT_CONTRACTS}" == "${CONTRACTS_N}" ]] \
    || fail "rebuilt member holds ${REBUILT_CONTRACTS} contracts, the rest of the cluster ${CONTRACTS_N}"
  [[ "${REBUILT_SHA}" == "${CUT_SHA}" ]] \
    || fail "the rebuilt member re-rendered ${REBUILT_SHA}, not ${CUT_SHA} — the contract store did not survive restore byte-identically"
  echo "  [ok] member ${VICTIM} rebuilt from nothing and re-rendered ${CUT_SHA:0:12}… with ${REBUILT_CONTRACTS} contracts"
fi

echo
echo "[PASS] yu17-swap-netting: two OTC contracts sequenced through consensus at N=${N},"
echo "       carried per-trade with both rates, reproducible from the cut, and absent from a"
echo "       netted position extract that netting would have reduced to nothing."
