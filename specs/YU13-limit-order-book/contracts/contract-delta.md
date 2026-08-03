# Contract Delta: YU13-limit-order-book

## 1. Existing external contracts

REST `/orders`, REST `/orders/batch`, REST `/trades`, FIX 4.4 order entry, risk decisions, order
lifecycle output, trade booking, UI routing, and every inherited NATS subject retain their
parent-state contracts for external consumers. The order-entry, cancel, and market-trade wire
shapes are unchanged; what changes is the matching behavior behind them — an order now rests or
crosses against a real book instead of auto-filling against a reference price. A market order
that crosses partial depth returns its remainder canceled rather than resting.

## 2. Matching-behavior contract (replaces the parent auto-fill policy)

| Aspect | Contract |
|---|---|
| Limit order | Rests at its price level's FIFO tail unless it crosses; crossing executes at the resting order's price, best level first, oldest order first. |
| Market order | Executes immediately against available depth; any unfilled remainder is canceled in place (never rests). Validated at the last trade price, else the opposite best; PRICE_MISSING when neither exists. |
| Price tick | Never triggers a fill; feeds risk freshness and seeds the mark only until the book first trades (ADR-051). |
| Execution price | The resting order's limit price; grid alignment makes it a valid price for both sides. |
| Both sides | Each match books a trade and updates a position on both the resting and the aggressing side; the resting side's order update carries FLAG_RESTING_UPDATE. |
| Price admission | Off-grid limit → INVALID; out-of-band limit → PRICE_COLLAR; both before any reservation. |

## 3. Book configuration

| Variable | Values / default | Contract |
|---|---|---|
| `BOOK_LEVELS` | power of two ≥ 64; default 1<<17 | Per-security band width in ticks; config-identity, identical on every member, carried in the snapshot header. |
| `BOOK_TICK_PX` | positive long; default 1_000 (0.001) | Price grid in Px units; every edge-representable price is on-grid; config-identity, carried in the snapshot header. |

## 4. Egress ack contract

The committed egress ack keeps its 24-byte length and all existing fields; byte 21 is the
resting-update class (1 = counterparty resting-order update, 0 = direct response to the acked
input). A client that ignores byte 21 observes the parent-state wire behavior.

## 5. Snapshot/recovery contract

Snapshot format is 2. The header additionally carries book geometry (`BOOK_LEVELS`,
`BOOK_TICK_PX`); a `T_BOOK` record carries each created book's band anchor and precedes that
book's order rows; open order rows in ascending-reference order rebuild each price level's exact
FIFO on restore. All inherited fail-closed rules hold, and recovery additionally fails closed on
an off-grid or out-of-band restored open row and on a legacy (format 1) snapshot — cross-format
restore is not a supported flow. Every other inherited recovery, readiness, and no-ID-reuse
contract from the parent state is unchanged.
