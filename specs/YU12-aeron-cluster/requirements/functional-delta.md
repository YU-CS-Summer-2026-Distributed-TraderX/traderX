# Functional Delta: YU12-aeron-cluster (vs YU11-aeron-replication)

The matching rules, two-tier risk logic, SBE wire codecs, order lifecycle and trade-booking
behaviour, the CQRS read-model side, and every inherited REST, FIX and NATS distribution contract
carry forward from `YU11-aeron-replication` unchanged — external consumers see the same system.
What changes is how that core is made highly available: the hand-built primary/standby replication
of the parent state is replaced by a three-member Aeron Cluster, where Raft consensus provides
election, log replication, commit ordering, snapshotting and member catch-up as library primitives.

## Added

- The inherited matching and risk core runs inside an Aeron Cluster `ClusteredService`, so a Raft
  majority — not custom plumbing — decides election, replication and commit.
- Three cluster members form an odd quorum, each running its Media Driver, Archive, Consensus
  Module and service container in one pod with per-pod log and snapshot storage.
- A partition minority is structurally unable to elect a leader, extend the committed log, or admit
  orders, which retires the fencing proofs the parent state depended on.
- Snapshots capture the complete deterministic state bound to the exact applied log position: book,
  every future-output generator, idempotency, risk, symbol identity and control versions.
- Recovery loads the newest valid snapshot and resumes strictly after its position, asserting every
  restored generator exceeds every identifier ever issued and failing closed if it does not.
- A replacement member with an empty volume rejoins on its own through snapshot retrieval plus
  committed-log replay, with no operator-run bundle transfer or marker negotiation.
- A stateless-forward gateway tier terminates FIX and REST order entry and follows the cluster
  leader internally, so a leader change costs counterparty sessions no reconnect or re-logon.
- Health and metrics expose cluster role, member ID, leadership term, commit, service and snapshot
  positions, election state, and log and snapshot disk state.
- Cluster member, required anti-affinity, per-pod PVC and NetworkPolicy runtime configuration for
  the dedicated multi-node kind profile and the GKE `blp-pool`, so members never share a node.

## Changed

- The consensus log is the only input path: orders, cancels, price ticks and control updates all
  arrive as sequenced ingress, and no member applies anything from a side channel.
- Readiness is now two distinct signals — deterministic state recovered from the cluster, and
  asynchronously refreshed admission state — and order admission opens only when both are valid.

## Removed

- Kubernetes Lease leader election and the `TRADERX_BLP_FAST_WITNESS` NATS KV witness: leadership
  no longer depends on the Kubernetes control plane or any external observer.
- The parent state's custom Aeron MDC replication, input journal, journal-reader recovery and
  snapshot-bundle transfer, together with the `BLP_REPLICATION_*` and `BLP_FAILOVER_MODE`
  configuration that drove them.

## Specified, implementation pending

- A feed adapter conflating the inherited NATS pricing and control feeds into sequenced cluster
  ingress — built and compiling, with live NATS verification still open.
