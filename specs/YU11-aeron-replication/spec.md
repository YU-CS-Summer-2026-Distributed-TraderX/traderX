# Feature Specification: Aeron + SBE BLP Replication

**Feature Branch**: `YU11-aeron-replication`
**Created**: 2026-07-16
**Status**: In implementation
**Input**: YU11 replication decisions Q1–Q4, parented on `YU10-fix-ingress`

## User Stories

- As the trading-platform operator, I want HA replication capacity materially above the
  File-backed NATS baseline so the warm-standby deployment does not consume most of the
  order-matcher's booked-order capacity.
- As the recovery operator, I want every durable follower ACK to identify an event already
  forced to the follower journal so acknowledged replication never means merely queued in
  memory.
- As the availability owner, I want routine follower loss to degrade to journal-protected solo
  operation with an alert, while deployments requiring synchronous replica durability can opt
  into strict halt behavior.
- As the release operator, I want NATS and Aeron available in the same binary with shadow
  comparison and one-value rollback so transport cutover never requires a code rollback.
- As the incident responder, I want follower restart, empty-volume bootstrap, sidecar restart,
  packet loss, schema mismatch, and Archive faults to remain diagnosable and fail closed at
  readiness or promotion.
- As the latency owner, I want an opt-in 30–60 ms failover detector that does not synchronously
  call the Kubernetes API and still admits only one witness-confirmed primary.

## Functional Requirements

- FR-AR01: The order-matcher SHALL select its BLP replication leg with
  `BLP_REPLICATION_TRANSPORT=nats|aeron`; the default SHALL be `nats`.
- FR-AR02: Both transport implementations SHALL implement the existing `EventHandler<InputEvent>`
  replication seam and SHALL preserve `Journaler` and replication as parallel consumers with the
  matching/risk engine gated behind both.
- FR-AR03: Aeron replication SHALL use reliable unicast UDP through one Archiving Media Driver
  sidecar in each order-matcher pod; Aeron SHALL replace only the BLP replication data/ACK leg.
- FR-AR04: The primary Aeron handler SHALL encode each input event into a fixed 64-byte SBE
  message directly inside an `ExclusivePublication.tryClaim` buffer with no intermediate payload
  copy.
- FR-AR05: The v1 input message SHALL carry the SBE header plus `inputSeq`, `eventTimeMillis`,
  `limitPx`, `priceTicks`, `orderRef`, `accountId`, `securityId`, `qty`, `leaderEpoch`,
  `commandType`, `side`, and `flags` in the fixed layout defined by `data-model.md`.
- FR-AR06: The follower SHALL reject an unknown schema/template/version, required unknown flag,
  stale epoch, duplicate with mismatched checksum, or non-contiguous input sequence before ring
  injection.
- FR-AR07: The follower SHALL decode accepted SBE records directly into a claimed input-ring slot
  and SHALL publish the slot exactly once.
- FR-AR08: The follower SHALL map its local ring sequence to the primary `(leaderEpoch,inputSeq)`
  in a fixed-capacity SPSC structure with bounded backpressure and no dropped watermark.
- FR-AR09: `Journaler.journaledSeq()` SHALL remain post-`FileChannel.force(false)` and SHALL be
  exposed to the follower ACK agent as the exact post-journal/pre-apply durable watermark.
- FR-AR10: In durable ACK mode, the follower SHALL publish the highest contiguous primary
  sequence whose mapped local sequence is at or below `Journaler.journaledSeq()`; ACKs SHALL be
  coalesced and SHALL include epoch and Archive recording position.
- FR-AR11: `BLP_REPLICATION_ACK_MODE=onring|durable` SHALL preserve `onring` as the default;
  `durable` SHALL use FR-AR09/10 for both NATS and Aeron followers.
- FR-AR12: `BLP_REPLICATION_FAILURE_POLICY=degraded-solo|strict` SHALL default to
  `degraded-solo`. In degraded-solo mode, loss of a caught-up follower SHALL alert, stop claiming
  synchronous follower durability, continue admission against the primary journal, and
  automatically re-establish shadow/replication catch-up.
- FR-AR13: Strict policy SHALL require durable ACK mode and SHALL close admission on connection
  loss, ACK timeout, sequence gap, schema mismatch, Archive discontinuity, or follower
  non-readiness. An incompatible strict/on-ring configuration SHALL refuse startup.
- FR-AR14: `BLP_REPLICATION_AERON_SHADOW=true` with NATS authoritative SHALL encode, record,
  consume, and compare the Aeron stream without injecting it into the follower BLP or gating the
  primary; any sequence or payload-checksum mismatch SHALL alert and make the shadow result fail.
- FR-AR15: A coordinated transport change SHALL require both ordinal peers to present the same
  transport, SBE schema checksum, cluster ID, and leader epoch during a signed session handshake;
  mismatch SHALL refuse readiness.
- FR-AR16: The peer handshake SHALL use NetworkPolicy plus a shared-secret HMAC challenge and
  SHALL bind cluster ID, pod UID, StatefulSet ordinal, transport, schema checksum, and epoch to
  the accepted Aeron session/stream IDs.
- FR-AR17: Each sidecar SHALL record the live replication stream in Aeron Archive on the
  order-matcher persistent volume and expose health, recording ID/position, replay position,
  catalog errors, free bytes, retransmits, and loss gaps.
- FR-AR18: A follower with a retained volume SHALL recover its local journal, request Archive
  replay from its checkpoint, merge replay with the live stream, and become ready only after its
  journaled and applied watermarks reach the observed live high watermark.
- FR-AR19: A follower without a valid local checkpoint SHALL install the newest complete
  checksummed snapshot bundle, replay from the bundle's Archive position, and remain unready on
  missing data, checksum failure, schema mismatch, or unavailable replay position.
- FR-AR20: Snapshot checkpoints SHALL persist `(leaderEpoch,inputSeq,recordingId,
  recordingPosition,sbeSchemaVersion)`; Archive segment retention SHALL not pass the minimum
  follower checkpoint.
- FR-AR21: The default `BLP_FAILOVER_MODE=lease` SHALL retain the proven Kubernetes Lease
  acquisition and synchronous admission fence unchanged.
- FR-AR22: `BLP_FAILOVER_MODE=fast-witness` SHALL use direct Aeron heartbeat staleness for failure
  detection and SHALL require an atomic compare-and-set claim in the `TRADERX_BLP_FAST_WITNESS`
  NATS KV bucket before promotion opens admission.
- FR-AR23: Fast-witness mode SHALL fail closed when the witness is unavailable or ambiguous;
  the winner SHALL reconcile the Kubernetes Lease asynchronously and a foreign witness revision,
  epoch, or confirmed Lease holder SHALL demote it before another order admission.
- FR-AR24: Aeron data, ACK, Archive control/replay, and heartbeat channels SHALL use stable
  StatefulSet ordinal DNS and dedicated cluster-internal UDP ports allowed only between
  order-matcher pods.
- FR-AR25: The kind runtime SHALL use a dedicated multi-node cluster profile capable of
  scheduling two required-anti-affinity order-matcher pods and their sidecars.
- FR-AR26: The GKE runtime SHALL keep one order-matcher pod plus sidecar per c2 node, record exact
  image/schema identities, and provide a documented per-PVC expansion/recreate procedure for the
  Archive capacity.
- FR-AR27: The runtime SHALL export transport mode, peer/session state, leader epoch, offered and
  consumed sequence, journaled/applied/ACK watermarks, Archive lag/free bytes, backpressure,
  retransmits, witness revision, and degraded/strict policy state through health and metrics.

## Non-Functional Requirements

- NFR-AR01: The warmed primary Aeron encode/claim path and follower poll/decode/inject path SHALL
  allocate exactly zero bytes on their application transport threads under isolated `-Xbatch`
  ThreadMXBean gates; existing producer, journaler, BLP, risk, and `noGcTest` gates remain exact
  zero.
- NFR-AR02: The sidecar SHALL use shared threading mode within a `750m` CPU request, `1` CPU
  limit, `512Mi` memory request, and `1Gi` memory limit. A throughput gain obtained by exceeding
  the one-core sidecar budget SHALL fail acceptance.
- NFR-AR03: Aeron HA SHALL achieve at least 35,000 booked orders/s and at least 25% above the
  immediately preceding File-backed NATS HA result across three comparable 30-second GKE runs,
  with zero failed or risk-misclassified submissions.
- NFR-AR04: Single-BLP REST, REST batch, and journaled-BLP controls SHALL remain within 5% or
  measured noise of same-day parent-state controls; risk p99 and output-topology results SHALL
  show no material regression.
- NFR-AR05: The default Lease path SHALL reach accepting traffic within 3 seconds p95 after a
  healthy-follower primary kill, with every acknowledged order present. Fast-witness mode SHALL
  record detection, witness claim, and admission-open timestamps against the 30–60 ms target.
- NFR-AR06: Asymmetric partition, 1%/5% induced loss, sidecar kill, follower kill/restart, primary
  kill, DNS/pod-IP change, empty follower volume, Archive corruption, Archive disk-full, and N/N-1
  schema vectors SHALL produce no ready/promotable sequence gap.
- NFR-AR07: Every benchmark SHALL store branch, HEAD, application and sidecar image identities,
  schema checksum, transport/policy configuration, node shape, per-run results, arithmetic mean,
  immediately preceding comparator, and same-day single-BLP control.

## Technical Debt Register

- TD-AR01: Fast-witness mode depends on the single deployed NATS/JetStream service as its atomic
  tiebreaker. Witness loss preserves safety by refusing promotion but reduces failover
  availability.
- TD-AR02: The deployment has two BLP replicas in one zone. The primary journal and its GCS
  archival path remain the durability authority when degraded-solo mode is active.

## Success Criteria

- SC-AR01: NATS-authoritative shadow mode processes a deterministic input mix through both
  transports with identical contiguous sequences and payload checksums and zero BLP double
  injection.
- SC-AR02: Aeron-authoritative mode survives follower restart and retained-volume Archive catch-up
  with journaled/applied watermarks converged before readiness.
- SC-AR03: Empty-volume bootstrap installs a checksummed snapshot bundle and merges Archive replay
  to live; corrupt, missing, or wrong-schema input remains unready and unpromotable.
- SC-AR04: Durable ACK proof demonstrates no ACK before `Journaler.journaledSeq()` advances and
  measures its cost against the on-ring ACK on the same host/configuration.
- SC-AR05: Degraded-solo mode continues journal-protected order admission after follower loss and
  emits an alert; strict mode refuses admission for the same fault.
- SC-AR06: Default Lease failover meets the 3-second p95 gate with every acknowledged order
  present; fast-witness mode records the 30–60 ms target without a double witness winner.
- SC-AR07: Three comparable GKE runs satisfy both NFR-AR03 throughput gates and all regression
  controls; otherwise NATS remains the selected transport.
- SC-AR08: Generation exits zero, the architecture document is generated from its model, every
  ancestor shared-file marker is present, and the full correctness/allocation/no-GC matrix passes.
