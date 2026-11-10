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
- FR-FFR-12: The Canceled ExecutionReport that answers an `OrderCancelRequest` SHALL carry the `CumQty` and
  `AvgPx` this gateway last reported for the order (tracked, or rebuilt from the session store under ADR-086),
  with `LeavesQty` 0. An order canceled after a partial fill SHALL NOT be reported `CumQty` 0
  ([ADR-088](../../system/adr-088-canceled-order-fill.md)).
- NFR-FFR-01: An update for an untracked order SHALL cost two byte scans and no JSON parse on the gateway, and a
  fill one early-buffer entry (FR-FFR-15; it allocated nothing in the overwritten ring that lost them). Every gateway with FIX on receives every order update on the venue; `FIX_FILL_FEED=0` is the
  opt-out for a throughput rig.

- FR-FFR-13: The fill feed SHALL read every capture stream of the base name (`PROJECTION_FEED_STREAM`): the one
  stream, or one stream per partition, `<base>_P<p>` (ADR-097). Each order subject of each stream gets its own
  ordered consumer bound to that stream, so each partition's fills arrive in order, once each. `jetstream` mode
  SHALL refuse while no such stream captures an order subject. `FixFillFeedIT.perPartitionStreamsAreEachReadThroughTheirOwnOrderedConsumer`.
- FR-FFR-14 (ADR-084 amendment, 2026-11-10): Fills SHALL be routed to the gateway that owns the order, so no gateway
  reads the whole order feed. Every gateway reading every order event was 24 × ~400 MB/s at 450K orders/s (the
  2026-11-10 campaign's count), and a per-account subject cannot cut it: a batched bridge message (FR-DRS-41) has one
  subject and many accounts. Instead:
  - The gateway SHALL name itself to the cluster: `GATEWAY_ID` (the production render: its pod name) travels after
    the shared secret in the connect credentials (`secret '\n' name`, `ClusterClientAuth`), and the member SHALL
    make the name the session's encoded principal; the secret alone (risk-extract, the feed adapter, a proof client)
    SHALL admit as before with no principal. A name that is not a subject token (`[A-Za-z0-9._-]{1,63}`) SHALL be
    refused at the client. An open rig (`CLUSTER_CLIENT_SECRET` unset under `ALLOW_DEV_SECRETS`, the kind rig) SHALL
    still name its gateways' sessions: routing does not depend on the ingress being closed (found on the first kind
    run, 2026-11-10: with no authenticator installed no session had a principal, and `TRADERX_FILLS` stayed empty).
  - The engine SHALL stamp the principal on a NEW order's direct booking output only (`OutputEvent.gateway`, read
    from the input's session at drain time, the same on every member and on replay; never engine state, never on a
    resting update, a cancel or a replace), and the order bridge SHALL carry it as `gateway` on that event only.
  - With `FIX_FILL_FEED=routed` the gateway SHALL read one ordered consumer on `/fills.<GATEWAY_ID>` of the read
    model's `TRADERX_FILLS` stream (`FILLS_STREAM`), from "new", through the same `onOrderUpdate` path and the same
    record splitting as the capture (FR-DRS-41), and SHALL refuse to start the feed without `GATEWAY_ID`; until the
    stream exists it retries as `jetstream` mode does (FR-FGM-05). `jetstream` and `core` modes are unchanged.
  - What reaches the subject is FR-DRS-48's: every update of an order whose booking event named this gateway, in
    the order's own order, batched per view batch. An order the read model never saw booked (evicted, or booked
    before the replica's first message after a restart with no snapshot of it) is not routed; the acceptor reports
    that as a gap as before (§7).
  Covered by `ClusterClientAuthTest` (a named gateway admitted with its name as the principal; the secret alone
  with none; a wrong secret rejected whatever the name; a non-token name refused), `OrderNatsPublisherGatewayTest`
  (the booking event carries `gateway`, other events do not), `FixFillFeedIT.routedModeReadsOnlyItsOwnSubject`,
  `test_prepare.RoutedFills` (the render names the gateway from its pod and sets `routed`; a routed feed without the
  name or without the router is refused), and the kind proof `scripts/proofs/yu18-fix-fill-feed.sh (routed)` (a cross
  between sessions on two gateways: each side's fill report at its own gateway, neither gateway on the capture).

## Acceptance

| Case | FR | Test | Expected |
| --- | --- | --- | --- |
| fills routed to the owning gateway | 14 | `ClusterClientAuthTest`, `OrderNatsPublisherGatewayTest`, `FixFillFeedIT.routedModeReadsOnlyItsOwnSubject`, `test_prepare.RoutedFills`, `yu18-fix-fill-feed.sh` (routed) | the named session's principal; `gateway` on the booking event only; a routed gateway receives its own subject's events and none of another's; the render names the gateway and refuses a nameless routed feed; on kind each side of a cross across two gateways is reported at its own gateway; LIVE on kind traderx-ffr14 2026-11-10 2:28 pm ET: `yu18-fix-fill-feed.sh` routed, the FIX session on one gateway and the aggressor through the other, PASS (COVERAGE "Fills routed to the owning gateway on kind") |
| two sessions cross 5 @ 50 | 01, 02, 03, 05 | `FixGatewayFillReportTest.bothSidesOfACross…` | both get New, then `F` / 2, LastQty 5, LastPx 50, CumQty 5; each report reaches its own session once |
| 10-lot filled 4 @ 50 then 6 @ 52, partial redelivered, a stranger's fill | 02, 03, 04, 08 | `…aPartialFill…` | `F` / 1 LastQty 4 leaves 6, then `F` / 2 LastQty 6 CumQty 10 AvgPx 51.2; nothing else; gap 0 |
| the fill arrives before the ack | 01, 06 | `…aFillPublishedBeforeTheAck…` | New, then the fill |
| the partial never reaches the gateway | 03, 08 | `…aFillThisGatewayNeverReceived…` | one report LastQty 10, CumQty 10; gap counter 1 |
| 10-lot filled 4 @ 50, then canceled | 12 | `…aCancelAfterAPartialFillReportsWhatWasFilled` | Canceled, CumQty 4, LeavesQty 0, AvgPx 50 (was CumQty 0) |
| connection cut mid-cross, 2 updates published during the cut | 07, 09 | `FixFillFeedIT` | `jetstream` delivers all 4 once, in order; `core` keeps 2; `jetstream` refuses to start before the stream exists |
| CompSci render | 09 | `test_prepare.py` | gateway carries `FIX_FILL_FEED=jetstream`; `0`, `core`, `auto` are refused |
| live cross on `traderx-fixf` | 02, 05 | `yu18-fix-session.sh` step 4, two-replica cross | Filled at the resting price on both sides, across replicas; partial then filled exact |
| live: all gateway consumers deleted mid-cross | 07, 08 | live drop on `traderx-fixf` | FILLED LastQty 6 CumQty 10 once; consumers recreated in seconds; gap 0 |

Without the fix: `FIX_FILL_FEED=0` reproduces suspect 8 live (New only, while members and SQL book the
cross). With `onOrderUpdate` disabled the three report tests fail. With a core subscription under
`jetstream`, `FixFillFeedIT` fails at "the stream lost the fills". With the gap counter removed, the gap test
fails.
- FR-FFR-15 (2026-11-10, the 450K acceptance run): A fill that reaches the gateway before its order is tracked SHALL
  be reported once the order is. Under FR-BSP-02 an order is tracked when its pipelined answer runs, seconds after its
  fill when a session is behind, so the 4,096-slot early ring overwritten in arrival order reported 4,501,180 of
  33,310,198 fills (13.5%) at 85K orders/s, and counted none lost.
  - Early fills SHALL wait by order ref, in arrival order, at most `FIX_EARLY_FILLS` (default 65,536) refs and
    30 s each (past the ack deadline). Tracking an order SHALL take its ref's fills in one lookup, oldest first.
  - An order answered terminal by its own ack (filled, or canceled) is never tracked: its early fills SHALL be
    dropped then, not left to age.
  - A fill dropped by age or count SHALL be counted: `traderx_fix_fill_reports_total{outcome="lost"}` with
    `FIX_FILL_FEED=routed` (FR-FFR-14: every fill on that feed is one of this gateway's orders), with one
    `ALERT FIX fill for ord-<ref> not reported` per gateway; `outcome="evicted"` on a venue-wide feed, where most
    early fills are other gateways' orders.
  - `FixGatewayFillReportTest.fillsThatBeatTheirPipelinedAnswersAreReportedWhateverArrivesMeanwhile` (the pre-fix
    acceptor: 0 of 100 reported) and `#aFillLeftUnreportedOnTheRoutedFeedIsCounted` (pre-fix: 0 of 1,000 counted).
- FR-FFR-16: A fill report SHALL be built under the feed lock and sent on its session's report thread (FR-BSP-02),
  after that session's earlier reports: an order's New is sent there before the order is tracked, so its fills follow
  it. Sent under the lock, one feed thread carried every fill of the gateway through QuickFIX/J and the session store
  one at a time and held every session's tracking behind it. `FixGatewayThroughputIT` with `FIX_GW_BENCH_FILLS=1`
  (32 sessions × 1,024, every order filled, 1 ms venue, one NATS): 4,327–4,658 fill reports/s against 130–136K answers/s
  before; 73,184–82,735/s, every fill, after (the pre-FR-FFR-15 acceptor: 617/s, the rest lost).

