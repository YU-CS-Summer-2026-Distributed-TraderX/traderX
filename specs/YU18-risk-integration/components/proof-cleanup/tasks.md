# Proof cleanup tasks

Status: source implementation and offline acceptance complete; coordinator review pending.

- [x] FR-PC01/02: snapshot exact fields, optional absence and target/UID identity before mutation.
- [x] FR-PC03: actual runner supervisor, owned forwards/process group, failure and TERM/INT handling.
- [x] FR-PC07: count actual executions and terminal outcomes; single/final selected proof prerequisite failures stay failures after successful cleanup.
- [x] FR-PC08: default refusal for member image rollback; reviewed immutable writer/restore and retained-boundary evidence admission with unknown/missing/mismatch/incompatible/Ready-only/empty controls and explicit recovery. Positive artifacts are synthetic fixtures; no real compatibility producer or proof supplied.
- [x] FR-PC04: exact restore, partial preparation, non-default replicas, absent env/probes, verification and member-readiness retry.
- [x] FR-PC05: durable unfinished record, inherited lease, explicit idempotent recovery, wrong-target and replacement refusal.
- [x] FR-PC06/NFR-PC01: storage inspection, explicit reset fence/intent, cleanup without PVC/SQL reset, bounded commands and private journal.
- [x] Exercise the unchanged a0d6da0b runner to reproduce stranded interrupted prep.
- [x] Test success, failed prep/proof, absent optional deployment, TERM/INT, SIGKILL, cleanup interruption/failure, lease refusal, target/UID refusal, member-readiness failure and emptyDir/PVC-retention/reset refusal.
- [ ] Coordinator review and shared index reconciliation.
- [ ] Separately authorized live cluster and retained-state compatibility acceptance with reviewed RI-18 images.

Executable trace: `scripts/tests/proof-cleanup/test_cleanup.py` invokes the copied actual runner/helper and a controlled proof fixture. Its `fake-command.py` implements a private disposable Kubernetes CLI double. Expected restoration compares the entire independently constructed original fixture, not a reimplementation of the helper's restoration projection. The baseline arm invokes the original runner from the exact reviewed Git revision. These are source/process acceptance checks; real STP assertions are unchanged and have not been rerun live by this lane.
