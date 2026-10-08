# Reconciliation proof subject (RI-19)

Status: integrated locally, 2026-10-07; see the evidence and limits below.

The negative control in `scripts/proofs/yu05-recon.sh` selects a trade observed in both the current forward window and SQL. It mutates only that scoped identity, requires its exact id in a newly created processor pod's `FIELD_MISMATCH` log, and verifies clean classification after restoration. Older retained SQL rows never determine the subject.

Source: `scripts/proofs/recon-proof-subject.py`; acceptance: `scripts/tests/recon-proof/test_subject.py`. No production Java, generated runtime, wire or snapshot changes. YU18's operative `ReconciliationService.java` persists counters through `RunRegistry.java`; the proof saves/resets/restores only the selected scope's checkpoint while the sole processor replica is stopped. This is a proof operation, not a production recovery policy.

The existing full-history/cross-member checks remain. The orphan absent/named/removed checks now run in the same proof helper with checked JSON and exact identity membership. Each invocation uses a unique probe id and ownership nonce in the existing sourceorderid field. Insertion is atomically bound to the observed scope; cleanup reconciles an ambiguous response and deletes only matching original contents, including byte-exact text guards. A foreign, changed or replaced row is retained with a refusal. R1 corrects the fixed-id check/insert race reproduced during coordinator review. The orphan phase owns a private forward to the current ready processor pod because the subject control replaced the pod behind the caller's old tunnel.

Run `python3 -m unittest discover -s scripts/tests/recon-proof -p 'test_*.py' -v`. This runs the actual Bash proof with fake command endpoints and a real temporary SQLite projection. It reproduces the unchanged accepted-base failure and checks full table restoration, including an unrelated scope's checkpoint. No retained rig, real Kubernetes/MariaDB integration, generated image or financial behavior has been validated. Shared indexes remain coordinator-owned.

See [spec](spec.md), [plan](plan.md), [tasks](tasks.md) and [state component rules](../../../../docs/spec-kit/state-components.md) (repository root: `docs/spec-kit/state-components.md`).

October 7 integration: combined source/generated checks passed in the coordinator candidate. The scoped implementation is integrated locally; original delivery notes retain their dates. Evidence is recorded in the shared coordination review directory `class-window-integration-20261007`. Live deployment, retained HA, performance and financial acceptance are not inferred from these checks.
