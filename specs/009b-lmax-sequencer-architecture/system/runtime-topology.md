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
- New durable local state: the journal directory (`journal.path`, default `./data/journal`) holds the
  append-only `input-events.journal`, the periodic `snapshot.dat` checkpoint, and the `symbols.tab`
  ticker->id map — one mounted volume (`order_matcher_journal` -> `/opt/app/data`) in containerized
  profiles, durable across container recreate. (A standalone projection checkpoint file is not yet
  implemented; the projector's `projectedSeq` watermark is in-memory, advanced on a committed DB flush.)

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
2. Start the hot-path node (`order-matcher`): restore the `symbols.tab` ticker map -> recover state ->
   mark ready. Recovery is `recovery.source`-driven: `db` (default) warm-starts the BLP from the persisted
   read-model and *verifies* snapshot+journal-tail replay matches it; `journal` rebuilds the live BLP from
   `snapshot.dat` + the journal tail with no DB. (A JIT warm-up replay before going live is not yet wired.)
3. Start/verify the Gateway role in `trade-service`: warm account/reference/price caches
   (`blp.cache.account.warm-on-start`), verify input ring connectivity.
4. Start the replica node (perf profile) and verify it tracks the leader sequence with output
   suppressed.
5. Ensure ingress routes order-management APIs and admin UI paths (unchanged from `009`).
6. Verify Prometheus discovers hot-path targets and the required metric families
   (`requirements/nonfunctional-delta.md`).
7. Verify Grafana has the hot-path dashboards provisioned (including the allocation-rate alert panel).

## Operational Windows

- Snapshot cadence (`snapshot.interval.ms`, env `SNAPSHOT_INTERVAL_MS`): periodic full-state `snapshot.dat`
  checkpoint, demo default 60 s (`0` = off); each snapshot bounds the journal tail recovery must replay.
- Nightly bounce: restart the hot-path node in a quiet window; recovery is snapshot + journal-tail replay.
  009b performs that replay on every restart; a cron-scheduled bounce window (`nogc.bounce.cron`) and a
  warm-up replay before going live remain aspirational.
- Failover drill (perf profile): kill the leader; the follower promotes at its current sequence and
  un-suppresses output; the Gateway re-targets producers.
