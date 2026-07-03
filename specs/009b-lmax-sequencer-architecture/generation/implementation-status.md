# Implementation Status: 009b LMAX Hot Path (runtime overrides)

Date: 2026-06-09 (updated 2026-06-11, twice; 2026-06-25 throughput refinements; 2026-06-30 snapshot
+ journal-replay recovery; 2026-07-03 warm-standby failover). Scope of what
`generation/runtime-overrides/order-matcher/` implements today versus what the spec defers to later
milestones. Verified by compiling and running the module's test suite (35 tests; 33 green — the two
`LmaxHotPathParityTest` trade-persistence cases fail on a PRE-EXISTING H2-vs-MariaDB projector SQL
mismatch, see "Known test issue" below) plus the Epsilon allocation gate against Java 21 /
Gradle 8.14.5, plus the live failover suite `scripts/verify-009b-failover.sh` (16 checks green).

## Warm-standby failover: follower BLP + promotion + VIP (2026-07-03, FR-09B30..B32)

The failover requirements are no longer deferred: the demo stack now runs an active/passive matcher
PAIR with automatic promotion, realized over the journal rather than a network replicator (the
journal already carries the identical sequenced input stream, so tailing it gives a lock-step
follower with ZERO added cost on the leader's hot path). All overlay-owned.

- **Follower BLP (`lmax/JournalFollower` (new), `LmaxEngine.goStandby`)** — `blp.role=standby`
  (env `BLP_ROLE`) boots a follower: it recovers from snapshot+journal exactly like journal
  recovery, then keeps TAILING `input-events.journal` (fixed 64-byte records; a partial record at
  the tail is simply left until its bytes arrive), applying every event to its own MatchingEngine.
  The read model is rebuilt through the same output ring/marshaller with the NATS bridges gated by
  the existing `replaying` flag, so the follower publishes nothing — but serves warm READS
  (`/orders`, `/positions`, `/health`) the whole time. Commands answer 503 until promoted; NATS
  price ticks are dropped (they arrive via the journal instead — never applied twice). Unknown
  securityIds trigger a `symbols.tab` reload (the leader persists a mapping before sequencing the
  first event that uses it). Measured under saturation (~84k booked/s): follower lag p50 = 0 bytes,
  worst ≈ 38k events ≈ 0.45 s.
- **Leader election / split-brain fence (`lmax/LeaderLock` (new))** — an OS file lock on
  `leader.lock` in the journal dir. The LIVE node (sole appender of journal/symbols.tab/snapshot)
  holds it; the kernel releases it exactly when the holder process dies. `blp.role` is only a
  PREFERENCE: a configured primary that finds the lock held (restart after a failover) demotes
  itself to follower (`blp.leader.acquire-timeout-ms`, default 10 s wait). Same-kernel scope only
  (compose named volume); a cross-host DR profile needs a real lease (Aeron Cluster/Raft).
- **Failure detection + promotion (`lmax/PrimaryWatchdog` (new), `LmaxEngine.promote`)** — the
  follower probes `failover.watch-url` (env `FAILOVER_WATCH_URL`; compose wires each node to watch
  the other) every `failover.probe-interval-ms` (1000); after `failover.probe-failures` (3)
  consecutive CONNECT failures (any HTTP status counts as alive — a degraded leader is still the
  leader) it promotes: win the lock FIRST (fences an alive-but-slow leader out; nothing can append
  after), stop the tailer, drain the remaining journal tail, un-gate outputs, then start the normal
  journaler+replicator+BLP input path. Acks are gated behind the journaler, so everything the dead
  leader ever acked is reconstructed; `nextOrderRef` is advanced past every replayed ORDER_NEW (no
  id collisions — this also fixed a pre-existing journal-recovery bug). If the tail drain fails,
  promotion ABORTS: the lock is released and following resumes. Manual promotion:
  `POST /admin/promote` (still lock-checked, so it cannot split-brain a healthy leader). Verified
  kill→ready ≈ 5–6 s with `FAILOVER_PROBE_FAILURES=3`.
- **VIP (`order-management-matcher/failover/haproxy.cfg` (new), compose `order-matcher-vip`)** —
  haproxy holds the well-known address for every client (trade-service `ORDER_MATCHER_URL`, ingress,
  host port **18110**): commands (POST/PUT/PATCH/DELETE) route only to a node whose
  `GET /admin/ready` is 200 (the live node — followers answer 503 there); reads prefer the live node
  and fall back to any process answering `/health` (the warm follower) while a failover is in
  flight. Docker DNS re-resolution (`resolvers` + `init-addr libc,none`) survives container
  recreation. Host ports: 18110 = VIP, 18111 = primary direct, 18112 = standby direct,
  18404 = haproxy stats. In-network, `order-matcher:18110` / `order-matcher-standby:18110` are the
  real nodes (Prometheus scrapes both at 1 s).
- **Failover surface (`controller/FailoverController` (new))** — `GET /admin/role` (role, live,
  leaderLockHeld, journalWriting, followerLagBytes, followerAppliedEvents, promotions),
  `GET /admin/ready` (VIP health check), `POST /admin/promote`. `/health` (`lmax` block) and
  `/metrics` expose the same: `traderx_blp_role{role=...}`, `traderx_blp_live`,
  `traderx_leader_lock_held`, `traderx_follower_lag_bytes`,
  `traderx_follower_applied_events_total`, `traderx_failover_promotions_total`.
- **Deterministic seed securityIds (`LmaxEngine.registerSeedSymbols`)** — the leader turns
  `symbols.tab` persistence on and registers the seed tickers FIRST in one fixed order, so a fresh
  volume's file starts with the seed ids and every later node restores the identical mapping
  (previously the DB warm-start assigned seed ids in DB iteration order and never persisted them —
  two nodes could disagree on id→ticker, which is fatal when the journal speaks ids). Volumes
  created before 2026-07-03 lack the seed block: reset them (`docker compose down -v`) once.
- **Gotcha worth remembering (fixed)** — `promote()` runs ON the watchdog's own thread; a
  self-interrupting `close()` left the thread's interrupt flag set, which made every subsequent NIO
  channel open throw `ClosedByInterruptException` — the promoted leader ran UNJOURNALED. The
  watchdog no longer self-interrupts, `promote()` clears the flag before channel work, and the
  verify suite asserts `journalWriting` on the new leader.
- **Compose defaults** — `SNAPSHOT_INTERVAL_MS` now defaults to `30000` on both matcher services
  (bounds leader restart AND standby catch-up); the standby runs the follower profile
  (`BLP_PIN_CPU=-1`, blocking wait, no DB dependency at all: projector never attached on a
  follower, `SPRING_JPA_DDL_AUTO=none`, `HIKARI_INIT_FAIL_TIMEOUT=-1`). For max-throughput bench
  runs set `SNAPSHOT_INTERVAL_MS=0` and `FAILOVER_PROBE_FAILURES=999999` (the bench driver's
  per-run matcher restarts would otherwise trigger promotion), and target the primary directly at
  `:18111`. Measured cost of the live follower on a 16-core host at saturation: ~20% sustained
  (84k vs 105k booked/s) — host CPU contention, not gating.

Overlay files: `lmax/JournalFollower.java`, `lmax/LeaderLock.java`, `lmax/PrimaryWatchdog.java`,
`controller/FailoverController.java` (all new), `lmax/LmaxEngine.java`, `lmax/Journaler.java`
(`isWriting`), `lmax/JournalReader.java` (shared decode), `lmax/SymbolTable.java` (read-only mode +
`reload()` + `beginPersisting()`), `service/OrderMatcherService.java` (role gating + telemetry),
`application.properties` (failover keys), `order-management-matcher/docker-compose.yml`
(standby + VIP services), `order-management-matcher/failover/haproxy.cfg`,
`order-management-matcher/observability/prometheus/prometheus.yml` (both nodes + VIP probe),
plus `src/test/java/.../lmax/FailoverPrimitivesTest.java` and repo-root
`scripts/verify-009b-failover.sh`.

## Known test issue (pre-existing, 2026-07-02 diagnosis)

`LmaxHotPathParityTest.marketTradeBooksAndUpdatesPosition` and
`.buyFillIncreasesNetPositionInTheBlp` fail at the committed baseline too: the raw-JDBC
`ProjectorHandler` emits MariaDB `INSERT ... ON DUPLICATE KEY UPDATE` upserts, which the H2 test
database (PostgreSQL mode) rejects, so no trade ever persists and the tests time out. 33/35 is the
real green baseline. Fix options: H2 MariaDB compatibility mode in the test properties,
dialect-aware upsert SQL, or asserting against the read model instead of the DB.

## Recovery: periodic snapshot + bounded journal-tail replay + no-DB cutover (2026-06-30)

State-009b recovery is no longer "warm-start only". The matcher now takes periodic full-state
snapshots and can recover — and keep trading — with the database stopped. All overlay-owned
(`generation/runtime-overrides/order-matcher/`), superseding the captured patchset (same precedent
as the throughput overrides).

- **Periodic snapshot (`SnapshotStore` (new), `LmaxEngine.writeSnapshot`)** — a `snapshot-scheduler`
  thread sequences a `TYPE_SNAPSHOT` marker (`InputEvent`) every `snapshot.interval.ms` (env
  `SNAPSHOT_INTERVAL_MS`; compose demo default `30000` since 2026-07-03, `0` = off). The marker rides the input ring
  like any other event, so the BLP writes the checkpoint **on its own thread at a consistent sequence
  point** — full book + net positions + last prices + `nextOrderRef`/`tradeCounter` — to
  `snapshot.dat` in `journal.path`, **atomically** (temp file + atomic rename, so a crash mid-write
  leaves the prior snapshot intact). The trigger is armed only AFTER recovery completes, so SNAPSHOT
  markers replayed from the journal tail are no-ops.
- **Bounded tail replay (`Journaler.lastSnapshotOffset`, `SnapshotStore.Data.coveredOffset`,
  `JournalReader.replayFrom`)** — the journaler forces an fsync through every SNAPSHOT marker and
  records the journal byte offset just past it; the snapshot persists that offset, so recovery loads
  the latest snapshot and replays **only the journal tail after `coveredOffset`** rather than the
  whole log. This bounds restart time as the journal grows (addresses the earlier unbounded-replay
  restart-OOM).
- **Two recovery sources (`recovery.source`, env `RECOVERY_SOURCE`, default `db`)**:
  - `db` (default) — warm-start the BLP from the persisted read-model (orders + net positions +
    trade counter), then `verifyJournalReplay()` rebuilds the same state in an **isolated shadow
    engine** (snapshot+tail when a snapshot exists, else seed+full-journal), diffs the digests, and
    logs `JOURNAL-REPLAY VERIFY: PASS|MISMATCH`. Verify-only — zero effect on the live engine; gate
    off with `journal.replay.verify=false` once trusted.
  - `journal` — `recoverLiveFromJournal()` reconstructs the **live** engine and read model from
    snapshot+tail (or seed+full-journal when fresh) with the NATS bridges gated
    (`readModel.setReplaying`) so recovery does not re-broadcast history. No DB warm-start: the
    matcher needs no database to recover.
- **No-DB cutover** — `output.projector.db.enabled=false` (env `OUTPUT_PROJECTOR_DB_ENABLED`) omits
  the DB projector entirely (no DB writes at all); combined with `recovery.source=journal` and the
  no-DB boot knobs (`SPRING_JPA_DDL_AUTO=none`, `HIKARI_INIT_FAIL_TIMEOUT=-1`,
  `MANAGEMENT_HEALTH_DB_ENABLED=false`) the node boots, recovers, books trades, and serves `/orders`
  + `/positions` from memory **with the database stopped** (reads repoint to the BLP via
  `LmaxEngine.listPositions`).
- **Durable ticker mapping (`SymbolTable` + `symbols.tab`)** — restored FIRST at boot (before any
  `idFor`), so security ids replayed from the journal resolve to the same tickers the original run used.

Overlay files: `lmax/SnapshotStore.java` (new), `lmax/JournalReader.java`, `lmax/Journaler.java`,
`lmax/LmaxEngine.java`, `lmax/MatchingEngine.java`, `lmax/InputEvent.java` (`TYPE_SNAPSHOT`),
`order-management-matcher/docker-compose.yml` (snapshot/recovery env + `order_matcher_journal` volume
at `/opt/app/data`, durable across container recreate).

## Throughput refinements: batch ingress + decoupled projector + write-path (2026-06-25)

Output-side throughput work after the A/B benchmark. Each change removed a *synchronous per-item I/O*
gate on the LMAX rings; all land in `generation/runtime-overrides/order-matcher/` (overlay-owned, so
they supersede the captured patchset, which is intentionally not re-derived — same precedent as the
Grafana/throughput dashboard overrides).

- **Batch ingress (FR-09B43)** — new `POST /orders/batch` (`OrderController` + `OrderMatcherService`
  + `LmaxEngine.executeNewOrderBatch`): claims a contiguous run of input-ring slots via `tryNext(n)`,
  registers all acks, fills, publishes once with `publish(lo, hi)`, and blocks once for all acks.
  Amortizes the request/reply-per-order gateway cost. Additive; the per-order 009 endpoints are unchanged.
- **Decoupled projector (FR-09B44)** — `ProjectorHandler` is now an enqueue-only ring consumer feeding a
  bounded `LinkedBlockingQueue` (`output.projector.queue-capacity`, default 1,000,000) drained by a
  `projector-drain` thread; `start()`/`stop()` wired in `LmaxEngine`. New gauges/counter
  `traderx_projector_queue_depth` / `_queue_capacity` / `_enqueue_blocks_total`; `projectedSeq` watermark
  advances only on a committed flush; queue-full = counted enqueue backpressure (no row loss). Realizes
  ADR-016 decision 6 ("a slow/down DB never stalls matching") up to the queue, not just the ring.
- **Insert-only batched writes (FR-09B45)** — trades persist via one multi-row
  `INSERT … ON CONFLICT (id) DO NOTHING` per flush through a `JdbcTemplate` (no per-row `merge` SELECT;
  replay-idempotent). `application.properties` enables Hibernate `jdbc.batch_size`/`order_inserts`/
  `order_updates` + `reWriteBatchedInserts` for the order/position writes. Schema unchanged (NFR-09B11).
- **Async NATS publish (FR-09B46)** — `NatsJSONPublisher.publish` drops the per-message
  `connection.flush()` (broker round-trip); the client writer flushes asynchronously. Order-matcher's own
  copy only; subjects/payloads unchanged.

Measured (demo profile, single laptop; fill-counter delta = sustained, in-process gauge = burst): sustained
booking ~1,060/s → ~2,045/s → ~3,720/s across the three output-side fixes; the in-memory engine bursts to
~34k/s until the projector queue fills, then throttles to the DB drain. Remaining sustained ceiling is the
order/position `merge` per-row SELECT (next step: `ON CONFLICT … DO UPDATE` + per-flush order-row dedup).
Bench tooling and findings: `scripts/bench/batch-load.mjs`, `scripts/bench/batch-experiment.mjs`,
`scripts/bench/results/` (repo-root dev tooling, not generated runtime). Overlay files touched:
`lmax/ProjectorHandler.java`, `lmax/LmaxEngine.java`, `service/OrderMatcherService.java`,
`controller/OrderController.java` (new), `messaging/nats/NatsJSONPublisher.java` (new),
`resources/application.properties`.

## Hot-path optimization + allocation gate pass (2026-06-11, second update)

- **BLP fence elimination**: the per-event telemetry fields are now plain `long`s published by a
  single `VarHandle.setRelease` of `blpSeq` per event (the Disruptor `Sequence.set` idiom); edge
  accessors acquire-load `blpSeq` first. Replaces 4–5 volatile stores (StoreLoad fences) per event
  with zero. `blpThreadId` moves to the Disruptor `onStart()` lifecycle hook, removing a per-event
  branch.
- **O(1) terminal removal**: fills triggered from the price-tick scan and from create-time matching
  pass their open-index slot down (`autoFill(..., openIndex)`), so removing a terminal order is a
  guarded swap-with-last at a known index instead of an O(depth) value scan (`removeOpenRef`).
- **Paired fill emit**: `OutputPublisher.emitFillWithTrade` claims the fill's order-update and
  TradeBooked slots in one `next(2)` batch claim and publishes both with one
  `publish(lo, hi)` — halving claim/publish (and BlockingWaitStrategy signal-lock) cost per fill.
  Event order and handler semantics are unchanged.
- **Journaler pad**: the 12-iteration single-byte pad loop is two puts (`putInt` + `putLong`).
  `Journaler` also records its thread id via `onStart()` (`journalThreadId()`).
- **T09B18 allocation gate (partial — gate + static check)**:
  - `AllocationGateTest` drives the real input-ring -> journaler+replicator -> BLP -> output-ring
    topology through a deterministic mix covering every BLP branch, then asserts **byte-exact zero**
    `ThreadMXBean.getThreadAllocatedBytes` deltas for the producer, journaler, and BLP threads over
    the measured phase (1M events in `test`). Negative-tested: an injected per-tick `new long[1]`
    fails with `expected: <0> but was: <18000000>`.
  - Gradle `noGcTest` task re-runs the gate under
    `-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xms256m -Xmx256m -XX:+AlwaysPreTouch`
    with a 3M-event budget (no reclamation: steady-state allocation exhausts the heap and fails);
    `pipeline/validate-no-gc-conformance.sh` wraps it (SC-NGC-01/SC-09B05).
  - `HotPathBannedApiTest` (SC-09B13/SC-NGC-04) scans compiled constant pools of the hot-path
    classes (MatchingEngine, OutputPublisher, RestingOrder, IntList, InputEvent, OutputEvent,
    ReplicatorStub; Journaler with cold-path SLF4J permitted) for BigDecimal, java.time, HashMap/
    ConcurrentHashMap, streams, regex, Atomic*, String.format, string concatenation, Spring, JPA.
  - JFR/async-profiler attribution tooling and the `perf`/`noGcTest` launch profiles for the
    packaged service (T09B17) remain deferred.
- Doc-truth: the verbatim snippets in `LMAX-BLP.md`, `LMAX-INPUT-DISRUPTOR.md`,
  `LMAX-OUTPUT-DISRUPTOR.md`, `LMAX-SEQUENCER-ARCHITECTURE.md`, and `LMAX-NO-GC-JAVA.md`
  (A12.2/A12.4/A12.5/A12.6/A12.8/A12.9/A12.10) were updated to match; `quickstart.md` step 4 now
  shows the real gate commands.
- 2026-06-12 follow-ups: measured A/B benchmark vs `009` (`scripts/bench-009-vs-009b.sh`,
  report `LMAX-BENCHMARK-009-VS-009B.md`: identical workload, `009` 179.6s vs `009b` 1.2s,
  identical outcomes; BLP allocated 4,776 B across the live run, corroborating the gate).
  Patchset now 41 entries: added `.github/workflows/no-gc-gate.yml` (Epsilon gate +
  hot-path conformance tests in generated-tree CI, T09B23 slice) and refreshed the stale
  "patchset has not landed" comments in the generated 009b lifecycle delegates (their
  repo-side wrappers, the pipeline hook comment, and the quickstart warm-start note were
  fixed in place).

## Patchset + pipeline integration (2026-06-10)

- `generation/patches/0001-state-overlay.patch` is captured via
  `pipeline/create-state-patchset.sh 009b-lmax-sequencer-architecture 009-order-management-matcher`
  (38 entries: the 25-file order-matcher overlay + state-identity files). A from-scratch
  `pipeline/generate-state.sh 009b-lmax-sequencer-architecture` applies it cleanly (direct apply,
  no 3-way fallback) and the overrides render is byte-idempotent on top.
- 2026-06-11: the 8 order-matcher entries touched by the conformance fixes (backpressure metric,
  output-ring headroom, projector batch histogram, `blp.book.pool-size`, doc-truth edits, metrics
  guard test) were regenerated and spliced into the patch in place — the other 30 entries are
  byte-identical to the original capture (no `create-state-patchset.sh` re-run; it is lossy for
  env wrappers). Verified: patch applies clean to the 009 order-matcher baseline and the overlay
  render on top is byte-idempotent again.
- Capture now excludes top-level-only installer outputs (`/api-explorer`, `/ingress/api-explorer`,
  `/runtime`, `state-ui.json`): they are absent in nested parent generations, so depth-1 forms
  would create patch entries whose preimages never exist at apply time. The state's own
  post-generation installers always rebuild them.
- 009b-aware pipeline gates added: pubsub-inspector enablement in
  `install-generated-api-explorer.sh`, a `009b` case (scripts/runbook/URLs/compose normalization)
  in `install-generated-runtime-harness.sh`, and `/grafana` + `/prometheus` ingress route
  injection in `render-state-009b-lmax-sequencer-architecture.sh` (the 009 render only injects
  them at generation depth 1, which a nested parent run skips).
- IDE support: `order-matcher/.parent-src/` (git-ignored) snapshots the 21 parent-009 classes the
  overlay does not replace, registered as an optional Gradle source dir so the overlay compiles
  standalone in `specs/`. The render script excludes it from generated trees.

## Implemented (demo profile)

- **Input disruptor** (`LmaxEngine`): multi-producer ring, ring sequence = global sequence;
  `Journaler` (binary file journal, fsync per drained batch) and `ReplicatorStub` (loopback ack)
  in parallel, BLP gated behind both via the sequence barrier (FR-09B01/03/04).
- **Event-driven BLP** (`MatchingEngine`): single thread, no locks/atomics, pooled
  `RestingOrder` entries, per-security `IntList` open-order index, `long[]` last prices,
  event-carried time, 009's fill policy in integer math (FR-09B02/B10/B13/B14).
- **Gateway facade** (`OrderMatcherService` + `LmaxEngine.execute*`): edge validation,
  `Px` fixed-point conversion, `SymbolTable` ticker->int mapping, synchronous REST semantics via
  request/response ack futures correlated on the input sequence (LMAX-BLP.md A7).
- **Output disruptor**: single producer, parallel handlers — `MarshallerHandler` (in-memory
  read model + acks + end-to-end latency), `NatsBridgeHandler` (exact 009 subjects/payloads),
  `TradeSubmitHandler` (TradeBooked -> existing trade pipeline, off the ack path),
  `ProjectorHandler` (batched, lag-tolerant `OrderBook` writes) (FR-09B20/21/22/24/25).
- **Recovery**: persisted read-model warm-start (seeds + open orders + net positions re-indexed
  into the BLP at boot) + append-only input journal — now with periodic full-state snapshots,
  bounded journal-tail replay, journal-replay verification, and an optional DB-less
  `recovery.source=journal` cutover (FR-09B16). See the 2026-06-30 section above.
- **Metrics**: all 009 families retained (match-latency histogram now a real HdrHistogram
  measurement) plus input/BLP/output/projector/alloc families from the contract delta —
  including (2026-06-11) `traderx_input_backpressure_events_total` (counted tryNext-fallback
  on ring-full claims, FR-09B07), `traderx_output_remaining_capacity`, and
  `traderx_projector_batch_size` (rows per successful flush).
- **Pool sizing**: the BLP resting-order pool is pre-allocated from the contract key
  `blp.book.pool-size` (default 65536) instead of a hardcoded literal.
- **Parity tests**: `PxTest` (penny parity, SC-09B04) and `LmaxHotPathParityTest`
  (policy/lifecycle/REST parity, SC-09B03-lite; plus a required-metric-families guard for the
  contract-delta scrape surface) pass without any runtime infrastructure.

## Documented deviations (implemented scope vs. spec letter)

- **Journal failure semantics (FR-09B04)**: on append failure the demo Journaler logs, disables
  itself, and keeps advancing its gating sequence so the BLP is not wedged — availability over
  durability in the containerized demo. The perf-profile journaler (Chronicle/Aeron, T09B14+)
  must instead stall the sequence barrier.
- **Order-id derivation (FR-09B14 letter)**: ids continue 009's dense `ord-013-%04d` numbering
  from a gateway-edge counter (warm-started from the read-model), not from the global sequence —
  price ticks share the sequence, so seq-derived ids would break 009 id parity (FR-09B40).
  Determinism is preserved: the ref is assigned at the edge and carried in the event, so the BLP
  never generates ids.
- **Resting-order retention (NGC-01 boundary)**: terminal orders stay addressable in the BLP book
  (009 parity: cancel/force-fill of a completed order returns it unchanged), so pool entries are
  never recycled; steady state is allocation-free up to `blp.book.pool-size`, then growth follows
  amortized doubling. Recycling/eviction arrives with the snapshot milestone (T09B14).
- **Config keys not yet wired** (land with their milestones): `journal.type`, `replication.*`,
  `affinity.*`, `nogc.*`, `blp.cache.*` (deferred features, T09B14/16/17/18). Snapshot/recovery ARE
  wired (2026-06-30), under `snapshot.interval.ms`, `recovery.source`, and
  `output.projector.db.enabled` (env `SNAPSHOT_INTERVAL_MS`/`RECOVERY_SOURCE`/`OUTPUT_PROJECTOR_DB_ENABLED`),
  not the spec's placeholder `blp.snapshot.*` / `output.projector.checkpoint-path` names;
  `output.nats.enabled`, `output.projector.enabled`, `output.projector.flush-interval-ms`,
  `output.projector.checkpoint-path` (output-stage toggles + idempotent checkpointed projection,
  FR-09B23 — T09B14/T09B22). `order.matcher.trade-service-url` is retained (contract delta lists
  it as removed) because the TradeBooked bridge still feeds trade-service until booking fusion.
- **Metric families tied to deferred features**: `traderx_replication_ack_latency_seconds`
  (T09B16 network-replicator remainder; the failover metrics `traderx_blp_role`/`traderx_blp_live`/
  `traderx_leader_lock_held`/`traderx_follower_lag_bytes`/`traderx_follower_applied_events_total`/
  `traderx_failover_promotions_total` ARE wired, 2026-07-03), `traderx_blp_book_depth` / `traderx_blp_positions_total` /
  `traderx_blp_cache_miss_total` / `traderx_blp_snapshot_seconds` / `traderx_blp_replay_seconds`
  (T09B13-fusion/T09B14), `traderx_jvm_gc_pause_seconds`, `traderx_jit_warmup_seconds`,
  `traderx_nightly_bounce_seconds` (T09B14/T09B18/T09B20).

## Deferred (tracked in tasks.md)

- **SBE codecs + Agrona structures** (T09B12): events are typed-field holders, not flyweights
  over off-heap buffers; the un-marshaller stage is omitted from the input topology until SBE
  lands. Custom `IntList`/array structures keep the hot path boxing- and stream-free meanwhile.
- **Booking/position fusion** (FR-09B08/B22 full form): fills bridge through trade-service from
  the output ring (strangler P2 boundary), preserving the 009 trade/position contract exactly.
  Consequence: a trade-service outage during a fill is surfaced via reject/tradeSubmitFailures
  counters instead of 009's pre-fill rejection — documented behavioral edge.
- **JIT warm-up replay + cron-scheduled nightly bounce** (T09B14 remainder). Snapshot files
  (`snapshot.dat`) and journal-replay recovery landed 2026-06-30 (section above); the JIT warm-up
  replay before going live and a scheduled bounce window remain deferred.
- **Network replicator (Aeron) for cross-host DR** (T09B16 remainder): the warm-standby follower,
  promotion, and lock fencing landed 2026-07-03 (section above) realized over the SHARED journal —
  same-host scope. The in-ring `ReplicatorStub` is still the loopback ack; a real network replicator
  (and a consensus lease instead of the file lock) is what a multi-host DR profile still needs.
- **`perf`/`noGcTest` launch profiles for the packaged service** (T09B17): the demo profile uses
  BlockingWaitStrategy and standard GC. The Epsilon allocation gate itself is implemented (see the
  2026-06-11 second update above); `traderx_hotpath_alloc_bytes_total` additionally exposes the BLP
  thread's allocation at runtime. JFR/async-profiler attribution tooling (T09B18 remainder)
  deferred.
- **Determinism replay + NATS payload byte-parity smoke** (T09B19 remainder, T09B21).

## How this was verified

```bash
# module assembled exactly as generation produces it (009 patch + 009 render + 009b overrides)
./gradlew compileJava   # clean (only pre-existing 009 deprecation warnings)
./gradlew test          # 15/15 green, broker-free (incl. AllocationGateTest byte-exact zero,
                        # HotPathBannedApiTest)
./gradlew noGcTest      # allocation gate under Epsilon GC, 3M steady-state events, green
```
