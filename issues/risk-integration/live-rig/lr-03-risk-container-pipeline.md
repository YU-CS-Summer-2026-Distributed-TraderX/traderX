# LR-03 — Risk container lifecycle and TraderX connection

Updated: 2026-10-14. Status: Stage A passed for corrected C8 image/profile, owned integration complete; Stage B blocked/unexecuted. Coordinator maintains acceptance; history retained.

Parent work: [RI-15/16](../15-portfolio-service-and-risk-results.md). This entry tracks acceptance evidence; it is not a separate implementation contract.

## Target and revision

Rig: Owned disposable CPU container and volumes; then disposable TraderX-to-service pipeline.

Current approved owned target: `e770f912baed47214220ce92e3de99a876781c68` in `/Users/yaakov/dev/lmax/jax-risk-container-upgrade`; corrected image `sha256:80c885f8305067421a4c34af3eeef95ab1eca5a38376e9dcc689f8fe8bd5483d`, build `98367871`, upstream C8. Historical 077/image24206883 acceptance remains below.

## Existing evidence

49 focused reliability cases reviewed in an isolated engine clone. Earlier EOD container/pipeline proof covers an older accepted profile, not this broader worker image.

## Required live scenario

Stage A: actual built image HTTP/API plus worker; same-key identity/conflict, overlapping submissions, lost response recovered by GET, worker-launch failure and restarts retaining queue/results/cache. Stage B: TraderX exports a supported synthetic request, submits once, polls and intakes the actual engine result with correct account/run and provenance.

## Acceptance and failure controls

Separate lifecycle stubs from real synthetic pricing. Observe actual execution ownership and retained durable job identity; no blind POST retry. Stage A may be completed by the active container lane. Stage B requires the agreed portfolio adapter/contract and must remain open if only container tests pass. Record image/content/dependency pins and mount/cleanup evidence.

## Dependencies

Stage A corrected profile is reviewed and incorporated into its approved owned engine target; Stage B still requires accepted request/result capability and the TraderX adapter. No new financial convention or original-engine activation implied.

## Result record

- Claim/worktree/runner: author C8/R1 qualification; approved owned engine integration after RI20 release, no new runtime lease.
- Tested revisions/images: C8 service candidate `e770f912baed47214220ce92e3de99a876781c68`, measured `sha256:80c885f8305067421a4c34af3eeef95ab1eca5a38376e9dcc689f8fe8bd5483d`, build98367871; exact case identities/mounts/commands in R1 evidence.
- Outcomes: author16 actual-container groups and880 installed-image passes reviewed; coordinator139 focused checks/HTTP control/image identity verified. Unchanged runtime context97 hashes rechecked at incorporation.
- Verdict: Stage A passed for corrected named profile; Stage B unexecuted/blocked.
- Cleanup: author owned resources removed; no new container for byte-identical incorporation. Stage B, native/GPU/fleet/production and general financial acceptance remain separate.

Keep failed and inconclusive attempts with their evidence. Complete only the scenario actually exercised; record any untested tier separately.

## October 7 coordinator acceptance

Stage A passed for image `sha256:242068836a9453d7c732159e32bef3716790a9ded5bb975ca585d9384dd3921c`, OCI build92024cd, service796ca5df and packaging077ffac. Coordinator independently repeated14actual-container groups;835installed-image regressions were reviewed from supplied nonzero XML (one missing-reference skip,11slow deselected). Linux/amd64 on ARM64 Docker Desktop emulation,2CPUs/2GiB/noextraswap/256PIDs/nonroot/read-only root, explicit durable mounts. Real four-position synthetic USD base pricing is separate from stub-price crash/lifecycle overlays. All owned containers removed.

Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri16-container-coordinator-20261008/review.md`; raw owned run `/private/tmp/ri16-container-coordinator-proof-20261008`. No deployment/nativeGPU/fleet/general financial/worker-drain-readiness claim. Stage B remains unexecuted: this did not exercise TraderX export, account/run/provenance-bound adapter or result intake. Original engine incorporation and connected API contract remain separate.


## October 9 corrected C8 acceptance and integration

Current image80c885f8 (full identity above) supersedes the first C8 candidate26/imageeecd only for the reviewed correction. Candidate26 failed a synthetic same-current-key/unknown-bound-contract invariant; the earlier15-group observations and that failure remain preserved separately. R1 returned409 without mutating historical rows; fresh current key priced. Coordinator independently reran139 focused checks and the HTTP control, verified73 evidence/97 runtime hashes and inspected image metadata. Author880 installed-image tests and16 actual-container groups were reviewed, not independently repeated by coordinator.

Approved owned target fast-forward077->e770f912 preserves history and exact measured runtime bytes. No original Alex incorporation, TraderX connected portfolio pipeline, deployment or financial blanket approval. Queue/EOD/cache/input mounts, CPU2/2GiB/PIDs256/nonroot/read-only and Linuxamd64-emulated scope remain exact. Lifecycle placeholders are separate from real synthetic pricing/calibration. Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri16-upstream-c8-r1-20261009/review.md`; integration evidence in parent coordination/ri16-c8-integration-20261009. Stage B remains blocked on accepted account/run/provenance-bound portfolio adapter/contract.

## October 14 separate local CPU profile

[Local CPU portfolio task](../resolved/local-cpu-portfolio-integration-20261014.md) is integrated at `c249adc5` from approved `b9fb0843`; human approved retaining engine `270a907a` as a separate owned local CPU profile. Actual installed macOS arm64 CPU HTTP/subprocess-worker proof completed eight direct synthetic Treasury jobs/1,416 positions, with restart/lost-reply GET recovery, persisted content binding and mismatch refusal. Coordinator independently checked the client/refusal paths and saved CPU intakes; final engine and actual-loop evidence is linked from the resolved task. Owned processes stopped.

This is additional local-process acceptance only. It neither replaces nor expands the C8 image Stage A record above. Stage B still requires sequenced TraderX export plus accepted account/run/provenance-bound adapter and result intake. Container qualification of `270a907a`, general financial correctness and future exposure numerical oracles remain unproved; no GKE/deployment or broad LR closure occurred.
