# Implementation Status: 009b LMAX Hot Path (runtime overrides)

Date: 2026-06-09 (updated 2026-06-11, twice). Scope of what
`generation/runtime-overrides/order-matcher/` implements today versus what the spec defers to
later milestones. Verified by compiling and running the module's test suite (15 tests green)
plus the Epsilon allocation gate against Java 21 / Gradle 8.14.5.

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
- **Recovery (demo shape)**: persisted read-model warm-start (seeds + open orders re-indexed
  into the BLP at boot) + append-only input journal (FR-09B16, demo-simplified).
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
  amortised doubling. Recycling/eviction arrives with the snapshot milestone (T09B14).
- **Config keys not yet wired** (land with their milestones): `journal.type`, `replication.*`,
  `affinity.*`, `nogc.*`, `blp.snapshot.*`, `blp.cache.*` (deferred features, T09B14/16/17/18);
  `output.nats.enabled`, `output.projector.enabled`, `output.projector.flush-interval-ms`,
  `output.projector.checkpoint-path` (output-stage toggles + idempotent checkpointed projection,
  FR-09B23 — T09B14/T09B22). `order.matcher.trade-service-url` is retained (contract delta lists
  it as removed) because the TradeBooked bridge still feeds trade-service until booking fusion.
- **Metric families tied to deferred features**: `traderx_replication_ack_latency_seconds`
  (T09B16), `traderx_blp_book_depth` / `traderx_blp_positions_total` /
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
- **Snapshot files, journal replay tooling, JIT warm-up, nightly bounce** (T09B14).
- **Real replication/failover** (T09B16): loopback stub only; no follower BLP yet.
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
