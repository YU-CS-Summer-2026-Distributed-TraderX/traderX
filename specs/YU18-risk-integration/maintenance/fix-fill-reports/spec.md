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
- FR-FFR-10: The tracked set SHALL hold at most `FIX_TRACKED_ORDERS` orders (FR-FFR-19; 65,536 until 2026-11-11). An
  order SHALL leave it on its FILLED,
  CANCELED or REJECTED update, or on an update whose epoch prefix differs from the one first seen for its ref.
  An accepted FIX cancel keeps it until the venue's CANCELED, which then ends it unreported (FR-FFR-17; removed at
  the cancel, that update waited as an untracked order's and aged out counted lost).
- FR-FFR-11: A fill published while no gateway consumes the feed (every replica down or restarting) is not
  reported unsolicited, and the gateway SHALL NOT report it later from a stale position. The client recovers it with `OrderStatusRequest (H)` or
  `OrderMassStatusRequest (AF)`. With persistent sessions (ADR-086) a fill that arrives while the session is
  logged on nowhere is stored under its lease and resent after the next Logon.
- FR-FFR-12: The Canceled ExecutionReport that answers an `OrderCancelRequest` SHALL carry the `CumQty` and
  `AvgPx` this gateway last reported for the order (tracked, or rebuilt from the session store under ADR-086),
  with `LeavesQty` 0. An order canceled after a partial fill SHALL NOT be reported `CumQty` 0
  ([ADR-088](../../system/adr-088-canceled-order-fill.md)).
- NFR-FFR-01: An update for an untracked order SHALL cost two byte scans and no JSON parse on the gateway, and a
  fill, cancel or reject one early-buffer entry (FR-FFR-15, FR-FFR-17; it allocated nothing in the overwritten ring that lost them). Every gateway with FIX on receives every order update on the venue; `FIX_FILL_FEED=0` is the
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
    With `FILLS_STREAMS` = S > 1 (FR-DRS-53, the production render) it reads `/fillsk.<k>.<GATEWAY_ID>` of
    `TRADERX_FILLS_<k>`, k = floorMod(GATEWAY_ID.hashCode(), S), the stream the router sends its fills to.
  - What reaches the subject is FR-DRS-48's: every update of an order whose booking event named this gateway, in
    the order's own order, batched per view batch. An order the read model never saw booked (evicted, or booked
    before the replica's first message after a restart with no snapshot of it) is not routed; the acceptor reports
    that as a gap as before (§7).
  - What the stream's retention buys (2026-11-10, the acceptance run: 2 GiB held ~24 s at 100K orders/s of FIX): the
    window the gateway's ordered consumer may fall behind by and still find its next message, a gateway's drain and
    in-place restart included; the production render sizes it from the rate (FR-DRS-48, `FILLS_WINDOW_MINUTES` 3). It
    does not buy a resume: the consumer starts from "new", and a replaced pod has a new name and so a new subject, so
    the fills of orders booked through the old pod go on to a subject no gateway reads (ADR-084 amendment's
    consequence), and the client recovers them with H/AF (FR-FFR-11). Whether a replaced gateway or a moved session
    should hear those fills unsolicited is open (ADR-084, "Open 2026-11-10").
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
- FR-FFR-17 (2026-11-10, the 450K acceptance run 2): An order the venue ends (CANCELED or REJECTED after acceptance:
  self-trade prevention, expiry, a trigger refusal) SHALL be reported to its FIX client as an unsolicited
  ExecutionReport, ExecType and OrdStatus Canceled (or Rejected), `LeavesQty` 0, the `CumQty` and `AvgPx` reported
  so far (ADR-088), `Text` the venue's reason when it gives one. Run 2's STP canceled 44,071,636 orders and the
  gateways reported none: only fills were handled for an untracked order, and a tracked one left the set unreported.
  - Such an update for an order not yet tracked SHALL wait in the early buffer as a fill does (FR-FFR-15), so a cancel
    that beats the pipelined answer is reported after the order's New; one dropped by age or count SHALL count as
    `outcome="lost"` on the routed feed, as a fill does (the ALERT names a fill or end).
  - A cancel the client asked for is answered by its own `OrderCancelRequest` (FR-FFR-12) and SHALL NOT be reported
    again. A refused or ambiguous client cancel SHALL leave the venue's end reported.
  - Sent on the session's report thread under the lease, as a fill is (FR-FFR-16, ADR-086 §3); counted in
    `traderx_fix_end_reports_total`.
  - `FixGatewayFillReportTest#aVenueCancelThatBeatsThePipelinedAnswerIsReportedAfterTheNew` (fails on the 316b269e
    acceptor), `#aRefusedClientCancelLeavesTheVenueCancelReported` (fails without clearing the request on refusal),
    `#aClientCancelIsReportedOnceWhenTheVenueUpdateFollows`.
- FR-FFR-18 (2026-11-10, the 450K acceptance run 3): A pipelined order SHALL be tracked when its ack reaches the
  gateway, not when its answer reaches the session's report thread. Run 3 lost 10,927,047 fills and ends (counted,
  ALERTed on all 18 gateways) once the rate passed ~150K orders/s: the store was saturated from the 0.44 step
  (every commit 128 bodies at ~290 ms), so each session's report thread sent at the store's pace, its acks waited
  behind it, and their updates aged out of the early buffer (30 s / 65,536).
  - The ack completes on one gateway thread (`fix-acks`), which tracks the order. Its updates SHALL wait on the order
    until its New is sent, then be applied in arrival order after it, so a fill or end never precedes its New
    (FR-FFR-15, FR-FFR-17).
  - Bound: what waits is the session's window (`MAX_IN_FLIGHT`, 1,024; an order holds its slot until its answer
    runs) times an order's few updates, with no time limit. The early buffer now covers only the ack's own hop.
  - An order evicted from the tracked set (FR-FFR-10) before its New was sent SHALL count its waiting updates as
    `outcome="lost"` on the routed feed.
  - `FixGatewayFillReportTest#aFillWhoseAnswerWaitsBehindTheSessionsReportsIsReported` (the report thread held, the
    early buffer overrun: fails on the ff684979 acceptor) and `#updatesForAnAnswerHeldPastTheEarlyAgeAreReported` (the
    age scaled to 100 ms, the answer held 10×: ff684979 loses the fill and the cancel).
- FR-FFR-19 (2026-11-11, the 450K acceptance run 4): A working order SHALL stay tracked until its end, through the
  routed feed's lag. Every order a gateway acks while its fills are late is working there, so the tracked bound is a
  time: 65,536 orders was ~4 s of one gateway at 300K orders/s. Run 4 lost 171,167 fills and ends by the end of the
  0.67 step, each burst within seconds of the read model's fills router (trade-processor-0, a NATS slow consumer from
  12:00:57 pm ET, out of memory at 12:05:34 pm ET) delivering late; with the feed on time the 0.67 step lost none.
  The fill found its order evicted while working, waited as an untracked one's and aged out counted lost.
  - Bound: `FIX_TRACKED_ORDERS`, default 524,288 (~215 B an order measured, ~113 MB of the gateway's 768 MB heap): ~30 s of
    feed lag at 300K orders/s, ~20 s at 450K. It guards the heap against a feed that stopped; it is not a window.
  - Past it the oldest working order SHALL be evicted, counted in `traderx_fix_tracked_evicted_total` and ALERTed once;
    its later fills and end age out of the early buffer as `outcome="lost"`.
  - Not the in-flight window or `fix-acks`: per-session in flight stayed under 100 and the store's commits under
    63 ms. A feed later than the bound, or one that drops ends, still loses reports; that is the router's to fix.
  - `FixGatewayFillReportTest#aRestingOrdersFillLateBehindTenSecondsOfAckedOrdersIsReported` (a resting order, then
    167,000 orders tracked, ten seconds of one 300K gateway, then its fill: fails with `FIX_TRACKED_ORDERS=65536`, the
    09fafeda bound, which evicts it) and `#aWorkingOrderEvictedPastTheTrackedBoundIsCounted`.
- FR-FFR-20 (2026-11-11, the 450K acceptance run 6): The booking event SHALL name the submitting gateway (FR-FFR-14)
  only for an order whose fills a gateway reports: not for a sequenced-stream order (ADR-090, the binary wire), whose
  client is answered by the ack alone.
  - Run 6 stage 2 (binary 345K/s + FIX 150K/s, one hour): a gateway's FIX and binary orders share its cluster session,
    so every binary order's updates were routed to its gateway (FR-DRS-48), waited in the FIX reporter's early buffer
    as an untracked order's (FR-FFR-15) and aged out: `outcome="lost"` 871,153,705, rising at the binary rate from the
    first second, while the FIX fills actually sent (455,435,633) equalled the clients' count. Stage 1 (FIX only) lost 0.
  - The same updates also filled the early buffer (65,536 orders) in well under a second per gateway, so a FIX fill that
    really beat its ack could be pushed out behind them; and they were the larger part of the routed streams' bytes.
  - The rule is in the engine's output, not the gateway: `event.streamSeq == 0` on the NEW. An unsequenced binary order
    (`--unsequenced`, pre-ADR-090 clients) is still named, routed and counted lost: the engine cannot tell it from a
    FIX order sent outside the session ring.
  - Not a decision on the apply path: the name is only a field of the bridged booking event. Members and gateways still
    roll together (the stage 2 gate `lost = 0` needs both).
  - Test: `ExactlyOnceStreamTest#aSequencedOrdersBookingDoesNotNameTheGateway` (fails on the unconditioned rule).
  - Kind (2026-11-12 12:01-12:10 am ET, `scripts/proofs/kind-ffr20-binary-fills.sh`, 2 gateways, one fills stream, no
    cluster secret): 508 sequenced binary orders filled (members' trades +508) with the router's routed count and both
    gateways' `outcome="lost"` at 0 before and after the age-out; the control, 852 unsequenced orders, routed 2 events
    an order and counted 424 and 428 lost. `yu18-fix-fill-feed.sh` (routed FIX fills) still passes. A second kind
    run (6 gateways, 8 claimed streams, the cluster secret set) passed the same on shards.
  - GKE run 6b (3c5b3728, us-east4-a, 2026-11-12 1:37-2:19 am ET; stage 1, then 5 minutes of binary 345K/s + FIX 150K/s): 90,599,616 binary orders accepted through the 18 gateways with
    `outcome="lost"` 0, evicted 0 and tracked evictions 0 on every gateway, where run 6 counted 871M; FIX filled
    38,603,504 = the clients' count, G3 and G3h ok.
  - Found by that run and closed by FR-FFR-21: a REST order through a gateway is also named and unsequenced, so its
    updates were routed and each aged out as lost (2 per REST cross on kind).
- FR-FFR-21 (2026-11-12): The gateway SHALL pass over the routed updates of an order it took over REST instead of
  counting them lost.
  - The engine cannot tell a REST order from a FIX order sent outside the session ring, so the rule is the gateway's:
    the REST handler tells the FIX reporter the order's ref at its answer (`notFix`), which drops any update that beat
    the answer and ignores the later ones.
  - Bound: the newest 65,536 REST orders per gateway. An older one still resting when it fills is counted lost again;
    REST is the console's and operators' path, not a load path.
  - Still named and still routed: the bytes reach the gateway and are dropped there. An unsequenced binary order
    (FR-FFR-20) is not covered.
  - Test: `FixGatewayFillReportTest#aRestOrdersRoutedUpdatesAreNotCountedLost` (three updates of two REST orders, one
    before its answer, are not counted; an undeclared order's fill is). Not exercised on a rig.
- FR-FFR-22 (2026-11-12, bridge-overflow GKE stage 3): Taking a session's lease SHALL NOT wait on the feed lock.
  Reading the stored log on adoption (ADR-086 §3) SHALL NOT take the feed lock on the store's watch thread. The
  orders the log shows ended SHALL leave the tracked set after the scan, on the caller's thread.
  - Why. A fill or end for a session that is live nowhere takes its lease under the feed lock (§3, FR-FFR-17). That
    happens on the feed thread, or on a report thread through FR-FFR-18's held updates. The caller then waits for the
    log scan. The scan's callback runs on the store's watch thread and took the feed lock at the log's first ended
    order. So the caller and the watch thread waited on each other until the scan's 30 s timeout. The lease was
    then let go, and the update was dropped without being counted. The next update for that session did the same
    again. Meanwhile no ack was tracked and no report was sent on that gateway. Logons on it timed out too, because
    their scans also need the lock.
  - What the run showed (stage 3: nats-0 write-throttled, the leader killed 60 s in): 24,312 fills and ends were
    counted `outcome="lost"`. The scan timed out under the feed lock 600 times across 18 gateways (341 on the feed
    thread, 259 on report threads). That kept going at 01:10–01:15 UTC, after the throttle was lifted and the load
    had ended. So the cause was the lock, not the disk. On the gateway that lost 15,104, the timeouts came every
    30 s from 01:02:41 UTC. Its fill reports had stopped by the 01:02:44 sample, and its lost count rose from 12 to 15,104 in the
    first 30 s sample after its second timeout. Every gateway that lost more than 1,800 had such timeouts.
  - What was not separated: stage 2 (throttle, no kill) froze in the same way (407 timeouts under the lock) and
    counted only 6 lost. A freeze ages out whatever is waiting in the early buffer (FR-FFR-15). How much was waiting
    was not recorded. After the kill, acks were late (orders stranded by the leader change were resubmitted), and
    that is the likely difference. It is not measured.
  - Not changed: the scan still runs on the caller's thread while it holds the feed lock. A slow store still holds
    the lock for as long as the scan takes, up to 30 s. A fill for a session whose lease is held by another gateway
    is still not reported here. With routed fills that other gateway never receives it (FR-FFR-14, ADR-084 "Open").
  - Tests: `FixSessionPersistenceIT#aFillForASessionLiveNowhereIsStoredAtOnceWhenItsLogHoldsAnEndedOrder`
    (`fillsReachTheSessionWhereverItIsOrIsNot` with an ended order in the log). It fails on the d3a022de acceptor:
    the feed held the lock for 30,066 ms and the fill was never stored. With the fix it passes in 4.6 s.
    `#anOrderEndedOnAnotherReplicaIsNotReportedAgainWhenTheSessionComesBack` pins the moved removal. It fails when the
    removal is deleted (A sends s1's Canceled a second time) and passes on the d3a022de acceptor too. No earlier test
    noticed that removal: it could be deleted with every test still passing. Not exercised on a rig.

