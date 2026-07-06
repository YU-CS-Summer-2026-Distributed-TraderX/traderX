# Implementation Plan: YU03 In-Memory Risk Gateway

Parent: `YU02-lmax-kubernetes`. Approach: forward-port the design (not the code) from the pre-k8s
`in-memory-risk-gateway` branch, re-based as a delta over the `YU02` runtime, delivered as the
smallest meaningful vertical slice first (one enforced, journaled, replayable pre-trade check + its
event-fed replica), then extended.

## Slice 1 (this commit) — delivered

1. **Risk core** (`risk/`): `RiskReason`, `RiskMetrics`, `RiskRejectedException`, `RiskRejectionBody`
   ported verbatim; `BlpRiskState` and `GatewayReplicaStore` adapted to the `YU02` base;
   `ReservationHolder` + `ReplicaBootstrap` new.
2. **BLP integration** (`lmax/`): decide+reserve before book entry, consume on fill, release on
   cancel, market-trade decisions + correlation acks, control-event handlers; `InputEvent`
   type-discriminated slots, `OutputEvent` reject/trade-decision kinds, `RestingOrder` reservation,
   `SnapshotStore` v3, `MarshallerHandler`/`InMemoryOrderReadModel`/`OrderSnapshot`/`HotPathMetrics`
   plumbing.
3. **Edge + control plane**: `OrderMatcherService` screening + rejection surface + metrics,
   `RiskControlController`, `RiskExceptionHandler`, config, journaled startup bootstrap.
4. **Tests**: `BlpRiskStateTest`, `GatewayReplicaStoreTest`, `RiskReplayDeterminismTest`.
5. **State packaging**: this spec pack, pipeline hooks, catalog registration.

## Key decisions (see ADRs + spec.md "Forward-port adaptations")

- Two-tier Gateway + BLP authority (ADR-018); control events in the global journal (ADR-020);
  watermarked replica bootstrap deferred with a journal-sequenced stand-in (ADR-019).
- No journal/replication wire-format change (type-discriminated payload slots), control ids 7–10,
  reservations on the order entry, SymbolTable stays the id authority, snapshot v3.
- Market trades become synchronous; optional `clientOrderId` is the only other API delta.

## Sequencing after slice 1

1. **Durable control feeds** — account-service/reference-data outbox → JetStream, watermarked
   subscribe-buffer-snapshot bootstrap, gap/epoch/staleness detection (ADR-019, FR-IMRG04/05/32/33/34).
2. **Entitlements** — fed once the real-auth roadmap item exists (principalKey path already wired).
3. **Observability** — Grafana dashboard + alerts for the new metric set (NFR-IMRG08).
4. **Perf acceptance** — extend `noGcTest` + latency benchmarks over the risk path; run the
   perf-profile p99 gates (NFR-IMRG01/02/13).
5. **Multi-Gateway** — deploy the Gateway as a separate tier and run the concurrency-overshoot test
   (FR-IMRG25).
6. **UI** — surface rejection reasons + `clientOrderId` (FR-IMRG44).

## Validation strategy

Unit + integration tests in-tree (green except two pre-existing env-only H2 projector failures on
the dev machine); replay-determinism and snapshot-v3-tail parity prove ADR-020/NFR-IMRG03. Full
container smoke, durable-feed propagation, and perf acceptance are deferred to the later commits above.
