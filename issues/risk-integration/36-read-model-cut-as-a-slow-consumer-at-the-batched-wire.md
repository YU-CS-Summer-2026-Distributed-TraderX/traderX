# RI36: the read model is cut as a slow consumer at the batched wire, and cannot catch up

Opened 2026-11-09 (trade-history-tiers lane, the GKE 400K campaign, RI34 §1b). Status: open; the fetch sizing is in
(FR-DRS-43); replica ownership (FR-DRS-45) and the order-view read router (FR-DRS-46) are in (2026-11-10, branch
tp-partition-ownership, proven on kind at 2 and 4 replicas); the GKE hour on 4 replicas is not.

## What

At 1.2–1.5M records/s (400–520K orders/s with FR-DRS-41's 20–40 records per message) NATS cut trade-processor's
connection as a slow consumer 351 times in five minutes (`Slow Consumer Detected: MaxPending of 67108864 Exceeded`),
history-feed 27 times. After each cut the pull consumers resume from the last delivered sequence and re-read; the
read model (order view, SQL projection) then sits 4–5M messages behind for the rest of the hour and the run's far end
cannot G3 it, while the history store (history-feed) stays current.

Two causes, one fixed:

1. **Fetch size in messages** (fixed, FR-DRS-43): `BATCH_SIZE = 1000` per partition fetch was sized for one-record
   messages; at ~12 KB a message, 16 feeds and two or three fetches in flight per feed exceed 64 MB on one connection.
   The fetch is now sized by bytes (1 MiB aim).
2. **No headroom** (open): trade-processor runs at 7–8 cores of 12 at this rate — the per-record JSON parse and the two
   views — so any lost 100 ms is never regained. history-feed does the same volume on 1.2–1.9 cores with a batch
   INSERT per fetch.

## Fix (the plan: issues/risk-integration/tasks/prod-450k-plan-20261110.md §4)

- Shard the read model by partition across replicas (the stream is already partitioned; each replica owns a subset),
  or at least run the order view and the SQL projection in separate processes.
- A cheaper per-record path: the compact wire (the options paper's F) or a streaming parser; measure the parse share
  first with a profile at 1M records/s.
- `max_pending` on the NATS server (256 MB) and one connection per partition in trade-processor are cheap and reduce
  the cut frequency; they do not add speed.
- The run's far end should take G3 (snapshot) after the read model drains and not gate the hour on it; the lossless
  claim is the history store's (G3h).
- A memory-backed stream's retention must cover the read model's longest restart: the hour's trade-processor fail-closed
  on `RETENTION_GAP` against a two-minute stream after its third restart (2026-11-09 11:26 pm ET).
- Replica ownership: `PROJECTION_FEED_PARTITIONS_OWNED` per replica, a partition owned twice is a start-up refusal,
  and a kind proof that every partition is read exactly once across replicas.

## Replica ownership (FR-DRS-45, 2026-11-10)

`PROJECTION_FEED_PARTITIONS_OWNED` (`all`, `0,1`, `0-3`, or `replica:<k>/<N>`: block k of N) is read by everything
trade-processor does per partition. Each replica claims its partitions in `projection_partition_owner` (replica id,
its ownership, a database-time heartbeat) before any feed reads; a partition with another live owner is a start-up
refusal (`PARTITION_OWNED`, exit 3). Health and accounting answer for the owned partitions and name them; the union
over the replicas is the whole read model. The recon sweep runs on partition 0's owner only. `GET /accounts/{id}/orders`
from the memory-only view answers 421 for an account another replica owns, never an empty list. Render:
`prepare.py --read-model-replicas N` / `kind-yu18-rig.py --tp-replicas N` (`prepare.shard_read_model`): one replica is
the Deployment, now `strategy: Recreate` (a RollingUpdate surge pod would be refused forever); N > 1 is a StatefulSet,
Parallel, ownership from the pod index, headless Service `trade-processor-replicas`.

**kind, 2026-11-10 10:48–10:52 am ET** (`scripts/proofs/kind-tp-ownership.sh` on `kind-yu18-rig.py --binary
--partitions 8 --tp-replicas 2`, images `traderx/{cluster-node,trade-processor}:own` from this branch, cluster
traderx-own, deleted): trade-processor-0 owned 0-3, -1 owned 4-7, in each pod's health and in
`projection_partition_owner`. binary-load, 4 sessions × 375/s for 120 s: 168,372 orders accepted, 0 rejected; 168,372
sides on all three members. Every partition's trade and order cursors advanced on exactly one replica (0 to 32,497–33,552
each; the other replica never reported it). G3 on the union of the two accountings (which refuses a partition answered
twice or not at all): sides 1..168,372, each once, 0 duplicates, 0 open orders. Order events read by the two views
(applied + ignored): 336,744 = the members' bridges' published `/orders` records 336,744; a partition read twice would
count its events twice. A third trade-processor (`tp-intruder`, owning `0`) exited 3 with `PARTITION_OWNED ... partition
0 is owned by replica trade-processor-0 (owns replica:0/2, heartbeat 1106 ms ago)`, loaded nothing, and partition 0
stayed -0's. The profile writes no SQL row per order event (`ORDER_FEED_PERSIST=false`), so the order-event count is
taken from the views, not a SQL table. The first run (10:35 am ET, 167,714 sides, every check equal) needed the load
accounts inserted into SQL `accounts` by hand mid-run: the trade snapshot's position upsert references them (production's
render seeds them; kind's database does not). The script now inserts them, and the second run above is untouched.
Evidence: shared notes `tp-ownership-kind-20261110/run2`. Not measured: per-replica CPU (kind has no metrics-server)
and any rate above kind's ~1,400 orders/s.

## The read router (FR-DRS-46, 2026-11-10) and the 4-replica render

yaakov's decision 4 (ADR-103 §3): a router by `account mod 8`, kept only if it holds the API contract and beats the
console asking every replica and merging, on latency and throughput ("if this help then yes"). The router is in
trade-processor itself, so clients still call the `trade-processor` Service: a replica answers the accounts it owns and
forwards the rest to the owner `projection_partition_owner` names (`PROJECTION_REPLICA_URL`,
`http://{replica}.trade-processor-replicas:18091`); a forwarded read is marked and never forwarded twice; no live owner
is 503, never an empty list. The production snapshot profile now renders 4 replicas (`--read-model-replicas`, default
`min(4, P)`), each at the one replica's measured 12 CPU / 24Gi; `rig.sh` drives the StatefulSet (`wl trade-processor`)
and its G3 reads the union of the replicas' accountings.

**kind, 4 replicas, 2026-11-10 11:17 and 11:23 am ET** (same rig, two runs, images `traderx/*:own2`, 330,882 sides in
all; evidence shared notes `tp-ownership-router-kind-20261110/run2`): ownership 0-1, 2-3, 4-5, 6-7; every partition's
cursors advanced on exactly one replica; G3 on the union, sides 1..330,882 each once; order events read 661,764 =
published 661,764; the third claimant refused (exit 3). The contract: each of the 8 load accounts in three shapes (open,
`?status=all`, `?clientOrderKey=`) read on every replica and through the Service answered 200 with the owner's body
(md5 in the pod); each replica forwarded 21-24 reads. The comparison (`order-read-bench.mjs` in a pod, 30 s a mode):

| read | router (the Service) | fan-out and merge | the owner directly (floor) |
|---|---|---|---|
| open orders, 16 concurrent | 1,450/s, p50 8.8, p99 42 ms | 635/s, p50 21.7, p99 80 ms | 1,410/s, p50 10.1, p99 34 ms |
| `?status=all` (16 MB, 12,500 orders), 2 concurrent | 6/s, p50 300, p99 1,369 ms | 5/s, p50 342, p99 1,101 ms | 8/s, p50 221, p99 802 ms |

On the small read (the FIX status's and the console's) the router sits at the floor and does 2.3× fan-out's reads at
40% of its median; on the 16 MB read the two are even, each paying one more body transfer than the floor. The router
earns its place on these numbers and is kept. One node with 11 CPUs: the absolute rates are the laptop's, and on GKE the
forward adds a real network hop where fan-out adds three. Aside, not this change's: a `status=all` read of a loaded
account is 16 MB and 221 ms at the owner, against the gateway's 2 s FIX-status timeout.

**Still open for N replicas in production:** the readmodel-pool must hold 4 × 12 CPU / 24Gi (or the per-replica size
measured down on GKE); the hour at 450K on 4 replicas with G3 on the union.

**What the router covers.** Only one read is served from a replica's memory: `GET /accounts/{accountId}/orders` (with
`?status=all` and `?clientOrderKey=`), from the order view when `ORDER_FEED_PERSIST=false`; its callers are the
gateway's FIX order status (`ORDER_READMODEL_URL=http://trade-processor:18091`) and the console. Its owner is partition
`p = floorMod(accountId, 8)`, replica `k = p * N / 8`, pod `trade-processor-<k>.trade-processor-replicas`.
`/v2/projections/{scope}/accounts/{id}/orders` is SQL (any replica). `/v2/projection-feed` and its `/accounting` are per
replica by design (rig.sh takes the union). The recon's status is meaningful on partition 0's owner only. Settlement,
TCA and EOD are SQL-backed (any replica).

## Evidence

Shared notes `bridge-batching-gke-20261109/`: `hour-take3-memory` (the first hour: read model 4–5M behind from 9:10 pm),
`hour-take5-*` (the second), NATS logs' slow-consumer lines per IP.
