# Non-Functional Delta: YU03-in-memory-risk-gateway over YU02-lmax-kubernetes

| Req | Status | Notes |
|---|---|---|
| NFR-IMRG01 latency budgets (gateway p99 < 25µs, BLP decision p99 < 25µs) | **Instrumented, not yet accepted** | `traderx_gateway_validation_latency_seconds` + `traderx_risk_decision_latency_seconds` exported; perf-profile acceptance run deferred (stale branch recorded gateway p99 625ns / BLP p99 459ns for the same pipeline shape). |
| NFR-IMRG02 zero steady-state allocation on decision path | **Code discipline done** | `BlpRiskState` is preallocated primitive arrays; no boxing/iterator/lambda on the decision path. Extending the allocation-gate test to cover risk paths is part of the test suite here; formal Epsilon-GC gate rerun deferred to the perf pass. |
| NFR-IMRG03 replay determinism | **Done** | Decisions are pure functions of sequenced events + fixed config seeds; covered by the replay test. |
| NFR-IMRG04 single-writer discipline | **Done** | No locks/atomics/clock reads/randomness added to the BLP thread; decision time is event-carried. |
| NFR-IMRG05 recovery target | **Done (mechanism)** | Risk state restores from snapshot v3 + tail; adds one pass over snapshot rows. |
| NFR-IMRG06 admission readiness gating | **Done** | Replica not-ready → 503 CONTROL_STATE_STALE; BLP readiness inherited. |
| NFR-IMRG07 staleness detection bounds | **Partial** | Price staleness enforced; control-feed staleness arrives with durable feeds. |
| NFR-IMRG08 observability retained + extended | **Partial** | All inherited metrics kept; new bounded metric set exported; Grafana dashboard (`traderx-risk-gateway.json`) provisioned, alert thresholds deferred. |
| NFR-IMRG09 authenticated control transport | **Partial** | Shared token + operator header; TLS/OIDC deferred to auth roadmap item. |
| NFR-IMRG10 bounded metric cardinality | **Done** | Reasons/replica labels only; no account/security/principal labels. |
| NFR-IMRG11 inherited build/publish/deploy intact | **Done** | State is order-matcher overrides only; YU02-lmax-kubernetes harness unchanged. |
| NFR-IMRG12 new dependencies | **Done** | None added (JDK HttpClient + existing Jackson/HdrHistogram). |
| NFR-IMRG13 no benchmark regression | **Deferred** | Rerun `noGcTest`/latency benchmarks on the perf pass. |
