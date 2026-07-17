# ADR-044: Aeron Cluster Raft consensus replaces the hand-built HA stack

Status: Accepted

## Context

The parent state's HA machinery is hand-built: Kubernetes Lease election, custom manual-MDC
replication, a NATS KV compare-and-set witness, and a five-slice snapshot-bundle recovery path.
Each piece required adversarial proof, and the proof campaign surfaced the cost — eight
compensating replication fixes, a demonstrated post-promotion ID reuse, and ~17 s kind
promotions through the Lease/heartbeat path. Meanwhile the transport premise is settled: the
same Aeron stack measured 520,520 events/s against the 10,561 File-NATS baseline, and the system
is a waypoint toward a production matching engine, so production-grade fault tolerance is in
scope.

## Decision

Host the BLP as a replicated deterministic state machine in Aeron Cluster: three members (odd
quorum), each running Media Driver + Archive + Consensus Module + clustered service container in
one pod. The consensus module owns election, log replication, commit, and member catch-up. The
inherited `MatchingEngine` and two-tier risk logic run unchanged inside
`ClusteredService.onSessionMessage`; the SBE codecs are reused as the ingress encoding.

Deleted outright: `LeaderElection` (k8s Lease), `FastWitness` (NATS KV CAS), the
`AeronReplicator`/`AeronReplicationFollower` MDC machinery, the `Journaler` input journal, the
`JournalReader` recovery path, and the bundle capture/transfer/install stack. The consensus log
and cluster snapshots subsume all of them.

## Consequences

Split-brain becomes structurally impossible — a partition minority cannot win a majority vote,
so fencing is by construction rather than by timing proof. Election leaves the Kubernetes
control plane; pods remain ordinary StatefulSet members with stable identities and per-pod
volumes. The BLP pool costs three nodes instead of two, and the cluster brings its own
operational surface (membership, snapshot and log management) in place of the deleted machinery.
Majority commit adds a quorum round-trip on the commit path; the parent design already awaited a
follower ACK, and the throughput gate (at or above the stored Aeron HA baseline) verifies the
delta rather than assuming it.
