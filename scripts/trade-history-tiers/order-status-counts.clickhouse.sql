-- FR-THT-12 (yaakov 2026-11-10: "Yes, count by order"): how many orders reached each status, READ-ONLY. A status is
-- counted once per order (distinct order_ref within the status), never once per row, so a repeated event cannot inflate
-- an order count: the store keeps every row the bridge publishes (FR-THT-11 leaves out only positioned repeats).
-- `repeated_rows` shows any order that carries the same status more than once; expected 0, and not a failure by itself.
-- One epoch per store (ADR-101: order refs restart at a fresh epoch). Compare `orders` with the client's own counts
-- (NEW with orders sent and booked, FILLED with filled orders), and trade sides with G3h, which counts by trade_seq.
SELECT status, uniqExact(order_ref) AS orders, count() AS rows, count() - uniqExact(order_ref) AS repeated_rows
FROM orders GROUP BY status ORDER BY status FORMAT TSVWithNames;
