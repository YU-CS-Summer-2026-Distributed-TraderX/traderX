# Full YU11 codeX engagement recap — 2026-07-17

## Purpose and bottom line

This is the durable record of the codeX YU11 engagement: the approximately six-hour
pre-MDC implementation/runtime session and the approximately one-hour unicast-MDC pivot. It is
weighted toward evidence that cannot be reconstructed from the final diff: failed designs,
superseded measurements, runtime symptoms, and the reasons work stopped.

The honest conclusion is **no-go / incomplete**:

- A fresh two-pod MDC pair can authenticate, merge an empty/near-empty Archive tail, become ready,
  and accept an order.
- The generated Java suites, inherited allocation gates, Aeron allocation gate, and inherited
  Epsilon no-GC gates pass.
- A primary can promote after its peer is deleted.
- A replacement follower joining a nonempty, newly promoted primary faults immediately on an
  initial sequence gap. There is no proven snapshot/bootstrap path around that failure.
- The default failover acceptance contract was not proved. The only timed deletion was graceful
  and took 9,502 ms to expose the alternate primary label; the force-delete version was committed
  afterward but never completed a timed run.
- No Aeron throughput or percentile-latency benchmark was completed. The go/no-go therefore lacks
  the main performance evidence YU11 was created to produce.

The complete raw measurement record is
[`scripts/bench/results/yu11-transport-2026-07-17.md`](../../scripts/bench/results/yu11-transport-2026-07-17.md).
It includes parameters, environment, provenance, confidence, failed allocation diagnostics, and
the explicit list of unmeasured quantities.

## Workspace and commits

- Worktree: `/Users/yaakov/dev/lmax/traderX-YU11-aeron-replication`
- Branch: `YU11-aeron-replication`
- Parent: `YU10-fix-ingress`
- No push was performed.
- GKE was not touched by codeX.
- The dedicated kind cluster was deliberately left running in its last state.

Last observed—not re-queried or mutated while writing this recap—`order-matcher-1` was the
promoted primary and fully ready, while replacement `order-matcher-0` had its sidecar ready but
the application unready after `FAULT_GAP=3`. That is the cluster state this handoff intentionally
leaves in place.

| Commit | Local time | Purpose | Result |
|---|---|---|---|
| `d2c42a9` | 2026-07-16 17:32 EDT | Scaffold YU11 state/spec pack | Created state and lineage |
| `026bb9b` | 17:50 | Aeron/SBE transport core | Embedded transport and codecs |
| `0f77083` | 18:07 | Archive sidecar/runtime profiles | Compose/kind/GKE render surfaces |
| `db61764` | 19:44 | Authenticated replay/shadow layer | Controlled replay proof; later found insufficient under kind timing |
| `2f1f8ca` | 23:11 | Required pre-pivot checkpoint | Preserved FastWitness/test, Aeron allocation gate, NetworkPolicy, `publishNotReadyAddresses`, launcher ordering, and the split-publication recovery machinery |
| `b9e7244` | 2026-07-17 00:03 EDT | Unicast MDC pivot | Replaced two data publications with one manual-MDC publication and deleted compensating replay machinery |

The protected checkpoint was made before the pivot, as requested. The pivot therefore remains
reviewable as a focused 182-insertion/180-deletion commit rather than erasing the failed approach
from history.

## Measurement summary

The values below are only an index. Use the raw results file for per-run tables and caveats.

- File-NATS local Phase-0 HA: 10,464 events/s before its allocation fix; 10,561 events/s after.
- File-NATS durable proxy: 10,242 events/s, 3.0% below the post-fix on-ring mean.
- File-NATS allocation: 1,669 → 1,589 B/event; repeat 1,591; retained budget 1,620 B/event.
- Externally supplied GKE baseline: single BLP 21,770 / 15,429 / 10,505 (mean 15,901 booked/s);
  File-NATS HA 10,100 / 9,290 / 3,651 (mean 7,680).
- YU11 Aeron allocation diagnostics: 272 B primary, 11,424 B follower, 656 B primary, and 208 B
  primary in different failed warm-up/compiler profiles. No-TLAB JFR showed the persistent
  counters were late compiler rematerialization rather than repeating hot-loop allocation.
- Retained Aeron gate: 250,000-event minimum warm-up and two consecutive exact-zero
  1,000,000-event windows on both transport threads; two fresh runs passed in 12 s each.
- Final inherited Epsilon gates: 3,000,000 measured events each for base and risk paths; passed in
  26 s. The Aeron gate is separate and is not part of `noGcTest`.
- Initial MDC catch-up: 881 ms from catch-up start to follower-ready on one constrained kind run.
- Graceful deletion: 9,502 ms to observe alternate primary label, failing the 3,000 ms script gate.
- Replacement follower: began catch-up at epoch 36 with primary input sequence 1,464 and recording
  position 41,952, then faulted 645 ms later with `FAULT_GAP=3`.
- **Aeron throughput and ACK latency:** not measured. No events/s, booked/s, p50, p95, or p99
  Aeron result exists.

## Pre-MDC chronology and dead ends

### The discarded working MDC prototype

Before the long failure loop, one manual-MDC/local-retention prototype actually passed a controlled
offline replay: Archive retained business sequences 0–3, the follower restarted against recording
position 384, replayed three offline orders, became ready, and accepted a later live order. It was
discarded because the handoff said “multicast/MDC” was deferred. That constraint was later
corrected: IP multicast was deferred, but Aeron unicast MDC was permitted. The implementation
discipline was reasonable, but the technical inference that MDC implied IP multicast was wrong.
The key process lesson is to challenge a constraint that excludes the idiomatic primitive when the
categories appear conflated.

### Setup defect: outbound spy recording did not preserve degraded-solo data

The first Archive arrangement recorded the outbound UDP publication via a spy-like/local recording.
It targeted degraded-solo retention, but with the peer sidecar absent the publication position
stayed at 96 while the authoritative business input sequence advanced to 3. The publication was
not connected, so there was nothing complete to record. This taught that local retention must be a
real connected destination; observing an outbound publication is not enough to make it connected.

### 1. Force two publications to share one session ID

The handoff-compliant replacement used one IPC publication to the local Archive and a second UDP
publication to the peer, forcing the UDP leg to reuse the Archive leg's `sessionId`. It targeted
the need for IPC-local durability plus a stream that `ReplayMerge` could join. A controlled local
loss/replay test passed, which produced false confidence. In kind, one leg accepted sequence 0
while the other returned `NOT_CONNECTED`; sequence 1 then arrived live and the follower correctly
faulted on the missing sequence. A shared session ID does not create a shared Aeron position.
This was the fundamental wrong turn; every later fix tried to preserve an invariant the structure
did not provide.

### 2. Pre-ring connection barrier

`awaitConnected` was moved into transport initialization, outside the Disruptor hot path, so the
engine would not admit sequence 0 before both Archive and live publications had images. It targeted
the observed startup gap and did eliminate one pre-failover occurrence. It failed as a general
solution because connectivity can change after startup, promotion intentionally occurs with the
peer absent, and the two publications still advance independently. The lesson was that a timing
barrier narrows the race but cannot make independent streams identical.

### 3. Split startup and promotion barriers

Startup was made to wait for both Archive and peer-live, while promotion waited only for the local
Archive. This targeted a separate 10-second failover stall where role publication blocked on the
dead peer's live connection. It removed that obviously wrong promotion dependency, but also made
the core divergence explicit: the new primary could archive events while the live leg was absent.
The lesson was that degraded-solo and split publications are incompatible with replay-to-live
position identity unless another durable bootstrap protocol bridges the gap.

### 4. Bounded live-publication retry/backpressure

The primary's UDP leg was changed from a one-shot claim/drop to bounded retry, polling ACKs and
spinning until the configured timeout; strict mode threw, while degraded-solo recorded the
failure and continued. It targeted transient `NOT_CONNECTED`, `BACK_PRESSURED`, and
`ADMIN_ACTION` results without silently losing a sequence. It still failed because any expired
deadline under degraded-solo advanced the Archive but not live. A retry policy can reduce
divergence frequency, not remove the permanent position mismatch after one asymmetric result.

### 5. Gap-triggered replay/re-merge

Instead of terminally faulting when the live follower saw a gap, the follower revoked readiness,
set `replayRequested`, synthesized a resume checkpoint from its last good sequence/position, and
started another Archive replay. It targeted the exact “sequence 0 archived, sequence 1 live”
symptom. The first implementation entered a loop of catch-up restarts and exposed Archive replay
publication lifecycle races. More fundamentally, its replay target came from the Archive
publication's position while its join target was the independent live publication's position.
Recovery machinery cannot reconcile position spaces that never represented the same stream.

### 6. Close old merge and wait one second before replay replacement

The next fix closed the prior `ReplayMerge`, waited one second for the Archive conductor to stop
the retained-session replay publication asynchronously, and only then requested a replacement.
It targeted `clashing sessionId` failures. The wait reduced one registration race, but closing the
old merged subscription also removed the established live receiver; the primary disconnected, and
the replay then chased a continuously advancing Archive without a live image it could join.
The lesson was that lifecycle delay was not the missing invariant and introduced availability
coupling of its own.

### 7. Preserve the old live receiver while registering the replacement

For an already merged stream, the code created the replacement replay first, waited 100 ms, swapped
it in, and only then closed the previous merge. This targeted the disconnection caused by fix 6.
For an active, not-yet-merged replay it retained the one-second close/wait path. It avoided one
tail-chasing trigger, but could still collide on the retained session and still compared unrelated
Archive/live positions. More subscriptions and handoff states made the failure harder to reason
about without changing its cause.

### 8. Stabilize the replay target and stop restarting a merged receiver

Heartbeat state was published/sampled as a stable `(inputSeq, recordingPosition)` pair, and the
follower stopped restarting a successfully merged receiver merely because the heartbeat
high-watermark arrived before the matching data fragment. This targeted torn watermark reads and
a legitimate heartbeat-before-fragment race. It was a sound local correction, but not a solution
to the split streams. The decisive run remained in `ReplayMerge{state=ATTEMPT_LIVE_JOIN}` while
the observed replay position moved from 3,456 to roughly 25,536 over about 118 seconds. The replay
was progressing, but its live join could never converge on the Archive's independent position
space.

These eight compensating fixes were removed or collapsed by the MDC pivot. The stable heartbeat
sampling remains useful independent of the split-publication mistake.

## The MDC pivot

### Structural change

The pivot made the Archive and peer follower destinations of **one**
`aeron:udp?control-mode=manual` `ExclusivePublication`. The application adds:

- a local UDP loopback destination on port 40127, recorded by the sidecar; and
- the peer follower's UDP destination on port 40123.

One `tryClaim`, one SBE encode, and one commit now fan out the same fragment. Session ID, Aeron
position, term progression, and fragment boundaries are structurally shared. A focused
`AeronMdcReplicationTest` proves the Archive receiver's session ID equals the publication session
ID and its observed position equals the publication position.

The accepted trade is that local Archive retention uses UDP loopback rather than IPC. That may
cost throughput, but correctness comes first and the cost was never measured.

### Machinery deleted and why it became unnecessary

- `livePublication`, `liveClaim`, and the second encode/commit: fan-out happens inside one MDC
  publication.
- `withSessionId`: session identity is naturally shared because only one publication exists.
- Per-live-leg bounded retry and `onLiveOfferFailure`: there is no separately advancing live
  claim. The single publication's result applies to the one logical stream.
- Separate startup-versus-promotion Archive/live barriers: connection of the local retention
  destination is the required baseline; the peer may be absent in degraded-solo.
- `replayRequested`, `restartArchiveReplay`, synthesized resume checkpoints, catch-up callbacks,
  one-second teardown waits, and 100 ms old/new receiver overlap: ordinary `ReplayMerge` now joins
  a recording of the actual live stream, so application-level repair of split positions is
  unnecessary.
- `followerCatchingUp` callback plumbing used only by those restarts: initial catch-up already owns
  readiness.

### What the pivot improved

- The first clean pair authenticated and completed Archive replay-to-live merge.
- Catch-up started at 03:50:23.084 and follower readiness was signalled at 03:50:23.965.
- Both pods reached Ready and a real pre-failover order was accepted.
- Unit and allocation verification remained green.

### What it made worse or left unresolved

- Local Archive traffic is now loopback UDP rather than IPC; its performance cost is unknown.
- Manual MDC destinations are static. DNS/pod-IP change behavior was not proved.
- The Archive only records from the current publication's beginning. A newly promoted primary
  starts a new publication/recording at a later business sequence.
- Snapshot-bundle transfer, minimum-checkpoint retention, disk-full behavior, Archive corruption,
  empty-PVC bootstrap, and cross-epoch checkpoint translation remain unimplemented/unproved.
- The replacement follower fault proves that a correct position space inside one publication is
  necessary but not sufficient for HA continuity across a leader epoch.
- No benchmark was run after the pivot.

## Sidecar reuse and launcher image ordering

The clean sidecar Docker rebuild was cancelled after it starved under Docker Desktop/cluster load.
That reuse was technically bounded: executable sidecar behavior did not change in the pivot; the
source change adjusted a comment/default while the manifest explicitly supplied the new inbound
recording channel. The already-tested sidecar image was therefore reused by exact local image ID
and immutable registry tag.

The checked-in launcher does satisfy the intended safety property:

1. It creates one UTC `kind-<timestamp>` tag.
2. It tags and pushes both the matcher and sidecar under that immutable tag.
3. It applies the StatefulSet, scales it to zero, waits for both old pods to disappear, then deletes
   the stale Lease.
4. It assigns **both** immutable registry tags in one `kubectl set image` call.
5. Only then does it scale the paired StatefulSet to two replicas.

That ordering prevents a production-tag pod from racing ahead of image selection and keeps a
reused sidecar paired with an explicitly selected matcher.

One caveat belongs in the record: the actual final MDC rollout could not complete the matcher's
OCI-index push through Docker Desktop. The matcher image was loaded directly into all kind nodes
and referenced by the mutable local name
`traderx/order-matcher:yu11-aeron-replication`, although its exact image ID was recorded. The
sidecar used the immutable `kind-20260717024618` registry tag. Thus the launcher is correct, but
the surgical diagnostic rollout did not exercise its two-immutable-tag path end to end.

## Why a checkpoint-less follower requires `inputSeq == 0`

The condition in `AeronReplicationFollower.onFragment` is deliberate fail-closed behavior:

```java
if (lastInputSeq < 0 && archiveConfig != null && inputSeq != 0L) {
    fault(FAULT_GAP);
    return;
}
```

`lastInputSeq < 0` means the follower has no logical replication checkpoint from which it can
prove continuity. Accepting an arbitrary live or replay fragment at sequence 1,464 would make the
follower look healthy while silently omitting all earlier state. Requiring zero is therefore a
valid integrity invariant for the only bootstrap path currently implemented: replay the complete
business sequence from its beginning.

It is also a marker for missing design. The specified cold-start path was “install a checksummed
snapshot bundle with its logical sequence/Archive position, then replay the tail.” That transfer
was never implemented. Once a validated snapshot establishes a nonzero logical checkpoint, the
follower should start from that checkpoint rather than demand zero. So the check is not a random
placeholder, but the system around it is incomplete: it currently has no safe way to establish a
nonzero starting point after an epoch change.

## Does Archive retain from position zero?

There is no YU11 retention manager. The sidecar does not call Archive trim, purge, truncate,
rotation, or deletion APIs. Existing recording segments persist on the pod PVC until an external
action or capacity failure. That is not the same as guaranteeing that the recording selected for a
new follower begins at business sequence zero:

- `AeronArchiveReplayMerge` selects the newest matching recording when there is no checkpoint.
- It starts at that recording's `startPosition`.
- A promoted primary creates a new Aeron publication and a new recording. Its Aeron position can
  start at zero while its first **business** `inputSeq` is already 1,464.
- Old recordings may remain on PVCs, but selection is per remote primary Archive and newest
  recording. The previous primary's older history is on a different pod/PVC and is not transferred.

Therefore the Archive preserves each recording from its own beginning, but YU11 does **not**
guarantee a single retained recording that covers business sequence zero through the current
leader epoch. It also has no minimum-follower-checkpoint purge policy or disk watermark.

## What happened at primary sequence 1,464

The replacement pod replayed 1,498 events from its local business journal and reconstructed its
local engine state. That does not establish the Aeron follower's logical checkpoint. Its persisted
Aeron checkpoint was unusable for the negotiated epoch, and the follower explicitly discards a
checkpoint whose epoch does not equal the new leader epoch:

- negotiated epoch: 36;
- usable checkpoint: none (`checkpointInputSeq=-1`);
- advertised primary input sequence: 1,464;
- advertised primary recording position: 41,952;
- follower `lastInputSeq`: -1;
- fault 645 ms after catch-up start: `FAULT_GAP=3`.

The evidence says it **did replay from the selected Archive recording's beginning**, not that it
skipped replay and merged straight to live. With no checkpoint, `AeronArchiveReplayMerge` chooses
the selected recording's `startPosition`. The selected newest recording belonged to the newly
promoted primary and began with a nonzero business sequence. Its first replayed business fragment
therefore tripped the initial-sequence invariant before a live merge could matter.

This distinction is decisive: the observed failure is not primarily “ReplayMerge failed to wire
replay before live.” The replay source itself lacked the earlier logical history. The durable fix
is snapshot/checkpoint transfer or another cross-epoch history handoff, plus a retention contract;
changing the merge wiring alone cannot manufacture sequences 0–1,463.

## Graceful-shutdown Lease gap — YU02 owner finding

`LeaderElection.stop()` shuts down heartbeat and Lease schedulers and tears down the heartbeat
dispatcher. It does **not** release, shorten, or transfer `order-matcher-leader`. A gracefully
terminating primary therefore leaves the standby waiting for:

- Kubernetes to make the old pod observably gone; or
- the terminating-pod safety path; or
- Lease expiry under the configured duration.

That creates a routine stall on rolling updates and node drains even when the terminating process
could voluntarily release leadership. The 9,502 ms local deletion result is consistent with that
gap. This is a **YU02-layer finding for later owner propagation** and was intentionally not fixed
in YU11.

The “3-second gate” is not `TERMINATING_GUARD_NS`. The constant is exactly 1,000,000,000 ns
(1 second). Three seconds is the YU11 ship/test threshold: after a healthy-follower primary kill,
the alternate should become primary within 3,000 ms, with the higher-level spec ultimately caring
about accepting traffic. The current test measures appearance of the alternate `blp-role=primary`
label, so even a passing 3,000 ms sample would still be a proxy rather than a complete
request-acceptance measurement.

The failed 9,502 ms run used ordinary graceful pod deletion. The script was changed during the MDC
pivot to force deletion (`--grace-period=0 --force --wait=false`) to model a crash. No completed
force-delete timing followed. Do not describe 9,502 ms as a force-delete result.

## Other findings and deferred work

### Runtime and harness findings fixed in YU11

- Kubernetes port names are limited to 15 characters; `archive-response` was rejected before pod
  creation and became `archive-resp`.
- The dedicated launcher initially omitted mandatory YU09 Secrets and inherited service images.
- A headless Service must set `publishNotReadyAddresses: true`; otherwise ordinal peer DNS is
  unavailable until readiness, while readiness itself depends on peer Aeron connection.
- The Aeron NetworkPolicy initially blocked risk-bootstrap HTTP ports.
- Allowing Kubernetes Service port 443 was insufficient in kind; NetworkPolicy sees the
  post-DNAT control-plane endpoint on TCP/6443 too.
- The launcher originally let the StatefulSet create pods with production image names before
  `set image`, causing a rollout deadlock. Staging at zero fixed the race.
- The previous Lease must only be deleted after both matcher pods are stopped. Deleting it while
  the old holder runs lets that holder immediately recreate it.
- A fast-witness winner must remain admission-authoritative while asynchronous Lease
  reconciliation retries an ambiguous/409 result; otherwise the witness winner can immediately
  demote on stale Lease state.

### Environment/harness dead ends

- Docker Desktop image export and `kind load` ran at only a few MB per minute for the large
  inherited JVM images. Attempts included one-by-one load, one combined archive, copying from the
  shared kind node's containerd, direct `docker save` to target containerd, and finally a local
  registry. The registry path was the first practical repeatable route.
- A clean in-container Gradle image build stalled, while the identical host `bootJar` finished in
  10 seconds. The launcher now builds the jar on the host and uses a runtime-only kind Dockerfile.
- One manual Docker invocation omitted the required `JAR_FILE` build argument, copied a directory
  to `/opt/app/app.jar`, and produced an invalid runtime image. This was a harness error, not a
  transport result.
- Docker Desktop later stalled on the matcher's OCI-index/attestation push even after the
  application layer uploaded. Direct `kind load` of the exact matcher image was used for the final
  diagnostic rollout.

### Resource pressure

The dedicated Docker VM was severely resource constrained. Simultaneous JVM startup/image work
caused Kubernetes API and NATS timeouts; controller-manager/scheduler restarts were observed.
Unrelated UI, post-trade, algo, tick-store, and observability workloads were scaled to zero in the
dedicated YU11 cluster to protect the control plane. One Spring startup took 129.361 seconds.
These numbers are environment evidence, not application performance.

### Functional gaps not chased

- Automatic checksummed snapshot-bundle transfer for empty or epoch-mismatched followers.
- Cross-epoch checkpoint translation and a rule relating local journal recovery to Aeron logical
  sequence.
- Archive retention behind the minimum follower checkpoint, free-space watermark, corruption,
  disk-full behavior, and recording selection across leader epochs.
- DNS/pod-IP replacement after MDC destinations are installed.
- Induced 1%/5% loss, asymmetric partition, sidecar kill, Archive kill, empty PVC, or schema N/N-1
  runtime proofs.
- Fast-witness kind/Compose timing. Only focused correctness tests exist; 30–60 ms was not measured.
- Strict/durable runtime behavior under a real peer loss.
- Metrics/dashboards sufficient to distinguish retransmit, replay lag, Archive disk pressure, and
  election delay.
- Aeron transport benchmark harness and all three-run comparisons.
- GKE A/B/A. The externally supplied GKE baseline exposed that the historical 35k absolute gate
  is invalid on the current risk-screened basis, but no YU11 cloud run occurred.

### Findings outside the immediate YU11 transport scope

- The inherited journal archiver warned that absent GCS HMAC credentials cause rotated segments to
  accumulate locally. The optional Secret remained intentionally absent in kind.
- EOD CronJob failures were visible in inherited full-stack runs and were not investigated; the
  dedicated launcher suspends the job for YU11 testing.
- The generated `implementation-status.md` is stale: it still says several now-implemented items
  are pending and does not capture the final broken rejoin state. Treat this recap as the current
  operational truth.

## Honest current state

### Works

- State generation and owner-layer rendering.
- SBE encode/decode vectors and core Aeron primary/follower IPC tests.
- Signed peer authentication/heartbeat focused tests.
- Exact post-force journal watermark wiring and ACK unit proofs.
- FastWitness compare-and-set/fencing focused tests.
- One manual-MDC publication delivering the same session/position to Archive and follower in a
  focused test.
- Final generated order-matcher test suite, including base/risk/Aeron allocation tasks.
- Inherited base/risk Epsilon no-GC gates.
- Sidecar unit/install distribution verification.
- A clean, initially empty/near-empty two-pod kind pair reaching Ready and accepting one order.
- Promotion of the surviving follower after graceful deletion, albeit outside the 3-second gate.

### Unproven

- Force-delete crash failover time.
- Accepted traffic after promotion.
- Replacement follower rejoin after any nontrivial primary history.
- A second failover after replacement.
- Aeron throughput, latency, CPU, disk, retransmit, or replay-rate advantage over NATS.
- Durable Aeron ACK cost.
- Fast-witness end-to-end timing and partition behavior.
- Empty-PVC/snapshot, corruption, disk-full, loss, and mixed-version matrices.
- GKE runtime and go/no-go.

### Known broken

The observed replacement follower cannot join a running primary after the first failover when the
new leader's newest recording begins at a nonzero business sequence and the follower's persisted
checkpoint is invalidated by the new epoch. It remains unready with `FAULT_GAP=3`.

This is a **real HA gap**, not merely a test that chose the wrong path. The session found no
alternative runtime path that successfully reseeds such a follower:

- a pair starting from an empty/near-empty stream works;
- a follower with a valid same-epoch checkpoint may be able to resume, but that case was not
  proved;
- across the actual leader epoch change, the persisted checkpoint was discarded;
- local journal recovery rebuilds local engine state but is not converted into an Aeron logical
  checkpoint;
- snapshot transfer is absent.

Consequently the demonstrated topology is effectively **one-shot failover**: the original standby
can promote, but the replacement standby cannot become ready, so the next primary loss has no
proven healthy alternate. Force-deleting instead of gracefully deleting the first primary may
improve promotion time, but it does not solve this replacement/rejoin defect.

## Recommended resumption point

Do not restart work in the deleted split-publication replay machinery. Keep single-publication
unicast MDC. The next design task is a bounded cold-follower bootstrap contract:

1. define how a promoted primary exposes a checksummed snapshot/journal bundle with an exact
   `(leaderEpoch, inputSeq, recordingPosition, dataSessionId)` boundary;
2. install that state on an uncheckpointed or epoch-mismatched follower;
3. begin Archive replay from the corresponding retained position;
4. retain/purge recordings behind a proved minimum follower checkpoint;
5. prove replacement rejoin and then a second failover;
6. only after correctness, run the missing Aeron transport and booked-order benchmarks.

Until those steps pass, retain NATS as the deployment default and do not claim YU11 HA or
performance readiness.

## Post-engagement addendum — 2026-07-17 (fable lane)

Three updates after this recap was written; the sections above are preserved as the accurate
state at engagement end.

1. **The Aeron transport A/B now exists.** `AeronReplicationPhase0Test` (commit `d2530ed`),
   matched line-for-line to the NATS Phase-0 harness: production MDC-UDP topology sustained
   **520,520 events/s** mean (±2% over three 30-second runs) versus the File-backed NATS HA
   baseline of 10,561 — **~49×**, with the same-day journaling control at 2.72M/s. Full tables,
   caveats, and confidence in `scripts/bench/results/yu11-transport-2026-07-17.md`. The
   performance premise of YU11 is confirmed; the open work is correctness only.
2. **A third runtime defect was found on the parked cluster.** The replacement pod's app
   container crash-looped past the recap's frozen state; on each fresh boot the follower receives
   the primary's original hello (issued ~40 minutes earlier, replayed from the retained control
   stream), rejects it with `AUTH_CLOCK` (code 8, >30s skew), and **terminally faults before ever
   reaching Archive catch-up** — it never gets far enough to hit the `FAULT_GAP` this recap
   documents. Freshness rejection of a replayed hello is correct; terminating the agent on the
   first stale one is not, because replay of old hellos is a normal condition on a
   retained/recorded stream. The agent should skip stale hellos and keep polling for a fresh one.
3. **Defect ledger for the resumption plan** (in dependency order): stale-hello handling (small),
   cross-epoch cold-follower bootstrap per the contract above (the real design task), graceful
   Lease release on shutdown (YU02-owner fix, propagate). The parked kind cluster has now served
   its diagnostic purpose.

## Overnight implementation addendum — 2026-07-17 (fable lane)

Ledger items 1 and 2 are now implemented and committed (nothing pushed):

- `aecee1f` — stale backlogged hellos are skipped (WARN + keep polling) instead of terminally
  faulting; forged/tampered hellos still fault. Regression test drives the exact
  app-container-restart backlog shape.
- `8695b79` — the cross-epoch bootstrap, with a deeper root cause than the FAULT_GAP guard: the
  wire inputSeq WAS the raw Disruptor ring sequence, which restarts at -1 on every reboot while
  the replicated stream must number continuously across the leader lineage. Implemented
  lineage-continuous business sequencing (engine `inputSeqBase`: journal-tail-derived at boot,
  follower-watermark-derived at promotion; a rotation ANCHOR record preserves the tail across
  segment rotation), plus the bootstrap itself: probe the new epoch's first input sequence from
  the primary's own recording (no schema change — the recording IS the stream; heartbeat-watermark
  fallback for an empty recording degrades to the legacy origin for a fresh pair), cut the local
  journal at exactly S0-1 discarding the divergent suffix, fail closed with precise reasons when
  local history cannot prove the boundary. 178 tests green including all allocation gates;
  noGcTest green.
- `c087683` — the `# syntax=docker/dockerfile:1.7` directives forced a BuildKit frontend fetch
  that fails offline/after a builder-cache prune; removed (no 1.x features used).
- `4322581` — first live run showed the bootstrap also ran on the runtime-demotion path, where it
  is unsound (journal cut raced the live Journaler's appends; a cut cannot rewind in-memory
  state; no probe→merge teardown gap on the shared replay port). Bootstrap is now boot-path-only;
  a demoted engine expects the stream to resume at its own watermark + 1 and faults into a pod
  restart (which runs the sound boot-path bootstrap) if the stream forked.
- `917783f` — the boot-only peer-auth wait widened to 3× the archive timeout (10s expired
  mid-Spring-startup twice under kind host load).

**New runtime finding (live, reproducible):** Aeron endpoint DNS resolution is boot-time-static.
A pod whose peer has no IP at control-agent init (peer Pending) creates a publication that can
never deliver — the pair wedges asymmetrically (one side authenticated-primary, the other waits
forever) and only a restart of the stale side heals it. This is the recap's deferred "DNS/pod-IP
replacement" gap observed in the wild; the durable fix (re-resolve or re-init the control
publication on peer-IP change) stays on the ledger.

Validated live so far: the probe correctly reads the epoch boundary from a real recording
(`epochStartInputSeq=90 source=recording`), the journal cut executes with an exact boundary, the
lineage base recomputes across boots (`base: 4801` from a 4,800-event applied history), and a
fresh follower joining a long-running primary catches up from the recording origin. The full
crash→rejoin→second-crash proof is the remaining E2E gate.
