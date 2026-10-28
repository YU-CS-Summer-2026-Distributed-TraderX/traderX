# GKE member runtime tasks

- [x] `order-matcher-archive` StorageClass and the generic ephemeral `/data` volume.
- [x] StorageClass emitted by the CompSci render.
- [x] `-Xms12g -Xmx12g -XX:MaxGCPauseMillis=50` on both StatefulSet variants.
- [x] `ClusterNodeMain` closes the barrier and exits 143; `ClusterNodeShutdownTest`.
- [x] Promtail in the CompSci render.
- [x] Admission population 21 images / 24 slots, defined once in `admit.py`.
- [x] `/ready` refuses until the member's election is CLOSED (FR-GMR-08); `ReadinessDiagnosticsTest`.

## Still open

- The archive purge sidecar is not wired into the GKE tier (ADR-079 §6).
