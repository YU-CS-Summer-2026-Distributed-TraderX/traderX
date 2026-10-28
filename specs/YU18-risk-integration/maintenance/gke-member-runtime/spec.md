# GKE member runtime specification

## Requirements

- FR-GMR-01: Each GKE cluster member SHALL mount `/data` from its own 32Gi `ReadWriteOnce` claim
  (`volumeClaimTemplates: data`) of StorageClass `order-matcher-archive` (`hyperdisk-balanced`, ext4,
  `WaitForFirstConsumer`, reclaim `Delete`), retained when the StatefulSet is scaled down or deleted
  (`persistentVolumeClaimRetentionPolicy` Retain/Retain), so a member returning, restarting or rescheduled to
  another node of the pool reads its own log. The CompSci render (`prepare.py`) SHALL refuse a member whose
  `/data` is not such a claim. A volume deleted with its pod let one scale-down destroy a majority of the logs
  (ADR-087).
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
- FR-GMR-07: The GKE cluster gateway SHALL consume the YU04 security control feed
  (`CONTROL_FEED_SUBSCRIBER=1`), as the kind gateway does, so a security added in reference-data reaches
  the gateway's replica without a restart, and one published while the gateway is down is applied when it
  returns (FR-IMRG32/33). Until reference-data's first delta creates `TRADERX_CONTROL_SECURITY` the
  subscriber SHALL retry without blocking readiness, logging each distinct failure once. The CompSci render
  (`prepare.py`) SHALL refuse a gateway without it.
- FR-GMR-08: A member's `/ready` SHALL refuse until its Aeron election state (`CLUSTER_ELECTION_STATE`)
  is `CLOSED`, in addition to the started engine and the `CLUSTER_READY_MAX_LAG` tolerance. A rejoining
  member closes its election only after `FOLLOWER_CATCHUP` has brought it up to the leader's log and it has
  joined the live log, so a StatefulSet roll does not take a second member down before the first has caught
  up, however short the log. An unread state is not ready, and `/ready` reports it as `electionState`. The
  no-observed-peer arm is kept: it cannot pass a member whose election is open, and a joined member does not
  go NotReady because its peers' `/health` is unreachable. Measured on `traderx` 2026-10-27 without the gate:
  a replacement member was Ready at applied 0 against peers at 1328 (the whole log), 15 s before
  `FOLLOWER_READY`.
- FR-GMR-09: A member JVM SHALL NOT cache a failed name lookup (`networkaddress.cache.negative.ttl=0`, set by
  `ClusterNodeMain` before any lookup). The JDK default keeps one for 10 s, so a survivor could not reach a
  replaced peer for up to 10 s after its name resolved (ADR-087).
- FR-GMR-10: Two members without their logs SHALL NEVER form a quorum. No operation the tier supports
  SHALL leave more than one member without its log at a time: scaling the StatefulSet down and up, deleting
  or restarting member pods (one, or several at once), rescheduling a member to another node, deleting and
  re-applying the StatefulSet, or rolling it. After any of them every member SHALL hold the log it held before
  (the same claim), and every member SHALL report the applied position and book hashes it had before until new
  input commits. Wiping a member (`kubectl delete pvc data-order-matcher-cluster-N`) is an explicit operator
  action, one member at a time, behind a snapshot barrier and a full catch-up, as in the ephemeral → persistent
  migration (ADR-087 §4). Losing two disks at once is outside what the tier can survive automatically: Aeron
  can then elect an empty leader (ADR-087 §1), and recovery is the GCS restore path.
- NFR-GMR-01: No engine, wire, snapshot or epoch change. `CLUSTER_HEARTBEAT_TIMEOUT_MS` stays 200 on
  the GKE tier.

## Success Criteria

- SC-GMR-01: `ClusterNodeShutdownTest` passes: a real one-member node in a child JVM exits 143 within
  30 s of SIGTERM.
- SC-GMR-02: `test_prepare` passes against an empty-root render, including
  `test_member_archive_storageclass_is_rendered` and `test_promtail_ships_this_namespace_from_every_node`.
- SC-GMR-03: An empty-root CompSci candidate contains the StorageClass, the promtail DaemonSet and
  exactly 21 distinct images in 24 slots.
- SC-GMR-05: `test_prepare.test_member_log_outlives_its_pod` passes: the render's member claims `data` with
  Retain/Retain, and a member with an ephemeral `/data`, or either retention set to Delete, is refused.
  `MemberDnsCacheTest` passes.
- SC-GMR-06: On a live tier `yu18-member-disk-survives.sh` passes in MODE=scale, delete and reschedule
  (FR-GMR-01, FR-GMR-10): the members taken away return holding the same claim, every member is at the applied
  position and book hashes it had before, and a fresh order commits. On a member StatefulSet with an ephemeral
  `/data` the same proof fails at "came back on a different disk". `yu18-minority-no-commit.sh` makes its minority by SIGSTOP/SIGCONT, so
  the majority keeps its logs.
- SC-GMR-04: `ReadinessDiagnosticsTest.rejoiningMemberBehindAShortLogIsNotReady` passes: applied 0 against
  peers at 1328 with lag tolerance 5000 is 503 while the election is in `FOLLOWER_CATCHUP`, and 200 once
  `CLOSED` at 1328.
