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
# shellcheck source=lib-state-image.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib-state-image.sh"
# shellcheck source=lib-replay-epoch.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib-replay-epoch.sh"
# EXPORTED, because 34 proofs read CTX/KCTX themselves and each decides independently which cluster
# to talk to. Most default to this same kind rig, so they were right by coincidence rather than by
# instruction. yu13-otel-trace-join deliberately has NO default (it can legitimately target the yu12
# rig, state-014 or GKE) and refuses rather than guess — measured 2026-08-18, it failed a suite run
# purely because a host reboot left the ambient current-context pointing at GKE. Its sibling
# yu13-otel-reject-trace-log-join carries the IDENTICAL trailing comment and DOES default, so the
# strictness difference reads as deliberate in both when it is arbitrary in one. Exporting makes the
# strict form work under the suite while keeping it strict for manual invocation, which is the
# behaviour worth having.
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
  yu16-treasury-pricing
  yu16-bond-position
  # Deliberately straight after yu16-bond-position, which leaves a held Treasury behind. The
  # accrual proof REFUSES on an extract with no TREASURY row rather than passing having checked
  # nothing, so this ordering is what keeps it from reporting SKIP-shaped success on a rig whose
  # positions were just wiped by a fresh epoch.
  yu16-accrued-interest
  # ADR-069 rules 1-4 (the session opens where the last one closed). Placed HERE, straight after
  # the bond block and BEFORE anything that closes an EOD session, for two reasons:
  #
  #   1. It reads the newest PUBLISHED session STRICTLY EARLIER than today, so a proof that cuts
  #      a close for today cannot change its answer -- but running it while the EOD tables are
  #      otherwise quiet keeps its planted-DRAFT arm unambiguous.
  #   2. It RESTARTS price-publisher (up to three times) and therefore re-seeds the feed. Nothing
  #      above it depends on a walked price level, and the proofs below that read the live
  #      reference (yu17-fnma-collar and the format-8 set) derive their probes FROM it rather than
  #      from a literal, so a re-seeded reference is not a hazard for them.
  #
  # It rolls no member, no gateway and no epoch, and it plants DRAFT rows keyed on
  # override_reason='yu17-session-opens-from-close' that it removes on every exit path -- a
  # leaked one is the exact object rule 2 exists to refuse, so its pre-clean runs before it
  # measures anything.
  yu17-session-opens-from-close
  # ADR-070 (the tape is the reference). Straight after its ADR-069 sibling and for the same
  # reasons: it restarts price-publisher (twice, plus a cleanup restamp) and rolls no member, no
  # gateway, no epoch. It needs the taq-replay-extract Secret and the replay-epoch stamp that
  # rebuild_fresh_epoch / start-cluster-kind.sh maintain, and their ABSENCE is a FAIL by design —
  # a rig quietly publishing the walk while everyone believes it replays the tape is this ADR's
  # own trap. Its hold-at-end arm drives the clock past the tape by re-stamping the ConfigMap and
  # restores the stamp from the PVC on every exit path.
  yu17-taq-replay
  # ADR-072 (replayed prints become order flow). Directly after its ADR-070 sibling, and in the
  # STABLE block for the same reason yu17-preopen-queue-open and yu17-retick-determinism are:
  # it asserts an EXACT order-ref and trade-leg delta for its own four orders, and the algo engine
  # has been observed moving that counter by 24 mid-proof — so it must stay ahead of
  # yu08-algo-slicing. It rolls nothing, mints one ticker, and leaves both accounts FLAT (every
  # cross is reversed) with anything still resting cancelled on the way out.
  #
  # THIS PROOF IS WHY THE SUITE CAN STILL BE BELIEVED WITH THE REPLAY LIVE. The replay is a third
  # writer of sequenced ORDER-shaped commands, and it moves every global counter the other proofs
  # bracket their work with. This one measures, on the rig, that the operator-scoped counters they
  # actually read do NOT move for it — and, in the same window, that they still move by exactly
  # four for orders of its own. A green suite that only passes because the replay happens to be
  # off is not a green suite, so step 1 here FAILS on a quiet publisher rather than skipping.
  yu17-replay-attribution
  # YU17 phase 2. It rolls NOTHING, so it belongs in the stable block rather than beside its
  # sibling below: it asserts the applied sequence moves by exactly two and that the contracts
  # artifact rebuilds byte-identically from the stored cut, and both are cheapest to assert on a
  # rig nothing else is disturbing. It closes an EOD session, so it sits AFTER every proof that
  # reads an extract (yu15-risk-extract, yu16-accrued-interest) rather than bumping the session
  # version underneath them. Its first suite run was 2026-08-17 on traderx/cluster-node:yu17-ackfix
  # at a fresh epoch, and it was NOT a "passes alone, fails in a suite" case. Steps 0-3 pass: both
  # swaptions sequenced through consensus, and an unknown exercise style refused 400 without moving
  # the sequence.
  #
  # IT WAS RED, AND IT IS FIXED — 2026-08-17, commits d3fd70b3 (YU17) / 97b03d70 (YU16) /
  # f379497b (YU15). Kept here because the reasoning is what stops the fix being undone.
  #
  # It used to fail at step 4 with /eod/session/close returning DRAFT, and so did yu17-swap-netting
  # below. THE GATE WAS CORRECT AND THE PROOF WAS WRONG: it asserted PUBLISHED straight off the
  # close, which assumes a universe that is never flagged. On 2026-08-17 the option
  # AAPL260918P00220000 was flagged SPIKE against the prior PUBLISHED close (2026-08-16 v1 = 0.435).
  # Option marks come off a simulated random-walk underlying, so a sub-dollar OTM put moving 1.41
  # absolute is ordinary and no band tuned for cash instruments survives it — roughly four closes in
  # five were affected.
  #
  # IT WAS A CLASS OF FOUR, not two, and all four were fixed together: yu17-swap-netting,
  # yu17-swaption-terms, yu16-accrued-interest, yu15-risk-extract. The latter two were passing at the
  # time — because the universe happened to be clean, NOT because they were robust — which is exactly
  # why a green suite was not evidence against the defect. The fix is ADR-026's own remedy: override
  # each flagged instrument AT ITS OWN OBSERVED CLOSE, publish, then assert. The band is not widened
  # and MISSING still fails, because no mark at all is a price-chain gap rather than a mark the gate
  # disliked. Both proofs verified green on a rig 2026-08-17 and again in the full suite 2026-08-18.
  #
  # WHAT LEFT WITH THE FIX, since it is not recorded anywhere else in this file: converting the
  # PUBLISHED assertion removed the only end-to-end detector of an option demoted to the 20% equity
  # band. Its replacement is yu06-quality-gate.sh step 6 (band selection, one planted prior driving
  # two opposite verdicts) — already in this array above. Do not remove that step believing the
  # accrual and extract proofs still cover it; they do not.
  yu17-swaption-terms
  # FORMAT-8 PROOF SET (design §5), registered BEFORE the mint with the red halves banked
  # (2026-08-25, this file's commit). Each script defaults EXPECT=after — the suite states the
  # POST-MINT claim — so on a pre-mint build these four are RED, deliberately: the defect they
  # describe (an inert collar on FNMA and every option) is live, and a skip would read as a pass
  # in the summary, which is exactly how the "no YU17 proofs in the YU17 suite" gap was born (see
  # the yu17-swap-netting account above). The mint chip runs the red halves as EXPECT=before
  # explicitly. All four roll nothing: resting non-crossing probes, cancelled on the way out.
  yu17-fnma-collar
  yu17-option-collar
  yu17-fine-grid
  yu17-book-retick           # mints a ticker via reference-data (needs the 18085 forward)
  # THE PHASE-MACHINE HALF of the same format-8 set (scope §5 rows 4 and 5), registered pre-mint
  # with its red halves banked (2026-08-25). Same default-EXPECT=after, deliberately-red posture as
  # the four above: on a pre-mint build POST /session 404s and the member's /health has no phase, so
  # both proofs RECORD that and go on to the OBSERVABLE defect — an order the venue should refuse is
  # accepted, and a crossing pair that should queue trades on the spot. A red that stopped at the
  # 404 would prove the API is missing, which is worth nothing at the mint.
  #
  # THEY MUST STAY IN THIS BLOCK, ahead of yu08-algo-slicing. Both assert EXACT order-ref deltas
  # (lib-consensus-readings.sh's assert_order_effects — the ref bracket is what makes their trade
  # readings attributable), and the algo engine has been observed moving next_order_ref by 24
  # mid-proof. Same exposure yu13-readmodel-effect-end has; same remedy, which is the scale-to-0
  # this runner already does.
  #
  # yu17-preopen-queue-open books ONE match on a ticker it mints and deletes the position rows on
  # the way out — the yu17-band-follows-market / yu17-retick-determinism posture. It rolls nothing,
  # kills nothing and wipes nothing. yu17-session-closed-rejects crosses nothing at all.
  yu17-session-closed-rejects
  yu17-preopen-queue-open
  yu13-otel-trace-join
  yu13-otel-reject-trace-log-join
  yu10-fix-session
  yu08-algo-slicing          # needs the algo engine up; scaled in below
  yu16-book-grid             # rebuilds a member from an empty disk
  # THE headline YU17 proof, and it lives HERE rather than next to yu17-swaption-terms because it
  # is a ROLLING proof: it deletes member 2's PVC and then its pod, so the member returns with
  # nothing and the contracts artifact has to rebuild from the other two (snapshot format 5 plus
  # the replayed log tail). That is the same class as yu16-book-grid directly above, hence the
  # adjacency. It must stay BEFORE yu13-stp-and-replace, which rolls the members onto yu15-era
  # builds that predate the contract record entirely.
  #
  # WAS RED at step 4, deliberately and NOT as a skip; FIXED 2026-08-17 with the other three call
  # sites — see the account at yu17-swaption-terms above. The price-quality gate was behaving
  # correctly and the assertion of PUBLISHED straight off /eod/session/close was what was wrong.
  # Anyone reading a historical "two YU17 proofs fail" as "YU17 is broken" had it backwards. Green
  # on a rig 2026-08-17 and in the full suite 2026-08-18.
  #
  # The reason it was left RED rather than skipped still applies to anything else that fails here:
  # a skip reads as a pass in the summary line, which is exactly how the "no YU17 proofs in the YU17
  # suite" gap was born in the first place.
  yu17-swap-netting
  # YU17 FX-rate fix (7256a33c). Same rolling class as the two above — it deletes member 2's PVC
  # and pod to prove the sequenced FX rate survives a rebuild from an empty disk — hence the
  # adjacency, and it must also stay BEFORE yu13-stp-and-replace's yu15-era member roll. Its flip
  # arm (GBP refused PRICE_MISSING before the rate is sequenced, accepted after) needs GBP to be
  # rate-less: seed-proof-fixtures deliberately seeds only EUR/JPY, so the arm runs once per
  # epoch; a second run on the same epoch exits 2 (SKIP) rather than passing without it.
  yu17-fx-credit
  yu16-ready-tracks-commit   # scales the members to 1 and back (no PVC wipe, no epoch change)
  # Same quorum-loss trick, taken further: it drives the no-ack streak past the LIVENESS limit and
  # lets the kubelet restart the gateway. Deliberately AFTER the readiness proof, whose step 3
  # asserts /ready stays 503 across a restored quorum — a liveness restart would clear the streak
  # and hand it a fresh, ready gateway to measure.
  yu16-liveness-restarts-wedge
  # Option B (keyed ack correlation, YU17): kills a leader under a staggered stream and checks
  # EVERY answered client against the engine's own idempotency table, then that depth self-drains
  # with no reconnect. Disruptive (leader kill, statefulset recovers it on the same image) but no
  # PVC wipe and no epoch — hence this block. Must stay BEFORE yu13-cancel-ingress (whose gateway
  # roll would hide the "no reconnect" reading) and BEFORE yu13-stp-and-replace's yu15-era member
  # roll (whose builds predate the 32-byte ack entirely). A kill that strands nothing exits 2
  # (uninformative, not confirming — rule 18); on a pre-B build this proof is REQUIRED to fail.
  yu17-keyed-ack-correlation
  # Format-8 §2.3.3 determinism (design §5 row 6). Lives in THIS block, not with its four siblings
  # above, because its tail kills the leader (statefulset recovers it on the same image — the
  # yu17-keyed-ack class, no PVC wipe, no epoch) and books one trade on a ticker it mints. Same
  # default-EXPECT=after and deliberately-red-pre-mint posture as the four above.
  yu17-retick-determinism
  # The two DURABILITY proofs of the format-8 set (scope §5 rows 6 and 7). Both default to
  # DESTRUCTIVE=0 and exit 2 (SKIP): one kills a leader, the other restarts a member, and unlike
  # yu17-retick-determinism there is no safe prefix to run first — every step is the destructive
  # part. That is deliberate and it is NOT the "a skip reads as a pass" trap, because their red
  # halves are banked OFF-RIG where the claim is actually decided: SessionSnapshotRestoreTest over
  # MECS's writeSnapshot/onSnapshotRecord seams measures that this build writes no session or queue
  # record at all, and that a restore accepting a truncated stream is SILENT. A halt is a snapshot
  # question; a new leader and a restarted member are both just a restore.
  # The MINT CHIP runs them for real on the fresh epoch:
  #     DESTRUCTIVE=1 EXPECT=after bash scripts/proofs/yu17-halt-survives-failover.sh
  #     DESTRUCTIVE=1 EXPECT=after bash scripts/proofs/yu17-closed-survives-restart.sh
  # They live HERE, beside yu17-retick-determinism, because that is where they belong once armed:
  # same kill-and-recover-on-the-same-image class, no PVC wipe, no epoch change, and both must stay
  # BEFORE yu13-stp-and-replace's yu15-era member roll.
  yu17-halt-survives-failover
  yu17-closed-survives-restart
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
  # YU16: yu16-treasury-pricing reads the Treasury quote directly. Owned here for the same reason
  # the reference-data forward is: a proof whose result depends on whether a human is holding a
  # tunnel in another terminal reports on the tunnel, not on the system.
  "svc/price-publisher 18100:18100"
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
# stp_return_gateway first: a run killed mid-borrow must hand the gateway back before the forwards
# it would need to verify that go away. No-ops unless a borrow is actually outstanding.
trap 'declare -F stp_return_gateway >/dev/null && stp_return_gateway; kill_forwards' EXIT

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
    # YU16 (FR-CDM12, ADR-058): deliberately still /stocks. The yu04 proofs moved to the general
    # /instruments route; this gate stays on the retained one, which makes "/stocks is retained"
    # a standing regression check rather than a claim in a spec.
    [[ "$(curl -s -o /dev/null -w '%{http_code}' -m5 http://localhost:18085/stocks/control-snapshot 2>/dev/null)" == "200" ]] || ready=0
    [[ "$(curl -s -o /dev/null -w '%{http_code}' -m5 http://localhost:18100/prices/UST-20280630 2>/dev/null)" == "200" ]] || ready=0
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
# DERIVED, never defaulted to a literal. This line used to read
#   BASELINE_IMAGE="${YU15_CLUSTER_IMAGE:-traderx/cluster-node:yu15}"
# which was correct while YU15 was the tip and silently wrong afterwards — on YU16 and YU17 a BARE
# invocation rebuilt the cluster onto :yu15 AT A FRESH EPOCH before running a single proof, then
# reported a full green suite about code predating the change under test. The default was the
# whole bug: it is better to refuse than to guess an engine build.
#
# The manifests are the authority (they are what kubectl applies), so BASELINE_IMAGE now comes
# from the same derivation start-cluster-kind.sh uses. CLUSTER_IMAGE is the neutral override name;
# YU15_CLUSTER_IMAGE still works so every existing invocation and doc keeps functioning.
BASELINE_IMAGE="${CLUSTER_IMAGE:-${YU15_CLUSTER_IMAGE:-$(declared_cluster_image "${ROOT}" || true)}}"
if [[ -z "${BASELINE_IMAGE}" ]]; then
  cat >&2 <<'EOF'
[fail] cannot determine the cluster-node image for this state, and will not fall back to a literal.
       A wrong baseline does not fail loudly — it rebuilds the rig at a fresh epoch and reports a
       green suite about a build nobody is testing.
       Fix the derivation (specs/<state>/generation/kubernetes/cluster/statefulset.yaml must
       declare a traderx/cluster-node image), or state it explicitly:
           CLUSTER_IMAGE=traderx/cluster-node:yuNN bash scripts/yu15/run-proofs.sh ...
EOF
  exit 1
fi
echo "[state] $(state_pack "${ROOT}") -> baseline image ${BASELINE_IMAGE}"
# Must match IMAGE_PRE in scripts/proofs/yu13-stp-and-replace.sh -- read the block there for what
# the pair is. Short version: SYNTHESIZED from this tree by scripts/yu15/build-stp-boundary-images.sh
# as of 2026-08-23, replacing two unrebuildable July builds. `pre` is today's engine with the
# cancel-rather-than-cross branch and the /replace route removed by a recorded patch; `fix` is
# today's engine untouched. Rebuild both whenever the tip moves -- the boundary is supposed to
# track the system, and a tag named for the current tree goes stale silently.
STP_IMAGE_PRE="${IMAGE_PRE:-traderx/cluster-node:stp-boundary-pre}"
current_image() { ${K} get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null; }

# The only safe way to swap the engine build OR to recover a wedged rig: take the cluster down,
# wipe the members' PVCs, and bring it up on a FRESH EPOCH. A ROLLING image swap is exactly the
# wrong way -- a deterministic-core change rolled gradually leaves some members applying under the
# old engine and some under the new, and the state machines diverge PERMANENTLY. Not theoretical:
# pinning by bare `set image` produced [61 ...] [62 ...] [62 ...], member 0 a full order behind and
# STILL behind after 180s, plus a risk-extract cut the members could not agree on.
# THE MEMBERS ONLY, AND THIS HAS NOT CHANGED. The gateway is deliberately left on whatever build
# it is already running.
#
# The gateway is stateless and holds no epoch, so it has no reason to share the members' build; the
# baseline block above already pins it to ${BASELINE_IMAGE} and this function has no business
# undoing that. It used to be repinned here alongside the StatefulSet, and the bug was precisely
# that a general-purpose function acquired a side effect exactly one caller wanted: the stp prep
# block below silently took the GATEWAY historical too, announcing only "fresh epoch minted ON ...".
# A runner repinning a component a PROOF is making claims about is how a change becomes invisible.
# Do not reintroduce that here. If a caller needs a matched pair, the caller borrows it and gives
# it back, in view -- see stp_borrow_gateway below.
#
# The OTHER half of the original warning is now stale and is recorded here so it is not re-argued:
# yu15-pre/yu15-stp predate the gateway's probe server (18111, /live) that the manifest's three
# probes point at, so the kubelet failed the startup probe and crash-looped a gateway whose only
# defect was being older than the manifest
# (issues/resolved/HANDOFF-issue-historical-gateway-images-fail-the-probe-port.md). That is handled
# now: GW_HISTORICAL_PROBES -- the pre-probe-server form, /ready on 18110 with the
# initialDelaySeconds:5 those builds shipped with -- exists in scripts/proofs/yu13-stp-and-replace.sh
# and yu13-cancel-ingress.sh, and both proofs already roll historical gateways with it. Rolling a
# historical gateway is no longer the hazard it was; doing it from INSIDE this function still is.
# THE IMAGE MUST BE ON THE NODES BEFORE ANYTHING IS DESTROYED. This function wipes the PVCs first
# and discovers an unreachable image afterwards, at which point the epoch it would have fallen back
# to no longer exists. `kind load` is start-cluster-kind.sh's job and this script never did it, so
# naming a CLUSTER_IMAGE that exists only in the local Docker daemon used to mean ImagePullBackOff
# several minutes later, on a rig with nothing left to run. Idempotent and cheap when already there.
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

# A SWALLOWED SEEDER IS A SUITE THAT ASSERTS AGAINST A RIG IT NEVER SET UP.
#
# The three call sites below used to be `>/dev/null 2>&1` with no status check. A seeder that died
# part way -- unreachable matcher, a class missing from the feed, a symbol table it could not fit --
# left the epoch PARTLY enabled and every later proof ran against it. Proofs that mint their own
# ticker still pass, so the suite stays green while the instrument classes the seeder exists to
# enable are simply absent: the same vacuous-pass shape this file refuses everywhere else. Measured
# 2026-08-22 while widening the seeder to the whole quoted universe -- the run that proved the
# widening worked could not distinguish "68 instruments enabled" from "the seeder exited at 3".
#
# Not fatal to the suite by itself (a partly-seeded rig can still answer some proofs honestly), but
# it must be VISIBLE and it must be attributable to the seeder rather than to whichever proof trips
# over it three steps later.
# BORROWING THE GATEWAY FOR THE SEEDING STEP, AND GIVING IT BACK.
#
# POST /seed does not complete when a TIP gateway sits in front of HISTORICAL members: measured
# 2026-08-22, all seven account seeds 422 and the first instrument {"seeded":false}, on two
# consecutive suite runs. It is not a readiness race and not a capacity refusal -- varying only the
# gateway, with the same seeder and the same fresh-epoch procedure, settles it:
#     members yu15-pre-1k + gateway yu17-jsrebind (tip)             -> every seed refused
#     members yu15-pre-1k + gateway yu15-pre-1k + historical probes -> all 68 enabled
# So the stp epoch had NEVER carried the fixture universe -- not 20 tickers, not 44, not 68. It hid
# because yu13-stp-and-replace's own roll_to puts the gateway on the same historical build before
# the proof seeds its own ticker, so the proof never saw the mismatch it runs underneath.
# (issues/open/the-stp-prep-seeds-through-a-tip-gateway-onto-historical-members.md)
#
# Scoped to this ONE caller on purpose. rebuild_fresh_epoch stays members-only; the seeding step is
# the only thing here that needs a matched pair, so it borrows the gateway around itself and returns
# it before the proof starts. Returning it BEFORE the proof is not tidiness: yu13-stp-and-replace
# captures the gateway's image on entry and restores that on EXIT, so handing it a historical
# gateway would make it faithfully restore a historical gateway at the end of the suite -- the
# restore-what-you-found latch that cost three proofs once already.
#
# CORRECTED 2026-08-23, because this paragraph is now false and it was load-bearing. It used to
# read: "the historical builds carry no CONTROL_FEED_SUBSCRIBER at all (verified by javap), so the
# hazard the env-var dance above exists to prevent is ABSENT for the borrowed window, not merely
# disabled." That was true of the July builds and is not true of the synthesized pair, which is
# built from this tree and carries the subscriber like every current build.
#
# The hazard is now merely DISABLED, which is weaker and is enough only because of the ordering
# below: `set env CONTROL_FEED_SUBSCRIBER=0` lands on the Deployment and its rollout is awaited
# BEFORE the epoch is minted, and stp_borrow_gateway patches only image+probes, so the borrowed
# gateway inherits the 0. Do not reorder the borrow above the `set env`: a borrowed gateway with
# the subscriber still on replays the YU04 control feed's 510-security universe straight into the
# brand-new epoch, which is exactly what the wait above exists to prevent.
STP_GW_BORROWED=0
STP_GW_IMAGE=""
STP_GW_PROBES=""

# Read the probe forms out of the proof that owns them rather than keeping a third copy. Every
# script in scripts/proofs is standalone and sources nothing -- a property worth more than the
# duplication between the two proofs -- but the runner is not a proof, and a third copy free to
# drift from the two that matter is strictly worse than reading theirs.
gw_probe_form() { # gw_probe_form <VAR_NAME>
  local v
  v="$(sed -n "s/^$1='\(.*\)'\$/\1/p" "${ROOT}/scripts/proofs/yu13-stp-and-replace.sh")"
  [[ -n "${v}" ]] || fail_hard "could not read $1 out of scripts/proofs/yu13-stp-and-replace.sh"
  printf '%s' "${v}"
}

stp_borrow_gateway() { # stp_borrow_gateway <image>
  local image="$1" container
  container="$(${K} get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].name}')"
  STP_GW_IMAGE="$(${K} get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].image}')"
  STP_GW_PROBES="$(${K} get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0]}' \
    | python3 -c 'import sys,json;c=json.load(sys.stdin);print(",".join(json.dumps(k)+":"+json.dumps(c.get(k)) for k in ("startupProbe","readinessProbe","livenessProbe")))')"
  # A CAPTURE OF AN ALREADY-BROKEN DEPLOYMENT IS NOT THE THING TO RESTORE. A run that died between
  # a patch and its restore leaves the probes stripped; capturing THAT as "original" latches the
  # damage into every later run, which then reports a successful restore. Same guard, same reason,
  # as the one in yu13-stp-and-replace.sh.
  if [[ "${STP_GW_PROBES}" == *'"startupProbe":null'* || "${STP_GW_PROBES}" == *'"livenessProbe":null'* ]]; then
    echo "[stp-prep] [warn] the gateway already carries no startup or liveness probe, so an earlier run"
    echo "[stp-prep] [warn] died before its restore. Returning the MANIFEST form, not this capture."
    STP_GW_PROBES="$(gw_probe_form GW_MANIFEST_PROBES)"
  fi
  STP_GW_BORROWED=1
  echo "[stp-prep] borrowing the gateway onto ${image} for the seeding step only (it was ${STP_GW_IMAGE})"
  ${K} patch deploy cluster-gateway --type=strategic \
    -p "{\"spec\":{\"template\":{\"spec\":{\"containers\":[{\"name\":\"${container}\",\"image\":\"${image}\",$(gw_probe_form GW_HISTORICAL_PROBES)}]}}}}" >/dev/null
  ${K} rollout status deploy/cluster-gateway --timeout=600s >/dev/null \
    || { echo "[stp-prep] [fail] the borrowed gateway never settled on ${image}"; return 1; }
}

stp_return_gateway() {
  [[ "${STP_GW_BORROWED}" == "1" ]] || return 0
  local container
  container="$(${K} get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].name}' 2>/dev/null)"
  echo "[stp-prep] returning the gateway to ${STP_GW_IMAGE} and the probes it had"
  ${K} patch deploy cluster-gateway --type=strategic \
    -p "{\"spec\":{\"template\":{\"spec\":{\"containers\":[{\"name\":\"${container}\",\"image\":\"${STP_GW_IMAGE}\",${STP_GW_PROBES}}]}}}}" >/dev/null 2>&1
  ${K} rollout status deploy/cluster-gateway --timeout=600s >/dev/null 2>&1 \
    || echo "[stp-prep] [warn] the gateway did not settle back on ${STP_GW_IMAGE} -- check it before the next run"
  STP_GW_BORROWED=0
}

SEED_N=0
seed_fixtures() { # seed_fixtures [fresh]  -- "fresh" clears the projection for a new epoch
  local log fresh=0
  [[ "${1:-}" == "fresh" ]] && fresh=1
  SEED_N=$((SEED_N + 1))
  log="/tmp/proofrun/seed-${SEED_N}.log"
  if FRESH_EPOCH="${fresh}" bash "${ROOT}/scripts/yu15/seed-proof-fixtures.sh" >"${log}" 2>&1; then
    grep -E 'feed census|instruments enabled' "${log}" | sed 's/^ */  [seed] /' || true
    return 0
  fi
  echo "[fail] seed-proof-fixtures exited nonzero (${log}). The rig is only PARTLY enabled and every"
  echo "       proof after this point asserts against it. Last lines:"
  tail -5 "${log}" | sed 's/^/       /'
  return 1
}


# GATE ON THE FACT, NOT ON THE COMMAND RETURNING. Both rollout waits used to redirect stdout only,
# so their failure text reached the terminal while their EXIT STATUS was discarded — the function
# carried on and the caller printed "fresh epoch" regardless. Measured 2026-08-17: it announced a
# fresh epoch while all three members sat in ImagePullBackOff and the PVCs were already wiped; the
# rig was down and the log asserted it was up. That failure was loud because NOTHING was running.
# The dangerous variant is a PARTIAL rollout — two members up, one wedged, rollout status times out,
# the message still says fresh epoch, and every proof afterwards describes a two-member cluster
# truthfully. prove-cluster-engine-change §1 already prescribes the end-state assertion below; it
# was simply never wired in here.
rebuild_fresh_epoch() { # rebuild_fresh_epoch [image] [allow-image-change] -- down, PVC wipe, optionally repin members, up
  local image="${1:-}"
  # AN EPOCH WIPE MUST NEVER ALSO BE A SILENT BUILD CHANGE. The baseline block derives its image
  # from the MANIFESTS, and the manifests drift behind what a lane actually rolled
  # (issues/open/the-manifests-pin-a-build-the-rig-no-longer-runs.md) — so a bare invocation on a
  # drifted rig would destroy the epoch AND revert the members to a build missing whatever the
  # drift carried. Measured 2026-08-25: manifests declared :yu17-bbo while the rig ran
  # :yu17-markwait2. Wiping is this function's job; CHANGING THE BUILD while doing it needs an
  # explicit second argument (the two stp call sites, which swap builds deliberately and in view)
  # or the operator override the refusal names. This is an interim loud-stop, not the pinning
  # policy — that remains yaakov's call, recorded in the issue above.
  local running; running="$(current_image)"
  if [[ -n "${image}" && -n "${running}" && "${image}" != "${running}" \
        && "${2:-}" != "allow-image-change" && "${ALLOW_IMAGE_CHANGE:-0}" != "1" ]]; then
    fail_hard "refusing to wipe the epoch AND change the members' build in one motion:
       members are running   ${running}
       this rebuild would pin ${image} (derived from the manifests / CLUSTER_IMAGE)
       If the running build is the one under test:   CLUSTER_IMAGE=${running} bash scripts/yu15/run-proofs.sh ...
       If the build change is deliberate:            ALLOW_IMAGE_CHANGE=1 bash scripts/yu15/run-proofs.sh ..."
  fi
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
  # A /seed through a MISMATCHED gateway is refused for reasons that have nothing to do with
  # writability -- that refusal is the whole reason stp_borrow_gateway exists -- so the write probe
  # only speaks when the pair matches. The stp prep mints onto a historical image with the gateway
  # still on the tip and seeds through a gateway it borrows for the purpose; it takes the else.
  if [[ "$(gateway_image)" == "$(current_image)" ]]; then
    await_cluster_writable || fail_hard "the cluster never sequenced a write after the epoch mint --
       the pods rolled and the members are ready, and the log is empty of any other complaint.
       This is NOT a fresh epoch anyone can seed; see the last answer printed above."
  else
    echo "[epoch] gateway $(gateway_image) does not match members $(current_image): skipping the write"
    echo "        probe (a mismatched pair refuses every /seed regardless of writability)"
  fi
  # ADR-070: a fresh epoch restarts the tape at day 1, and the publisher learns that ONLY through
  # the replay-epoch stamp. A stale stamp is the silent-wrong form — the clock keeps running from
  # the DEAD epoch's mint and the prices are completely plausible — so a stamp that cannot be
  # written is a hard stop, exactly like the write probe above. (A rig with no replay wiring at
  # all stamps nothing and returns 0; the publisher then says so on /health and walks.)
  stamp_replay_epoch || fail_hard "the replay-epoch ConfigMap could not be stamped for this fresh
       epoch — the publisher would keep replaying on the dead epoch's clock, silently"
  roll_feed_adapter
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

# ---- WHAT "THE EPOCH IS MINTED" HAS TO MEAN, AND TWICE DID NOT --------------------------------
#
# `rollout status` returning is a true statement about PODS. The runner has now handed on a rig
# that satisfied it and was unusable in two different ways, neither of which failed loudly:
#
#   1. THE CLUSTER COULD NOT YET SEQUENCE A WRITE. Measured 2026-08-25, members rolled and nothing
#      else touched, POST /seed at fixed offsets after `rollout status` returned:
#          +0s {"error":"TimeoutException"}   +10s {"seeded":true}   +20s/+30s/+60s {"seeded":true}
#      Under ten seconds, reproduced three times. yu13-otel-reject-trace-log-join seeded inside
#      that window and failed on three consecutive runs, always at the same line.
#      (issues/resolved/rollout-status-returns-before-the-cluster-can-sequence-a-write.md)
#
#   2. THE FEED ADAPTER WAS ASLEEP. It is the third Deployment running the cluster-node image and
#      a cluster CLIENT exactly as the gateway is, and it got neither the roll the gateway gets
#      here nor any assertion. Minting an epoch under a live adapter kills its session, it
#      fail-fasts (deliberately), and CrashLoopBackOff -- already at its 5-MINUTE CAP after a suite
#      that rolls the members a few times -- then sleeps through the healthy cluster underneath it.
#      Reproduced 2026-08-25: session lost at 17:00:46, members 3/3 at 17:01:12, and the SAME POD
#      sat ready=false with no retry attempted until 17:06:14. The suite still went green, because
#      the proofs that need prices seed their own or run before the adapter dies. So the rig was
#      handed on with no live price feed and every reading still looking fine, on the state where
#      the feed is the collar's reference (ADR-066) and the price-derived grid's derivation input.
#
#      A rollout restart is the remedy because a NEW POD's backoff starts at ZERO, not because pod
#      identity matters -- the original issue read it as "only a new pod recovers it" and that is
#      measurably false (`dirDeleteOnStart(true)` is already set, and a fresh pod against absent
#      members crash-loops identically).
#
#      SO THE ROLL BUYS LATENCY, NOT RECOVERY. Left alone the adapter comes back by itself, within
#      five minutes. Do not read the `rollout restart` below as load-bearing for correctness: it
#      closes a five-minute hole in which this rig has no price feed, on the state where that feed
#      is the collar's reference and the price grid's input, and where every other reading still
#      looks fine. The load-bearing half is the ASSERTION after it, which is the only thing here
#      that can tell a working feed from a dead one at all.
#      (issues/resolved/a-fresh-epoch-strands-the-feed-adapter-and-only-a-new-pod-recovers-it.md)
#
# Both gates below assert the FACT rather than the command returning, and both are satisfiable and
# refutable: point them at a cluster scaled to zero, or at an adapter that cannot reach the
# members, and they go red inside their budget. Neither retries an ASSERTION -- they are setup
# gates on the runner's own claim, and the hard failure stands if the property never arrives.

# Moved up from the gateway-pin block below, which is now not the first caller.
gateway_image() { ${K} get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null; }

# The write goes through the GATEWAY POD ITSELF (wget on localhost:18110) rather than a port
# forward: this runs inside rebuild_fresh_epoch, which is called before any forward exists and from
# the stp wrap, where re-establishing one costs a gateway roll. ZZPROBE9 is the same throwaway
# ticker the symbol-table probe further down already registers -- one symbol slot serves both, and
# nothing reads its price.
await_cluster_writable() { # await_cluster_writable [budget-seconds]
  local budget="${1:-120}" waited=0 r=""
  while (( waited < budget )); do
    r="$(${K} exec deploy/cluster-gateway -- wget -q -O- --header='Content-Type: application/json' \
      --post-data='{"accountId":42422,"tickers":"ZZPROBE9","price":100}' \
      http://localhost:18110/seed 2>/dev/null)"
    if [[ "${r}" == *'"seeded":true'* ]]; then
      echo "[epoch] cluster sequenced a write ${waited}s after the roll"
      return 0
    fi
    sleep 3; waited=$((waited + 3))
  done
  echo "[epoch] cluster never sequenced a write within ${budget}s (last answer: ${r:-nothing})"
  return 1
}

# THE ASSERTION IS THE ROUND TRIP, NOT THE POD. "feed-adapter is 1/1 Ready" is the same
# false-success this whole family is about: the Deployment carries no readinessProbe, so Ready
# means the container started, and a container that starts and then times out connecting is Ready
# for its whole doomed life. A `SYMBOL <ticker>=<id>` line is printed only when a registration the
# adapter OFFERED came back on the EGRESS -- ingress publication connected, command sequenced and
# committed by the members, ack delivered to this client. Nothing else in that process prints one,
# and a stranded adapter prints none. On a fresh epoch the symbol table is empty and so is the
# adapter's own cache, so it re-registers every ticker it has seen: 69 on this rig's published
# universe. The bar is 20 -- far above "one lucky ack", far enough below 69 that a publisher whose
# universe shrinks does not turn a real gate into a flake.
#
# THE POD UID MUST CHANGE FIRST. `kubectl logs deploy/feed-adapter` during a Recreate roll can
# still resolve the OUTGOING pod, whose log carries the previous epoch's 69 SYMBOL lines; counting
# those would pass this gate against an adapter that never reconnected. That is exactly the trap
# await_member_restored refuses in scripts/proofs/lib-consensus-readings.sh, and it is worth more
# here than there, because here nothing downstream would ever contradict it.
roll_feed_adapter() {
  local replicas old uid name n waited=0
  replicas="$(${K} get deploy feed-adapter -o jsonpath='{.spec.replicas}' 2>/dev/null)"
  if [[ -z "${replicas}" ]]; then
    echo "[epoch] no feed-adapter Deployment on this rig; nothing to restore"
    return 0
  fi
  if [[ "${replicas}" == "0" ]]; then
    echo "[epoch] feed-adapter is scaled to 0 -- THIS EPOCH HAS NO PRICE FEED (deliberate in the stp prep)"
    return 0
  fi
  # PIN IT HERE, not in the repin block below, because that block runs AFTER the baseline mint and
  # this gate runs inside it. A cluster client on a different codec generation from the members
  # cannot round-trip a registration, so a stale adapter would fail this assertion for a reason
  # that has a one-line remedy and no relation to the fault the assertion exists to catch.
  local want; want="$(current_image)"
  if [[ -n "${want}" && "$(${K} get deploy feed-adapter -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)" != "${want}" ]]; then
    echo "[epoch] repinning feed-adapter to ${want} (it tracks the members' build)"
    ${K} set image deployment/feed-adapter \
      "$(${K} get deploy feed-adapter -o jsonpath='{.spec.template.spec.containers[0].name}')=${want}" >/dev/null
  fi
  old="$(${K} get pod -l app=feed-adapter -o jsonpath='{.items[0].metadata.uid}' 2>/dev/null)"
  ${K} rollout restart deployment/feed-adapter >/dev/null
  ${K} rollout status deployment/feed-adapter --timeout=600s >/dev/null \
    || fail_hard "the feed adapter's rollout did not complete after the epoch mint"
  while (( waited < 240 )); do
    uid="$(${K} get pod -l app=feed-adapter -o jsonpath='{.items[0].metadata.uid}' 2>/dev/null)"
    name="$(${K} get pod -l app=feed-adapter -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)"
    if [[ -n "${uid}" && "${uid}" != "${old}" && -n "${name}" ]]; then
      n="$(${K} logs "${name}" 2>/dev/null | grep -c '^SYMBOL ' || true)"
      [[ "${n}" =~ ^[0-9]+$ ]] || n=0
      if (( n >= 20 )); then
        echo "[epoch] feed adapter sequencing: ${n} symbols round-tripped through consensus (${waited}s)"
        return 0
      fi
    fi
    sleep 5; waited=$((waited + 5))
  done
  fail_hard "the feed adapter is not sequencing after the epoch mint: ${n:-0} symbols registered in
       240s on pod ${name:-<none>} (want >= 20). Every other reading on this rig looks fine and the
       suite would go green -- with no live price feed behind the collar's reference or the
       price-derived grid. See issues/resolved/a-fresh-epoch-strands-the-feed-adapter-*.md"
}

NEED_FRESH=0
if [[ "$(current_image)" != "${BASELINE_IMAGE}" ]]; then
  echo "[baseline] cluster is on $(current_image); rebuilding on ${BASELINE_IMAGE} at a fresh epoch"
  rebuild_fresh_epoch "${BASELINE_IMAGE}"
  # SAY SO. Without this the projection-staleness check below runs against the brand-new epoch it
  # just minted, necessarily fires (engine tradeCounter 0 < the dead epoch's highest trade id in
  # SQL) and calls rebuild_fresh_epoch a SECOND time -- scale to zero, wait for delete, wipe the
  # PVCs, scale to three, two 600s rollout waits, gateway restart. Measured three times in a row
  # during the format-8 mint. The check is right; the question was already answered on this line.
  # The `if NEED_FRESH == 1` block further down still clears and reseeds the projection, exactly
  # once. (issues/resolved/the-proof-runner-wipes-the-epoch-twice-on-an-image-change.md)
  NEED_FRESH=1
  echo "[baseline] cluster now on ${BASELINE_IMAGE}, fresh epoch"
fi

# FORCE_FRESH_EPOCH=1: start from a mint even when the image already matches. A run on a STANDING
# epoch inherits every trade the previous suite accumulated, and yu05-recon's stillness gate — a
# documented proxy its own header refuses to widen — settles mid-sweep on a multi-page blotter and
# misses the mismatch control planted on the oldest row. Measured 2026-08-26: same build, fresh
# epoch = recon green; standing 170-trade epoch = the planted-control arm settled early and went
# red. Default off: an incremental re-run against a standing epoch is still a valid (and faster)
# thing to want; a CLOSING run should pass 1.
if [[ "${NEED_FRESH}" == "0" && "${FORCE_FRESH_EPOCH:-0}" == "1" ]]; then
  echo "[baseline] FORCE_FRESH_EPOCH=1: minting a fresh epoch before the run"
  rebuild_fresh_epoch "${BASELINE_IMAGE}"
  NEED_FRESH=1
fi

# The GATEWAY image is pinned separately, because checking only the StatefulSet let a whole run
# fail four proofs: yu13-cancel-ingress rolls the gateway to its own build, its restore did not
# happen (the run before it died part-way), and the STS-only check above never noticed. Against
# that 9-day-old gateway clordid double-booked on resend (no clientOrderKey plumbing), both OTel
# proofs found no trace in Tempo (no gateway spans), and yu08's children were silently rejected
# (predates the instrumentOf alias) -- every one reporting a different build's behaviour
# truthfully. The gateway is stateless, so a mismatch here needs a repin and nothing else: no PVC
# wipe, no epoch reset, no projection clear.
if [[ "$(gateway_image)" != "${BASELINE_IMAGE}" ]]; then
  echo "[baseline] gateway is on $(gateway_image); repinning to ${BASELINE_IMAGE}"
  ${K} set image deployment/cluster-gateway \
    "$(${K} get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].name}')=${BASELINE_IMAGE}" >/dev/null
  ${K} rollout status deployment/cluster-gateway --timeout=600s >/dev/null
fi

# THE EXTRACT PRODUCER NEEDS BOTH HALVES OF THE TREATMENT THE GATEWAY GETS ABOVE: a repin, for a
# tag that is simply wrong, and a restart, for a tag that is right while the content behind it is
# not. Only the second existed, and the staleness check below CANNOT cover the first -- it asks
# whether the pod is OLDER than the image, and a producer left behind by a DESCENDANT branch is
# newer. Restarting it just brings the same wrong build back.
#
# Measured 2026-08-14: the YU16 suite ran with the members and the gateway on
# traderx/cluster-node:yu16 and the producer still on yu17p2, whose SwapContractCsv requires a
# `contracts=` field that a YU16 cut does not carry. The EOD batch aborted with "cut header missing
# contracts=", and yu15-risk-extract and yu16-accrued-interest both failed reporting "no
# RISK-EXTRACT-READY" -- true, and silent about the cause. Same shape as the gateway lesson above,
# one deployment further along: check every Deployment that runs the cluster-node image, not the
# StatefulSet alone.
producer_image() { ${K} get deploy risk-extract -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null; }
if [[ -n "$(producer_image)" && "$(producer_image)" != "${BASELINE_IMAGE}" ]]; then
  echo "[baseline] risk-extract is on $(producer_image); repinning to ${BASELINE_IMAGE}"
  ${K} set image deployment/risk-extract \
    "$(${K} get deploy risk-extract -o jsonpath='{.spec.template.spec.containers[0].name}')=${BASELINE_IMAGE}" >/dev/null
  ${K} rollout status deployment/risk-extract --timeout=600s >/dev/null
fi

# THE FEED ADAPTER IS THE THIRD DEPLOYMENT RUNNING THE CLUSTER-NODE IMAGE, and the lesson above is
# stated as "check every Deployment that runs the cluster-node image, not the StatefulSet alone" --
# so it gets the same repin rather than becoming the next instance of it. It is a cluster CLIENT
# speaking AeronReplicationCodec on the ingress, so a stale build here is a wire mismatch, not a
# cosmetic tag. Cheap and silent when it is scaled to 0, which is where it sits today
# (issues/open/the-feed-adapter-parses-the-wrong-level-of-the-pricing-envelope.md).
adapter_image() { ${K} get deploy feed-adapter -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null; }
if [[ -n "$(adapter_image)" && "$(adapter_image)" != "${BASELINE_IMAGE}" ]]; then
  echo "[baseline] feed-adapter is on $(adapter_image); repinning to ${BASELINE_IMAGE}"
  ${K} set image deployment/feed-adapter \
    "$(${K} get deploy feed-adapter -o jsonpath='{.spec.template.spec.containers[0].name}')=${BASELINE_IMAGE}" >/dev/null
  ${K} rollout status deployment/feed-adapter --timeout=600s >/dev/null
fi

# The EXTRACT PRODUCER runs the same cluster-node image and was pinned by nothing. Its tag never
# changes, so `kubectl apply` leaves a pod running whatever bits that tag meant when it started --
# observed on 2026-08-10 with a producer six days stale while the members ran a fresh build. The
# tag comparison above cannot see it (the tag matches; the CONTENT does not), so compare the
# resolved image ID against the local daemon's and restart when they differ. risk-extract is a
# stateless batch producer: a restart costs nothing, no PVC, no epoch.
# Compare TIMES, not ids: kind re-imports the image under its own digest, so the pod's imageID
# never equals the local daemon's Id and an id comparison would fire on every single run -- a
# guard that always fires is as useless as one that never does. A producer older than the image
# it claims to run is the actual condition, and it is satisfiable.
producer_started() { ${K} get pod -l app=risk-extract -o jsonpath='{.items[0].status.startTime}' 2>/dev/null; }
local_image_built() { docker image inspect "${BASELINE_IMAGE}" --format '{{.Created}}' 2>/dev/null; }
epoch_of() { python3 -c "import sys,datetime;print(int(datetime.datetime.fromisoformat(sys.argv[1].replace('Z','+00:00')).timestamp()))" "$1" 2>/dev/null || echo 0; }
PRODUCER_AT="$(producer_started)"; IMAGE_AT="$(local_image_built)"
if [[ -n "${PRODUCER_AT}" && -n "${IMAGE_AT}" ]]; then
  if (( $(epoch_of "${IMAGE_AT}") > $(epoch_of "${PRODUCER_AT}") )); then
    echo "[baseline] risk-extract started ${PRODUCER_AT}, older than ${BASELINE_IMAGE} built ${IMAGE_AT}; restarting it"
    ${K} rollout restart deployment/risk-extract >/dev/null
    ${K} rollout status deployment/risk-extract --timeout=600s >/dev/null
  fi
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
    # Seeding after this registers the whole quoted universe -- 68 securities as of 2026-08-22 (20
    # equities, 24 listed options, 19 treasuries/bills/strips/corporates, 5 ETFs). That fits because
    # the two historical builds now carry MAX_SECURITIES=1024 like every current build; the 64-cap
    # originals are :yu15-pre-orig64 / :yu15-stp-orig64 and refuse at the 65th security. The seeder
    # asserts its own total against capacity, so this is checked rather than assumed.
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

    # THE FEED ADAPTER GOES DOWN FOR THE SAME TWO REASONS THE CONTROL FEED DOES, and it is a
    # cluster client on the TIP build while the members are about to go historical -- the very
    # mismatch stp_borrow_gateway exists to work around, one Deployment further along. It also
    # ticks 69 instruments into a log that is supposed to hold only this proof's fixtures. The
    # restore block below scales it back to 1 BEFORE its rebuild_fresh_epoch, so roll_feed_adapter
    # brings it up and asserts it is sequencing rather than leaving that to hope.
    echo "[stp-prep] feed adapter to 0 (tip client, historical members; and this epoch stays minimal)"
    ${K} scale deploy/feed-adapter --replicas=0 >/dev/null 2>&1 || true
    ${K} wait --for=delete pod -l app=feed-adapter --timeout=120s >/dev/null 2>&1

    echo "[stp-prep] control feed off + fresh epoch minted ON ${STP_IMAGE_PRE}"
    ${K} set env deploy/cluster-gateway CONTROL_FEED_SUBSCRIBER=0 >/dev/null
    # WAIT FOR THE SUBSCRIBER TO ACTUALLY BE GONE BEFORE MINTING THE EPOCH. `set env` starts a
    # rollout; it does not finish one. Without this wait the OLD gateway pod -- still
    # CONTROL_FEED_SUBSCRIBER=1 -- is alive while rebuild_fresh_epoch below wipes the PVCs and
    # brings the members up, and it replays the YU04 control feed's 510-security universe straight
    # into the brand-new epoch.
    #
    # It used to be FATAL on the historical builds this prep exists to serve, when MAX_SECURITIES was
    # 64 there against 1024 today: the table was exhausted in the first 13% of the replay and every
    # symbol registration after that was refused with id = -1, surfacing as yu13-stp-and-replace
    # failing its seed with a FAST `422 {"seeded":false}`. Those builds now carry 1024, so a 510-
    # security replay would fit -- but it is still 510 securities of someone else's reference data
    # in an epoch that is supposed to hold only this proof's fixtures, so the subscriber stays off.
    #
    # Measured 2026-08-14. At failure the epoch carried applied=655 on all three members, against
    # ~130 for seed-proof-fixtures alone; the ~510 excess is the universe. Intermittent precisely
    # because it is a race on whether the old pod is still up when the members return.
    ${K} rollout status deploy/cluster-gateway --timeout=300s >/dev/null 2>&1
    rebuild_fresh_epoch "${STP_IMAGE_PRE}" allow-image-change
    # A fresh epoch needs a fresh projection — the engine's counters restart below the trade ids
    # already in SQL, and stp's own preflight (correctly) refuses to run into that. The main heal
    # path clears after its rebuilds; this wrap forgot to, and stp failed in-suite on exactly the
    # guard that exists to catch it.
    # Borrow the gateway so it MATCHES the members for the seeding step, then give it back before
    # the proof runs. Without the match nothing seeds at all -- see stp_borrow_gateway above.
    if stp_borrow_gateway "${STP_IMAGE_PRE}"; then
      # Every gateway rollout tears the forwards down, so re-establish on both sides of the borrow.
      if start_forwards; then
        # STP_PREP_SEEDED gates the per-proof seed below. This epoch can only be seeded through the
        # matched pair borrowed just above, and by the time that generic call runs the gateway is
        # back on the baseline while the members are still historical -- the very mismatch this
        # block exists to work around. Letting it run again does no damage (the epoch is already
        # seeded) but it fails, and reports "runs against a partly-seeded rig" about a rig that is
        # fully seeded. A false alarm in the one place a reader checks for a real one.
        if seed_fixtures fresh; then STP_PREP_SEEDED=1; else
          echo "[warn] the stp epoch is only partly seeded -- see above"
        fi
      else
        echo "[warn] no forwards after borrowing the gateway -- the stp epoch is NOT seeded"
      fi
    else
      echo "[warn] could not borrow the gateway; the stp epoch will NOT be seeded (tip gateway in"
      echo "       front of historical members refuses every /seed)"
    fi
    stp_return_gateway
    start_forwards || { echo "[fail] no forwards after returning the gateway"; break; }
    STP_RESTORE_FEED=1
  fi

  # yu05-recon's forward-sweep verdict reads LIFETIME counters: ReconciliationService's
  # matched/missing/field_mismatch are LongAdders that count classification EVENTS since the
  # process started, not distinct trades. That makes the proof's verdict a function of everything
  # trade-processor watched before it — and this suite deliberately mints fresh epochs (the
  # baseline rebuild above, yu13-stp-and-replace below), after which trade ids restart from 1 and
  # collide with projection rows the previous epoch left behind. A sweep that classifies id "7-S"
  # against last epoch's "7-S" reports FIELD_MISMATCH truthfully and permanently.
  #
  # Observed: the proof failed in two suite runs with mismatching ids that were low-numbered
  # post-epoch ids absent from SQL, while its own authoritative full-history set comparison
  # (engine trades vs SQL rows vs journal provenance, plus a planted orphan probe) was clean in
  # every run — and it passes standalone on a freshly started process every time.
  #
  # Restarting the process zeroes the counters. This weakens no assertion: it removes inherited
  # state the assertion was never measuring, which is the same reason the baseline block above
  # refuses to let a proof inherit an engine build from the run before it.
  if [[ "${p}" == yu05-recon ]]; then
    ${K} rollout restart deployment/trade-processor >/dev/null 2>&1
    ${K} rollout status deployment/trade-processor --timeout=300s >/dev/null 2>&1
    sleep 20   # let the first scheduled sweep run against a settled projection
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
  if [[ "${STP_PREP_SEEDED:-0}" == "1" ]]; then
    echo "  [seed] already seeded by the stp prep through a matched gateway; not re-running"
    STP_PREP_SEEDED=0
  else
    seed_fixtures || echo "[warn] ${p} runs against a partly-seeded rig -- see above"
  fi

  printf "%-34s " "${p}"
  bash "${script}" > "/tmp/proofrun/${p}.log" 2>&1
  case $? in
    0) echo "PASS"; pass=$((pass + 1)); results+=("PASS ${p}") ;;
    # "capability absent" was a cause this line cannot know. It was true of the only skippers that
    # existed when it was written (the yu04/yu05 pair, which skip when reference-data's control
    # snapshot is missing) and became a false statement the moment a proof skipped for a different
    # reason: yu16-accrued-interest skips when the EOD gate holds the session, where the capability
    # is present and working and the PRECONDITION is dirty. The summary line is what gets skimmed,
    # so it was asserting the wrong cause to exactly the readers who never open the log. Render the
    # verdict, let the proof state the reason.
    2) echo "SKIP (see log)"; skip=$((skip + 1)); results+=("SKIP ${p}") ;;
    *) echo "FAIL"; fail=$((fail + 1)); results+=("FAIL ${p}") ;;
  esac

  if [[ "${STP_RESTORE_FEED:-0}" == "1" && "${p}" == yu13-stp-and-replace ]]; then
    # Hand the rig back the way the rest of the suite expects it: the proof's own restore trap
    # returns the image it FOUND, which after the prep above is the historical one. Rebuild the
    # epoch on the baseline build before turning the feed back on, or the next 510-security replay
    # lands on a 64-capacity engine.
    echo "[stp-prep] restoring ${BASELINE_IMAGE} at a fresh epoch, then the feed adapter and control feed"
    ${K} scale deploy/feed-adapter --replicas=1 >/dev/null 2>&1 || true
    rebuild_fresh_epoch "${BASELINE_IMAGE}" allow-image-change
    ${K} set env deploy/cluster-gateway CONTROL_FEED_SUBSCRIBER=1 >/dev/null
    ${K} rollout restart deploy/cluster-gateway >/dev/null
    ${K} rollout status deploy/cluster-gateway --timeout=300s >/dev/null 2>&1
    start_forwards && { seed_fixtures fresh || echo "[warn] restored epoch only partly seeded -- see above"; }
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
