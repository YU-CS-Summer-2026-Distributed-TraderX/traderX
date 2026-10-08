# LR05 console runtime packaging correction

Updated: 2026-10-08. Status: ready for coordinator review; source integration pending. Author: Codex console packaging lane. Coordinator: current EOD integration coordinator.

Parent: [LR-05 deployment artifacts](../live-rig/lr-05-deployment-artifacts.md), [RI-20/08](../20-deployment-image-provenance.md). Contract: [console runtime packaging](../../../specs/YU18-risk-integration/maintenance/console-runtime-packaging/README.md).

## Authorization and routing

Human-authorized correction dispatched through the 2026-10-08 LR05 console-packaging handoff. Scope is the missing runtime module and focused installed-image regression. No UI, auth, Desk order, engine, financial, peer-checkout or rig changes.

Author chat: 01a0f327-6dbb-7331-bad8-00faf7478c0c. Model/reasoning inherited from the active chat, not independently reported. Local status: `coordination/eod-integration/status/codex-console-packaging.txt` under the parent workspace. Transport: immutable local board messages; coordinator polls. CLAIM posted 2026-10-08T13:39:29Z before edits.

Checkout: `/Users/yaakov/dev/lmax/traderX-lr05-console-packaging`. Branch: `codex/lr05-console-packaging`. Accepted base: e274df81ff82bbba65238bd193c7a0dc54215f76. Operative owner: root `web-front-end-console/Dockerfile`. Other claimed paths: `scripts/test-console-image.py`, this record and the four maintenance-pack documents. Integration target: canonical `traderX-risk-integration`, only after direct human approval. Approval pending.

## Progress and review

The original image sha256:cf04a3fca5f0165afefaf8f54391e62e4f685c02129dc5b7487422c9aaf84058 exits 1 with `ERR_MODULE_NOT_FOUND /app/desk-orders.mjs`. The helper failed as expected; logs retain that exact error. The correction adds one filename to the explicit COPY list.

Build command: `docker build --platform linux/amd64 --pull=false -t traderx/console:lr05-packaging-20261008-01a0f327 web-front-end-console`. Build succeeded. Existing cache was used where available; apt/runtime installation and Angular compilation ran. Base identities are captured in the build log; this is not a claim of bitwise rebuild reproducibility.

Candidate Docker image identity: sha256:59bf0ed33d4830f42ec61573537f7c64431275a5c967088bd552f2a91d23065d. Build manifest: sha256:879c3957de2c9047c85be3ef31eb184a026732fa569db2a5e73cde59ec5c462e. Config: sha256:d974c3f031244b37a93ca057ed5eed7c62c2b3d17e4af09e99746d23a8710f79. Architecture: amd64.

`python3 scripts/test-console-image.py IMAGE --evidence NEW_DIRECTORY` ran against the original and candidate. Candidate passed 14 assertions, including all seven source/installed module hashes, default command startup, `/healthz`, Angular index and both JS bundles, signed-out `/auth/me`, local `/gcs/list` refusal, and presence of gcloud/kubectl. Runs used read-only 256 MiB/one-CPU containers, ephemeral loopback ports, no source mounts/credentials and LOCAL_ONLY=1. Both owned containers were removed. The candidate image is retained for review.

Local evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/lr05-console-packaging-20261008/` contains `build.log`, `negative-original/{report.json,container.log}` and `positive-fixed/{report.json,container.log}`. Candidate commit and artifact hashes are recorded in the immutable READY board message. Coordinator independent checks and review verdict: pending.

## Live-rig disposition

This proves installed-image startup and focused local HTTP behavior. It does not prove a deployed pod, Kubernetes/cloud bridge access, browser flows or order handling. The live rig owner alone may load and roll the reviewed candidate, verify actual image contents and record console endpoint results against [LR-05](../live-rig/lr-05-deployment-artifacts.md). No rig was changed by this lane.

## Integration and closeout

Direct human approval, resulting integration commit, shared maintenance-index update and live-owner acceptance remain pending. This bounded correction does not close the broader deployment artifact, GKE or financial-validation obligations. No push, cloud operation or cross-worktree propagation was performed. Worktree changes are confined to the claimed paths.
