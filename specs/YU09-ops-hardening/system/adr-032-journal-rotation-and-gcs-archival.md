# ADR-032: Journal Rotation at Snapshot Boundaries, Archived to GCS via HMAC-Signed S3 Client

**Status:** Accepted
**Date:** 2026-07-12
**State:** `YU09-ops-hardening` (parent `YU08-execution-algo-engine`)

## Context

`order-matcher`'s journal (`Journaler`) writes every sequenced input event to a single file
(`input-events.journal`) on the pod's PVC, forever — no rotation, no cap, no archival. A
long-lived pod's journal grows without bound. The periodic snapshot mechanism
(`SnapshotStore`/`snapshotIntervalMs`) already tracks, per snapshot, the byte offset in that same
file up to which recovery no longer needs to replay (`lastSnapshotOffset`) — so the information
needed to know "these bytes are safe to move off the active file" already exists; nothing tracks
it independently or archives what it points past.

## Decision

`Journaler.rotate()` runs on the journaler thread, immediately after a SNAPSHOT marker is flushed
and forced (inside the same `onEvent` call that already updates `lastSnapshotOffset`). It closes
the active `FileChannel`, renames `input-events.journal` to an immutable
`input-events-<epoch-millis>.journal` segment, opens a fresh empty `input-events.journal`, and
resets both `writtenBytes` and `lastSnapshotOffset` to 0. Because `LmaxEngine.writeSnapshot()`
(which persists `coveredOffset = journaler.lastSnapshotOffset()` into `snapshot.dat`) runs on the
BLP thread strictly after the journaler thread has processed the same SNAPSHOT event (Disruptor
sequence-barrier ordering — the journaler is upstream of the BLP in the handler chain), the
0-offset it reads always correctly describes the *new* file. Recovery is unaffected: it always
reads `input-events.journal` from byte 0, which after this change just means "from the start of
the current (small) file" instead of "from the start of one ever-growing file".

The closed segment is handed to `JournalArchiver`, which uploads it to a GCS bucket on its own
background thread using AWS SDK v2's `S3Client` pointed at `storage.googleapis.com` — GCS's
documented S3-compatible interoperability mode, authenticated with an HMAC key/secret the same
shape as YU07's `tick-store-gcs-hmac`. A segment is deleted locally only after a confirmed upload;
a failed or unconfigured upload leaves it on disk rather than losing it.

The whole feature is gated by `journal.archive.enabled` (`ORDER_MATCHER_JOURNAL_ARCHIVE_ENABLED`,
default `false`). `Journaler`'s pre-existing constructors are preserved and construct with
`archiver=null`, so `rotate()` is never called and behavior is byte-for-byte unchanged unless a
manifest explicitly opts in.

## Consequences

- No new scheduling primitive: rotation cadence is exactly the existing snapshot cadence
  (`SNAPSHOT_INTERVAL_MS`), so there is one interval to reason about, not two.
- Every prior state's Journaler-dependent tests and benchmarks
  (`AllocationGateTest`, `LmaxHotPathParityTest`, throughput benchmarks) are unaffected — the flag
  defaults off and the legacy code paths are untouched.
- A crash between the file rename and the channel reopen (`Files.move` succeeding,
  `FileChannel.open` failing) would leave the journaler without an active file; `rotate()`'s
  `catch (IOException)` logs and leaves the journaler on its now-renamed-away channel reference,
  which is the same availability-over-durability posture the class already takes on an append
  failure (`failed = true`, sequence keeps advancing) — not a new failure mode, an existing one
  reached a different way.
- Upload correctness depends on an external SDK's SigV4 implementation rather than code in this
  repo — accepted in `research.md` Decision 3 as a smaller risk than a hand-rolled signer.
