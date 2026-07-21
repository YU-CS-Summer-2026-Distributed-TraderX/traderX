# Functional Delta: YU13-limit-order-book

Parent: `YU12-aeron-cluster`

| ID | Delta |
|---|---|
| FD-LOB01 | Replace the price-triggered auto-fill matching policy with a genuine crossing limit-order book: each security carries a two-sided book of resting orders and an accepted non-marketable limit order rests at its price level's FIFO tail. |
| FD-LOB02 | Cross a marketable order against resting opposite-side orders best-price-first and FIFO within a level, filling min(aggressor remaining, resting remaining) at the resting order's limit price. |
| FD-LOB03 | Emit an order update, a booked trade with its own trade sequence, and a position update for BOTH sides of every match; flag the resting side's update so gateway ack correlation can distinguish it. |
| FD-LOB04 | Execute market orders (no limit price) immediately against available depth and cancel any unfilled remainder in place; validate them at the last trade price, falling back to the opposite best, rejecting PRICE_MISSING when neither exists. |
| FD-LOB05 | Stop triggering fills on price ticks; a tick feeds risk freshness and seeds a security's mark only until its book first trades, after which the last trade price is the mark. |
| FD-LOB06 | Represent each security's book as array-indexed price levels on a fixed 0.001 grid inside a banded window with intrusive FIFO queues of pooled orders, giving O(1) add/cancel/match and zero-allocation steady state. |
| FD-LOB07 | Admit limit prices only on the grid (reject INVALID) and inside the band (reject PRICE_COLLAR), both before any risk reservation. |
| FD-LOB08 | Extend the cluster snapshot to format 2: header carries book geometry, each created book's band anchor precedes its order rows, and open rows in ascending-reference order rebuild each level's exact FIFO; fail closed on an off-grid or out-of-band restored row and on a legacy (format 1) snapshot. |
| FD-LOB09 | Carry a resting-update class byte on every egress ack; count only direct acks in gateway offer/ack and pipelined-batch accounting, and count both sides of a cross in the booked-fill metric. |
| FD-LOB10 | Unlink an open resting order from its price level in O(1) on cancel; force-fill an open order by unlinking it and executing the full remainder at the last trade price (falling back to its limit when no price has printed). |
