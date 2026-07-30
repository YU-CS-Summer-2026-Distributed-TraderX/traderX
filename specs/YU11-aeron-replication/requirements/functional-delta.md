# Functional Delta: YU11-aeron-replication (vs YU10-fix-ingress)

Parent: `YU10-fix-ingress`

Every YU10 external contract carries forward: REST and FIX ingress, entitlement and risk checks, the
matching engine, output publication, and every inherited NATS subject. YU11 changes one leg only —
how the primary order-matcher ships each input event to its warm-standby follower, how that follower
acknowledges it, and what the health and metrics payloads report about it. File-backed NATS stays the
default and the rollback path; Aeron plus SBE arrives beside it as a measured alternative, selectable
with a single environment value.

## Added

- A second replication transport chosen at startup with `BLP_REPLICATION_TRANSPORT=nats|aeron`, so a
  transport cutover is a config change rather than a code rollback (default `nats`).
- Aeron reliable unicast UDP replication through an Archiving Media Driver sidecar in each
  order-matcher pod, wired for compose, a dedicated multi-node kind profile, and GKE.
- A NetworkPolicy permitting the Aeron data, ACK, control, and replay UDP ports only between
  `app=order-matcher` pods, so replication traffic never leaves the order-matcher pod set.
- Archive storage on the order-matcher persistent volume, with a documented capacity-expansion and
  `--cascade=orphan` StatefulSet recreate procedure that grows a claim without deleting pods.
- A fixed 64-byte SBE input record encoded directly into an Aeron `tryClaim` buffer, so the primary's
  send path adds no intermediate payload copy.
- Follower validation that rejects an unknown schema, a required unknown flag, a stale leader epoch,
  a mismatched checksum, or a non-contiguous sequence before ring injection.
- Decoding of each accepted record straight into a claimed input-ring slot, published exactly once,
  which leaves the follower path free of both an intermediate copy and duplicate injection.
- A fixed-capacity SPSC map from the follower's local ring sequence to the primary
  `(leaderEpoch, inputSeq)`, with bounded backpressure and no dropped watermark.
- Durable ACK mode (`BLP_REPLICATION_ACK_MODE=onring|durable`, default `onring`) in which the
  follower acknowledges only sequences already forced to its journal, never merely queued in memory.
- Coalesced durable ACKs carrying leader epoch and Archive recording position, applied to NATS
  followers as well as Aeron ones, so durability semantics do not depend on the transport.
- A follower-loss policy switch (`BLP_REPLICATION_FAILURE_POLICY=degraded-solo|strict`, default
  `degraded-solo`): degraded-solo alerts and keeps admitting against the primary journal.
- Strict policy that closes admission on the same faults and requires durable ACK mode; a
  strict/on-ring combination refuses startup rather than promising durability it cannot deliver.
- Shadow mode (`BLP_REPLICATION_AERON_SHADOW=true`) that encodes, records, consumes, and
  checksum-compares the Aeron stream while NATS stays authoritative, without double injection.
- Per-pod Aeron Archive recording of the live stream, with `(leaderEpoch, inputSeq, recordingId,
  recordingPosition, sbeSchemaVersion)` checkpoints persisted as the follower's replay origin.
- Archive-backed follower catch-up: replay merged into the live stream for a retained volume, and a
  checksummed snapshot bundle installed for an empty one.
- A signed HMAC peer handshake binding cluster ID, transport, schema checksum, and leader epoch, so
  a mismatched pair of replicas never reaches readiness.
- An opt-in `fast-witness` promotion path whose atomic compare-and-set claim in the
  `TRADERX_BLP_FAST_WITNESS` NATS KV bucket gates admission, with asynchronous Lease reconciliation.
- Health and metric surfaces for transport mode, ACK mode, failure policy, peer session state,
  leader epoch, and the journaled, received, and acknowledged follower watermarks.
- Witness, shadow, and failover surfaces: witness revision and epoch, shadow mismatch count,
  offer-failure backpressure, and fast-failover detection, claim, and admission phase timings.

## Changed

- Replication now runs through a delegating selector on the existing `EventHandler<InputEvent>` seam;
  journaling and replication stay parallel consumers with matching and risk gated behind both.
- `Journaler.journaledSeq()` is exposed to the follower ACK agent as the exact post-force,
  pre-apply durable watermark, rather than remaining internal to the journal.
- The Kubernetes Lease promotion path remains the default and is unchanged, but failover is now
  selected explicitly through `BLP_FAILOVER_MODE=lease|fast-witness`.

## Specified, implementation pending

- Archive segment retention held behind the minimum follower checkpoint, so reclaiming recorded
  segments remains an operator action rather than an automatic one.
- Archive fault handling: the `BLP_ARCHIVE_MIN_FREE_BYTES` fail-closed disk watermark and
  catalog-error handling are contract text with no runtime enforcement yet.
- Archive lag and free-byte gauges and the applied watermark, named in the health and metrics
  contracts but absent from the exported metric set and the health payload.
- Live multi-node kind and GKE evidence: those manifests and wrappers render, but no live run yet
  backs the Aeron throughput, recovery, and failover gates.
