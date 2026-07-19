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
- [x] T-AC20 Measure failover. GKE final (100ms/400ms/200ms timeouts): system-facing
  **653-716 ms idle, 724/778 ms under full flood** (node-clock-precise crash instrument);
  client-facing best ~200 ms; off-plane proven across ~40 kills, 0 ID reuse everywhere.
  PROOF-yu12-gke-failover-2026-07-18.md.
- [x] T-AC21 GKE comparison labelled `aeron-cluster` RUN (user authorized GKE this session):
  pipelined gateway sustains **28,860-35,714 submits/s, 45,684-135,834 booked/s** vs the 25,149
  baseline — NFR-AC02 met and exceeded. Flood-hardening landed en route: bounded egress,
  output-ring backpressure drain (poison-pill deadlock class killed), gateway probe/heap,
  60 s snapshots (measured), catch-up-gated readiness (rolling restarts safe).
  scripts/bench/results/gke-comparison.csv label `aeron-cluster-pipelined`.
- [x] T-AC22 Evidence recorded in `generation/implementation-status.md` (2026-07-19 GKE section).
