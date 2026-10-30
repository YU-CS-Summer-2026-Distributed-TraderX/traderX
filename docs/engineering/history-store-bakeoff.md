# History store bake-off: ClickHouse vs QuestDB vs KDB-X

2026-10-29, evening ET. Branch `history-store-bakeoff` (from `traderX-risk-integration`). The question: which store
captures EVERY order event and every fill from `TRADERX_FEED` at the full 400K orders/s (1M rows/s: 1 trade side and
1.5 order updates per order, `downstream-at-400k.md` §1), for blotter history, TCA and the regulatory/EOD export.
MariaDB keeps current state (snapshot mode); this store keeps everything.

**Recommendation: ClickHouse.** It takes the full-rate volume at the feed's own ceiling (1.1–1.2M rows/s against a
1M target) on under 4 cores, keeps an hour at full rate in the least disk (~131 GiB against ~300), answers the
per-account reads (a, d) in ~5 ms warm and at most 0.13 s cold, exports the day 5–18× faster than the others, was
exactly once across a `kill -9`, and has no licence limit on RAM, cores or hosting. KDB-X matched it on ingest and
exactly-once with a fraction of the CPU, but its per-account reads are scans until an end-of-day re-sort (26 s cold),
its export has to be chunked to stay under the 16 GiB heap, and its licence forbids making it available to third
parties. QuestDB did not sustain 1M rows/s on the same host (0.79M).

## 1. Results

Same rig, same input, same feed code for all three: a stream filled once with 600,000,000 events (240,000,000 orders
= 10 minutes at 400K orders/s), drained flat out by the feed into the store, with the feed `kill -9`ed 240 s into
the drain and restarted (§2).

| | **ClickHouse 25.8** | QuestDB 9.1.0 | KDB-X Community 5f (2026.10.23) |
|---|---|---|---|
| Sustained ingest, first 240 s of the drain | **1.14M rows/s** (r2: 1.18M) | 0.79M rows/s | **1.23M rows/s** |
| Ingest over the whole drain (outage excluded) / best 5 s | 1.10M / 1.37M rows/s | 0.76M / 1.10M rows/s | 1.21M / 1.45M rows/s |
| Knee | the feed's own ceiling (null sink: 1.37M rows/s) | 0.79M rows/s: the store | the feed's own ceiling |
| Keeps up with 400K orders/s (1M rows/s) | yes | **no** (79%) | yes |
| Exactly once across `kill -9` (rows / distinct (part, seq) / distinct key vs the stream) | 240,000,000 + 360,000,000, all equal: **exact** | 240,000,000 + 360,000,000, all equal: **exact** | 240,000,000 + 360,000,000, all equal: **exact** |
| Disk for the 10-minute volume / per hour at 400K orders/s | **21.8 GiB / ~131 GiB** (active parts; `du` read 80 GiB right after the drain, 62.6 GB of it merged-away parts awaiting deletion) | 49.6 GiB / ~298 GiB | 49.5 GiB / ~297 GiB |
| Peak CPU during the drain | 3.8 cores | 4.1 cores | 0.2 cores (write-down process) |
| Peak CPU, check + queries + export | 17.4 cores | 13.8 cores | 2.1 cores (query process; 4 secondary threads is the licence cap) |
| Peak memory during the drain: anon (heap) / with page cache | 2.9 / 53.2 GiB | 1.8 / 42.3 GiB | 0.42 / 34.3 GiB (write-down process) |
| Peak anon, check + queries + export | 30.9 GiB (the 360M-key `uniqExact`) | 8.3 GiB | 12.1 GiB (query process, under the 16 GiB cap) |
| (a) today's fills, one account, page 11 of 100: cold / warm | 131 / 5.9 ms | 1,389 / 35 ms | 26,343 / 183 ms |
| (b) one account's VWAP vs the market's, per security, last 5 min | 3,793 / 298 ms | 760 / 204 ms | 1,447 / 602 ms |
| (b2) VWAP by account and security, last 5 min (5.0M groups) | 580 / 502 ms | 1,303 / 752 ms | 2,078 / 2,137 ms |
| (d) one account's position and average buy price, as of mid-run | 12 / 4.9 ms | 2,081 / 17 ms | 102 / 102 ms |
| (c) EOD export, 600M rows as CSV to the client: trades / orders | **18.9 s / 41.8 s** (12.7M / 8.6M rows/s) | 93.5 s / 211 s (2.6M / 1.7M rows/s) | 338 s / 774 s (0.71M / 0.47M rows/s), 2M-row chunks |
| Licence | Apache 2.0 | Apache 2.0 | free for internal business use; 16 GiB RAM and 24 cores per instance; no third-party hosting (§4) |

Query times are client wall time over the 0.5 ms netem link, cold = first run after dropping the store host's page
cache, warm = median of 5. ClickHouse's numbers are its third run (r3, §3); r2, on the same input, agreed within ~15% on
ingest, warm queries and export (its cold (a) was 59 ms). CPU is the store container's cgroup; the drain-time peaks are what ingest costs, the
later ones are the exactly-once check (whole-table distinct counts) and the export.

## 2. Rig and method

- **VMs** (project-compsci-484414, us-east1-b, VPC `default`, deleted afterwards): `hsb-feed` c4d-standard-32
  (NATS 2.14 JetStream with the production server settings, the load and the feed) and `hsb-store`
  c4d-standard-16, 62 GiB (one store at a time, in Docker, host network, data on 500 GB hyperdisk-balanced, 800 MB/s).
  `tc netem delay 0.5ms` on both VMs' egress: 1.1–1.3 ms RTT between them.
- **Input**: `HsbLoad` writes the bridges' exact managed JSON (`TradeNatsPublisher.encode` /
  `OrderNatsPublisher.encode`, with the run-descriptor fields, trace ids and client keys) on the partition subjects,
  P = 4, 10,000 accounts, 500 securities. A cross = 2 orders = 2 trade sides + 3 order updates (NEW for the resting
  order, FILLED for both). Every publish is JetStream-acked, so the stream holds exactly what the load counted.
- **Why a pre-filled stream, not live load** (§5.1): one `TRADERX_FEED` stream took only 240–280K acked msgs/s on
  this host, a quarter of the target, so a live 400K orders/s run would have measured NATS. The stream was split per
  partition (`TRADERX_FEED_P<p>` over `/trades.<p>`, `/orders.<p>`; subjects, storage and limits otherwise as
  `JetStreamFeed.ensureStream`), filled once at ~375K msgs/s (26 min) with the volume of 10 minutes at full rate,
  and every store drained the same backlog. The drain rate is the store's sustained ingest and its knee: a store
  that drains at or above 1M rows/s keeps up with 400K orders/s.
- **Feed** (`HistoryFeed`, the same code for every store): 8 lanes (2 feeds × 4 partitions), each on its own NATS
  connection and durable pull consumer, 5,000-message fetches, 100,000-row / 500 ms batches, one atomic write per
  batch, ack (AckPolicy.All) only to the store's durable watermark, exactly-once by the store's own max(`seq`)
  (spec FR-HST-02/03). The null sink (no store) measured the feed's own ceiling: 1.37M rows/s.
- **Durability per store**: ClickHouse MergeTree `fsync_after_insert=1, fsync_part_directory=1`; QuestDB WAL
  tables over ILP/HTTP, one request = one transaction per table, `cairo.commit.mode=sync`; KDB-X the intraday
  write-down pattern (`kdb/hsb.q`): small in-memory tables, appended every second to date-partitioned splayed tables,
  then `sync -f`, and only then does the watermark the feed acks to advance.
- **Schemas**: numeric ids; ClickHouse `PARTITION BY toDate(ts) ORDER BY (account, ts)`, LowCardinality strings;
  QuestDB `PARTITION BY DAY WAL`, SYMBOL columns; KDB-X date-partitioned, symbol columns enumerated, no sort or
  attribute intraday (the usual `p#` re-sort is an end-of-day job).
- **Kill test**: `kill -9` of the feed at 240 s, restart 30 s later; the restart recreates each durable at the
  store's cursor + 1 (NATS took 49–174 s per consumer to do that cold, §5.2), then drains to the end.
- **Check**: rows, distinct (`part`, `seq`) and distinct business key (`trade_seq*2+side`,
  `order_ref*4+ordinal`) per table against the stream's per-subject counts (240,000,000 and 360,000,000).
- **Resources**: the store container's cgroup every 2 s (cpu usage, memory anon / current / peak).

## 3. Runs, as they happened

| Run (ET) | Store | Outcome |
|---|---|---|
| 9:16 pm | ClickHouse, attempt 1 | 269M rows in 240 s (best 5 s 1.52M rows/s); the restart's consumer creates timed out at the client's 5 s API default, 8 at once on 150M-message shards |
| 9:51 pm | ClickHouse r2 | restart bound the old durables and waited out ackWait: trades exact, **19 of 360M order events lost**, all just above each lane's cursor (an AckPolicy.All ack of a later message retired unwritten redeliveries). The restart path was changed to recreate at cursor + 1 (FR-HST-03). Superseded by r3; the resource sampler did not run (a `pkill -f` matched its own `sh -c`) |
| 10:07 pm | QuestDB | exact; its own `count_distinct` over 240M keys was refused ("too large allocation requested: 4294967312"), so the check runs per partition |
| 10:34 pm | KDB-X | exact; the first check expression went past the 16 GiB licence heap (`'stop`) and every later call failed until a GC; rerun one partition per call, on a fresh query process |
| 11:41 pm | ClickHouse r3 | exact; the 8 consumer recreates took 104–129 s cold. (Its first launch at 11:16 died on a syntax error in `bench.sh`.) |

The 2 h `max_age` of `TRADERX_FEED` began expiring the first fill during the KDB-X run (oldest messages filled at
8:46 pm); KDB-X still held all 240,000,000 / 360,000,000 rows. The stream was refilled with `max_age` 8 h for r3.

## 4. KDB-X licence: what the text says

Sources: the KX Community Edition License Agreement v1.1 (27 August 2025) and KX's usage-restrictions page, which
the agreement incorporates; the KDB-X release notes; and measurements on this rig.

| Question | Answer | From |
|---|---|---|
| Is production use allowed? | **Yes, for internal business use.** The licence is "for use by you solely for the Permitted Use", Permitted Use = "personal or internal business use"; the restrictions page says "free for both personal and commercial projects" and calls KDB-X "ready for production use". Not allowed: making it available to third parties "whether or not over a network or on a hosted basis", time-sharing, service bureau, SaaS, cloud; bundling or distributing it, or making it available to end users "for revenue generating purposes", needs an OEM licence. Installing it on servers licensed for KX commercial support is prohibited. | agreement §2.1, §2.2, Attachment A; restrictions page |
| Per process or per host? | **Per instance (host or deployment), all q processes together**: "Running multiple processes within a single instance or deployment is allowed, provided the total RAM does not exceed 16GB"; "Usage is restricted to a single physical or virtual instance"; "Maximum number of 24 CPU cores can be used by all instances". Threads (4 secondary) and connections (16) are per q process. Registering several licences to get round the limits is prohibited. | restrictions page |
| Does the 16 GiB count mmap'd on-disk reads? | **The text does not say** ("Maximum 16GB RAM permitted for use by q"). **What is enforced is the heap**: the release notes describe a heap memory limit (`'stop` on breach, assignments blocked), and on this rig an 18.6 GiB column mapped, summed, averaged and filtered moved `.Q.w[]` mmap to 20,000,000,016 while `.Q.lim[]` mem stayed at the heap's high-water mark (1,088 MiB; 4,416 MiB once `where` built a 2.5 GB vector in heap). The bake-off's HDB mapped the whole 49.5 GiB store under the cap. | release notes; measured |

Consequence for TraderX: the history store serves TraderX's own desk and back office, which is internal use; any
client-facing access to it (a client portal reading fills from it) would be the third-party use the licence
excludes. The heap limit is also `.Q.lim[]`'s *high-water* mark, not the current heap: once one expression peaked
near 16 GiB, `cur` stayed at 15,808 MiB after `.Q.gc[]` had returned the memory.

## 5. Findings outside the store

### 5.1 One JetStream stream does not take 400K orders/s

| Publishers (JetStream-acked, 8K–32K window each) | Streams | Acked msgs/s | NATS CPU |
|---|---|---|---|
| 8 | 1 (`TRADERX_FEED`) | 278–284K | ~4 cores |
| 16, 24 | 1 | 240–318K | 4–8 cores |
| 8 | 4 (one per partition) | 375–393K | ~7 cores |

400K orders/s is 1M msgs/s. One stream's ingest path, with publish acks, gives a quarter of that on a c4d-standard-32;
sharding per partition gives ~40%. This bounds the capture stream itself, whatever reads it, and it is the same wall
`downstream-at-400k.md` §7.8 hit live ("IPQ len limit reached"). The read side is not the limit: eight pull
consumers delivered 1.37M msgs/s to the null sink.

### 5.2 Recreating a filtered consumer deep in a large stream costs minutes, once

A durable created at a start sequence 70M deep in a 150M-message shard (two subjects, so the consumer is filtered)
took **96.5 s** cold and **0.0 s** immediately after (NATS loads the shard's per-block subject index once);
unfiltered, 0.0 s. Eight at once after the `kill -9`: 49–60 s (QuestDB run), 161–174 s (KDB-X run), 104–129 s (ClickHouse r3). Production
restarts of any cursor-from-sequence consumer (the snapshot trade store's included) pay this once per stream after a
NATS restart or page-cache eviction; a stream per (feed, partition) would avoid the filter entirely.

## 6. Prototype

The feed is `scripts/history-store-bakeoff/src/HistoryFeed.java` with `sink=clickhouse`; spec
`specs/YU18-risk-integration/components/history-store/spec.md` (FR-HST-01..05, NFR-HST-01/02, SC-HST-01/02).
Reproduce: `fetch-deps.sh` (jnats + KX's Apache-2.0 `c.java`), copy the directory to `/opt/hsb` on both VMs
(the KDB-X install at `/opt/kx` is the licensee's own), `vm/setup.sh feed|store`, fill with `HsbLoad total=240000000 threads=8 streams=4 maxAgeHours=8 create=true`,
then `vm/bench.sh <store>` per store (`vm/post.sh <store>` re-runs only the check, queries and export) and
`summarize.py`. The raw logs and cgroup samples of the runs in §1 (`*.log` is ignored by the repo) are in the coordinator's
side-session notes, `history-store-bakeoff-20261029/`. Not deployed; nothing in the render, the manifests or
GKE changes.

Cloud cost: two VMs from 8:38 to 11:57 pm ET (3.3 h), about $11.
