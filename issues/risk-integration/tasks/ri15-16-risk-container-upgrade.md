# RI15/16 task — Reviewed CPU risk-service container qualification

Updated: 2026-10-07. Status: reviewed; awaiting integration target and user approval. Author: codex-risk-reliability. Coordinator: current TraderX coordinator.

Parents: [RI15](../15-portfolio-service-and-risk-results.md), [RI16](../16-risk-job-lifecycle-and-worker-scaling.md). Scope: package reviewed service and existing serial worker; pin image/dependencies, persistent stores and CPU profile; prove container lifecycle/restart/identity. TraderX connected portfolio adapter/UI, broad financial conventions, worker fleet and original-engine activation are excluded.

## Authorization and routing

User approved the recommended two tasks and said `ok start those`; implementation/local disposable container proofs were authorized. Integration approval: pending. Integration repository/branch: to be agreed before incorporation; original Alex checkout remains outside authorized edits. No source merging is implied by local review acceptance.

Chat: `01a116fd-2fce-77f1-a561-3b03472f0722`, verified accessible. Model: gpt-6.1-sol High. Status: `coordination/eod-integration/status/codex-risk-reliability.txt`. Handoff: parent `HANDOFF-CODEX-RISK-CONTAINER-UPGRADE-20261008.md`.

Owned clone `/Users/yaakov/dev/lmax/jax-risk-container-upgrade`, branch `codex/risk-container-upgrade`, base `796ca5dfb9d9d95ffb125fdd29ee041e678e910e`. Engine implementation has no TraderX state owner; exact17packaging/doc/test paths only, engine Python/pyproject/constraints unchanged. Service incorporation dependency: reviewed f120072 then796ca5df. TraderX canonical accepted revision at dispatch: bf1d13e9.

## Progress and review

Candidate: `077ffacfc0de9c93caad9592184d73884bf3449b`. Build source:92024cd; immutable image `sha256:242068836a9453d7c732159e32bef3716790a9ded5bb975ca585d9384dd3921c`.

Coordinator review found no blocking issue. Independently repeated14actual-container groups;94build-source hashes/74author artifacts and exact image/OCI identity verified. Supplied835installed-image regression passes reviewed from836-case XML,0failure/error,1missing-reference skip,11slow deselected. No coordinator rerun of that unchanged suite claimed. All owned proof containers removed. Original/checkouts/staging preserved.

Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri16-container-coordinator-20261008/review.md`; raw proof `/private/tmp/ri16-container-coordinator-proof-20261008`.

## Live-rig disposition

[LR03](../live-rig/lr-03-risk-container-pipeline.md) StageA passed for named Linux/amd64-emulated DockerDesktop CPU/mount profile. Real four-position synthetic USD base pricing is separate from stub-price lifecycle overlays. StageB TraderX account/run/provenance-bound export/submission/intake is unexecuted, outside this packaging scope and blocked on accepted adapter/contract. Health proves API/storage, not worker-drain readiness; no fleet/general financial/performance/nativeGPU acceptance.

## Integration and closeout

Target selection and direct user integration approval pending. No integration commit/done date exists. After approved incorporation, verify unchanged reviewed image/runtime bytes or rebuild/requalify changed artifacts, update parent/spec/queue and move this bounded record to resolved. Keep LR03B and broad RI15/16 open. Do not copy packaging into TraderX's pricing implementation or write Alex's original checkout without explicit target authorization.
