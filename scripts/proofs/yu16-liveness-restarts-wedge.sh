#!/usr/bin/env bash
# yu16-liveness-restarts-wedge.sh — a gateway that cannot commit is RESTARTED by Kubernetes with no
# human in the loop, and the probe that says so is answerable while the order path is saturated.
#
# THE HALF OF THE FIX THIS GUARDS (issues/HANDOFF-issue-gateway-wedges-after-leader-kill.md).
# Readiness (yu16-ready-tracks-commit.sh) made /ready mean "I can commit", which stops the
# LoadBalancer feeding a gateway that books what it denies. On a multi-replica tier that is the
# whole cure. On THIS rig — and on the GKE correctness rig, both replicas: 1 — there is nowhere
# else to route, so the outage simply persists until a human runs `kubectl rollout restart`. That
# restart is the known, reliable remedy and the gateway is stateless; the liveness probe is what
# asks for it automatically.
#
# WHAT MAKES THIS FALSIFIABLE rather than a script watching a pod bounce:
#
#   * the NEGATIVE CONTROL runs first — a drive that OVERSUBSCRIBES the gateway's 64-thread HTTP
#     pool, on a healthy cluster, must leave /live at 200 and the restart count untouched. A
#     liveness probe that trips on load (or on its own) cannot pass this;
#   * the PROBE-PORT assertion is checked at the worst moment — while the order path is saturated
#     AND cannot commit, which is exactly the state (§5) in which the gateway measurably stopped
#     answering ALL HTTP on 18110. If the probe port answers there, the verdict below is the
#     signal and not a timeout, which is the difference between a diagnosable restart and an
#     indiscriminate one;
#   * the restart is attributed — a kubelet event naming the liveness probe, and not OOMKilled —
#     so a pod that died for any other reason cannot be read as a pass. Either of the kubelet's
#     two phrasings counts; see step 3 for why demanding the Unhealthy one fails on a real
#     cluster against a perfectly correct restart.
#
# WHY QUORUM LOSS AND NOT THE WEDGE: the wedge is a race (~1 run in 4). Quorum loss induces the
# same PROPERTY deterministically — the gateway cannot commit anything — which is all the probe
# claims to detect.
#
# DESTRUCTIVE: scales the member StatefulSet to 1 and back, and deliberately gets the gateway
# container killed. No PVC wipe, no epoch change. Run it late, like the other rolling proofs.
#
# Usage: ./yu16-liveness-restarts-wedge.sh [-v]
set -euo pipefail

VERBOSE=0
case "${1:-}" in -v|--verbose) VERBOSE=1; shift ;; esac
vlog() { [ "${VERBOSE}" = 1 ] && printf '%s\n' "$@" >&2 || true; }

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
ACCT="${ACCT:-42422}"
TICKER="${TICKER:-LIV$(date +%H%M%S)}"
PRICE="${PRICE:-100.00}"
# Deliberately ABOVE the gateway's 64-thread HTTP pool: the point is to hold every order thread at
# once, which is the condition under which the gateway stopped answering 18110 entirely (§5).
DRIVE="${DRIVE:-80}"

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }

# Forward to the POD, not the Service: a failing readiness probe removes the pod from the Service
# at precisely the moment the measurement matters, and a svc-based forward then reports 000 instead
# of the 503 being asserted. Both ports are re-established after the restart under test.
PROBE_PORT="${PROBE_PORT:-18511}"
ORDER_PORT="${ORDER_PORT:-18512}"
PF_PIDS=()
cleanup() {
  for p in "${PF_PIDS[@]:-}"; do [[ -n "${p}" ]] && kill "${p}" 2>/dev/null || true; done
  # Never leave the cluster at one member. Unlike a rollout, a half-scaled StatefulSet makes every
  # proof after this one fail for a reason that has nothing to do with what it tests.
  if [[ "$(${K} get sts order-matcher-cluster -o jsonpath='{.spec.replicas}' 2>/dev/null)" != "3" ]]; then
    echo "[cleanup] restoring order-matcher-cluster to 3 replicas" >&2
    ${K} scale sts order-matcher-cluster --replicas=3 >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

forward_once() { # (re)establish both forwards against the current gateway pod, then poll briefly
  for p in "${PF_PIDS[@]:-}"; do [[ -n "${p}" ]] && kill "${p}" 2>/dev/null || true; done
  PF_PIDS=()
  ${K} port-forward "pod/${GW}" "${PROBE_PORT}:18111" >/dev/null 2>&1 & PF_PIDS+=($!)
  ${K} port-forward "pod/${GW}" "${ORDER_PORT}:18110" >/dev/null 2>&1 & PF_PIDS+=($!)
  # Or bash reports each forward as "Terminated: 15" when the next forward() reaps it — noise in
  # the middle of the step a human reads to decide what the restart proved.
  # -a, not bare `disown`: there are TWO forwards and bare disown only detaches the
  # most recent job, so the other still reports "Terminated: 15" when cleanup reaps it.
  disown -a 2>/dev/null || true
  # 40s per attempt, six attempts = ~240s overall, which covers the ~60s GKE cold start several
  # times over. Per-attempt rather than one long wait is the whole point: a dead forward has to be
  # noticed and re-spawned, and a budget that never returns control cannot do that.
  local i
  for i in $(seq 1 40); do [[ "$(live_code)" != "000" ]] && return 0; sleep 1; done
  return 1
}

# RETRY THE FORWARD ITSELF, not just the request through it. `kubectl port-forward` is a process,
# and it DIES when the container it targets restarts — which is precisely when step 4 calls this.
# Spawning it once and then polling curl for a while cannot recover from that: the listener is
# gone, so every poll returns 000 no matter how long the budget, and the proof reports "the
# gateway probe port never came back after the restart" about a gateway that is serving fine.
#
# Measured on GKE 2026-08-13, three consecutive runs. The last one is the unambiguous one:
# container started 21:33:21, forwarding polled 21:33:30 -> 21:37:30 and saw 000 throughout, and a
# hand-made forward answered 200 immediately afterwards. The gateway was up for three of those
# four minutes. Widening the poll budget 60 -> 240 did NOT fix it and was the wrong diagnosis;
# only re-spawning does.
#
# kind hid this the same way it hid the other two: its restart is fast enough that the single
# spawn usually lands after the container is back.
forward() {
  local attempt
  for attempt in 1 2 3 4 5 6; do
    forward_once && return 0
    echo "  [forward] attempt ${attempt} saw no answer on ${PROBE_PORT}; re-establishing" >&2
  done
  return 1
}

PROBE_URL="http://localhost:${PROBE_PORT}"
ORDER_URL="http://localhost:${ORDER_PORT}"
live_body() { curl -s -m 10 "${PROBE_URL}/live" 2>/dev/null; }
live_code() { curl -s -m 10 -o /dev/null -w '%{http_code}' "${PROBE_URL}/live" 2>/dev/null; }
ready_code() { curl -s -m 10 -o /dev/null -w '%{http_code}' "${PROBE_URL}/ready" 2>/dev/null; }
jnum() { python3 -c 'import sys,json
try: print(json.loads(sys.stdin.read()).get(sys.argv[1], ""))
except Exception: print("")' "$1"; }
streak() { live_body | jnum noAckStreak; }
restarts() { ${K} get pod "${GW}" -o jsonpath='{.status.containerStatuses[0].restartCount}' 2>/dev/null; }

# Seed, and say WHY when it does not work — see the same helper in yu16-ready-tracks-commit.sh. A
# curl rc of 7/28 is the forward, an HTTP code is the gateway answering, and a 200 carrying
# {"seeded":false} is the engine's symbol table exhausted, which `curl -f` does not treat as an
# error at all. This proof makes its own forwards, so rc=7 here means forward() lost the pod.
seed() {
  local out rc code body
  # `&& rc=0 || rc=$?`, not a bare assignment then `rc=$?`: under `set -e` a failing command
  # substitution takes the whole script down on the assignment line, so the diagnostic below would
  # never print and the proof would die silently with curl's exit code.
  out="$(curl -s -m 20 -w '\n%{http_code}' -X POST "${ORDER_URL}/seed" \
    -H 'Content-Type: application/json' \
    -d "{\"accountId\":${ACCT},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}" 2>&1)" && rc=0 || rc=$?
  code="$(printf '%s' "${out}" | tail -1)"
  body="$(printf '%s' "${out}" | sed '$d')"
  [[ ${rc} -eq 0 ]] || fail "seed could not reach ${ORDER_URL} (curl rc=${rc}).
  rc=7 is nothing listening and rc=28 a timeout — this proof owns its own forwards, so that means
  forward() lost pod/${GW}, not that the cluster is unwell."
  [[ "${code}" == "200" ]] || fail "seed got HTTP ${code} from ${ORDER_URL}: ${body:-empty body}"
  [[ "${body}" == *'"seeded":true'* ]] || fail "seed returned 200 but did not seed: ${body:-empty}.
  {\"seeded\":false} is the engine's symbol table exhausted (MAX_SECURITIES) — a fresh epoch is the
  only cure, and every assertion below would otherwise run against an unregistered ticker."
}

order() { curl -s -m 20 "${ORDER_URL}/orders" -X POST -H 'Content-Type: application/json' \
  -d "{\"accountId\":${ACCT},\"ticker\":\"${TICKER}\",\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":${PRICE}${1:+,\"clientOrderId\":\"$1\"}}" 2>/dev/null; }
drive() { # DRIVE orders at once; every one parks a gateway HTTP thread for the ack timeout
  local i pids=()
  for i in $(seq 1 "${DRIVE}"); do
    order "liv-$$-${1}-${i}" >/dev/null &
    pids+=($!)
  done
  # Wait on THESE pids, never a bare `wait` — the port-forwards are background jobs of this shell.
  for i in "${pids[@]}"; do wait "${i}" 2>/dev/null || true; done
}

# ---------------------------------------------------------------------------------------------
step "0. preflight — a build and a manifest that HAVE the liveness signal"
GW="$(${K} get pods -l app=cluster-gateway -o jsonpath='{.items[0].metadata.name}')"
[[ -n "${GW}" ]] || fail "no cluster-gateway pod"
LIVE_PATH="$(${K} get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].livenessProbe.httpGet.path}')"
[[ "${LIVE_PATH}" == "/live" ]] || fail "the gateway Deployment carries no livenessProbe on /live
  (got '${LIVE_PATH:-none}'). Nothing below would be testing Kubernetes' behaviour."
forward || fail "the gateway probe port (18111) never answered — this build predates the split
  probe server, so /live cannot be the thing under test"
BODY="$(live_body)"
LIMIT="$(printf '%s' "${BODY}" | jnum noAckLimit)"
[[ "${LIMIT}" =~ ^[0-9]+$ ]] || fail "no noAckLimit in /live (${BODY})"
[[ "$(live_code)" == "200" ]] || fail "gateway is not live before the proof starts: ${BODY}"
R0="$(restarts)"
[[ "${R0}" =~ ^[0-9]+$ ]] || fail "could not read restartCount for ${GW}"
seed
echo "  ${GW} restarts=${R0} ${BODY}"

step "1. NEGATIVE CONTROL — ${DRIVE} concurrent orders (> the 64-thread pool) on a HEALTHY cluster"
drive control
CODE="$(live_code)"; BODY="$(live_body)"
echo "  after ${DRIVE} healthy orders: [${CODE}] ${BODY}"
[[ "${CODE}" == "200" ]] || fail "/live went ${CODE} on a HEALTHY cluster under ordinary load — this
  probe would restart every gateway in the fleet at peak"
# 70s, and the number is load-bearing: livenessProbe is periodSeconds 10 x failureThreshold 6, so
# a probe that was going to fire takes 60s to do it. This used to wait 20s and claim exactly the
# sentence below — which only an INSTANT restart could have falsified, so the assertion was
# decorative for the other 40 seconds. It matters more than the other timing constants in this
# file: a restart storm under healthy load is the single risk the whole liveness decision was held
# for, and this is the only step that defends against it.
sleep 70
[[ "$(restarts)" == "${R0}" ]] || fail "the gateway restarted ($(restarts) vs ${R0}) with the cluster
  healthy: the probe is not measuring the ability to commit"

step "2. remove quorum, then saturate — the probe must still ANSWER"
${K} scale sts order-matcher-cluster --replicas=1 >/dev/null
sleep 25
ROUND=0
while :; do
  ROUND=$((ROUND + 1))
  drive "noquorum${ROUND}" &
  DRIVE_BG=$!
  # THE §5 ASSERTION, taken while the drive is still in flight: every order thread is parked on an
  # ack that will never come, which is the exact state in which the gateway measurably stopped
  # answering all HTTP on 18110. The probe port has its own single-thread executor and must be
  # unaffected — otherwise the liveness verdict below is a TIMEOUT, which is indiscriminate.
  sleep 6
  SAT_CODE="$(live_code)"
  echo "  round ${ROUND}: probe port under saturation -> [${SAT_CODE}] streak=$(streak)"
  [[ "${SAT_CODE}" != "000" ]] || fail "the probe port did not answer while the order path was
    saturated and unable to commit. That is §5 of the issue: a probe the server cannot serve is not
    a signal, and Kubernetes would be acting on a timeout rather than on the gateway's own verdict."
  wait "${DRIVE_BG}" 2>/dev/null || true
  S="$(streak)"
  [[ "${S}" =~ ^[0-9]+$ ]] || fail "probe stopped answering after the drive: $(live_body)"
  [[ "${S}" -ge "${LIMIT}" ]] && break
  [[ "${ROUND}" -lt 6 ]] || fail "streak stalled at ${S} after ${ROUND} rounds of ${DRIVE} — it never
    reached the liveness limit ${LIMIT}, so this run cannot show what the probe does there"
done
CODE="$(live_code)"; BODY="$(live_body)"
echo "  [${CODE}] ${BODY}"
[[ "${CODE}" == "503" ]] || fail "/live is ${CODE} at streak ${S} >= limit ${LIMIT}"
[[ "$(ready_code)" == "503" ]] || fail "/ready is not 503 while /live is — readiness must fail first
  and by a wide margin, or liveness is restarting pods that were still in the Service"

step "3. THE ASSERTION — Kubernetes restarts the container, with no human"
# failureThreshold 6 x periodSeconds 10 = 60s of sustained failure by design, plus the kill and the
# JVM+Aeron start. Nothing below touches the pod; the kubelet is the only actor.
for _ in $(seq 1 60); do
  [[ "$(restarts)" != "${R0}" ]] && break
  sleep 5
done
R1="$(restarts)"
[[ "${R1}" != "${R0}" ]] || fail "the gateway was never restarted (restarts still ${R0}) after ${S}
  consecutive submits got no committed ack. Without a restart this rig has no path back: at
  replicas: 1 readiness has nowhere else to route."
TERM_REASON="$(${K} get pod "${GW}" -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason}' 2>/dev/null)"
[[ "${TERM_REASON}" != "OOMKilled" ]] || fail "the container was OOMKilled, not killed by the probe —
  this run proves nothing about liveness"
# TWO ACCEPTABLE ATTRIBUTIONS, and the second one is the reliable one. The kubelet emits both an
# Unhealthy/"Liveness probe failed" per failed check AND a single Killing/"failed liveness probe,
# will be restarted" when it acts. This used to demand the first, which passed on kind and FAILED
# ON GKE on 2026-08-13 against a restart that was entirely correct: streak 144, /live 503,
# exitCode 143, and the Killing event naming the liveness probe — with no Unhealthy/Liveness event
# recorded at all.
#
# Not a GKE quirk, and not flake. The kubelet's event recorder rate-limits per (object, reason),
# and readiness had already spent that budget under the SAME reason=Unhealthy: 150 recorded
# "Readiness probe failed" events on that pod. By construction readiness fails long before
# liveness here (limit 20 vs 100), so whenever liveness fires, the Unhealthy budget is already
# saturated — the crowding is guaranteed, not incidental. kind only survived it by recording
# fewer events first.
#
# The Killing event is emitted once per kill, so it is not subject to that pressure, and it names
# the cause just as explicitly. Accept either.
EVENT_MSGS="$(${K} get events --field-selector "involvedObject.name=${GW}" \
  -o jsonpath='{range .items[*]}{.message}{"\n"}{end}' 2>/dev/null)"
ATTRIB="$(printf '%s' "${EVENT_MSGS}" | grep -m1 -E 'Liveness probe failed|failed liveness probe')" \
  || fail "restartCount moved ${R0} -> ${R1} but no event names the liveness probe as the cause
  (looked for 'Liveness probe failed' and 'failed liveness probe'). The restart is not
  attributable, and an unattributable restart is not a pass.
  The events this pod DID record — read them before believing the restart was unattributed, since
  a rate-limited Unhealthy budget looks identical here to a restart nobody can explain:
$(${K} get events --field-selector "involvedObject.name=${GW}" \
    -o custom-columns=COUNT:.count,REASON:.reason,MESSAGE:.message 2>&1 | sed 's/^/    /')"
echo "  restarts ${R0} -> ${R1}, lastState.terminated.reason=${TERM_REASON:-none}, kubelet event: ${ATTRIB}"

step "4. the restarted gateway serves again once quorum is back"
${K} scale sts order-matcher-cluster --replicas=3 >/dev/null
${K} rollout status sts/order-matcher-cluster --timeout=600s >/dev/null
forward || fail "the gateway probe port never came back after the restart"
for _ in $(seq 1 60); do [[ "$(ready_code)" == "200" ]] && break; sleep 2; done
[[ "$(ready_code)" == "200" ]] || fail "the restarted gateway never became ready: $(curl -s -m10 "${PROBE_URL}/ready")"
RESP="$(order recovered)"
echo "  order -> ${RESP}"
[[ "${RESP}" == *'"orderRef"'* ]] || fail "the restarted gateway still cannot commit: ${RESP}"
[[ "$(live_code)" == "200" ]] || fail "/live did not recover: $(live_body)"

echo
echo "[PASS] liveness restarts a gateway that cannot commit: ${DRIVE} concurrent orders on a healthy"
echo "       cluster left /live at 200 and the pod untouched; with quorum gone the probe port kept"
echo "       answering while every order thread was parked, /live went 503 at streak ${S} >= ${LIMIT},"
echo "       and the kubelet restarted the container on its own (${R0} -> ${R1}) — the remedy that"
echo "       previously needed a human running rollout restart."
