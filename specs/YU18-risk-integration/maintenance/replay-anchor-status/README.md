# Replay anchor status

Status: integrated locally, 2026-10-07; see the evidence and limits below.
Base: `a0d6da0bfbef481f301bb1172b6810cb8a4c9e3e`. This is the bounded replay subsection of RI21;
managed GKE startup, run identity, migration and engine/SQL disaster recovery remain queued.

Authoritative source: root `scripts/yu15/lib-replay-epoch.sh` and `replay-anchor-evidence.py`.
Callers: `start-cluster-kind.sh`, `run-proofs.sh`'s `rebuild_fresh_epoch`, and
`scripts/proofs/yu17-taq-replay.sh`'s explicit restore and EXIT cleanup. The proof cleanup now
returns failure if restamping fails, while preserving an earlier nonzero exit. The after replay
proof refuses disabled stamping before queries because it must restore its temporary clock change.

Read [spec](spec.md), [plan](plan.md), and [tasks](tasks.md). State generation remains in the
[state parent](../../README.md); [component rules](../../../../docs/spec-kit/state-components.md)
apply. These root operational scripts are sourced directly, not composed runtime overrides.
Adding this component does not activate a service or change a deployment.

## Operator contract

```bash
K=(kubectl --context chosen-context -n chosen-namespace)
source scripts/yu15/lib-replay-epoch.sh
stamp_replay_epoch
```

The existing simple string form (`K="kubectl --context chosen-context -n chosen-namespace"`)
also works. Only the kubectl executable, one explicit context and one explicit namespace are
accepted. Indexed arrays support shell quoting; target values must use the documented safe token
characters (context: letters, digits, dot, underscore, colon, slash, at sign, dash; namespace:
Kubernetes DNS label). Duplicate flags, unknown options, associative arrays and malformed prefixes
refuse before queries. Ambient `CTX`, `NS` and kubectl current-context do not choose the target.
Explicit context names select a kubeconfig entry; this helper does not authenticate that endpoint.

Stamping is required by default. The selected member-0 pod must mount the documented writable
member-0 PVC at cluster-node's `/data`, with `CLUSTER_BASE_DIR=/data`, no shadowing/subpath, and a
bound PV whose claimRef matches the claim's name, namespace and UID. Missing, invalid, unused or
unreadable evidence refuses before ConfigMap creation/apply. Reading PV evidence may require
additional read permissions. No `emptyDir`, pod-start or current-clock fallback exists.

A deliberate `REPLAY_ANCHOR_MODE=disabled` returns zero with status `disabled` and performs no
queries or writes, after validating the target. It asserts no verified anchor, fresh engine epoch,
tape-disable or synthetic behavior. Refusal is never interpreted as this operator choice.

`REPLAY_ANCHOR_STATUS` is `unavailable`, `disabled`, or `stored`. `REPLAY_ANCHOR_PRODUCER` is
`uninspected`, `absent`, `present`, `restart-failed`, `rollout-failed`, or `rollout-complete`.
Apply success reports a storage-derived anchor; restart/rollout failures still return nonzero
with status `stored`. A successful `--ignore-not-found` empty Deployment read reports confirmed
absence, separately from a failed read. A stored anchor with absent producer returns zero and
reports no rollout. Disabled mode and stored/absent are distinct explicit outcomes.

The anchor is the PVC creation timestamp converted to Unix milliseconds (UTC seconds or up to
six fractional digits). This records storage creation, not authenticated engine epoch minting.
Restamping identical evidence preserves the value; it may restart the producer again. ConfigMap
apply and rollout success do not establish replay position or financial validity. Concurrent
storage changes between reads are outside this helper's snapshot of API evidence; the operator
must retain the rig boundary during recovery. No new recovery/wipe permission is granted.

## Offline validation

Run `python3 scripts/tests/replay-anchor/test_anchor.py`. Commands are synthetic fixtures and
never delegate to real kubectl, kind, docker or gcloud. Full kind-start caller execution and
verbatim runner/restore/cleanup function paths exercise failure propagation. Extracted runner
functions stub unrelated preparation; this is not a full proof-runner lifecycle or live cluster
proof. Reviewed RI17/RI18 contracts are preserved; RI20 is not a stacked dependency.

October 7 integration: combined source/generated checks passed in the coordinator candidate. The scoped implementation is integrated locally; original delivery notes retain their dates. Evidence is recorded in the shared coordination review directory `class-window-integration-20261007`. Live deployment, retained HA, performance and financial acceptance are not inferred from these checks.
