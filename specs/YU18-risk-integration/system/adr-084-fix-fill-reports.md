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

1. **The gateway reads `/orders` from the durable stream.** This is the leader's order bridge (YU13
   brief 07), the feed the order read model already consumes. Each message carries the order's
   quantity, remaining quantity, status, last fill quantity and last execution price. The gateway reads
   it from the `TRADERX_FEED` capture stream through two JetStream ordered consumers, one for `/orders`
   and one for `/orders.*`, so every `BRIDGE_PARTITIONS` layout is covered without knowing the count.
   They start at "new" when the gateway starts. An ordered consumer delivers in stream order and is
   recreated at the last delivered sequence + 1 after any gap, missed heartbeat or lost consumer, so a
   message this gateway misses (a slow consumer, a dropped connection, a deleted consumer) is re-read
   from the stream, not lost. It uses the URL the control-feed subscriber uses (`NATS_ADDRESS`).
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
7. **Gaps that the stream cannot cover are ALERTed.** Every update names its own fill
   (`lastFillQuantity`). When an update adds more cumulative quantity than that, updates for the order
   were lost before this gateway saw them: upstream of the stream (a bridge drop, which ADR-083 already
   counts and alarms), or on a core subscription. The acceptor logs `ALERT FIX fill gap on ord-<ref>`,
   counts it in `/metrics` as `traderx_fix_fill_reports_total{outcome="gap"}` beside
   `{outcome="sent"}`, and still reports the engine's `CumQty`. A lost *final* fill has no later update
   to show it; on the stream that loss can only be an ingest drop, which ADR-083 alarms.
8. **`FIX_FILL_FEED` names the mode, and production names the stream.**
   - `jetstream`: the ordered consumers above. The gateway waits for the stream to exist rather than
     fall back. `scripts/gcp-compsci/prepare.py` sets it on the CompSci gateway and refuses any other
     value.
   - `core`: a plain subscription, for a tier with no stream. A missed message is gone; decision 7 is
     the only signal.
   - unset or `auto`: the stream when one captures `/orders`, else core with a startup WARN.
   - `0`: off, for a throughput rig that cannot afford the fan-out.

## Consequences

- No engine, wire, snapshot or consensus change. A gateway rolls on its own.
- **What a FIX client gets across a gateway restart or a replica change.**
  - With in-memory sessions (TD-AC01) the session, its sequence numbers and the order map go with the
    process. A fill after that is **not re-sent**; the client recovers with `OrderStatusRequest (H)` or
    `OrderMassStatusRequest (AF)`, which read the order read model.
  - With `FIX_SESSION_STORE=jetstream` (ADR-086, the GKE gateway) a fill that arrives while the session
    is logged on nowhere is stored under its lease and resent after the next Logon, on any replica.
  - Either way, a fill published while **no** gateway is consuming the feed (every replica down or
    restarting) is not reported unsolicited: a restarted gateway's consumers start at "new", and the
    fill is in no gateway's early ring. `H`/`AF` answer it. ADR-086 §3 states the same bound.
- Within one gateway's life no fill is lost to this gateway: the stream re-delivers what it missed.
  What can still be lost is lost upstream of the stream (a bridge ingest drop), and it is alarmed
  twice, by the bridge (ADR-083) and, when a later fill shows it, by decision 7.
- Unsolicited cancels (an STP cancel of a resting order) are still not reported; such an entry just
  expires.
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
- `FixFillFeedIT` (real JetStream server): both subscriber modes reach NATS through a TCP proxy that
  the test cuts mid-cross. Two updates are published during the cut, then the connection is
  restored. The `jetstream` mode delivers all four updates once each, in order; `core` keeps the two
  it saw. With the ordered consumer swapped for a core subscription under `jetstream`, the test fails
  at "the stream lost the fills". `jetstream` refuses to start before the stream exists. 5 of 5 repeat
  runs pass. The gap ALERT test reports a fill whose partial was never received as one fill of 10
  (CumQty 10) and counts one gap; with the counter removed it fails.
- Live, `traderx-fixf`, `FIX_FILL_FEED=jetstream` on both gateway replicas: `yu18-fix-session` step 4
  passes again. Mid-cross, after a resting Buy 10 had been reported PARTIALLY_FILLED 4, all four
  gateway consumers on `TRADERX_FEED` were deleted (the projection's `tp-*` consumers were kept).
  The 6 that filled it was published while they were gone. The client still got FILLED, LastQty 6,
  CumQty 10, exactly once. The consumers were recreated within seconds of the delete, and
  `traderx_fix_fill_reports_total{outcome="gap"}` stayed 0 on both gateways.
