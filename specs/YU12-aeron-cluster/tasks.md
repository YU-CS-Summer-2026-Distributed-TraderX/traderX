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

- [ ] T-AC08 Serialize complete deterministic state in `onTakeSnapshot` (book, generators,
  idempotency, risk, symbols, control versions) and restore on `onStart`.
- [ ] T-AC09 Assert on load and promotion that every restored generator exceeds every ID ever
  issued; fail closed on violation.
- [x] T-AC10 Prove snapshot → post-snapshot orders → restart → strict no-ID-reuse on the
  single-member cluster.
- [ ] T-AC11 Port the parent state's corruption/interrupted-install/term-change recovery matrix
  to the cluster snapshot path.

## Three-member cluster

- [ ] T-AC12 StatefulSet member identity, headless discovery, per-pod PVCs, NetworkPolicy, and
  the dedicated kind profile for three members.
- [ ] T-AC13 Prove leader election, leader-kill re-election, and wiped-member rejoin via snapshot
  retrieval + log replay on kind.
- [ ] T-AC14 Prove the promoted recovered member passes the strict no-ID-reuse assertion live.

## Gateway and ingress

- [ ] T-AC15 Build the stateless-forward FIX/REST gateway on the cluster client with
  leader-follow re-pointing.
- [ ] T-AC16 Prove counterparty FIX session survival across a leader change.
- [ ] T-AC17 Build the feed adapter sequencing conflated pricing/control ingress; remove every
  side-channel input path.
- [ ] T-AC18 Implement the split readiness contract (cluster state vs admission state).

## Proof

- [ ] T-AC19 Keep inherited allocation gates exact-zero on the service thread; `noGcTest` green.
- [ ] T-AC20 Measure client-observed failover under 1,000 ms through the gateway.
- [ ] T-AC21 Run the three-run GKE comparison labelled `aeron-cluster` against the stored YU11
  Aeron HA baseline.
- [ ] T-AC22 Record all evidence in `generation/implementation-status.md`.
