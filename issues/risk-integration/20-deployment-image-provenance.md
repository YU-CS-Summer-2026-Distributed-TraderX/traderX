# RI-20 — Deploy and proof paths do not verify intended image contents

Updated: 2026-10-07. Status: in progress (offline admission integrated; deployment provenance/pinning remains). Maintainer: coordinator. Integrated source revision: `b0b4c33276c93c9aae545019320d606f7f0d0c2d`.

## Current finding

A baseline proof suite skips node tag resolvability unless rebuilding. Legacy GKE deployment selects an old state/context without a rendered-image provenance guard. Historical live tag mismatches are not current observations.

Verified by current-source review; local reproductions and limits are recorded in [October 7 triage](open-issue-triage-2026-10-07.md). Historical runtime observations remain historical.

## Work

- [ ] Check every selected member/consumer image resolves where it will run, independently of whether a rebuild happens.
- [ ] Verify rendered deployment images against intended component/state/build provenance, including deliberately inherited components.
- [ ] Replace stale default-context assumptions with explicit target identity and refuse ambiguous artifacts.
- [ ] Reuse RI18 content provenance; do not duplicate its fingerprint contract.

## Acceptance

- [ ] Offline rendered-manifest and node-inventory fixtures exercise matching, missing, stale and inherited-image cases.
- [ ] Actual GKE deployment verification waits for credits and explicit authorization.

## Original reports

- [a-suite-that-does-not-rebuild-never-checks-the-members-image-is-resolvable.md](../open/a-suite-that-does-not-rebuild-never-checks-the-members-image-is-resolvable.md)
- [gke-deploy-path-has-no-image-guard.md](../open/gke-deploy-path-has-no-image-guard.md)
- [the-manifests-pin-a-build-the-rig-no-longer-runs.md](../open/the-manifests-pin-a-build-the-rig-no-longer-runs.md)

Next: Supply reviewed immutable deployment artifacts/provenance, then perform separately authorized rollout verification.

Scope: local source/test work only when assigned. No push, deployment, retained-rig mutation or broad worktree propagation.

2026-10-07 dispatch: isolated offline intended-artifact/render/reference checks; reviewed RI17/18 dependencies may be stacked only in owned checkout. No actual Kubernetes/GKE deployment or live node inspection. Shared HANDOFF-CODEX-IMAGE-ADMISSION-20261007.md; exact checkout/base stack CLAIM pending.

2026-10-07 offline milestone reviewed at `5787073c`:47 independent controls pass,48artifact+13source hashes match. Intent/reference/render checks accepted in stated trusted-local/offline scope. Current realmutable refs still refuse; no actual image pin/build/deploy, retained compatibility or live target proof. Controlled integration pending; dependency originals/duplicates must be reconciled. Evidence `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri20-coordinator-20261007/review.md`.

## October 7 integration outcome

Offline intended-image/reference admission is integrated, including explicit target/state/artifact binding, local render closure and checks on each node before writes. Combined tests: 47 passed. Current mutable image pins and missing per-component provenance still refuse; pin/build/rollout and live verification remain open.

Combined validation: 213 generated Java cases (including five allocation gates), 142 generated publisher/console cases, 171 operational-script cases, 13 OOM checks and 16 memory checks passed. The console production build and five repository gates passed. These are scoped local/source/generated results, not live HA, deployment-profile performance or financial acceptance. Evidence: shared coordinator `review-evidence/class-window-integration-20261007`.
