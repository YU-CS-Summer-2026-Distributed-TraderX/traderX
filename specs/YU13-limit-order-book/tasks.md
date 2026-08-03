# Tasks: YU13-limit-order-book

## Spec and generation

- [x] T-LOB01 Create the full YU13 spec pack and generated architecture document.
- [x] T-LOB02 Add the catalog entry, state-generation hook, and render script; inherit the YU12
  runtime harness.
- [x] T-LOB03 Generate YU13 from a clean target and verify every ancestor marker survives on the
  shared overridden files.

## Crossing book

- [x] T-LOB04 Add `LimitBook`: array-indexed price levels on a 0.001 grid inside a banded
  window, intrusive FIFO queues of pooled orders, occupancy-bitmap best-price maintenance.
- [x] T-LOB05 Rework `MatchingEngine` into the crossing engine: grid/band admission, create-ack
  first, best-price-first FIFO cross with both-side paired emission, rest or market-cancel of the
  remainder, cancel/force-fill unlink, ticks reduced to freshness + mark seeding.
- [x] T-LOB06 Add `FLAG_RESTING_UPDATE` and the book-membership links to the output/order model;
  keep `LimitBook` and the engine banned-API-clean (hot-path scan set extended).

## Cluster hosting

- [x] T-LOB07 Snapshot format 2: capture book geometry in the header and per-security band
  anchors before order rows; restore rebuilds each level's FIFO from ascending-ref rows; fail
  closed on off-grid/out-of-band rows and on a legacy format-1 snapshot.
- [x] T-LOB08 Resting-class egress ack byte; gateway offer/ack and pipelined-batch accounting
  count only direct acks; booked-fill metric counts both sides.

## Proof

- [x] T-LOB09 Crossing-semantics unit tests: price-time priority, partial fills, market orders,
  cancel unlink, grid/band admission, last-trade-price, replay determinism (`LimitOrderBookTest`).
- [x] T-LOB10 Snapshot round-trip + fail-closed tests including book price-time priority
  preservation (`ClusterSnapshotCodecTest`).
- [x] T-LOB11 Rework the allocation gates to a two-sided level-neutral crossing mix with
  terminal-retention eviction hot; keep base/risk/aeron/cluster gates and the Epsilon no-GC run
  green.
- [x] T-LOB12 Rework the inherited integration suite (parity, output-handlers, spike, three-member)
  from tick-triggered fills to crossing flow, preserving each test's original proof intent.
- [x] T-LOB13 Match-latency histogram benchmark: p50/p99/p99.9/p99.99/max in ns for resting
  inserts, limit crosses, and market orders under closed-loop load (`MatchLatencyBenchmarkTest`).
- [x] T-LOB14 Re-bench booked throughput on a live kind cluster with two-sided marketable flow
  and run the kind HA recovery proof on the crossing engine. 10,533 booked trades/s on kind (not
  like-for-like with the GKE-measured 25,149 bar — see `generation/implementation-status.md`); HA
  proof (format-2 snapshot round-trips the
  full resting book across a failover, price-time priority preserved, book identical on all
  members, zero ID reuse across two crashes).

## Carried forward

- [x] T-LOB15 Like-for-like GKE throughput run against the 25,149 NFR-AC02 bar on the crossing
  engine. Done 2026-07-21: 62,333 booked trades/s cold, 75,440 warm (conc 48 x batch 200, zero
  failures) on `blp-c3-pool` — 2.5-3x the bar. Book returns to zero open orders after each run;
  all three members identical after ~4.5M trades. See `generation/implementation-status.md`.
- [ ] T-LOB16 Empty-disk rejoin into an already-advanced cluster is blocked by the inherited
  Aeron 1.51 term-history defect (`ISSUES-yu12-rejoin-term-poisoning-2026-07-19.md`), verified
  independent of the crossing book. Needs the Aeron-level fix.
