# Contract Delta: YU12-aeron-cluster

## 1. Existing external contracts

REST `/orders`, REST `/orders/batch`, FIX 4.4 order entry, risk decisions, order lifecycle
output, trade booking, UI routing, and every inherited NATS subject retain their parent-state
contracts for external consumers. The FIX/REST termination point moves to the gateway tier; the
wire contracts themselves are unchanged. `risk.entitlement.enforced` remains `false`.

## 2. Removed parent-state configuration

`BLP_REPLICATION_TRANSPORT`, `BLP_REPLICATION_AERON_SHADOW`, `BLP_REPLICATION_ACK_MODE`,
`BLP_REPLICATION_FAILURE_POLICY`, `BLP_FAILOVER_MODE`, `BLP_FAST_WITNESS_BUCKET`, and the peer
replication channel/stream variables are removed with the machinery they configured. Consensus,
replication, commit, and election are internal to the cluster.

## 3. Cluster configuration

| Variable | Values / default | Contract |
|---|---|---|
| `BLP_CLUSTER_MEMBER_ID` | integer from the StatefulSet ordinal | This member's Raft identity; stable across restarts and volume replacement. |
| `BLP_CLUSTER_MEMBERS` | static member endpoint list | Consensus/ingress endpoints per member, keyed by member ID; identical on every member. |
| `BLP_CLUSTER_DIR` | path on the per-pod PVC | Consensus log, snapshots, and cluster mark file. |
| `BLP_CLUSTER_APPOINTED_LEADER_ID` | integer; unset in HA | Fixes the leader for the single-member proof runtime; unset for elected three-member operation. |

## 4. Snapshot/recovery contract

A snapshot is bound to exactly one applied log position and contains the complete deterministic
state enumerated in `data-model.md`. Recovery resumes strictly after the snapshot position and
asserts every restored ID generator strictly exceeds every identifier ever issued; violation
refuses readiness. A wiped member rejoins via snapshot retrieval plus committed-log replay.

## 5. Readiness contract

Member readiness requires: valid recovered deterministic state (assertion passed), cluster
role established, and — for admission — gateway/control-feed state valid at or beyond the
recovery boundary. The two readiness signals are exposed distinctly.
