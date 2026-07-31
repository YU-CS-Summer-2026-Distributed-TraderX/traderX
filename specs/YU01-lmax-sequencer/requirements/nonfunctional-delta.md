# Non-Functional Delta: YU01-lmax-sequencer

Parent state: `009-order-management-matcher`

Document NFR changes introduced by this state.

## Runtime / Operations

- Keep the LGTM stack from `007` (Grafana, Prometheus, Loki, Tempo, OTel Collector, Promtail, Blackbox
  Exporter) and all `009` runtime components; ingress routing and UI serving are unchanged.
- Rebuild `order-matcher` internals as the LMAX hot-path node hosting the sequencer, input ring,
  journaler/replicator/un-marshaller, BLP, and output ring (same service identity, port, and health
  surface as `009`).
- `trade-service` additionally plays the Gateway/Receptionist role: edge validation from in-memory
  replicas, `ticker -> securityId` mapping, fixed-point conversion, SBE encode, sequence submission.
  Its REST/WS contract is unchanged.
- New durable runtime state: journal directory (`journal.path`, default `./data/journal`), snapshot
  directory (`blp.snapshot.path`, default `./data/snapshots`), projection checkpoint
  (`output.projector.checkpoint-path`). These must survive restarts and be writable volumes in
  containerized profiles.
- Run profiles (`runtime.profile`): `demo` (default for `C2` containers — BlockingWaitStrategy, no
  pinning, no hugepages, single replica, replication loopback/stub), `perf` (bare metal — busy-spin on
  BLP/Journaler, pinned isolated cores via `isolcpus`/Affinity, ZGC/Shenandoah, large pages, NUMA,
  replicas + DR), `noGcTest` (CI — Epsilon GC small fixed heap). JVM flag matrix in
  `requirements/no-gc-conformance.md`.
- Operability: nightly bounce (restart + snapshot/replay) in a configured quiet window
  (`nogc.bounce.cron`); JIT warm-up replay (`nogc.warmup.events`) before the node reports ready.
- Startup order and health gating per `system/runtime-topology.md` (node is not "ready" until replay +
  warm-up complete).

## Security / Compliance

- No auth/RBAC change; admin view remains local-dev demonstration scope, as in `009`.
- Operational actions (cancel/force-fill) remain auditable: they are now journaled sequenced events,
  strictly ordered and replayable, in addition to structured logs (off-hot-path/async).
- The journal and snapshots contain trading data and must live on the same trust footing as the
  database volume; no secrets in journal, snapshot, or deployment-bundle artifacts.
- As convergence level `C2`, container build/publish CI with namespace
  `ghcr.io/finos/traderx-c2/<component>`, immutable commit-SHA tags plus `latest`, GHCR run bundle, and
  `runtime/deploy/` bundle obligations carry forward from `009` unchanged.
- New dependencies (Disruptor, Agrona, SBE, Chronicle Queue/Aeron, Affinity, HdrHistogram, JMH) are
  pinned to latest CVE-clean releases and subject to the repo dependency CVE gate.

## Performance / Scalability

Latency budgets (performance profile; demo/`C2` profile is exempt from budgets but not from the
allocation gate):

| Stage | Typical | p99 budget |
| --- | --- | --- |
| Gateway validate + encode + submit | 2–5 µs | < 20 µs |
| Sequencer + input ring claim | < 1 µs | < 5 µs |
| Journal (durable append) | 5–20 µs | < 50 µs |
| Replication ack (LAN) | 30–80 µs | < 150 µs |
| BLP business logic (match + book + position + emit) | 1–5 µs | < 25 µs |
| Output ring + marshal | 2–5 µs | < 20 µs |
| In-node compute (Gateway -> output emit, excl. network) | ~15–40 µs | < 150 µs |
| End-to-end incl. durable + replicated ack | ~0.3–1 ms | < 3 ms |

- NATS fan-out and read-model projection are off the order-acknowledgement path and carry no ack-path
  budget.
- Throughput headroom: single-threaded BLP capacity (LMAX reference: ~6M events/s/thread) is orders of
  magnitude beyond demo load; backpressure is bounded by ring capacity, never unbounded queues.
- Batching: consumers drain to the highest available sequence and amortize per-batch costs
  (journal flush, output flush) on `endOfBatch`; the projector enqueues on the ring and its separate
  drain thread batches the DB writes off it.
- All latency reporting is full-distribution (HdrHistogram p50/p99/p99.9/max) with jHiccup separating
  JVM/OS pauses from application latency.

## Reliability / Observability

- Recovery: snapshot + journal replay to the last journaled sequence; restart target `< 1 minute`
  including warm-up. Read-model loss degrades to projector rebuild, not trading-state loss.
- Failover: follower BLPs at the same sequence with output suppressed; promotion-based failover without
  cold replay (perf profile; contract-level check in demo profile).
- Decoupling: DB or NATS outage must not stop matching; affected output handlers lag within the bounded
  ring and catch up (FR-09B24).
- Order-management components keep Prometheus metrics and `/health`; mandatory scrape coverage per
  `009` NFR-01308 continues to apply to every metrics-capable service.
- Required metrics (in addition to all retained `009` order metric families):

| Metric | Type | Meaning |
| --- | --- | --- |
| `traderx_disruptor_input_remaining_capacity` | gauge | Free input-ring slots (backpressure headroom). |
| `traderx_input_published_seq` | gauge | Publisher cursor. |
| `traderx_input_gating_seq` | gauge | `min(journaler, replicator, unmarshaller)`. |
| `traderx_input_seq_lag` | gauge | `published − BLP consumed`. |
| `traderx_input_events_total{type=...}` | counter | Per-type ingest counts. |
| `traderx_input_backpressure_events_total` | counter | Producer waits for a free slot. |
| `traderx_journal_write_latency_seconds` | histogram | Journaler append latency. |
| `traderx_replication_ack_latency_seconds` | histogram | Replicator ack latency. |
| `traderx_blp_event_latency_seconds` | histogram | `onEvent` processing latency (real measurement). |
| `traderx_blp_book_depth{security=...}` | gauge | Resting orders per security. |
| `traderx_blp_positions_total` | gauge | Distinct in-memory positions. |
| `traderx_blp_cache_miss_total{cache=...}` | counter | Request/response events emitted for misses. |
| `traderx_blp_snapshot_seconds` | histogram | Snapshot duration. |
| `traderx_blp_replay_seconds` | gauge | Last recovery replay duration. |
| `traderx_output_publish_latency_seconds` | histogram | True end-to-end (`now − ingressNanos`) at egress. |
| `traderx_output_remaining_capacity` | gauge | Output ring headroom. |
| `traderx_output_events_total{kind=...}` | counter | Per-kind egress counts. |
| `traderx_output_nats_errors_total` | counter | NATS bridge publish failures. |
| `traderx_projector_lag_seq` | gauge | `BLP seq − last projected seq`. |
| `traderx_projector_batch_size` | histogram | Rows per projector flush. |
| `traderx_hotpath_alloc_bytes_total{node=...}` | counter | Steady-state allocation (**must stay ~0**). |
| `traderx_jvm_gc_pause_seconds` | histogram | GC pause distribution (expect empty/sub-ms). |
| `traderx_jit_warmup_seconds` | gauge | Warm-up duration at startup. |
| `traderx_nightly_bounce_seconds` | gauge | Last bounce (restart + replay) duration. |

- Grafana dashboard additions: ring headroom + sequence lag, journal/replication latency percentiles,
  BLP event latency, true end-to-end egress latency, projector lag/batch size, allocation-rate panel
  alerting if `> 0` in steady state, GC-pause panel (expect flat), warm-up/bounce durations. Existing
  `009` order dashboards continue to work unchanged.
- Smoke tests assert metrics endpoint availability and non-empty response for the required metric
  families above.
