# Implementation Status: YU13-limit-order-book

Status as of 2026-07-20. Verified on the JVM (kind-free); the live-cluster bench and HA proof
(T-LOB14) are the remaining work.

## Generation

- `bash pipeline/generate-state.sh YU13-limit-order-book` exits 0, recursively generating the
  full YU12→…→YU02→014 ancestry and overlaying the YU13 crossing-book assets last-wins.
- Shared-override markers verified in the generated `order-matcher` tree — every ancestor's
  behavior survives the overlay alongside YU13's:
  - YU13: `LimitBook` class, `FLAG_RESTING_UPDATE`, `SNAPSHOT_FORMAT = 2`.
  - YU12: `aeron-cluster` dependency, `ClusteredService` hosting, `TradeNatsPublisher` bridge.
  - YU11: SBE generation. YU10: QuickFIX/J. YU09: no-GC/allocation gate tasks. YU03: risk
    gateway (`BlpRiskState`, `decideAndReserve`).
- Runtime wrapper scripts (`{start,stop,status}-state-YU13-limit-order-book-generated.sh`,
  `test-state-YU13-limit-order-book.sh`) delegate to the inherited YU10 harness; YU13 changes
  the matching policy, not the run/deploy surface.

## Engine (crossing limit-order book)

- `LimitBook`: array-indexed price levels on a 0.001 grid (`BOOK_TICK_PX = 1000`) inside a
  banded window (`BOOK_LEVELS = 1<<17` = $131.07, anchored mid-band on the security's first
  limit); intrusive doubly-linked FIFO queues of pooled orders; per-side occupancy bitmaps for
  O(1) best-price maintenance; O(1) append/reduce/unlink.
- `MatchingEngine`: grid/band admission before reservation, create-ack first, best-price-first
  FIFO crossing with both-side paired emission (order update + trade + position per side,
  resting side flagged), limit-rest or market-cancel of the remainder, cancel/force-fill unlink,
  ticks reduced to risk-freshness + mark seeding (the mark is the last trade price, ADR-051).

## Behavioral proof (all green on the JVM)

- `LimitOrderBookTest` — price-time priority, best-price-first across levels, partial fills
  keeping queue position, market fill-then-cancel-remainder, market PRICE_MISSING on empty
  unpriced book, off-grid INVALID, out-of-band PRICE_COLLAR, cancel mid-level unlink, ticks
  never fill, last-price-is-last-trade, and replay-determinism (identical recovery digests).
- `ClusterSnapshotCodecTest` — format-2 header geometry + band-anchor round-trip; a post-restore
  crossing sweep reproduces the source's exact fills (per-level FIFO preserved); fail-closed on
  off-grid/out-of-band restored rows, legacy format-1, and every inherited corruption case.
- `AeronClusterSpikeTest` — single-member snapshot+tail AND zero-tail recovery on the crossing
  engine: refs 1..9 never reused, trade counter continues (2 pre-snapshot → 14 after the
  crossing sweep), reservations/executed exposure exact across two recoveries, idempotent retry
  answered with the original ref after both recoveries.
- `ThreeMemberClusterTest` — leader kill, wiped-member empty-disk rejoin, second failover: no ID
  reuse across two failovers, cross-member state equality, trade lineage unbroken (maxTradeSeq 14).
- `OutputDisruptorHandlersTest` — a cross emits per side (order update + TradeBooked +
  PositionUpdated), both positions move, the resting side carries FLAG_RESTING_UPDATE.
- `LmaxHotPathParityTest` (12/12) — REST create/cancel/force-fill/market-trade lifecycle parity
  retained; every fill driven by a genuine resting-opposite + crossing order.

## Zero-allocation and banned-API

- Allocation gates (base `allocationGateTest`, risk-gated `riskAllocationGateTest`, Aeron
  transport, cluster apply-path) pass against the crossing engine, exercising crossing, partial
  fills, market-order remainder cancel, and terminal-retention eviction with exactly zero bytes
  on the producer, journaler, and BLP threads. The base gate needed the same C2-only pinning
  (`-XX:-TieredCompilation -XX:CompileThreshold=10000`) the Aeron/cluster gates already use — the
  crossing engine reshaped inlining enough that a tier transition rematerialized a constant 160
  bytes mid-window (a compiler artifact; an on-thread branch-by-branch probe measures exact zero
  for every steady branch once warm).
- `HotPathBannedApiTest` — `LimitBook` added to the hot-path scan set; no runtime string
  concatenation or other banned APIs on the crossing path.

## Engine latency artifact (NFR-LOB01)

`MatchLatencyBenchmarkTest`, per-order match op measured on the BLP thread under closed-loop
load (no coordinated omission), nanoseconds — the engine's own number, not wire-to-wire:

| Op | p50 | p99 | p99.9 | p99.99 | max |
|---|---|---|---|---|---|
| resting insert | 167 ns | 750 ns | 10.7 µs | 42.7 µs | 291 µs |
| limit cross | 583 ns | 2.2 µs | 23.3 µs | 81.1 µs | 601 µs |
| market order | 584 ns | 2.3 µs | 27.9 µs | 71.9 µs | 2.2 ms |

The median match is sub-microsecond; the tail is JIT/scheduling on a shared dev machine, not the
book operation. Honest talk line: the match is nanoseconds, wire-to-wire is microseconds in
software.

## Open (T-LOB14)

- Live kind-cluster throughput re-measurement on the crossing engine with genuinely two-sided
  marketable flow, against the NFR-AC02 baseline (25,149 booked/s).
- The kind HA recovery proof (crash → promote → empty-disk rejoin → second crash, 0 reuse, book
  identical on all members) on the crossing engine.
