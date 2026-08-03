# Implementation Plan: YU03-in-memory-risk-gateway

## Goal

Add a two-tier pre-trade risk admission gate to the LMAX BLP — an in-process Gateway replica that
screens without any synchronous lookup, and an authoritative single-writer BLP decision that checks
and reserves exact aggregate exposure in global sequence order — so every acceptance and rejection
is journaled and replays deterministically, without changing the inherited ring topology, journal
gate, matching policy, or NATS subjects.

## Workstreams

1. Risk core (`risk/`)
   - `RiskReason`, `RiskMetrics`, `RiskRejectedException`, `RiskRejectionBody` ported; `BlpRiskState`
     and `GatewayReplicaStore` adapted to the YU02 base; `ReservationHolder` and `ReplicaBootstrap`
     new.
2. BLP integration (`lmax/`)
   - Decide-and-reserve before book entry, consume on fill, release on cancel, market-trade
     decisions with correlation acks, and control-event handlers.
   - `InputEvent` type-discriminated payload slots, `OutputEvent` reject/trade-decision kinds,
     `RestingOrder` reservation, `SnapshotStore` v3, and marshaller/read-model plumbing.
3. Edge + control plane
   - `OrderMatcherService` screening, rejection surface, and metrics; `RiskControlController` and
     `RiskExceptionHandler`; `risk.*` config; journaled startup bootstrap.
4. Observability
   - Bounded Micrometer metric set on both tiers and a provisioned Grafana risk-gateway dashboard.
5. State registration
   - Spec pack, generation hook + render script, catalog entry, runtime harness registration.
6. Validation
   - Unit tests for the decision pipeline, reservation lifecycle, edge screening, and replay
     determinism; allocation-gate and p99 latency CI gates over the risk path.

## Key decisions (see ADRs + spec.md)

- Two-tier Gateway + authoritative BLP decision (ADR-018); control events in the global journal
  (ADR-020); replica bootstrap is journal-sequenced (ADR-019).
- No journal/replication wire-format change: new data rides type-discriminated payload slots unused
  by each event type, so pre-state journals replay unchanged and the 64-byte record is shared by
  the journal and the NATS replication stream.
- Reservations ride the pooled order entry (`ReservationHolder` on `RestingOrder`) rather than
  dense orderRef-indexed arrays, since orderRef is monotonic/unbounded here; aggregates rebuild
  from open orders at snapshot restore.
- SymbolTable stays the security-id authority (`symbols.tab` persists ids across restarts); the
  replica aligns to it at startup.
- Snapshot format v3 extends the single `snapshot.dat`; v1/v2 snapshots still load.
- Market trades become synchronous (`POST /trades` blocks for the sequenced decision); the optional
  `clientOrderId` field is the only other admission API delta.

## Exit Criteria

- Spec and tasks are complete and reviewed.
- Generation hook produces expected artifacts and exits successfully.
- Unit test suites pass: `BlpRiskStateTest`, `GatewayReplicaStoreTest`, `RiskReplayDeterminismTest`,
  the allocation gate with risk gating on, and the p99 latency gates on both tiers.
- Generated shared files retain every ancestor state's content alongside this state's additions.
- State can be published to `code/generated-state-YU03-in-memory-risk-gateway`.

## Validation status

- Unit + integration suite green (48 tests: 47 pass, 1 skipped), including replay-determinism and
  snapshot-v3-tail parity, which prove ADR-020 / NFR-IMRG03.
- Perf acceptance: the allocation gate runs the real `BlpRiskState` on every ORDER_NEW under
  Epsilon-GC with zero steady-state allocation, and the p99 latency gate holds on both the BLP
  `decideAndReserve` and edge `screen()` paths (see `AllocationGateTest`, `GatewayReplicaStoreTest`).
- Live verification ran in an isolated `traderx-yu03-staging` namespace (see
  `generation/implementation-status.md`); full multi-scenario container smoke is tracked in
  `tasks.md` under "Still open".
