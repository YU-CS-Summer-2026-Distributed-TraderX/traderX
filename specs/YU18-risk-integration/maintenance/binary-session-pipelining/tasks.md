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
- [ ] T-BSP04 GKE: the same comparison on a pinned guaranteed gateway (FR-PRG-06) with `--throughput-members` (egress
  coalescing; without it a pipelined session overruns the members' best-effort egress), and `queue` p99 there.
