# Data Model: YU05 Post-Trade Compliance Bundle

## Deterministic trade identity (all sub-capabilities depend on this)

`trd-09b-<tradeSeq>` — `OrderSnapshot.tradeIdFor(long tradeSeq)`. `tradeSeq` is the BLP's single-
writer, snapshot-persisted global trade counter — a pure function of journal replay order, so the
derived id is stable across restarts, replays, and redeliveries. **The live writer,
`ProjectorHandler.toTrade()`, already used this correctly**; `TradeOrder.fromEvent()` (the
optional, disabled-by-default legacy NATS path) was fixed to match — see research.md.

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

## Settlement (`TRADES` table, MariaDB)

Extends the existing `TRADES` table (which already has a `State` column with `Settled` as a valid
value) with one new column:

| Column | Type | Notes |
|---|---|---|
| `settlementdate` | DATETIME, nullable | Set at booking time to `created + settlement.t-plus-days` business days (default 1). Advanced trades (`Processing → Settled`) are swept once `now() >= settlementdate`. |

`State` transitions: `New` → `Processing` (at booking, real `settlementDate` computed —
**`ProjectorHandler.toTrade()` is the live writer that sets this**, not trade-processor's
`TradeService`, which only matters if the legacy NATS path is ever enabled) → `Settled` (scheduled
sweep, in trade-processor, or manual force) | `Cancelled` (not produced by any code path yet;
reserved for a future cancel-fill scenario).

## Reconciliation classification (`trade-processor`, computed)

Not persisted as a table (in-memory summary, refreshed each sweep):

| Classification | Meaning | Sweep |
|---|---|---|
| `MATCHED` | Blotter entry and MariaDB row exist for the id, all compared fields agree. | Forward (scheduled) |
| `MISSING_IN_PROJECTION` | Blotter (journal-derived) has the id; no MariaDB row exists yet — a dropped/delayed write. | Forward (scheduled) |
| `FIELD_MISMATCH` | Both exist; `accountId`/`security`/`side`/`quantity`/`price` differ. | Forward (scheduled) |
| `ORPHAN_IN_PROJECTION` | A MariaDB row with no corresponding fill anywhere in the full journal history. | Full-history (on-demand, FR-PTC10) |

Forward-sweep cursor: last `tradeSeq` successfully swept, persisted only in-memory (restarts
re-scan from the current blotter's oldest retained entry — acceptable because the blotter itself is
bounded and rebuilt on restart).

## Full-history index (`order-matcher`, in-memory, on-demand — FR-PTC10)

An unbounded `TradeBlotter` instance, populated only when `POST /recon/full-history/reindex` is
triggered (never automatically), by replaying the *entire* journal through a shadow `MatchingEngine`
(same construction as `verifyJournalReplay()`/`generateRegulatoryReport()`) with a
`TradeBlotterHandler` as its only output-ring listener. Same row shape as the live blotter above.
Overwritten by each new reindex; not persisted across restarts (an operator re-triggers as needed).

## Audit record (`order-matcher`, in-memory, on-demand — ADR-023, FR-PTC20/21)

One record per reportable output event, captured by `AuditLogHandler` during a
`generateRegulatoryReport(fromSeq, toSeq)` shadow replay:

| Field | Type | Source |
|---|---|---|
| `kind` | String | `ORDER_ACCEPTED` / `ORDER_REJECTED` / `ORDER_PARTIALLY_FILLED` / `ORDER_FILLED` / `ORDER_CANCELED` / `TRADE_BOOKED` |
| `inputSeq` | long | `OutputEvent.inputSeq` — the range filter key |
| `orderId` | String | `OrderSnapshot.orderIdFor(orderRef)` |
| `tradeId` | String, nullable | `OrderSnapshot.tradeIdFor(tradeSeq)`, only for `TRADE_BOOKED` |
| `accountId`, `security`, `side`, `quantity`, `price` | — | Same derivation as the trade blotter |
| `timestampMillis` | long | `OutputEvent.updatedAtMillis` (event-carried, not wall-clock) |

## TCA report (`trade-processor`, computed on demand — ADR-024, FR-PTC30-32)

| Field | Type | Notes |
|---|---|---|
| `tradeId`, `security`, `side`, `quantity`, `executionPrice` | — | From the `Trade` row. |
| `benchmarkPrice` | BigDecimal, nullable | TWAP over `[created - tca.window-minutes, created]`; falls back to nearest-prior-sample "arrival price" if no window samples exist; `null` if no price history covers either. |
| `arrivalPrice` | BigDecimal, nullable | Nearest price sample at or before the window start. |
| `slippageBps` | BigDecimal, nullable | `(executionPrice - benchmark) / benchmark * 10000`, sign-flipped for `Sell` — positive always means "worse than benchmark." `null` when `benchmarkPrice` is `null` (never fabricated as 0). |
| `benchmarkSampleCount` | int | How many `PriceHistoryStore` samples backed the TWAP — 0 if only the arrival-price fallback applied. |

Backing data: `PriceHistoryStore` — a bounded (`tca.price-history.capacity-per-ticker`, default
10,000), per-ticker, time-ordered sample list `(price, timestampMillis)`, fed by `PriceTickHandler`
subscribing to price-publisher's existing `pricing.*` NATS feed. VWAP is not computed (FR-PTC32
deferred) — the feed carries no per-tick volume.

## Deferred data model (specified, not implemented)

- **Entitlements** (`FR-PTC40`): `(principalKey, accountId) -> enabled` mapping, feeding the same
  `entitlementKeys`/`entitlementEnabled` structures `BlpRiskState` already has allocated and unused
  since YU03 (see YU03 `data-model.md`).
