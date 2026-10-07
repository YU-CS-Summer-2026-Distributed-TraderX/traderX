# Proof cleanup

Status: integrated locally, 2026-10-07; see the evidence and limits below.

The proof runner records the rig configuration before preparation and returns that configuration when restoration is safe, including on ordinary exit, proof failure and catchable termination. A private durable journal makes unfinished work visible after SIGKILL or host loss. Cleanup preserves PVCs and refuses unsafe or mismatched recovery targets. Different member images require separately reviewed retained-state compatibility evidence before cleanup can stop, patch or start members.

Source: `scripts/yu15/run-proofs.sh` and `scripts/yu15/proof-cleanup.py`. These are repository operational tools, invoked directly, with no runtime override or generated service copy. Earlier branches are unchanged. The [state component rules](../../../../docs/spec-kit/state-components.md) govern this pack; shared generation remains at the state parent.

Run source acceptance:

```bash
python3 -m unittest discover -s scripts/tests/proof-cleanup -v
bash -n scripts/yu15/run-proofs.sh
```

Existing proof selection, IMAGE_PRE/IMAGE_FIX and the STP assertions remain in their current owners. RI-18 owns image-content admission and the STP builder/proof files. This component proves cleanup configuration behavior with fake CLI/process fixtures. It proves no matching, financial, cluster recovery or image compatibility result.

The journal defaults to `$XDG_STATE_HOME/traderx/proof-cleanup/<context-and-namespace-hash>.json`, or `~/.local/state/traderx/proof-cleanup/` when XDG_STATE_HOME is absent. `PROOF_CLEANUP_JOURNAL` selects an explicit durable path. `PROOF_LOG_DIR` redirects runner logs into a dedicated evidence directory. Journals are private local artifacts, mode 0600; they contain configuration and must not be published. Use the same journal path and context for subsequent invocations.

Recovery after an unfinished run is explicit:

```bash
CTX=kind-traderx-yu12-cluster NS=traderx \
  python3 scripts/yu15/proof-cleanup.py recover
```

If the supervisor was killed while its runner still lives, the inherited journal lease refuses recovery. Stop only the verified owned runner/helpers first; the recorded runner PID is evidence to inspect, not permission to kill an arbitrary current process with a reused PID. Recovery checks context, namespace, server/cluster fingerprint, namespace UID and every captured workload UID before any mutation. Changed targets, replaced workloads, emptyDir data mounts or PVCs deleted on scale-down require manual reconciliation.

Retained member rollback is blocked by default. The journal records possible member writer images before reset and before STP selects its pre/fix roles. Unknown writer history and mutable image tags cannot identify a compatible boundary. Even immutable identity from RI-18 establishes content provenance only; it does not authorize rollback.

`PROOF_RETAINED_COMPATIBILITY` may name an independently reviewed local proof manifest. The journal's `requiredCompatibility` describes the exact request: journal UUID, target identity, member UID, all relevant retained PVC names/UIDs, every recorded writer image and the restore image by container. Every image must be pinned with `@sha256:`. The review manifest uses schema `traderx-retained-restore-review-v1`, the exact `request`, verdict `compatible`, a nonempty `reviewedBy`, absolute `evidencePath` and the reviewed bytes' `evidenceSha256`. Its hashed JSON evidence uses schema `traderx-retained-restore-evidence-v1` and the same request. Each writer/reader pair needs one case with `writerImage`, `readerImage`, result `compatible`, a positive integer `assertions`, and true `checks.snapshotRestore`, `checks.logTailReplay` and `checks.stateEquality`. Missing, duplicate, stale, mismatched, incompatible or readiness-only evidence refuses.

This is a trusted local review-artifact interface, not reviewer authentication or a new compatibility-proof producer. The reviewer must inspect the independently executed snapshot-plus-tail/state comparison before selecting its artifact; names or metadata cannot substitute for that review. No real producer or retained acceptance is delivered by this milestone. Tests supply explicitly synthetic fixture artifacts only. Real member restoration remains refused until independently verified evidence is available. `ALLOW_IMAGE_CHANGE` and `ALLOW_PROOF_RESET` remain authorization fences, not compatibility proof.

On compatibility refusal, members keep their current image/count, client Deployments stay quiet, independent observability settings can still return, and the journal remains unfinished with pending resources. Retry refuses again until matching review evidence is supplied; identity/boundary checks repeat before member stop, image patch and restart. There is no reset fallback. Non-image configuration restoration retains its independent cleanup path.

An epoch reset in preparation additionally requires `ALLOW_PROOF_RESET=1`, alongside the existing image-change fence where applicable. This records irreversible engine/PVC/projection effects. It does not authorize a retained rig or restore an earlier trading state. Cleanup never deletes PVCs, resets SQL, seeds fixtures or restores a replay anchor from a destroyed epoch. Authorized proof data effects remain data effects; configuration restoration is reported separately.

The terminal result reports `proof_exit` and `cleanup_exit`. A successful proof with failed cleanup exits 70; a failed or signaled proof retains its original runner status while exposing cleanup failure separately. Preparation attempts, actual executions and terminal outcomes are counted separately; a failed prerequisite on the final selected proof is still a failed proof outcome. Individual proofs report `PROOF_OK (cleanup pending)` until the supervisor finishes. Cleanup has one 660-second command budget, based on the existing 600-second rollout wait plus request overhead. Budget exhaustion retains the journal for a later scoped retry.

See [requirements](spec.md), [implementation](plan.md), [acceptance trace](tasks.md) and [maintained RI-17 issue](../../../../issues/risk-integration/17-interrupted-proof-cleanup.md).

October 7 integration: combined source/generated checks passed in the coordinator candidate. The scoped implementation is integrated locally; original delivery notes retain their dates. Evidence is recorded in the shared coordination review directory `class-window-integration-20261007`. Live deployment, retained HA, performance and financial acceptance are not inferred from these checks.
