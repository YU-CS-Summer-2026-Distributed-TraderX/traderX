# ADR-084: FIX fill reports come from the leader's order feed, not the cluster egress

Status: accepted (yaakov, 2026-10-27). Requirement: YU10 FR-FIX05 on the YU12+ gateway tier
(ADR-047). Found by the spec-driven proof audit, round 2, suspect 8 (`scripts/proofs/COVERAGE.md`).

## Context

`FixGatewayAcceptor` answered each NewOrderSingle with exactly one ExecutionReport, built from that
order's direct ack. The engine's direct ack for a new order is ACCEPTED, even when the order crosses in
the same apply. Its fills, and every fill of a resting order hit later, reached the FIX session as
nothing. On GKE a resting FIX Sell and a crossing FIX Buy both got `ExecType=0 New` and never a fill,
while SQL showed both orders FILLED.

The cluster egress cannot carry these reports, for two reasons:

1. **No fill data.** The 32-byte egress record holds the applied sequence, the orderRef, the kind, the
   trade sequence and three class bytes. It has no fill quantity and no price. A PARTIALLY_FILLED
   record cannot say how much filled or at what price.
2. **Wrong recipient.** `drainOutputs(session)` sends every output of an apply to the cluster session
   that sent the input, which is the aggressor's gateway. A resting order owned by a FIX session on
   another gateway is never on its own gateway's egress at all.

Adding either to the egress would be a wire change for every member and gateway.

## Decision

1. **The gateway subscribes to `/orders`.** This is the leader's order bridge (YU13 brief 07), the
   feed the order read model already consumes. Each message carries the order's quantity, remaining
   quantity, status, last fill quantity and last execution price. The subscription is core NATS, to
   `/orders` and `/orders.*`, so it covers every `BRIDGE_PARTITIONS` layout without knowing the count.
   It uses the URL the control-feed subscriber uses (`NATS_ADDRESS`).
2. **Every gateway sees every update and acts only on its own orders.** The acceptor keeps
   `orderRef → (session, ClOrdID, symbol, side)` for each FIX order it accepted. An update for any
   other ref costs two byte scans and no parse. So the owning session hears a fill whichever gateway
   the aggressor came through.
3. **What is reported.** For an update with status PARTIALLY_FILLED or FILLED whose cumulative quantity
   (`quantity − remainingQuantity`) exceeds what the session has been told, the acceptor sends one
   unsolicited ExecutionReport:
   - `ExecType=F` (Trade); `OrdStatus` 1 (PARTIALLY_FILLED) or 2 (FILLED);
   - `LastQty` = the cumulative increase, and `LastPx` = `lastExecutionPrice`;
   - `CumQty`, and `LeavesQty` (0 on FILLED);
   - `AvgPx` = the quantity-weighted mean of the reported `LastPx`;
   - `OrderID ord-<orderRef>`, the order's current `ClOrdID` (the latest after a replace), `Symbol`,
     `Side` and `OrderQty`.
   A NEW update, a redelivery and a stale state add nothing and send nothing. A missed message folds
   into the next one's `LastQty`, so `CumQty` always equals the engine's.
4. **The ack's own report is unchanged.** The New, Rejected, Canceled or Filled answer to the order
   still comes from its direct ack, and it is always sent before any fill report for that order.
5. **The feed can beat the ack.** Both leave the leader in one apply, and the ack still has to
   complete a future before the order is tracked. Fill updates for untracked refs are kept in a ring of
   the venue's last 4,096. Tracking an order replays any for its ref.
6. **Bounded and expiring.** The map holds at most 65,536 orders. An entry leaves on its FILLED,
   CANCELED or REJECTED update, or on an accepted FIX cancel. An update whose epoch prefix differs from
   the one first seen for that ref drops the entry, because a new epoch reuses refs.
7. **On by default wherever FIX is.** `FIX_FILL_FEED=0` turns it off for a throughput rig that cannot
   afford the fan-out. `scripts/gcp-compsci/prepare.py` refuses a CompSci render that sets it.

## Consequences

- No engine, wire, snapshot or consensus change. A gateway rolls on its own.
- Fill reports are as durable as the FIX session: ephemeral (TD-AC01). A counterparty that reconnects
  to another gateway, or after a gateway restart, gets no fill report for an order placed before;
  `OrderStatusRequest (H)` answers it. Unsolicited cancels (an STP cancel of a resting order) are
  still not reported; such an entry just expires.
- The feed is best effort, like the egress. A bridge drop delays a report to the order's next update,
  and the last fill can be lost. The bridge's own drop and 429 counters (ADR-083) say when.
- Every gateway with FIX on receives every order update on the venue. At 400K/s that is NATS fan-out
  times the number of gateways.

## Evidence

- `FixGatewayFillReportTest` (two real QuickFIX/J sessions, feed messages from the production
  `OrderNatsPublisher` encoder): both sides of a cross get `ExecType=F OrdStatus=2` at the resting
  price; a 10-lot gets PARTIALLY_FILLED 4 (leaves 6) then FILLED 6 (cum 10, AvgPx 51.2 over two
  prices); a redelivered partial and another gateway's fill send nothing; a fill that beats the ack is
  reported after the New. With `onOrderUpdate` disabled all three fail.
- Live, 2026-10-27, side namespace `traderx-fixf` (production images, this gateway build),
  `yu18-fix-session` step 4: the aggressing Buy got Filled `LastPx=50 CumQty=5` and the resting Sell
  got its fill report. With `FIX_FILL_FEED=0` the same run reproduced suspect 8 exactly (New only on
  both sides, while every member and SQL booked the cross).
- Live, two gateway replicas: a resting Sell entered through gateway A and crossed through gateway B
  got Filled 5 @ 50 on A's session; a resting Buy 10 got PARTIALLY_FILLED 4 (leaves 6) then FILLED 6
  (cum 10); no report reached the other session.
