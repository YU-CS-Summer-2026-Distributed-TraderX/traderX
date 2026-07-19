# Feature Pack: YU12-aeron-cluster

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: Implemented and proven on GKE (failover, throughput, snapshots, safe rolling ops) — see generation/implementation-status.md
Track: `architecture`
Lineage role: `optional`
Previous state: `YU11-aeron-replication`

This pack moves BLP high availability from the hand-built two-node primary/standby model
(Kubernetes Lease election, custom Aeron MDC replication, NATS KV fast witness, snapshot-bundle
recovery) to Aeron Cluster: a Raft consensus module replicating one committed log into a
deterministic `ClusteredService` that hosts the inherited `MatchingEngine` and two-tier risk
logic unchanged. Election, log replication, commit, snapshotting, and member catch-up become
consensus-library primitives; a partition minority is structurally unable to elect a leader.
Order-entry ingress moves to a gateway tier that terminates FIX/REST sessions and follows the
cluster leader, so counterparty sessions survive failover.

Primary intent:

- host the deterministic matching/risk core inside `ClusteredService` callbacks with every input
  — orders, cancels, price ticks, control updates — entering exclusively as consensus-log ingress,
- bind `onTakeSnapshot` state to the exact applied log position and carry every future-output
  generator (order references, trade counters, idempotency, risk reservations, symbol identity,
  control versions) in replicated state, with a strict no-ID-reuse assertion on recovery,
- let a wiped replacement member rejoin through snapshot retrieval plus committed-log replay
  instead of the parent state's bundle transport and marker negotiation,
- keep the CQRS/read-model side, the SBE wire codecs, and the benchmark harnesses unchanged,
- prove client-observed failover under one second through the leader-following gateway and
  throughput at or above the stored YU11 Aeron HA baseline.

Core artifacts:

- `generation/runtime-overrides/order-matcher/` — clustered service hosting of the matching/risk
  core, cluster snapshot/restore, single-node cluster spike proof
- `system/adr-044` … `adr-047` — consensus vehicle, single-input consensus log, snapshot
  completeness, gateway tier
- `system/architecture.model.json` — generated architecture flow for the cluster topology

Target runtime behavior:

- three cluster members (odd quorum) each run Media Driver + Archive + Consensus Module +
  service container in one pod with per-pod log/snapshot storage,
- the Raft majority elects and fences the leader with no Kubernetes control-plane or external
  witness dependency; a minority partition refuses leadership and admission,
- recovery loads the newest snapshot and resumes strictly after its log position, asserting every
  restored ID generator exceeds every identifier ever issued,
- readiness distinguishes cluster-recovered deterministic state from asynchronously refreshed
  gateway/control state and opens admission only when both are valid.
