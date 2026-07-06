# Tasks: YU03 In-Memory Risk Gateway

## Slice 1 — done

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
- [x] T-11 `ReplicaBootstrap` journaled startup fetch (ADR-019 slice-1 stand-in).
- [x] T-12 Tests: `BlpRiskStateTest`, `GatewayReplicaStoreTest`, `RiskReplayDeterminismTest`.
- [x] T-13 Spec pack (spec, requirements, ADRs 018/019/020, architecture, runtime-topology,
      data-model, contract-delta, plan, research, no-gc, this file); pipeline hooks; catalog.
- [x] T-14 State renumbering to `YUxx-` lineage (YU02-lmax-kubernetes / YU03-in-memory-risk-gateway).
- [x] T-15 Runtime harness: YU03 start/stop/status/test scripts + `install-generated-runtime-harness`
      case so `generate-state.sh YU03-in-memory-risk-gateway` completes.

## Deferred — later commits (see plan.md)

- [ ] T-20 Durable account-service/reference-data control feeds (outbox → JetStream), watermarked
      subscribe-buffer-snapshot bootstrap, gap/epoch/staleness detection (ADR-019, FR-IMRG04/05/32/33/34).
- [ ] T-21 Entitlement replica fed from the real-auth roadmap item (principalKey path already wired).
- [x] T-22a Grafana dashboard for the risk metric set (`traderx-risk-gateway.json`): decisions/
      rejections by reason, control-update rejections, gateway/BLP decision latency p99,
      replica rebootstrap events.
- [ ] T-22b Alerts for the risk metric set (NFR-IMRG08) — no alert rules defined yet; needs
      threshold decisions (e.g. what rejection rate or replica-rebootstrap rate pages someone),
      same reasoning as the T-23b latency gate.
- [x] T-23a `AllocationGateTest` extended with `hotPathIsAllocationFreeInSteadyStateWithRiskGating()`
      — real `BlpRiskState` wired into the BLP so every ORDER_NEW runs `decideAndReserve`;
      `noGcTest` (Epsilon-GC, never reclaims) passes with risk gating on (NFR-IMRG02).
- [x] T-23b p99 latency CI gate over the risk path (NFR-IMRG01/13): 5us threshold (~5-8x the
      observed 600-950ns p99 on dev hardware), asserted in
      `AllocationGateTest.hotPathIsAllocationFreeInSteadyStateWithRiskGating()` (BLP decideAndReserve)
      and `GatewayReplicaStoreTest.screenLatencyP99StaysUnderGateway5usBudget()` (edge screen()).
      Stable across repeated runs — 0 failures from the latency assertion itself in 6+ runs (the
      known unrelated 72-byte allocation flake still fires independently ~1-in-3).
- [ ] T-24 Multi-Gateway deployment + concurrency-overshoot test (FR-IMRG25).
- [ ] T-25 UI: surface rejection reasons + `clientOrderId` (FR-IMRG44).
- [ ] T-26 Full container smoke: order/cancel/fill, projector convergence, NATS/WS delivery,
      durable control propagation, kill-switch/restriction enforcement end to end.
- [ ] T-27 k8s manifest env plumbing for `RISK_*` knobs (defaults are live-safe today).
