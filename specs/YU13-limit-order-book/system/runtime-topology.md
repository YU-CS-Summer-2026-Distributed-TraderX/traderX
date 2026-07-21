# Runtime Topology: YU13-limit-order-book

YU13 changes the matching policy hosted inside the cluster service — a genuine crossing
limit-order book replaces price-triggered auto-fill — and leaves the process, networking, and
health topology inherited from YU12 intact. The book is BLP-thread-private state inside the
`ClusteredService`; it adds no process, port, or volume.

## Entrypoints

| Entrypoint | Transport | Consumer |
|---|---|---|
| gateway REST port | HTTP REST/UI | unchanged inherited clients |
| gateway FIX port | FIX 4.4 over TCP | unchanged inherited FIX initiators |
| cluster ingress/egress UDP | Aeron Cluster client protocol | gateway tier and feed adapter |
| member consensus UDP ports | Aeron Cluster consensus/log/catch-up | cluster members |
| archive control/replay UDP | Aeron Archive protocol | member recovery and snapshot retrieval |

## Components

- **order-matcher cluster member (3 StatefulSet replicas)**: one pod runs the Media Driver,
  Archive, Consensus Module, and the clustered service container hosting the `MatchingEngine`
  (now a crossing limit-order book: per-security two-sided `LimitBook` with price-time priority)
  and the two-tier risk core. Per-pod PVC holds the consensus log and snapshots (format 2: the
  header carries book geometry and each created book's band anchor precedes its order rows).
  Stable StatefulSet ordinals provide member identity; the `blp-pool` dedicated-core pinning
  applies to the single service thread.
- **fix-gateway tier**: terminates counterparty FIX sessions and REST connections, screens
  admission against control-feed state, forwards through the Aeron Cluster client, and re-points
  on leader change without dropping counterparty sessions. **Scales out horizontally** — each
  replica holds its own cluster session + owner thread, so N replicas = N× parallel ingress; the
  `order-matcher-gw` Service round-robins REST (no affinity) and the separate
  `order-matcher-gw-fix` Service pins FIX with `sessionAffinity: ClientIP` — k8s affinity is
  per-Service, not per-port, so one combined Service would pin REST too (ADR-047).
- **feed adapter**: consumes inherited NATS pricing/control subjects and publishes conflated
  ticks and policy updates as cluster ingress.
- **trade-egress bridge** (`TradeNatsPublisher`, ADR-048): on the leader, republishes every booked
  trade from the deterministic apply stream to NATS `/trades` (non-blocking enqueue off the apply
  thread), so cluster fills reach `trade-processor` → the SQL DB → the UI blotter/position feeds.
- **NATS/JetStream**: inherited non-replication roles only — pricing, control feeds, output
  distribution (incl. the trade-egress bridge), EOD gating. No replication leg, no witness bucket.
- **trade-processor, MariaDB, position-service, UI, downstream services**: unchanged inherited CQRS
  topology, now fed by committed cluster outputs via the trade-egress bridge — `trade-processor`
  consumes `/trades`, persists Trade + Position to MariaDB, and republishes `/accounts/*/trades` +
  `/positions` to the UI's NATS websocket.

## Networking

- Cluster members exchange consensus, log, and catch-up traffic over dedicated cluster-internal
  UDP ports between stable StatefulSet ordinal DNS names on a headless Service with
  `publishNotReadyAddresses: true`.
- The gateway and feed adapter reach members over the cluster ingress/egress ports; a
  namespace-scoped NetworkPolicy restricts every Aeron port to the participating pods.
- No Aeron port uses ingress-nginx, LoadBalancer, NodePort, IP multicast, or host mappings.
- Kind uses a dedicated named multi-node cluster with three schedulable workers and required
  anti-affinity; the shared single-node cluster is not modified.
- GKE required anti-affinity keeps one member per `blp-pool` node.

## Startup / Health Order

1. Each member opens its Aeron directory, validates the Archive catalog and cluster mark file,
   and recovers: newest valid snapshot loaded, committed log applied strictly after the snapshot
   position, generator assertion passed.
2. Members complete Raft election; a majority elects exactly one leader.
3. A wiped replacement member retrieves the latest snapshot and replays the committed log tail
   before reporting follower readiness.
4. The feed adapter connects and sequences control/pricing ingress; gateway control-feed
   admission state becomes valid.
5. The gateway opens counterparty admission only when cluster readiness and admission-state
   readiness both hold.

## Degraded Behavior

| Condition | Behavior |
|---|---|
| One member lost (of three) | Majority holds; commit and admission continue; the replacement rejoins via snapshot retrieval + log replay. |
| Leader lost | Raft re-election among the majority; the gateway re-points on the leader signal; counterparty sessions stay connected. |
| Partition minority | The minority cannot elect a leader, extend the log, or admit orders; it rejoins and truncates uncommitted entries on heal. |
| Two members lost (of three) | No majority: commit and admission stop; state is preserved on the surviving log/snapshot volumes. |
| Gateway instance lost | Counterparty sessions drop to ordinary reconnect; cluster state is unaffected; REST routing resumes on the replacement. |
| Feed adapter lost | No new ticks/control updates are sequenced; order flow continues against last-applied state; adapter restart resumes ingress. |
| Snapshot/log disk pressure | Members surface archive/log disk state through health; recording refuses before unsafe exhaustion. |
| Generator assertion failure on recovery | The member refuses readiness and does not serve or vote leadership with invalid state. |
| Off-grid or out-of-band limit price | The order rejects at admission (INVALID off-grid, PRICE_COLLAR out-of-band) before any reservation; the book and every other order are untouched. |
| Restored open order outside the restored band | Recovery fails closed: a book row the restored band geometry cannot hold means the anchor or geometry did not survive intact, so the member refuses readiness rather than serve a divergent book. |
| Market order with no available depth and no mark | The order rejects PRICE_MISSING; it never rests (market orders never rest) and reserves nothing. |
