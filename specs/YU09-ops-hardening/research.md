# Research: YU09 — Ops Hardening

## Decision 1: rotate the journal at the existing snapshot boundary, not a separate timer

`SnapshotStore.Data` already persists `coveredOffset = journaler.lastSnapshotOffset()` at every
periodic snapshot (`LmaxEngine.writeSnapshot()`), and recovery already seeks to that absolute byte
offset within `input-events.journal` and replays forward (`LmaxEngine`'s
`snapshot(@offset)+tail` recovery mode). That means the moment a snapshot is written, every byte
before `coveredOffset` is redundant — the snapshot alone reconstructs that state. Rotating exactly
at that point turns "redundant bytes" into "a closed file safe to archive and delete locally",
using an invariant the system already maintains rather than inventing a new one.

Sequencing is also already safe: `Journaler` and `MatchingEngine` are both `EventHandler`s on the
same input Disruptor ring, and the code comment on `Journaler` states the journaler runs "ahead of
the BLP" — a Disruptor sequence-barrier dependency, not a race. `writeSnapshot()` runs on the BLP
thread after the SNAPSHOT event reaches it, which is strictly after `Journaler.onEvent` has
already processed the same event. So rotating inside `Journaler.onEvent` (resetting
`lastSnapshotOffset` to 0 for the new file) is guaranteed to complete before `writeSnapshot()`
reads it — no new synchronization needed.

A separate rotation timer was considered and rejected: it would need its own coordination with the
snapshot schedule to avoid rotating mid-replay-window (rotating between a snapshot and its
covered-offset write would corrupt the offset), and would duplicate scheduling machinery
(`snapshotScheduler`) that already exists for exactly this cadence.

## Decision 2: gate the whole feature behind `journal.archive.enabled`, default `false`

Every prior state's Journaler behavior (one ever-growing file, `AllocationGateTest`,
`LmaxHotPathParityTest`, and every throughput benchmark) is unchanged when the flag is unset —
`Journaler`'s existing two-arg and four-arg constructors still exist and construct with
`archiver=null`, and `onEvent` only calls `rotate()` when `archiver != null`. This makes the
feature strictly additive: nothing about existing tests, benchmarks, or deployed states changes
unless a manifest explicitly opts in.

## Decision 3: AWS SDK v2's S3 client for the GCS upload, not hand-rolled HMAC signing

GCS's XML API is S3-compatible by design — the documented interoperability path for HMAC
credentials is to point any S3 SDK at `storage.googleapis.com` with the HMAC key/secret as the
AWS access key/secret. YU07's Python capture path gets this for free through DuckDB's `httpfs`
extension; order-matcher is a JVM service with no DuckDB, so the equivalent needs an explicit S3
client. Implementing AWS SigV4 request signing by hand was considered and rejected: signature bugs
are silent (a malformed signature just fails auth, with no local way to verify correctness without
a live bucket), and the failure mode of a bug in hand-rolled signing is silent data loss (journal
segments accumulate locally forever, never actually uploading) — the risk of getting it wrong
outweighs the cost of one added dependency for a well-tested, widely-used signer.

## Decision 4: a separate `order-matcher-journal-gcs-hmac` Secret, not reuse of `tick-store-gcs-hmac`

The two archival paths write to different buckets for different data (tick-store: market
data/fills for research; order-matcher: raw journal segments for disaster recovery). Reusing one
service account's HMAC credential across both would mean either over-provisioning that account's
bucket access or under-provisioning it for one of the two uses. A second bucket-scoped service
account, mirroring the same HMAC-key-pair shape, keeps least privilege intact and matches the
existing pattern exactly rather than extending it.

## Decision 5: DR scoped to a runbook, not real multi-region GKE

The cluster is deliberately single-zone (see root `CLAUDE.md`'s own "Remaining" section) — this is
a research fork of a demo trading platform, not a production system with an SLA. Standing up
genuine multi-region GKE (cross-region cluster, cross-region MariaDB replication, global load
balancing, cross-region journal replication ahead of the BLP's single-writer model) is a
multi-week infrastructure project disproportionate to what this fork needs, and would still leave
the fundamental architectural fact unaddressed: the BLP is a single-threaded, single-writer engine
by design (see `LMAX-BLP.md`) — a second, independently-writing region isn't a small extension of
today's replication, it's a different consistency model. Documenting the cluster's actual failure
modes and today's real recovery levers (redeploy-from-git, journal+snapshot recovery,
node-pool resize) is the proportionate deliverable; it also correctly represents that zone loss is
currently unrecoverable within the cluster, rather than implying a false safety net.
