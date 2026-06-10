# Implementation Status: 009b LMAX Hot Path (runtime overrides)

Date: 2026-06-09. Scope of what `generation/runtime-overrides/order-matcher/` implements today
versus what the spec defers to later milestones. Verified by compiling and running the module's
test suite (11 tests green) against Java 21 / Gradle 8.14.5.

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
  measurement) plus input/BLP/output/projector/alloc families from the contract delta.
- **Parity tests**: `PxTest` (penny parity, SC-09B04) and `LmaxHotPathParityTest`
  (policy/lifecycle/REST parity, SC-09B03-lite) pass without any runtime infrastructure.

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
- **`perf`/`noGcTest` launch profiles + Epsilon allocation gate** (T09B17/T09B18): the demo
  profile uses BlockingWaitStrategy and standard GC; `traderx_hotpath_alloc_bytes_total`
  exposes the BLP thread's allocation for observation in the meantime.
- **Determinism replay + NATS payload byte-parity smoke** (T09B19 remainder, T09B21).

## How this was verified

```bash
# module assembled exactly as generation produces it (009 patch + 009 render + 009b overrides)
./gradlew compileJava   # clean (only pre-existing 009 deprecation warnings)
./gradlew test          # 11/11 green, broker-free
```
