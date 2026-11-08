# Account admission tasks

- [x] Diagnose RI33: the bash 3.2 brace expansion of the body, account 0 admitted, the offer-only 200 (issue file).
- [x] FR-AAD-01 body validation; FR-AAD-02 member ack and gateway 200/422/504; FR-AAD-03 `/seed`.
- [x] `AccountAdmissionAckTest`: fails 4/4 on the pre-fix code and passes 4/4 with the fix. `ClusterReconOtcRefusalTest` counts egress
  by kind: 6 bookings plus 2 account-control acks. On 2026-11-08, `scripts/ci/engine-tests.sh hosted` ran 918 tests with 0 failures
  (10 skipped) and all five allocation gates passed. `dedicated` passed: ThreeMemberClusterTest 2/2, SnapshotBarrierPerformanceTest,
  noGcTest and riskNoGcTest. (The earlier "514 tests" figure came from a bare `./gradlew test` and is not the CI bar.)
- [ ] Merge into `traderX-risk-integration` (coordinator), and a cluster-node image for the next bring-up.
