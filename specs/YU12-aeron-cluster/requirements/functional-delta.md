# Functional Delta: YU12-aeron-cluster

Parent: `YU11-aeron-replication`

| ID | Delta |
|---|---|
| FD-AC01 | Host the inherited deterministic matching/risk core in an Aeron Cluster `ClusteredService`; Raft majority provides election, log replication, and commit. |
| FD-AC02 | Run three cluster members (odd quorum), each with Media Driver + Archive + Consensus Module + service container in one pod and per-pod log/snapshot storage. |
| FD-AC03 | Make the consensus log the only input path: orders, cancels, price ticks, and control/policy updates enter as sequenced SBE ingress; remove every side-channel input. |
| FD-AC04 | Remove the k8s Lease election, NATS KV fast witness, custom MDC replication, input journal, journal-reader recovery, and snapshot-bundle transfer machinery. |
| FD-AC05 | Persist complete deterministic state in `onTakeSnapshot` bound to the applied log position, including every future-output generator, idempotency, risk, symbol, and control-version state. |
| FD-AC06 | Recover from the newest valid snapshot plus the committed log strictly after its position, asserting every restored generator exceeds every identifier ever issued; fail closed otherwise. |
| FD-AC07 | Rejoin a wiped replacement member through snapshot retrieval plus committed-log replay with no operator intervention. |
| FD-AC08 | Add a stateless-forward gateway tier terminating FIX/REST, screening admission, and following the cluster leader without dropping counterparty sessions. |
| FD-AC09 | Add a feed adapter sequencing conflated pricing and control-feed updates as cluster ingress. |
| FD-AC10 | Split readiness into cluster-recovered deterministic state and asynchronously refreshed admission state; open admission only when both are valid. |
| FD-AC11 | Add cluster member/anti-affinity/PVC/NetworkPolicy runtime configuration for the dedicated kind profile and the GKE `blp-pool`. |
| FD-AC12 | Expose cluster role, term, commit/service/snapshot positions, election state, and log disk state through health and metrics. |

The REST, FIX, risk-decision, order-lifecycle, trade-booking, read-model, and every inherited
NATS distribution contract remain unchanged for external consumers.
