# YU12 proposal: consistent sub-200 ms system failover and sub-500 ms client failover

Date: 2026-07-19

Lane: codeX independent ideation

Scope: proposal only; no implementation or live-cluster changes

## Verdict

Both targets are plausible, but **timeout tuning alone cannot meet them consistently on the
current deployment**.

The normal-path system gap is currently 653-778 ms. More importantly, a snapshot is an
approximately 8-second service-apply barrier. A leader can be elected while the surviving
services are still inside that barrier, but that is not yet “a new leader serving committed
orders.” Therefore the unconditional sub-200 ms target is physically unreachable while that
barrier remains.

The recommended design has four coupled parts:

1. Replace the role-only measurement with a phase-resolved **first post-kill commit/apply**
   measurement.
2. Reduce the synchronous snapshot barrier below 50 ms, or remove it from the market-hours
   service path without weakening the snapshot/log recovery contract.
3. Isolate the consensus duty cycle from CPU throttling, then tune Aeron's heartbeat, canvass,
   and nomination intervals as one budget.
4. Preserve the replicated Aeron client session and let `NewLeaderEvent` re-point ingress;
   do not close a healthy session on a 500 ms ack stall.

My target candidate, after the first three gates pass, is:

| Knob | Current | Target candidate |
|---|---:|---:|
| leader heartbeat interval | 100 ms | 20 ms |
| leader heartbeat timeout | 400 ms | 80 ms |
| election timeout | 200 ms | 40 ms |
| election status interval | Aeron default 100 ms | 10 ms |

This keeps four missed heartbeats, bounds Aeron's nomination jitter to 20 ms, and removes the
100 ms canvass-publication quantum. It is a test candidate, not a value to deploy by assertion.
It is acceptable only if the observed consensus duty-cycle and network tails leave real margin.

With those prerequisites, the expected budget is approximately 130-190 ms system-facing and
180-300 ms client-facing. If the snapshot or scheduler gates cannot be met, the honest fallback
is to retain a wider heartbeat timeout and target roughly 250-350 ms system-facing and
350-500 ms client-facing; the hard sub-200 ms claim must then be declined.

## Ground truth and one discrepancy to settle first

The proposal is based on:

- `docs/handoff/PROOF-yu12-gke-failover-2026-07-18.md`;
- `docs/handoff/RECAP-yu12-full-2026-07-19.md`;
- the YU12 runtime overrides for `ClusterNodeConfig`, `ClusterNodeMain`,
  `MatchingEngineClusteredService`, `ClusterProofClient`, and `ClusterGatewayMain`;
- the GKE StatefulSet, gateway, and proof-client manifests;
- the locally resolved Aeron Cluster 1.51.0 classes used by this worktree.

There is a small brief-versus-code discrepancy worth resolving in the first experiment:

- `ClusterProofClient` explicitly closes and endpoint-cycles after a 500 ms ack stall.
- The current `ClusterGatewayMain` does not have the same stall trigger. It endpoint-cycles on
  initial connection and after `isClosed()` or an exception, while its 10-second
  `offerAndAwait` loop continues polling egress.

The two clients should still converge on one tested owner-loop implementation, but measurements
must not attribute proof-client behavior to the gateway until this difference is accounted for.

## 1. Target decomposition

### 1.1 System-facing: current normal path

The observed 653-716 ms idle and 724/778 ms flood results can be decomposed as:

```text
leader kill
  -> follower heartbeat-loss detection
  -> CANVASS position exchange
  -> randomized nomination
  -> ballot / leader log reconciliation
  -> leader initialization and service role change
  -> first newly committed input applied
```

The current instrument stops at `MatchingEngineClusteredService.onRoleChange(LEADER)`. That is a
useful election timestamp, but the stated target ends later: the new leader must be serving
committed orders.

The tunable and currently hidden terms are:

| Term | Current bound or evidence | Lever |
|---|---|---|
| heartbeat-loss detection | up to about 400 ms from the last leader update | heartbeat interval and timeout |
| canvass send quantum | Aeron 1.51 default `electionStatusIntervalNs` = 100 ms | expose and reduce it |
| nomination jitter | Aeron selects in `[0, electionTimeout / 2]`, currently up to 100 ms | election timeout |
| ballot, log join, role/apply | residual in the current 653-778 ms samples | duty-cycle, CPU/network isolation, phase instrumentation |
| snapshot overlap | about 8 seconds of service-apply stall | snapshot redesign |

A useful bound is:

```text
T_system_serve
  <= T_heartbeat_timeout
   + T_canvass_quantum
   + T_nomination_jitter
   + T_vote_log_join_and_apply
   + T_scheduler_and_network_tail
```

For the 20/80/40/10 candidate, the first three deterministic terms consume at most
`80 + 10 + 20 = 110 ms`. That leaves about 90 ms for ballot, log join, first commit/apply, and
the tail. This is plausible on a quiet same-zone network with isolated CPU, but not on the
present one-CPU-capped, three-members-on-two-nodes deployment without evidence.

### 1.2 System-facing: snapshot overlap is the hard blocker

All members snapshot at the same applied log position. The service callback synchronously emits
the snapshot records, and the measured service-apply stall is approximately eight seconds at
the current state size.

If the leader dies during that window:

- Raft may elect a replacement quickly because the Consensus Module has its own agent;
- the surviving clustered service can still be busy in `onTakeSnapshot`;
- no new committed order is applied until that barrier clears.

Thus a role-only 150 ms result during a snapshot would be a false pass for the stated SLO. The
acceptance clock must stop at the first post-kill input committed and applied by the new leader.

### 1.3 Client-facing: current anatomy

For the proof client, the measured 838-1657 ms gap is:

```text
last pre-kill ack
  + up to 500 ms ack-stall detection
  + close replicated Aeron session
  + serial single-endpoint connect attempts
  + sometimes 1 second spent on the dead endpoint
  + new session and first committed ack
```

The bimodality matches whether endpoint cycling reaches the dead member before the leader.

There is also up to one proof submission period (currently 50 ms) of sampling phase, plus the
gateway owner's possible 50 ms blocking queue poll when it is otherwise idle.

A client-only change cannot hit sub-500 ms while system failover itself is 653-778 ms. It can,
however, remove the extra 500-1000+ ms reconnect tax and make client failover track the actual
cluster outage.

## 2. Mechanism A: measure the service SLO, not merely the role transition

Add a single correlated failover timeline:

1. `KILL`: node-clock epoch timestamp printed immediately before `kill -9`, as today.
2. `CANVASS`, `NOMINATE`, `CANDIDATE_BALLOT`, `LEADER_LOG_REPLICATION`, `LEADER_READY`:
   timestamp changes to Aeron's election-state counter on each survivor.
3. `ROLE_LEADER`: existing `onRoleChange`.
4. `NEW_LEADER_EVENT`: gateway/proof-client `EgressListener.onNewLeader`.
5. `FIRST_APPLY`: new leader applies the first post-kill canary input.
6. `FIRST_ACK`: client receives its committed ack.

Aeron exposes counters for election state/count, node role, leadership term, commit position,
Consensus Module max cycle time, service max cycle time, cycle-threshold exceedance, and snapshot
duration. Read them from each pod's CnC file and stamp transitions on that node. Use the same
NTP sanity check already used by the precise kill harness.

The two reported SLOs become:

```text
system-facing = FIRST_APPLY - KILL
client-facing = FIRST_ACK - last pre-kill ACK
```

Keep `ROLE_LEADER - KILL` as a diagnostic sub-metric, not the acceptance result.

Falsification:

- A role transition below 200 ms with `FIRST_APPLY` above 200 ms fails.
- A result set without snapshot-overlap kills does not establish consistency.
- A 20/s or 50 ms canary alone is too phase-sensitive near the target. Use a 5-10 ms canary
  interval or an always-pending canary, and report the sampling contribution separately.

## 3. Mechanism B: make the snapshot barrier compatible with the SLO

### 3.1 First choice: make the existing exact-boundary snapshot fast

Profile the current callback before designing a new snapshot protocol. The code contains an
obvious scaling hazard:

- it materializes `allOrderTuples()`;
- builds the terminal set;
- then, for each terminal reference, linearly scans all orders to find the matching tuple.

That terminal phase is potentially quadratic in retained orders. Replace it with a direct,
allocation-free-for-live-processing snapshot visitor:

- iterate open orders once;
- iterate terminal references in eviction-FIFO order;
- resolve each terminal reference through the engine's reference index in O(1);
- serialize directly to the snapshot publication with a reusable record buffer;
- stream risk, symbol, position, price, generator, and idempotency records without intermediate
  lists where practical.

This is a cold-path optimization. It must not add allocation to `onSessionMessage` or the
matching/risk hot path.

Gate:

- snapshot callback p99 and max below 50 ms at the largest supported live state;
- first post-snapshot input applies within 10 ms after callback completion;
- snapshot contents remain exact through boundary B and recovery resumes at B+1;
- zero-tail and snapshot-plus-tail recovery, idempotent retry, terminal eviction order, symbol
  identity, all generators, and promotion continuity remain green.

The 50 ms bound is deliberately stricter than the 200 ms SLO because a kill may land at the
start of the snapshot.

### 3.2 If a fast synchronous snapshot is not achievable

Do not simply return from `onTakeSnapshot` and publish later; Aeron's callback and recorded log
position are the boundary contract.

The next design would need a preallocated, boundary-stable snapshot image whose ownership can be
flipped at B and serialized without observing B+1 mutations. Candidate approaches are:

- a double-buffered off-heap state image updated deterministically with live mutations, then
  frozen at B;
- an incrementally maintained snapshot representation with no per-event allocation;
- an Aeron-supported standby-snapshot mechanism, only if 1.51.0 ground-truth proves that it
  preserves the same service boundary and recovery semantics.

All three are substantially higher-risk than removing the current traversal cost. Any mirror
must cover the full snapshot-completeness matrix, including generators, idempotency retention
order, terminal eviction order, symbol identity, risk/admission state, and config identity.

Disabling periodic snapshots during market hours is an operational stopgap, not the final
answer. It removes the barrier but increases restart replay and leaves emptyDir whole-cluster
loss exposure for a longer tail. It can support a bounded trading-session SLO, not an
unconditional 24x7 claim.

## 4. Mechanism C: give the failure detector a real scheduling margin

The current member container has:

- a one-CPU limit;
- four important agents competing in the JVM (driver, archive, Consensus Module, clustered
  service), plus application and JVM work;
- three members packed on two nodes;
- only preferred, not required, pod anti-affinity.

An 80 ms heartbeat timeout is unsafe if the Consensus Module or network driver can be
descheduled or CFS-throttled for a similar interval. Tightening first would manufacture false
elections.

Before the target timeout profile:

1. Raise the C2 quota and run one member per node with required hostname anti-affinity.
2. Give each member Guaranteed QoS: equal CPU/memory requests and limits. Start with three CPUs
   and 1536 MiB per member on c2-standard-4, leaving one core for node/system work.
3. Keep the gateway/proof client off the member CPUs, preferably on a separate pool.
4. If the node pool supports static CPU management, use integer CPU requests and verify the
   actual exclusive cpuset; do not infer pinning from the manifest.
5. Measure Consensus Module, driver sender/receiver, archive, service, and cgroup throttling
   tails under idle, 35-40k/s flood, snapshot, catch-up, and one-member rejoin.

Keep the current driver/Archive threading modes for the first isolated run. Change
`MediaDriver` from `SHARED` to `SHARED_NETWORK` or `DEDICATED`, or change Archive threading,
only when the duty-cycle evidence identifies that agent as the tail. Busy-spin idle strategies
without exclusive CPU can make the problem worse.

Required margin for the 80 ms detector:

- no Consensus Module max duty-cycle gap above 20 ms during the acceptance soak;
- no relevant driver receive/send gap above 20 ms;
- no cgroup throttle interval near the detector timeout;
- same-zone heartbeat inter-arrival p99.999 comfortably below 40 ms;
- zero false elections while snapshots and replay/catch-up are active.

If these gates fail, widen the detector. Correctness and availability beat the headline.

## 5. Mechanism D: tune all Aeron election quanta, not only three

`ClusterNodeConfig` currently exposes heartbeat interval, heartbeat timeout, election timeout,
and startup canvass timeout. Add:

```text
CLUSTER_ELECTION_STATUS_INTERVAL_MS
```

and wire it to `ConsensusModule.Context.electionStatusIntervalNs`.

In Aeron 1.51:

- heartbeat timeout gates entry to election after leader silence;
- election status interval gates repeated canvass-position publication;
- once a unanimous/quorum candidate exists, nomination delay is randomized up to half the
  election timeout;
- the election timeout also bounds ballot phases and retries.

Recommended sweep, only after snapshot and scheduling gates:

| Profile | heartbeat interval | heartbeat timeout | election timeout | status interval | Purpose |
|---|---:|---:|---:|---:|---|
| current control | 100 | 400 | 200 | 100 | reproduce 653-778 ms |
| conservative | 50 | 200 | 100 | 25 | establish roughly 250-350 ms region |
| bridge | 25 | 120 | 60 | 10 | expose residual ballot/log-join tail |
| target candidate | 20 | 80 | 40 | 10 | attempt consistent sub-200 ms |
| diagnostic only | 10 | 50 | 20 | 5 | determine physical floor; do not ship without exceptional margin |

Run one profile per fresh, settled cluster epoch. Do not mutate timeouts repeatedly in one
campaign and treat the churn as a product result.

Reject these shortcuts:

- a Kubernetes Lease, probe, or pod watcher in the promotion path;
- an external detector that can force a Raft leader without quorum fencing;
- a fixed appointed leader intended to remove nomination randomness;
- declaring success from the best sample or from role change alone.

## 6. Mechanism E: use Aeron's replicated client session as designed

### 6.1 What Aeron 1.51 already provides

Inspection of the pinned 1.51.0 client shows:

- `pollEgress` decodes `NewLeaderEvent`;
- `AeronCluster.onNewLeader` keeps the same final `clusterSessionId`, updates leadership term
  and leader member, closes the old ingress publication, rebuilds the leader publication from
  the advertised endpoint list, and calls `EgressListener.onNewLeader`;
- if the old egress image closes first, the client enters `AWAIT_NEW_LEADER` rather than
  immediately closing;
- the default new-leader wait is twice the leader heartbeat timeout reported by the server,
  unless explicitly overridden;
- `messageTimeoutNs` bounds initial connect and the new leader publication-connect phase.

At the current 400 ms heartbeat timeout, the implicit new-leader wait is about 800 ms—only
around 22 ms beyond the measured 778 ms loaded role transition. The proof client's explicit
500 ms stall close is earlier still. Both margins are too small.

### 6.2 Recommended client owner loop

Use one shared owner-loop implementation for the proof client and gateway:

1. Re-test normal multi-endpoint `ingressEndpoints` on GKE now that every egress channel has
   `term-length=64k` and `/dev/shm` is sized. Connect starting at each follower and require the
   redirect to complete.
2. Keep the successful `AeronCluster` instance alive across leader loss.
3. Poll egress continuously; use an explicit `EgressListener` implementation to timestamp
   `onNewLeader` and session events.
4. Treat an ack stall as observability/backpressure, not authority to close the session.
5. While ingress offers return a negative result, retain commands in a bounded gateway-owned
   pending queue and keep polling. Resume offers when the new leader publication connects.
6. Set `newLeaderTimeoutNs` explicitly to at least 1 second (prefer 2 seconds during the first
   soak). This is a give-up bound, not latency added to a successful leader change.
7. Set `messageTimeoutNs` explicitly and measure it; 500-1000 ms is reasonable once serial
   endpoint cycling is gone.
8. Poll before blocking for owner work. Replace the possible 50 ms `tasks.poll` sleep with
   non-blocking queue drain plus an Aeron idle strategy or a sub-millisecond park.
9. Only after `isClosed()` should a rate-limited asynchronous full-endpoint reconnect begin.
   Bound reconnect frequency so a bad period cannot create thousands of publications and fill
   `/dev/shm`.

If multi-endpoint initial connect still wedges on GKE, keep single-endpoint discovery only for
bootstrap. Then prove that the first `NewLeaderEvent` supplies the full endpoint list and moves
the same session to a different member. Do not use bootstrap endpoint cycling as the steady-state
failover mechanism.

Falsification:

- `clusterSessionId` changes across a normal leader kill;
- any `CONNECT` occurs between kill and first post-kill ack;
- no `onNewLeader` arrives before `newLeaderTimeoutNs`;
- starting on a follower still wedges after the egress term-length fix;
- repeated kills grow publications, sessions, or `/dev/shm`;
- gateway and proof client disagree under the same kill.

At the current cluster timeout, native following should remove the 1.6-second mode but still land
near the 650-900 ms system floor. Once the system target is met, the same path should put the
client comfortably below 500 ms.

## 7. Ambiguous orders and idempotent retry

Session survival avoids most reconnect ambiguity, but an offered command without a committed
ack is still ambiguous. Do not automatically resubmit it under the current gateway contract:

- REST and FIX currently write `0` into the `clientOrderKey` slot;
- the proof client also uses key `0`;
- the service's idempotency capacity is only 1024 entries;
- egress is intentionally best-effort and may drop a committed ack.

Before automatic retry:

1. Derive a stable nonzero 64-bit key from client identity plus REST `clientOrderId` or FIX
   `ClOrdID`; reject conflicting payload reuse of the same key.
2. Echo the key in committed egress so correlation does not depend on FIFO after dropped or
   asynchronous lifecycle messages.
3. Dimension idempotency retention by rate and ambiguity horizon:

   ```text
   capacity >= peak keyed commands/s * maximum retry horizon * safety factor
   ```

   At 36k commands/s, 1024 entries cover only about 28 ms. Even a two-second horizon needs at
   least 72k entries before safety factor. A power-of-two starting point such as 131072 must be
   validated against memory and snapshot duration.
4. Preserve key, original decision/reference, payload fingerprint, and exact retention order in
   snapshot B; replay B+1 onward; assert promotion returns the original result.
5. Test retry after egress loss, after leader change, after snapshot-plus-tail recovery, after
   zero-tail recovery, and at the retention frontier.

The replicated service already snapshots idempotency decisions and preserves retention order,
which is the right base. The live gateway contract and retention horizon are the missing pieces.

No resubmission design may weaken:

- committed-log single input;
- monotonic `nextOrderRef > highestIssuedRef`;
- deterministic replay;
- fail-closed risk/control admission;
- exact-zero allocation on the service apply hot path.

## 8. Verification and soak gates

### Gate 0: measurement validity

- Fresh cluster epoch.
- One leader, two followers; previous replacement fully caught up plus margin.
- Node clock offset recorded before and after the campaign.
- Phase timeline records kill, all election states, role, first apply, new-leader event, first ack.
- Harness never kills the next leader before the prior member rejoins.

### Gate 1: snapshot compatibility

- Snapshot max below 50 ms at maximum supported state, or snapshot is proven not to delay
  `FIRST_APPLY`.
- At least 20 leader kills intentionally land inside snapshot work.
- Snapshot plus nonempty tail, zero tail, wiped-member retrieval, follower promotion,
  idempotent retry, terminal eviction, symbols, risk, and every future-output generator pass.

### Gate 2: false-election margin

- 30-minute idle soak and 60-minute 35-40k/s flood soak per candidate profile.
- Multiple snapshots, one follower catch-up, and one follower rejoin during the loaded soak.
- Zero uncommanded elections.
- Consensus/driver duty-cycle and cgroup throttle gates from section 4 pass.

### Gate 3: failover distribution

For the final candidate:

- 100 isolated idle leader kills;
- 100 isolated loaded leader kills, including snapshot phases and both node-placement cases;
- system `FIRST_APPLY - KILL`: every sample below 200 ms, with max and p99.9 reported;
- client ack gap: every sample below 500 ms, with max and p99.9 reported;
- zero ID reuse, zero duplicate execution for keyed retries, one leader only;
- all members converge to identical authoritative counters/state after each recovery cycle.

Five good kills establish a direction, not consistency.

### Gate 4: non-crash faults

One fault at a time:

- process `kill -9`;
- pod loss;
- node loss after one-member-per-node placement;
- one-way leader network loss/blackhole;
- delayed/lost heartbeat burst shorter than the detector timeout;
- gateway egress interruption;
- leader loss during snapshot, replay, and follower catch-up.

The minority must never serve committed orders. A partition that destroys quorum must stop
admission even if that violates the latency SLO; safety has priority.

## 9. Recommended implementation order

1. **Strengthen the harness.** Add election-state, first-apply, `onNewLeader`, duty-cycle,
   throttling, and snapshot-phase timestamps. Reproduce the current 400/200 control.
2. **Run the no-code client experiment.** On GKE, connect with the full endpoint list, stop
   closing on ack stall, and prove same-session native leader following. This isolates the old
   kind redirect unknown after the real `/dev/shm` root cause was fixed.
3. **Fix the snapshot barrier.** Remove the quadratic traversal/materialization first. Re-run
   the snapshot completeness and promotion matrix. Do not tune to 80 ms while `FIRST_APPLY`
   can be delayed by seconds.
4. **Fix placement and CPU isolation.** Three member nodes, required anti-affinity, Guaranteed
   QoS, gateway off the member CPUs. Measure before changing threading/idle strategies.
5. **Expose election status interval and sweep profiles.** One fresh, settled epoch per profile,
   conservative to target. Stop tightening at the first false election or margin failure.
6. **Unify the gateway/proof owner loop.** Native `NewLeaderEvent`, bounded pending queue,
   explicit timeouts, sub-millisecond polling, rate-limited async reconnect fallback.
7. **Harden ambiguous retry.** Stable client keys, correlated egress, retention sizing, complete
   snapshot/replay proof.
8. **Run the 200-kill distribution and fault matrix.** Only then change the SLO/status docs.

## 10. Explicit risks and decisions

| Risk | Why it matters | Decision / falsifier |
|---|---|---|
| false elections from an 80 ms timeout | ordinary scheduling or packet tails can exceed it | require duty-cycle/network margin and zero false elections under full soak |
| snapshot barrier hides behind fast role change | leader exists but cannot apply orders | judge `FIRST_APPLY`, kill during snapshots |
| current two-node packing | correlated node fault and CPU contention | quota bump plus required one-per-node placement before consistency claim |
| native redirect still wedges | original kind observation may survive the `/dev/shm` fix | re-test all follower starts on GKE; single-endpoint bootstrap is fallback only |
| client session times out just before leader event | default is only 2x heartbeat timeout | explicit 1-2 s `newLeaderTimeoutNs`; observe same session ID |
| proof client does not represent gateway | current reconnect loops differ | shared owner-loop implementation and side-by-side measurements |
| egress ack drop creates ambiguity | egress is best-effort by design | stable key, correlated ack/reconcile, no blind retry |
| 1024-key dedup retention | only ~28 ms at 36k/s | rate-by-horizon sizing and retention-frontier tests |
| larger idempotency state worsens snapshots | retry safety and latency interact | include target capacity in snapshot-duration gate |
| aggressive busy-spin/thread splitting | can increase contention under CPU quota | change only from duty-cycle evidence, with isolated CPU |
| measurement artifacts recur | this campaign already found four | fresh epochs, one fault, role settle, catch-up margin, phase-resolved clocks |

## Recommendation in one sentence

First make “serving” measurable and snapshots non-blocking enough for the SLO; then isolate CPU,
tune Aeron's 100 ms hidden canvass quantum plus heartbeat/election to a 20/80/40/10 candidate,
and let the existing replicated Aeron session follow `NewLeaderEvent` instead of destroying it.
