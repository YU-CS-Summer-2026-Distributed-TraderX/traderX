# Tasks: in-memory-risk-gateway

## Specification and Registration

- [x] **TIMRG01** Define architecture, functional/nonfunctional/no-GC requirements, data model,
  contracts, topology, messaging, ADRs, implementation plan, and smoke scope.
- [x] **TIMRG02** Register state/catalog lineage, generated learning paths, UI metadata expectations, and
  spec coverage with parent `009b-lmax-sequencer-architecture`.
- [x] **TIMRG03** Implement state generation hook, renderer/lifecycle delegates, overlay capture path, and
  initial `009b`-parity generated output.

## P0 — Contracts and Baselines

- [ ] **TIMRG04** Lock SBE submitted-command/control/decision schemas, stable reason precedence, schema
  evolution/upcast policy, and external HTTP status mapping.
- [ ] **TIMRG05** Capture `009b` admission remote-call/latency, no-GC, output latency, output topology,
  and smoke baselines using a documented environment/fixture.
- [ ] **TIMRG06** Build deterministic account/security/price/policy/restriction/kill-switch fixtures and
  control-source version/epoch helpers.

## P1 — Sources, Stream, and Replicas

- [ ] **TIMRG07** Add account/account-entitlement transactional outbox or equivalent durable versioned
  change log plus watermarked snapshot contract.
- [x] **TIMRG08** Add authoritative security id/status versioned change log plus watermarked snapshot;
  preserve existing reference-data query API.
- [ ] **TIMRG09** Add minimal authenticated/versioned risk-policy, restriction, and kill-switch owner with
  provenance/audit and watermarked snapshot.
- [ ] **TIMRG10** Configure durable retained control subjects, ACLs, retention, consumer positions, high
  watermarks, and source retry/outbox draining.
- [ ] **TIMRG11** Implement reusable bounded replica bootstrap: subscribe/buffer, snapshot verify/install,
  apply `>W`, catch up, readiness, gap/epoch/re-bootstrap.
- [ ] **TIMRG12** Add bootstrap race, duplicate, reorder, gap, epoch, checksum/schema, buffer overflow,
  retention loss, and stale-feed tests.

## P2 — Gateway Screening

- [ ] **TIMRG13** Install replica library and readiness health in trade-service and order-matcher Gateway
  admission surfaces.
- [x] **TIMRG14** Implement shared local screening rules and stable preliminary rejection mapping with
  zero remote account/reference/price/risk lookup.
- [x] **TIMRG15** Replace client-driven `SymbolTable.idFor` registration with reference-authoritative
  security mapping and reject unknown/disabled securities.
- [ ] **TIMRG16** Add trusted principal-to-account entitlement validation and required `clientOrderId`
  normalization/bounded key mapping.
- [x] **TIMRG17** Add price source timestamp/version/trading status and missing/stale/price-collar checks;
  remove zero fallback from validation.
- [ ] **TIMRG18** Add Gateway readiness/rejection/latency/replica metrics with bounded labels and no-GC
  coverage.

## P3 — Sequenced BLP Risk

- [ ] **TIMRG19** Add submitted-command and complete versioned control-event codecs, journal records,
  decode/apply handlers, and compatibility tests.
- [ ] **TIMRG20** Add bounded/preallocated BLP account/security/entitlement/policy/restriction/kill-switch
  state and stable decision precedence.
- [ ] **TIMRG21** Implement checked fixed-point exposure/notional calculation and exact open-order buy/
  sell quantity/notional reservations.
- [ ] **TIMRG22** Implement reservation conversion/release on partial/full fill, cancel, reject, expiry,
  and explicit policy-driven resting-order cancellation.
- [ ] **TIMRG23** Implement bounded idempotency state, deterministic retention frontier, original-decision
  return, and capacity behavior.
- [ ] **TIMRG24** Emit accepted/rejected decisions with reason/policy/control/price versions and correlated
  Gateway response; add mismatch diagnostic.
- [ ] **TIMRG25** Extend BLP snapshot, journal replay, warm-start, and follower state to controls,
  reservations, price freshness, idempotency, and watermarks.

## P4 — API, Failure Modes, and Compatibility

- [x] **TIMRG26** Make synchronous order/market-trade APIs return success only after BLP acceptance and
  map stable rejections/unready/capacity to documented responses.
- [ ] **TIMRG27** Implement explicit fail-closed matrix and policy-defined cancel/risk-reducing exceptions;
  remove any generic fail-open switch.
- [ ] **TIMRG28** Prove rejected commands remain journaled but produce no executable order, reservation,
  trade, position update, or accepted business subject event.
- [ ] **TIMRG29** Prove accepted order/trade/position REST, WS, NATS, UI, and relational query behavior
  remains compatible with `009b`.
- [x] **TIMRG30** Update state UI metadata, About/status, operational docs, and dashboards without changing
  unrelated frontend behavior.

## P5 — Quality Gates and Publication

- [ ] **TIMRG31** Extend Epsilon allocation and banned-API tests across Gateway screen, control apply, BLP
  decision, reservations, idempotency, decision emit, and inherited output handlers.
- [x] **TIMRG32** Add HdrHistogram/JMH/JLBH-style Gateway/BLP risk latency benchmarks and compare to
  documented `009b` baseline.
- [ ] **TIMRG33** Add multi-Gateway same-headroom race, Gateway/BLP skew, reservation property,
  idempotency, capacity, and mixed replay tests.
- [x] **TIMRG34** Provision Prometheus scrape coverage, Grafana panels, and alerts for replica/risk metrics;
  verify bounded cardinality.
- [ ] **TIMRG35** Run `noGcTest`, state smoke, `outputLatencyBenchmark`, `outputTopologyBenchmark`,
  determinism replay, and full module/contract suites; record evidence in implementation status.
- [x] **TIMRG36** Capture final overlay patchset, regenerate from clean parent, verify deterministic output,
  and prepare generated snapshot branch/tag publication.
