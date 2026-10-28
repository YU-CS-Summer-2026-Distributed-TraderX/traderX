# ADR-088: A canceled order keeps its fill

Status: accepted 2026-10-28 (coordinator queue). Requirements: FR-DRS-27 ([downstream read side](../components/downstream-read-side/spec.md)),
FR-FFR-12 ([FIX fill reports](../maintenance/fix-fill-reports/spec.md)). Found by `yu18-fix-resume` step 5 (ADR-086 evidence).

## Context

`OrderStatusRequest` for a CANCELED order answered `CumQty = quantity − remainingQuantity`. The engine sets
`remaining = 0` on every cancel (client, STP, band re-anchor, DAY expiry, FOK/IOC/market remainder), because
for a terminal order `remaining` is FIX `LeavesQty`. So a Sell 3 canceled unfilled was reported
`OrdStatus=4 CumQty=3`: fully filled. No reader could do better, because no order update or read-model row
carried the filled quantity of a canceled order. The Canceled report the gateway sends on an accepted cancel
had the mirror defect: it hard-coded `CumQty 0`, so a partly filled order canceled read as never filled.

## Decision

1. **The read model owns the filled quantity.** `OrderUpdate`/`OrderRow` gain `filledQuantity`
   (`orderbook.filledquantity`). Open: `quantity − remaining`; FILLED: `quantity`; CANCELED/REJECTED: the
   previous state's value. The engine emits every partial fill as its own order update before the cancel
   (`emitFillWithTradeAndPosition`), so the previous state is exact. The in-memory `OrderView` and the SQL
   projection share one rule (`OrderView.filled`); the SQL path reads the row it already loads to keep the
   trace id, so this costs no extra query. A row from before the column falls back to its own open state.
2. **The gateway reads it.** `ORDER_READMODEL_URL` responses carry `filledQuantity`; status reports use it
   as `CumQty`. A read model without the field gives 0 for CANCELED/REJECTED, never the whole quantity.
3. **The Canceled report carries what was reported.** On an accepted cancel the gateway reports the
   tracked `CumQty`/`AvgPx` (ADR-084's per-order state, rebuilt on adoption by ADR-086 §3), then forgets it.

## Rejected

- **Carry the filled quantity on the engine's output.** Exact at the source, but `RestingOrder` has no such
  field; a terminal re-publish (a cancel of a canceled order) needs it kept, which is a snapshot format bump and
  a fresh epoch, for a value the read model can derive from updates it already applies in order.
- **Stop zeroing `remaining` on cancel.** Changes deterministic state (a coordinated roll) and the meaning of
  `remainingQuantity` for every reader, where FIX wants `LeavesQty 0`.

## Consequences

- A schema column: `900-migrations.sql` adds it idempotently; the database pod's `schema-migrate` init
  container applies it on its next start, and trade-processor (`ddl-auto=validate`) refuses to start
  until it exists. So on an existing volume the order is: ConfigMap, database pod restart, then trade-processor.
- The in-memory view (`ORDER_FEED_PERSIST=false`) reads 0 for an order whose earlier updates it never held
  (evicted from the terminal ring, or before its first message). The SQL projection has no such gap.
- No engine, wire, ack, snapshot or consensus change. Being derived, the field is excluded like `traceId` from the event
  digest, the event-recovery retained-row check, the run-transition legacy check and the sealed-legacy no-op check.
