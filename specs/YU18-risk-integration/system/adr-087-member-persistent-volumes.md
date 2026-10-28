# ADR-087: A cluster member's log outlives its pod

Status: accepted 2026-10-27 (yaakov), production moved in roll 4, 2026-10-28, by §4 (rollout record §13). Pack:
[GKE member runtime](../maintenance/gke-member-runtime/spec.md) (FR-GMR-01, FR-GMR-09, and the invariant FR-GMR-10: two members without their logs never form a quorum). Supersedes the
lifecycle half of [ADR-081](adr-081-gke-member-runtime.md) §1: the disk and its class are unchanged.

## 1. Context: proof-audit suspect #15

On the GKE tier each member's `/data` was a generic ephemeral volume, created with the pod and deleted with
it. So `kubectl scale sts --replicas=1`, or any two member pods deleted together, destroyed a majority of the
consensus logs. Raft assumes a majority's storage survives, and with it gone two outcomes were observed:

- **The audit's run** (`yu18-minority-no-commit`, 3 → 1 → 3, 40d93f6b).
  - Members 1 and 2 came back empty. Member 1, on an empty log, won a term with member 2's vote and closed it.
  - Member 0, which held the real log, won a later term. It then stayed in `LEADER_READY` for 20 minutes, while
    both followers retried joining its log every 200 ms.
  - No majority shared a history, so every order answered 504.
  - A rolling restart of all three healed it only because it wiped each disk in turn, and each member then
    re-copied member 0's log.
- **A local emulation**, which keeps member 0 unreachable until the two empty members elect each other.
  - That pair is a working quorum and commits new orders on an empty history.
  - Member 0 is locked out for good: `followerLogAwait - joinPosition=96 less than logPosition=7488`.
  - This is silent loss of committed state.

The window comes from DNS. The survivor had been looking up the deleted pods' names, and the JDK caches a failed
lookup for 10 s (`networkaddress.cache.negative.ttl` in `java.security`; NodeLocal DNS caches denials for 5 s).
So for several seconds after the new pods' names resolved, the survivor could not reach them, while they could
reach it. That was 8.5 s in the audit and 1.6–7.7 s in seven GKE re-runs, all of which recovered within 25 s.

The discriminator on GKE: the same 45 s minority made by freezing members 1 and 2 (SIGSTOP/SIGCONT, disks kept).
Member 0 never became leader, applied stayed put, and the window's order got 504; the first order after resume
was acked at +13 s. Losing quorum and getting it back heals by itself; the wedge needs the disk loss.

## 2. Decision

1. **Per-member persistent volumes.**
   - `statefulset-emptydir.yaml` (the name is historical) declares `/data` as `volumeClaimTemplates: data`, on
     the same StorageClass (`order-matcher-archive`, Hyperdisk Balanced, `WaitForFirstConsumer`) and size (32Gi).
   - `persistentVolumeClaimRetentionPolicy` is `whenScaled: Retain` and `whenDeleted: Retain`.
   - A returning, restarted or rescheduled member reads its own log. Wiping a member is now an explicit
     `kubectl delete pvc data-order-matcher-cluster-N`.
   - The CompSci render (`prepare.py`) refuses a member StatefulSet whose `/data` is not such a claim.
2. **No negative DNS caching on members.** `ClusterNodeMain`'s class initialiser sets
   `networkaddress.cache.negative.ttl=0` before anything in the JVM resolves a name. This shortens the survivor's
   blind window to NodeLocal's 5 s and removes the second pod's fixed 10 s `awaitDns` wait. It narrows the
   window; the volumes close the hazard.

**Re-attach.** The disk is zonal `ReadWriteOnce` and is created in the zone of the member's first node. The member
pool is single-zone, so a rescheduled member (node drain, repair, anti-affinity onto a spare node) attaches the
same disk on its new node. The node selector and the taint toleration are unchanged.

**Growth.** The archive no longer resets with each replaced pod. It grows for the life of the claim until archive
purge-after-snapshot is wired (ADR-079 §6, still open): ~10–20 MB/s at ~100k orders/s under bench load, little at
demo rates, 32Gi per member.

**Other code that assumed an empty disk at start:**
- The `restore-from-gcs` initContainer already skips a disk holding `recording.log`. A total-loss restore now
  deletes the three claims first (`yu12-gke-restore-from-gcs.sh` does). The CompSci render strips the
  initContainer anyway.
- `yu12-gke-recovery.sh` and `yu13-gke-replace-proof.sh` delete the victim's claim before its pod, as
  `yu16-book-grid.sh` already did, so their empty-disk rebuild is still one.
- The YU15 runner's fresh epoch already deletes the claims.
- Recovery identity (`RunDescriptor`, `CLUSTER_EPOCH`) lives on `/data` and now persists with it, which is
  what it is for.

## 3. Recorded alternative, rejected for now: an empty-disk election guard

A member that starts with an empty `/data` while a peer's `/health` reports history (`applied > 0`) sets
`ConsensusModule.Context.appointedLeaderId` to that peer:
- Aeron reads the field only in `canvass()`, to skip self-nomination, so the empty member still votes and follows
  any leader, but can never be the empty candidate.
- It is cleared at the member's first `CLOSED` election, using the existing election-phase watcher.
- An all-empty fresh epoch has no peer with history and is unaffected.

The guard was prototyped in the emulation:
- Before: the empty pair commits on an empty history and member 0 is locked out.
- After: the empty members wait, member 0 leads once reachable, all three close, and an order commits on member
  0's history. Killing member 0 afterwards still fails over to member 1, which commits.

It closes the race inside Aeron's election rather than removing the condition, and it changes consensus
behaviour, so persistent volumes were chosen (yaakov, 2026-10-27). They enforce FR-GMR-10 by never creating two
empty members. It remains the answer for a genuine loss of two disks, for example
two nodes lost with their volumes.

## 4. Production roll plan (ephemeral → persistent), for the rollout chat

**Constraints:**
- `spec.volumeClaimTemplates` is immutable on a live StatefulSet, so it is replaced with `--cascade=orphan`. The
  running pods keep running on their ephemeral volumes throughout.
- No two members may be without their log at once, so each member is swapped alone, behind a snapshot barrier and
  a full catch-up.
- The members' build does not change in this roll; the DNS line is not in the deterministic core. If the
  cluster-node image moves too, the engine-roll rules still apply: same image on all three before step 3.

**Claims:**
- The old claims are `order-matcher-cluster-N-data`, owned by their pods. The new ones are
  `data-order-matcher-cluster-N`. The names never collide.
- **Delete none by hand.** Each old claim is garbage-collected with its pod when that pod is replaced, and its
  disk goes with it (class reclaim `Delete`).

**Steps:**
1. **Pre-checks.**
   - All three members `1/1 Ready`, one LEADER, election `CLOSED`, and the same applied and book hashes on all
     three (quiesce the algo engine to read them).
   - Engine pool has free Hyperdisk attach headroom; `order-matcher-archive` exists.
2. `kubectl -n traderx delete sts order-matcher-cluster --cascade=orphan`. The pods keep running and their
   ephemeral claims stay bound, because a pod owns its claim, not the StatefulSet.
3. Apply the new StatefulSet with `updateStrategy: OnDelete`. It adopts the three pods, replaces none, and never
   deletes a pod by itself.
   - It does try to bring each adopted pod's volumes to the new template. A pod's volumes are immutable, so this
     fails (`FailedUpdate ... pod updates may not change fields`) on the lowest ordinal still on its old volume,
     and the controller stops its pass there. That is expected and harmless.
   - The same stop is why this plan does NOT use a partitioned RollingUpdate. In the rehearsal, partition 2
     replaced nothing.
   - The same stop is why the order is ascending ordinals. A deleted pod above a not-yet-replaced lower ordinal is
     NOT recreated: in the rehearsal, member 2 stayed absent for 2+ minutes until the StatefulSet was rolled back.
4. **Each member in turn, in ascending ordinal order 0, 1, 2**, whichever of them leads. A leader's turn costs one
   election between the two members that hold the full log.
   1. **Snapshot barrier.** Toggle a snapshot on the leader (`ClusterTool /data/cluster snapshot`) and wait until
      `traderx_cluster_snapshots` has advanced on all three.
   2. **Replace the member.** `kubectl delete pod order-matcher-cluster-N`. Its ephemeral claim is
      garbage-collected with it, and the controller recreates the pod on an empty `data-order-matcher-cluster-N`.
   3. **Wait for it to catch up.** It is Ready only when its election is `CLOSED` within the lag tolerance
      (FR-GMR-08). Then check that its applied position and both book hashes equal the other two members', and
      that the old `order-matcher-cluster-N-data` claim is gone. **Never delete the next pod before both hold.**
      Two pods missing at once would be two empty members: FR-GMR-10.
5. Re-apply the manifest unchanged, which restores its RollingUpdate strategy. All three pods are already at the
   update revision, so nothing rolls.
6. **Prove it.** Run `yu18-member-disk-survives.sh` (MODE=delete) and `yu18-minority-no-commit.sh` on the live
   tier, per the rollout chat's approvals.

**Rehearsed** on a side namespace on 2026-10-28 (`traderx-s15`, c4d-standard-4, Hyperdisk), from an ephemeral
cluster at applied 39:
- Steps 2–5 took 2 min 12 s.
- Each member returned on `data-order-matcher-cluster-N` with applied 39 and the same order and position hashes
  on all three after each step.
- All three old claims were gone at the end.

**Rollback** from any point: delete the StatefulSet with `--cascade=orphan` again and re-apply the old one.
- It adopts the pods still on ephemeral claims.
- It recreates any missing ordinal on a fresh ephemeral claim, which catches up.
- This was exercised in the rehearsal: member 2 was recreated and back at applied 39 in 22 s.
- A member already on its new claim keeps its log until its pod is replaced.
