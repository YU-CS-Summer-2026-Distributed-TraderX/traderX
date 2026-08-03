# Tasks: YU03-in-memory-risk-gateway

## Delivered

- [x] T-01 Port `RiskReason` / `RiskMetrics` / `RiskRejectedException` / `RiskRejectionBody`.
- [x] T-02 Adapt `BlpRiskState` to the YU02 base (reservations via `ReservationHolder`, snapshot tuples).
- [x] T-03 Adapt `GatewayReplicaStore` (seeded + control-fed, SymbolTable id alignment, fail-closed).
- [x] T-04 `InputEvent` type-discriminated payload slots (keys/control); control ids 7–10.
- [x] T-05 `OutputEvent` reject + trade-decision kinds; `RestingOrder` reservation fields + riskReason.
- [x] T-06 `MatchingEngine`: decide+reserve before book, consume on fill, release on cancel,
      market-trade decision, control-event handlers.
- [x] T-07 `SnapshotStore` v3 (risk sections + per-order reservation) + `LmaxEngine` capture/restore.
- [x] T-08 `LmaxEngine` wiring: risk-state construction, ingress keys, control submission, trade ack,
      recovery-boundary policy re-alignment.
- [x] T-09 `OrderMatcherService` screening + rejection surface + risk metrics + price feed.
- [x] T-10 `RiskControlController` + `RiskExceptionHandler` + config (`risk.*`).
- [x] T-11 `ReplicaBootstrap` journaled startup fetch of the account/security universe (ADR-019).
- [x] T-12 Tests: `BlpRiskStateTest`, `GatewayReplicaStoreTest`, `RiskReplayDeterminismTest`.
- [x] T-13 Spec pack (spec, requirements, ADRs 018/019/020, architecture, runtime-topology,
      data-model, contract-delta, plan, research, no-gc, this file); pipeline hooks; catalog.
- [x] T-14 State registration under the `YUxx-` lineage (parent `YU02-lmax-kubernetes`).
- [x] T-15 Runtime harness: YU03 start/stop/status/test scripts + generation-hook registration.
- [x] T-16 Grafana dashboard for the risk metric set (`traderx-risk-gateway.json`): decisions/
      rejections by reason, control-update rejections, gateway/BLP decision latency p99, replica
      rebootstrap events.
- [x] T-17 Allocation gate: `AllocationGateTest.hotPathIsAllocationFreeInSteadyStateWithRiskGating()`
      wires the real `BlpRiskState` into the BLP so every ORDER_NEW runs `decideAndReserve`;
      `noGcTest` (Epsilon-GC) passes with risk gating on (NFR-IMRG02).
- [x] T-18 p99 latency CI gate over the risk path (NFR-IMRG01): 5µs threshold (~5–8× the observed
      600–950ns p99 on dev hardware), asserted in `AllocationGateTest` (BLP `decideAndReserve`) and
      `GatewayReplicaStoreTest.screenLatencyP99StaysUnderGateway5usBudget()` (edge `screen()`).
- [x] T-19 UI: surface rejection reasons + `clientOrderId` (FR-IMRG44). `OrderResponse` gained a
      `riskReason` field (REST create-order response and NATS live-blotter bridge); the order ticket
      has an optional Client Order ID field; the create-order alert shows the actual rejection reason
      for both the BLP-level (200 OK, status=REJECTED) and edge-level (422/503,
      `RiskRejectionBody.reason`) paths.

## Still open

- [ ] Entitlement feeding into the admission-time replica/BLP check (FR-IMRG02 entitlement replica,
      FR-IMRG30 full authn). The `principalKey` slot is already wired on the admission path; this
      threads a resolved principal's entitlements into it, using the real JWT auth built in
      `YU05-post-trade-compliance`.
- [ ] Alert rules for the risk metric set (NFR-IMRG08). The dashboard is provisioned; alert
      thresholds (e.g. what rejection rate or replica-rebootstrap rate pages someone) are a
      paging-policy decision, not defined yet.
- [ ] Multi-Gateway deployment + concurrency-overshoot test (FR-IMRG25): deploy the Gateway as a
      separate tier and confirm the BLP's single-writer authority prevents overshoot under
      concurrent gateways.
- [ ] Full multi-scenario container smoke. Staging live-verification covers the `PRICE_COLLAR`
      rejection scenario (see `generation/implementation-status.md`); order/cancel/fill, projector
      convergence, NATS/WS delivery, durable control propagation, and kill-switch/restriction
      enforcement are not yet exercised end to end in one pass.
- [ ] k8s manifest env plumbing for `RISK_*` knobs (defaults are live-safe today).
