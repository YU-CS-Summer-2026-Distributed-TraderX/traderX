# GKE member runtime tasks

- [x] `order-matcher-archive` StorageClass and the generic ephemeral `/data` volume (lifecycle superseded by ADR-087).
- [x] StorageClass emitted by the CompSci render.
- [x] `-Xms12g -Xmx12g -XX:MaxGCPauseMillis=50` on both StatefulSet variants.
- [x] `ClusterNodeMain` closes the barrier and exits 143; `ClusterNodeShutdownTest`.
- [x] A SIGTERM during the startup election exits 143, not halt(70) (e3adcf9b); the test signals inside the election.
- [x] Promtail in the CompSci render.
- [x] Admission population 21 images / 24 slots, defined once in `admit.py`.
- [x] `/ready` refuses until the member's election is CLOSED (FR-GMR-08); `ReadinessDiagnosticsTest`.
- [x] Member `/data` on a retained per-member claim, and the render refuses an ephemeral one (FR-GMR-01, ADR-087).
- [x] No negative DNS caching on member JVMs (FR-GMR-09); `MemberDnsCacheTest`.
- [x] `yu18-minority-no-commit.sh` freezes the minority; `yu18-member-disk-survives.sh` for the disk-loss path.
- [x] Production moves from ephemeral to persistent claims: the roll plan in ADR-087 §4, done in roll 4 (2026-10-28, 11:05–11:08 am ET; `docs/deployment/traderx-demo-rollout-20261026.md` §13).

## Still open

- The archive purge sidecar is not wired into the GKE tier (ADR-079 §6). With retained claims the archive grows
  for the life of the claim, not of the pod.
