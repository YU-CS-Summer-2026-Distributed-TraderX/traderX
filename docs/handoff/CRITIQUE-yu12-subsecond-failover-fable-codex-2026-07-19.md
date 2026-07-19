# Critique of Fable's YU12 sub-second failover proposal

Date: 2026-07-19

Reviewer: codeX

Reviewed artifact:
`docs/handoff/PROPOSAL-yu12-subsecond-failover-fable-2026-07-19.md`

## Verdict

Fable has the right client-side direction, especially preserving the replicated Aeron session
and re-testing multi-endpoint redirect on GKE. I would not execute the proposal unchanged,
however.

The system design does not yet meet the hard sub-200 ms target, because it:

- measures role election rather than the first post-kill committed apply;
- leaves Aeron's 100 ms election-status/canvass quantum untouched;
- treats the 8-second snapshot barrier mainly as a false-election concern rather than a direct
  serving outage;
- assumes scheduling margin that the current one-CPU-capped, three-members-on-two-nodes
  deployment has not demonstrated.

The client design also calls blind resubmission safe even though the current proof client and
gateway use idempotency key `0`, and the service retains only 1024 idempotency entries.

The proposal is a strong client-redesign sketch. Its system and retry sections need revision
before implementation.

## Priority findings

### P0: snapshot overlap invalidates the system-facing claim

Fable treats the approximately 8-second snapshot barrier mainly as a possible cause of false
elections:

- Fable proposal lines 21-24.
- `MatchingEngineClusteredService.onTakeSnapshot`, lines 230-238.

The clustered service synchronously emits snapshot records. If the leader dies during that
window, Raft may elect a replacement quickly while the surviving clustered service remains
unable to apply new committed orders.

A result such as:

```text
ROLE_LEADER - KILL = 150 ms
FIRST_APPLY - KILL = 4,000 ms
```

fails the stated target, because the target is “leader dead -> new leader serving committed
orders,” not merely “leader role assigned.”

Required revision:

1. Make `FIRST_APPLY - KILL` the system-facing acceptance metric.
2. Intentionally kill leaders during active snapshot work.
3. Reduce the snapshot callback below the remaining SLO budget before claiming consistent
   sub-200 ms serving.
4. Profile and remove the current snapshot traversal cost first. The terminal-order phase
   materializes all order tuples and then scans the full list once per terminal reference
   (`MatchingEngineClusteredService.writeSnapshot`, lines 292-310), which is potentially
   quadratic.

### P0: blind resubmission is unsafe in the current system

Fable says unacknowledged offers can be blindly resubmitted because idempotency makes them safe
(lines 48-50).

That is not the current contract:

- `ClusterGatewayMain` writes `0` into the `clientOrderKey` slot (line 255).
- `ClusterProofClient` creates orders with key `0` (line 92).
- `MatchingEngineClusteredService.IDEMPOTENCY_CAPACITY` is 1024 (line 68).
- Egress is explicitly best-effort, so an absent ack does not prove the order was uncommitted.

At 36,000 keyed commands per second, 1024 retained decisions cover only about 28 ms. A retry
after a normal failover could already fall behind the retention frontier.

Required revision before automatic retry:

1. Generate a stable, nonzero key from client identity and REST `clientOrderId` or FIX
   `ClOrdID`.
2. Reject reuse of the same key with a different payload.
3. Echo the key in committed egress so correlation survives dropped or interleaved lifecycle
   messages.
4. Size retention by:

   ```text
   peak keyed commands/s * maximum retry horizon * safety factor
   ```

5. Prove the original result survives leader change, snapshot plus tail, zero-tail recovery,
   promotion, and the retention frontier.

## Major design gaps

### P1: the proposed timeout profile does not meet the hard target

Fable's primary profile is:

```text
heartbeat interval = 25 ms
heartbeat timeout = 150 ms
election timeout = 100 ms
```

The proposal itself estimates 250-300 ms system-facing, which is a useful intermediate target
but does not satisfy the requested sub-200 ms bound.

It also leaves Aeron 1.51's default `electionStatusIntervalNs = 100 ms` unchanged. That interval
gates canvass-position publication and can consume half the complete target by itself.

Required revision:

- expose `CLUSTER_ELECTION_STATUS_INTERVAL_MS`;
- tune heartbeat timeout, election timeout, and election-status interval together;
- treat 25/150/100 as a conservative bridge profile;
- only attempt a profile such as 20/80/40/10 after snapshot and scheduler-margin gates pass.

### P1: false-election assumptions ignore CPU scheduling

Fable argues that sub-millisecond pod networking and the allocation-free service hot path make
false elections unlikely.

Those facts do not protect the ordinary Consensus Module, Media Driver, Archive, and JVM
threads. The current member manifest has:

- CPU request: 250m;
- CPU limit: 1;
- three members packed onto two nodes;
- preferred, not required, hostname anti-affinity.

An 80-150 ms detector is unsafe if cgroup throttling or agent duty-cycle gaps approach that
interval.

Required revision:

1. Run one member per node with required anti-affinity.
2. Give members Guaranteed QoS with equal CPU/memory requests and limits.
3. Keep gateway/proof-client load off member CPUs.
4. Measure Consensus Module, driver, service, and cgroup-throttling tails under idle, flood,
   snapshot, replay, catch-up, and rejoin.
5. Require a substantial measured margin below the heartbeat timeout.

A five-minute idle soak is inadequate evidence for “consistent.”

### P1: Aeron 1.51 has no proposed graceful step-down control

Fable proposes using ClusterTool or a Consensus Module control toggle to transfer leadership
during maintenance (lines 30-33).

The Aeron 1.51 control states available here are:

- suspend;
- resume;
- snapshot;
- shutdown;
- abort;
- standby snapshot.

There is no step-down or leadership-transfer toggle, and ClusterTool exposes no such command.
Planned maintenance failover is worth separating from crash failover, but this mechanism must
be replaced or implemented explicitly rather than added to the runbook.

### P1: heartbeat tuning also shrinks the client's survival window

In Aeron 1.51, the default `newLeaderTimeoutNs` is twice the leader heartbeat timeout reported
by the server unless explicitly overridden.

Therefore:

```text
heartbeat timeout 150 ms -> client new-leader wait about 300 ms
heartbeat timeout 100 ms -> client new-leader wait about 200 ms
```

Those windows are dangerously close to Fable's own election estimate. A client can close just
before the new-leader announcement or ingress publication becomes usable.

Required revision:

- set `newLeaderTimeoutNs` explicitly to at least 1 second, preferably 2 seconds for initial
  soak campaigns;
- treat it as a give-up bound, not added successful-failover latency;
- configure and measure `messageTimeoutNs` separately.

### P1: parallel reconnect races are a hazardous fallback

Fable's fallback races two independent connections and keeps the first session that succeeds
(lines 58-62).

YU12 has already observed reconnect churn leaking client sessions until the Consensus Module
reported:

```text
ERROR - concurrent session limit
```

Parallel connection races can recreate that failure mode and allocate multiple publications in
`/dev/shm`.

A safer fallback order is:

1. Use the full endpoint list and native leader following.
2. If follower redirect still wedges, use single-endpoint bootstrap to obtain one session.
3. Preserve that session across leader change; `NewLeaderEvent` carries the advertised endpoint
   list.
4. Only after the client is genuinely closed, begin one rate-limited asynchronous reconnect.

## Corrections and clarifications

### Client latency terms overlap

The opening equation adds system recovery, stall detection, and reconnect time. In practice,
system election and the client's stall timer run concurrently.

A better model is:

```text
client outage
  ~= max(system/native-leader recovery,
         stall threshold + reconnect path)
     + submission phase
     + first-ack RTT
```

This explains both observed modes more accurately.

### Listener method name

The Aeron 1.51 API method is:

```text
EgressListener.onNewLeader(...)
```

`NewLeaderEvent` is the protocol event; `onNewLeaderEvent` is not the listener method in the
pinned API.

### Election fixed-cost claim is not yet measured

Fable describes `leaderInit/joinLogAsLeader` as a fixed 50-100 ms observed cost. The current
measurements expose only the complete kill-to-role interval. Phase instrumentation is required
before assigning that residual specifically to log join.

### Unsupported bench claim

The statement that silent gateway session death was hit during the bench is not supported by
the proof artifact. The documented session-related bench failures were:

- an undrained client session throttling egress/apply;
- leaked sessions from reconnect churn exhausting the concurrent-session limit.

## What should be retained

The following recommendations are sound and should survive revision:

1. Preserve the replicated Aeron session instead of reconnecting on an ack stall.
2. Re-test full multi-endpoint redirect on GKE after the egress `term-length=64k` and
   `/dev/shm` fixes.
3. Keep crash failover off the Kubernetes control plane.
4. Instrument election phases and verify the same `clusterSessionId` survives.
5. Increase proof-client cadence to reduce measurement quantization.
6. Use 25/150/100 as a conservative intermediate experiment.
7. Keep planned maintenance behavior separate from crash-failover acceptance, after replacing
   the unsupported step-down mechanism.

## Recommended revision

Revise the proposal around this order:

1. Measure `KILL -> election phases -> ROLE_LEADER -> FIRST_APPLY -> onNewLeader -> FIRST_ACK`.
2. Remove or bound the snapshot apply barrier.
3. Isolate CPU and establish Consensus Module/driver duty-cycle margin.
4. Expose and tune Aeron's election-status interval with the other timeouts.
5. Prove same-session native leader following without reconnect.
6. Add real client keys and retention sizing before any resubmission.
7. Run a substantial kill distribution, including loaded and snapshot-overlap kills, rather
   than accepting a small number of best-case samples.

Until those revisions land, the defensible conclusion is:

- native leader following should eliminate the client bimodality;
- 25/150/100 may reach the 250-300 ms system region;
- consistent sub-200 ms serving remains unproven;
- blind retry remains unsafe.
