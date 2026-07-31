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

# Pin the cluster to the baseline image BEFORE anything runs.
#
# The rolling proofs each restore what they found, but "what they found" is only correct if the rig
# started correct. A previous suite run that was interrupted mid-roll leaves the members on a proof's
# own image, and the next run then restores THAT as if it were the baseline -- the leftover becomes
# sticky. Observed: a whole suite executed against traderx/cluster-node:yu15-stp, where
# yu13-clordid-suppression reported a DOUBLE BOOK and yu08 reported the scheduler dead. Both were
# reporting a different engine's behaviour truthfully; neither was a bug in what it tested.
#
# An engine build is the one variable no proof should inherit from the run before it.
BASELINE_IMAGE="${YU15_CLUSTER_IMAGE:-traderx/cluster-node:yu15}"
current_image() { ${K} get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null; }

# The only safe way to swap the engine build OR to recover a wedged rig: take the cluster down,
# wipe the members' PVCs, and bring it up on a FRESH EPOCH. A ROLLING image swap is exactly the
# wrong way -- a deterministic-core change rolled gradually leaves some members applying under the
# old engine and some under the new, and the state machines diverge PERMANENTLY. Not theoretical:
# pinning by bare `set image` produced [61 ...] [62 ...] [62 ...], member 0 a full order behind and
# STILL behind after 180s, plus a risk-extract cut the members could not agree on.
rebuild_fresh_epoch() { # rebuild_fresh_epoch [image] -- down, PVC wipe, optionally repin, up
  local image="${1:-}"
  ${K} scale sts order-matcher-cluster --replicas=0 >/dev/null
  ${K} wait --for=delete pod -l app=order-matcher-cluster --timeout=300s >/dev/null 2>&1
  ${K} delete pvc -l app=order-matcher-cluster --ignore-not-found >/dev/null 2>&1
  if [[ -n "${image}" ]]; then
    ${K} set image statefulset/order-matcher-cluster \
      "$(${K} get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].name}')=${image}" >/dev/null
    ${K} set image deployment/cluster-gateway \
      "$(${K} get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].name}')=${image}" >/dev/null
  fi
  ${K} scale sts order-matcher-cluster --replicas=3 >/dev/null
  ${K} rollout status statefulset/order-matcher-cluster --timeout=600s >/dev/null
  ${K} rollout restart deployment/cluster-gateway >/dev/null
  ${K} rollout status deployment/cluster-gateway --timeout=600s >/dev/null
}

NEED_FRESH=0
if [[ "$(current_image)" != "${BASELINE_IMAGE}" ]]; then
  echo "[baseline] cluster is on $(current_image); rebuilding on ${BASELINE_IMAGE} at a fresh epoch"
  rebuild_fresh_epoch "${BASELINE_IMAGE}"
  echo "[baseline] cluster now on ${BASELINE_IMAGE}, fresh epoch"
fi

# The GATEWAY image is pinned separately, because checking only the StatefulSet let a whole run
# fail four proofs: yu13-cancel-ingress rolls the gateway to its own build, its restore did not
# happen (the run before it died part-way), and the STS-only check above never noticed. Against
# that 9-day-old gateway clordid double-booked on resend (no clientOrderKey plumbing), both OTel
# proofs found no trace in Tempo (no gateway spans), and yu08's children were silently rejected
# (predates the instrumentOf alias) -- every one reporting a different build's behaviour
# truthfully. The gateway is stateless, so a mismatch here needs a repin and nothing else: no PVC
# wipe, no epoch reset, no projection clear.
gateway_image() { ${K} get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null; }
if [[ "$(gateway_image)" != "${BASELINE_IMAGE}" ]]; then
  echo "[baseline] gateway is on $(gateway_image); repinning to ${BASELINE_IMAGE}"
  ${K} set image deployment/cluster-gateway \
    "$(${K} get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].name}')=${BASELINE_IMAGE}" >/dev/null
  ${K} rollout status deployment/cluster-gateway --timeout=600s >/dev/null
fi

# Members that share an applied sequence but DISAGREE on the book have diverged -- permanently, on
# a deterministic core -- and every digest-agreement proof after this point would fail while
# reporting the divergence as its own bug. Different sequences are mere lag and converge on their
# own; identical sequence + different books is the documented discriminator. Seen live when run 1's
# stp restore rolled the members while the log tail replayed under mixed engine builds: m0/m1
# open=6 trades=136 vs m2 open=7 trades=134, all three at applied=7365. The only recovery is a
# wipe to a fresh epoch.
member_state() { # member_state <ordinal> -> "<applied> <bookHash>"
  ${K} exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics' 2>/dev/null \
    | awk '/^traderx_cluster_applied/ {a=$2} /^traderx_book_order_hash/ {h=$2} END {print a, h}'
}
if [[ "${NEED_FRESH}" == "0" ]]; then
  # Quiet the rig first: sampling three members sequentially under live algo traffic would never
  # catch them at one sequence, and the loop would misreport a busy healthy rig as unverifiable.
  ${K} scale deploy/execution-algo-engine --replicas=0 >/dev/null 2>&1
  tries=0
  while :; do
    read -r A0 H0 <<<"$(member_state 0)"
    read -r A1 H1 <<<"$(member_state 1)"
    read -r A2 H2 <<<"$(member_state 2)"
    if [[ -n "${A0}" && "${A0}" == "${A1}" && "${A1}" == "${A2}" ]]; then
      if [[ "${H0}" == "${H1}" && "${H1}" == "${H2}" ]]; then
        break
      fi
      echo "[epoch] members share applied=${A0} but disagree on the book (${H0} ${H1} ${H2}): diverged; rebuilding"
      rebuild_fresh_epoch
      NEED_FRESH=1
      break
    fi
    tries=$((tries + 1))
    if [[ ${tries} -ge 30 ]]; then
      echo "[fail] members never reached one applied sequence (applied: ${A0:-?} ${A1:-?} ${A2:-?}) -- a lagging or wedged member; refusing to run blind"
      exit 1
    fi
    sleep 2
  done
fi

# A fresh epoch needs a fresh projection -- DETECTED, not assumed. This runner used to do the
# FRESH_EPOCH clear inline in the pin block, silenced with >/dev/null, and it silently did
# nothing: seed-proof-fixtures.sh refused to act before it could reach the matcher, and at that
# point in the run no port-forward existed yet. A whole suite pass then ran against the DEAD
# epoch's rows -- trade ids are <tradeSeq>-<side>, the wiped engine's counter restarted at 1, so
# every id it minted already existed and trade-processor dropped this epoch's trades as
# "Duplicate trade delivery ignored". Three proofs failed on trades the engine definitely booked
# (yu13-clordid-suppression 0/2 rows, yu15-option-persistence 0/2 rows, yu13-stp-and-replace's
# preflight tradeCounter 150 < SQL max 546).
#
# So check for the wedge itself, independent of which path created it: if member 0's trade counter
# is BEHIND the highest trade id in SQL, those rows cannot be this epoch's -- every new trade is
# doomed to dedup against them. (SQL behind the engine is mere bridge lag and is fine.) The heal is
# a full fresh-epoch rebuild, not a bare SQL clear: on a wedged rig the projection already MISSED
# trades this epoch booked, so the engine holds positions no surviving row explains -- only
# restarting both sides makes "SQL is a projection of the log" true again.
if [[ "${NEED_FRESH}" == "0" ]]; then
  ENGINE_TRADES="$(${K} exec order-matcher-cluster-0 -- \
    sh -c 'wget -qO- http://localhost:8080/metrics' 2>/dev/null | awk '/^traderx_cluster_trades/ {print $2}')"
  SQL_MAX_TRADE="$(${K} exec deploy/eod-price-db -c mariadb -- \
    mariadb -utraderx -ptraderx traderx -sN -e \
    "SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(id,'-',1) AS UNSIGNED)),0) FROM trades;" 2>/dev/null)"
  if [[ ! "${ENGINE_TRADES}" =~ ^[0-9]+$ || ! "${SQL_MAX_TRADE}" =~ ^[0-9]+$ ]]; then
    echo "[fail] could not read engine tradeCounter ('${ENGINE_TRADES}') or SQL max trade id ('${SQL_MAX_TRADE}') -- refusing to run blind"
    exit 1
  fi
  if [[ "${ENGINE_TRADES}" -lt "${SQL_MAX_TRADE}" ]]; then
    echo "[epoch] engine tradeCounter ${ENGINE_TRADES} < highest trade id in SQL ${SQL_MAX_TRADE}: projection is from a dead epoch; rebuilding"
    rebuild_fresh_epoch
    NEED_FRESH=1
  fi
fi

if [[ "${NEED_FRESH}" == "1" ]]; then
  start_forwards || { echo "[fail] could not establish forwards for the fresh-epoch clear"; exit 1; }
  FRESH_EPOCH=1 bash "${ROOT}/scripts/yu15/seed-proof-fixtures.sh" \
    || { echo "[fail] fresh-epoch clear+seed failed -- the suite would assert against a projection this epoch cannot write"; exit 1; }
  echo "[epoch] projection cleared and reseeded for this epoch"
fi

mkdir -p /tmp/proofrun
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
