#!/usr/bin/env bash
# yu16-ready-tracks-commit.sh — the gateway's readiness signal means "I can commit", not "my socket
# is open", and it clears only on a demonstrated commit.
#
# THE DEFECT THIS GUARDS (issues/open/HANDOFF-issue-gateway-wedges-after-leader-kill.md). After a leader
# kill a single gateway can hold a session it believes is good while every order comes back 504
# "no committed ack" — and the orders are not refused, they are committed and booked while the
# client is told they failed. `/ready` answered {"connected":true} throughout, so Kubernetes never
# took the pod out of the Service and the LoadBalancer kept routing a public IP into it.
#
# WHY QUORUM LOSS AND NOT THE WEDGE. The wedge is a RACE — reproduced roughly one run in four. A
# proof that depends on it SKIPs most of the time, which is worthless as a regression check. Quorum
# loss induces the same *property* deterministically: the gateway cannot commit anything, and
# crucially, when the members come back the SOCKET recovers before the ability to commit is
# demonstrated. That gap is the exact moment the old probe lied, and it is asserted below.
#
# What makes this falsifiable rather than a script watching a probe turn red:
#
#   * the NEGATIVE CONTROL runs first — the same order volume on a healthy cluster must leave
#     /ready at 200 with the streak at zero, so a probe that fails on traffic (or always) cannot
#     pass;
#   * the discriminating assertion is the RESTORED-QUORUM window: connected is back to true while
#     /ready is still 503. That is where the previous socket-based probe reported healthy, so it is
#     the one moment that proves the change under test is doing the work;
#   * readiness must clear only AFTER a real committed order, never on the socket alone.
#
# DESTRUCTIVE: scales the member StatefulSet to 1 and back. No PVC wipe, no epoch change, but the
# cluster is unavailable for the duration — run it late, like the other rolling proofs.
#
# Usage: ./yu16-ready-tracks-commit.sh [-v]
set -euo pipefail

VERBOSE=0
case "${1:-}" in -v|--verbose) VERBOSE=1; shift ;; esac
vlog() { [ "${VERBOSE}" = 1 ] && printf '%s\n' "$@" >&2 || true; }

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
# READ THE POD, NOT THE SERVICE. The whole point of this change is that a failing readiness probe
# removes the pod from the Service — which kills a svc-based port-forward at exactly the moment the
# measurement matters, and reports 000 instead of the 503 being asserted.
READY_URL="${READY_URL:-}"
# WHOSE ENDPOINT IS THIS? A remedy may only name something this script put in the path. The forward
# below (step 0) is ours and is for READY_URL on 18510; the MATCHER_URL forward is NOT made here, so
# naming it is only right while MATCHER_URL is the default WE chose. The bench suite exports
# MATCHER_URL to in-cluster and cloud endpoints (run-all-tiers.sh, run-incluster-comparable.sh,
# rest-latency-probe.mjs), so an inherited value is a live path, not a hypothetical — and on it a
# port-forward hint sends the operator to build something that is not in the path at all.
MATCHER_SET_BY_CALLER=$([[ -n "${MATCHER_URL:-}" ]] && echo yes || echo no)
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
ACCT="${ACCT:-42422}"
TICKER="${TICKER:-RDY$(date +%H%M%S)}"
PRICE="${PRICE:-100.00}"
DRIVE="${DRIVE:-25}"

fail() { echo "[FAIL] $*" >&2; exit 1; }
# What an unreachable MATCHER_URL means depends on who chose it — see MATCHER_SET_BY_CALLER.
matcher_hint() {
  if [[ "${MATCHER_SET_BY_CALLER}" == "yes" ]]; then
    echo "MATCHER_URL was set by the caller, so check that endpoint itself — this script makes no
  forward for it and a port-forward is very likely not in its path."
  else
    echo "MATCHER_URL is this script's own default, which needs a forward this script does not make:
  kubectl --context ${CTX} -n ${NS} port-forward svc/order-matcher 18110:18110"
  fi
}
step() { echo; echo "=== $* ==="; }

PF_PID=""
# ADR-072: THE DISCRIMINATOR NEEDS A WINDOW IN WHICH NOTHING COMMITS, AND THE TAPE REPLAY DENIES IT.
#
# Step 3's whole content is the gap between "the socket came back" and "something committed": the
# old socket-based probe reported healthy in it, and the fixed probe must not. The replay submits
# ~6 orders/s THROUGH THIS GATEWAY, so the first one to commit after quorum returns clears the
# no-ack streak and /ready is legitimately 200 before this proof reads it. Measured 2026-08-26 on
# the first suite run with the replay live: step 3 read 200 with noAckStreak already back to 0, and
# accused the gateway of tracking the connection while the gateway was doing exactly the right
# thing.
#
# This is NOT "turn the replay off so the suite goes green". The claim under test is unchanged and
# is still asserted in full; what is being removed is a CONFOUNDING VARIABLE that makes the
# experiment undecidable — the same reason step 2 removes quorum rather than waiting for one to
# fail. It is scoped to steps 2-4, restored on every exit path, and the proof FAILS rather than
# continues if it cannot take it down or bring it back.
REPLAY_PAUSED=0
pause_replay() {
  ${K} get deploy price-publisher >/dev/null 2>&1 || { echo "  [note] no price-publisher on this tier; nothing to pause"; return 0; }
  ${K} scale deploy price-publisher --replicas=0 >/dev/null \
    || fail "could not pause the tape replay, so the no-commit window step 3 needs cannot exist"
  ${K} wait --for=delete pod -l app=price-publisher --timeout=120s >/dev/null 2>&1
  REPLAY_PAUSED=1
  echo "  [replay] price-publisher scaled to 0 — no external order flow until step 4 completes"
}
resume_replay() {
  [[ "${REPLAY_PAUSED}" == "1" ]] || return 0
  REPLAY_PAUSED=0
  ${K} scale deploy price-publisher --replicas=1 >/dev/null 2>&1
  ${K} rollout status deploy/price-publisher --timeout=300s >/dev/null 2>&1 \
    && echo "  [replay] price-publisher restored" \
    || echo "  [replay] WARNING: price-publisher did not come back — the rig has no feed and no"
  return 0
}
cleanup() {
  [[ -n "${PF_PID}" ]] && kill "${PF_PID}" 2>/dev/null || true
  resume_replay
}
trap cleanup EXIT

ready_body() { curl -s -m 10 "${READY_URL}/ready" 2>/dev/null; }
ready_code() { curl -s -m 10 -o /dev/null -w '%{http_code}' "${READY_URL}/ready" 2>/dev/null; }
jnum() { python3 -c 'import sys,json
try: print(json.loads(sys.stdin.read()).get(sys.argv[1], ""))
except Exception: print("")' "$1"; }

# Seed, and say WHY when it does not work. This used to be `curl -sf … || fail "seed failed"`, which
# discards all three things that tell the causes apart — and they are genuinely different: a curl rc
# of 7/28 is a dead or missing port-forward (nothing to do with the cluster), an HTTP code is the
# gateway answering, and a 200 carrying {"seeded":false} is the engine's symbol table exhausted,
# which -f does not even treat as an error. Two people hit the bare message from two different
# causes on 2026-08-13, which is one more than a message like that is worth.
seed() {
  local out rc code body
  # `&& rc=0 || rc=$?`, not a bare assignment then `rc=$?`: under `set -e` a failing command
  # substitution takes the whole script down on the assignment line, so the diagnostic below would
  # never print and the proof would die silently with curl's exit code — which is worse than the
  # bare "seed failed" this replaced.
  out="$(curl -s -m 20 -w '\n%{http_code}' -X POST "${MATCHER_URL}/seed" \
    -H 'Content-Type: application/json' \
    -d "{\"accountId\":${ACCT},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}" 2>&1)" && rc=0 || rc=$?
  code="$(printf '%s' "${out}" | tail -1)"
  body="$(printf '%s' "${out}" | sed '$d')"
  [[ ${rc} -eq 0 ]] || fail "seed could not reach ${MATCHER_URL} (curl rc=${rc}).
  rc=7 is nothing listening and rc=28 is a timeout — the transport, not the cluster.
  $(matcher_hint)"
  [[ "${code}" == "200" ]] || fail "seed got HTTP ${code} from ${MATCHER_URL}: ${body:-empty body}"
  [[ "${body}" == *'"seeded":true'* ]] || fail "seed returned 200 but did not seed: ${body:-empty}.
  {\"seeded\":false} is the engine's symbol table exhausted (MAX_SECURITIES) — a fresh epoch is the
  only cure, and every assertion below would otherwise run against an unregistered ticker."
}

order() { curl -s -m 20 "${MATCHER_URL}/orders" -X POST -H 'Content-Type: application/json' \
  -d "{\"accountId\":${ACCT},\"ticker\":\"${TICKER}\",\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":${PRICE}${1:+,\"clientOrderId\":\"$1\"}}" 2>/dev/null; }
# Bounded concurrency, deliberately well under the gateway's 64-thread HTTP pool. An unbounded
# drive against a gateway that cannot commit parks a thread per request for the full ack timeout
# and starves the probe itself — measured: /ready stopped answering entirely (000, permanently),
# which is a different failure and would make every assertion here unattributable.
drive() {
  local i pids=()
  for i in $(seq 1 "${DRIVE}"); do
    order "drv-$$-${1}-${i}" >/dev/null &
    pids+=($!)
  done
  # Wait on THESE pids, never a bare `wait`. Step 0 leaves a kubectl port-forward running in the
  # background and a bare wait blocks on it forever — the proof hung in step 1 with no output and
  # no failure, which is indistinguishable from a slow cluster.
  for i in "${pids[@]}"; do wait "${i}" 2>/dev/null || true; done
}

# ---------------------------------------------------------------------------------------------
step "0. preflight — a build that HAS the new signal"
if [[ -z "${READY_URL}" ]]; then
  GW="$(${K} get pods -l app=cluster-gateway -o jsonpath='{.items[0].metadata.name}')"
  [[ -n "${GW}" ]] || fail "no cluster-gateway pod"
  ${K} port-forward "pod/${GW}" 18510:18110 >/dev/null 2>&1 & PF_PID=$!
  READY_URL="http://localhost:18510"
  for _ in $(seq 1 30); do [[ "$(ready_code)" != "000" ]] && break; sleep 1; done
fi
BODY="$(ready_body)"
LIMIT="$(printf '%s' "${BODY}" | jnum noAckLimit)"
[[ "${LIMIT}" =~ ^[0-9]+$ ]] || fail "this gateway build has no noAckLimit in /ready (${BODY}).
  It predates the readiness fix, so the 200s below would be the OLD probe and mean nothing."
[[ "${DRIVE}" -gt "${LIMIT}" ]] || fail "DRIVE=${DRIVE} does not exceed noAckLimit=${LIMIT}"
[[ "$(ready_code)" == "200" ]] || fail "gateway is not ready before the proof starts: ${BODY}"
seed
echo "  ${BODY}"

step "1. NEGATIVE CONTROL — ${DRIVE} orders on a HEALTHY cluster leave /ready at 200"
drive control
CODE="$(ready_code)"; BODY="$(ready_body)"
echo "  after ${DRIVE} healthy orders: [${CODE}] ${BODY}"
[[ "${CODE}" == "200" ]] || fail "/ready went ${CODE} on a HEALTHY cluster after ordinary traffic —
  the probe is failing on load, and would take the ingress down under peak"
[[ "$(printf '%s' "${BODY}" | jnum noAckStreak)" == "0" ]] || fail "streak non-zero when healthy: ${BODY}"

step "2. remove quorum: the gateway can no longer commit anything"
pause_replay
${K} scale sts order-matcher-cluster --replicas=1 >/dev/null
sleep 25
drive noquorum
CODE="$(ready_code)"; BODY="$(ready_body)"
STREAK="$(printf '%s' "${BODY}" | jnum noAckStreak)"
echo "  [${CODE}] ${BODY}"
[[ "${CODE}" == "503" ]] || fail "/ready is ${CODE} while the cluster has no quorum and nothing can
  commit — this is precisely the state a readiness probe exists to report"
[[ "${STREAK}" -ge "${LIMIT}" ]] \
  || fail "503 but streak ${STREAK} < limit ${LIMIT}: it went unready for some other reason"

step "3. THE DISCRIMINATOR — quorum back, socket back, still NOT ready"
${K} scale sts order-matcher-cluster --replicas=3 >/dev/null
${K} rollout status sts/order-matcher-cluster --timeout=600s >/dev/null
# Poll for the socket to come back, NOT for readiness — the gap between them is the whole point.
CONNECTED=""
for _ in $(seq 1 60); do
  BODY="$(ready_body)"; CONNECTED="$(printf '%s' "${BODY}" | jnum connected)"
  [[ "${CONNECTED}" == "True" || "${CONNECTED}" == "true" ]] && break
  sleep 2
done
CODE="$(ready_code)"
echo "  [${CODE}] ${BODY}"
[[ "${CONNECTED}" == "True" || "${CONNECTED}" == "true" ]] \
  || fail "the gateway session never came back, so this run cannot show the gap the fix closes"
[[ "${CODE}" == "503" ]] || fail "/ready returned ${CODE} the moment the SOCKET recovered, before any
  order had been committed. That is the old behaviour exactly — readiness is tracking the connection
  again, not the ability to commit."
echo "  connected is true and /ready is STILL 503 — the old socket-based probe reported healthy here"

step "4. one COMMITTED order clears it"
RESP="$(order clearing)"
echo "  order -> ${RESP}"
[[ "${RESP}" == *'"orderRef"'* ]] || fail "the clearing order did not commit: ${RESP}"
for _ in $(seq 1 30); do [[ "$(ready_code)" == "200" ]] && break; sleep 2; done
BODY="$(ready_body)"
[[ "$(ready_code)" == "200" ]] || fail "/ready did not recover after a committed order: ${BODY}"
[[ "$(printf '%s' "${BODY}" | jnum noAckStreak)" == "0" ]] || fail "streak did not reset: ${BODY}"
echo "  ${BODY}"
resume_replay

echo
echo "[PASS] readiness tracks the ability to COMMIT: ordinary traffic on a healthy cluster left it"
echo "       at 200, losing quorum drove it to 503 at streak >= ${LIMIT}, it stayed 503 while the"
echo "       session was back but nothing had committed — the window where the old probe reported"
echo "       healthy — and exactly one committed order cleared it."
