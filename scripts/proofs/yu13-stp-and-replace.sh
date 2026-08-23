#!/usr/bin/env bash
# yu13-stp-and-replace.sh — falsifiable proof of the member bundle: self-trade prevention
# (ADR-057, cancel-oldest) and engine-native atomic replace (ADR-058).
#
# Falsifiable by construction. It runs the SAME two scenarios against the pre-change members first
# and shows the real failures against the real system:
#   * a self-cross BOOKS A WASH TRADE, visible as 2 rows in the MariaDB `trades` table;
#   * POST /replace 404s, because no such ingress exists.
# Then it rolls the members and gateway forward and shows the same two scenarios behave correctly.
#
# The members are rolled with their PVCs INTACT, deliberately, so the before/after runs are against
# the same state machine lineage rather than two different clusters. TWO separate guarantees make
# that safe, and conflating them is how this comment went stale once already:
#
#   * FORMAT IDENTITY across the step under proof. Both pinned builds write SNAPSHOT_FORMAT 3, so
#     the pre -> stp roll is 3 -> 3 and the epoch carries over untouched. This is the original
#     reason and it still holds exactly as written.
#   * A WIPED EPOCH AT BOTH ENDS, which is what keeps the tip's format out of this entirely. The
#     tip is no longer format 3 -- the YU17 layer writes 7 -- so this proof neither runs on a
#     tip-authored epoch nor hands one back. run-proofs.sh's stp prep wipes the PVCs and mints this
#     epoch fresh AT FORMAT 3 on IMAGE_PRE before the proof starts, and its restore wipes again
#     (rebuild_fresh_epoch "${BASELINE_IMAGE}") when it puts the baseline back. No snapshot ever
#     crosses the 3/7 line in either direction.
#
# READER TOLERANCE IS DELIBERATELY NOT RELIED ON, and saying so is the point: the tip carries
# MIN_READABLE_SNAPSHOT_FORMAT = 3 and so COULD restore a format-3 epoch, but nothing in this flow
# exercises that and nothing here should start depending on it. The other direction is not a choice
# at all -- the pinned builds carry no MIN_READABLE field, it postdates them, so their reader is a
# strict equality on 3 and they cannot read a newer epoch under any circumstances. That asymmetry
# is the whole precondition in step 0 below.
#
# Assertion ends, honestly stated:
#   * trades  -> the ENGINE's own trade counter, on ALL THREE members. MariaDB `trades` is reported
#                alongside but NOT asserted: it is a best-effort bridged view (leader-only, NATS,
#                non-blocking offer), and on 2026-07-22 the engine booked 5.4M trades while that
#                table stayed frozen at 939,019 rows. An SQL-only assertion therefore reports "no
#                trade" for trades that definitely happened.
#   * orders  -> the replicated book digest agreed by ALL THREE members.
#   There is NO order read model: `orderbook` holds 0 rows for every order ever submitted, so
#   order-state assertions have no SQL effect end today. That gap is named, not papered over.
#
# Usage: bash scripts/yu15/run-proofs.sh yu13-stp-and-replace
#
# NOT `./yu13-stp-and-replace.sh` against whatever epoch is on the rig, which is what this line used
# to offer. This proof never mints its own epoch -- the runner does, fresh, on IMAGE_PRE -- and
# roll_to rolls with PVCs intact by design, so a tip-authored epoch walks straight into the
# format-3-reader/format-7-snapshot failure described above. Step 0 refuses that rather than
# producing it. Both images must also be present locally.
set -euo pipefail

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
# THE -1k PAIR, NOT THE BARE :yu15-pre / :yu15-stp. Same two builds, with MAX_SECURITIES grafted
# from 64 to 1024 directly onto the compiled MatchingEngineClusteredService -- five inlined use
# sites plus the ConstantValue attribute, nothing else in either image touched. The 64-capacity
# originals are kept as :yu15-pre-orig64 / :yu15-stp-orig64 for provenance and must NOT be used
# here: they cap the whole suite's tradeable universe at 64 securities, which is what kept the
# fixture seeder from enabling bonds and ETFs.
#
# The version boundary this proof exists to cross is UNCHANGED by the graft: the same 15 classes
# differ between the -1k pair as between the originals, because the identical transform was applied
# to both sides. `pre` still has no SELF_TRADE_PREVENTED in RiskReason and no /replace route in the
# gateway; `stp` still has both. A pair rebuilt from today's tree would roll X to X and pass while
# proving nothing -- that is the failure mode this pinning avoids.
IMAGE_PRE="${IMAGE_PRE:-traderx/cluster-node:yu15-pre-1k}"
IMAGE_FIX="${IMAGE_FIX:-traderx/cluster-node:yu15-stp-1k}"
SELF="${SELF:-42422}"      # the account that trades against itself
OTHER="${OTHER:-22214}"    # the genuine counterparty
TICKER="${TICKER:-STP$(date +%H%M%S)}"
PRICE="${PRICE:-100.00}"
QTY=5

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }
# The trade bridge projects into the `database` deploy on the current rigs (eod-price-db carries
# the same schema but only EOD pricing data). Override SQL_DB for a rig wired differently.
# Deployment name and CONTAINER name are not the same thing on every rig: the cluster rig runs
# deploy/eod-price-db whose container is plainly "mariadb", while the state-014 rig ran
# deploy/database with a container of the same name. Assuming they match made `sql` fail with
# "container ... is not valid for pod", which rows() then returned as empty -- and an empty rows()
# is reported by the preflight as "already has trade rows", i.e. the single most misleading
# possible message for a container-name mismatch.
SQL_DB="${SQL_DB:-eod-price-db}"
SQL_CONTAINER="${SQL_CONTAINER:-mariadb}"
sql() { ${K} exec deploy/${SQL_DB} -c ${SQL_CONTAINER} -- mariadb -utraderx -ptraderx traderx -sN -e "$1" 2>&1 \
          | { grep -v "Using a password on the command line" || true; }; }
rows() { sql "SELECT COUNT(*) FROM trades WHERE security='${TICKER}';"; }

members() { ${K} get pods -l app=order-matcher-cluster -o name | sed 's|pod/||' | sort; }

book() { # book <ordinal> -> "<openOrders> <orderHash>"
  ${K} exec "order-matcher-cluster-$1" -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null || curl -s http://localhost:8080/metrics' \
    | awk '/^traderx_book_open_orders/ {d=$2} /^traderx_book_order_hash/ {h=$2} END {print d, h}'
}
# The ENGINE's own trade counter, per member. This is the authoritative booked-trade end: the
# MariaDB `trades` table is a bridged VIEW of it, and the bridge is best-effort (leader-only, NATS,
# non-blocking offer). Measured 2026-07-22: the engine booked 5.4M trades while the table stayed
# frozen at 939,019 rows -- so an SQL-only assertion can report "no trade" for a trade that
# definitely happened. Assert here; confirm in SQL when SQL is keeping up.
trade_count() {
  ${K} exec "order-matcher-cluster-$1" -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
    | awk '/^traderx_cluster_trades/ {print $2}'
}
trades_all() { for m in 0 1 2; do printf "%s " "$(trade_count "${m}")"; done; }

stp_count() {
  ${K} exec "order-matcher-cluster-$1" -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null || curl -s http://localhost:8080/metrics' \
    | awk '/^traderx_stp_cancels/ {print $2}'
}
# All three members must agree. Retried, because they apply the committed tail at slightly
# different times and an immediate sample can catch one mid-apply — which looks exactly like a
# determinism failure and is not one. Persistent disagreement IS the failure.
# 30s was not enough. This is called immediately after roll_to has restarted all three members,
# and a follower replaying a log tens of thousands of sequences deep legitimately trails for a
# while -- observed as member 2 one order ahead of 0 and 1, which converged on its own moments
# later (all three at seq 18254). Failing at 30s reported that transient lag as "members never
# agreed on the book", i.e. as a book divergence, which on a deterministic core is the most
# serious thing this proof could say. It is worth waiting to be sure before saying it.
DIGEST_TIMEOUT_S="${DIGEST_TIMEOUT_S:-180}"
digest_consensus() {
  local b0 b1 b2 i
  for i in $(seq 1 "${DIGEST_TIMEOUT_S}"); do
    b0="$(book 0)"; b1="$(book 1)"; b2="$(book 2)"
    # Agreement means three members agreed on A DIGEST, not on a non-answer. Reads that returned
    # nothing compare equal, so a metrics endpoint that is not answering yet — exactly the case
    # right after roll_to restarts every member — used to satisfy this and print "book agreed at
    # []". That is a vacuous consensus check: it reports agreement strongest at the moment it
    # knows least.
    #
    # `-n` did NOT close that, which is why this is a shape test and not an emptiness test. On no
    # input at all, book()'s `END {print d, h}` still fires with both variables unset and prints a
    # single SPACE (verified: `printf '' | awk ... | od -c` -> ' ' '\n'). `-n " "` is true and
    # " " == " " == " ", so three unreachable members would sail straight through the guard added
    # to stop exactly this and print "book agreed at [ ]" — one character away from the bug it
    # replaced. Established from the helper's behaviour on empty input, not from a captured run;
    # the guard is insufficient either way. Require two integers (the hash is routinely negative)
    # and the no-data value cannot spell agreement in any form.
    if [[ "${b0}" =~ ^[0-9]+\ -?[0-9]+$ && "${b0}" == "${b1}" && "${b1}" == "${b2}" ]]; then
      echo "${b0}"
      return 0
    fi
    sleep 1
  done
  # Print each member's applied sequence alongside the digests. Lagging members show DIFFERENT
  # sequences and are still moving; genuinely diverged members sit at the SAME sequence with
  # different books. Without that the two are indistinguishable in the failure message.
  local seqs=""
  for m in 0 1 2; do
    seqs+="m${m}=$(${K} logs "order-matcher-cluster-${m}" --tail=40 2>/dev/null \
      | grep -oE 'seq=[0-9]+' | tail -1) "
  done
  fail "members never agreed on the book after ${DIGEST_TIMEOUT_S}s: [${b0}] [${b1}] [${b2}] (applied: ${seqs})"
}

# The script owns its port-forward: every gateway rollout tears one down, and a dead tunnel would
# be indistinguishable from "the feature is absent" — the ambiguity that makes a proof worthless.
PF_PID=""
PF_PORT="${MATCHER_URL##*:}"
stop_pf() { if [[ -n "${PF_PID}" ]]; then kill "${PF_PID}" 2>/dev/null || true; wait "${PF_PID}" 2>/dev/null || true; fi; PF_PID=""; }
start_pf() {
  stop_pf
  ${K} port-forward svc/order-matcher "${PF_PORT}:18110" >/dev/null 2>&1 & PF_PID=$!
  local tries=0
  until curl -sf --max-time 5 "${MATCHER_URL}/ready" >/dev/null 2>&1; do
    tries=$((tries + 1)); [[ ${tries} -lt 90 ]] || fail "gateway never became reachable"
    kill -0 "${PF_PID}" 2>/dev/null || { ${K} port-forward svc/order-matcher "${PF_PORT}:18110" >/dev/null 2>&1 & PF_PID=$!; }
    sleep 2
  done
}
# Whatever image the cluster was on before this proof touched it. Restored on EXIT, because a
# failure part-way used to LEAVE the members on this proof's own image -- and that image predates
# the gateway's instrumentOf alias, so every later proof silently broke (children rejected with a
# bare {"kind":2} and no reason). A proof that changes the cluster owes it back.
ORIGINAL_IMAGE="$(${K} get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)"
restore_image() {
  stop_pf
  [[ -n "${ORIGINAL_IMAGE}" ]] || return 0
  local now
  now="$(${K} get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)"
  [[ "${now}" == "${ORIGINAL_IMAGE}" ]] && return 0
  echo "[restore] returning the cluster to ${ORIGINAL_IMAGE}"
  roll_to "${ORIGINAL_IMAGE}" || echo "[warn] could not restore ${ORIGINAL_IMAGE} -- do it before running other proofs"
}

# THE PROBES BELONG TO THE CURRENT BUILD, AND THIS PROOF DEPLOYS OLDER ONES.
#
# The manifest points three probes -- startup and liveness at /live, readiness at /ready -- at the
# gateway's probe port 18111, served by a dedicated single-thread server. Both bundles this proof
# rolls to PREDATE that server: verified 2026-08-14, ClusterGatewayMain.class in yu15-pre and
# yu15-stp contains no /live and no GATEWAY_PROBE_PORT at all, and serves 18110 only. The kubelet
# therefore fails the STARTUP probe and crash-loops a gateway whose only defect is being older than
# the manifest -- and the symptom is `rollout status` timing out, which reads as "slow" rather than
# "incompatible", above a completely clean pod log.
#
# So for as long as this proof owns the deployment it probes the one endpoint every build has
# served: /ready on 18110, exactly what the manifest declared before the probe server existed.
# Startup and liveness are dropped, because on these builds they have no endpoint to ask; readiness
# carries failureThreshold 24 for the reason the manifest's own comment gives -- with no startup
# probe a gateway needs ~2 minutes of slack for JVM + media driver + awaitConnected. Nothing here is
# under proof: this proof asserts on the engine's trade counter, the replicated book digest, and the
# gateway's /replace STATUS CODE, never on a probe verdict.
#
# The same accommodation, for the same reason, is in scripts/proofs/yu13-cancel-ingress.sh -- both
# proofs deliberately deploy a gateway older than the manifest that describes it. Kept as two local
# copies rather than a shared lib because every proof in this directory is standalone and readable
# on its own; if a third one needs it, extract then.
GW_CONTAINER="$(${K} get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].name}' 2>/dev/null)"
GW_ORIGINAL_IMAGE="$(${K} get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)"
GW_ORIGINAL_PROBES="$(${K} get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0]}' 2>/dev/null \
  | python3 -c 'import sys,json;c=json.load(sys.stdin);print(",".join(json.dumps(k)+":"+json.dumps(c.get(k)) for k in ("startupProbe","readinessProbe","livenessProbe")))')"
# initialDelaySeconds MATTERS HERE AND IS NOT DECORATION. YU15's own gateway manifest -- the one
# these historical builds were actually deployed against -- carried `initialDelaySeconds: 5` on this
# probe. Dropping it lets the kubelet probe from t=0 and mark the pod Ready the moment the socket
# answers, which on these builds is `connected:true` -- "my session opened", not "I can commit".
# That is the shallow readiness YU16 replaced for exactly this reason, and reinstating a build that
# predates the fix means reinstating the delay it shipped with.
GW_HISTORICAL_PROBES='"startupProbe":null,"livenessProbe":null,"readinessProbe":{"httpGet":{"path":"/ready","port":18110},"initialDelaySeconds":5,"periodSeconds":5,"failureThreshold":24}'
# The manifest's own form (specs/*/generation/kubernetes/cluster/gateway.yaml). Used ONLY when the
# live capture above is degenerate -- the floor a restore can always reach, never the source of
# truth, which is why the live capture is preferred whenever it is intact.
GW_MANIFEST_PROBES='"startupProbe":{"httpGet":{"path":"/live","port":18111},"periodSeconds":5,"failureThreshold":24},"readinessProbe":{"httpGet":{"path":"/ready","port":18111},"periodSeconds":5,"failureThreshold":3},"livenessProbe":{"httpGet":{"path":"/live","port":18111},"periodSeconds":10,"failureThreshold":6,"timeoutSeconds":3}'
# A CAPTURE OF AN ALREADY-BROKEN DEPLOYMENT IS NOT THE THING TO RESTORE.
#
# A run that dies between the patch and its EXIT trap leaves the probes stripped. Capture THAT as
# "original" and the damage LATCHES: every later run faithfully restores a deployment with no
# startup and no liveness probe, and yu16-liveness-restarts-wedge -- which runs three proofs
# earlier and cannot see who did it -- then fails its preflight with "the gateway Deployment
# carries no livenessProbe on /live". A true statement about a rig the proofs themselves broke.
# Measured 2026-08-14: one aborted hand-run stripped them, and the two full suites after it
# restored them stripped, each one reporting a successful restore.
#
# So a capture missing either probe is read as evidence of that abort rather than as intent. Same
# instinct as a negative control on an assertion: a degenerate reading is not an answer.
if [[ "${GW_ORIGINAL_PROBES}" == *'"startupProbe":null'* \
   || "${GW_ORIGINAL_PROBES}" == *'"livenessProbe":null'* ]]; then
  echo "[warn] the gateway Deployment is already missing a startup or liveness probe, so an earlier"
  echo "[warn] run died before its restore. Restoring the MANIFEST form on exit, not this one."
  GW_ORIGINAL_PROBES="${GW_MANIFEST_PROBES}"
fi
GW_PATCHED=0

patch_gateway() { # patch_gateway <image> <probe-json-fragment>  -- image and probes in one rollout
  GW_PATCHED=1
  ${K} patch deploy cluster-gateway --type=strategic \
    -p "{\"spec\":{\"template\":{\"spec\":{\"containers\":[{\"name\":\"${GW_CONTAINER}\",\"image\":\"$1\",$2}]}}}}" >/dev/null
}

# The gateway is restored to what the GATEWAY was, not to ORIGINAL_IMAGE -- those are different now
# that the runner mints this proof's epoch on IMAGE_PRE without repinning the gateway. Returning it
# to the StatefulSet's original image would leave the rig on a historical gateway and quietly hand
# it to whatever runs next.
restore_gateway() {
  [[ "${GW_PATCHED}" == "1" && -n "${GW_ORIGINAL_IMAGE}" ]] || return 0
  echo "[restore] returning the gateway to ${GW_ORIGINAL_IMAGE} and its manifest probes"
  patch_gateway "${GW_ORIGINAL_IMAGE}" "${GW_ORIGINAL_PROBES}" \
    || { echo "[warn] could not restore the gateway -- repin it before running other proofs"; return 0; }
  GW_PATCHED=0
  ${K} rollout status deploy/cluster-gateway --timeout=300s >/dev/null \
    || echo "[warn] gateway did not settle on ${GW_ORIGINAL_IMAGE} -- check it before running other proofs"
  return 0
}
trap 'restore_image; restore_gateway' EXIT

# A SNAPSHOT BARRIER IS PART OF THE ROLLING PROCEDURE, not a convenience.
#
# Recovery is "latest snapshot + replay the log tail". A rolling restart takes the members down one
# at a time, so the FIRST member back replays the un-snapshotted tail on the NEW build while the
# other two still hold the OLD build's result of the very same events. When those events are
# semantics-sensitive -- and steps 2-3 deliberately put a self-cross in that tail, the one event
# whose meaning this bundle changes -- the members diverge the moment the first one comes back. The
# 60 s periodic snapshot then fires mid-roll, each member writes its OWN state at the shared log
# position, and every later restart restores that member's own divergent snapshot. The divergence
# stops being transient and becomes the epoch.
#
# Measured 2026-08-02 on the kind rig, and this is the whole failure: after roll_to(yu15-stp) the
# members sat at applied=336 with m2 (highest ordinal -> restarted first, replayed the tail under
# STP) reporting trades=6 open=1 while m0/m1 reported trades=8 open=0. Exactly the wash trade, two
# ways. It survived the restore roll back to yu15-pre, because by then each member was loading its
# own snapshot. Waiting longer could never fix it: this is divergence, not lag.
#
# So take the barrier BEFORE the roll. Every member then recovers from a snapshot authored under
# the OLD semantics that already contains the self-cross's effect, and replays only the idle tail
# after it -- which has no semantics-sensitive event in it. Nothing re-adjudicates.
#
# NOTE THE FINDING, which is about the system and not about this script: rolling the deterministic
# core with PVCs intact is only safe across a snapshot barrier. Without one it diverges the cluster
# permanently, silently, and with all three members reporting Ready.
snap_count() { # snap_count <ordinal> -> local snapshots taken by that member since it started
  ${K} exec "order-matcher-cluster-$1" -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
    | awk '/^traderx_cluster_snapshots/ {print $2}'
}
# Deliberately waits for the members' OWN periodic trigger (CLUSTER_SNAPSHOT_INTERVAL_MS, 60 s)
# rather than toggling the control counter from outside: the leader already runs that trigger, so
# there is nothing to detect, nothing to race it with, and nothing new that can fail. A snapshot
# counted AFTER the baseline is read is necessarily at a log position at or past everything this
# proof has submitted, which is the only property the barrier needs.
SNAPSHOT_BARRIER_TIMEOUT_S="${SNAPSHOT_BARRIER_TIMEOUT_S:-150}"
snapshot_barrier() {
  local b0 b1 b2 i
  b0="$(snap_count 0)"; b1="$(snap_count 1)"; b2="$(snap_count 2)"
  [[ -n "${b0}" && -n "${b1}" && -n "${b2}" ]] \
    || fail "cannot read traderx_cluster_snapshots from all three members ([${b0}] [${b1}] [${b2}]) — refusing to roll without a barrier"
  echo "  waiting for a snapshot barrier on all three members (from [${b0} ${b1} ${b2}])"
  for i in $(seq 1 "${SNAPSHOT_BARRIER_TIMEOUT_S}"); do
    if [[ "$(snap_count 0)" -gt "${b0}" && "$(snap_count 1)" -gt "${b1}" && "$(snap_count 2)" -gt "${b2}" ]]; then
      echo "  snapshot barrier taken; the tail this roll replays holds no pre-change event"
      return 0
    fi
    sleep 1
  done
  fail "no snapshot barrier within ${SNAPSHOT_BARRIER_TIMEOUT_S}s (still [$(snap_count 0) $(snap_count 1) $(snap_count 2)]):
  rolling the deterministic core without one diverges the members permanently, so this proof stops
  rather than produce that divergence and report it as a book disagreement."
}

engines_dead() { # true only when NO member's clustered service has applied anything
  local m ea
  for m in 0 1 2; do
    ea="$(${K} exec "order-matcher-cluster-${m}" -- sh -c 'wget -qO- http://localhost:8080/health 2>/dev/null' \
      | sed -n 's/.*"engineApplied":\(-\{0,1\}[0-9]\{1,\}\).*/\1/p')"
    [[ -n "${ea}" && "${ea}" -lt 0 ]] || return 1
  done
  return 0
}

roll_to() { # roll_to <image>   — PVCs intact: the epoch survives, format 3 is unchanged
  local image="$1"
  # Only when the image actually changes. `set image` to the value already there starts no rollout,
  # so no member restarts, so there is no tail for anyone to replay under new semantics -- and the
  # 60 s wait would be pure cost. Step 1 rolls to the image the runner already minted the epoch on
  # and hits exactly this case.
  if [[ "$(${K} get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].image}')" != "${image}" ]]; then
    # A DEAD ENGINE CANNOT TAKE A BARRIER — and that is precisely when the restore path runs.
    # The barrier protects against a mixed-version replay of semantics-sensitive events. If no
    # member's SERVICE has applied anything, nothing was adjudicated under the image being left
    # behind, so there is no divergence for a barrier to prevent — while demanding one guarantees a
    # deadlock in the only situation the restore path exists for. Observed 2026-08-05: a stale
    # IMAGE_PRE fail-closed on the snapshot, every service agent died, the consensus modules stayed
    # up reporting snapshots=[0 0 0], and restore spent its 150s waiting for a counter that could
    # never move — then failed INSIDE the EXIT trap, leaving the cluster on the broken image.
    if engines_dead; then
      echo "  no member's engine has applied anything (engineApplied<0 on all three): nothing was"
      echo "  adjudicated under the outgoing image, so no barrier is needed — or possible. Rolling."
    else
      snapshot_barrier
    fi
  fi
  ${K} set image statefulset/order-matcher-cluster \
    "$(${K} get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].name}')=${image}" >/dev/null
  # THE GATEWAY GOES WITH THEM, and NOT because the members moved -- this proof is about a BUNDLE.
  # Step 3 asserts that `POST /replace` 404s on the PRE-CHANGE GATEWAY, which is a statement about
  # the gateway's build, not the engine's; step 7 needs the new gateway's /replace route. Measured
  # 2026-08-14 by decoupling them: with historical members under a current gateway, step 3 got
  # `504 {"error":"no committed ack"}` -- the route exists, offers the command, and no old member
  # ever acks it. A timeout is NO ANSWER, and no answer is not the refusal the step asserts.
  #
  # Unconditional, deliberately: the runner mints the stp epoch on IMAGE_PRE with the gateway left
  # on the baseline build, so at step 1 the members do not move and the gateway still must.
  patch_gateway "${image}" "${GW_HISTORICAL_PROBES}"
  ${K} rollout status statefulset/order-matcher-cluster --timeout=600s >/dev/null
  ${K} rollout status deployment/cluster-gateway --timeout=600s >/dev/null
  # kubectl's StatefulSet rollout status returned here while member 0 was STILL ON THE OLD IMAGE.
  # For a change in the deterministic core that is not a cosmetic race: a self-cross applied in
  # that window fills on the old member and cancels on the new ones, and the three state machines
  # diverge PERMANENTLY. It happened on 2026-07-22 and this proof caught it. Wait for the fact --
  # every pod running the target image AND ready -- not for the controller's opinion of it.
  local tries=0
  until [[ "$(${K} get pods -l app=order-matcher-cluster \
      -o jsonpath='{range .items[*]}{.spec.containers[0].image}{" "}{.status.containerStatuses[0].ready}{"\n"}{end}' \
      | sort -u | tr -d '\n')" == "${image} true" ]]; do
    tries=$((tries + 1)); [[ ${tries} -lt 120 ]] || fail "members never all reached ${image} and ready"
    sleep 5
  done
  start_pf
  seed
  # And prove the members agree BEFORE any traffic, so a later disagreement cannot be blamed on
  # the step that produced it.
  echo "  members + gateway now on ${image}; book agreed at [$(digest_consensus)]"
}

seed() {
  # Retried: this runs moments after roll_to replaced the gateway, and the OLDER builds this proof
  # deliberately rolls to answer /ready 200 before their cluster session is actually usable -- the
  # first /seed can then time out. That is fixture setup racing an old build's shallow readiness,
  # not the behaviour under proof; run 1 of the suite died exactly here ("seed failed for 42422")
  # and the failure-path restore then diverged the members. Seeding is idempotent, so retrying is
  # safe.
  # Report what it SAW, not only that it wanted something else. "seed failed for 42422 after 5
  # attempts" is true and useless: 000 (no answer -- a dead tunnel or a gateway that is not
  # listening) and 500 (an answer, from a gateway that could not resolve the symbol) are completely
  # different faults and this line could not tell them apart. `-o /dev/null` threw away the only
  # evidence, and `-sf`'s exit code collapsed both into "nonzero".
  local acct try code
  for acct in "${SELF}" "${OTHER}"; do
    for try in 1 2 3 4 5; do
      code="$(curl -s --max-time 20 -o /tmp/yu13-seed-body -w '%{http_code}' \
        -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
        -d "{\"accountId\":${acct},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}" || echo 000)"
      [[ "${code}" == 2* ]] && break
      # Elapsed time is the discriminator and costs nothing to print. A FAST 422 means the ack
      # arrived carrying id=-1 (the engine refused, e.g. MAX_SECURITIES=64 on these builds); a SLOW
      # one means offerAndAwait hit ACK_TIMEOUT_MS with no ack at all. Those are different faults
      # and the code alone cannot tell them apart -- measuring this is what identified the readiness
      # race behind the 422s on 2026-08-14.
      echo "  seed ${acct} attempt ${try}/5 t+${SECONDS}s -> ${code} $(cat /tmp/yu13-seed-body 2>/dev/null)"
      if [[ ${try} -ge 5 ]]; then
        # CAPTURE THE EVIDENCE THIS FAILURE NEEDS, because it is intermittent and hand-sampling it
        # has failed repeatedly: by the time a human reaches the rig the gateway has been rolled and
        # the window is gone. A fast 422 means the ack arrived carrying id = -1, and id = -1 has
        # exactly ONE producer -- MatchingEngineClusteredService's `nextSymbolId >= MAX_SECURITIES`
        # capacity branch. MAX_SECURITIES is 64 on these historical builds against 1024 today, so
        # the live question is whether something filled the table before this seed ran.
        #
        # The applied sequence is the available proxy: this epoch is minted fresh with the control
        # feed off and should carry only seed-proof-fixtures (~130). A reading in the hundreds or
        # thousands means the YU04 feed's 510-security universe reached the log anyway, which is
        # what run-proofs.sh disables the subscriber to prevent and what its own line about
        # "a 510-security replay lands on a 64-capacity engine" warns about.
        local seq_report=""
        for _m in 0 1 2; do
          seq_report+="m${_m}=$(${K} exec "order-matcher-cluster-${_m}" -- \
            sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' 2>/dev/null \
            | awk '/^traderx_cluster_applied/{print $2}') "
        done
        echo "  [evidence] applied sequence at failure: ${seq_report}"
        echo "  [evidence] members=$(${K} get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)"
        echo "  [evidence] gateway=$(${K} get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)"
        echo "  [evidence] CONTROL_FEED_SUBSCRIBER=$(${K} get deploy cluster-gateway -o jsonpath='{range .spec.template.spec.containers[0].env[?(@.name=="CONTROL_FEED_SUBSCRIBER")]}{.value}{end}' 2>/dev/null)"
        echo "  [evidence] a fast 422 (attempts ~5s apart, i.e. only the sleep) means the engine"
        echo "             ANSWERED with id=-1, which is the capacity branch and nothing else."
        fail "seed failed for ${acct} after 5 attempts; last: HTTP ${code} \
$(cat /tmp/yu13-seed-body 2>/dev/null) (000 = no answer at all: the gateway never replied, which is
a different fault from a gateway that replied with an error)"
      fi
      sleep 5
    done
  done
}

order() { # order <account> <side> [price] -> body
  curl -s --max-time 30 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
    -d "{\"accountId\":$1,\"ticker\":\"${TICKER}\",\"side\":\"$2\",\"quantity\":${QTY},\"limitPrice\":${3:-${PRICE}}}"
}
replace() { # replace <orderRef> <qty> <price> -> "<http> <body>"
  local out
  out="$(curl -s --max-time 30 -o /tmp/yu13-rep-body -w '%{http_code}' \
    -X POST "${MATCHER_URL}/replace" -H 'Content-Type: application/json' \
    -d "{\"orderRef\":$1,\"quantity\":$2,\"limitPrice\":$3}")"
  echo "${out} $(cat /tmp/yu13-rep-body)"
}
ref_of() { sed -n 's/.*"orderRef":\([0-9]*\).*/\1/p' <<<"$1"; }

# ---------------------------------------------------------------------------------------------
step "0. preflight"
# THE RUNNER MINTS THIS PROOF'S EPOCH, AND THIS PROOF REFUSES TO RUN WITHOUT IT.
#
# roll_to below rolls the members with their PVCs INTACT. That is only safe onto an epoch the pinned
# builds can actually READ, and their reader is a strict equality on SNAPSHOT_FORMAT 3 (no
# MIN_READABLE field -- see the header). The tip writes 7. Point this proof at a tip-authored epoch
# and the members die inside onSnapshotRecord with "snapshot corrupt: symbol id N" -- a FALSE
# accusation, the snapshot being intact and the reader simply too old -- while the consensus modules
# stay up and the pods stay READY. The rig then looks healthy with every engine dead, and the
# restore path hangs on a snapshot barrier a dead engine can never take.
#
# The members' CURRENT image is the available proxy for "who authored this epoch": run-proofs.sh's
# stp prep wipes the PVCs and mints on IMAGE_PRE, so in the supported path the members are already
# sitting on IMAGE_PRE when this starts.
#
# THIS IS NOT COVERED BY THE STALENESS CHECK BELOW, which compares image BUILD DATES. That heuristic
# was defeated on 2026-08-22, when IMAGE_PRE/IMAGE_FIX were rebuilt to graft MAX_SECURITIES=1024
# onto them: the -1k pair is now NEWER than the deployed tip while its snapshot reader is still
# format-3-only. Build date stopped tracking reader capability the moment those images were touched,
# so the date check can no longer see this hazard and this one has to.
if [[ "${ORIGINAL_IMAGE}" != "${IMAGE_PRE}" && "${ORIGINAL_IMAGE}" != "${IMAGE_FIX}" ]]; then
  fail "the members are on ${ORIGINAL_IMAGE}, so this epoch was not minted on ${IMAGE_PRE}.
  This proof does not mint its own epoch -- the runner does. Run it as:
      bash scripts/yu15/run-proofs.sh yu13-stp-and-replace
  Rolling ${IMAGE_PRE} onto an epoch authored by ${ORIGINAL_IMAGE} puts a strict-equality format-3
  snapshot reader in front of a newer snapshot. That fails as \"snapshot corrupt: symbol id N\" with
  every engine dead and the pods still READY -- a false accusation this refusal exists to prevent."
fi
# kind compares the DOCKER manifest digest against containerd's config digest, so it decides an
# image is "not yet present" every single time and re-copies 194MB into four nodes. On a host where
# three busy-spinning Aeron members already burn ~150-200% CPU each, that load can take longer than
# the whole proof. SKIP_KIND_LOAD=1 when the nodes demonstrably already have both tags.
# PRESENT IS NOT CONTEMPORANEOUS.
# `docker image inspect` passes any tag that exists, including one stranded by an earlier generation
# of the rig -- and build-cluster-image.sh rebuilds the rig's own tag but NOT these two proof-only
# tags, so every rebuild of the rig strands them a little further behind the state on disk.
#
# Rolling the deterministic core BACK to a build older than the state it must recover is not a
# controlled experiment, it is a corrupt-snapshot event. Observed 2026-08-05 on this rig, with
# IMAGE_PRE from 2026-07-22 and the deployed image rebuilt 2026-08-04: loading the barrier snapshot
# the newer build had just written threw, inside onSnapshotRecord,
#     java.lang.IllegalStateException: snapshot corrupt: symbol id 64
# (MAX_SECURITIES is 64, so ids run 0..63) which killed the service agent on all three members with
#     AgentTerminationException: failed to start service=0
# The consensus modules stayed alive and the pods stayed READY, so the rig LOOKED healthy while
# every engine was dead -- and the restore path then hung on a snapshot barrier a dead engine can
# never take. Rebuild both tags from a pre-change tree, or point IMAGE_PRE/IMAGE_FIX at builds that
# are at least as new as what is deployed.
DEPLOYED_CREATED="$(docker image inspect "${ORIGINAL_IMAGE}" --format '{{.Created}}' 2>/dev/null || true)"
STALE_IMAGES=""
for img in "${IMAGE_PRE}" "${IMAGE_FIX}"; do
  IMG_CREATED="$(docker image inspect "${img}" --format '{{.Created}}' 2>/dev/null || true)"
  [[ -n "${DEPLOYED_CREATED}" && -n "${IMG_CREATED}" ]] || continue
  if [[ "${IMG_CREATED}" < "${DEPLOYED_CREATED}" ]]; then
    STALE_IMAGES="${STALE_IMAGES}${STALE_IMAGES:+, }${img} (built ${IMG_CREATED%%T*})"
  fi
done

# A stale pre-change image costs the REGRESSION half, not the whole proof. Steps 5-9 assert what
# the bundle actually claims — STP cancels the oldest rather than booking a wash trade, replace is
# atomic under the same orderRef — and the deployed build CARRIES the bundle, so they can be
# asserted against it directly with no roll at all. Same shape as yu13-cancel-ingress.
#
# What is lost is real and is reported as lost: without a pre-change build, "no wash trade" is not
# contrasted against a run that DID book one. Step 6's two-account falsification arm still runs, so
# the claim is not vacuous — an inert book fails it — but the before/after story is not shown.
SKIP_REGRESSION=0
if [[ -n "${STALE_IMAGES}" ]]; then
  SKIP_REGRESSION=1
fi

# Only now pay for the load — kind re-copies 194MB into four nodes every time (see below), which is
# a minute of work to reach a refusal the check above can reach in a second.
for img in "${IMAGE_PRE}" "${IMAGE_FIX}"; do
  docker image inspect "${img}" >/dev/null 2>&1 || fail "image ${img} not present locally"
  [[ "${SKIP_KIND_LOAD:-0}" == "1" ]] || kind load docker-image "${img}" --name "${CTX#kind-}" >/dev/null
done
${K} get deploy trade-processor >/dev/null 2>&1 || fail "trade-processor is not deployed"
# A WIPED epoch restarts the engine's tradeCounter at 1, while MariaDB still holds every trade any
# earlier epoch ever booked. Trade ids are <tradeSeq>-<side>, so the new epoch's trades collide with
# old rows and trade-processor drops them as "Duplicate trade delivery ignored" -- silently, with
# the cluster reporting success. That is the third instance of the silent-read-model-drop class in
# this project, after the VARCHAR(15) OCC bug and the trades.accountid foreign key. Refuse to run
# rather than assert against a read model that cannot see this epoch.
SQL_MAX_TRADE="$(sql "SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(id,'-',1) AS UNSIGNED)),0) FROM trades;")"
ENGINE_TRADES="$(${K} exec order-matcher-cluster-0 -- \
  sh -c 'wget -qO- http://localhost:8080/metrics' 2>/dev/null | awk '/^traderx_cluster_trades/ {print $2}')"
[[ "${ENGINE_TRADES:-0}" -ge "${SQL_MAX_TRADE:-0}" ]] || fail \
  "engine tradeCounter ${ENGINE_TRADES} < highest trade id already in SQL ${SQL_MAX_TRADE}: this
  epoch's trades would be dropped as duplicates. Run load until the counter passes it, or use an
  epoch that was never wiped." 
[[ "$(${K} get deploy trade-processor -o jsonpath='{.status.readyReplicas}')" == "1" ]] \
  || fail "trade-processor is not READY — no fill can reach SQL, so this proof cannot run"
# Member restart counts are ASSERTED against a preflight baseline, never printed: a real member
# bounce mid-proof would otherwise pass silently as a success.
RESTARTS0="$(${K} get pods -l app=order-matcher-cluster \
  -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')"
echo "[ok] preflight: both images loaded, trade-processor ready, ticker ${TICKER}"

# These two live HERE, not inside step 2 where they were declared, because the skip path below
# reaches step 5 without ever executing step 2 — a skipped run then died on
# "await_trades_agree: command not found", i.e. the proof aborting on its own helper rather than on
# any claim about the system.
#
# Take the baseline only once the three members AGREE on it. These reads happen moments after
# roll_to restarted every member, and sampling each in turn while they are still catching up
# captured [6 12 12] -- a baseline the members never simultaneously held. want = baseline + 2 is
# then a different target per member and cannot all be satisfied, so the proof failed reporting
# [14 14 14] against "want +2 on each of [6 12 12]" -- i.e. it failed at the exact moment all
# three DID agree. The baseline has to be a consistent cut, not three separate snapshots.
await_trades_agree() {
  local t0 t1 t2 tries=0
  while [[ ${tries} -lt 120 ]]; do
    t0="$(trade_count 0)"; t1="$(trade_count 1)"; t2="$(trade_count 2)"
    # Numeric for the same reason the digest is shape-tested: an unreadable counter yields "" on
    # all three, "" == "" == "" is agreement, and every later `-eq` against that baseline evaluates
    # "" as 0 — so "the members did not book a self-trade" would be asserted from no data at all.
    if [[ "${t0}" =~ ^[0-9]+$ && "${t0}" == "${t1}" && "${t1}" == "${t2}" ]]; then
      T0_0="${t0}"; T0_1="${t1}"; T0_2="${t2}"
      return 0
    fi
    tries=$((tries + 1)); sleep 1
  done
  fail "members never agreed on a trade-count baseline: [${t0} ${t1} ${t2}]"
}

# Poll for the effect rather than sleeping a fixed 5s and asserting once. These members were
# restarted moments ago by roll_to and a follower still catching up on the log legitimately reports
# a stale trade count for a beat -- observed as [140 140 140] -> [140 142 142], which is member 0
# lagging, NOT the three disagreeing. A fixed sleep turns that into a false divergence report, the
# most alarming possible way to fail. Wait for the fact, with an explicit timeout.
await_trades() { # await_trades <want-delta>
  local want tries=0 m b ok
  want="$1"
  while [[ ${tries} -lt 60 ]]; do
    ok=1
    for m in 0 1 2; do
      b="T0_${m}"
      [[ "$(trade_count "${m}")" -eq "$(( ${!b} + want ))" ]] || ok=0
    done
    [[ ${ok} -eq 1 ]] && return 0
    tries=$((tries + 1)); sleep 1
  done
  return 1
}

if [[ "${SKIP_REGRESSION}" == "1" ]]; then
echo
echo "=== 1-4. SKIPPED: no pre-change image contemporaneous with the deployed build ==="
echo "[skip] stale: ${STALE_IMAGES}"
echo "[skip] deployed: ${ORIGINAL_IMAGE} (built ${DEPLOYED_CREATED%%T*})"
echo "[skip] build-cluster-image.sh rebuilds the rig's tag but NOT these proof-only tags, so every"
echo "[skip] rig rebuild strands them further behind the state on disk. Rolling the core back to one"
echo "[skip] hands it a snapshot a newer build wrote; it fail-closes and its service agent dies on"
echo "[skip] all three members while the pods stay READY."
echo "[skip]"
echo "[skip] NOT DEMONSTRATED: the pre-change half — that this same self-cross USED to book a wash"
echo "[skip] trade, and that /replace USED to 404. Steps 5-9 below still assert the forward claims"
echo "[skip] against the deployed build, which carries the bundle, and step 6's two-account arm"
echo "[skip] still falsifies an inert book. Rebuild IMAGE_PRE from the current tree with the bundle"
echo "[skip] reverted to get the before/after story back."
# roll_to() is what normally opens the port-forward and seeds this proof's accounts and ticker.
# The skip path never calls it, so a skipped run reached step 5 with no tunnel and died on curl's
# exit 7 ("failed to connect") having printed nothing at all — a connection failure wearing the
# costume of a proof result.
start_pf
seed
echo "  gateway forwarded and fixtures seeded; book agreed at [$(digest_consensus)]"
STP0_0="$(stp_count 0)"; STP0_1="$(stp_count 1)"; STP0_2="$(stp_count 2)"
else
step "1. roll BACK to the pre-change members (${IMAGE_PRE})"
roll_to "${IMAGE_PRE}"
[[ "$(rows)" == "0" ]] || fail "${TICKER} already has trade rows; pick a fresh ticker"

step "2. on the pre-change engine a self-cross BOOKS A WASH TRADE"
BEFORE="$(digest_consensus)"
await_trades_agree
ROWS0="$(rows)"
SELF_SELL="$(order "${SELF}" Sell)"; echo "  ${SELF} sell -> ${SELF_SELL}"
SELF_BUY="$(order "${SELF}" Buy)";   echo "  ${SELF} buy  -> ${SELF_BUY}"
await_trades 2 \
  || fail "members did not all book the 2-sided wash trade within 60s: [$(trades_all)] (want +2 on each of [${T0_0} ${T0_1} ${T0_2}])"
echo "  engine trades: [${T0_0} ${T0_1} ${T0_2}] -> [$(trades_all)]  (a self-trade books BOTH sides)"
echo "  book: ${BEFORE} -> $(digest_consensus)"
# Secondary, and explicitly secondary: the bridged read model. Reported, not asserted, because the
# bridge is best-effort by design and its state is not evidence about the engine.
PRE_ROWS="$(rows)"
echo "  MariaDB trades rows for ${TICKER}: ${ROWS0} -> ${PRE_ROWS}$( \
  [[ "${PRE_ROWS}" == "2" ]] && echo "   (read model agrees)" \
                             || echo "   (READ MODEL LAGGING OR DOWN -- not evidence either way)")"

step "3. on the pre-change gateway /replace does not exist"
PRE_REPLACE="$(replace "$(ref_of "${SELF_BUY}")" 9 "${PRICE}")"
echo "  POST /replace -> ${PRE_REPLACE}"
[[ "${PRE_REPLACE}" == 404* ]] || fail "expected 404 from the pre-change gateway, got ${PRE_REPLACE}"

step "4. roll FORWARD to the member bundle (${IMAGE_FIX})"
roll_to "${IMAGE_FIX}"
STP0_0="$(stp_count 0)"; STP0_1="$(stp_count 1)"; STP0_2="$(stp_count 2)"
fi

step "5. the SAME self-cross now books nothing and cancels the resting order instead"
await_trades_agree   # a consistent cut, not three separate snapshots — see step 2
BEFORE="$(digest_consensus)"
SELF_SELL2="$(order "${SELF}" Sell)"; echo "  ${SELF} sell -> ${SELF_SELL2}"
MID="$(digest_consensus)"; echo "  book with the sell resting: ${MID}"
SELF_BUY2="$(order "${SELF}" Buy)";   echo "  ${SELF} buy  -> ${SELF_BUY2}"
sleep 5
AFTER="$(digest_consensus)"
echo "  engine trades: [${T0_0} ${T0_1} ${T0_2}] -> [$(trades_all)]   (must not move)"
echo "  book after:  ${AFTER}"
for m in 0 1 2; do
  b="T0_${m}"
  [[ "$(trade_count "${m}")" -eq "${!b}" ]] || fail "member ${m} booked a self-trade under STP"
done
# The self sell left the book (STP-cancelled); the self buy took its place, so depth is unchanged
# from "sell resting" — but the CONTENT hash must differ, or nothing actually happened.
[[ "${AFTER}" != "${MID}" ]] || fail "the book is byte-identical: the STP cancel did not happen"
for m in 0 1 2; do
  before_var="STP0_${m}"
  [[ "$(stp_count "${m}")" -gt "${!before_var}" ]] \
    || fail "member ${m} recorded no STP cancel — the three members did not all apply it"
done
echo "  traderx_stp_cancels advanced on all three members"

step "6. falsification arm: the identical economics from TWO accounts still fill"
await_trades_agree   # a consistent cut, not three separate snapshots — see step 2
order "${OTHER}" Sell >/dev/null
order "${SELF}" Buy >/dev/null
sleep 5
echo "  engine trades: [${T0_0} ${T0_1} ${T0_2}] -> [$(trades_all)]"
for m in 0 1 2; do
  b="T0_${m}"
  [[ "$(trade_count "${m}")" -eq "$(( ${!b} + 2 ))" ]] \
    || fail "a genuine two-account cross did not book on member ${m}: step 5 proves nothing"
done

step "7. atomic replace takes effect, under the SAME orderRef"
REST="$(order "${OTHER}" Sell "$(python3 -c "print(${PRICE} + 5)")")"
REF="$(ref_of "${REST}")"; echo "  resting sell ref=${REF} @ $(python3 -c "print(${PRICE} + 5)")"
BEFORE="$(digest_consensus)"
REP="$(replace "${REF}" 9 "$(python3 -c "print(${PRICE} + 3)")")"
echo "  POST /replace (qty 5->9, px +5 -> +3) -> ${REP}"
[[ "${REP}" == 200* ]] || fail "replace was not accepted: ${REP}"
[[ "${REP}" == *"\"orderRef\":${REF}"* ]] || fail "replace minted a new orderRef; identity was not preserved"
AFTER="$(digest_consensus)"
echo "  book: ${BEFORE} -> ${AFTER}"
[[ "${BEFORE}" != "${AFTER}" ]] || fail "the replace changed nothing on the members"
[[ "${BEFORE%% *}" == "${AFTER%% *}" ]] \
  || fail "depth changed: a replace must be one order in and one order out, not two orders"

step "8. a REJECTED replace leaves the order untouched — the atomicity claim"
BEFORE="$(digest_consensus)"
REJ="$(replace "${REF}" 9 "$(python3 -c "print(${PRICE} + 500)")")"   # far outside the price band
echo "  POST /replace to an out-of-band price -> ${REJ}"
[[ "${REJ}" == 422* ]] || fail "expected 422 for a rejected replace, got ${REJ}"
[[ "${REJ}" == *PRICE_COLLAR* ]] || fail "expected the reason PRICE_COLLAR in the body: ${REJ}"
AFTER="$(digest_consensus)"
echo "  book: ${BEFORE} -> ${AFTER}"
[[ "${BEFORE}" == "${AFTER}" ]] \
  || fail "a REJECTED replace changed the book — the client's order was not left intact"
# ...and it is still tradeable at the price the accepted replace moved it to.
await_trades_agree   # a consistent cut, not three separate snapshots — see step 2
order "${SELF}" Buy "$(python3 -c "print(${PRICE} + 3)")" >/dev/null
sleep 5
echo "  engine trades: [${T0_0} ${T0_1} ${T0_2}] -> [$(trades_all)]   (the survived order fills)"
for m in 0 1 2; do
  b="T0_${m}"
  [[ "$(trade_count "${m}")" -eq "$(( ${!b} + 2 ))" ]] \
    || fail "the order that survived the rejected replace could not be traded (member ${m})"
done

step "9. no member bounced during the proof"
RESTARTS1="$(${K} get pods -l app=order-matcher-cluster \
  -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')"
[[ "${RESTARTS0}" == "${RESTARTS1}" ]] \
  || fail "a member restarted mid-proof (${RESTARTS0} -> ${RESTARTS1}); results are not trustworthy"

echo
# The verdict must not claim the half that was skipped. "Proven against the pre-change failure" on
# a run that never rolled to a pre-change image is the same defect yu13-cancel-ingress carried: an
# [ok] line asserting the opposite of what the run did.
if [[ "${SKIP_REGRESSION}" == "1" ]]; then
echo "[PASS] self-trade prevention and atomic replace, asserted against the DEPLOYED build."
echo "       NOT proven here: the pre-change contrast (steps 1-4 skipped — reason printed above)."
echo "       Step 6's two-account arm did run, so an inert book would have failed this."
else
echo "[PASS] self-trade prevention and atomic replace, proven against the pre-change failure."
fi
echo "       Known limitation: order state has no SQL effect end (the orderbook table holds 0 rows"
echo "       for every order ever submitted), so order assertions are against the three members'"
echo "       agreed book digest. Trade assertions are in MariaDB."
