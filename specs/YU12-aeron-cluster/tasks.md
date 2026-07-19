# Tasks: YU12-aeron-cluster

## Spec and generation

- [x] T-AC01 Create the full YU12 spec pack and generated architecture document.
- [x] T-AC02 Add catalog, state-generation, render, runtime-harness, and state-wrapper entries.
- [x] T-AC03 Generate YU12 from a clean target and verify every ancestor marker survives.

## Clustered service core

- [x] T-AC04 Add the Aeron Cluster dependency aligned with the locked Aeron version.
- [x] T-AC05 Host `MatchingEngine` + risk apply inside `ClusteredService.onSessionMessage` with
  the inherited SBE ingress decode.
- [x] T-AC06 Prove the single-member round-trip: cluster client offer → consensus log → match →
  committed output.
- [ ] T-AC07 Route time-driven behavior through cluster time / `onTimerEvent`; audit state
  transitions for wall-clock, entropy, and cross-thread reads.

## Snapshot completeness

- [x] T-AC08 Serialize complete deterministic state in `onTakeSnapshot` (book with terminal
  eviction order, generators, idempotency with retention order, risk policy/accounts/securities,
  positions, prices) and restore on `onStart`, per `system/snapshot-completeness-matrix.md`.
- [ ] T-AC09 Assert on load and promotion that every restored generator exceeds every ID ever
  issued; fail closed on violation.
- [x] T-AC10 Prove snapshot → post-snapshot orders → restart → strict no-ID-reuse on the
  single-member cluster.
- [ ] T-AC11 Port the parent state's corruption/interrupted-install/term-change recovery matrix
  to the cluster snapshot path.

## Three-member cluster

- [x] T-AC12 StatefulSet member identity, headless discovery, per-pod PVCs, NetworkPolicy, and
  the dedicated kind profile for three members.
- [x] T-AC13 Prove leader election, leader-kill re-election, and wiped-member rejoin via snapshot
  retrieval + log replay on kind (PROOF-yu12-kind-ha-2026-07-18.md).
- [x] T-AC14 Prove the promoted recovered member passes the strict no-ID-reuse assertion live
  (0 REUSE across 2 failovers + empty-disk rejoin, kind).

## Gateway and ingress

- [x] T-AC15 Build the stateless-forward FIX/REST gateway on the cluster client with
  leader-follow re-pointing. Live-verified on kind: REST /orders + /orders/batch + /metrics, and a
  POST served through a live leader-kill (orderRef 8056 after electing a new leader).
- [x] T-AC16 Prove counterparty FIX session survival across a leader change (FixGatewaySurvivalTest, in-process).
- [x] T-AC17 Build the feed adapter sequencing conflated pricing/control ingress; remove every
  side-channel input path, including symbol-identity registration as sequenced ingress
  (matrix finding F2). Built; live NATS verification pending.
- [x] T-AC18 Implement the split readiness contract (cluster state vs admission state).

## Proof

- [x] T-AC19 Keep inherited allocation gates exact-zero on the service thread; `noGcTest` green.
- [x] T-AC20 Measure client-observed failover. GKE (tuned 1s heartbeat): client-facing ~200 ms
  (transparent), system-facing ~2 s (12 s default); off-plane proven. PROOF-yu12-gke-failover-2026-07-18.md.
- [~] T-AC21 GKE comparison labelled `aeron-cluster`: deploy+bench packaged as hand-over
  commands (GKE-yu12-deploy-bench.md); gateway serves /orders/batch + /metrics for it. GKE run is yaakov's.
- [ ] T-AC22 Record all evidence in `generation/implementation-status.md`.
