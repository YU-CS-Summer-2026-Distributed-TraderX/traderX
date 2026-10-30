-- FR-DRS-33 one-off, READ-ONLY: order rows whose cumulative fill disagrees with their trade sides in SQL.
-- Run at the next bring-up, against eod-price-db, BEFORE trading. Rows mode only: snapshot mode prunes `trades` to
-- blotter windows (TradeSnapshotStore), which would make every older order look stale.
--
-- Join key: trades.sourceorderid and orderbook.orderid are both "<epoch>-<orderRef>" (TradeNatsPublisher,
-- OrderNatsPublisher). Each trade row is one side, so it belongs to exactly one order.
-- What a stale row looks like (OrderView.filled): FILLED rows take filled = quantity, and NEW/PARTIALLY_FILLED rows
-- take quantity - remaining, so a lost middle update cannot leave either of them stale. CANCELED, REJECTED and
-- SUSPENDED rows carry the filled quantity from the row's previous state, so a lost partial fill before a cancel
-- leaves filledquantity low. That is the class G3's open-rows check cannot see.

-- 1. Summary, with the control. agree_with_fills must be large: if it is ~0, the join key is wrong and a
--    disagree of 0 proves nothing. unchecked = rows from before the filledquantity column (FR-DRS-27); not compared.
SELECT
  SUM(o.filledquantity IS NOT NULL AND o.filledquantity = COALESCE(t.qty, 0) AND t.qty > 0) AS agree_with_fills,
  SUM(o.filledquantity IS NOT NULL AND o.filledquantity = 0 AND t.qty IS NULL)              AS agree_unfilled,
  SUM(o.filledquantity IS NOT NULL AND o.filledquantity <> COALESCE(t.qty, 0))              AS disagree,
  SUM(o.filledquantity IS NULL)                                                             AS unchecked
FROM orderbook o
LEFT JOIN (SELECT projectionscope, sourceorderid, SUM(quantity) AS qty, COUNT(*) AS sides
           FROM trades WHERE sourceorderid IS NOT NULL GROUP BY projectionscope, sourceorderid) t
  ON t.projectionscope = o.projectionscope AND t.sourceorderid = o.orderid;

-- 2. The disagreeing rows (row_filled < trade_filled = a lost update; row_filled > trade_filled = a trade side
--    missing from SQL, which G3's 1..N check would already have refused).
SELECT o.projectionscope, o.orderid, o.status, o.quantity, o.remainingquantity,
       o.filledquantity AS row_filled, COALESCE(t.qty, 0) AS trade_filled, COALESCE(t.sides, 0) AS sides,
       o.consensussequence, o.updatedat
FROM orderbook o
LEFT JOIN (SELECT projectionscope, sourceorderid, SUM(quantity) AS qty, COUNT(*) AS sides
           FROM trades WHERE sourceorderid IS NOT NULL GROUP BY projectionscope, sourceorderid) t
  ON t.projectionscope = o.projectionscope AND t.sourceorderid = o.orderid
WHERE o.filledquantity IS NOT NULL AND o.filledquantity <> COALESCE(t.qty, 0)
ORDER BY o.projectionscope, o.updatedat
LIMIT 200;

-- 3. Trade sides whose order has no row at all: the order's every update was lost. Expected 0.
--    Excludes "<epoch>-0": a booking through the gateway's /trades (the demo ticket, TYPE_TRADE_NEW) is a trade with no
--    order, carrying the reserved orderRef 0; the engine issues refs only on ORDER_NEW, from 1 (FR-DRS-33 recon note).
--    Those sides are still counted by G3's 1..N trade check.
SELECT t.projectionscope, COUNT(DISTINCT t.sourceorderid) AS orders_without_row, COUNT(*) AS sides
FROM trades t
LEFT JOIN orderbook o ON o.projectionscope = t.projectionscope AND o.orderid = t.sourceorderid
WHERE t.sourceorderid IS NOT NULL AND o.orderid IS NULL AND t.sourceorderid NOT LIKE '%-0'
GROUP BY t.projectionscope;
