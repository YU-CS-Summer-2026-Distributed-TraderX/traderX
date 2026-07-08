# Data Model: YU05 Post-Trade Compliance Bundle

## Deterministic trade identity (all sub-capabilities depend on this)

`trd-09b-<tradeSeq>` — `OrderSnapshot.tradeIdFor(long tradeSeq)`, already existed, now actually
wired into `TradeOrder.fromEvent()` (order-matcher) and used verbatim as the MariaDB `TRADES.ID`
(trade-processor). `tradeSeq` is the BLP's single-writer, snapshot-persisted global trade counter —
a pure function of journal replay order, so the derived id is stable across restarts, replays, and
redeliveries.

## Trade blotter (`order-matcher`, in-memory, replay-rebuilt — slice 1)

Not a database table; not part of the journal/snapshot wire format. Populated by
`TradeBlotterHandler` (a new output-ring `EventHandler<OutputEvent>` that does not suppress on
replay) from every `KIND_TRADE_BOOKED` event.

| Field | Type | Source |
|---|---|---|
| `id` | String | `OrderSnapshot.tradeIdFor(tradeSeq)` |
| `tradeSeq` | long | `OutputEvent.tradeSeq` |
| `accountId` | int | `OutputEvent.accountId` |
| `security` | String (ticker) | `SymbolTable.tickerFor(securityId)` |
| `side` | String (`Buy`/`Sell`) | `OutputEvent.side` |
| `quantity` | int | `OutputEvent.tradeQty` |
| `price` | BigDecimal | `Px.toBigDecimal(OutputEvent.tradePx)` |
| `execTimeMillis` | long | `OutputEvent.updatedAtMillis` |

Bounded FIFO by insertion (tradeSeq) order, capacity `recon.blotter.capacity` (default 500,000);
oldest entries evicted once full. Rebuilt in full on recovery (see `research.md`), because the
output-ring handler chain runs during journal replay and this handler does not gate on
`readModel.isReplaying()`.

## Settlement (`trade-processor`, MariaDB — slice 1)

Extends the existing `TRADES` table (which already has a `State` column with `Settled` as a valid
value — unused until now) with one new column:

| Column | Type | Notes |
|---|---|---|
| `settlementdate` | DATETIME, nullable | Set at booking time to `created + settlement.t-plus-days` business days (default 1). Advanced trades (`Processing → Settled`) are swept once `now() >= settlementdate`. |

`State` transitions (slice 1): `New` → `Processing` (immediately, at booking — fill and booking are
synchronous in this system) → `Settled` (scheduled sweep or manual force) | `Cancelled` (not
produced by any code path yet; column/enum value already existed, reserved for a future cancel-fill
scenario).

## Reconciliation classification (`trade-processor`, computed — slice 1)

Not persisted as a table in slice 1 (in-memory summary only, refreshed each sweep):

| Classification | Meaning |
|---|---|
| `MATCHED` | Blotter entry and MariaDB row exist for the id, all compared fields agree. |
| `MISSING_IN_PROJECTION` | Blotter (journal-derived) has the id; no MariaDB row exists yet — a dropped/delayed NATS delivery. |
| `FIELD_MISMATCH` | Both exist; `accountId`/`security`/`side`/`quantity`/`price` differ. |
| `ORPHAN_IN_PROJECTION` (deferred, `FR-PTC10`) | A MariaDB row with no corresponding blotter entry — needs full-history blotter coverage, not implemented in slice 1. |

Recon cursor: last `tradeSeq` successfully swept, persisted only in-memory in slice 1 (restarts
re-scan from the current blotter's oldest retained entry — acceptable because the blotter itself is
bounded and rebuilt on restart).

## Deferred data model (specified, not implemented)

- **Regulatory reporting** (`FR-PTC20`): a flat audit record per order/trade lifecycle event
  (event type, timestamp, account, security, side, qty, price, order id, trade id), sourced from
  the *input* journal via `JournalReader` (already exists) plus the *output*-side trade blotter,
  not the MariaDB projection.
- **TCA** (`FR-PTC30`): per-trade execution-quality record (arrival price, VWAP/TWAP benchmark,
  slippage in bps) computed over a settled trade + a historical/synthetic price series.
- **Entitlements** (`FR-PTC40`): `(principalKey, accountId) -> enabled` mapping, feeding the same
  `entitlementKeys`/`entitlementEnabled` structures `BlpRiskState` already has allocated and unused
  since YU03 (see YU03 `data-model.md`).
