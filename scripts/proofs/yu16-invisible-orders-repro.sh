#!/usr/bin/env bash
# yu16-invisible-orders-repro.sh — reproduce §1 of the wedge issue ON DEMAND: orders the client is
# told failed, which the cluster sequenced and booked anyway.
#
# THIS IS A REPRO, NOT A PROOF, and it is deliberately NOT in scripts/yu15/run-proofs.sh. It
# demonstrates that a defect is present. A suite entry would either report a standing FAIL (which
# trains people to skim failures) or assert the bug's continued existence (which inverts the day
# someone fixes it). Run it by hand when you are working on §1.
#
# WHY IT EXISTS. issues/HANDOFF-issue-gateway-wedges-after-leader-kill.md is organised around a
# leader-kill WEDGE that reproduces about one run in four, and that framing has already sent one
# investigation to consensus for an hour. For §1's divergence you do not need the wedge at all:
# quorum loss produces booked-but-denied orders deterministically, in about ninety seconds. First
# measured 2026-08-13 — 160 clients answered 504, 159 orders resting, all three members agreeing.
#
# WHAT IT ASSERTS, and why this shape. Booking-grained quantities only: the OPEN-ORDER COUNT the
# members agree on, never `next_order_ref`. §4 of the issue records the existing failover proof
# reporting "55 DUPLICATED" off a ref delta — refs are consumed by orders that never rest, so a ref
# counter measures allocations, not bookings, and the two differ by exactly the traffic this defect
# generates. Anything built on these numbers must not repeat that.
#
# WHEN §1 IS FIXED this script reports NO DIVERGENCE and says so plainly. That is the good outcome
# and it is not a failure — read the verdict, not the exit code.
#
# DESTRUCTIVE: scales the members to 1 and back, and leaves the booked orders resting. No PVC wipe
# and no epoch change, but it moves the open-order count, so run it away from counter-exact work.
#
# Usage: ./yu16-invisible-orders-repro.sh [-v]
set -uo pipefail

VERBOSE=0
case "${1:-}" in -v|--verbose) VERBOSE=1; shift ;; esac
vlog() { [ "${VERBOSE}" = 1 ] && printf '%s\n' "$@" >&2 || true; }

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
ACCT="${ACCT:-42422}"
TICKER="${TICKER:-INV$(date +%H%M%S)}"
PRICE="${PRICE:-100.00}"
DRIVE="${DRIVE:-40}"
PROBE_PORT="${PROBE_PORT:-18531}"
ORDER_PORT="${ORDER_PORT:-18532}"

die() { echo "[ERROR] $*" >&2; exit 1; }   # the script could not measure — distinct from a verdict
step() { echo; echo "=== $* ==="; }

GW=""
PF_PIDS=()
OUTDIR="$(mktemp -d)"
kill_pf() { for p in "${PF_PIDS[@]:-}"; do [[ -n "${p}" ]] && kill "${p}" 2>/dev/null || true; done; PF_PIDS=(); }
cleanup() {
  kill_pf
  rm -rf "${OUTDIR}"
  # Both of these are mutations this script made and must not outlive it: a half-scaled StatefulSet
  # breaks every later run, and a raised liveness threshold silently disarms the probe the rest of
  # the rig depends on.
  [[ "$(${K} get sts order-matcher-cluster -o jsonpath='{.spec.replicas}' 2>/dev/null)" == "3" ]] \
    || ${K} scale sts order-matcher-cluster --replicas=3 >/dev/null 2>&1
  ${K} set env deploy/cluster-gateway LIVE_NO_ACK_STREAK- >/dev/null 2>&1
}
trap cleanup EXIT

forward() {
  kill_pf
  ${K} port-forward "pod/${GW}" "${PROBE_PORT}:18111" >/dev/null 2>&1 & PF_PIDS+=($!)
  ${K} port-forward "pod/${GW}" "${ORDER_PORT}:18110" >/dev/null 2>&1 & PF_PIDS+=($!)
  # -a, not bare `disown`: there are TWO forwards and bare disown only detaches the
  # most recent job, so the other still reports "Terminated: 15" when cleanup reaps it.
  disown -a 2>/dev/null || true
  local i
  for i in $(seq 1 30); do
    [[ "$(curl -s -o /dev/null -w '%{http_code}' -m5 "http://localhost:${PROBE_PORT}/live" 2>/dev/null)" != "000" ]] && return 0
    sleep 1
  done
  return 1
}

# Booking-grained witness, read from a MEMBER — the gateway cannot answer this, it holds no state.
open_orders() { ${K} exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics' 2>/dev/null \
  | awk '/^traderx_book_open_orders/ {print $2}'; }
book_hash() { ${K} exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics' 2>/dev/null \
  | awk '/^traderx_book_order_hash/ {print $2}'; }

agreed_open() { # all three members' open-order count, or empty if they disagree
  local a b c
  a="$(open_orders 0)"; b="$(open_orders 1)"; c="$(open_orders 2)"
  [[ -n "${a}" && "${a}" == "${b}" && "${b}" == "${c}" ]] || { echo ""; return; }
  echo "${a}"
}

# ---------------------------------------------------------------------------------------------
step "0. preflight"
# The algo engine books orders continuously, which would move the open-order count underneath the
# only measurement this script makes. Refuse rather than report a number nobody can attribute.
ALGO="$(${K} get deploy execution-algo-engine -o jsonpath='{.spec.replicas}' 2>/dev/null || echo 0)"
[[ "${ALGO:-0}" == "0" ]] || die "execution-algo-engine is at ${ALGO} replicas; its traffic would move
  the open-order count this script measures. Scale it to 0 first."

GW="$(${K} get pods -l app=cluster-gateway -o jsonpath='{.items[0].metadata.name}')"
[[ -n "${GW}" ]] || die "no cluster-gateway pod"

# Disable the liveness restart for the duration. Not cosmetic: DRIVE orders build a no-ack streak
# well past LIVE_NO_ACK_STREAK, the kubelet would restart the gateway mid-run, and a restart clears
# the owner queue — which changes what gets booked and makes the delta unattributable.
${K} set env deploy/cluster-gateway LIVE_NO_ACK_STREAK=100000 >/dev/null
${K} rollout status deploy/cluster-gateway --timeout=300s >/dev/null
GW="$(${K} get pods -l app=cluster-gateway -o jsonpath='{.items[0].metadata.name}')"
forward || die "gateway probe port never answered — is this a build with the split probe server?"

OPEN_BEFORE="$(agreed_open)"
[[ -n "${OPEN_BEFORE}" ]] || die "the three members do not agree on open-order count before the run
  ($(open_orders 0) / $(open_orders 1) / $(open_orders 2)) — measuring a delta across a diverged
  cluster would be meaningless. A fresh epoch is the cure."
echo "  gateway=${GW}  open orders (all three agree)=${OPEN_BEFORE}"

SEED="$(curl -s -m 20 -X POST "http://localhost:${ORDER_PORT}/seed" -H 'Content-Type: application/json' \
  -d "{\"accountId\":${ACCT},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}" 2>&1)"
[[ "${SEED}" == *'"seeded":true'* ]] || die "seed failed (${SEED:-no answer}); {\"seeded\":false} is the
  engine's symbol table exhausted, which needs a fresh epoch"

step "1. remove quorum — the gateway can no longer commit anything"
${K} scale sts order-matcher-cluster --replicas=1 >/dev/null
# Wait for the CONDITION, not for a guessed interval. A bare `sleep 25` assumes members 1 and 2 are
# gone by then, which is an assumption about pod termination speed on whichever rig you happen to be
# on — and "holds in practice on kind" is exactly what three separate bugs in the sibling liveness
# proof looked like right up until GKE ran them.
#
# This one is load-bearing in a nastier way than a flaky timeout: if quorum is NOT actually lost the
# drive below simply succeeds, every client is told the truth, and the script reports NO DIVERGENCE
# — which reads as "§1 has been fixed". A vacuous pass that delivers good news is the worst kind, so
# this refuses rather than proceeds.
DOWN=0
for _ in $(seq 1 60); do
  READY="$(${K} get sts order-matcher-cluster -o jsonpath='{.status.readyReplicas}' 2>/dev/null)"
  [[ "${READY:-0}" -le 1 ]] && { DOWN=1; break; }
  sleep 2
done
[[ ${DOWN} -eq 1 ]] || die "members never scaled down to 1 (readyReplicas=${READY:-unknown}) after 120s.
  Quorum was never lost, so the drive below would just succeed and this run would report NO
  DIVERGENCE — which reads as '§1 is fixed'. Refusing to produce that."
# Members are down; give the gateway's cluster session a moment to notice before driving.
sleep 10

step "2. ${DRIVE} orders, each answered by the gateway, each outcome recorded CLIENT-SIDE"
# Client-side truth, not the gateway's own streak counter. The claim being reproduced is about what
# the CLIENT was told, so the client's own answers are the only honest witness for that half.
pids=()
for i in $(seq 1 "${DRIVE}"); do
  ( curl -s -m 25 "http://localhost:${ORDER_PORT}/orders" -X POST -H 'Content-Type: application/json' \
      -d "{\"accountId\":${ACCT},\"ticker\":\"${TICKER}\",\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":${PRICE},\"clientOrderId\":\"inv-$$-${i}\"}" \
      > "${OUTDIR}/${i}" 2>/dev/null ) &
  pids+=($!)
done
for p in "${pids[@]}"; do wait "${p}" 2>/dev/null || true; done

ACKED=0; DENIED=0
for i in $(seq 1 "${DRIVE}"); do
  if grep -q '"orderRef"' "${OUTDIR}/${i}" 2>/dev/null; then ACKED=$((ACKED + 1)); else DENIED=$((DENIED + 1)); fi
done
echo "  clients told SUCCESS: ${ACKED}    clients told FAILURE: ${DENIED}"
vlog "  sample denial: $(head -c 120 "${OUTDIR}/1" 2>/dev/null)"

step "3. restore quorum and let the cluster settle"
${K} scale sts order-matcher-cluster --replicas=3 >/dev/null
${K} rollout status sts/order-matcher-cluster --timeout=600s >/dev/null
# Poll to a STABLE count rather than sleeping a guessed interval: the abandoned offers land after
# quorum returns, and a fixed sleep would either race them or pad every run.
LAST=""; STABLE=0
for _ in $(seq 1 40); do
  NOW="$(agreed_open)"
  if [[ -n "${NOW}" && "${NOW}" == "${LAST}" ]]; then
    STABLE=$((STABLE + 1)); [[ ${STABLE} -ge 2 ]] && break
  else
    STABLE=0
  fi
  LAST="${NOW}"; sleep 5
done
OPEN_AFTER="$(agreed_open)"
[[ -n "${OPEN_AFTER}" ]] || die "the members do not agree on open-order count after the run
  ($(open_orders 0) / $(open_orders 1) / $(open_orders 2)) — that is a DIVERGENCE, which is a
  different and worse problem than the one this script reproduces. Stop and investigate."

BOOKED=$((OPEN_AFTER - OPEN_BEFORE))
INVISIBLE=$((BOOKED - ACKED))

step "RESULT"
printf '  open orders   %s -> %s   (booked %s)\n' "${OPEN_BEFORE}" "${OPEN_AFTER}" "${BOOKED}"
printf '  clients told  %s succeeded, %s failed\n' "${ACKED}" "${DENIED}"
printf '  book hash agreed by all three members: %s\n' "$(book_hash 0)"
echo
if [[ ${INVISIBLE} -gt 0 ]]; then
  echo "  DIVERGENCE REPRODUCED: ${INVISIBLE} orders are resting in the book that NO client was told"
  echo "  it owns. Every one of those ${DENIED} clients was answered a failure. The client's view and"
  echo "  the book's view have diverged silently and permanently, with the client under-counting its"
  echo "  own exposure — §1 of issues/HANDOFF-issue-gateway-wedges-after-leader-kill.md."
else
  echo "  NO DIVERGENCE: the book gained ${BOOKED} orders and clients were told about ${ACKED}."
  echo "  If §1 has been fixed, this is the outcome to expect and this script has outlived its"
  echo "  purpose. Before concluding that, check DENIED above is non-zero — if nothing was denied,"
  echo "  the run never induced the condition (quorum came back too fast, or the drive was too"
  echo "  small) and it has demonstrated nothing either way."
fi
