#!/usr/bin/env bash
# yu17-fx-credit.sh — the credit gate values non-USD swap notionals with a SEQUENCED FX rate.
#
# The defect this proves fixed (commit 7256a33c): the credit counter summed RAW contract notionals
# across currencies into one USD limit — EUR/GBP under-reserved by roughly the FX rate, JPY
# over-reserved ~150x. The fix makes the rate part of sequenced state (TYPE_FX_RATE, snapshot
# T_FX_RATE, format 7) and refuses a booking whose currency has no rate.
#
# What the unit suite could not exercise, proven here against a live cluster:
#   1. the gateway HTTP route (POST /risk/control/fxrate) — auth, validation, and the happy path
#   2. THE FLIP: an identical GBP booking is refused PRICE_MISSING before the rate is sequenced
#      and accepted after — the ordering IS the negative control (vacuous-pass rule 10); a build
#      that ignores the rate cannot produce it
#   3. rate state survives a member destroyed to an empty disk: the rebuilt member re-derives the
#      rate from snapshot (T_FX_RATE) or log replay, and its agreement on a post-rebuild booking
#      is witnessed by the cross-member cut digest — a member that lost the rate would have
#      REFUSED the booking the leader accepted, and its contract store could not render the same
#      sha at the marker
#
# PRECONDITION: GBP must be rate-less. seed-proof-fixtures.sh deliberately seeds only EUR/JPY for
# exactly this reason. If GBP already has a rate (this proof ran earlier on this epoch), the flip
# arm cannot run and this exits 2 (SKIP) — mint a fresh epoch to exercise it; a pass without the
# flip would prove nothing the unit suite has not.
#
# CLEANUP OBLIGATION: this proof leaves a GBP rate and two GBP contracts sequenced on the rig.
# Both are permanent replicated state and die only with an epoch wipe; seed-proof-fixtures.sh
# re-seeds EUR/JPY (not GBP) on the next fresh epoch.
#
# Usage: ./yu17-fx-credit.sh   (assumes scripts/yu15/start-cluster-kind.sh has run and the
#                               gateway is forwarded on 18110)
set -euo pipefail

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
RISK_CONTROL_TOKEN="${RISK_CONTROL_TOKEN:-dev-risk-control}"
EOD_MASTER_SECRET="${EOD_MASTER_SECRET:-kind-local-dev-token-secret-not-a-real-credential}"

ACCOUNT=22214                  # enabled via /seed below
CCY="GBP"
CONVENTIONS="GBP-SONIA-1Y-ACT365F"
RATE="1.2618"                  # USD per GBP, sequenced in step 4
NOTIONAL=10000000
FIXED_RATE="0.041"
EFFECTIVE="2026-08-17"
MATURITY="2031-08-17"

fail() { echo "[FAIL] $*" >&2; exit 1; }
skip() { echo "[SKIP] $*" >&2; exit 2; }
step() { echo; echo "=== $* ==="; }

extract_pod() { ${K} get pod -l app=risk-extract -o jsonpath='{.items[0].metadata.name}'; }

json_field() { python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('$1',''))"; }

# `|| true` on curl-in-a-substitution is load-bearing under `set -e`: a connect failure must
# surface as code 000 (NO answer, which is not a refusal), not abort before the guard runs.
# See yu17-swap-netting.sh for the incident write-up.
fxrate() { # fxrate <currency> <rate> [token] -> http_code
  curl -s -m20 -o /dev/null -w '%{http_code}' -X POST "${MATCHER_URL}/risk/control/fxrate" \
    -H 'Content-Type: application/json' \
    -H "X-Risk-Control-Token: ${3:-${RISK_CONTROL_TOKEN}}" -H 'X-Risk-Operator: yu17-fx-credit' \
    -d "{\"currency\":\"${1}\",\"rate\":${2}}" || true
}

book() { # book <clientOrderId> [account] -> "<http_code> <body>" on stdout
  local body
  body="$(curl -s -w '\n%{http_code}' --max-time 25 -X POST "${MATCHER_URL}/swaps" \
    -H 'Content-Type: application/json' \
    -d "{\"clientOrderId\":\"${1}\",\"accountId\":${2:-${ACCOUNT}},\"payReceive\":\"Receive\",
         \"notional\":${NOTIONAL},\"fixedRate\":${FIXED_RATE},\"effectiveDate\":\"${EFFECTIVE}\",
         \"maturityDate\":\"${MATURITY}\",\"conventions\":\"${CONVENTIONS}\"}" || true)"
  [[ -n "${body}" ]] || { echo "000 {}"; return 0; }
  echo "$(echo "${body}" | tail -1) $(echo "${body}" | sed '$d' | tr -d '\n')"
}

step "0. preflight — rig reachable, three members, a build that KNOWS the fxrate control"
curl -sf --max-time 10 "${MATCHER_URL}/ready" >/dev/null \
  || fail "gateway not reachable at ${MATCHER_URL} (port-forward svc/order-matcher 18110:18110?)"
[[ "$(${K} get pod -l app=order-matcher-cluster -o name | wc -l | tr -d ' ')" == "3" ]] \
  || fail "need 3 cluster members"
POD="$(extract_pod)"; [[ -n "${POD}" ]] || fail "no risk-extract pod"
# Build discriminator, behavioral rather than a class-file probe: a fix build answers an UNKNOWN
# currency with 400 at the boundary; a pre-fix build has no fxrate action and answers 404. Side
# effect free — nothing is sequenced for a refused currency.
PROBE="$(fxrate NOPE 1.0)"
case "${PROBE}" in
  400) : ;;
  404) fail "the running gateway predates the FX-rate fix (fxrate is an unknown control) — rebuild and roll a fresh epoch" ;;
  000) fail "the fxrate probe got NO answer (curl 000) — that says nothing about the build" ;;
  *)   fail "the fxrate probe returned HTTP ${PROBE}, expected 400 (unknown currency)" ;;
esac
# The discriminator must be able to say NO: a genuinely unknown ACTION must still 404, or the
# probe above would call any 400-happy endpoint a fix build.
NOSUCH="$(curl -s -m20 -o /dev/null -w '%{http_code}' -X POST "${MATCHER_URL}/risk/control/nosuchcontrol" \
  -H 'Content-Type: application/json' \
  -H "X-Risk-Control-Token: ${RISK_CONTROL_TOKEN}" -H 'X-Risk-Operator: yu17-fx-credit' -d '{}' || true)"
[[ "${NOSUCH}" == "404" ]] || fail "an unknown control action returned HTTP ${NOSUCH}, not 404 — the build probe proves nothing"
curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
  -d "{\"accountId\":${ACCOUNT},\"tickers\":\"AAPL\",\"price\":150.00}" >/dev/null \
  || fail "seed failed for account ${ACCOUNT} — every booking would be refused for the wrong reason"
echo "[ok] gateway ready, 3 members, fxrate control present, account ${ACCOUNT} enabled"

step "1. the route's guards: bad token, USD, unknown currency, bad rate"
[[ "$(fxrate ${CCY} ${RATE} wrong-token)" == "401" ]] \
  || fail "a wrong control token was not refused with 401"
[[ "$(fxrate USD 1.0)" == "400" ]] \
  || fail "USD (the limit currency, identity by construction) must be refused as not settable"
[[ "$(fxrate ${CCY} 0)" == "400" ]] \
  || fail "a zero rate must be refused at the boundary, before it is sequenced"
echo "[ok] 401 wrong token, 400 USD, 400 zero rate — nothing was sequenced by any of them"

step "2. THE FLIP, arm one — a ${CCY} booking with no sequenced rate is refused PRICE_MISSING"
RUN_ID="$(date -u +%s)"
read -r PRE_CODE PRE_BODY <<<"$(book "yu17fx-pre-${RUN_ID}")"
[[ "${PRE_CODE}" == "000" ]] && fail "the booking got NO answer (curl 000) — the gateway or the forward is down"
if [[ "${PRE_CODE}" == "200" ]]; then
  skip "${CCY} already has a sequenced rate on this epoch (booking accepted) — the fail-closed arm cannot run; mint a fresh epoch. The contract just booked is left on the rig."
fi
[[ "${PRE_CODE}" == "422" ]] \
  || fail "rate-less ${CCY} booking returned HTTP ${PRE_CODE}, expected 422: ${PRE_BODY}"
PRE_REASON="$(echo "${PRE_BODY}" | json_field reason)"
# Exact reason, not "any 422": a refusal for UNKNOWN_ACCOUNT or CREDIT_LIMIT would also 422 and
# would say nothing about the rate path.
[[ "${PRE_REASON}" == "PRICE_MISSING" ]] \
  || fail "refusal reason was '${PRE_REASON}', expected PRICE_MISSING — refused, but not for the missing rate"
echo "[ok] refused PRICE_MISSING — the raw notional was NOT admitted against the USD limit"

step "3. sequence the rate through the gateway route (the happy path the unit suite cannot drive)"
SET_CODE="$(fxrate ${CCY} ${RATE})"
[[ "${SET_CODE}" == "200" ]] || fail "fxrate ${CCY}=${RATE} returned HTTP ${SET_CODE}"
echo "[ok] ${CCY} = ${RATE} USD/unit sequenced"

step "4. THE FLIP, arm two — the identical booking is now accepted"
read -r POST_CODE POST_BODY <<<"$(book "yu17fx-post-${RUN_ID}")"
[[ "${POST_CODE}" == "000" ]] && fail "the booking got NO answer (curl 000) after the rate was sequenced"
[[ "${POST_CODE}" == "200" ]] \
  || fail "the identical booking still fails (HTTP ${POST_CODE}: ${POST_BODY}) — the rate event did not reach the gate"
FIRST_ID="$(echo "${POST_BODY}" | json_field contractId)"
[[ "${FIRST_ID}" =~ ^SW-[0-9]+$ ]] || fail "accepted booking carried contract id '${FIRST_ID}', not SW-<seq>"
echo "[ok] ${FIRST_ID} booked. Refused before the rate, accepted after — nothing else changed."

step "5. destroy member 2 to an empty disk; it must rebuild the rate state"
VICTIM=2
[[ "$(${K} get pod "order-matcher-cluster-${VICTIM}" -o jsonpath='{.status.phase}')" == "Running" ]] \
  || fail "member ${VICTIM} is not Running; a rebuild proof needs a healthy starting point"
BACKING="$(${K} get sts order-matcher-cluster -o jsonpath='{.spec.volumeClaimTemplates[*].metadata.name}')"
if [[ -n "${BACKING}" ]]; then
  ${K} delete pvc "${BACKING}-order-matcher-cluster-${VICTIM}" --wait=false >/dev/null
fi
${K} delete pod "order-matcher-cluster-${VICTIM}" --wait=true >/dev/null
# Wait for EXISTENCE before readiness — `kubectl wait` returns NotFound immediately against a pod
# the StatefulSet controller has not recreated yet (see yu17-swap-netting.sh step 10).
for _ in $(seq 1 150); do
  ${K} get pod "order-matcher-cluster-${VICTIM}" >/dev/null 2>&1 && break
  sleep 2
done
${K} wait --for=condition=Ready "pod/order-matcher-cluster-${VICTIM}" --timeout=600s >/dev/null \
  || fail "member ${VICTIM} never became Ready after being destroyed"
echo "[ok] member ${VICTIM} rebuilt from nothing (snapshot + log replay)"

step "6. a post-rebuild ${CCY} booking, witnessed by all three members' cut digests"
# The teeth: if the rebuilt member did NOT recover the rate (T_FX_RATE record or replayed
# TYPE_FX_RATE), it refuses this booking while the leader accepts it — its contract store then
# lacks the contract, and the cross-member digest at the marker below CANNOT agree.
read -r SECOND_CODE SECOND_BODY <<<"$(book "yu17fx-rebuild-${RUN_ID}")"
[[ "${SECOND_CODE}" == "200" ]] \
  || fail "post-rebuild booking returned HTTP ${SECOND_CODE}: ${SECOND_BODY}"
SECOND_ID="$(echo "${SECOND_BODY}" | json_field contractId)"
[[ "${SECOND_ID}" =~ ^SW-[0-9]+$ && "${SECOND_ID}" != "${FIRST_ID}" ]] \
  || fail "post-rebuild contract id '${SECOND_ID}' is not a fresh SW-<seq>"

# Run the real EOD chain so the extract marker fires and every member renders the cut at one
# sequence. Close-then-override-then-publish handling as in yu17-swap-netting.sh step 4: a flagged
# instrument elsewhere in the universe must not decide a proof about FX rates.
BEFORE_READY="$(${K} logs "${POD}" --tail=-1 | grep -c 'RISK-EXTRACT-READY' || true)"
[[ "${BEFORE_READY}" =~ ^[0-9]+$ ]] || fail "could not count prior RISK-EXTRACT-READY lines"
TOKEN="$(${K} exec deploy/trade-processor -- sh -c 'curl -fsS -X POST http://localhost:18091/auth/dev-token \
  -H "X-Auth-Master-Secret: '"${EOD_MASTER_SECRET}"'" \
  -H "Content-Type: application/json" \
  -d "{\"subject\":\"yu17-fx-proof\",\"accounts\":[],\"admin\":true,\"ttlSeconds\":900}"' 2>/dev/null)"
[[ -n "${TOKEN}" ]] || fail "could not mint an admin token from trade-processor"
CLOSE="$(${K} exec deploy/trade-processor -- sh -c \
  "curl -fsS -X POST 'http://localhost:18091/eod/session/close' -H 'Authorization: Bearer ${TOKEN}'" 2>/dev/null)"
CLOSE_STATUS="$(echo "${CLOSE}" | json_field status)"
if [[ "${CLOSE_STATUS}" != "PUBLISHED" ]]; then
  SESSION_DATE="$(echo "${CLOSE}" | json_field sessionDate)"
  [[ -n "${SESSION_DATE}" ]] || fail "close returned ${CLOSE_STATUS} and no sessionDate: ${CLOSE}"
  FLAGGED="$(echo "${CLOSE}" | python3 -c 'import json,sys
for p in json.load(sys.stdin)["instruments"]:
    if p.get("flagged"):
        print("%s\t%s\t%s" % (p["security"], p["quality"], p.get("closingPrice")))')"
  [[ -n "${FLAGGED}" ]] || fail "close returned ${CLOSE_STATUS} with nothing flagged"
  while IFS=$'\t' read -r SEC QUAL PX; do
    [[ "${PX}" != "None" && -n "${PX}" ]] || fail "${SEC} is ${QUAL} with no mark at all"
    ${K} exec deploy/trade-processor -- sh -c \
      "curl -fsS -X POST 'http://localhost:18091/eod/prices/${SESSION_DATE}/override' \
        -H 'Authorization: Bearer ${TOKEN}' -H 'Content-Type: application/json' \
        -d '{\"security\":\"${SEC}\",\"price\":${PX},\"reason\":\"yu17-fx-credit: accepted the observed mark\"}'" \
      </dev/null >/dev/null 2>&1 || fail "override of ${SEC} was rejected"
  done <<< "${FLAGGED}"
  CLOSE="$(${K} exec deploy/trade-processor -- sh -c \
    "curl -fsS -X POST 'http://localhost:18091/eod/prices/${SESSION_DATE}/publish' \
      -H 'Authorization: Bearer ${TOKEN}'" </dev/null 2>/dev/null)"
  CLOSE_STATUS="$(echo "${CLOSE}" | json_field status)"
fi
[[ "${CLOSE_STATUS}" == "PUBLISHED" ]] || fail "session close did not publish (status '${CLOSE_STATUS}')"
AFTER_READY="${BEFORE_READY}"
for _ in $(seq 1 60); do
  AFTER_READY="$(${K} logs "${POD}" --tail=-1 | grep -c 'RISK-EXTRACT-READY' || true)"
  [[ "${AFTER_READY}" -gt "${BEFORE_READY}" ]] && break
  sleep 2
done
[[ "${AFTER_READY}" -gt "${BEFORE_READY}" ]] || fail "no RISK-EXTRACT-READY after the EOD chain completed"
READY="$(${K} logs "${POD}" --tail=-1 | grep 'RISK-EXTRACT-READY' | tail -1 | sed 's/^RISK-EXTRACT-READY //')"
N="$(echo "${READY}" | json_field consensusSequence)"
[[ "${N}" =~ ^[0-9]+$ ]] || fail "announcement carried no consensusSequence: ${READY}"

MEMBER_SHAS=""
for i in 0 1 2; do
  LINE=""
  for _ in $(seq 1 40); do
    LINE="$(${K} logs "order-matcher-cluster-${i}" --tail=-1 2>/dev/null | grep "RISK-EXTRACT-CUT seq=${N} " | tail -1 || true)"
    [[ -n "${LINE}" ]] && break
    sleep 3
  done
  [[ -n "${LINE}" ]] || fail "member ${i} never rendered a cut at seq=${N}"
  SHA="$(echo "${LINE}" | sed -n 's/.*sha256=\([0-9a-f]\{64\}\).*/\1/p')"
  # Shape-tested, never emptiness-tested: "" == "" == "" is agreement (vacuous-pass rule 1).
  [[ "${SHA}" =~ ^[0-9a-f]{64}$ ]] || fail "member ${i} cut line carried no 64-hex sha256: ${LINE}"
  MEMBER_SHAS="${MEMBER_SHAS}${SHA}"$'\n'
done
[[ "$(printf '%s' "${MEMBER_SHAS}" | sort -u | wc -l | tr -d ' ')" == "1" ]] \
  || fail "members disagree on the cut at seq=${N} — the rebuilt member did not recover the rate state (it refused a booking the leader accepted)"
echo "[ok] ${SECOND_ID} booked post-rebuild; all three members — the rebuilt one included — render one sha at N=${N}"

step "7. negative control — the reason check can fail"
# Hand the step-2 reason assertion a refusal produced by a DIFFERENT cause and confirm it would
# have been rejected: an unknown-account 422 must not satisfy a check about missing rates.
read -r OTHER_CODE OTHER_BODY <<<"$(book "yu17fx-neg-${RUN_ID}" 999123)"
[[ "${OTHER_CODE}" == "422" ]] || fail "unknown-account booking returned HTTP ${OTHER_CODE}, expected 422"
OTHER_REASON="$(echo "${OTHER_BODY}" | json_field reason)"
[[ "${OTHER_REASON}" == "UNKNOWN_ACCOUNT" ]] || fail "expected UNKNOWN_ACCOUNT, got '${OTHER_REASON}'"
[[ "${OTHER_REASON}" != "PRICE_MISSING" ]] \
  || fail "an unknown-account refusal reads as PRICE_MISSING — step 2's reason check proves nothing"
echo "[ok] a differently-caused 422 carries a different reason; step 2's assertion has teeth"

echo
echo "[PASS] yu17-fx-credit: the ${CCY} rate exists only as sequenced state — refused before,"
echo "       accepted after, recovered by a member rebuilt from an empty disk."
echo
echo "[CLEANUP NOTE] this run left sequenced state the rig keeps until the next epoch wipe:"
echo "  - ${CCY} = ${RATE} USD/unit (re-running this proof on this epoch will SKIP the flip arm)"
echo "  - contracts ${FIRST_ID} and ${SECOND_ID} on account ${ACCOUNT}"
