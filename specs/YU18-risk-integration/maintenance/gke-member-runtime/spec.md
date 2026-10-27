# GKE member runtime specification

## Requirements

- FR-GMR-01: Each GKE cluster member SHALL mount `/data` from a generic ephemeral 32Gi `ReadWriteOnce`
  claim of StorageClass `order-matcher-archive` (`hyperdisk-balanced`, ext4, `WaitForFirstConsumer`,
  reclaim `Delete`).
- FR-GMR-02: The kustomization and the CompSci render (`prepare.py`) SHALL both emit the StorageClass,
  cluster-scoped, with no namespace.
- FR-GMR-03: Members SHALL run `-Xms12g -Xmx12g -XX:MaxGCPauseMillis=50` on G1, at a 16Gi memory
  request equal to the limit. `statefulset.yaml` SHALL carry the same JVM flags as `statefulset-emptydir.yaml`.
- FR-GMR-04: A member SHALL exit with status 143 within the grace period on SIGTERM, after closing its
  `ShutdownSignalBarrier`, archive and media driver, and SHALL log `Cluster node down` once they are
  closed. SIGTERM is delivered to the container's PID 1. Where a shell launches the JVM (kept off PID 1
  so a crash harness can SIGKILL it from `kubectl exec`), that shell SHALL forward TERM and INT to the
  JVM and SHALL exit with the JVM's status. The CompSci render (`prepare.py`) SHALL refuse a member shell
  that does not. Without forwarding, `sh` as PID 1 has no handler, and every delete or roll ends in
  SIGKILL (137) after the grace period.
- FR-GMR-05: The CompSci render SHALL include promtail (ConfigMap, ServiceAccount, ClusterRole,
  ClusterRoleBinding, DaemonSet). It SHALL name the cluster-scoped pair `promtail-<namespace>`, scrape
  `/var/log/pods/<namespace>_*`, and tolerate the `traderx-engine` taint.
- FR-GMR-06: CompSci admission SHALL expect the rendered population: 12 private images, 9 upstream
  images (21 in all) in 24 container slots. The counts SHALL be defined once, in `admit.py`.
- NFR-GMR-01: No engine, wire, snapshot or epoch change. `CLUSTER_HEARTBEAT_TIMEOUT_MS` stays 200 on
  the emptyDir tier.

## Success Criteria

- SC-GMR-01: `ClusterNodeShutdownTest` passes: a real one-member node in a child JVM exits 143 within
  30 s of SIGTERM.
- SC-GMR-02: `test_prepare` passes against an empty-root render, including
  `test_member_archive_storageclass_is_rendered` and `test_promtail_ships_this_namespace_from_every_node`.
- SC-GMR-03: An empty-root CompSci candidate contains the StorageClass, the promtail DaemonSet and
  exactly 21 distinct images in 24 slots.
