# Exactly-once client specification

Status: FR-XOC-01, 02, 04, 05 describe the venue as it is; FR-XOC-03 and FR-XOC-06 are new; FR-XOC-07 (2026-11-12) bounds
the read-model reads FR-XOC-03 and the status requests make. Scope: the order-entry contract a
client relies on to resend after a gateway kill or a leader kill without losing or duplicating an order.
Decision: [ADR-091](../../system/adr-091-exactly-once-client.md).

## Requirements

- FR-XOC-01: The idempotency key SHALL be the binary NEW's `clOrdId` (u64; 0 = none), FIX's
  `TargetCompID + SOH + ClOrdID`, and REST's `clientOrderId` (hashed; empty = none). A NEW whose key the engine
  already ACCEPTED SHALL NOT execute again, on any gateway replica, before or after a leader election. It SHALL be
  answered with the original order: its current state and `orderRef` while the order is open or in terminal
  retention, and `REJECTED` with reason `DUPLICATE` naming the original `orderRef` once it has left retention
  (FR-IMRG14). A key the engine never accepted SHALL be decided as new.
- FR-XOC-02: Answering a retry SHALL change nothing: one output, the original's ref and state, `publishNats=false`,
  no `TRADE_BOOKED`, no reservation. The order read model, the trade bridge and a strict replay of the log SHALL
  each count the order once.
- FR-XOC-03: The FIX acceptor SHALL answer a `NewOrderSingle` that the engine names `DUPLICATE` of `ord-<ref>` with
  an ExecutionReport `ExecType=I` (Order Status), `OrderID ord-<ref>`, the request's `ClOrdID` and `Text`
  `DUPLICATE: ClOrdID already executed as ord-<ref>`. Its state SHALL come from the read model when
  `ORDER_READMODEL_URL` is configured, by the order's client key (FR-XOC-07), else `OrdStatus=3` (done: the order is
  terminal, its detail aged out). It
  SHALL NOT answer `OrdStatus=8` or `OrderID NONE`. The `ClOrdID → ord-<ref>` mapping SHALL be kept, so an
  `OrderCancelRequest` by `OrigClOrdID` resolves.
- FR-XOC-04: The engine SHALL dedupe the last `IDEMPOTENCY_CAPACITY` (262,144) accepted keys. A retry that reaches
  the engine after more distinct keys than that since its original is decided as new. A client resending across
  an outage of T seconds at R orders/s therefore needs R × T well below 262,144 (about 0.65 s at 400K/s, 52 s at
  5K/s); past it a resend can double-book, which a strict replay reports as an unreceipted output. Exactly-once
  at 400K/s is the proposed [ADR-090](../../system/adr-090-exactly-once-dedupe.md) (per-stream sequences, FR-XOD).
- FR-XOC-05: The binary acceptor's ack status 2 (ambiguous) SHALL mean no committed decision reached the gateway:
  the order may or may not have executed, and a resend with the same `clOrdId` is safe (FR-XOC-01). Status 0 with
  reason `DUPLICATE` and a ref > 0 SHALL be read as the receipt for that ref.
- FR-XOC-06: A FIX `NewOrderSingle` whose submit comes back ambiguous after the gateway saw a leader change while it
  waited SHALL be resubmitted by that gateway under the same key (and, on an ADR-090 stream, at its stored sequence),
  at most twice, and answered by the result: booked once if the old leader lost it, restated (`150=I`) if it had
  committed. An ambiguous answer with no leader change in between SHALL NOT be resubmitted, since a plain timeout
  can outlast the FR-XOC-04 window. Production 2026-11-06 (e1106, leader kill under CLIENT2-8): 7 such orders were
  never answered, because FIX carries no ambiguous answer for a client to resend on and the sessions stayed up.
- FR-XOC-07 (2026-11-12): The gateway SHALL NOT read an account's whole order list to answer for one order.
  - A restatement (FR-XOC-03) and an `OrderStatusRequest` SHALL read the one order booked under the request's client
    key (`GET /accounts/{id}/orders?clientOrderKey=`, ADR-093). A restatement the key does not find is answered as
    FR-XOC-03's "no read model" case. An `OrderStatusRequest` whose key finds nothing, or another order than the
    `OrderID` it names, MAY fall back to the listing; `OrderMassStatusRequest` lists by definition.
  - A listing SHALL be bounded: at most `ORDER_READMODEL_LIST_CONCURRENCY` (2) in flight a gateway, a caller that
    waits 2 s for a permit is answered "order status unavailable"; it is parsed row by row from the stream, and given
    up past `ORDER_READMODEL_LIST_MAX_ROWS` (100,000), answered the same way, never as a short list.
  - Sizing: a parsed row is about 200 bytes, so two listings at the bound hold about 40 MB of the gateway's 768 MB
    heap. The heap is not raised.
  - Counters: `traderx_gateway_readmodel_reads_total{kind="list"|"key"}`,
    `traderx_gateway_readmodel_refused_total{reason="busy"|"too_large"}`.
  - Why: GKE acceptance run 7 (2026-11-12, `3526dcdf`), stage 3. After the leader's JVM was killed at 450K orders/s
    each gateway resubmitted 1,100-1,450 stranded FIX orders (FR-XOC-06), the new leader answered the booked ones
    `DUPLICATE`, and each restatement read `?status=all`, the account's share of the read model's order ring, as one
    String and one JSON tree, on its session's own thread (13 of them at once in one gateway's profile). All 18 gateways ran out of heap 9-13 s after the leader change and again 10-15 s after their
    clients reconnected and sent 1.4M status requests; the members' rate went from ~400K sides/s to ~0. The cluster,
    bridges, read model and history were exact. A second kill reproduced it (17 of 18), with the listing at 16-17 %
    of the gateways' allocation across the kill and absent before it.
  - Off-rig, at the gateway's JVM settings (ZGC, 768 MB), 33 session threads x 40 reads against a read-model stand-in
    whose `?status=all` answer is 7,150 rows / 4.5 MB (kind measured 7,129-7,150 rows / 5.5 MB an account at the
    default 50,000-row ring; GKE's size was not measured): the `3526dcdf` reader threw `OutOfMemoryError` in 3 of 3
    runs (27, 25, 15 of them, the first after 2.2-2.4 s) and in 0 of 1 at 13 threads; the by-key read 0 in 2 runs
    (1,320 reads in 0.3 s); the bounded listing 0 at 7,150, 50,000 and 150,000 rows (230 of 1,320 refused busy at
    50,000; all refused at 150,000, over the row bound or busy). The old reader there is a copy of the removed
    method, not the old class. Harness and results: the acceptance notes, `run7-3526dcdf/gateway-oom-local/`.
  - Kind did NOT reproduce it (`scripts/proofs/kind-fix-restatement-failover.sh`, 2026-11-12, `3526dcdf`, one
    gateway at production's JVM settings, 28 FIX sessions x 40/s): 4,071 orders stranded by the kill, none answered
    `DUPLICATE`, so no restatement ran and the read model served 0 listings; 0 `OutOfMemoryError`. Kind's election
    took 10.5 s, and the stranded orders were ones sent to a leader already dead, so they were booked fresh. The
    restatement storm needs orders committed and not yet acknowledged at the kill: GKE rates.
  - GKE, run 7b (2026-11-12, `9c39f4ef`, the acceptance render, the leader's JVM killed after 10 min at 439K
    sides/s; run 7's stage 3 on `3526dcdf` is the before arm): 28,140 FIX orders stranded, 25,512 restated, each by one
    by-key read (25,512), 0 listings, 0 refused; **0 `OutOfMemoryError` and 0 restarts on 18 of 18 gateways**, all ready
    at every 30 s sample for 6 min; FIX 0 rejected, 0 reconnects, 0 duplicate answers or fills, its fill rate
    unchanged through the kill. The lists the old code would have read were 8,079-9,908 rows / 6.5-8.0 MB an account.
    Not shown: the bounded listing on a rig (no status request arrived: 0 listings), and the binary half of the load
    after the kill (its driver stops itself on an unanswered order).
  - Seen there, not part of this requirement: 49 of 142,715,038 FIX orders were never answered (FR-XOC-06's two
    resubmissions exhausted or not triggered; not read further).
  - Not covered: an open order the read model has not projected yet is restated `39=3` (as before this change).

## Acceptance

| Case | FR | Where | Expected |
| --- | --- | --- | --- |
| retry after the original left retention | 01 | `IdempotencyEvictionTest.retryAfterTerminalEviction…` | REJECTED/DUPLICATE naming the original; no second order |
| retry of a retained order | 01, 02 | `IdempotencyEvictionTest.retryOfARetainedOrder…` | one output: ORDER_FILLED, original ref, `publishNats=false` |
| FIX resend, original aged out, read model present | 03 | `FixGatewayFillReportTest.aResentClOrdId…` | `150=I`, `37=ord-42`, `39=2`, CumQty 5, Text `DUPLICATE…` |
| same, no read model | 03 | `FixGatewayFillReportTest.withoutAReadModel…` | `150=I`, `37=ord-42`, `39=3`, never `39=8` |
| FIX order stranded by an election, lost | 06 | `FixGatewayFillReportTest.anOrderStrandedByAnElection…` | resubmitted once under the same key; `150=0` for `ord-7` |
| same, committed before the election | 06 | `FixGatewayFillReportTest.anOrderThatCommittedBeforeTheElection…` | `150=I` for `ord-42`; not booked twice |
| 40 restatements at once | 03, 07 | `FixGatewayFillReportTest.aResentClOrdId…` | 40 by-key reads, 0 listings; fails on the listing |
| a listing past the row bound | 07 | `ReadModelOrdersTest.aListingOverTheRowBound…` | unavailable, never a short list; by-key unaffected |
| 20 listings behind a held one | 07 | `ReadModelOrdersTest.listingsBeyondThePermits…` | 20 refused, 1 reached the read model, by-key goes through |
| ambiguous with no election | 06 | `FixGatewayFillReportTest.anAmbiguousAnswerWithoutAnElection…` | one submit, no answer |
| back-to-back elections | 06 | `FixGatewayFillReportTest.resubmissionsAcrossRepeatedElections…` | three submits, then ambiguous |
| binary client through a gateway kill and a leader kill | 01, 02, 05 | harness arm `xo`, local triad | 0 missing / duplicate / ambiguous; strict native replay, allowance 0 |
| stub gateways: 20 ambiguous, a gateway dies with 20 in flight | 05 | harness `tests/test_loadgen_exactly_once.mjs` | 600/600 executed once; today's loadgen fails |
| FIX client through a gateway kill and a leader kill, ADR-086 sessions | 01, 03 | harness arm `xof`, local triad | 0 missing / duplicate / ambiguous; strict native replay, allowance 0 |
