# Recovery identity tasks

Status: review needed; authorized local migration implemented and verified 2026-09-24. Owner: Codex RI-06.

- [x] Inventory and publish source/storage recovery matrix; reproduce receipt relabeling.
- [x] Implement bounded persistent receipt scope with compatibility/failure-ordering documentation.
- [x] Prove acceptance cases in source and generated tests, including process crashes/concurrency.
- [x] Prepare scoped delivery and hashed evidence for coordinator review; board release accompanies the commit.
- [x] Authorized extension: platform-issued persisted epoch, legacy trade-key migration, epoch-scoped positions/queries and distributed recovery/reconciliation proof.


## Expanded migration implementation

- [x] Stage 1: conflict-aware single/batch dedup; same-batch dedup; transaction-bound write lock; publish after commit. Six real MariaDB/JPA tests pass including concurrency/rollback, and affected unit suite passes.
- [x] Stage 2: persisted run descriptor and explicit legacy adoption; scoped projection schema/read APIs and publisher/reconciliation identity propagation.
- [x] Stage 3: explicit crash-safe fresh transition, producer receipt identity and retained consensus recovery proof.
- [x] Final generated/affected regression/parity: 174 runtime files identical; core 568 passed/6 existing skips, five allocation gates, focused identity 10, trade unit 98, position unit 11, MariaDB 14, EOD source/generated 167 each, tools source/generated 15 each.
- [ ] Coordinator review/integration. No production activation.

The real consensus proof and fault-injected SQL transition proof are separate. Managed UI rollout, missing-NATS-history repair, arbitrary member-loss HA and external financial validation are outside this delivery. See the runbook and hashed review evidence.

## Coordinator review corrections

- [x] M1: reproduce exact/case-equivalent legacy-v0/v1 epoch reuse on MariaDB; globally reserve order namespaces with SQL uniqueness and pre-prepare collation/prefix checks. Keep all historical IDs unchanged. Corrected suite19/19 includes accent collation, unattributed prefix and interrupted conflicting-schema upgrade.
- [x] M2: connect actual old/fresh Aeron nodes and gateways, production NATS paths, Spring HTTP controller/JPA and MariaDB; complete migration after a dropped committed freeze reply, reject premature activation, and verify exact first fresh IDs/positions plus unchanged nonempty old history. Delivered launcher passes1/1 without skips.
- [x] Affected unit regressions98/98; generated parity and source/spec gates rerun for correction delivery.
- [ ] Coordinator acceptance/integration of the corrections. No retained activation or UI rollout.
