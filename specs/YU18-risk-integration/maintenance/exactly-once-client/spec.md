# Exactly-once client specification

Status: FR-XOC-01, 02, 04, 05 describe the venue as it is; FR-XOC-03 is new. Scope: the order-entry contract a
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
  `ORDER_READMODEL_URL` is configured, else `OrdStatus=3` (done: the order is terminal, its detail aged out). It
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

## Acceptance

| Case | FR | Where | Expected |
| --- | --- | --- | --- |
| retry after the original left retention | 01 | `IdempotencyEvictionTest.retryAfterTerminalEviction…` | REJECTED/DUPLICATE naming the original; no second order |
| retry of a retained order | 01, 02 | `IdempotencyEvictionTest.retryOfARetainedOrder…` | one output: ORDER_FILLED, original ref, `publishNats=false` |
| FIX resend, original aged out, read model present | 03 | `FixGatewayFillReportTest.aResentClOrdId…` | `150=I`, `37=ord-42`, `39=2`, CumQty 5, Text `DUPLICATE…` |
| same, no read model | 03 | `FixGatewayFillReportTest.withoutAReadModel…` | `150=I`, `37=ord-42`, `39=3`, never `39=8` |
| binary client through a gateway kill and a leader kill | 01, 02, 05 | harness arm `xo`, local triad | 0 missing / duplicate / ambiguous; strict native replay, allowance 0 |
| stub gateways: 20 ambiguous, a gateway dies with 20 in flight | 05 | harness `tests/test_loadgen_exactly_once.mjs` | 600/600 executed once; today's loadgen fails |
