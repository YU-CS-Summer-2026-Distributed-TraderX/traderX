# Feature Specification: Aeron Cluster BLP Consensus

**Feature Branch**: `YU12-aeron-cluster`
**Created**: 2026-07-17
**Status**: In implementation
**Input**: Aeron Cluster migration brief,
YU11 recovery proof invariants, parented on `YU11-aeron-replication`

## User Stories

- As the trading-platform operator, I want BLP high availability provided by Raft consensus so a
  partition minority is structurally unable to elect a leader or admit orders, replacing fencing
  proofs that must hold under adversarial timing.
- As the availability owner, I want leader election to complete inside the cluster without the
  Kubernetes control plane or an external witness, so failover speed is bounded by Raft election
  rather than Lease renewal and pod-termination observation.
- As the recovery operator, I want a replacement member with an empty volume to rejoin by
  retrieving a snapshot and replaying the committed log, with no hand-built bundle transport,
  marker negotiation, or journal-cut machinery.
- As the correctness owner, I want every future-output generator — order references, trade
  counters, idempotency state, risk reservations, symbol identity, control versions — carried in
  the replicated snapshot/log state, so a recovered or promoted member can never reissue an
  identifier it has already used.
- As a FIX counterparty, I want my session terminated on a gateway tier that survives a BLP
  leader change, so failover does not force reconnect, re-logon, or sequence renegotiation.
- As the release operator, I want the deterministic matching/risk core reused as-is inside the
  cluster service, so the migration replaces replication plumbing without changing matching,
  risk, or downstream CQRS contracts.

## Functional Requirements

- FR-AC01: The order-matcher BLP SHALL run as a replicated deterministic state machine hosted in
  an Aeron Cluster `ClusteredService`; the consensus module SHALL provide leader election, log
  replication, and commit ordering via Raft majority vote.
- FR-AC02: The cluster SHALL run an odd number of members (three by default); each member SHALL
  run its Media Driver, Archive, Consensus Module, and clustered service container together in
  one pod with per-pod persistent storage for the log and snapshots.
- FR-AC03: Every input to the state machine — order commands, cancels, price ticks, and
  control/policy updates — SHALL enter exclusively as consensus-log ingress messages; no replica
  SHALL apply any input from a side channel.
- FR-AC04: Cluster ingress SHALL use the inherited SBE wire codecs (the fixed 64-byte
  `InputEventMessage` family); unknown schema, template, version, or required flag SHALL be
  rejected at the ingress boundary before sequencing.
- FR-AC05: The service SHALL apply committed messages on a single thread through the inherited
  `MatchingEngine` and two-tier risk logic; matching rules, risk calculations, and output event
  shapes are unchanged from the parent state.
- FR-AC06: Time-driven behavior SHALL use cluster time and `onTimerEvent`; the service SHALL NOT
  read wall clock, system entropy, or any cross-thread mutable value inside a state transition.
- FR-AC07: `onTakeSnapshot` SHALL persist the complete deterministic state bound to exactly the
  service's applied log position: order book, `nextOrderRef` and every other future-output
  generator, trade counters, idempotency state, risk reservations and balances, symbol-table
  identity, and control/policy versions.
- FR-AC08: Recovery SHALL load the newest valid snapshot and resume applying the log strictly
  after that snapshot's position — re-applying nothing the snapshot covers and skipping nothing
  after it.
- FR-AC09: On recovery and on promotion the service SHALL assert that every restored ID generator
  strictly exceeds every identifier ever issued; violation SHALL fail closed by refusing
  readiness.
- FR-AC10: A replacement member with an empty volume SHALL rejoin by retrieving the latest
  snapshot and replaying the committed log tail from the cluster, reaching follower readiness
  without operator intervention.
- FR-AC11: Leader election SHALL have no Kubernetes control-plane or external-witness dependency;
  the k8s Lease election, the `TRADERX_BLP_FAST_WITNESS` NATS KV witness, and the custom
  MDC replication and bundle-transfer machinery of the parent state are removed.
- FR-AC12: A partition minority SHALL NOT elect a leader, extend the committed log, or admit
  orders.
- FR-AC13: Order-entry ingress SHALL run on a gateway tier that terminates FIX counterparty
  sessions and REST connections and forwards to the cluster leader through the Aeron Cluster
  client; on leader change the gateway SHALL re-point internally without dropping counterparty
  FIX sessions.
- FR-AC14: Committed outputs SHALL feed the inherited CQRS side unchanged: order lifecycle
  events flow to the projector/read-model and the inherited NATS distribution subjects under
  their existing contracts.
- FR-AC15: Readiness SHALL distinguish deterministic state recovered from the cluster
  snapshot/log from asynchronously-refreshed gateway/control-feed state used to admit commands;
  admission SHALL NOT open until the latter is valid at or beyond the recovery boundary.
- FR-AC16: The kind runtime SHALL schedule three cluster members with required anti-affinity on a
  dedicated multi-node cluster profile; the GKE runtime SHALL run one member per `blp-pool` node.
- FR-AC17: Health and metrics SHALL expose cluster role, member ID, leadership term, commit
  position, service position, snapshot position, election state, and log/snapshot disk state.

## Non-Functional Requirements

- NFR-AC01: The clustered service application thread SHALL allocate exactly zero bytes after
  warm-up under the inherited isolated `-Xbatch` ThreadMXBean gates; the inherited base, risk,
  and Epsilon no-GC gates remain exact-zero.
- NFR-AC02: Three comparable 30-second GKE runs through the inherited `run-gke-bench.sh` harness
  (label `aeron-cluster`) SHALL meet or exceed the stored YU11 Aeron HA baseline — 25,149
  booked/s on the cleanest run, single-BLP parity — with zero failed or risk-misclassified
  submissions.
- NFR-AC03: Client-observed failover — leader kill to first accepted order through the gateway —
  SHALL complete in under 1,000 ms.
- NFR-AC04: The no-ID-reuse recovery proof SHALL pass: orders issued after a snapshot, recovery
  from snapshot plus log tail, promotion of the recovered member, and the next generated order ID
  strictly greater than every ID ever issued — not merely every ID still retained in memory.
- NFR-AC05: A three-member cluster SHALL preserve commit availability through one member failure;
  log disk usage SHALL be bounded by post-snapshot log management.
- NFR-AC06: Every benchmark record SHALL store branch, HEAD, image identities, schema and
  configuration identity, node shape, per-run results, arithmetic mean, and same-day comparators.

## Technical Debt Register

- TD-AC01: The gateway tier is a stateless-forward deployment; its counterparty FIX session state
  lives only on the gateway instance, so gateway loss drops counterparty sessions to reconnect
  while cluster order state is unaffected.
- TD-AC02: All cluster members deploy in one zone; the snapshot/log persistent volumes are the
  durability authority for zone-level loss.

## Success Criteria

- SC-AC01: A single-member cluster hosts the inherited `MatchingEngine` as a `ClusteredService`:
  an order submitted through the cluster client round-trips the consensus log and matches, and a
  snapshot/restart cycle restores the book and ID generators with the strict no-reuse assertion
  passing.
- SC-AC02: A three-member kind cluster elects a leader, survives leader kill with majority
  re-election, and a wiped replacement member rejoins to readiness via snapshot retrieval plus
  log replay.
- SC-AC03: The no-ID-reuse recovery matrix passes, including the corruption, interrupted-install,
  and epoch-change cases inherited from the parent state's recovery tests.
- SC-AC04: Client-observed failover through the gateway measures under 1,000 ms with the
  counterparty FIX session surviving the leader change.
- SC-AC05: Three GKE runs satisfy NFR-AC02 and the full allocation/no-GC gate matrix passes.
- SC-AC06: Generation exits zero, every ancestor shared-file marker survives, and the
  architecture document is generated from its model.
