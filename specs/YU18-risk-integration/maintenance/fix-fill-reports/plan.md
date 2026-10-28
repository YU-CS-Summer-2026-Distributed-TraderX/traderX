# FIX fill reports plan

The cluster egress cannot carry a fill report: its 32-byte record has no fill quantity or price, and every
output of an apply goes to the aggressor's cluster session. The leader's `/orders` bridge already carries
each order's full state, so the gateway reads it, keeps `orderRef → (session, ClOrdID, symbol, side)` for
its own FIX orders, and turns cumulative growth into Trade reports. The stream, not a core subscription,
makes a missed message recoverable; the per-order `lastFillQuantity` check makes an unrecoverable one loud.
ADR-084 records why an egress wire change was not taken.
