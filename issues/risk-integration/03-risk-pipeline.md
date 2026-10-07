# RI-03 — Operational risk pipeline and parallel portfolios

Updated: 2026-10-07. Status: in progress: synthetic one-portfolio container pipeline integrated; broader service/workers remain. Maintainer: coordinator; original delivery notes below retain their dates.

Existing foundations include local bundle/coordinator work, result intake and a Treasury demonstration. Do not recreate those as competing orchestration services.

- [ ] Map existing producer, coordinator, engine API, storage and UI responsibilities.
- [ ] Automate portfolio cut → verified market package → durable submission → status/recovery → validated immutable result → UI.
- [ ] Define remote artifact staging explicitly; a server-local bundlePath is not an upload protocol.
- [ ] Preserve job/workload/attempt identities, coverage, original result bytes and result eligibility.
- [ ] Prove one portfolio through the container with no manual re-entry of economics.
- [ ] Then add stateless execution workers for independent portfolios; keep durable jobs/results external to workers.
- [ ] Add bounded concurrency, ownership/leases as appropriate, retries, backpressure, cancellation and per-job resource limits.
- [ ] Add authenticated boundaries, useful logs/metrics and visible failure/recovery states.

Next: Upgrade the existing pipeline through RI15/RI16 after accepted contracts; do not recreate the delivered one-portfolio path.

2026-09-23: User authorized RI-03 Codex lane. App worktree/task creation queued as client-new-thread:cbd02f05-a6b5-40fe-a96c-3514e60b7851. Assignment HANDOFF-CODEX-RISK-PIPELINE.md; board 20260923T234357Z-coordinator-risk-pipeline-assignment.txt. Base d6ca3330. Single-portfolio local HTTP-container/intake/Risk-UI proof; four upstream contract failures retained; no cloud or parallel workers. Lane must post exact checkout CLAIM and directly message coordinator 01a0aa96-c6ad-7931-8ac5-bf456a7d750b on completion.

20260924T001602Z: Actual task `01a0d0a7-d1b9-71a3-9ab8-c430f2e48ef3`, worktree `/Users/yaakov/.codex/worktrees/b644/lmax`, branch `codex/risk-pipeline`, delivery `0641e24d`. Coordinator verified 39 evidence hashes and ran 10 adapter tests; reproduced missing dependency setup and contradictory signed/structural-zero outcomes accepted as verified. Integration held for two fixes. Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri03-coordinator-20260924/review.md`. Full single-portfolio proof is supplied evidence, not a new independent run; broader portfolios/distribution remain open.

20260924T003145Z: Corrections `107ed69d` accepted; code/docs from `0641e24d` + `107ed69d` integrated as `3244f7eb889dd0c893926915785c6899f3224160`. Coordinator independently verified 34 correction hashes,14 focused tests,152 source tests+CLI smoke and spec gates. Exact 23-file parity; existing dirty/user files preserved, no push/deploy. Original dated synthetic bill profile only. Next: resolve four engine defects and agree broader profiles/service recovery before parallel workers. Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri03-integration-20260924/review.md`.

October 7 status reconciliation: the top status and next action supersede earlier queued/implementation-held headers. Historical unchecked planning lists are not a claim that implemented milestones are absent. Remaining acceptance is not marked complete.
