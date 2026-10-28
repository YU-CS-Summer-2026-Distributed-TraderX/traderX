# FIX fill reports specification

Status: implemented (`abc57a39`, `0326f733`), unit-, integration- and live-verified on `traderx-fixf`; not
deployed to `traderx`. Scope: the cluster gateway's FIX acceptor (`FixGatewayAcceptor`) and its `/orders`
subscription. No engine, wire, snapshot or consensus change.

- FR-FFR-01: Every NewOrderSingle the cluster accepts SHALL be answered first from its direct ack, exactly as
  before (New, Rejected, Canceled or Filled), and no fill report for that order SHALL be sent before that
  answer.
- FR-FFR-02: Every later fill of an order a FIX session placed SHALL reach that session as one unsolicited
  ExecutionReport with `ExecType=F`, `OrdStatus` 1 (PARTIALLY_FILLED) or 2 (FILLED), `LastQty`, `LastPx`,
  `CumQty`, `LeavesQty`, `AvgPx`, `OrderID ord-<orderRef>`, the order's current `ClOrdID` (the newest after
  a replace), `Symbol`, `Side` and `OrderQty`. This includes the aggressor's own fills in the apply that
  accepted it and every fill of a resting order hit later.
- FR-FFR-03: `CumQty` SHALL equal the engine's cumulative fill (`quantity − remainingQuantity` of the
  order's update). `LastQty` SHALL be the increase over the `CumQty` last reported to the session, `LastPx`
  the update's `lastExecutionPrice`, and `AvgPx` the quantity-weighted mean of the reported `LastPx`.
- FR-FFR-04: An update that adds no cumulative quantity (NEW, a redelivery, an older state) SHALL send
  nothing. An update for an order no session on this gateway placed SHALL send nothing.
- FR-FFR-05: The owning session SHALL get the fill whichever gateway replica the counterparty order came
  through: every gateway reads every order update and reports only the orders it tracks.
- FR-FFR-06: A fill update that arrives before the order's direct ack SHALL still be reported, after the
  answer of FR-FFR-01. The gateway keeps the last 4,096 fill updates for untracked orders and replays those
  for an order when it starts tracking it.
- FR-FFR-07: With `FIX_FILL_FEED=jetstream` the gateway SHALL read `/orders` and `/orders.*` from the
  `TRADERX_FEED` stream through JetStream ordered consumers starting at "new". A message the gateway misses
  while running (a slow consumer, a dropped connection, a deleted consumer) SHALL be re-read from the stream
  and reported once. The gateway SHALL NOT start the feed until the stream captures `/orders`, and SHALL NOT
  fall back to a core subscription.
- FR-FFR-08: When an update adds more cumulative quantity than its own `lastFillQuantity`, the gateway SHALL
  log `ALERT FIX fill gap on ord-<orderRef>`, count it in `/metrics`
  `traderx_fix_fill_reports_total{outcome="gap"}`, and still report the engine's `CumQty` per FR-FFR-03.
  Every report sent SHALL count in `traderx_fix_fill_reports_total{outcome="sent"}`.
- FR-FFR-09: `FIX_FILL_FEED` SHALL accept `jetstream`, `core` (a plain subscription, with a startup WARN that
  a missed message loses a report), `auto` or unset (the stream when one captures `/orders`, else core with
  the WARN) and `0` (off, logged at startup). `scripts/gcp-compsci/prepare.py` SHALL set `jetstream` on the
  CompSci gateway and refuse a render with any other value.
- FR-FFR-10: The tracked set SHALL hold at most 65,536 orders. An order SHALL leave it on its FILLED,
  CANCELED or REJECTED update, on an accepted FIX cancel, or on an update whose epoch prefix differs from the
  one first seen for its ref.
- FR-FFR-11: A fill published while no gateway consumes the feed (every replica down or restarting) is not
  reported unsolicited, and the gateway SHALL NOT report it later from a stale position. The client recovers it with `OrderStatusRequest (H)` or
  `OrderMassStatusRequest (AF)`. With persistent sessions (ADR-086) a fill that arrives while the session is
  logged on nowhere is stored under its lease and resent after the next Logon.
- NFR-FFR-01: An update for an untracked order SHALL cost two byte scans and no JSON parse or allocation on
  the gateway. Every gateway with FIX on receives every order update on the venue; `FIX_FILL_FEED=0` is the
  opt-out for a throughput rig.

## Acceptance

| Case | FR | Test | Expected |
| --- | --- | --- | --- |
| two sessions cross 5 @ 50 | 01, 02, 03, 05 | `FixGatewayFillReportTest.bothSidesOfACross…` | both get New, then `F` / 2, LastQty 5, LastPx 50, CumQty 5; each report reaches its own session once |
| 10-lot filled 4 @ 50 then 6 @ 52, partial redelivered, a stranger's fill | 02, 03, 04, 08 | `…aPartialFill…` | `F` / 1 LastQty 4 leaves 6, then `F` / 2 LastQty 6 CumQty 10 AvgPx 51.2; nothing else; gap 0 |
| the fill arrives before the ack | 01, 06 | `…aFillPublishedBeforeTheAck…` | New, then the fill |
| the partial never reaches the gateway | 03, 08 | `…aFillThisGatewayNeverReceived…` | one report LastQty 10, CumQty 10; gap counter 1 |
| connection cut mid-cross, 2 updates published during the cut | 07, 09 | `FixFillFeedIT` | `jetstream` delivers all 4 once, in order; `core` keeps 2; `jetstream` refuses to start before the stream exists |
| CompSci render | 09 | `test_prepare.py` | gateway carries `FIX_FILL_FEED=jetstream`; `0`, `core`, `auto` are refused |
| live cross on `traderx-fixf` | 02, 05 | `yu18-fix-session.sh` step 4, two-replica cross | Filled at the resting price on both sides, across replicas; partial then filled exact |
| live: all gateway consumers deleted mid-cross | 07, 08 | live drop on `traderx-fixf` | FILLED LastQty 6 CumQty 10 once; consumers recreated in seconds; gap 0 |

Without the fix: `FIX_FILL_FEED=0` reproduces suspect 8 live (New only, while members and SQL book the
cross). With `onOrderUpdate` disabled the three report tests fail. With a core subscription under
`jetstream`, `FixFillFeedIT` fails at "the stream lost the fills". With the gap counter removed, the gap test
fails.
