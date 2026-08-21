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
# EXPORTED, because the proofs read CTX/KCTX themselves and each decides independently which cluster
# to talk to. Most default to this same kind rig, so they were right by coincidence rather than by
# instruction. yu13-otel-trace-join deliberately has NO default (it can legitimately target the yu12
# rig, state-014 or GKE) and refuses rather than guess — measured 2026-08-18 twice, on YU17 (host
# reboot left the ambient context on GKE) and on YU16's un-exported copy (failed an otherwise green
# suite run). Exporting makes the strict form work under the suite while keeping it strict for
# manual invocation. (Carried from YU17's 1947fd16 hardening.)
export CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"

# Ordered deliberately. The cluster-rolling proofs go LAST: they are the slowest, and until they
# run everything else has a stable rig. yu08 is separated from the counter-exact proofs because the
# algo engine's traffic moves next_order_ref underneath them (see seed-proof-fixtures.sh).
PROOFS=(
  yu03-risk-proof
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
  yu13-otel-trace-join
  yu13-otel-reject-trace-log-join
  yu10-fix-session
  yu08-algo-slicing          # needs the algo engine up; scaled in below
  # The gateway probe pair. Named for the state that authored them (YU16), carried here because
  # this branch runs the same gateway and carries the same defect, and because this is the only
  # branch with a proof harness to run them from. Both scale the members to 1 and back (no PVC
  # wipe, no epoch change); the liveness one additionally gets the gateway container killed by the
  # kubelet, so it must follow the readiness one — whose step 3 asserts /ready stays 503 across a
  # restored quorum, which a restart would clear.
  yu16-ready-tracks-commit
  yu16-liveness-restarts-wedge
  # Option B (keyed ack correlation, carried from YU17): kills a leader under a staggered stream
  # and checks EVERY answered client against the engine's own idempotency table, then that depth
  # self-drains with no reconnect. Disruptive (leader kill, statefulset recovers it on the same
  # image) but no PVC wipe and no epoch — hence this block. Must stay BEFORE yu13-cancel-ingress
  # (whose gateway roll would hide the "no reconnect" reading) and BEFORE yu13-stp-and-replace's
  # yu15-pre member roll (whose build predates the 32-byte ack entirely). A kill that strands
  # nothing exits 2 (uninformative, not confirming); on a pre-B build this proof MUST fail.
  yu17-keyed-ack-correlation
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
  # The yu04 pair reads reference-data's control snapshot directly and SKIPs without it. Both
  # scripts document `kubectl port-forward svc/reference-data 18085:18085` as a manual
  # prerequisite, so the suite's result depended on whether a human happened to be holding one in
  # another terminal -- and both proofs reported "capability absent" when nobody was, which reads
  # as a statement about the tier rather than about a missing tunnel. The runner owns its forwards;
  # this is one of them.
  "svc/reference-data 18085:18085"
  "svc/tempo 3200:3200"
  "svc/loki 3100:3100"
  "svc/grafana 3000:3000"
)

# Match kubectl+port-forward in EITHER order. `port-forward.*${NS}` matched nothing here and never
# had: every forward this runner starts is `kubectl --context kind-traderx-... -n traderx
# port-forward svc/...`, so "traderx" appears BEFORE "port-forward" and the pattern could not hit.
# (It did match the manual form the yu04 proofs document, where `-n traderx` comes after -- so it
# killed the forwards nobody asked it to and none of its own.) The kill half of "forwards are
# re-established before every proof" was therefore vacuous: nothing was ever torn down, the first
# forward to bind a port kept it for the whole suite, and a stale tunnel to a dead gateway pod
# would have survived every re-establish while the replacements silently lost the bind.
kill_forwards() { pkill -f "kubectl.*port-forward" 2>/dev/null; sleep 1; }
trap kill_forwards EXIT

start_forwards() {
  kill_forwards
  local pf
  for pf in "${FORWARDS[@]}"; do
    # shellcheck disable=SC2086
    ${K} port-forward ${pf} >/dev/null 2>&1 &
    # Forget the job, or bash reports each one when kill_forwards reaps it -- six
    # "Terminated: 15" lines per proof, ~114 across a full suite, all of them noise in the log a
    # human reads to decide what passed. Invisible until kill_forwards actually started killing
    # things; the old pattern never matched, so nothing ever died to be reported.
    disown 2>/dev/null || true
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
    [[ "$(curl -s -o /dev/null -w '%{http_code}' -m5 http://localhost:18085/stocks/control-snapshot 2>/dev/null)" == "200" ]] || ready=0
    # Only when the observability stack is expected to be up. stp deliberately scales it to zero
    # for a quiet box, and waiting on a service we just switched off is an unsatisfiable condition
    # -- it aborted the whole stp wrap with "forwards never all became reachable".
    if [[ "${OBS_EXPECTED:-1}" == "1" ]]; then
      [[ "$(curl -s -o /dev/null -w '%{http_code}' -m5 http://localhost:3200/ready 2>/dev/null)" == "200" ]] || ready=0
      # Loki and Grafana answer non-2xx on / by design; a connection at all is enough.
      [[ "$(curl -s -o /dev/null -w '%{http_code}' -m5 http://localhost:3100/ 2>/dev/null)" != "000" ]] || ready=0
    fi
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
# Must match IMAGE_PRE in scripts/proofs/yu13-stp-and-replace.sh.
STP_IMAGE_PRE="${IMAGE_PRE:-traderx/cluster-node:yu15-pre}"
current_image() { ${K} get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null; }

# The only safe way to swap the engine build OR to recover a wedged rig: take the cluster down,
# wipe the members' PVCs, and bring it up on a FRESH EPOCH. A ROLLING image swap is exactly the
# wrong way -- a deterministic-core change rolled gradually leaves some members applying under the
# old engine and some under the new, and the state machines diverge PERMANENTLY. Not theoretical:
# pinning by bare `set image` produced [61 ...] [62 ...] [62 ...], member 0 a full order behind and
# STILL behind after 180s, plus a risk-extract cut the members could not agree on.
# THE MEMBERS ONLY. The gateway is deliberately left on whatever build it is already running.
#
# It used to be repinned here alongside the StatefulSet, and that is what made the stp prep block
# below take the GATEWAY historical too -- which nothing wanted. The gateway is stateless and holds
# no epoch, so it has no reason to share the members' build; the baseline block above already pins
# it to ${BASELINE_IMAGE} and this function has no business undoing that. It cost two proofs:
# yu15-pre/yu15-stp predate the gateway's probe server (18111, /live) that the manifest's three
# probes point at, so the kubelet failed the startup probe and crash-looped a gateway whose only
# defect was being older than the manifest. See
# issues/resolved/HANDOFF-issue-historical-gateway-images-fail-the-probe-port.md.
# THE IMAGE MUST BE ON THE NODES BEFORE ANYTHING IS DESTROYED. rebuild_fresh_epoch wipes the PVCs
# first and discovers an unreachable image afterwards, at which point the epoch it would have fallen
# back to no longer exists. `kind load` is start-cluster-kind.sh's job and this script never did it,
# so naming a CLUSTER_IMAGE that exists only in the local Docker daemon used to mean
# ImagePullBackOff several minutes later, on a rig with nothing left to run — measured on YU16's
# copy 2026-08-18: it wiped the PVCs, never loaded the named image, and announced a fresh epoch
# over three ImagePullBackOff members. Idempotent and cheap when already there.
# (Carried from YU17's 1947fd16 hardening.)
ensure_image_on_nodes() { # ensure_image_on_nodes <image>
  local image="${1:-}" cluster
  [[ -n "${image}" ]] || return 0
  case "${CTX}" in kind-*) cluster="${CTX#kind-}" ;; *) return 0 ;; esac   # kind rigs only
  docker image inspect "${image}" >/dev/null 2>&1 \
    || fail_hard "${image} is not in the local Docker daemon — build it first (scripts/yu15/build-cluster-image.sh)"
  kind load docker-image "${image}" --name "${cluster}" >/dev/null 2>&1 \
    || fail_hard "could not load ${image} onto kind cluster ${cluster}"
}

fail_hard() { echo "[fail] $*" >&2; exit 1; }

# GATE ON THE FACT, NOT ON THE COMMAND RETURNING. Both rollout waits used to redirect stdout only,
# so their failure text reached the terminal while their EXIT STATUS was discarded — the function
# carried on and the caller printed "fresh epoch" regardless. The dangerous variant is a PARTIAL
# rollout — two members up, one wedged, rollout status times out, the message still says fresh
# epoch, and every proof afterwards describes a two-member cluster truthfully.
# prove-cluster-engine-change §1 already prescribes the end-state assertion below; it was simply
# never wired in here. (Carried from YU17's 1947fd16 hardening.)
rebuild_fresh_epoch() { # rebuild_fresh_epoch [image] -- down, PVC wipe, optionally repin members, up
  local image="${1:-}"
  ensure_image_on_nodes "${image}"
  ${K} scale sts order-matcher-cluster --replicas=0 >/dev/null
  ${K} wait --for=delete pod -l app=order-matcher-cluster --timeout=300s >/dev/null 2>&1
  ${K} delete pvc -l app=order-matcher-cluster --ignore-not-found >/dev/null 2>&1
  if [[ -n "${image}" ]]; then
    ${K} set image statefulset/order-matcher-cluster \
      "$(${K} get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].name}')=${image}" >/dev/null
  fi
  ${K} scale sts order-matcher-cluster --replicas=3 >/dev/null
  ${K} rollout status statefulset/order-matcher-cluster --timeout=600s >/dev/null \
    || fail_hard "the members' rollout did not complete — NOT a fresh epoch, and the PVCs are already wiped"
  ${K} rollout restart deployment/cluster-gateway >/dev/null
  ${K} rollout status deployment/cluster-gateway --timeout=600s >/dev/null \
    || fail_hard "the gateway's rollout did not complete after the epoch mint"
  assert_members_up "${image}"
}

# Every member on the target image AND ready — the check `rollout status` returning is not the same
# as. Empty image argument means "whatever is pinned", so only readiness is asserted.
# NOTE the `if` form throughout rather than `grep -q ... && fail_hard`. That shorter spelling returns
# 1 in the HEALTHY case (grep finds no bad line), which is harmless under this script's `set -uo
# pipefail` and becomes an abort-when-everything-is-fine the day someone adds `-e`. Not worth
# leaving as a tripwire in the function whose whole job is to be believed.
assert_members_up() { # assert_members_up [image]
  local image="${1:-}" states count
  states="$(${K} get pods -l app=order-matcher-cluster \
    -o jsonpath='{range .items[*]}{.spec.containers[0].image}{" "}{.status.containerStatuses[0].ready}{"\n"}{end}' 2>/dev/null)"
  count="$(printf '%s\n' "${states}" | grep -c . || true)"
  if [[ "${count}" != "3" ]]; then
    fail_hard "expected 3 members after the epoch mint, saw ${count}"
  fi
  if printf '%s\n' "${states}" | grep -q -v ' true$'; then
    fail_hard "a member is not ready after the epoch mint:
${states}"
  fi
  if [[ -n "${image}" ]] && printf '%s\n' "${states}" | grep -q -v "^${image} "; then
    fail_hard "a member is not on ${image} after the epoch mint:
${states}"
  fi
  return 0
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

# THE EXTRACT PRODUCER RUNS THE SAME cluster-node IMAGE AND WAS PINNED BY NOTHING. Same lesson as
# the gateway above, one Deployment further along: check every Deployment that runs this image, not
# the StatefulSet alone. It is a stateless batch producer, so a repin costs nothing -- no PVC, no
# epoch, no projection clear.
#
# Measured 2026-08-14 on the YU16 rig: the suite ran with the members and the gateway on
# traderx/cluster-node:yu16 while the producer was still on a YU17 build, whose renderer requires a
# `contracts=` field that a YU16 cut does not carry. The EOD batch aborted with "cut header missing
# contracts=", and two proofs failed reporting "no RISK-EXTRACT-READY" -- true, and silent about
# the cause. A producer left behind by a DESCENDANT branch is NEWER than the image, so no
# staleness test can see it; only a tag comparison can.
producer_image() { ${K} get deploy risk-extract -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null; }
if [[ -n "$(producer_image)" && "$(producer_image)" != "${BASELINE_IMAGE}" ]]; then
  echo "[baseline] risk-extract is on $(producer_image); repinning to ${BASELINE_IMAGE}"
  ${K} set image deployment/risk-extract \
    "$(${K} get deploy risk-extract -o jsonpath='{.spec.template.spec.containers[0].name}')=${BASELINE_IMAGE}" >/dev/null
  ${K} rollout status deployment/risk-extract --timeout=600s >/dev/null
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

  # Symbol-table exhaustion is replicated state, so it survives everything except an epoch wipe --
  # the image matches, the members agree, and yet every proof that mints a fresh ticker fails at
  # its seed step ("seed failed for <acct>", {"seeded":false}). It happens when something registers
  # a large universe through consensus: the control-feed subscriber replaying reference-data's
  # 510-security stream into a MAX_SECURITIES=64 table did exactly this. Detect it the way a proof
  # would hit it: try to register a throwaway ticker, and rebuild if the engine refuses.
  if [[ "${NEED_FRESH}" != "1" ]]; then
    # The probe needs a live forward: without one curl answers nothing, and "no answer" read as
    # "exhausted" triggered a needless multi-minute rebuild on every fresh runner invocation --
    # safe in direction, but it buries the real signal. Unreachable and exhausted are different
    # verdicts; establish the forward first so a refusal can only mean the table.
    start_forwards || { echo "[fail] could not establish forwards for the symbol-table probe"; exit 1; }
    probe="$(curl -s -m20 -X POST http://localhost:18110/seed -H 'Content-Type: application/json' -d '{"accountId":42422,"tickers":"ZZPROBE9","price":100}' 2>/dev/null)"
    if [[ "${probe}" == *'"seeded":false'* ]]; then
      echo "[epoch] engine refuses to register a fresh ticker: symbol table exhausted; rebuilding"
      rebuild_fresh_epoch
      NEED_FRESH=1
    elif [[ "${probe}" != *'"seeded":true'* ]]; then
      echo "[fail] symbol-table probe got no usable answer (${probe:-nothing}) even with a verified forward"
      exit 1
    fi
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
  # A NAMED PROOF THAT DOES NOT EXIST IS A FAILURE, NOT A WARNING.
  #
  # This was `continue` with a [warn]: the suite printed one line, never touched `fail`, and
  # reported success having silently run a smaller suite than it claimed. That is the vacuous pass
  # in its purest form -- the failure mode and the success mode are indistinguishable from the
  # output. It was live on `main` for 17 days, where PROOFS named `yu03-risk-demo` while the script
  # is `yu03-risk-proof`: the YU03 risk proof was skipped on every run and every run said green.
  # The typo is fixed; this line is why the typo cost anything.
  #
  # Deliberately NOT counted as a skip. A skip means "this proof decided it does not apply here" --
  # a verdict the proof reached. This is the suite failing to find something it was told to run,
  # which is a fact about the suite. Folding the second into the first is how a shrinking suite
  # keeps a clean record.
  if [[ ! -f "${script}" ]]; then
    echo "[fail] no such proof: ${p} (expected ${script})"
    results+=("FAIL ${p} - script missing; the suite named a proof that does not exist")
    fail=$((fail + 1))
    continue
  fi

  # yu13-stp-and-replace rolls the members onto HISTORICAL engine builds (yu15-pre / yu15-stp)
  # with PVCs intact -- that epoch continuity is the point of the proof. Those builds hold
  # MAX_SECURITIES=64, and with the control feed on, the current epoch's log carries the full
  # 510-security universe as sequenced registrations, which a 64-capacity build cannot replay:
  # rolling onto it wedges or diverges the members. So the proof gets an epoch the old builds can
  # carry -- subscriber off, fresh epoch, so the log holds only the proof's own fixtures -- and the
  # feed is restored (env back on + gateway restart replays the stream) afterwards.
  if [[ "${p}" == yu13-stp-and-replace ]]; then
    # Mint the epoch ON the pre-change image, not on the current one.
    #
    # This proof rolls the members onto historical builds (yu15-pre, then yu15-stp) with PVCs
    # intact -- that epoch continuity is the point. So whatever log exists when it starts gets
    # replayed by a 64-capacity engine, and minting it here means the log is authored by the same
    # engine that will replay it. The proof's own step-1 roll_to(pre) also becomes a no-op, which
    # is worth having on its own.
    #
    # WHAT THIS DOES NOT FIX, corrected 2026-08-02. This block used to name the authoring build as
    # the cause of [0 0] [0 0] [1 <hash>] -- two members holding one book and the third another.
    # It is not: that signature reproduced with the epoch minted right here on yu15-pre, three
    # times. The real cause is a missing SNAPSHOT BARRIER before the roll, and the fix lives in
    # roll_to() in scripts/proofs/yu13-stp-and-replace.sh -- read the comment there before
    # attributing that shape to anything on this line.
    #
    # Seeding after this registers ~20 tickers, comfortably inside the historical 64 limit.
    # Quiet the box first. This proof rolls all three members TWICE and needs consensus to re-form
    # after each roll; on a contended kind node it instead logs "leader heartbeat timeout" and
    # "quorum position went backwards" and the members never agree. It passed standalone before the
    # observability stack and the control-feed services joined the rig — the box got busier, not
    # the proof wronger. Nothing here is used by stp, and the OTel proofs that need it have already
    # run by this point in the order.
    echo "[stp-prep] scaling the observability stack to 0 (stp needs a quiet box for consensus)"
    for d in grafana loki tempo prometheus otel-collector; do
      ${K} scale deploy/"${d}" --replicas=0 >/dev/null 2>&1 || true
    done
    STP_RESTORE_OBS=1
    OBS_EXPECTED=0

    echo "[stp-prep] control feed off + fresh epoch minted ON ${STP_IMAGE_PRE}"
    ${K} set env deploy/cluster-gateway CONTROL_FEED_SUBSCRIBER=0 >/dev/null
    # WAIT FOR THE SUBSCRIBER TO ACTUALLY BE GONE BEFORE MINTING THE EPOCH. `set env` starts a
    # rollout; it does not finish one. Without this wait the OLD gateway pod -- still
    # CONTROL_FEED_SUBSCRIBER=1 -- is alive while rebuild_fresh_epoch below wipes the PVCs and
    # brings the members up, and it replays the YU04 control feed's 510-security universe straight
    # into the brand-new epoch.
    #
    # That is fatal on the historical builds this prep exists to serve: MAX_SECURITIES is 64 there
    # against 1024 today, so the table is exhausted in the first 13% of the replay and every symbol
    # registration after that is refused with id = -1 -- which surfaces as
    # yu13-stp-and-replace failing its seed with a FAST `422 {"seeded":false}`.
    #
    # Measured 2026-08-14. At failure the epoch carried applied=655 on all three members, against
    # ~130 for seed-proof-fixtures alone; the ~510 excess is the universe. Intermittent precisely
    # because it is a race on whether the old pod is still up when the members return.
    ${K} rollout status deploy/cluster-gateway --timeout=300s >/dev/null 2>&1
    rebuild_fresh_epoch "${STP_IMAGE_PRE}"
    # A fresh epoch needs a fresh projection — the engine's counters restart below the trade ids
    # already in SQL, and stp's own preflight (correctly) refuses to run into that. The main heal
    # path clears after its rebuilds; this wrap forgot to, and stp failed in-suite on exactly the
    # guard that exists to catch it.
    start_forwards || { echo "[fail] no forwards for the stp fresh-epoch clear"; break; }
    FRESH_EPOCH=1 bash "${ROOT}/scripts/yu15/seed-proof-fixtures.sh" >/dev/null 2>&1
    STP_RESTORE_FEED=1
  fi

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

  if [[ "${STP_RESTORE_FEED:-0}" == "1" && "${p}" == yu13-stp-and-replace ]]; then
    # Hand the rig back the way the rest of the suite expects it: the proof's own restore trap
    # returns the image it FOUND, which after the prep above is the historical one. Rebuild the
    # epoch on the baseline build before turning the feed back on, or the next 510-security replay
    # lands on a 64-capacity engine.
    echo "[stp-prep] restoring ${BASELINE_IMAGE} at a fresh epoch, then the control feed"
    rebuild_fresh_epoch "${BASELINE_IMAGE}"
    ${K} set env deploy/cluster-gateway CONTROL_FEED_SUBSCRIBER=1 >/dev/null
    ${K} rollout restart deploy/cluster-gateway >/dev/null
    ${K} rollout status deploy/cluster-gateway --timeout=300s >/dev/null 2>&1
    start_forwards && FRESH_EPOCH=1 bash "${ROOT}/scripts/yu15/seed-proof-fixtures.sh" >/dev/null 2>&1
    if [[ "${STP_RESTORE_OBS:-0}" == "1" ]]; then
      echo "[stp-prep] restoring the observability stack"
      for d in grafana loki tempo prometheus otel-collector; do
        ${K} scale deploy/"${d}" --replicas=1 >/dev/null 2>&1 || true
      done
      STP_RESTORE_OBS=0
      OBS_EXPECTED=1
    fi
    STP_RESTORE_FEED=0
  fi
done

echo
echo "==== ${pass} passed, ${skip} skipped, ${fail} failed ===="
# "${results[@]}" on an EMPTY array trips set -u ("unbound variable") and turned a clean
# no-proofs-ran outcome into a shell error after the summary had already printed.
if [[ ${#results[@]} -gt 0 ]]; then
  printf '%s\n' "${results[@]}" | grep -v '^PASS' || true
fi
[[ ${fail} -eq 0 ]]
