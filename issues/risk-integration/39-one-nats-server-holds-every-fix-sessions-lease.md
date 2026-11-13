# RI39: one NATS server holds every FIX session's lease, so slowing that server costs all 600 sessions

Opened 2026-11-12 by the bridge-overflow lane, from GKE stage 2 of the 450K slow-stream rerun. Evidence is in the
shared notes, `bridge-overflow-20261112/gke2/s2-throttle/`.
Status: open. A fix is proposed below; nothing is built.
Related: [ADR-103](../../specs/YU18-risk-integration/system/adr-103-capture-on-a-nats-cluster-members-on-c4d-16.md),
which covers placement over five servers, and ADR-086 §6b with FR-GFS-16, FR-GFS-21 and FR-GFS-24 in
[fix-session-persistence](../../specs/YU18-risk-integration/maintenance/fix-session-persistence/spec.md).

## What

The fault was a cgroup `io.max` write limit of 40 MB/s on nats-0's volume, from 7:37:24 to 7:40:50 pm ET. nats-0 kept
running and answering. Its layout at the start of the stage:

```
nats-0: KV_FIX_ORDERS KV_FIX_SESSIONS KV_FIX_SESSIONS_10 KV_FIX_SESSIONS_15 KV_FIX_SESSIONS_5 KV_FIX_SESSION_LEASE
        TRADERX_FEED_P0 TRADERX_FEED_P5 TRADERX_FILLS_0 TRADERX_FILLS_10 TRADERX_FILLS_15 TRADERX_FILLS_5
```

nats-0 was chosen as the fault target because it leads two feed partitions. It also holds `KV_FIX_SESSION_LEASE` and
`KV_FIX_ORDERS`. Every FIX session's lease is in that one stream, and so is every gateway's FIX order store.

About 24 s into the limit, the gateways logged `FIX session FIX.4.4:TRADERX->CLIENTnnn lease lost: java.io.IOException:
Timeout or no response waiting for NATS` (00:37:48.76 UTC). Every gateway logged it in the same millisecond. Clients
then reconnected and were refused (`Logon rejected: session is live on another gateway`) for minutes afterwards.
Four of the 20 session buckets were on the slow server, so about 120 of the 600 sessions should have been affected.
The lease took all 600.

## Why

`FixSessionStore` places the session buckets (FR-GFS-21: bucket i on `FIX_SESSION_PLACEMENT` tag i mod N) and writes
each one on its own server's connection (FR-GFS-24). The lease and order buckets are created with no placement, as
R1, on the main connection (`FixSessionStore.java` near line 299). So the JetStream meta leader puts each of them on
whichever server it likes, one stream for every session. Each lease is renewed every 500 ms with a 3 s TTL (ADR-086
§6b). When that one stream's server stores slowly, every renewal everywhere waits on it. FR-GFS-24's own text says
it left them there ("The lease and order buckets ... stay on the main connection").

That makes one server a single point of failure for the whole FIX entry. The fault it doesn't survive is not an
outage, only a slow volume, which is run 5's condition (2026-11-11). It also makes any slow-stream fault on that
server a FIX fault, which is why the bridge-overflow throttle was not a clean feed fault.

## Proposed fix (not built)

**Shard the lease and order buckets with the session buckets, and place each shard with its session bucket.**
Session K's lease goes in `FIX_SESSION_LEASE_<b>` and its orders in `FIX_ORDERS_<b>`, where `b = bucketOf(K)`, the
bucket that already holds K's state. Each shard gets the same placement tag as session bucket b and is written on
that server's own connection (FR-GFS-24). Then a slow server costs exactly the sessions whose state is already on
it: about a fifth at five servers, the same set whose commits would stall anyway. A FIX client could still be
refused while that server is slow, but only if its own state lives there.

- Bucket 0 keeps the unsuffixed names (`FIX_SESSION_LEASE`, `FIX_ORDERS`), as FR-GFS-21 did for `FIX_SESSIONS`, so a
  one-bucket render is the layout it is now.
- The fence does not change: a lease is still one key, compare-and-set on its own revision, and the `K.seq` CAS is
  untouched, because a session's lease and its state never span two buckets.
- A fresh epoch purges every shard (FR-GFS-13). `validate()` refuses a render whose lease or order shard count does
  not match the session bucket count.
- Draft requirement, FR-GFS-25: the lease and order stores SHALL be sharded and placed with the session buckets,
  bucket i on server i mod N, each written on its server's own connection.

**Alternatives considered:**
- R3 for the lease bucket. A quorum write still waits on a slow stream leader until leadership moves, and it costs
  three writes per renewal. It doesn't fix the stall.
- Placing the lease on a server with no feed streams. That moves the single point to another server, and a fault
  there still costs all 600.
- A longer lease TTL. It only delays the loss, and it lengthens failover for a client whose gateway really died
  (ADR-086 §6b chose 3 s for that).

**Tests to write with it:**
- An IT on a three-server NATS with one server's writes slowed. Renewals for sessions in the other servers' buckets
  stay live. Control: on the current code every session loses its lease.
- The GKE readback shows each lease and order shard on the server of its session bucket.

## Until it is fixed

A slow-stream fault meant only for the feed should target a server that holds no FIX lease or order bucket. In this
layout that is nats-1 or nats-2 (feed P1 and P6, or P2 and P7). The meta leader picks the lease's server at bucket
creation, so read the layout at each bring-up and choose from it.
