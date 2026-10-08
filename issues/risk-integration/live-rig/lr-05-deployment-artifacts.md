# LR-05 — Intended image and deployment artifact verification

Updated: 2026-10-07. Status: blocked on reviewed immutable artifacts. Owner: unassigned validation lane; coordinator maintains queue.

Parent work: [RI-20/08](../20-deployment-image-provenance.md). This entry tracks acceptance evidence; it is not a separate implementation contract.

## Target and revision

Rig: Owned disposable local kind deployment first; GKE separately when available.

Candidate: Offline image-admission milestone integrated; actual deployment digests not yet supplied. Pin the exact tested commit, generated source, image and configuration before execution.

## Existing evidence

47 offline intended-artifact/reference controls passed in the earlier integration. No new live pod/node rollout proof follows from those checks.

## Required live scenario

Deploy the reviewed compatible artifact set to an owned disposable rig; compare intended manifests/provenance with resolved per-node and per-pod image contents. Apply an owned mismatched-image control and require refusal/visible failure.

## Acceptance and failure controls

Running pods are insufficient: verify actual digest/content, selected profile, expected services/endpoints and meaningful order/read-model behavior. Local kind proof cannot close the separate GKE rollout qualification.

## Dependencies

Built immutable artifact set with trusted provenance and explicit target; cloud deployment requires separate authorization.

## Result record

- Claim/worktree/runner: pending.
- Tested revisions, images and rig identity: pending.
- Commands, actual outcomes, artifacts and negative controls: pending.
- Verdict: not executed for this entry.
- Cleanup and remaining qualification: pending.

Keep failed and inconclusive attempts with their evidence. Complete only the scenario actually exercised; record any untested tier separately.
