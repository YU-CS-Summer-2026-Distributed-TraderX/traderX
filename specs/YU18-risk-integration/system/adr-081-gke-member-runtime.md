# ADR-081: Cluster members on GKE: archive volume, heap, pause goal, clean exit

Status: accepted, 2026-10-25 to 2026-10-27. Pack: [GKE member runtime](../maintenance/gke-member-runtime/spec.md).
Manifest source: `specs/YU17-otc-rates/generation/kubernetes/cluster/gke/` (`storageclass.yaml`,
`statefulset-emptydir.yaml`, `statefulset.yaml`, `kustomization.yaml`). YU18's CompSci profile
renders them through `scripts/gcp-compsci/prepare.py`.

## 1. Member `/data` on a dedicated archive volume (858319c3, 951ed3c9)

> The lifecycle below (deleted with the pod) is superseded by [ADR-087](adr-087-member-persistent-volumes.md):
> the same disk and class, now a retained per-member claim.

**Context.** At 400K orders/s every between-snapshot member spike (22–27 ms) was the leader's Aeron
archive `write()` blocked in the ext4 journal of the node boot disk, which the archive shared with
kubelet, container logs and image layers. Aeron bounds commit by the leader's recorded position, so the
whole cluster waited on it (STALLS.md, 2026-10-25).

**Decision.** `/data` is a generic ephemeral volume, 32Gi, `ReadWriteOnce`, of StorageClass
`order-matcher-archive` (`pd.csi.storage.gke.io`, `hyperdisk-balanced`, ext4, `WaitForFirstConsumer`,
`Delete`). It keeps the emptyDir lifecycle the tier relies on: created with the pod and deleted with it,
so a replaced member starts empty and rejoins by catch-up. The StorageClass is cluster-scoped. Both the
kustomization and the CompSci render emit it, so a fresh cluster binds the claims with no hand-applied
object. Hyperdisk attaches only to C4D/C4/C3/N4 machines; on an N2 pool switch `type` to `pd-balanced`.

**Result.** The rig's probe counted 0 journal waits on the dedicated volume, against 5–6 per 3 minutes on
the boot disk.

## 2. 12 GB heap and a 50 ms G1 pause goal (d9224e37, 6af15608)

**Context.** 1,024 books need ~5–7 GB (ADR-079 §6). G1's default `MaxGCPauseMillis` (200 ms) equals the
emptyDir tier's `CLUSTER_HEARTBEAT_TIMEOUT_MS` (200). At 62 accounts × 1,000 symbols, mixed pauses of
204–221 ms each ended just before an election on the member that paused, and an empty-disk rejoin
restarted its 12.2 GB log replication 764 times.

**Decision.** Members run `-Xms12g -Xmx12g -XX:MaxGCPauseMillis=50` on G1, at a 16Gi memory request
equal to the limit. The 1Gi memory-backed `/dev/shm` counts against the same cgroup. The heartbeat
timeout stays 200 ms. The PVC variant `statefulset.yaml` (reference only) carries the same flags.

**Result.** hxb row 8 (same rig, image and load, only the flag changed): max pause 69–79 ms, no
elections, one cut sha on all three members, and the empty-disk rejoin completed in ~121 s.

**Amended 2026-10-29 (ADR-096 §1).** The CompSci production profile runs the same 12 GB heap on generational ZGC,
with `MAX_ACTIVE_BOOKS=1024` pinned so that the derived book cap does not drop to ZGC's 873.

## 3. A member exits on SIGTERM (37e1e30e)

**Context.** `ClusterNodeMain` awaited an Agrona `ShutdownSignalBarrier` and never closed it. Under
Agrona 2.4.1 the barrier's shutdown hook waits for `close()`, so a stopped member logged "hasn't
terminated in 10 seconds" until the kubelet's SIGKILL. Its archive and media driver never closed cleanly.

**Decision.** Cleanup runs on the main thread after `await()`, inside try-with-resources. The barrier
closes and the JVM exits 143 (128 + SIGTERM), inside the pod's 10 s grace period.

**A signal during the startup election (e3adcf9b).** `Cluster node up` is logged before the startup election
closes. A SIGTERM inside that window closed the service while the consensus module still waited on it; the
agent ended with an `AgentTerminationException` and its termination hook halted the JVM with 70 mid-close
(4 of 25 runs beside 32 CPU hogs). `ClusterNodeMain` now marks the node stopping when the signal arrives, and
from then on the hook logs and returns, so the close finishes and the JVM exits 143. Outside a shutdown the hook
still halts 70, because a consensus module that terminates on its own needs a pod restart.

## 4. Pod logs reach Loki (c8c8ac31)

**Context.** The CompSci profile shipped Loki without a log shipper. The collector has no logs
pipeline, so Loki stayed empty.

**Decision.** The CompSci render includes the base promtail ConfigMap, ServiceAccount, ClusterRole,
ClusterRoleBinding and DaemonSet. The cluster-scoped pair is named `promtail-<namespace>`, so a side
namespace never rebinds the live one's. The scrape glob is `/var/log/pods/<namespace>_*`, and the
DaemonSet tolerates the engine taint, so member logs ship too. Promtail is the ninth upstream image in
the admitted population (21 images, 24 container slots).
