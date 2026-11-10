# Session pipelining specification (binary and FIX)

Status: implemented 2026-11-10 on `binary-acceptor-pipelining`; unit tests and kind only (see [tasks](tasks.md)): the
rate half of NFR-BSP-01 met on kind, the `queue` half not (RI35); NFR-BSP-02's rate met on kind, with the members' egress drop open (T-BSP07). Not on GKE or production. Issue: [RI35](../../../../issues/risk-integration/35-binary-acceptor-serialises-a-session.md).
Dependencies: the gateway's pipelined submit (`ClusterGatewayMain`'s `PendingOrder` future, owner thread, deadline
reap and failover reap, all unchanged). No engine, wire-layout, snapshot or member change.

## Requirements

- FR-BSP-01: A binary order-entry connection SHALL carry many orders at once. The connection's thread SHALL read,
  decode and submit frames without waiting for their acks (`OrderSubmitter.submitOrderAsync`,
  `submitSequencedOrderAsync`, `submitCancelAsync`, `submitReplaceAsync`), and one writer per connection SHALL write
  each ack when that order's committed decision arrives.
  - Arrival order: each asynchronous submit takes its window slot and queues the order for the cluster before it
    returns, on the connection's thread, so one connection's orders reach the log in the order they arrived (a
    sequenced stream's seq n+1 can never overtake seq n, and two replaces of one order apply in the order sent).
  - Bound: at most 4,096 frames per connection SHALL be read and not yet acked. With the bound reached the gateway
    SHALL stop reading the connection, so TCP pushes back on the client. The gateway-wide window
    (`GATEWAY_MAX_INFLIGHT`, default 4,096) still bounds the gateway as a whole, and a full window parks the
    connection's thread exactly as it parked a synchronous submit.
  - Ack order: acks on one connection MAY be written in a different order from their requests. The echoed `clOrdId`
    is the client's correlation; a client that pipelines SHALL give its orders distinct `clOrdId`s (0 means "no
    key" and cannot be told apart).
  - Outcomes are unchanged: the future completes with what the synchronous submit returns, after the same reject
    log, root span and wedge accounting (`noAckStreak`, the offered-unacked self-heal), off the owner thread on the
    connection's writer. A null answer (no committed ack within the same budget, ACK_TIMEOUT + 2 s from the enqueue;
    a queued task the owner never started is retired) is still `STATUS_AMBIGUOUS`, never a reject.
  - Close: frames already read SHALL still be answered (a client that half-closes receives every ack it is owed). The
    connection's thread SHALL wait for its answers (each bounded by the submit deadlines; the wait gives up after
    60 s with a warning), then stop its writer and close the socket. No thread, slot or future outlives them.
  - Latency decomposition (`LATENCY_DECOMP=1`, `GET /latency`): `decode`, `queue` and `cluster` are unchanged
    (bytes-in to the submit call on the connection's thread; the owner-thread wait from enqueue to offer; offer to
    committed ack). `reply` now runs from the answer reaching the connection's writer to the ack flushed (a sampled
    ack is flushed on its own; others are flushed once per burst); the hop from the owner thread to the writer is
    in `total` and in no segment, as the waiter's wake-up was before. `total` is still bytes-in to ack flushed.
- FR-BSP-02: A FIX session SHALL carry many NewOrderSingles at once (`FixGatewayAcceptor`). `fromApp`, on
  QuickFIX/J's session thread, SHALL submit each without waiting (`OrderSubmitter.submitOrderAsync` with the
  CompID-qualified ClOrdID, typed or not), and the session's one report thread SHALL send each answer when that
  order's committed decision arrives.
  - Arrival order: as FR-BSP-01, each order is queued for the cluster before `fromApp` returns.
  - Bound: at most `FIX_MAX_QUEUED_WRITES` / 2 (1,024) NewOrderSingles per session SHALL be undecided. With the bound
    reached `fromApp` waits, so QuickFIX/J stops dispatching the session and, once its inbound queue is full, stops
    reading it (ADR-092). Half the write bound leaves room for the window's reports and fills.
  - Report order: one thread sends a session's answers, in the order the orders were decided; reports for different
    orders MAY be in a different order from their requests (ClOrdID correlates). One order's reports keep their
    order (its New before its fills, as before). A request naming an order still in flight (F and G by OrigClOrdID,
    H by ClOrdID; AF all of them) SHALL wait for that order's answer before it is acted on, so it finds what it found
    when orders were answered one at a time.
  - Outcomes are unchanged: the same answer (report, duplicate restatement, ClOrdID map, fill tracking); null is still
    no report (ambiguous, the counterparty reconciles); FR-XOC-06 still resubmits an order a leader change stranded,
    from the report thread. ADR-086's in-flight accounting for the lease ends when the answer is sent.
  - Inbound sequence: QuickFIX/J advances a session's inbound number when `fromApp` returns, before the order is
    decided. FR-GFS-19 keeps the stored number below every undecided order, so an order in flight when its gateway
    dies is resent by the client and answered (as a duplicate if it was committed), as ADR-086 §4 gave a synchronous
    one.
  - Not pipelined: cancels and replaces, and every order of an exactly-once session (`FIX_EXACTLY_ONCE=jetstream`,
    ADR-090), whose store-then-submit and FR-XOD-12 catch-up stay one order at a time.
  - Latency decomposition: FIX orders sample `queue` and `cluster` as binary ones do; `decode`, `reply` and `total`
    are binary only.
- NFR-BSP-01: With pipelining, a gateway's binary rate SHALL NOT depend on the number of client sessions: on one rig,
  16 sessions SHALL reach the rate 64 sessions reach, with `/latency` `queue` p99 under 50 µs and G3h ok.
- NFR-BSP-02: With FR-BSP-02, on one rig and one offer, 4 FIX sessions SHALL reach the rate 64 reach, with every
  order answered once and G3h ok, against the pre-fix gateway's 4 sessions.

## Out of scope

FIX exactly-once stays off, and its sessions stay serial (FR-BSP-02). The account-control path (RI33) is untouched.
