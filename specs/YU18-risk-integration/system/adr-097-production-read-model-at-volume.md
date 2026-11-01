# ADR-097: Production's read model keeps persistence on at high rates: the stream is the record, SQL holds snapshots

Status: accepted 2026-10-29 (yaakov: (1) snapshot mode, (2) scale out by partition, (4) a faster disk and a bigger
machine for the database; not kdb). Code on `traderX-risk-integration`; not deployed. Packs:
[downstream read side](../components/downstream-read-side/spec.md) (FR-DRS-29..32),
[production rig](../maintenance/production-rig/spec.md) (FR-PRG-20, FR-PRG-21).

## Context

Production's read model writes one SQL row per trade side and per order event (`TRADE_PROJECTION_MODE=rows`,
`ORDER_FEED_PERSIST=true`). One MariaDB took ~38–40K rows/s batched, and four writers into one table took 66K
together. That is far below the members' rate, so persistence on has meant a low ceiling. The building blocks for
something else were already on the branch: the durable capture stream, the in-memory trade store with SQL snapshots,
the memory-only order view, and bridge partitions with receipt lanes ([ADR-080](adr-080-downstream-read-side.md),
[ADR-083](adr-083-bridge-receipt-publish.md)). Three things kept them off production:
- the order view lost every order older than its 300 s replay window on a restart (production scales to 0 nightly);
- the restore gate's G3 read SQL trade rows 1..N, which the snapshot store does not keep;
- nothing measured the whole read side with persistence on under realistic cross-node latency.

## Decision

### 1. The snapshot read model (FR-DRS-29..32)

- Every trade side and order update is stored in the JetStream stream (`TRADERX_FEED`, on the NATS PVC). The stream
  is the system of record for the replay window.
- Trades are booked in memory per partition. SQL takes a snapshot every 5 s (positions, settlement counts, each
  account's blotter window, the cursor), as before.
- New: the snapshot also commits the partition's **exactly-once accounting**: side count, highest `tradeSeq`, and
  wrapped sums of seq and seq². A side whose `tradeSeq` is not above the partition's last is refused and counted,
  never booked twice. Each partition is one receipt lane in engine order (ADR-083), so a lower `tradeSeq` can only be
  a second copy.
- New: the memory-only **order view snapshots its open orders** with a per-partition cursor and resumes from them. It
  is exact after any outage the stream retains, and its work is bounded by open orders, not by the update rate.
- New: cursor-acknowledged consumers **pull pipelined**. Under ingest, a lagging consumer is served at ~70 ms per
  1,000-message pull, and the serial fetch-apply-ack loop added the apply time to every pull.

### 2. An exactly-once check that works at volume (FR-DRS-29, FR-PRG-21)

With duplicates refused, the booked sides of each partition are distinct. The members' `trades` counter numbers every
side 1..t. So if, summed over partitions:
- the count is t − base,
- the highest is ≤ t,
- the sums of seq and seq² mod 2^64 equal the closed form over (base, t] minus the unprojected ranges,

then the booked set is that range. The check needs O(P) numbers from `/v2/projection-feed/accounting`, whatever the
volume. The two sums cover the one case a partition cannot see: a side under two accounts.

The restore gate's G3 uses this check in snapshot mode. It also checks that the order view's committed open orders
equal the members' restored `openOrders`, and that every committed cursor has reached its subject's last message.

### 3. The production profile (FR-PRG-20)

`prepare.py --read-model snapshot [--partitions P]` renders:

| Part | Setting |
|---|---|
| Members | `BRIDGE_PARTITIONS=P` (the member image already has partitions and receipts: `c562bbab` contains `79b754f7` and `48655be8`) |
| trade-processor | jetstream feed, P partitions, snapshot trades, memory-only orders with their snapshot; 12 CPU / 24Gi guaranteed, gen-ZGC 16g; `readmodel-pool` (c4d-standard-16) |
| NATS | 24Gi with `GOMEMLIMIT=20GiB`, the base sizing of every profile since FR-PRG-28 (2Gi was OOM-killed under load; this profile first set 12Gi/10GiB); stream limit = rate × 1,344 B/order × 30 min; `max_file_store` and the PVC above it |
| Database | `db-pool` (c4d-standard-8), 6 CPU / 24Gi, 16G buffer pool; claim `eod-price-db-fast` on Hyperdisk Balanced (12K IOPS, 400 MiB/s), restored from a VolumeSnapshot of the old claim |

The database's disk moves by snapshot and restore, never by re-creation. `validate()` refuses a moved claim with no
snapshot source, because it would start empty and G3 would then refuse the restore.

### 4. What limits the rate: one stream, not the database

Measured on throwaway VMs with +1 ms RTT between every pair (Evidence below):
- The database is not the limit. Snapshot writes are bounded by dirty state, not by trades.
- The trade-processor is not the limit. It idles at a few percent CPU while it waits for deliveries.
- The single NATS stream is the limit. Every message goes into it with a receipt, and every partition's consumer reads
  from it. NATS sat ~50% CPU idle with its disk ~25% busy while consumers starved, and a lone stream stored ~95K
  msgs/s of a 405K offer. Eight per-partition streams stored 357K msgs/s with no drop.

So the scale-out that pays is a stream per partition (FR-DRS-32): `TRADERX_FEED_P<p>` capturing `/trades.<p>` and
`/orders.<p>`. The gateway's FIX fill feed reads each of them (FR-FFR-13). Separate SQL tables or database instances
per partition would not help, because SQL is not where the time goes.

### 5. The cutover from rows on `TRADERX_FEED` to the profile, with no gap (FR-PRG-20..22)

Production today: row read model, one stream `TRADERX_FEED`. The two stream layouts overlap on subjects, so the old
stream must go before the per-partition ones are created. Nothing may be published in between, or it would be
captured by neither. The cutover therefore happens with no input and no publisher:
1. **Gateways at 0** (`rig.sh down` drains them, or `gateways.sh 0`). No order enters, and the gateways' fill feed is
   off.
2. **Row read model caught up and checked.** The members are up and gated. Every durable `tp-*` consumer on
   `TRADERX_FEED` has nothing pending and nothing ack pending. Then `rig.sh snapshot-base` re-runs the row check (SQL
   holds sides 1..t, each once) and records base = t, once.
3. **Members to 0 behind the snapshot barrier** (the `down` path), then trade-processor to 0. With nothing running,
   nothing can publish.
4. **The database disk moves.** VolumeSnapshot of `eod-price-db-data` at 0 replicas, restored into
   `eod-price-db-fast` (rendered by the profile).
5. **Delete `TRADERX_FEED`** (`nats stream rm TRADERX_FEED -f`). It holds only sides already in SQL (step 2).
6. **Apply the profile.** trade-processor starts first. Support workloads are Ready before the members, and it
   creates `TRADERX_FEED_P0..P7`. The members then start with `BRIDGE_PARTITIONS=8`. That is an egress setting, read
   at start, with no apply-path effect.
7. **Restore gate** with the snapshot G3: it expects exactly (base, trades]. Any side published into neither stream
   would be a count deficit, and G3 refuses. The gateways start only after it passes, on a build with FR-FFR-13
   (`053b3e25`), whose fill feed reads `TRADERX_FEED_P<p>`.

A gap is impossible by construction, because steps 3–6 run with no publisher. G3 checks this afterwards by counting.

## Consequences

- Production's read model keeps every trade side and order update durable (stream, then SQL snapshots) at the
  measured rate below, against ~1K/s unattributed for the row orderbook across a 1 ms link (FR-DRS-28 before) and
  ~40K rows/s for the batched row path.
- SQL `trades` becomes a per-account window. Settlement and recon read the ledger (ADR-080 §7.6). Full history is
  the kdb/Parquet capture.
- The switch from rows is a one-time step. Drain the row projection, run a passing row G3, record
  `rig-snapshot-base`, snapshot the database claim at 0, apply the profile.
- Production needs two new pools (`readmodel-pool`, `db-pool`) in `rig.sh pools/up/down` (the rollout lane's file).
- The switch to one stream per partition deletes `TRADERX_FEED` first: the two layouts overlap on subjects. The
  gateways must run a build with FR-FFR-13 before it, or their fill feed finds no stream and refuses.

## Evidence (throwaway VMs, 2026-10-29, 8:06–10:15 PM ET)

Rig, project-compsci-484414 / us-east1-b, each VM with netem 0.5 ms on every egress queue (+1 ms RTT):
- `srm-nats`: c3-standard-8 (production's nats-pool shape), NATS 2.14, production config, 340 GB pd-balanced.
- `srm-db`: c4d-standard-8, the profile's MariaDB, 200 GB Hyperdisk Balanced 12K IOPS / 400 MiB/s.
- `srm-tp`: c4d-standard-16, the profile's trade-processor (12 CPU, gen-ZGC 16g), built from this branch.
- `srm-load`: c4d-standard-16, `SnapLoad`, which publishes as the members' receipt lanes do. It uses one connection
  and thread per partition, in order, with a reply subject on every message, and the legacy wire production runs.
  Per cross it publishes 2 trade sides and 3 order updates; 1% of orders are left resting. 1,000 accounts.

Every step ends with the exactly-once check against the closed form over every side published since the stream
began. Every step below was EXACT.

| Layout | P | NATS | Orders/s held, every hop persisting | First rate that is not held |
|---|---|---|---|---|
| one stream | 8 | c3-standard-8 | 50K | 60K |
| one stream per partition | 8 | c3-standard-8 | **125K** (311K events/s projected = offered, committed 5.6 s after the stop) | 150K |
| one stream per partition | 16 | c3-standard-22, Hyperdisk | 125K | 150K |

- **Kill test.** SIGKILL trade-processor mid-load at 100K orders/s, 10 s down: the accounting stayed EXACT over
  6,000,000 sides, and the 30,000 committed open orders equal the resting orders published.
- **Idle-advance defect.** The accounting caught a silent loss in the trade store's idle advance: 1,408 of
  3,000,000 sides. It is now fixed (FR-DRS-31).
- **Machine sizes.** Database ~5% CPU. trade-processor ≤16% of 16 vCPU. NATS at 2Gi was OOM-killed; 12Gi held.

Full table, method and the limiting factors: `docs/engineering/downstream-at-400k.md` §7.12.

**Target.** Production keeps persistence on at **125K orders/s** with P = 8, one stream per partition, NATS on
nats-pool's c3-standard-8 (12Gi), trade-processor on c4d-standard-16, and the database on c4d-standard-8 with
Hyperdisk Balanced. The end-to-end rate is the lower of this and the members' rate with the bridges on; the members'
side is outside this measurement. Above ~135K orders/s, one NATS server stops serving reads during ingest. That
holds on a c3-standard-22 too, so the next step is more NATS servers, not a bigger one.
