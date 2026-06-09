# Runtime Topology: 009b-lmax-sequencer-architecture

Parent state: `009-order-management-matcher`

Describe runtime topology and network/data flow changes introduced by this state.

## Entrypoints

- App ingress: `http://localhost:8080` (unchanged)
- Grafana: `http://localhost:3001` (unchanged)
- Prometheus: `http://localhost:9090` (unchanged)
- NATS monitor: `http://localhost:8222/varz` (unchanged)
- Order matcher (hot-path node) health: `http://localhost:<order-matcher-port>/health`
- Order matcher metrics: `http://localhost:<order-matcher-port>/metrics`

## Components

- Inherits the full `009` runtime (app + pricing + LGTM observability) with unchanged edge components:
  ingress, Angular UI, account/position/people/reference-data services, NATS, price-publisher.
- Rebuilds `order-matcher` internals as the LMAX hot-path node (same service identity/port):
  - sequencer + input ring (multi-producer)
  - journaler (durable append to `journal.path`), replicator (follower/DR stream), un-marshaller
  - single-threaded BLP (order books, positions, caches)
  - output ring (single producer) with marshaller, NATS bridge, and read-model projector
- `trade-service` gains the Gateway/Receptionist role (in-memory validation, symbol mapping,
  fixed-point conversion, SBE encode, sequence submission); REST/WS contract unchanged.
- Optional replica hot-path node (warm standby, output suppressed) — loopback/stub in `demo` profile,
  real second node + DR in `perf` profile.
- New durable local state: journal directory, snapshot directory, projection checkpoint file (mounted
  volumes in containerized profiles).

## Networking

- UI -> ingress -> Gateway REST/WS: unchanged paths and shapes.
- price-publisher ticks reach the Gateway for sequencing (`price.input.via-ring=true`); the matcher no
  longer holds its own out-of-band NATS price subscription.
- The BLP makes no outbound network calls. NATS egress happens only from the output-ring bridge;
  DB writes happen only from the projector.
- Replication traffic (perf profile): sequenced input stream from the leader's replicator to follower
  and DR nodes (Aeron); none in demo profile.
- Prometheus scrapes hot-path metrics from the matcher node, actuator metrics from compatible JVM
  services, and blackbox probes for order endpoints — extending the `009` scrape set with the new
  metric families.
- Grafana queries Prometheus/Loki/Tempo for hot-path views (ring headroom, sequence lag, latency,
  projector lag, allocation rate, GC pauses).

## Startup / Health Order

1. Start inherited `009` runtime baseline (database, NATS, LGTM, edge services).
2. Start the hot-path node (`order-matcher`): load latest snapshot -> replay journal from the snapshot
   sequence -> run JIT warm-up replay (`nogc.warmup.events`) -> mark ready. The node reports unhealthy
   until replay + warm-up complete.
3. Start/verify the Gateway role in `trade-service`: warm account/reference/price caches
   (`blp.cache.account.warm-on-start`), verify input ring connectivity.
4. Start the replica node (perf profile) and verify it tracks the leader sequence with output
   suppressed.
5. Ensure ingress routes order-management APIs and admin UI paths (unchanged from `009`).
6. Verify Prometheus discovers hot-path targets and the required metric families
   (`requirements/nonfunctional-delta.md`).
7. Verify Grafana has the hot-path dashboards provisioned (including the allocation-rate alert panel).

## Operational Windows

- Nightly bounce (`nogc.bounce.cron`): restart the hot-path node in a quiet window; recovery is
  snapshot + replay (< 1 minute target) and re-runs warm-up before going live.
- Snapshot cadence (`blp.snapshot.interval`): nightly by default; snapshots also taken before planned
  bounces.
- Failover drill (perf profile): kill the leader; the follower promotes at its current sequence and
  un-suppresses output; the Gateway re-targets producers.
