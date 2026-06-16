# Data Model: LMAX Sequencer Architecture (Trading Hot Path)

## Scope

This state defines data model impact relative to `009-order-management-matcher`. The external/relational
surface is preserved; the authoritative store moves from the database to the input journal, and a set of
in-memory and binary structures is introduced on the hot path.

## Entity Changes

- Added: **Input event** (ring slot / SBE message, one mutable holder per slot, reused forever):
  - `seq` (long, global sequence number, strictly monotonic)
  - `type` (byte: `ORDER_NEW | ORDER_CANCEL | FORCE_FILL | PRICE_TICK | TRADE_NEW`)
  - `accountId` (int)
  - `securityId` (int, mapped from ticker at the Gateway)
  - `side` (byte: 0=Buy, 1=Sell)
  - `qty` (long)
  - `limitPx` (long fixed-point, ×1,000,000)
  - `priceTicks` (long fixed-point, for `PRICE_TICK`)
  - `ingressNanos` (long, stamped at the Gateway; the only time source on the hot path)
- Added: **Output event** (output ring slot):
  - `seq` (long, echoes the producing input sequence)
  - `kind` (byte: `ORDER_ACCEPTED | ORDER_REJECTED | ORDER_PARTIALLY_FILLED | ORDER_FILLED |
    ORDER_CANCELED | TRADE_BOOKED | POSITION_UPDATED`)
  - `accountId` (int), `securityId` (int), `side` (byte)
  - `qty` (long), `pxTicks` (long fixed-point), `remainingQty` (long)
  - `status` (byte: `NEW | PARTIALLY_FILLED | FILLED | CANCELED | REJECTED`)
  - `ingressNanos` (long, carried through for true end-to-end latency at egress)
- Added: **Journal record** (append-only, authoritative): `(seq, type, raw SBE bytes, ingressNanos)`;
  schema-versioned (`schemaId`/`version`) for forward replay.
- Added: **Snapshot**: serialized BLP state (order books, positions, caches) plus the sequence number it
  reflects; written on the snapshot cadence and on nightly bounce.
- Added: **Projection checkpoint**: last projected `seq`, persisted by the Read-model Projector so
  rebuilds and restarts are idempotent.
- Added: **Symbol table**: `ticker (string) <-> securityId (int)` mapping owned by the Gateway;
  strings never cross into the rings or BLP.
- Added: in-memory BLP state (pre-allocated/pooled, never `new`-ed mid-life):
  - `OrderBook[] booksBySecurity` — array indexed by `securityId`; pooled resting-order entries,
    returned to the free list at terminal status.
  - `Long2ObjectHashMap<Position>` keyed by `accountId × securityId` (`qty`, `avgPx` fixed-point).
  - `long[] lastPxBySecurity` — last price per security, fixed-point.
  - `Int2ObjectHashMap<Account>` and `Int2ObjectHashMap<Security>` validation caches, event-fed,
    warmed at startup; misses resolved via request/response events.
- Changed: **`OrderBook` table is demoted to an async read-model.** Column shape from `009`
  (`orderId`, `accountId`, `security`, `side`, `quantity`, `remainingQuantity`, `limitPrice`
  decimal(18,3), `status`, `createdAt`, `updatedAt`, `lastExecutionPrice`, `lastFillQuantity`) is
  unchanged; rows are written by the batched Projector from output events instead of inline JPA.
  Trade/position rows likewise remain schema-identical and become projector-written on this path.
- Changed: order IDs derive deterministically from the global sequence (the `009`
  `String.format("ord-013-%04d", …)` scheme is replaced by a pure function of `seq`, rendered to the
  existing external string shape at the output edge).
- Removed: matcher-internal `ConcurrentHashMap<String,BigDecimal> lastPrices`, `AtomicInteger`
  order-sequence counters, and per-tick JPA query results as state carriers (replaced by the structures
  above).

## Compatibility Notes

- Backward compatibility requirements are reflected in `requirements/functional-delta.md`,
  `requirements/nonfunctional-delta.md`, and `contracts/contract-delta.md`.
- Source of truth: the journal is authoritative; the relational read-model is rebuildable by
  re-projection (FR-09B23). The `OrderBook` schema generation contract from `009`
  (NFR-01312/NFR-01313) remains binding.
- Open order semantics carry over from `009`: `open` = status in `NEW|PARTIALLY_FILLED`; `unfilled` =
  `remainingQuantity > 0`.
- Auto-fill policy semantics carry over from `009` and are evaluated in fixed-point integer math:
  - `Buy` in-the-money when `marketPrice <= limitPrice`; `Sell` when `marketPrice >= limitPrice`.
  - remaining `< 1000`: full fill; otherwise half fill (rounded up), per triggering event.
- Fixed-point: global scale ×1,000,000 (6 dp), `187.250 -> 187_250_000L`. All `BigDecimal`/string
  rendering happens at the Gateway/output edges; rounding is locked to `009` behavior by the
  penny-parity fixture (SC-09B04).
- Eventual consistency (by design): UI is push-fed from output events; the relational read-model is a
  slightly-behind projection. REST bootstrap reads remain correct because the projector checkpoint is
  monotonic over the same ordered stream.

## Ring sizing

`slots_needed >= peak_input_rate × max_handler_stall × safety_factor`, rounded up to a power of two.
Demo example: 50,000 events/s × 2 ms journaler stall × 4 = 400 → generously rounded to
`2^16 = 65,536` (input and output). Perf profile: `2^20`. Memory ≈ `ring_size × (holder + off-heap
slot buffer)` (~256 MB at `2^20 × 256 B`), pre-touched at startup. Config keys:
`disruptor.input.ring-size`, `disruptor.output.ring-size`.

## Traceability

- Input stream/journal/snapshot shapes link to FR-09B01..FR-09B08, FR-09B16 in `spec.md`.
- BLP in-memory structures link to FR-09B10..FR-09B15 and NGC-01/NGC-04.
- Output event/read-model/checkpoint shapes link to FR-09B20..FR-09B25.
- Fixed-point and symbol-table rules link to FR-09B05, NGC-03, SC-09B04.
