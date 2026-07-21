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

Captured on a quiescent machine (kind workload scaled to zero), 250k–500k measured ops per class:

| Op | p50 | p99 | p99.9 | p99.99 | max |
|---|---|---|---|---|---|
| resting insert | 167 ns | 583 ns | 2.04 µs | 32.1 µs | 136.6 µs |
| limit cross | 542 ns | 1.88 µs | 15.4 µs | 83.6 µs | 272.4 µs |
| market order | 584 ns | 2.04 µs | 15.7 µs | 74.8 µs | 1.20 ms |

The median match is sub-microsecond and p99 is under 2 µs. The tail beyond p99.9 is JIT
compilation, safepoints, and OS scheduling on a shared dev machine — not the book operation; a
tuned host (AOT/GraalVM or Zing ReadyNow, isolated cores, Epsilon) is where the p99.99 goes. Do
not quote p50 alone: the tail is the number that matters. Honest talk line: the match is
nanoseconds, wire-to-wire is microseconds in software, nanoseconds only in silicon.

## Reconciliation with the YU12 lane (2026-07-21)

YU12's `3394186` (synchronous `/trades`) and `2f5a813` (seed-job hardening,
`RECON_POLL_INTERVAL_MS`, gateway replicas=3) were merged in. `2f5a813` cherry-picked cleanly
(none of its manifests are shadowed by a YU13 layer). `3394186` could NOT be cherry-picked: it
patches `ClusterGatewayMain`/`MatchingEngineClusteredService` in the YU12 layer, and YU13 carries
its own copies which win at generation — so it was hand-merged into the YU13 copies.

Two collisions resolved in the merge:

- **Egress ack byte 21.** `3394186` writes the RiskReason ordinal at byte 21; YU13 already uses
  byte 21 for the resting-update class. The two live in different spec layers, so git could not
  flag it. RiskReason moved to byte 22; ack length unchanged (24).
- **`KIND_TRADE_BOOKED` is no longer unique to `/trades`.** The crossing book emits it for BOTH
  sides of every ORDER match, so the ack kind alone would let a foreign fill answer a `/trades`
  request (a 200 for someone else's trade) and would inflate the new market-trade metric with all
  crossing fills. Byte 23 now carries a market-trade class, stamped for the duration of a
  `TYPE_TRADE_NEW` apply (covering mid-apply backpressure drains); the gateway gates both the
  correlation and the counters on it. Verified live: three `/trades` calls against a cluster that
  had just booked two crossing fills reported exactly `booked=1, rejected=2` — un-inflated.

`422` vs `504` preserved exactly: 422 is a committed business rejection carrying its RiskReason;
504 means no committed decision arrived (failover/timeout — ambiguous, the trade may still
commit). Live-verified: valid trade → 200 `{"booked":true}`; unseeded security → 422
`UNKNOWN_SECURITY`; unknown account → 422 `UNKNOWN_ACCOUNT`.

Re-run as a hot-path change after the ack-layout edit: `test` 227/0, `noGcTest` + `riskNoGcTest`,
and all four allocation gates green.

## Live kind measurements (T-LOB14, 2026-07-21)

3-member cluster + 3 gateways on kind (one member per worker, 1 CPU / 1536Mi each), image pinned
`traderx/cluster-node:yu13`, 7 real accounts + a 20-ticker reference universe seeded FIRST (the
OSFF-1 silent-reject gate — verified by a smoke cross that booked 2 fills before any bench ran).

| Measurement | Value |
|---|---|
| Submit rate (in-cluster, 3 gateways, conc 48 × batch 200) | ~10,267 orders/s |
| Booked trades/s (leader engine trade counter, 2 per cross) | **10,533/s** |
| Crossing ratio | 0.92 fills per submitted order — genuinely two-sided |
| Failed | 1.2% under maximum concurrency |

This does NOT clear the 25,149 booked/s NFR-AC02 bar, and it is not a like-for-like comparison —
that baseline was established on GKE (`c3-standard-4` dedicated nodes), whereas this is kind on a
laptop with 1-CPU-limited members inside a Docker VM. The GKE run below settles it.

## Live GKE measurements (T-LOB15, 2026-07-21) — the like-for-like number

3-member cluster on `blp-c3-pool` (3 CPU / 2Gi per member, one per node) + 3 gateways, image
`us-east1-docker.pkg.dev/traderx-501015/traderx/cluster-node:yu13` built `--platform linux/amd64`
(the recurring arm64/amd64 trap), same bench script and parameters as the kind run.

| Run | Config | Submitted | Failed | Booked trades/s |
|---|---|---|---|---|
| 1 (cold JIT) | conc 48 × batch 200 | 1,860,400 | 0 | **62,333** |
| 2 (warm) | conc 48 × batch 200 | 2,253,600 | 0 | **75,440** |
| 3 (over-saturated) | conc 96 × batch 250 | 348,000 | 27,000 | 11,866 — past the knee |

**The crossing engine clears the NFR-AC02 bar decisively: 62–75k booked trades/s vs 25,149,
roughly 2.5–3×, with zero failures at the sustainable concurrency.** Peak observed submit rate
71,606 orders/s.

Two properties worth noting from the run:

- **The book stays bounded.** With genuinely two-sided flow every order crosses, and
  `traderx_book_open_orders` returned to 0 on all three members after each run — the unbounded
  growth of the parent state's `FILL_FULL_THRESHOLD` half-fill policy is structurally gone.
- **No divergence under sustained load.** After ~4.5M booked trades all three members reported an
  identical trade counter and identical book digest.

kind vs GKE is ~7× (10,533 → 75,440) on identical code and bench parameters, confirming the kind
figure was environment-bound (1 CPU vs 3 CPU members, Docker VM vs dedicated nodes) and never
engine-bound — consistent with the 542 ns p50 in-JVM cross.

Two measurement traps worth recording: benching through `kubectl port-forward` understates
throughput badly (a single-threaded proxy — 2,396 vs 9,383 submit/s in-cluster), and the batch
harness rotates tickers per order while alternating sides by index, so with an EVEN ticker count
each symbol receives only one side and nothing ever crosses on a real book. Use an odd ticker
count.

## HA recovery proof on the crossing engine (T-LOB14)

Full evidence in `docs/handoff/PROOF-yu13-kind-ha-crossing-book.md`. Fixture: ten asks @150.050
arriving FIRST, ten @150.000 arriving SECOND — arrival order and price order deliberately
disagree, so the fill pattern is a falsifiable test of price priority.

Proven across two crashes and two promotions, asserted from the members' own book digests:

- Snapshot format 2 **round-trips the full two-level resting book across a failover**: the killed
  leader reloaded its own snapshot and reconstructed a book hash byte-identical to the members
  that never restarted.
- **Price-time priority survives the failover** — the post-failover sweep consumed exactly the ten
  better-priced asks (the ones that arrived second) and left the worse level untouched.
- **Book identical on all three members** at every checkpoint; a snapshot-recovered member later
  won an election and led successfully.
- **Zero ID reuse**: `nextOrderRef` strictly monotonic 1 → 21 → 22 → 23 → 24 across both crashes.
- **Determinism across epochs**: the same input sequence replayed in a different epoch after a
  full wipe produced the identical book hash.

NOT proven — **empty-disk rejoin into an already-advanced cluster**. The joiner wedges in consensus
log replication (`applied=-1`). This is the inherited Aeron 1.51 defect documented in the YU12 lane
(`ISSUES-yu12-rejoin-term-poisoning-2026-07-19.md`), not a YU13 regression: it reproduced on a
cluster whose book was EMPTY with no format-2 snapshot content, and no snapshot/format/band error
appears in the joiner's logs — the failure is entirely inside log replication, before the service
loads any state. Three members starting together from empty always converge (done four times).

## Open

- A like-for-like GKE throughput run against the 25,149 bar on the crossing engine.
- The inherited Aeron 1.51 empty-disk-rejoin defect (YU12 issue) blocks that acceptance line on
  any aged epoch; it needs the Aeron-level fix, not a YU13 change.
