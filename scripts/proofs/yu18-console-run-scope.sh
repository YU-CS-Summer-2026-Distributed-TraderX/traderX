#!/usr/bin/env bash
# yu18-console-run-scope.sh — the console's run context, driven in headless Chrome (YU18 managed-run
# UI, FR-MUI01..09) plus its proxy refusal (FR-MUI07), on a live side namespace.
#
#   FR-MUI07  signed in as admin AND carrying the operator token, every forwarded form of
#             projection-control, projection-recovery and gateway /run/control answers 403
#             operator_control_refused; a reader through the same proxy answers 200 (control).
#   FR-MUI01..06, 08, 09  yu18-console-run-scope.mjs: the spec pack's SC-MUI04/06 browser proof on this
#             tier (registry phases and scoped rows are the spec's labelled SQL fixtures). Steps 10-11
#             (database gone, hung read) need DESTRUCTIVE=1: they scale eod-price-db to 0 and hold a
#             table lock.
#
# On a backend with no run registry (GET /v2/projections 404) only the unmanaged label and legacy
# subjects are asserted (FR-MUI06's one legacy signal). On exit the fixture rows, registry rows and
# pointer are put back as found.
#
# Usage: CTX=<ctx> NS=<ns> CONSOLE_PASSWORD=<pw> [CONSOLE_USER=admin DESTRUCTIVE=1] bash scripts/proofs/yu18-console-run-scope.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
: "${CONSOLE_PASSWORD:?set CONSOLE_PASSWORD (the console admin password; it is never printed)}"
FIXTURES="$(cd "${here}/../.." && pwd)/specs/YU18-risk-integration/components/managed-run-ui/proofs"
OUT="${OUT:-$(mktemp -d)/console-run-scope}"
pf CON "${CONSOLE_TARGET:-svc/web-front-end-console}" 8080
pf NMON "${NATS_MON_TARGET:-deploy/nats}" 8222   # monitoring is on the pod, not the Service
pf NATSP "${NATS_TARGET:-svc/nats}" 4222 tcp
JAR="$(mktemp)"
curl -s -m 10 -c "${JAR}" -o /dev/null -H 'Content-Type: application/json' \
  -d "{\"user\":\"${CONSOLE_USER:-admin}\",\"password\":\"${CONSOLE_PASSWORD}\"}" "${CON}/auth/login"

step "0. preflight: registry and fixtures"
reg="$(curl -s -m 10 -b "${JAR}" -o "${BODY_FILE}" -w '%{http_code}' "${CON}/position-service/v2/projections")"
MANAGED=0
case "${reg}" in
  200) MANAGED=1; ok "run registry present: $(body | head -c 200)";;
  404) ok "no run registry (404): unmanaged backend; only the FR-MUI06 legacy arm applies";;
  *)   fail "GET /position-service/v2/projections through the console answered ${reg}: $(body | head -c 200)";;
esac
if [[ "${MANAGED}" == 1 ]]; then
  for a in 22214 10031 11413; do [[ -n "$(sql "SELECT id FROM accounts WHERE id=${a}")" ]] || fail "account ${a} (spec fixture) is absent"; done
  [[ -z "$(sql "SELECT 1 FROM projection_runs WHERE projection_scope IN ('run_a','run_b')")" ]] || fail "run_a/run_b already in the registry: a previous run did not clean up"
  ORIG_PTR="$(sql "SELECT projection_scope FROM projection_active WHERE singleton_id=1")"
  ORIG_LEG="$(sql "SELECT phase, IFNULL(frozen_seq,'NULL') FROM projection_runs WHERE projection_scope='legacy-unknown'")"
  [[ "${ORIG_PTR}" == legacy-unknown && -n "${ORIG_LEG}" ]] || fail "the spec fixtures start from pointer=legacy-unknown; found pointer '${ORIG_PTR}', legacy row '${ORIG_LEG}'"
  read -r LEG_PHASE LEG_FROZEN <<<"${ORIG_LEG}"
  restore() {
    sql "DELETE FROM trades WHERE projectionscope IN ('run_a','run_b'); DELETE FROM positions WHERE projectionscope IN ('run_a','run_b');
         DELETE FROM orderbook WHERE projectionscope IN ('run_a','run_b'); UPDATE projection_active SET projection_scope='legacy-unknown' WHERE singleton_id=1;
         DELETE FROM projection_runs WHERE projection_scope IN ('run_a','run_b');
         UPDATE projection_runs SET phase='${LEG_PHASE}', frozen_seq=${LEG_FROZEN} WHERE projection_scope='legacy-unknown'" >/dev/null
    echo "[restore] fixture rows removed; pointer $(sql "SELECT projection_scope FROM projection_active"), legacy-unknown $(sql "SELECT phase FROM projection_runs WHERE projection_scope='legacy-unknown'")"
  }
  trap 'restore; _lib_yu18_cleanup' EXIT
fi

step "FR-MUI07. the console refuses operator control in every forwarded form"
OP=(-H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-console-run-scope')
for p in /trade-processor/v2/projection-control/activation/0000 /trade-processor/v2/projection-recovery/catch-up \
         /order-matcher/run/control /legacy/trade-processor/v2/projection-recovery/catch-up /legacy/order-matcher/run/control \
         /gw/0/run/control /gw/1/run/control; do
  st="$(curl -s -m 10 -b "${JAR}" -o "${BODY_FILE}" -w '%{http_code}' -X POST "${OP[@]}" -H 'Content-Type: application/json' -d '{}' "${CON}${p}")"
  [[ "${st}" == 403 && "$(body)" == *operator_control_refused* ]] || fail "POST ${p} answered ${st} $(body | head -c 160) (want 403 operator_control_refused)"
done
st="$(curl -s -m 10 -b "${JAR}" -o /dev/null -w '%{http_code}' "${CON}/position-service/v2/projections/active")"
[[ "${st}" == 200 || ( "${MANAGED}" == 0 && "${st}" == 404 ) ]] || fail "control: the reader through the same proxy answered ${st}"
ok "7 forms 403 operator_control_refused, signed in and with the operator token; the reader answers ${st}"

step "FR-MUI01..09. the blotter in Chrome"
u="$(secret mariadb-credentials username)"; p="$(secret mariadb-credentials password)"
OUT="${OUT}" CONSOLE_URL="${CON}/" CONSOLE_USER="${CONSOLE_USER:-admin}" CONSOLE_PASSWORD="${CONSOLE_PASSWORD}" \
  NATS_MON="${NMON}" NATS_PORT="${NATSP}" DB_USER="${u:-traderx}" DB_PASS="${p:-traderx}" DB_CONTAINER="${DB_CONTAINER}" \
  FIXTURES="${FIXTURES}" MANAGED="${MANAGED}" DESTRUCTIVE="${DESTRUCTIVE:-0}" CTX="${CTX}" NS="${NS}" \
  node "${here}/yu18-console-run-scope.mjs" || fail "browser checks failed (evidence: ${OUT})"
echo "[PASS] console run scope: FR-MUI07 refusals + browser checks (evidence ${OUT})"
