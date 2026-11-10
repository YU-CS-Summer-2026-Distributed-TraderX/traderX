# Session pipelining (binary and FIX)

Status: implemented 2026-11-10 on `binary-acceptor-pipelining` (RI35, binary and FIX); unit tests and kind only. kind binary: 16 sessions reach the 64-session rate; `queue` p99 under 50 µs not met on kind (by the pre-fix gateway either). kind FIX: 4 sessions reach the 64-session rate (4.0× the pre-fix 4); one 64-session run lost 187 acks to the members' egress drop (T-BSP07).

[Spec](spec.md) · [Plan](plan.md) · [Tasks](tasks.md). Issue: RI35.

One binary session used to carry one order at a time, so a gateway's rate was sessions ÷ cluster round trip. The
connection now submits each frame without waiting, in arrival order, and its writer thread writes each ack when it
completes, up to 4,096 frames in flight per connection (FR-BSP-01). The changed sources are
`BinaryGatewayAcceptor.java`, `OrderSubmitter.java` (four asynchronous defaults), `ClusterGatewayMain.java`
(`submitPipelinedAsync`, with the synchronous path's pieces extracted unchanged) and `BinaryGatewayAcceptorTest.java`
in the YU18 order-matcher runtime override (the test is new to YU18; YU13's copy is superseded).

A FIX session did the same through QuickFIX/J's `fromApp`. It now submits each NewOrderSingle without waiting, in
arrival order, and the session's report thread sends each answer when it is decided, up to 1,024 per session
(FR-BSP-02). QuickFIX/J advances the inbound number when `fromApp` returns, so the session store holds the stored number
below every undecided order (FR-GFS-19), and an order in flight at a gateway crash is still resent and answered.
Changed: `FixGatewayAcceptor.java`, `FixSessionStore.java`, `OrderSubmitter.java`/`ClusterGatewayMain.java` (the
String-keyed `submitOrderAsync`), `FixGatewayFillReportTest.java`, `FixStoreGroupCommitIT.java`,
`scripts/bench/load/fix-multi.mjs` (password, reject and stray-report counts, a RESULT line).
