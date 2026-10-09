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

## October 8 approved integration

The user approved console candidate `8f4623d26251292a629b2e6a1c40dd9632e428ad` (including `fcba84f4`) and archival proof candidate `055581fdef5b08bf24757384c8167723384b7d97` into `traderX-risk-integration`. The console packaging task is [done and archived](../resolved/lr05-console-packaging.md); the proof drivers remain historical fixtures for the closed October 8 lease. This entry supersedes earlier awaiting-approval/source-not-integrated statements for these two candidates only. Independent full-ancestry generation, source/render parity and scoped checks are recorded under parent `coordination/eod-integration/review-evidence/review36-20261008/integration/`. No rig/container was restarted. Existing native image/connected evidence applies to unchanged build-context bytes. Broader RI/LR release, authenticated Desk, revised amd64, cloud, retained recovery, financial and sizing obligations remain open.
