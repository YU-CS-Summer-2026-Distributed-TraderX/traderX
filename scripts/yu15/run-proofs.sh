#!/usr/bin/env bash
# Run the proof suite against the YU15 cluster rig, in an order that works, with the port-forwards
# kept alive between proofs.
#
# WHY A RUNNER. Running the proofs back to back by hand does not work, and the reason is not
# obvious. Several of them ROLL THE CLUSTER -- yu13-cancel-ingress rolls the gateway,
# yu13-stp-and-replace rolls all three members -- and a rollout replaces the pod the port-forward is
# attached to, so the forward dies silently. Every proof after it then fails with a connection
# error that has nothing to do with what it tests. Measured directly: a clean sweep where four
# proofs "failed" and `curl localhost:18110/ready` returned 000 afterwards. Each of those four
# passes on its own.
#
# So: forwards are re-established and VERIFIED before every proof, not once at the start.
#
#   bash scripts/yu15/run-proofs.sh              # everything
#   bash scripts/yu15/run-proofs.sh yu03 yu06    # only proofs whose name contains these
#
# Prerequisites:
#   bash scripts/yu15/start-cluster-kind.sh
#   bash scripts/yu15/start-observability-kind.sh     # the two OTel proofs need it
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"

# Ordered deliberately. The cluster-rolling proofs go LAST: they are the slowest, and until they
# run everything else has a stable rig. yu08 is separated from the counter-exact proofs because the
# algo engine's traffic moves next_order_ref underneath them (see seed-proof-fixtures.sh).
PROOFS=(
  yu03-risk-demo
  yu05-settlement
  yu05-recon
  yu05-regulatory-reproducible
  yu05-auth-entitlements
  yu04-live-delta
  yu04-offline-catchup
  yu06-quality-gate
  yu06-consumer-halt
  yu13-clordid-suppression
  yu13-readmodel-effect-end
  yu15-option-persistence
  yu15-risk-extract
  yu15-otel-trace-join
  yu15-otel-reject-trace-log-join
  yu10-fix-session
  yu08-algo-slicing          # needs the algo engine up; scaled in below
  yu13-cancel-ingress        # rolls the gateway
  yu13-stp-and-replace       # rolls all three members
)

if [[ $# -gt 0 ]]; then
  filtered=()
  for p in "${PROOFS[@]}"; do
    for want in "$@"; do [[ "${p}" == *"${want}"* ]] && filtered+=("${p}") && break; done
  done
  PROOFS=("${filtered[@]}")
fi

FORWARDS=(
  "svc/order-matcher 18110:18110"
  "deploy/trade-processor 18091:18091"
  "svc/tempo 3200:3200"
  "svc/loki 3100:3100"
  "svc/grafana 3000:3000"
)

kill_forwards() { pkill -f "port-forward.*${NS}" 2>/dev/null; sleep 1; }
trap kill_forwards EXIT

start_forwards() {
  kill_forwards
  local pf
  for pf in "${FORWARDS[@]}"; do
    # shellcheck disable=SC2086
    ${K} port-forward ${pf} >/dev/null 2>&1 &
  done
  # Verify EVERY forward, not just the gateway. Waiting only on 18110 is what made a suite run
  # fail four proofs that pass individually: the OTel pair needs Tempo on 3200 (which answers 503
  # for a while after its forward is re-made) and yu10 needs trade-processor on 18091. They came up
  # a beat later than the gateway and the proofs started without them, failing on connection
  # errors that named nothing. A forward that is not verified is not established.
  local tries=0 ready
  while :; do
    ready=1
    [[ "$(curl -s -o /dev/null -w '%{http_code}' -m5 http://localhost:18110/ready 2>/dev/null)" == "200" ]] || ready=0
    [[ "$(curl -s -o /dev/null -w '%{http_code}' -m5 http://localhost:18091/actuator/health 2>/dev/null)" == "200" ]] || ready=0
    [[ "$(curl -s -o /dev/null -w '%{http_code}' -m5 http://localhost:3200/ready 2>/dev/null)" == "200" ]] || ready=0
    # Loki and Grafana answer non-2xx on / by design; a connection at all is enough.
    [[ "$(curl -s -o /dev/null -w '%{http_code}' -m5 http://localhost:3100/ 2>/dev/null)" != "000" ]] || ready=0
    [[ ${ready} -eq 1 ]] && break
    tries=$((tries + 1))
    [[ ${tries} -lt 90 ]] || { echo "[fail] forwards never all became reachable"; return 1; }
    sleep 2
  done
}

pass=0; skip=0; fail=0; results=()
for p in "${PROOFS[@]}"; do
  script="${ROOT}/scripts/proofs/${p}.sh"
  [[ -f "${script}" ]] || { echo "[warn] no such proof: ${p}"; continue; }

  # yu08 is the only proof that needs the algo engine; everything else is better off without its
  # traffic moving the counters.
  if [[ "${p}" == yu08-* ]]; then
    ${K} scale deploy/execution-algo-engine --replicas=1 >/dev/null 2>&1
    ${K} rollout status deploy/execution-algo-engine --timeout=300s >/dev/null 2>&1
  else
    ${K} scale deploy/execution-algo-engine --replicas=0 >/dev/null 2>&1
  fi

  start_forwards || { echo "[fail] could not establish forwards before ${p}"; break; }
  bash "${ROOT}/scripts/yu15/seed-proof-fixtures.sh" >/dev/null 2>&1

  printf "%-34s " "${p}"
  bash "${script}" > "/tmp/proofrun/${p}.log" 2>&1
  case $? in
    0) echo "PASS"; pass=$((pass + 1)); results+=("PASS ${p}") ;;
    2) echo "SKIP (capability absent — see log)"; skip=$((skip + 1)); results+=("SKIP ${p}") ;;
    *) echo "FAIL"; fail=$((fail + 1)); results+=("FAIL ${p}") ;;
  esac
done

echo
echo "==== ${pass} passed, ${skip} skipped, ${fail} failed ===="
printf '%s\n' "${results[@]}" | grep -v '^PASS' || true
[[ ${fail} -eq 0 ]]
