# Implementation Plan: YU13-limit-order-book

## Goal

Replace the inherited price-triggered auto-fill matching policy with a genuine crossing
limit-order book — two-sided per-security books, price-time priority, execution at the resting
price, partial fills, market orders — inside the unchanged Aeron Cluster hosting, preserving the
three standing disciplines: determinism from the consensus log alone, zero-allocation steady
state, and complete snapshot recovery.

## Workstreams

1. **Book structure** — `LimitBook`: array-indexed price levels on a fixed 0.001 grid inside a
   banded per-security window; intrusive doubly-linked FIFO queues of pooled `RestingOrder`
   entries; occupancy bitmaps maintaining best bid/ask; O(1) append, reduce, and unlink.
2. **Matching engine** — `MatchingEngine` rework: grid/band admission before reservation,
   create-ack first, cross loop executing both sides per step (paired order update + trade +
   position emission, resting side flagged), remainder rest or market-cancel, cancel/force-fill
   unlink, ticks reduced to risk-freshness plus mark seeding.
3. **Cluster snapshot format 2** — header carries book geometry; T_BOOK records carry band
   anchors; open rows restore in ascending-reference order rebuilding exact per-level FIFO;
   fail-closed on off-grid or out-of-band restored rows and on format mismatch.
4. **Gateway correlation** — resting-class byte in every egress ack; offer/ack and pipelined
   batch accounting count only direct acks; booked-fill metric counts both sides.
5. **Proof surface** — crossing-semantics unit tests (priority, partials, market, cancel, band,
   determinism), snapshot round-trip and fail-closed tests, allocation gates re-worked to a
   level-neutral two-sided crossing mix, match-latency histogram benchmark, and the inherited
   integration suite rewritten from tick-fill to crossing flow.

## Key decisions

- Grid 0.001 (`BOOK_TICK_PX`, 1_000 Px units): exactly the 3dp granularity the price edges
  produce, so every edge-representable price is on-grid (ADR-050).
- Band `1<<17` ticks (`BOOK_LEVELS`, $131.07) anchored mid-band on the security's first limit
  price, clamped at zero; both values are config identity across members and are carried in the
  snapshot header on restore.
- The last price is the last TRADE price; ticks only seed it before the first print (ADR-051).
- Egress ack byte 21 is the resting-update class; the 24-byte ack length is unchanged, so
  clients that ignore the byte observe the previous wire behavior.

## Exit Criteria

- All spec-pack success criteria SC-LOB01..06 hold with recorded evidence in
  `generation/implementation-status.md`.
- `bash pipeline/generate-state.sh YU13-limit-order-book` exits 0 and the generated tree carries
  every ancestor marker on shared files.
- The snapshot completeness audit passes against the format-2 snapshot, and the kind HA recovery
  proof (crash → promote → empty-disk rejoin → second crash) holds on the crossing engine with
  zero identifier reuse and identical books on all members.
