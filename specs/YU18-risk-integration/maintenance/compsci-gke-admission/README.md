# CompSci GKE cold-demo admission

This diagnostic owns the explicit CompSci deployment profile's storage-access and
source/payload admission tooling. Runtime images remain the recorded `6156ad22`
dependency; the prepared source/configuration candidate has its own revision.

The tier adapter reuses RI20 parsing, target/digest/slot rules and independent
full-ancestry generation. It adds two explicit providers: compiled build contexts
(including the root console) and locked upstream OCI images. It never supplies
RI18 labels to images built by a different producer. The legacy admission path
and its refusal rules remain unchanged.

Admission is read-only and emits a frozen manifest snapshot. It does not grant
canonical integration, application rollout, retained recovery, public access or
production performance acceptance. See [spec](spec.md), [plan](plan.md),
[tasks](tasks.md) and [deployment guide](../../../../docs/deployment/compsci-gke.md).
