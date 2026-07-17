# YU11 cross-epoch recovery findings — 2026-07-17

Audience: the next agent implementing the remaining YU11 recovery work.

This is a read-only design finding from codeX after reviewing
`ISSUES-yu11-e2e-2026-07-17.md` at commit `83049c8`. It does not supersede that issue ledger or
the cycle-8 live proof. It narrows P1 to a recommended implementation and records an additional
snapshot-sequence defect that must be corrected before the transfer path is sound.

## Verdict

P1 is real and remains unsolved.

Cycle 8 proved:

```text
local journal business tail = 838
new epoch recording start   = 839
```

That is the exact-boundary fast path already implemented by the journal cut. It does not prove the
lagging or empty replacement case recorded in the issue ledger:

```text
new epoch recording start = 159
local retained tail       = 39
missing interval          = 40..158
```

In that case the replacement cannot reconstruct state through `158`, and ReplayMerge cannot begin
before the current epoch recording's start at `159`. Failing closed is correct, but the node remains
permanently unable to rejoin without a state-transfer mechanism.

## Decision: implement a recovery bundle, not replay-chaining

The correctness path should transfer a full-state snapshot from the authenticated current primary,
bound to an exact point in the current epoch's Archive recording.

Replay-chaining may be retained as a later optimization, but it cannot be the recovery contract:

1. The Kubernetes sidecar records the local matcher's MDC publication through
   `AERON_RECORD_INBOUND_CHANNEL`. `AERON_RECORD_OUTBOUND_CHANNEL` is empty in the HA manifest.
2. When leadership alternates, epoch recordings are consequently distributed across the two pods'
   separate Archive catalogs.
3. A promoted primary can have applied an earlier epoch while it was a follower. That state is in
   its journal/snapshot, but the earlier epoch's publication need not exist in its local Archive.
4. The other pod may have lost its PVC—the exact case recovery must handle.
5. Journal rotation and archival also mean historical input payloads are not guaranteed to remain
   locally replayable forever.

Walking the current leader's Archive therefore cannot guarantee that all missing epochs exist.
The current leader's full in-memory state plus its durable snapshot/journal lineage is the
authoritative source that can bridge any number of earlier epochs.

## Prerequisite defect: snapshot markers are handled in two sequence domains

The current code calls snapshot markers “non-business” and excludes them from the journal tail, but
the Aeron transport treats them as ordinary contiguous inputs:

- `LmaxEngine.submitSnapshot()` consumes a ring slot and stamps
  `event.seq = inputSeqBase + ringSeq`.
- `AeronReplicator` publishes that marker with the stamped sequence.
- `AeronReplicationFollower` advances `lastInputSeq` for every valid frame, including the marker,
  and requires the next frame to be `lastInputSeq + 1`.
- `JournalReader.lastBusinessSeq()` ignores snapshot records.
- `Journaler` writes a rotation anchor using the last non-snapshot sequence.

If a snapshot marker is the last replicated input before a restart, the wire lineage has advanced
through the marker but the active journal anchor reports the preceding state-changing input.
`initInputSeqBase()` can then reuse the marker's sequence instead of continuing after it. The same
mismatch can falsely report that a follower cannot prove a boundary which is actually a snapshot
marker.

There is a second contributor: `startSnapshotScheduler()` runs for both roles, and
`submitSnapshot()` is not role-gated. A follower can inject a local snapshot marker that never
existed in the primary's replication stream.

### Required correction

Before adding bundle transfer:

1. Only a primary may locally submit a scheduled snapshot marker. A follower snapshots when it
   receives the primary's replicated marker.
2. Define `inputSeq` as the lineage sequence for every replicated input, including snapshot
   markers. Whether the marker mutates business state is irrelevant to sequence continuity.
3. Replace the “business tail” bootstrap helpers with input-stream tail semantics:
   - the last input sequence includes a snapshot marker;
   - a rotation anchor carries the last input sequence through the marker;
   - exact-boundary truncation can prove a marker sequence.
4. Preserve anchor records as journal-private and skip them during state replay.
5. Add tests for restart and promotion immediately after a snapshot marker.

This change should be completed independently and kept small enough to review before state
transfer is introduced.

## Recovery-bundle protocol

### Fast path remains unchanged

For an epoch-mismatched or checkpoint-less follower:

1. Probe the current epoch recording to obtain its first input sequence `S0`.
2. If the local recovery image can prove state through `S0 - 1`, perform the existing exact
   divergent-suffix cut and join the recording at `S0`.
3. Only request a bundle when that proof fails because the follower is behind, empty, or its
   retained snapshot/rotation boundary is already beyond the requested cut.

### Bundle capture

The authenticated current primary should handle a replay request by sequencing a snapshot marker
at input sequence `B` in its current epoch.

The bundle boundary must contain:

```text
leaderEpoch
inputSeq = B
archivePosition = position immediately after marker B
dataSessionId
payloadChecksum for marker B
schema version/checksum
snapshot length + SHA-256
symbols length + SHA-256
request correlation ID
```

The Archive position must be captured by `AeronReplicator` immediately after committing marker
`B`. Reading `publicationPosition()` later from the BLP snapshot callback is not exact: the
replication handler is upstream of the BLP and may already have published later inputs.

A no-allocation implementation can attach the committed position, session ID, and checksum to the
reusable snapshot `InputEvent`, or publish them into a fixed-capacity sequence map. The BLP
snapshot callback must receive `B`, rather than the current no-argument `Runnable`.

After both upstream handlers have processed `B`:

- the marker is journal-forced;
- its exact publication position is known;
- the BLP state is consistent through `B`;
- `snapshot.dat` can be atomically written for that boundary.

### Bundle contents

The minimal bundle is:

- a full-state `snapshot.dat`;
- `symbols.tab`;
- the signed/checksummed manifest.

It does not need to contain events `40..158`: the snapshot replaces the need to replay them.

The transferred snapshot should be normalized for a new active journal whose first 64-byte record
is an anchor at `B`. Its `coveredOffset` must therefore be `64`, independent of the source
primary's current journal byte layout. Add snapshot format version 4 with the Aeron input boundary,
or write a normalized transfer copy without modifying the primary's local recovery snapshot.

The snapshot already contains the matching book, positions, last prices, counters, risk controls,
reservations, and risk idempotency state. `symbols.tab` is part of the recovery image because
journal and wire inputs carry numeric security IDs rather than ticker strings.

### Transfer transport

The existing SBE messages are useful but incomplete:

- `ReplayRequestMessage` has a correlation ID.
- `SnapshotManifestMessage` lacks `dataSessionId`, last-frame checksum, and correlation ID.
- No message or endpoint currently transfers the file bytes.

Extend the manifest and add a bootstrap-only chunk transport. A separate Aeron stream is preferable
to adding file traffic to the steady-state control polling path. Chunk transfer is startup-only and
may allocate; it must not enter the replication hot-path allocation gates.

Every request and manifest must remain bound to the already authenticated peer session. If the
leader epoch changes while the primary captures or the follower downloads the bundle, abort the
generation and renegotiate with the new leader.

### Atomic follower installation

Installation happens before journal recovery and before follower transport begins consuming the
current stream:

1. Download into a new temporary generation.
2. Verify authenticated peer, cluster/schema compatibility, epoch, lengths, and SHA-256 hashes.
3. Write a fresh active journal containing only an anchor at `B`.
4. Write an Aeron follower checkpoint:

   ```text
   (leaderEpoch, B, archivePositionAfterB, payloadChecksumB, dataSessionId)
   ```

5. Atomically replace the journal recovery generation.
6. Load the transferred symbol table before any `idFor()` assignment.
7. Load the full-state snapshot; replay no local tail beyond its 64-byte anchor.
8. Start ReplayMerge at `archivePositionAfterB`, requiring session `dataSessionId`.
9. Set the expected first input to `B + 1`.

`AeronArchiveReplayMerge.awaitRecording()` already has the shape needed to wait until the Archive
has recorded the required position.

The current initialization order loads `symbols.tab` before follower bootstrap planning. Either
move symbol persistence loading until after possible bundle installation or add a startup-only
replace/reload operation which is legal only before the first symbol lookup.

## Failure rules

The follower must remain not-ready and preserve its prior recovery generation when:

- authentication or schema validation fails;
- the manifest epoch differs from the negotiated epoch;
- the epoch changes during transfer;
- a length or hash check fails;
- the requested recording session/position cannot be found;
- the snapshot boundary does not precede the requested ReplayMerge start exactly;
- installation is interrupted before the generation switch.

Never partially overwrite the active snapshot, symbols, journal, or follower checkpoint. Temporary
files from a failed transfer may be deleted on the next boot.

## Suggested implementation slices

1. **Input-sequence cleanup**
   - Primary-only local snapshot submission.
   - Snapshot-aware input tail and anchors.
   - Restart/promotion boundary tests.

2. **Exact snapshot boundary**
   - Pass marker `inputSeq` into the snapshot callback.
   - Capture publication end position/session/checksum at the replicator.
   - Snapshot format v4 or normalized transfer snapshot.

3. **Manifest and transfer agent**
   - Extend SBE schema.
   - Add bundle capture, hashing, chunk send/receive, authentication, and epoch cancellation.

4. **Atomic installer and bootstrap integration**
   - Install snapshot, symbols, anchor, and checkpoint.
   - Preserve the existing exact journal-cut fast path.

5. **Proof**
   - Unit and corruption/interruption matrix.
   - Allocation/no-GC regression gates.
   - Full kind crash/rejoin/second-crash test.

## Decisive kind acceptance test

The proof must deliberately exercise the case cycle 8 did not:

1. Start a healthy pair and submit enough traffic to establish state.
2. Stop the standby and preserve or construct a journal ending far behind the primary.
3. Advance the primary and force at least one new leader epoch/recording.
4. Start the replacement with a tail below the current epoch's `S0 - 1`, or with an empty PVC.
5. Observe bundle request, exact boundary capture, verified atomic installation, ReplayMerge catch-up,
   and `2/2 Ready`.
6. Compare matching/risk/read-model state at the recovery boundary.
7. Kill the active primary again.
8. Prove the bundle-recovered follower promotes and continues the input lineage without duplicate
   or gap.

Also run:

- corrupted snapshot and symbols hashes;
- epoch change during bundle transfer;
- interrupted transfer followed by restart;
- snapshot marker as the final pre-crash input;
- repeated recovery with an already valid exact-boundary journal;
- all existing tests, allocation gates, and `noGcTest`.

## Other open issues

This design does not close the remaining independent findings:

- P2: Lease/election flapping and graceful Lease release belong at the YU02 owner layer.
- P3: the Aeron control publication can retain stale DNS resolution and needs bounded
  reinitialization.
- P5: the kind crash-test stopwatch assumes fast-witness while the kind profile currently uses the
  slower Lease/heartbeat path.

Those items should not be allowed to substitute for the P1 proof. Reducing epoch churn makes the
recovery gap rarer; it does not make a lagging or empty replacement recoverable.
