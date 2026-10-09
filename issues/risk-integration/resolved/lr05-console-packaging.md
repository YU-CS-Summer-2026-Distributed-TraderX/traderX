# LR05 task — Console runtime module packaging

Updated: 2026-10-08. Status: done after human-approved canonical integration and scoped verification. Parents: RI11/RI20; broader live entry LR05 remains open.

The dated delivery history below is preserved. Its pending-approval statements describe earlier stages and are superseded by the approved integration outcome at the end.

The existing Desk author used a fresh owned checkout at traderX-lr05-console-packaging, branch codex/lr05-console-packaging, base e274df81. Candidate fcba84f498fd013f889167269c70a409928459bb is clean. The authoritative root Dockerfile now explicitly copies desk-orders.mjs. Other changes are the installed-image regression and four maintenance documents; application semantics and authentication are unchanged.

Original exact console image cf04a3fc failed with ERR_MODULE_NOT_FOUND /app/desk-orders.mjs. Both server.mjs and desk-session.mjs import it. Coordinator independently reproduced that failure and tested the fixed exact image: 14 assertions pass, seven installed static ESM module hashes match source, 88 source context hashes verified, owned test container removed. This is actual isolated container evidence, not connected Desk or Kubernetes acceptance.

Candidate image: traderx/console:lr05-packaging-20261008-01a0f327, Docker identity sha256:59bf0ed33d4830f42ec61573537f7c64431275a5c967088bd552f2a91d23065d, linux/amd64. The sole live owner received a bounded handoff to load and roll only this candidate console on its leased disposable rig, preserving the accepted failure and candidate verdict separately.

Evidence: parent coordination/eod-integration/review-evidence/lr05-console-coordinator-20261008 and lr05-console-packaging-20261008. Handoff: HANDOFF-CODEX-LR05-CONSOLE-PACKAGING-20261008.md. CLAIM: 20261008T133929Z-codex-console-packaging-claim.txt. READY: 20261008T134722Z-codex-console-packaging-ready.txt.

Canonical source integration is not approved and has not occurred. The task is not done or resolved. The live window ends 17:10:47 UTC, with weekly remaining strictly above 50%; no push, cloud, retained rig or data mutation is authorized.

October 8 platform follow-on: the amd64 candidate cannot resolve through arm64 node CRI despite index load. Same-source target-arm64 build0ada3b1b warns its runtime base is amd64; coordinator read installed Node/kubectl/dash ELF headers, all x86_64 while config declaresarm64. Original author resumed a narrow Dockerfile/runtime-base and installed-platform regression correction, preserving bridges and application behavior. Explicit dependency is clean reviewedfcba84f4. No valid native artifact or successful candidate pod proof follows from build success or a target label. Handoff: parent HANDOFF-CODEX-LR05-CONSOLE-PLATFORM-20261008.md. No canonical integration.

October 8 platform correction reviewed: candidate8f4623d2 extendsfcba84f4 with pinned official multi-platform Cloud CLI runtime and native Node/kubectl packages. Exact arm64 image59116758 independently passed19checks, including actualAArch64 Node/shell/kubectl/Python/loaders and offlineCLI starts. Seven ESM modules match source;88context/12evidence hashes verified. Old genuineamd64 control passes; mislabeled0ada refused beforestart. NewDockerfile arm64 only built/tested. Sole rig owner received unique finaltag and exactindex/platformmanifest/config for candidate rollout. Canonical integration remains unapproved and unchanged. Evidence parent review-evidence/lr05-console-platform-coordinator-20261008.

October 8 connected candidate milestone passed coordinator review. Exactly one native candidate pod is ready with zero restarts; assigned-node CRI content matches image59116758. Actual console health, Angular HTML/scripts, signed-out refusal, three nonempty member health routes, gateway readiness and SQL reconciliation responses verified. Seven images and28 raw node comparisons checked. This is a connected startup/read-surface proof; authenticated Desk execution, real cloud bridge access and GKE remain outside this bounded packaging milestone. Candidate8f4623d2 still requires human integration approval into canonical. Evidence: parent review-evidence/live-rig-connected-review-20261008.

Final campaign closeout: native candidate connected acceptance is reviewed and both owned rigs released. Final349file evidence manifest verified. Candidate8f4623d2 remains reviewed and ready for canonical integration approval; no automatic merge or archive.

## Approved integration outcome

Human approval covers exact candidate `8f4623d26251292a629b2e6a1c40dd9632e428ad`, dependency `fcba84f498fd013f889167269c70a409928459bb`, and target `traderX-risk-integration`. The integrated runtime Dockerfile and installed-image regression remain byte-identical to the reviewed candidate. Final console build-context manifest matches all 88 files; previously reviewed image `sha256:59116758b3200efa263cee23d7325ed31bc5a846003fa4f7d6eaff31e1d0b699` therefore retains its 19-check native arm64 and connected startup/read-surface evidence. No new image or rig run is claimed.

Full YU18 ancestry was generated in the owned `traderX-review36-integration` checkout; operative source/render parity and scoped generated/source checks are recorded in parent `coordination/eod-integration/review-evidence/review36-20261008/integration/`. That evidence records the final canonical commit and preservation checks. The maintained pack is [console runtime packaging](../../../specs/YU18-risk-integration/maintenance/console-runtime-packaging/README.md); [LR05](../live-rig/lr-05-deployment-artifacts.md) retains deployment qualification limits.

The bounded missing-module/native-platform packaging correction is complete. Revised native amd64 qualification, authenticated Desk trading, real cloud bridges, trusted release provenance and GKE remain separate open work; this closeout does not resolve RI11, RI20 or all of LR05.
