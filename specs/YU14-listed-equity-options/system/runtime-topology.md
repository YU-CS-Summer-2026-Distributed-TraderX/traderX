# Runtime Topology: YU14-listed-equity-options

YU14 changes the instrument model and the risk gate's notional arithmetic inside the cluster
service, and leaves the process, networking, and health topology inherited from YU13 intact.
The contract multiplier is BLP-thread-private dense state inside `BlpRiskState`; the state adds
no process, port, or volume. Option contracts occupy ordinary security slots in every existing
component.

## Entrypoints

| Entrypoint | Transport | Consumer |
|---|---|---|
| gateway REST port | HTTP REST/UI | unchanged inherited clients; option OCC tickers accepted wherever a ticker is |
| gateway FIX port | FIX 4.4 over TCP | unchanged inherited FIX initiators |
| cluster ingress/egress UDP | Aeron Cluster client protocol | gateway tier and feed adapter |
| member consensus UDP ports | Aeron Cluster consensus/log/catch-up | cluster members |
| archive control/replay UDP | Aeron Archive protocol | member recovery and snapshot retrieval |

## Components

- **order-matcher cluster member (3 StatefulSet replicas)**: one pod runs the Media Driver,
  Archive, Consensus Module, and the clustered service container hosting the crossing
  `MatchingEngine` and the two-tier risk core, whose notional math is contract-multiplier-aware.
  Symbol registration derives each security's multiplier from the committed OCC ticker on the
  cold path. Per-pod PVC holds the consensus log and snapshots (format 3: the security record
  carries the multiplier; restore fails closed on multiplier < 1 or a non-3 format).
- **fix-gateway tier**: inherited unchanged — terminates counterparty sessions, screens
  admission, forwards through the Aeron Cluster client with the widened 32-byte SBE ticker
  field, and re-points on leader change. `/seed` registers option chains exactly as equity
  tickers.
- **feed adapter**: inherited unchanged; option price ticks are ordinary per-security ticks.
- **trade-egress bridge**: inherited unchanged; option fills republish to `/trades` keyed by
  tradeSeq+side with the OCC ticker as the security string.
- **NATS/JetStream, trade-processor, MariaDB, position-service, UI**: inherited CQRS topology
  unchanged; option trades and positions flow as ordinary security rows keyed by OCC ticker.
- **reference data** (`reference-data/*.csv` in this pack): consumed at extract time only; no
  runtime component reads it.

## Networking

- Identical to YU13: cluster-internal UDP between stable StatefulSet ordinals on the headless
  Service, gateway/feed-adapter ingress-egress ports, namespace-scoped NetworkPolicy over the
  pinned Aeron port range, no LoadBalancer/NodePort/multicast for Aeron traffic.
- Kind uses the dedicated named multi-node cluster with three schedulable workers and required
  anti-affinity; GKE keeps one member per `blp-pool` node.

## Startup / Health Order

1. Each member recovers: newest valid snapshot loaded (format 3 enforced, multiplier validated
   per security record), committed log applied strictly after the snapshot position, generator
   assertion passed.
2. Members complete Raft election; a majority elects exactly one leader.
3. A wiped replacement member retrieves the latest snapshot and replays the committed log tail
   before reporting follower readiness.
4. The feed adapter connects and sequences control/pricing ingress; gateway admission state
   becomes valid.
5. The gateway opens counterparty admission only when cluster readiness and admission-state
   readiness both hold.

## Degraded Behavior

All YU13 rows carry forward verbatim (member loss, leader loss, partition, gateway loss, feed
adapter loss, disk pressure, generator assertion, off-grid/out-of-band prices, restored rows
outside the band, unpriced market orders). Added by this state:

| Condition | Behavior |
|---|---|
| Restored security record with multiplier < 1 | Recovery fails closed: the member refuses readiness rather than enforce un-multiplied caps. |
| Snapshot header carrying format 2 (or any non-3 format) | Recovery fails closed with the inherited unknown-format rule. |
| Order on a registered option whose multiplied notional exceeds a cap | Rejects ORDER_NOTIONAL / CREDIT_LIMIT / CONCENTRATION_LIMIT at the multiplied level, before any reservation. |
| Multiplied notional arithmetic overflow | Rejects ORDER_NOTIONAL (reserve/decide paths); executed-exposure accumulation saturates (inherited behavior). |
| Option ticker exceeding the 32-byte SBE field | Registration refuses at the encoding boundary; no partial registration reaches the log. |
