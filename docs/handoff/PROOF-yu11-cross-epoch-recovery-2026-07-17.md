# YU11 cross-epoch recovery proof — 2026-07-17

## Verdict

The authenticated snapshot-bundle path is proven on the dedicated kind cluster for the case that
the prior exact-journal-cut design could not recover:

- the replacement had an empty PVC;
- the current epoch's recording began at a nonzero input sequence;
- the replacement could not prove the state immediately before that recording;
- it requested and installed a primary snapshot at an exact Aeron marker boundary;
- it replayed zero local tail events, merged into the current recording, and became ready;
- the bundle-recovered follower later promoted after a second primary crash and continued the
  input lineage.

The implementation is committed in five reviewable slices:

| Slice | Commit | Result |
|---|---|---|
| 1 | `fb4bf13` | One replicated snapshot-marker sequence domain |
| 2 | `1b15aba` | Exact marker boundary captured at Aeron publication commit |
| 3 | `2e0ddaa` | Authenticated, checksummed recovery bundle |
| 4 | `27d767f` | Atomic bundle install and startup integration |
| 5 | `aefc885` | Non-overwritable snapshot-marker boundary register |

Nothing was pushed. codeX did not touch GKE.

## Environment and setup

- Worktree: `/Users/yaakov/dev/lmax/traderX-YU11-aeron-replication`
- Branch: `YU11-aeron-replication`
- Cluster/context: `traderx-yu11-aeron` / `kind-traderx-yu11-aeron`
- Namespace: `traderx`
- Matcher shape: two-replica StatefulSet, embedded Aeron client plus Archive sidecar
- GKE: untouched; its deployed image remained the pre-slice base owned by the fable lane
- Risk entitlement enforcement: unchanged (`false`)

The test used the cycle-script PVC discipline: scale the matcher down before deleting matcher
PVCs, wait for deletion to complete, and remove the old Lease only when both matcher pods were
gone. The known kind PV-affinity race on ordinal 1 was repaired by scaling to one, deleting and
waiting for PVC 1, then scaling back to two.

## Slice-1 regression proof

The existing exact-cut path was rerun before bundle work, because changing marker lineage could
have invalidated the cycle-8 invariant. It still passed:

```text
local journal input tail = 1387
new epoch boundary       = 1388
```

The replacement rejoined and a smoke order was accepted. This preserves the earlier cycle-8
`838 == 839 - 1` proof while making snapshot markers part of the contiguous replicated lineage.

## The decisive empty-follower run

Five IBM Buy orders were submitted after a clean two-node start. The follower was stopped and its
PVC was deleted. The sole primary was then force-deleted so it restarted from its preserved PVC
and claimed a new epoch whose recording began well after sequence zero:

```text
Input-seq lineage base: 3793 (journal input tail 3792)
```

Starting ordinal 1 with an empty PVC correctly rejected the exact-cut fast path:

```text
Aeron follower bootstrap:
  negotiatedEpoch=4
  checkpointEpoch=-1
  epochStartInputSeq=9685
  source=recording

Exact journal-cut bootstrap unavailable at boundary 9684 (local tail -1)
Requesting an authenticated recovery bundle.
```

### First attempt and slice-5 defect

The first transfer request did not produce a bundle. The primary logged:

```text
Snapshot at local ring seq 1117 has no exact Aeron boundary
Snapshot at local ring seq 1208 has no exact Aeron boundary
Snapshot at local ring seq 1299 has no exact Aeron boundary
```

The general fixed-capacity publication sequence map was being overwritten by upstream run-ahead
before the BLP callback observed the marker. Slice 5 added a dedicated primitive marker register
to `AeronReplicator`, release-published by marker local sequence after the successful
`tryClaim` commit. The BLP reads that register first and retains the map as fallback.

### Successful bundle

With the slice-5 image, the primary captured:

```text
Aeron snapshot boundary:
  epoch=4
  inputSeq=10492
  position=77568
  sessionId=1799446462
```

The empty follower then logged:

```text
Aeron follower bundle installed:
  epoch=4
  inputSeq=10492
  position=77568
  sessionId=1799446462
  correlation=3326948505942520500

LIVE RECOVERY [journal]:
  snapshot(@64)+tail
  replayed 0 tail events
  12 orders warm

Follower caught up and ready (pod=order-matcher-1)
```

Both pods reached `2/2 Ready`. The stable order/read-model projection, excluding the continuously
changing market-price field, had the same SHA-256 on both pods:

```text
42663a72f24e341315223d63e97d7c6652bf3064246dd498984fcca85b084289
```

This is the key cross-epoch proof: the follower did not possess the missing historical epochs,
installed state through marker 10492, needed no local journal tail replay, and merged after the
exact Archive position 77568.

## Second-crash proof

The original primary (`order-matcher-0`) was force-deleted at approximately
`19:22:17Z`. The bundle-recovered follower transitioned at `19:22:34.181Z`:

```text
BLP role transition -> PRIMARY (pod=order-matcher-1)
Promotion lineage base:
  lastReplicatedInputSeq=12813
  ringCursor=2320
  base=10493
```

It claimed epoch 5 and produced a new exact snapshot boundary. The replacement ordinal 0 then
bootstrapped from the epoch-5 recording:

```text
negotiatedEpoch=5
checkpointEpoch=-1
epochStartInputSeq=12824
source=recording
Follower caught up and ready (pod=order-matcher-0)
```

This proves that bundle recovery is not a one-shot terminal state: the recovered node can become
leader and continue the lineage. The replacement happened to have sufficient preserved local
history for the epoch-5 exact-cut fast path, so the second replacement did not require a second
bundle.

## nextOrderRef defect found by acceptance

### Symptom

The first bundle recovery reported:

```text
12 orders warm, nextRef 8, tradeCounter 12
```

After the second crash, the promoted follower accepted `ord-013-0008`, reusing an ID previously
issued before the crash. This is a real correctness defect; matching state equality alone did not
prove snapshot completeness.

### Exact root cause

The defect is **not** introduced by bundle normalization pairing a counter from one boundary with
orders from another. `AeronBootstrapBundleStore.capture()` writes one normalized
`SnapshotStore.Data` and copies both `source.nextOrderRef()` and `source.orders()` from that same
source object.

The stale value is created before normalization:

1. `LmaxEngine.recoverLiveFromJournal()` restores `nextOrderRef` from the snapshot, then replays
   tail events directly into `MatchingEngine`. An `ORDER_NEW` in that tail updates the book but
   does not advance `nextOrderRef`.
2. `AeronReplicationFollower.onFragment()` decodes replicated `ORDER_NEW` events into the local
   ring but does not observe their order references. A follower can therefore apply orders while
   its independent `nextOrderRef` atomic remains at the older snapshot value.
3. A later snapshot faithfully captures this internally inconsistent state: the order tuples
   contain newer references, while `nextOrderRef` is stale.
4. Bundle install with zero tail replay makes the inconsistency immediately visible. Other paths
   could mask it when DB warm-up recomputed the maximum reference or when no new ID was requested
   before another restart.

The architectural error is keeping a monotonic ID generator outside the deterministic replicated
state transition. It is a snapshot-completeness defect, not an Aeron byte-transfer defect.

### Repair explored but deliberately not landed

A three-seam candidate repair was built and tested:

- advance the atomic for every replayed `ORDER_NEW`;
- advance it when a follower injects a replicated `ORDER_NEW`;
- on snapshot load, reconcile the stored counter with the maximum retained order reference.

The local test image was:

```text
localhost:5001/traderx/order-matcher:kind-20260717-counterfix
sha256:8f1a57513cc8e464f6c7e15965b6d228810004da7779b13f79944e0f5e90c65f
```

Live evidence on that image:

```text
bundle recovery: 12 orders warm, nextRef 13
pre-crash orders: ord-013-0013, ord-013-0014
follower promotion: epoch 10
first post-promotion order: ord-013-0015
replacement recovery: 14 orders warm, nextRef 15
```

That demonstrates the diagnosis, but the repair spans recovery, follower injection, and snapshot
validation rather than being the requested safe one-liner. It was therefore removed from the
worktree and was not committed. The running kind cluster still uses this locally built diagnostic
image; the committed branch intentionally does not.

The durable correction should make the generator part of the replicated/snapshotted state, assert
on load that it is greater than every recovered order reference, and test promotion after
post-snapshot orders. Merely taking `max(snapshot counter, retained orders)` is insufficient when
terminal-order retention can evict the highest historical reference.

## Risk-replica lag observation

At the first bundle recovery boundary, the stable matching/read-model hash matched, but the
gateway control replicas did not:

```text
primary:  ready=true,  watermark=2897, accounts=7, securities=28
follower: ready=false, watermark=954,  accounts=5, securities=16
```

`/health` likewise reported `riskReplicaReady=false` on the follower even though Kubernetes
reported the pod `2/2 Ready`. The follower later completed its control-feed bootstrap during
promotion and accepted the post-promotion order.

This does not invalidate the transferred BLP snapshot, which includes the authoritative risk
state at marker B. It does show that the separately maintained `GatewayReplicaStore` and the pod's
readiness contract can trail that recovery boundary. YU12 must distinguish:

- deterministic risk state included in the Raft snapshot/log; and
- asynchronously refreshed control-feed/gateway state used to admit new commands.

Promotion/readiness must not expose admission until the latter is valid, or it must rebuild that
state from the same Cluster snapshot rather than an independent feed.

## P5 stopwatch caveat

Fast witness is not enabled in the kind profile. The second-crash transition above took roughly
17 seconds from deletion to the promotion log via heartbeat detection. A later fixed-image run
took approximately 16.6 seconds to the promotion log and 22.7 seconds before the primary label was
observed.

The “3-second gate” is the crash script's assertion on measured primary-label exposure, not
`TERMINATING_GUARD_NS` (1 second). The script first has a separate 10-second deadline and then
asserts `failover_ms <= 3000`. This is the known P5 profile/stopwatch mismatch owned by the fable
lane, not a bundle failure.

## Verification gates

Before the decisive kind run:

- full order-matcher test suite passed;
- inherited allocation gates passed;
- Aeron allocation gate passed;
- risk allocation gate passed;
- `noGcTest` passed;
- sidecar tests passed.

After the slice-5 marker-register correction, the focused real-Aeron round-trip test and all three
allocation gates passed. The live empty-PVC recovery and second-crash promotion then passed as
described above.

## State left running

The dedicated kind cluster was left running:

- `order-matcher-0`: `2/2`, primary;
- `order-matcher-1`: `2/2`, standby;
- both use the local diagnostic `counterfix` matcher image;
- no GKE resources were changed.

## Transfer to YU12 Aeron Cluster

YU11 is now a waypoint. Aeron Cluster/Raft supersedes the custom leader election, peer replay
catalog negotiation, and most bundle transport machinery. Two findings transfer directly:

1. The replicated snapshot marker must define one exact deterministic boundary. In Cluster terms,
   state written by `onTakeSnapshot` must correspond to the service's applied log position, and
   recovery must resume after exactly that state.
2. Snapshot completeness includes every future-output generator and admission dependency, not
   just the visible order book. `nextOrderRef`, trade IDs, idempotency state, risk reservations,
   symbol IDs, and policy/control versions require explicit snapshot/replay invariants.

The YU12 acceptance test should issue orders after a snapshot, restart/recover from snapshot plus
log tail, promote the recovered node, and assert the next generated order ID is strictly greater
than every ID ever issued—not merely every order still retained in memory.
