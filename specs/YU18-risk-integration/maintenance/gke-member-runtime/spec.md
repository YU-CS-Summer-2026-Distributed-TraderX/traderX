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
- FR-GMR-03: On the kustomization tiers (kind; the GKE `statefulset-emptydir.yaml` and `statefulset.yaml`) members
  SHALL run `-Xms12g -Xmx12g -XX:MaxGCPauseMillis=50` on G1, at a 16Gi memory request equal to the limit, and
  `statefulset.yaml` SHALL carry the same JVM flags as `statefulset-emptydir.yaml`. The CompSci production render is
  not this tier: there FR-PRG-05 governs the JVM and the container, and what is deployed is generational ZGC at the
  same 12 GB heap (`prepare.py MEMBER_JVM`: `-XX:+UseZGC -XX:+ZGenerational -Xms12g -Xmx12g -XX:+AlwaysPreTouch`, no
  pause goal), `MAX_ACTIVE_BOOKS=1024`, and since ADR-103 15 CPU / 40Gi on c4d-standard-16 (ADR-096 §1, ADR-103 §2;
  `test_prepare.test_members_run_generational_zgc_with_the_book_cap_pinned`). A reader of a production safepoint
  log should expect ZGC, not G1 (L0's finding, 2026-11-10).
- FR-GMR-04: A member SHALL exit with status 143 within the grace period on SIGTERM, after closing its
  `ShutdownSignalBarrier`, archive and media driver, and SHALL log `Cluster node down` once they are
  closed. SIGTERM is delivered to the container's PID 1. Where a shell launches the JVM (kept off PID 1
  so a crash harness can SIGKILL it from `kubectl exec`), that shell SHALL forward TERM and INT to the
  JVM and SHALL exit with the JVM's status. The CompSci render (`prepare.py`) SHALL refuse a member shell
  that does not. Without forwarding, `sh` as PID 1 has no handler, and every delete or roll ends in
  SIGKILL (137) after the grace period. This SHALL hold for a SIGTERM that lands before the startup election
  has closed: once the shutdown signal has arrived, the consensus module's termination hook SHALL log and
  return instead of halting, so the close completes. Outside a shutdown, a terminated consensus module SHALL
  still halt the JVM with status 70 so the pod restarts.
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
- FR-GMR-11: The cluster gateway's media driver (one SHARED agent) SHALL take its idle strategy from
  `GATEWAY_IDLE_STRATEGY`, with the members' names (`ClusterNodeConfig.idleOverrideSupplier`: `lowpark`,
  `yielding`, `busyspin`, `backoff`). Unset SHALL be `lowpark` (Backoff, 1 µs max park), not pure spin.
  `backoff` SHALL leave Aeron's default untouched (Backoff, 1 ms max park), so measurements taken before
  2026-11-10 stay reproducible. Any other name SHALL refuse to start. The gateway's start line names the
  strategy (`GATEWAY up: ... driverIdle=`). `CLUSTER_IDLE_STRATEGY` reaches only the members, so the gateway
  had parked up to 1 ms between orders at low rate. L0, 2026-11-10, on GKE: client p50 1,165 µs on the default
  against 614 µs with the gateway driver on spin, p99 2,230 → 1,041 µs; pure spin cost 8% of capacity at
  saturation (more, smaller packets).
- FR-GMR-12 (latency L1): Each member agent SHALL take its own idle strategy from `CLUSTER_IDLE_STRATEGY_<AGENT>`,
  where AGENT is SENDER, RECEIVER or CONDUCTOR (the DEDICATED driver's threads), ARCHIVE (the archive's conductor and,
  when DEDICATED, its recorder and replayer), CONSENSUS or SERVICE. It takes the same names as `CLUSTER_IDLE_STRATEGY`.
  An agent with none set SHALL take the member-wide idle (`CLUSTER_IDLE_STRATEGY`, else `CLUSTER_IDLE_SLEEP_MS`, else
  Aeron's default), so a member with none of these set runs as before, and earlier measurements stay reproducible. An
  unknown name SHALL refuse to start and name its variable. The member's start line SHALL name each agent's idle and the
  archive threading (`Cluster node up: ... idle=[sender=... service=...] archiveThreading=...`). Busy-spin wants a core
  per spinning agent: it is paired with the engine pool's static CPU manager (FR-PRG-05, FR-OPL-05). Node-local: members
  may differ, so it rolls one member at a time.
- FR-GMR-13 (latency L2): The archive's threading SHALL come from `CLUSTER_ARCHIVE_THREADING` (SHARED, DEDICATED,
  INVOKER). Unset SHALL be SHARED, as before. Any other value SHALL refuse to start. Node-local, as FR-GMR-12.
- FR-GMR-14 (latency L6a): A member SHALL take its media driver from `CLUSTER_MEDIA_DRIVER`: `embedded` (unset or
  blank; the Java driver in the member JVM, `ClusteredMediaDriver.launch`, as before) or `external`. Any other value
  SHALL refuse to start. External SHALL launch no driver: the member waits, bounded by `CLUSTER_EXTERNAL_DRIVER_WAIT_MS`
  (default 120000), for a driver with a live heartbeat in `CLUSTER_AERON_DIR` (a dead driver's CnC left in the Memory
  volume does not count), and exits 1 if none comes. It then launches the archive and the consensus module (in that
  order, as `ClusteredMediaDriver.launch` does) and the service container from the same contexts minus the driver,
  inside the same mark-file retry loop. SIGTERM closes them and leaves the driver running. The start line SHALL name
  the mode (`Cluster node up: ... mediaDriver=embedded|external (...)`). Under external the driver's threading and
  its agents' idle come from the driver's own config (`AERON_THREADING_MODE`, `AERON_<AGENT>_IDLE_STRATEGY`,
  `AERON_SOCKET_SO_RCVBUF`, ...), not from `CLUSTER_MEDIA_DRIVER_THREADING` or the `CLUSTER_IDLE_STRATEGY*` knobs.
  The driver for the A/B is the C `aeronmd` at the members' Aeron 1.51.0 (`scripts/gcp-compsci/Dockerfile.aeronmd`),
  a native sidecar on the member's `shm` volume added by `scripts/bench/latency/aeronmd-sidecar.py`, which mirrors the
  member's Java driver settings and keeps the pod Guaranteed. No production render changes. Node-local: members may
  differ (the wire protocol is the same), so it rolls one member at a time.
- NFR-GMR-01: No engine, wire, snapshot or epoch change. `CLUSTER_HEARTBEAT_TIMEOUT_MS` stays 200 on
  the GKE tier.

## Success Criteria

- SC-GMR-01: `ClusterNodeShutdownTest` passes: a real one-member node in a child JVM exits 143 within
  30 s of SIGTERM. The test signals as soon as `Cluster node up` is logged, which is inside the startup
  election, so every run exercises the election window (the pre-fix build exits 70 in 4 of 20 quiet runs).
- SC-GMR-02: `test_prepare` passes against an empty-root render, including
  `test_member_archive_storageclass_is_rendered` and `test_promtail_ships_this_namespace_from_every_node`.
- SC-GMR-03: An empty-root CompSci candidate contains the StorageClass, the promtail DaemonSet and
  exactly 21 distinct images in 24 slots.
- SC-GMR-07: `ClusterNodeConfigTest` passes. With nothing set, the contexts carry Aeron's default idle and a SHARED
  archive, and the start line reads `aeron-default` for every agent. The names parse, and an unknown one names its
  variable. An agent's own idle wins over the member-wide one. In a forked JVM with `CLUSTER_IDLE_STRATEGY=lowpark`,
  `CLUSTER_IDLE_STRATEGY_SENDER=busyspin`, `CLUSTER_IDLE_STRATEGY_SERVICE=busyspin` and
  `CLUSTER_ARCHIVE_THREADING=dedicated`, exactly the sender and the service spin, every other agent runs lowpark, and
  the archive is DEDICATED. With the sender wired to the member-wide idle instead, that test fails (negative control,
  2026-11-10).
- SC-GMR-08: `ClusterNodeConfigTest.mediaDriverDefaultsToEmbeddedAndRefusesUnknown` and
  `ClusterNodeExternalDriverTest` pass. A one-member node in a child JVM with `CLUSTER_MEDIA_DRIVER=external`, its
  driver in another process (the test JVM's Java driver, or the C driver with `AERONMD=<path>`), elects (CLOSED), acks
  an order from `ClusterProofClient`, exits 143 on SIGTERM with the driver still alive; with no driver it exits 1.
  `test_aeronmd_sidecar` passes: the sidecar mirrors the member's directory, threading, idle (per-agent, member-wide,
  sleep) and `-Daeron.*` driver properties, takes whole CPUs from the member (requests = limits), and refuses a member
  that is not Guaranteed or an Aeron dir off the Memory volume.
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
