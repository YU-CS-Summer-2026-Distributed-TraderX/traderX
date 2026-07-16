# Research: YU11-aeron-replication

## Measured starting point

The File-backed NATS Phase-0 harness exercises the real primary replicator, real follower
injector, JetStream File storage, and both journals. Three-run means are 10,561 transport
events/s for on-ring ACK and 10,242 for the conservative durable proxy. The proxy waits through
the follower's final applied gating sequence because the YU10 lane could not wire the journaler's
post-force watermark. The same measurement records 1,589 allocated bytes/event in jnats after the
owned payload/list reduction; the public acknowledged publish API still creates a request/completion
graph for every event.

These results establish two independent constraints. The transport leg is far below the
single-journal control, so it is a valid optimization seam. The primary journal is already the
durability authority, so follower loss does not justify halting all trading by default. YU11
therefore measures Aeron against the NATS baseline while separating transport choice, ACK
strength, and follower-loss policy into explicit flags.

## Why Aeron Transport + SBE

The order-matcher already isolates replication as a Disruptor consumer parallel to `Journaler`.
Aeron's claimed-buffer publication fits that seam: the single replication-handler thread claims
space and an SBE flyweight writes named fixed fields in place. The follower polls fragments and
decodes directly into the existing multi-producer input ring. No REST contract, matching rule,
risk calculation, output topology, or non-replication NATS subject changes.

SBE supplies schema identity and generated codecs around the existing 64-byte fixed record. The
transport gain comes from the publication/flow-control model, not from relabeling fixed offsets;
SBE's value is deterministic layout, golden compatibility vectors, and fail-closed handling of
unknown templates/versions/flags.

## ACK strength and availability are separate

`Journaler` advances `journaledSeq` only after the drained batch is written and
`FileChannel.force(false)` completes. Passing that exact object/watermark to the follower ACK
agent removes the YU10 post-apply proxy. The ACK agent maps local ring sequences back to primary
input sequences and coalesces the highest contiguous durable point.

On-ring and durable ACK remain selectable because they answer different operational needs.
Follower-loss policy is independent:

- `degraded-solo` stops claiming synchronous follower durability, alerts, and continues against
  the primary journal while reconnection/catch-up proceeds;
- `strict` requires durable ACK and closes admission whenever the follower cannot prove the
  contiguous journal watermark.

This preserves process/pod-loss durability through the primary journal and avoids turning a
routine follower reschedule into a total trading outage. Strict policy remains available for a
deployment whose durability contract includes the narrow primary-volume-plus-follower double
failure window.

## Archive is catch-up, not business recovery

Reliable live UDP cannot recover an absent subscriber after the sender's retransmission window.
Each pod therefore runs an Archiving Media Driver sidecar and records the replication stream on
the same persistent volume family as journal/snapshot data. The application journal remains the
business recovery authority; Archive supplies an indexed replication tail for a follower.

A retained-volume follower replays from its checkpoint and merges live. An empty-volume follower
installs a complete checksummed snapshot bundle and replays from the bundle's recording position.
Readiness and promotion require both journaled and applied watermarks at the observed live high
watermark. Missing recording positions, checksum/schema faults, catalog errors, and disk pressure
are explicit unready states.

## Sidecar topology and CPU budget

The media driver and Archive run in a separate Java process so their duty cycle, heap, GC, and
CPU are visible independently of Spring and the BLP. Shared threading mode caps the sidecar at
one core on each four-vCPU c2 node. The application and sidecar share a memory-backed Aeron
directory and the pod's Archive volume path.

The one-core cap is part of the performance contract. A transport microbenchmark that improves
by consuming the gateway/BLP cores fails the end-to-end gate.

## Safe fast failover needs a third decision point

Two replicas cannot distinguish peer death from an asymmetric partition by direct heartbeat
alone. Letting either side promote on silence creates two potential writers; an epoch in the
replication record rejects stale streams but does not retract orders already admitted on both
isolated primaries.

The opt-in fast path therefore uses three pieces:

1. direct Aeron heartbeat provides the 30–50 ms failure detector without a broker hop;
2. a compare-and-set record in the existing NATS JetStream KV service is the atomic tiebreaker;
3. the winner opens admission with the witness revision/epoch in its fence and reconciles the
   Kubernetes Lease asynchronously.

Only one contender can commit the witness revision. A pod unable to reach the witness cannot
promote; an ambiguous update remains non-authoritative; a foreign witness revision or confirmed
Lease holder demotes before admission. The default path continues using the Kubernetes Lease as
the synchronous promotion gate.

## Cutover discipline

One binary contains both transports. With NATS authoritative, Aeron shadow mode records and
consumes the same event stream, comparing contiguous sequence and payload checksum without
injecting a second event into the follower ring. The application and sidecar expose the same SBE
schema checksum and reject a mismatched session.

A coordinated pair restart changes `BLP_REPLICATION_TRANSPORT` to `aeron`. Mixed peers refuse
readiness. Restoring the value to `nats` is the rollback; non-replication NATS subjects remain
unchanged in either mode.

## Proof model

The transport harness reports offered, recorded, follower-journaled, and ACKed rates; p50/p99
ACK latency; backpressure; retransmits/loss gaps; Archive lag/disk growth; allocation; and sidecar
CPU. Deterministic loss and replay cases precede the compose pair.

The deployment decision uses booked orders on GKE, not transport events: at least 35,000 booked/s
and at least 25% above the immediately preceding File-backed NATS HA run, with three 30-second
runs, zero failed/risk-misclassified submissions, and same-day single-BLP/regression controls.
