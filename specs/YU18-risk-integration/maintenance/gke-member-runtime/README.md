# GKE member runtime

Status: integrated, 2026-10-25 to 2026-10-27. Decisions: [ADR-081](../../system/adr-081-gke-member-runtime.md),
[ADR-087](../../system/adr-087-member-persistent-volumes.md) (persistent member volumes; code only, not yet rolled).
The book-capacity guard sized against this heap is ADR-079 §6.

Sources:

- `specs/YU17-otc-rates/generation/kubernetes/cluster/gke/storageclass.yaml`, `statefulset-emptydir.yaml`,
  `statefulset.yaml`, `kustomization.yaml`: the archive volume, heap and pause goal.
- YU18 `generation/runtime-overrides/order-matcher/.../cluster/ClusterNodeMain.java` and
  `ClusterNodeShutdownTest.java`: the SIGTERM exit; `ReadinessDiagnosticsTest.java`: the catch-up readiness gate.
- `scripts/gcp-compsci/prepare.py`, `test_prepare.py`, `admit.py`, `prepare-admission.py`,
  `admission-controls.py`: the CompSci render (StorageClass, promtail) and its admitted image population.

See [spec](spec.md), [plan](plan.md) and [tasks](tasks.md).
