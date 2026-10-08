# LR-03 — Risk container lifecycle and TraderX connection

Updated: 2026-10-07. Status: Stage A passed for named image/profile; Stage B blocked. Owner: unassigned validation lane; coordinator maintains queue.

Parent work: [RI-15/16](../15-portfolio-service-and-risk-results.md). This entry tracks acceptance evidence; it is not a separate implementation contract.

## Target and revision

Rig: Owned disposable CPU container and volumes; then disposable TraderX-to-service pipeline.

Candidate: accepted packaging077ffacfc0de9c93caad9592184d73884bf3449b, image24206883 (full identity below), service796ca5df. Pin the exact tested commit, generated source, image and configuration before execution.

## Existing evidence

49 focused reliability cases reviewed in an isolated engine clone. Earlier EOD container/pipeline proof covers an older accepted profile, not this broader worker image.

## Required live scenario

Stage A: actual built image HTTP/API plus worker; same-key identity/conflict, overlapping submissions, lost response recovered by GET, worker-launch failure and restarts retaining queue/results/cache. Stage B: TraderX exports a supported synthetic request, submits once, polls and intakes the actual engine result with correct account/run and provenance.

## Acceptance and failure controls

Separate lifecycle stubs from real synthetic pricing. Observe actual execution ownership and retained durable job identity; no blind POST retry. Stage A may be completed by the active container lane. Stage B requires the agreed portfolio adapter/contract and must remain open if only container tests pass. Record image/content/dependency pins and mount/cleanup evidence.

## Dependencies

Packaging candidate review for A; accepted request/result capability and TraderX adapter for B. No new financial convention or original-engine activation implied.

## Result record

- Claim/worktree/runner: pending.
- Tested revisions, images and rig identity: pending.
- Commands, actual outcomes, artifacts and negative controls: pending.
- Verdict: not executed for this entry.
- Cleanup and remaining qualification: pending.

Keep failed and inconclusive attempts with their evidence. Complete only the scenario actually exercised; record any untested tier separately.

## October 7 coordinator acceptance

Stage A passed for image `sha256:242068836a9453d7c732159e32bef3716790a9ded5bb975ca585d9384dd3921c`, OCI build92024cd, service796ca5df and packaging077ffac. Coordinator independently repeated14actual-container groups;835installed-image regressions were reviewed from supplied nonzero XML (one missing-reference skip,11slow deselected). Linux/amd64 on ARM64 Docker Desktop emulation,2CPUs/2GiB/noextraswap/256PIDs/nonroot/read-only root, explicit durable mounts. Real four-position synthetic USD base pricing is separate from stub-price crash/lifecycle overlays. All owned containers removed.

Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri16-container-coordinator-20261008/review.md`; raw owned run `/private/tmp/ri16-container-coordinator-proof-20261008`. No deployment/nativeGPU/fleet/general financial/worker-drain-readiness claim. Stage B remains unexecuted: this did not exercise TraderX export, account/run/provenance-bound adapter or result intake. Original engine incorporation and connected API contract remain separate.
