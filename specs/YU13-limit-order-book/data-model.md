# Data Model: YU13-limit-order-book

## LimitBook (one per security, lazily created on the security's first order)

| Field | Type | Meaning |
|---|---|---|
| `tickTicks` | long | Px units per book tick (1_000 = 0.001) |
| `levels` | int | band width in ticks (power of two, default 1<<17) |
| `baseLevel` | long | absolute tick index of array slot 0; -1 until anchored |
| `bidHead/bidTail`, `askHead/askTail` | RestingOrder[levels] | per-level intrusive FIFO queue ends |
| `bidQty`, `askQty` | long[levels] | aggregate open quantity per level (racy-safe edge depth reads) |
| `bidBits`, `askBits` | long[levels/64] | level-occupancy bitmaps driving best-price maintenance |
| `bestBid`, `bestAsk` | int | current best level slots (NO_LEVEL when side empty) |
| `openOrders` | int | resting order count |

Slot mapping: `slot = limitPx / tickTicks - baseLevel`; anchoring on first use places the first
price mid-band (`baseLevel = max(0, absLevel - levels/2)`). `priceAt(slot)` is exact: grid
admission guarantees a level price equals every resting order's limit at that level.

## RestingOrder additions (pooled entry; links are BLP-private, never serialized)

| Field | Type | Meaning |
|---|---|---|
| `bookNext`, `bookPrev` | RestingOrder | intrusive FIFO links within the price level |
| `bookLevel` | int | level slot while resting; NO_LEVEL otherwise (`isResting()`) |

## Engine state changes

- `booksBySecurity: LimitBook[]` replaces the per-security open-reference `IntList` index; open
  orders ARE book nodes, and recovery digests/tuples iterate the dense `ordersByRef` index
  filtered by open status.
- `lastPxBySecurity` holds the last TRADE price; `PRICE_TICK` seeds it only while `Px.NONE`.
- `bookLevels`/`bookTickPx` are engine-wide geometry (env `BOOK_LEVELS` / `BOOK_TICK_PX`,
  defaults 1<<17 and 1_000), settable before first use and adopted from the snapshot on restore.

## Cluster snapshot format 2

| Record | Layout | Notes |
|---|---|---|
| `T_HEADER` (52 B) | int type, int format=2, long nextOrderRef, long highestIssuedRef, long appliedSeq, long tradeCounter, int bookLevels, long bookTickPx | geometry adopted before any book exists |
| `T_BOOK` = 11 | int type, long securityId, long baseLevel | one per created book, written before order rows; baseLevel -1 round-trips an unanchored book |
| `T_ORDER` | unchanged 15-field tuple | open rows first in ascending ref order (arrival order → exact per-level FIFO on restore), then terminal rows in eviction-FIFO order |
| all other records | unchanged from format 1 | policy, accounts, securities, symbols, idempotency, positions, prices |

Fail-closed on restore: unknown format (including format 1), off-grid open limit, open row
outside the restored band, order ref at/beyond the restored generator, missing header, unknown
record type.

## Egress ack (24 bytes, length unchanged)

| Offset | Field |
|---|---|
| 0 | long appliedSeq |
| 8 | int orderRef |
| 12 | byte kind |
| 13 | long tradeSeq |
| 21 | byte restingClass — 1 = counterparty resting-order update, 0 = direct response |

## Output-event flags

`FLAG_RESTING_UPDATE = 1<<6` marks the resting side's order update in a match step; all other
kinds and flags are unchanged from the parent state.
