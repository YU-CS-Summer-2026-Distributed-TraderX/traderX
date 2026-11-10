# Binary session pipelining tasks

Updated 2026-11-10.

- [x] T-BSP01 Pipeline `BinaryGatewayAcceptor.handle` (FR-BSP-01).
- [x] T-BSP02 `BinaryGatewayAcceptorTest`: completion-order acks with every clOrdId once, arrival order at the seam,
  the bound reached and never exceeded; a half-closed connection answered in full (an ambiguous answer stays
  ambiguous); a closed connection with orders pending leaks nothing. `PipelineTaskDeadlineTest`: the asynchronous
  answer completes on the caller's executor; silence is null and retires the unstarted task.
- [x] T-BSP03 kind (`scripts/proofs/kind-binary-pipelining.sh`, one arm per fresh rig, 15,000/s offered): 16 sessions
  14,573/s, 64 sessions 14,294/s; 4 sessions 14,778/s against the pre-fix gateway's 3,349/s; G3h ok in every arm.
  NFR-BSP-01's rate half met. Its `queue` half is not met on kind: p99 160–359 µs (p50 1.2–1.4 µs), and the pre-fix
  gateway reads 84–272 µs at the same rates. Numbers and causes: RI35.
- [x] T-BSP05 Pipeline `FixGatewayAcceptor`'s NewOrderSingle (FR-BSP-02) and hold the stored target below undecided
  orders (FR-GFS-19). `FixGatewayFillReportTest`: arrival order at the seam, the bound reached and held, answers in
  decision order, each once, an ambiguous one silent; a cancel of an order in flight waits for its answer.
  `FixStoreGroupCommitIT.theStoredTargetStaysBelowAnUndecidedOrder` in both store modes. All 81 FIX tests pass.
- [x] T-BSP06 kind (`scripts/proofs/kind-fix-pipelining.sh`, one arm per fresh rig, 8,000/s offered): 4 sessions
  7,985/s against the pre-fix gateway's 2,004/s; 16 sessions 7,982/s; 64 sessions 7,973 and 7,984/s; G3h ok on
  every arm that reached it. NFR-BSP-02's rate met. One of two new-gateway 64-session runs lost 187 committed acks at
  the load's start to the members' best-effort egress (answered ambiguous); the pre-fix gateway's one 64-session run
  lost none. Numbers and the mechanism: RI35.
- [ ] T-BSP07 Members: count every egress drop and stop dropping committed acks to a live session (RI35), then rerun
  T-BSP06's 64-session arm until clean.
- [ ] T-BSP04 GKE: the same comparison on a pinned guaranteed gateway (FR-PRG-06) with `--throughput-members` (egress
  coalescing; without it a pipelined session overruns the members' best-effort egress), and `queue` p99 there.
