-- FR-DRS-33 recon on the history store (FR-THT-05): the SQL version (scripts/gcp-compsci/rig/fr-drs-33-order-fill-recon.sql, removed with rows mode 2026-11-06)
-- moved to ClickHouse, READ-ONLY. It reads every trade side and every order event the capture streams carried, so it
-- holds in snapshot mode and at any volume; the SQL version needs rows mode (snapshot mode prunes `trades`).
-- Join key: trades.order_ref (the numeric part of sourceOrderId) = orders.order_ref. order_ref 0 is a /trades ticket
-- booking (ADR-100: no order). An order's cumulative fill is its largest quantity - remaining over its live events
-- (NEW, PARTIALLY_FILLED, FILLED): a CANCELED or REJECTED event does not restate it. ClickHouse's LEFT JOIN fills an
-- absent side with 0, so an order with no trade side has qty = sides = 0. One epoch per store (ADR-101: order refs
-- restart at a fresh epoch).

-- 1. Summary, with the control: agree_with_fills must be large, or the join key is wrong and disagree = 0 proves nothing.
WITH o AS (SELECT order_ref, maxIf(toInt64(qty) - remaining, status IN ('NEW', 'PARTIALLY_FILLED', 'FILLED')) AS filled
           FROM orders GROUP BY order_ref),
     t AS (SELECT order_ref, sum(qty) AS qty, count() AS sides FROM trades WHERE order_ref != 0 GROUP BY order_ref)
SELECT countIf(o.filled = t.qty AND t.qty > 0) AS agree_with_fills, countIf(o.filled = 0 AND t.sides = 0) AS agree_unfilled,
       countIf(o.filled != t.qty) AS disagree
FROM o LEFT JOIN t USING (order_ref) FORMAT TSVWithNames;

-- 2. The disagreeing orders (events_filled > trade_filled: a trade side missing; < : an order event missing).
WITH o AS (SELECT order_ref, maxIf(toInt64(qty) - remaining, status IN ('NEW', 'PARTIALLY_FILLED', 'FILLED')) AS filled,
                  argMax(status, (cseq, ordinal)) AS last_status, max(ts) AS last_ts FROM orders GROUP BY order_ref),
     t AS (SELECT order_ref, sum(qty) AS qty, count() AS sides FROM trades WHERE order_ref != 0 GROUP BY order_ref)
SELECT o.order_ref, o.last_status, o.filled AS events_filled, t.qty AS trade_filled, t.sides, o.last_ts
FROM o LEFT JOIN t USING (order_ref) WHERE o.filled != t.qty ORDER BY o.last_ts LIMIT 200 FORMAT TSVWithNames;

-- 3. Trade sides whose order has no event at all. Expected 0.
SELECT uniqExact(order_ref) AS orders_without_events, count() AS sides FROM trades
WHERE order_ref != 0 AND order_ref NOT IN (SELECT order_ref FROM orders) FORMAT TSVWithNames;
