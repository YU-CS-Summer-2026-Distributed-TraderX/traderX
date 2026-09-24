# Missed-event recovery tasks

- [x] Read assignment, establish isolated base and post exact lane claim.
- [x] Inspect archive, serializers, scoped registry and transaction ownership; specify before implementation.
- [x] Reproduce actual outage gap: real consumer restart leaves 4 of 6 trade legs and quantity40 instead of60.
- [x] Implement complete archive recovery and atomic projection checkpoint.
- [x] Verify duplicate, ordering, conflict, rollback and run barriers: six MariaDB controls.
- [x] Run generated real SQL/service/transport restart proof and required gates.
- [x] Record limitations and executable evidence; delivery commit/READY_FOR_REVIEW recorded on the board.

## Requirement evidence mapping

- FR-ER01/06: EventRecoveryPersistenceIT.frozenAndSelectedScopeBarriersRemainEffective and managed live old/fresh isolation.
- FR-ER02: RunIdentityConsensusTest strict actual archive replay, missing genesis, and capacity refusal; EventRecoveryPersistenceIT.missingDuplicateUnorderedAndWrongIdentitySourceRefuse. The capacity test first failed and exposed swallowed callback errors; corrected replay explicitly propagates them.
- FR-ER03/04: interiorGapAndOutOfOrderUpdatesConvergeWithoutChangingRetainedTrades verifies immutable SQL history and source-order cost basis; conflictingTradeAndAlteredOrderRefuseWithoutCheckpoint checks altered fields independently of retained digest.
- FR-ER05: checkpointFailureRollsBackEveryProjectionAndNotificationThenRetrySucceeds and outerTransactionCrashBeforeCommitAndLostReplyAfterCommit. These inject SQL failure/transaction rollback; they do not claim a JVM kill precisely within commit. Live proof closes/restarts actual service after committed checkpoint.
- NFR-ER01/02: bounded peer response, capped source events, archive-cap refusal and local-only endpoint implementation; limits are configuration bounds, not measured performance guarantees.

Original c057ac31 delivery regressions: 100 generated trade-processor unit tests; 19 existing plus6 new real MariaDB tests;26 matcher replay/identity tests. Five inherited allocation gates also ran and passed under the checked-in Java21 test profile; no latency measurement or deployment-profile guarantee. Required four SpecKit gates pass. Final checked-in launcher passes baseline, managed outage/restart recovery and six SQL controls on official generated sources. All187 runtime files match, with zero missing/mismatched files. Evidence: /Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri06-o1-event-recovery-20260924 (SHA256SUMS, final-launcher XML/logs, test-counts.json, parity.json). Owned disposable containers and matcher/gateway children confirmed absent after proof.

## Review correction O1-R1 (2026-09-24)

- [x] Reproduce same-key unexplained balance overwrite on real MariaDB before correction.
- [x] Add pre-write retained quantity attribution, explicit missing-position refusal, and source-order provenance checks.
- [x] Implement approved additive active-pointer reader and unknown scoped orders404.
- [x] Final generated negative controls, reader tests and live outage regression; correction evidence/commit.

Final R1 official generated execution:102 trade-processor unit tests,13 recovery SQL controls,11 position-service unit tests,3 real-SQL reader controls pass. Complete live launcher passes both original outage reproduction and managed consumer/NATS recovery. All189 runtime files match official generation;four required gates plus component validator pass. Evidence: coordination/eod-integration/review-evidence/ri06-o1-r1-20260924 (counts.json, before.xml, final-tests, final-launcher, parity.json, SHA256SUMS). Original immutable evidence remains separate. Correction commit/READY_FOR_REVIEW recorded on the board; no integration acceptance implied. Reader verification uses real SQL with MockMvc servlet transport, not a browser or deployed service claim.
