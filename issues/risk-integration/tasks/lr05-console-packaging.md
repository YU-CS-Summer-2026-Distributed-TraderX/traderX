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

## Platform correction follow-on, 2026-10-08

Explicit reviewed dependency: fcba84f498fd013f889167269c70a409928459bb, verified clean before extending the same owned branch. The human-authorized coordinator routed the actual arm64-kind incompatibility through `HANDOFF-CODEX-LR05-CONSOLE-PLATFORM-20261008.md`. The prior genuine amd64 image remains valid only for its tested architecture. A subsequent 0ada3b1bd51fc7ad805ba72529028c13c12640e7e239df4a8d998685223bf209 image had an arm64 label but x86-64 runtime binaries; it is a retained negative control, not a native artifact.

The Dockerfile now uses the official multi-platform Cloud CLI stable index pinned at c631b05e8121048b29c9917bdc0a9fadd76d012f04f180a4409ec2ee534e832e. Native Node comes from Debian apt and kubectl from Google's signed apt repository. The test copies installed executables out of a never-started container, checks ELF e_machine and follows PT_INTERP to verify dynamic loaders before startup. The installed graph/HTTP smoke remains, with offline gcloud and kubectl startup added. Base anonymous config volumes are removed with owned containers.

Build: `docker build --platform linux/arm64 --pull=false -t traderx/console:lr05-platform-arm64-20261008-01a0f327 web-front-end-console`. Candidate image/index: sha256:58a6eb7ae4c71ef7dd4106fa440bc05c079f51a4a1eae464e957734ccd4bae4f. Platform manifest: sha256:d29c1b8bb96c3ae40fbeb8de5892d1c6f520a070ab912316a500ef006e82a9a4. Config: sha256:f216e9368a09b3e208ad5a4a473216d58c8edda41ed168a3293ab0ce564ef1ae. All inspected binaries and the loader are ELF machine 183 (AArch64). The base's selected arm64 manifest is d532f52185d74e4fb763ceaa15c0190372ef33ae3390b5b6bb144b060e228444; no InvalidBaseImagePlatform warning occurred.

Evidence directory: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/lr05-console-platform-20261008/`. Native arm64 positive: 19 checks passed. Retained genuine amd64 control: 19 checks passed with ELF machine 62. Mislabeled 0ada image: expected failure at the installed-ELF check before server startup, retaining all five inspected binary/loader hashes. Each run removed its owned container/anonymous volumes and temporary copies. Actual gcloud version 588.0.0 and kubectl v1.35.9-dispatcher started offline. No bridge connectivity or live pod qualification follows from that.

The old build/image/deployment failures remain preserved in their original evidence locations. The revised Dockerfile was built only for arm64; the amd64 control uses the earlier Dockerfile. Source integration, revised shared indexes and live-owner image acceptance remain pending; this worker did not modify the rig.

Final comment-corrected Dockerfile was rebuilt into unique tag `traderx/console:lr05-platform-arm64-final-20261008-01a0f327`; final candidate index is sha256:59116758b3200efa263cee23d7325ed31bc5a846003fa4f7d6eaff31e1d0b699. Its platform manifest and config match the first native build above; only the attestation/index identity differs. `build-arm64-final.log` and `positive-arm64-final/` preserve the exact-source final build and smoke. No earlier tag was overwritten.
