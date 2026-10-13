# C4D bounded settlement correction

Updated 2026-10-13. Status: ready for review; source and retained-data proof passed, zero-node cleanup verified.
Owner: 01a135b5-8b9e-7d00-b589-63cf004884a9. Branch: codex/c4d-offline-diagnosis; base diagnostic commit 231218a6. Coordinator retains canonical routing. Direct user authorization covers implementing this fix and testing it against the same retained-data copy; no integration or push.

The delivered image reproduced heap exhaustion in MariaDB CompleteResult buffering through SettlementService.sweep. This candidate adds a YU18 override with bounded ID batches, independent transactions, the existing projection write lock, conditional updates, and committed-only counters. It preserves automatic date/state semantics and the force override. A matching index is included in fresh/retained and standalone schema paths. Earlier states are unchanged.

Contract, plan and gates: [settlement-memory](../../../specs/YU18-risk-integration/maintenance/settlement-memory/README.md). Private evidence: parent lmax coordination/eod-integration/review-evidence/c4d-settlement-fix-20261013. The original diagnostic evidence remains separately preserved.

Acceptance requires generated unit/MariaDB tests, exact retained due-row completion with field witness preservation, bounded memory, restart continuation, recorded configuration/transaction timings, and zero-node cleanup preserving original PVCs/archives. Passing this bounded correction does not close all LR06 memory paths, retained delivery, matcher performance or original all-member restoration obligations. No runtime fix is integrated until the user separately authorizes that scope and target.

Evidence:115 generated unit tests and9 MariaDB tests passed. All5,757,028 original due trades transitioned across the batch/restart proof; final build completed4,137,028 remaining rows. Final SQL count5,851,507 Settled, zero due, protected-field SHA256 identical. Final sampled heap57.5MiB under768MiB, no OOM/restart. Completion used a test-only1s delay; shipped5s unchanged. See private REPORT.md for artifact identities, batch timing, failed attempts and proof limits.

Final cleanup at16:25:51UTC: namespace absent, zeroGKE nodes/TraderX VMs, fourMIG targets0, originalPVC UID/bindings preserved, original controllers0. RI30 untouched. Review and any later integration remain coordinator/user-owned.
