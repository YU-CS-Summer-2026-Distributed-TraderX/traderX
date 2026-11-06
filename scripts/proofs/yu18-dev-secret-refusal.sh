#!/usr/bin/env bash
# yu18-dev-secret-refusal.sh — YU18 FR-ERH-S5: the gateway and the member refuse to start on an unset or known
# development secret unless ALLOW_DEV_SECRETS=true.
#
# Runs the rig's OWN cluster-node image (read from the members' StatefulSet) as one-off pods beside the rig; it
# touches no workload. Each arm is a pod whose only differences from its control are the named ones:
#   - gateway, AUTH_JWT_SECRET=dev-jwt-shared-secret, real RISK_CONTROL_TOKEN  -> exits non-zero, log names AUTH_JWT_SECRET
#   - gateway, RISK_CONTROL_TOKEN=dev-risk-control, real JWT secret          -> exits non-zero, log names RISK_CONTROL_TOKEN
#   - member,  AUTH_JWT_SECRET unset                                         -> exits non-zero, log names AUTH_JWT_SECRET
#   - control: the first arm plus ALLOW_DEV_SECRETS=true                     -> no refusal line (it gets past the check)
# The refusal is read at the effect end: the container's terminated exit code and its own log line.
#
# Negative control: NEGATIVE=1 adds ALLOW_DEV_SECRETS=true to every refusing arm. That must FAIL; a pass means the
# proof cannot tell a refusal from a start.
# Usage: CTX=<ctx> NS=<ns> [NEGATIVE=1] bash scripts/proofs/yu18-dev-secret-refusal.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
IMG="$("${K[@]}" get statefulset order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].image}')"
[[ -n "${IMG}" ]] || fail "no order-matcher-cluster StatefulSet image"
# each arm runs with its workload's own JVM flags: the gateway's constructor needs its --add-opens before main's check
jvm_of() { "${K[@]}" get "$1" -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="JAVA_TOOL_OPTIONS")].value}'; }
GWJ="$(jvm_of deploy/cluster-gateway)"; MBJ="$(jvm_of statefulset/order-matcher-cluster)"
REAL="$(python3 -c 'import secrets;print(secrets.token_hex(32))')"
CP=/opt/app/classes:/opt/app/lib/*
# run_arm runs in $( ), so a list built inside it never reaches this shell: the pods carry this run's label instead
RUN="pa-devsecret-$$"
cleanup() { "${K[@]}" delete pod -l "pa-proof=${RUN}" --wait=false >/dev/null 2>&1; _lib_yu18_cleanup; }
trap cleanup EXIT
NEGENV=(); [[ "${NEGATIVE:-0}" == 1 ]] && NEGENV=(--env=ALLOW_DEV_SECRETS=true)

# run_arm <name> <main class> <env...> — a one-off pod; prints "<exit code>" once it has terminated, or "running".
run_arm() {
  local name="${RUN}-$1" main="$2" i st; shift 2
  "${K[@]}" run "${name}" --image="${IMG}" --labels="pa-proof=${RUN}" --restart=Never --image-pull-policy=IfNotPresent "$@" \
    --command -- java -cp "${CP}" "${main}" >/dev/null || fail "kubectl run ${name}"
  for i in $(seq 1 60); do
    st="$("${K[@]}" get pod "${name}" -o jsonpath='{.status.containerStatuses[0].state.terminated.exitCode}' 2>/dev/null)"
    [[ -n "${st}" ]] && { echo "${st}"; return 0; }
    sleep 1
  done
  echo running
}
logs_of() { "${K[@]}" logs "${RUN}-$1" 2>/dev/null; }
refused() { # refused <arm> <variable> <exit>
  local l; l="$(logs_of "$1")"
  [[ "$3" != running && "$3" != 0 ]] || fail "$1: the process did not refuse (state: $3) — FR-ERH-S5 says it must not start on a dev $2"
  grep -qF "$2 is unset or a development default" <<<"${l}" || fail "$1: exited $3 but not on the dev-secret check: $(tail -3 <<<"${l}")"
  ok "$1: exit $3, \"$(grep -F "$2 is unset" <<<"${l}" | tail -1 | cut -c1-140)\""
}
GW=finos.traderx.ordermatcher.cluster.ClusterGatewayMain
MB=finos.traderx.ordermatcher.cluster.ClusterNodeMain

step "0. image ${IMG}"
ok "arms run beside the rig in ${NS}; no workload is changed${NEGATIVE:+ (NEGATIVE: ALLOW_DEV_SECRETS=true on every refusing arm)}"

step "1. gateway on the dev JWT secret"
e="$(run_arm gw-jwt "${GW}" --env=JAVA_TOOL_OPTIONS="${GWJ}" --env=AUTH_JWT_SECRET=dev-jwt-shared-secret --env=RISK_CONTROL_TOKEN="${REAL}" ${NEGENV[@]+"${NEGENV[@]}"})"
refused gw-jwt AUTH_JWT_SECRET "${e}"

step "2. gateway on the dev operator token"
e="$(run_arm gw-rct "${GW}" --env=JAVA_TOOL_OPTIONS="${GWJ}" --env=AUTH_JWT_SECRET="${REAL}" --env=RISK_CONTROL_TOKEN=dev-risk-control ${NEGENV[@]+"${NEGENV[@]}"})"
refused gw-rct RISK_CONTROL_TOKEN "${e}"

step "3. member with AUTH_JWT_SECRET unset"
e="$(run_arm mb-jwt "${MB}" --env=JAVA_TOOL_OPTIONS="${MBJ}" --env=CLUSTER_MEMBER_ID=0 ${NEGENV[@]+"${NEGENV[@]}"})"
refused mb-jwt AUTH_JWT_SECRET "${e}"

step "4. control: arm 1 with ALLOW_DEV_SECRETS=true gets past the check"
e="$(run_arm gw-allow "${GW}" --env=JAVA_TOOL_OPTIONS="${GWJ}" --env=AUTH_JWT_SECRET=dev-jwt-shared-secret --env=RISK_CONTROL_TOKEN="${REAL}" --env=ALLOW_DEV_SECRETS=true)"
l="$(logs_of gw-allow)"
! grep -qF "is unset or a development default" <<<"${l}" || fail "control: refused even with ALLOW_DEV_SECRETS=true: $(grep -F 'development default' <<<"${l}" | tail -1)"
[[ -n "${l}" ]] || fail "control: the pod logged nothing (state ${e}); it cannot show it got past the check"
ok "no refusal line (state ${e}; it then fails or runs on whatever this one-off pod lacks, which is not the check)"

echo; echo "YU18-DEV-SECRET-REFUSAL PASS image=${IMG}"
