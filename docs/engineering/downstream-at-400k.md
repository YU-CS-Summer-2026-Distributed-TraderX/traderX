# The read side at 400K orders/s

2026-10-26, evening ET. Branch `traderX-risk-integration` (YU18). Follows `DOWNSTREAM.md` (the 2026-10-25 hop map,
capacity table and §5 ranking) and the `dddcdc86` fix, which bounded and batch-amortized the SQL projection to about
38–40K rows/s on one writer. The engine sustains 400K orders/s (hxb, 2026-10-26); at ~1 trade side and ~1.5 order updates
per order the read side must absorb **~1M events/s**, ~25× what one batched writer does.

This document fixes the read-side contract, ranks the four §5 directions by gain per effort, and records a prototype of
the top two with before/after numbers on the DOWNSTREAM.md rig shape. Nothing here changes the cluster's wire, ack or
snapshot formats. One NATS **subject** changes when partitioning is enabled (§4.1); the message bytes do not.

## 1. Read-side contract: what each reader needs

| Reader | Asks for | Needs | Freshness it can live with | Today's source |
|---|---|---|---|---|
| **FIX order status (H / AF)**, via `ClusterGatewayMain.orders()` | an account's open orders; with `includeTerminal`, recent terminal ones | the *current* state of each order: id, side, qty, remaining, status, security | seconds; must never answer a stale state as current without saying so | HTTP → trade-processor `GET /accounts/{id}/orders` → SQL `orderbook` |
| **`GET /accounts/{id}/orders`** (client restart, console) | same | same, plus `?status=all` for cancel/replace/STP proofs to see CANCELED | seconds | SQL `orderbook` |
| **Positions** (console `/accounts/{id}/positions`, position-service `GET`) | quantity and average cost per (account, security) | a consistent position per key; equal to Σ booked trades | seconds live; exact at a named sequence for EOD | SQL `positions`, written in the trade's transaction |
| **Blotter** (console `/accounts/{id}/trades`, position-service `GET /trades`) | an account's recent fills | the trade rows, by account; recent first | seconds | SQL `trades` + after-commit NATS fan-out |
| **EOD P&L** (`EodPnlConsumer`) | every account's holdings at the session close | positions **as of the close**, not "whatever SQL has caught up to" | once a day, but must be exact | SQL `positions` (the one reader that is wrong under lag) |
| **Risk extract** (`RiskExtractMain`) | the portfolio at a named consensus sequence N | the engine's sequenced cut | once a day, exact | engine cut over NATS; already independent of the projection |
| **Recon / repair** | "is the projection complete, and if not, from where to replay" | a contiguous watermark per feed and a replayable source from that watermark | continuous | `projection_runs.checkpoint_seq` (a high-water mark, not a watermark) + archive replay that refuses after purge |

Three things fall out of the table:

- **No reader wants one SQL row per order event.** Every order reader wants the current state per order, and the only
  reader of *history* (proofs, `?status=all`) wants the last few. Terminal order history at 400K/s is 400K rows/s
  forever; it belongs in the append-only capture (the kdb tap already receives `txOrder`), not a hot read model.
- **Positions have two consumers with different needs.** Live readers want Σ trades, seconds stale is fine. EOD wants an
  exact cut at a sequence, which the engine already renders for the risk extract.
- **Loss is the contract violation, lag is not.** Every reader tolerates seconds of lag. None tolerates a trade that never
  arrives, and today's core-NATS path turns any lag into permanent loss (DOWNSTREAM.md §3).

## 2. Delivery guarantees, per hop, after this design

| Hop | Guarantee | Mechanism |
|---|---|---|
| Apply thread → bridge queue | at-most-once, non-blocking, counted drop on a full 64K queue | unchanged (`TradeNatsPublisher` / `OrderNatsPublisher.offer`) |
| Bridge thread → NATS | at-most-once publish, counted on failure; **captured by a JetStream stream** once it reaches the server | unchanged publish; the stream is server-side capture, the bridge never waits for a JetStream ack |
| Stream → projection | **at-least-once, in order per partition, replayable from the durable cursor** | pull consumer per partition, batch acked after the SQL commit; bounded by the stream's size/age limits |
| Projection → SQL | idempotent: trade id dedupe, order `(consensusSequence, outputOrdinal)` monotone, positions absolute per batch | unchanged |
| Projection → in-memory order view | idempotent, same ordering rule, rebuilt from the stream's replay window on restart | `OrderView` |
| Leader failover | still a gap: the dead leader's unpublished bridge queues (≤64K events per bridge) | unchanged; see §8 |

The bridge-side guarantee is deliberately unchanged: the only way to make hop 1 lossless is for the bridge to read the
Aeron log instead of a queue the apply thread fills, which is §5 item 3 of DOWNSTREAM.md (an archive-replay consumer) and
is where the design goes next if failover gaps matter more than the cost of a second log reader.

## 3. Ranking by gain per effort

| Rank | Direction | What it buys | Cost | Verdict |
|---|---|---|---|---|
| **1** | **(c) replayable, partitioned feed** (JetStream capture of the bridges' subjects; `/trades.<p>`, `/orders.<p>` with `p = accountId mod P`; one durable pull consumer per partition; replicas own disjoint partitions) | turns loss into lag (restart, OOM, NATS cut, slow DB all become catch-up); N writers for N partitions with no shared lock; the stream is the recon source | medium: ~150 lines in trade-processor, a 10-line change in each bridge, one lock row per partition; no engine change | **prototyped** (§4) |
| **2** | **(a) no SQL row per order event**: an in-memory order view fed by the same egress, open orders plus a bounded ring of recent terminal ones; `GET /accounts/{id}/orders` and FIX status answer from it | removes ~60% of projected rows and the slowest write (orders are ~32 of the ~66 µs per cross); the order path becomes CPU-bound on parse, not SQL-bound | small: `OrderView` + a flag; rebuild-on-restart needs (c) | **prototyped** (§4) |
| 3 | (b) positions from sequenced cuts | exact positions at a named N for EOD P&L, which is the one reader wrong under lag; and `EodPnlConsumer` stops reading SQL positions at all | small for EOD (the cut already exists; P&L reads it instead of SQL); **not** for live positions: a cut is apply-thread time on every member (~50 MB and hundreds of ms per 1M positions, ADR-079) | design only, §5; do the EOD half next |
| 4 | (d) recon and repair at volume | with (c) the questions become "is the durable cursor behind the stream head" and "is the stream behind the bridge's published count"; repair is replay from the cursor | small once (c) exists; the live blotter recon (`ReconciliationService`, 100 trades/s) is retired for the managed path | design only, §5 |

Why (c) before (a): (a) alone keeps core NATS, so every order the memory view misses during a hiccup is gone and the view
cannot be rebuilt after a restart. With the stream in place, (a) is a flag. Why (b) is not first: live positions are
already the cheapest row per cross (one multi-row upsert per batch; 215–255K rows/s measured ceiling), and taking them
from cuts would move cost onto the apply thread of all three members.

## 4. Prototype: (c) + (a) on `traderX-risk-integration`

### 4.1 What changed

| Component | Change | Default |
|---|---|---|
| `order-matcher/.../cluster/BridgePartitions.java` (new) | `BRIDGE_PARTITIONS=P` makes both bridges publish on `<subject>.<accountId mod P>`. The JSON envelope's `topic` stays the bare subject, so `projectionEvent()` (the cold replay verifier) is byte-identical. | unset → the bare subject, today's wire |
| `TradeNatsPublisher`, `OrderNatsPublisher` | `nats.publish(BridgePartitions.subjectFor(subjects, r.accountId), …)`; three lines each. Still non-blocking, still core publish, still no wait on the apply thread. | — |
| `trade-processor/.../JetStreamFeed.java` (new) | ensures the `TRADERX_FEED` stream (subjects `/trades`, `/trades.*`, `/orders`, `/orders.*`; file storage; size and age limits), a durable per partition (`tp-trades-p<p>`, `tp-orders-p<p>`, `AckPolicy.All`), a pull loop per partition that fetches ≤1000, hands the batch to the existing `processBatch`/`persistBatch`, and acks the last message after it returns (after the SQL commit). | — |
| `TradeFeedHandler`, `OrderFeedHandler` | `traderx.feed.mode=jetstream` skips the core subscription (`NatsJSONSubscriber.autoSubscribe()`, a YU18 copy of the 006 class with that seam) and starts one feed thread per owned partition. The bounded queue, the flush thread and the tradeSeq gap counter are the core path, unchanged. | `core` |
| `OrderView` (new) | every update is applied to a per-account map with the SQL projection's ordering rule; terminal orders are kept in a count-bounded ring (`traderx.order-view.terminal-capacity`, 50K, ~850 B per entry from a heap histogram, so ~42 MB) and evicted oldest-first. At 200K the ring filled the 256 MB default heap and the process thrashed in full GC, which is how the size was found. | always on |
| `OrderFeedHandler` | `traderx.order-feed.persist=false` writes no `orderbook` row; the view's consumer is then an ephemeral replay of the last `traderx.order-view.replay-seconds` (300) so a restart rebuilds the view. | `true` |
| `OrderController` | with persistence off, `GET /accounts/{id}/orders` (and `?status=all`) answers from the view as the same `OrderRow` JSON; the scoped `/v2/projections/{scope}/…` route stays SQL. The gateway's FIX status source is unchanged. | — |
| `ProjectionWriteLock` | one lock row per partition (`1 + p`), bound per feed thread; partitions are disjoint by account, so they commit in parallel. Every existing caller still takes row 1. | partition 0 |
| `TradeService.processTrades`, `OrderProjectionService.persistAll` | `entityManager.flush()` before the shared `projection_runs` counter UPDATE, so that row's lock spans the commit instead of the bulk INSERT. Without it a second partition waited for the first's whole insert. | — |
| `TradeService` | `traderx.projection.fanout=false` skips the per-trade after-commit UI fan-out (two core publishes per trade side); `traderx.projection.batch-timing=true` logs where a batch's time goes, every 50 batches (bench only). | on / off |
| `JetStreamFeed` pull wait | `traderx.feed.fetch-wait-ms` (200) bounds how long a pull waits to fill a batch. | 200 ms |
| `ProjectionFeedController` | `/v2/projection-feed` adds `mode`, `persist`, `applied` (both feeds), `ignoredStale`, `viewSize`. | — |

Safety: the apply thread's `offer` is unchanged; the bridges never call JetStream; nothing waits on the downstream. The
stream must exist before the leader starts publishing or those messages are core-only (the prototype creates it from
trade-processor at startup; the deployment order is NATS, trade-processor, then members, or create the stream in the NATS
manifest).

### 4.2 Rig and method

Same shape as DOWNSTREAM.md: `mariadb:11.4` (4 CPU / 2 GiB, the GKE args, the shipped YU18 DDL), `nats:2.10-alpine -js`
(2 CPU, file store), trade-processor from the rendered YU18 tree in `eclipse-temurin:21-jdk` (1 GiB / 2 CPU, default
256 MB heap, `-XX:+ExitOnOutOfMemoryError`), the load generator (`ProjLoadP`, the bridges' exact managed wire plus
partition subjects) inside the same docker network. Settlement and recon sweeps were disabled by interval on both arms
(they are not part of the question and they contend for the write lock).

**Where it ran.** The laptop was not usable: another session's 4-node kind cluster took 5–7 of its 11 CPUs, and the
dddcdc86 baseline fell from 38–40K to 3–9K rows/s there. Every number below is from a dedicated GCE VM (`dsq-bench`,
`c4d-standard-16`, us-east1-b, debian-12 + docker, deleted afterwards) running the same containers with the same CPU and
memory limits. The batched single-writer ceiling on it reproduces 2026-10-25: 105K trade inserts/s, 94K orderbook
upserts/s, 191K position upserts/s.

**Method.** A rate ladder per arm: offer *R* crosses/s for ~20 s, then measure until every published event is booked or
nothing moves for 20 s. Each step reports booked/published, drops, rows/s over the time to the last booked row, and
replica CPU-seconds per row. **Kept pace** means everything was booked within the load window with zero drops. Each feed
mode gets a fresh JVM. Tables are reset with `TRUNCATE` between modes, because InnoDB purge lag after a bulk `DELETE`
slowed primary-key reads and contaminated an earlier run. A cross is 2 trade sides and 2 FILLED order updates, so
"both" at *R* crosses/s offers 4*R* msgs/s.

### 4.3 Results

**Database ceiling (one MariaDB, 1000-row batches):**

| Table | Rows/s |
|---|---|
| `trades` insert | 105,338 |
| `orderbook` upsert | 94,117 |
| `positions` upsert | 190,969 |

**Baseline: the dddcdc86 tip, core NATS, one replica.**

| Feed | Offered msgs/s | Booked | Dropped | Rows/s | Verdict |
|---|---|---|---|---|---|
| orders | 20K | 400,000 / 400,000 | 0 | 19,361 | kept pace |
| orders | 40K | 790,657 / 800,000 | 9,343 | 36,335 | fell behind, **lost** |
| orders | 80K | 582,566 / 1,200,000 | 617,434 | 35,446 | fell behind, **lost** |
| trades | 20K | 383,626 / 400,000 | 16,374 | 18,530 | fell behind, **lost** |
| trades | 40K | 597,258 / 800,000 | 202,742 | 27,072 | fell behind, **lost** |
| trades | 80K | 420,183 / 1,200,000 | 779,817 | 25,607 | fell behind, **lost** |
| both | 40K | 601,861 / 800,000 | 198,139 | 26,135 | fell behind, **lost** |
| both | 80K | 613,435 / 1,600,000 | 986,565 | 26,602 | fell behind, **lost** |
| both | 160K | 458,257 / 2,400,000 | 1,941,743 | 25,937 | fell behind, **lost** |

**Prototype, P=1: JetStream feed, orders in memory (`ORDER_FEED_PERSIST=false`), one replica.**

| Feed | Offered msgs/s | Booked | Dropped | Rows/s | CPU µs/row | Verdict |
|---|---|---|---|---|---|---|
| orders (view) | 160K | 1,600,000 / 1,600,000 | 0 | 141,718 | 7.0 | **kept pace** |
| trades | 20K | 400,000 / 400,000 | 0 | 19,332 | 47.0 | kept pace |
| trades | 40K | 800,000 / 800,000 | 0 | 25,236 | 21.8 | lagged, caught up |
| trades | 80K | 1,200,000 / 1,200,000 | 0 | 25,671 | 21.2 | lagged, caught up |
| both | 40K | 800,000 / 800,000 | 0 | 38,494 | 25.9 | **kept pace** |
| both | 80K | 1,600,000 / 1,600,000 | 0 | 46,721 | 14.8 | lagged, caught up |
| both | 160K | 2,400,000 / 2,400,000 | 0 | 47,646 | 13.6 | lagged, caught up |

**Prototype, P=1, orders still written to SQL (`ORDER_FEED_PERSIST=true`), to isolate the feed from the view:**

| Feed | Offered msgs/s | Booked | Dropped | Rows/s |
|---|---|---|---|---|
| orders | 40K | 800,000 / 800,000 | 0 | 26,189 |
| both | 80K | 1,600,000 / 1,600,000 | 0 | 22,649 |

**Prototype, P=2 and P=4: one replica per partition, trades only, one MariaDB.**

| Partitions | Offered msgs/s | Booked | Dropped | Rows/s | vs P=1 |
|---|---|---|---|---|---|
| P=1 | 80K | 1,200,000 / 1,200,000 | 0 | 25,671 | 1.0× |
| P=2 | 40K | 800,000 / 800,000 | 0 | 29,474 | 1.15× |
| P=2 | 80K | 1,200,000 / 1,200,000 | 0 | 29,513 | 1.15× |
| P=4 | 80K | 1,200,000 / 1,200,000 | 0 | 36,085 | 1.41× |
| P=4 | 160K | 1,200,000 / 1,600,000 at cut-off | 0 | 31,491 | 1.23× |

The P=2 and P=4 numbers are after the flush-before-counter fix (§4.4). Steps that ended with backlog still draining
show "at cut-off": the backlog was booked afterwards, with nothing redelivered or dropped. Two P=4 "both" steps are
left out because they measured the previous step's carry-over backlog.

**Loss under a crash** (P=1). 600,000 trade sides were published into the stream at 958K msgs/s, and the replica was
`kill -9`ed 5 s later with 126,000 booked. After restart the durable consumer resumed from its last ack:

| Check | Result |
|---|---|
| Trade rows | 600,000 of 600,000 |
| Distinct ids | 600,000 |
| tradeSeq gaps | 0 |
| Positions ≠ Σ trades | 0 keys |

The baseline's equivalent (DOWNSTREAM.md §3) lost 131,886 of 202,000.

**Stream ingest, no consumer:** 2,000,000 messages (both feeds) captured at **1.34M msgs/s, 662 MB/s**, all retained
(1013 MiB on disk), from one publisher thread.

### 4.4 What the numbers say

- **Loss is gone.** With the stream, no arm dropped one event at any offered rate up to 640K msgs/s, and a `kill -9`
  mid-drain lost nothing. On the baseline every rate above ~20K msgs/s lost events permanently. That is the contract
  violation §1 names, and it is closed for every hop downstream of the NATS server.
- **Orders leave SQL.** The in-memory view keeps pace with 160K updates/s on one thread at 7 µs of CPU each. That is
  ~4× the baseline's best order rate, and it takes the order rows off the database entirely. Removing them roughly
  doubles what the database can give trades: with orders persisted through the same stream, the "both" arm drained
  at 22.6K rows/s, against 47.6K with the view.
- **The trade projection is one MariaDB instance's worth, ~25–36K rows/s, and that is the database, not the
  consumer.** Found on 2026-10-27 (early morning ET) with one-variable experiments on the same rig shape, after the
  neighbour's kind cluster had gone and the laptop reproduced the VM's control number (24.4K rows/s):

  | Experiment | Result | Verdict |
  |---|---|---|
  | `EXPLAIN` the position prefetch | primary-key `range`, 989 rows; but the row-constructor `IN` is superlinear in pairs (100 → 3 ms, 300 → 16 ms, 1000 → 189 ms with `count(*)`), ten chunks of 100 → 23 ms | not the live cost: the in-batch timer shows it at ~4 ms |
  | Per-phase timer in `processTrades` (bench flag `traderx.projection.batch-timing`) | per 1000-row batch: lookup 4, prefetch 4, bulk trade INSERT 11–18, position upsert 6, counter 0, commit + fan-out 3 ms; ~33 ms of SQL plus ~20 ms in the pull loop | the SQL half alone would allow ~30K rows/s per partition |
  | UI fan-out off (`traderx.projection.fanout=false`) | 24.4K → 28.6K rows/s; CPU per row 34 → 25 µs | +17%, a cost but not the cap |
  | NATS container at 4 CPUs instead of 2 | 24.4K → 25.1K rows/s | no effect |
  | **DB only: N concurrent bulk writers** (`BulkT`, 1000-row batches, distinct ids) | 1 writer, one table: **98K** inserts/s. 4 writers, one shared `trades` table: **66K** aggregate (20K + 13K + 19K + 14K). 2 writers, own tables: 84K. 4 writers, own tables: **149K** (43K + 36K + 34K + 36K) | **this is the cap**: four writers into one table do less than one writer, and even on separate tables the instance reaches 1.5× one writer |

  So partitions scale the consumer but not the store. The `trades` primary key is a near-monotonic VARCHAR
  (`e1-<epoch>-<tradeSeq>-B/S`), so every partition's bulk insert lands on the same right-hand B-tree pages; giving each
  partition its own table lifts that to ~150K/s on one instance, and the instance is the next wall. "Two connections per
  partition" would be the same thing as two partitions on one table and was not run: the DB-only test already shows what
  concurrent writers into one table do. MariaDB sat at ~1–20% CPU in every arm; the limit is InnoDB's concurrent-write
  path, not CPU.
- **Against 400K orders/s** (~400K trade sides and ~600K order updates per second): the order side is covered by
  ~4 view partitions. The trade side needs the store to scale with the partitions, which one MariaDB instance does
  not: it needs either one database instance per partition (~4 instances at 98K each, if the per-instance rate holds
  with the full per-batch work, which measured at ~30K rows/s per partition here; so more like 8–12 instances), or
  §5 item 7 of DOWNSTREAM.md, an append-only trade store, with SQL keeping only what the UI and EOD read. Per-partition
  tables on one instance are a 1.5× stopgap, not a path to 400K.
- **What the earlier quoted numbers were.** The dddcdc86 commit measured 30–35K trade sides/s as the peak per-second
  counter delta during a flood, and that matches the 25–27K average here. An earlier version of this ladder divided
  booked rows by the 20 s no-progress timeout too, which understated every fell-behind baseline step by up to 2×.
  The tables above use the corrected runner.

## 5. Design for (b) and (d)

### (b) Positions from sequenced cuts — the EOD half

`EodPnlConsumer.process` reads `positions.findByScope(...)` from SQL and marks them. Under lag that is the wrong
portfolio; under a scope mismatch (`legacy-unknown` is hard-coded) it is no portfolio. The risk extract already solves
the same problem correctly: a sequenced marker names N, the leader renders the cut at N, quiescence is witnessed at N+1,
and the cut is written once. The change is to make P&L a second consumer of that cut:

- `eod.prices.ready` → risk-extract offers the marker and publishes the cut (today) → **position-service subscribes to
  `risk.extract.cut` (or reads the stored `seq-N.cut` beside the fixture) and marks the cut's rows instead of SQL rows.**
- The cut carries `(account, security, quantity)` and the stamp (epoch, N, sha); P&L rows get `consensus_sequence = N`, so
  extract and P&L are provably the same portfolio by construction, and the "extract/P&L disagreement" check in
  DOWNSTREAM.md §4 becomes a sha comparison.
- Cost: a subscriber in position-service and one column on `eod_position_pnl`; no engine change, no new marker, no extra
  apply-thread time (the cut is rendered once for both).

**Not for live positions.** A cut costs apply time on every member, proportional to the position count (ADR-079 measured
~50 MB of allocation per 1M positions after the fix, and hundreds of ms). Rendering it every few seconds at 400K/s would be
a permanent apply-thread tax and a latency spike on each member. Live positions stay Σ trades in the projection; after (a)
removes the order rows, the position upsert is one multi-row statement per 1000-trade batch and is not the binding cost.

### (d) Recon and repair at volume

With the stream in place the three questions and their answers are cheap:

| Question | Today | After (c) |
|---|---|---|
| Is the projection behind? | `ReconciliationService` classifies ≤100 trades/s against a 500K live blotter | `consumer.pending` on each durable (stream head − ack floor), exported from `/v2/projection-feed`; alert on pending > budget |
| Did anything never reach the stream? | tradeSeq gap counter, in memory, single consumer only | bridge `published` − stream's per-subject message count, per partition; plus the gap counter when P = 1 |
| Repair | archive replay from genesis, refused after purge | **replay is the normal path**: a durable that falls behind catches up; a destroyed projection re-reads from the stream's retained window; beyond the window, the archive-replay tool as today |
| Purge safety | `ArchivePurgeMain` is independent of the projection | gate the stream's `maxAge` and the archive purge on the slowest durable's ack floor (DOWNSTREAM.md §5 item 3) |

The live blotter on the apply thread (`RECON_BLOTTER_CAPACITY`, ~9% of an apply thread at 400K/s on all three members)
is no longer needed for the managed path and can be set to 0 on the 400K tier.

## 7. The trade store: an append-only log plus SQL snapshots

Decision (yaakov, 2026-10-27, early morning ET): an append-only trade store; SQL keeps only the blotter window and the
EOD inputs. This section is the design and the prototype's numbers (§7.4).

### 7.1 Store choice

| Candidate | Already in the stack | Ordered, replayable by position | Durable | Write rate measured | Verdict |
|---|---|---|---|---|---|
| **The JetStream capture stream** (`TRADERX_FEED`, the bridges' own subjects) | yes, since §4 | yes: stream sequence per partition subject | file storage, size/age limits, survives a replica `kill -9` (§4.3) | 1.34M msgs/s, 662 MB/s | **chosen** as the system of record for the projection |
| The kdb capture (brief 06, `txTrade` → ZSTD Parquet, read natively by KDB-X) | yes | by `tradeSeq`, as files | yes | ~100 MB/s per member, capped at 256 MB per tap | **kept** as the queryable history; not a replay source for the projection (it is per member and best-effort) |
| Segment files or a columnar log keyed by `tradeSeq`, written by trade-processor | no | would be | would be | not built | rejected: a second copy of what the stream already holds, plus a new format to recover |

The stream is what the bridge published, i.e. a durable replica of the leader's egress; the Aeron Archive remains the
cluster's own record, and the failover gap of §8 item 1 applies to every downstream store equally.

### 7.2 What SQL keeps

| Reader | Needs | Source after §7 | How fresh |
|---|---|---|---|
| Positions (console, position-service `GET`) | every (account, security) quantity and average cost | SQL `positions`, written as a **snapshot** of the in-memory projection every `snapshot-ms` | ≤ snapshot interval (5 s default) |
| Blotter (console `/accounts/{id}/trades`, position-service `GET /trades`) | an account's recent fills | SQL `trades` holds the last `blotter-depth` (100) fills **per account**, rewritten in the same snapshot | ≤ snapshot interval |
| EOD P&L | positions at the close | SQL `positions` at the close (the market is closed, the last snapshot is final), or the engine cut (§5 (b)) | exact at the cut's N; the SQL snapshot carries its `consensus_seq` |
| Risk-extract cross-check | positions at N | the cut; SQL `projection_cursor.consensus_seq` says which N the snapshot is at | exact when `consensus_seq ≥ N` and the projection was quiescent |
| FIX status, open orders | order state | the in-memory order view (§4) | live |
| Full trade history, regulatory, analytics | every fill | the kdb/Parquet capture; the stream for its retention window | — |
| Recon | "is the projection complete?" | `projection_cursor.stream_seq` against the stream's last sequence per partition; the bridge's published count against the stream's message count | continuous |
| Repair | "rebuild from where?" | restart: snapshot + replay from `stream_seq + 1`. Destroyed SQL: replay the stream's retained window into an empty view, then snapshot. Beyond the window: the archive-replay tool, as today | — |

What SQL no longer gets: one row per trade side. `trades` is a per-account window, so settlement and the
live-blotter recon read the ledger instead (§7.6), and `TRADE_ID_CONFLICT` dedupe is replaced by the cursor: a seq at
or below the cursor is a replay and is ignored.

### 7.3 Write path (prototype, `traderx.trade-projection.mode=snapshot`)

- `TradeView`, one per owned partition: positions and per-account blotter rings in memory. A fill is booked with the
  same `TradeService.book` math as the row path (average cost, bond face-weighting), so positions are bit-identical to
  what the row path would hold.
- The consumer is ephemeral and starts at `projection_cursor.stream_seq + 1`; the cursor is the acknowledgement.
- `TradeSnapshotStore.write`, every `snapshot-ms`, in ONE transaction under the partition's lock row: the changed
  positions (multi-row upsert), each changed account's window (insert the window's rows, delete the account's rows
  outside it), the cursor, and the scope's counters. A failed snapshot is re-marked and retried at the next tick.
- Restart: load the scope's positions and windows, replay from the cursor. `TradeView.apply` ignores `seq ≤ cursor`.
- Bonds fail closed on this path too (2026-10-27): `TradeService.book(order, position, metadata, at)` runs the row
  path's `bondRejection` first (metadata resolved once per bond per batch). A rejected side lands `Rejected` on the
  blotter window and never reaches a position, not even a flat one, and never counts toward settlement.
  `TradeViewTest.aBondWithoutReferenceDataIsRejectedAndNeverReachesAPosition` fails with the rejection removed
  (A/B on the empty-root render) and passes with it.
- Trade time is the ledger's: `created` and the settlement date come from the captured envelope's `date` (the
  bridge's clock), so a replay books the same dates the first pass did.

The SQL rate is bounded by the state, not the trade rate: per snapshot at most (dirty positions) + (dirty accounts ×
blotter depth) rows, i.e. for 1,000 accounts × 50 symbols and depth 100 at most 150K rows per interval, however many
trades arrived.

### 7.4 Measured

Same rig shape and method as §4.2, on a dedicated VM (`dsq-store`, `c4d-standard-16`, 2026-10-27 ~1:05–1:15 am ET;
deleted afterwards). Snapshot interval 5 s, blotter depth 100, orders in memory. "Drain" includes up to one snapshot
interval, because a step counts a trade as booked only when its snapshot has landed in SQL.

| Arm | Feed | Offered msgs/s | Booked | Dropped | Rows/s | CPU µs/row | vs row path |
|---|---|---|---|---|---|---|---|
| store, P=1 | trades | 80K | 1,200,000 / 1,200,000 | 0 | 47,329 | 13.1 | 25,671 |
| store, P=1 | trades | 160K | 2,400,000 / 2,400,000 | 0 | 72,390 | 10.8 | — |
| store, P=1 | both | 160K | 2,400,000 / 2,400,000 | 0 | 45,146 | 23.8 | 47,646 (both, P=1) |
| store, P=4 | trades | 160K | 2,400,000 / 2,400,000 | 0 | **115,163** | 15.1 | 31,491 (P=4) |
| store, P=4 | trades | 320K | 4,800,000 / 4,800,000 | 0 | **158,755** | 9.1 | — |
| store, P=4 | both | 320K | 2,400,000 + 2,400,000 | 0 | **172,091** | 9.4 | 57,784 (P=4, both) |

What SQL received for all of that: after the P=1 arm (4.8M trade sides booked) `trades` held **100,000 rows** (1,000
accounts × the 100-fill window) and `positions` 1,960 rows (the keys the mix touches), with the cursor at stream
sequence 6,000,000. The database's write rate is now set by the snapshot interval and the number of dirty accounts,
not by the trade rate.

**Loss under a crash, the hard case.** 600,000 trade sides were published at 973K msgs/s and the replica was
`kill -9`ed 5 s later, before its first snapshot (cursor empty, `trade_rows` 0). After restart:

| Check | Result |
|---|---|
| `trade_rows` | 600,000 of 600,000 |
| Cursor | stream sequence 600,000 |
| tradeSeq gaps | 0 |
| SQL `trades` rows | 100,000 (the windows), `positions` 1,960 |
| Every position vs an independent recomputation from the load mix (`expect.py`) | 1,960 keys: 0 missing, 0 wrong, 0 extra |

**Against 400K orders/s** (~400K trade sides/s): four partitions on one 16-vCPU VM reached 159K trade sides/s with
the trade consumers CPU-bound at ~9 µs per side, so ~10 partitions of this consumer cover the trade side, and the
database is no longer in the equation; the order side was already covered (§4.4). What is left to size is the stream
(1.34M msgs/s, 662 MB/s measured on one node; retention must span the longest outage plus the snapshot interval) and
the consumer's parse cost, where the compact wire of §8 item 3 is the next lever.

### 7.6 Settlement and recon on the ledger

Both used to treat SQL `trades` as the ledger. With the trade store they read the system of record:

| | Row path (`TRADE_PROJECTION_MODE=rows`) | Trade store (`snapshot`) |
|---|---|---|
| What is settled | `SettlementService` flips each `trades` row `Processing` → `Settled` at T+N | a side traded on UTC date D settles at D + N business days (the row path's own `plusBusinessDays`), so the settlement state of a partition is its **booked sides per trade date**: `projection_trade_days`, written in the same transaction as the cursor. `GET /v2/projection-feed/settlement?asOf=` sums them per partition. The window rows are still flipped by the same sweep, so the blotter shows `Settled` |
| Recon "is this fill in the projection?" | `trades.findById(id)` | the engine's blotter page is matched against the **capture stream**, read forward only as far as the recon has reached; a side is MATCHED when the stream holds it with the engine's fields **and** its partition's committed `projection_cursor.stream_seq` covers it; MISSING only once the stream has moved past its tradeSeq without it; anything else waits (lag is not a verdict) |

Proved on the rig (`dsq-ledger`, c4d-standard-16, 2026-10-27 9:31–9:33 am ET), P = 2, T+1, 220,000 sides with three
trade dates (today, Thu 10/22, Fri 10/23), against `LedgerCheck`, which reads every message straight off the stream
and applies its own business-day rule:

| asOf (UTC) | Service settled (p0, p1) | Independent settled (p0, p1) |
|---|---|---|
| Fri 10/23 12:00 | 60,000 (30,000, 30,000) | 60,000 (30,000, 30,000) |
| Sat 10/24 12:00 | 60,000 (30,000, 30,000) | 60,000 (30,000, 30,000) |
| Mon 10/26 12:00 | 120,000 (60,000, 60,000) | 120,000 (60,000, 60,000) |
| now, Tue 10/27 13:31 | 120,000 (60,000, 60,000) | 120,000 (60,000, 60,000) |
| Wed 10/28 12:00 | 220,000 (110,000, 110,000) | 220,000 (110,000, 110,000) |

The Friday trades settle Monday, not Saturday. The 100,000 window rows were all `Settled`, matching an independent
per-row check (0 wrong). After `kill -9` and restart the endpoint returned the same counts, and all 1,960 positions
matched `expect.py`. The recon side is proven live in §7.8.

### 7.7 Stream retention

The stream must hold everything from the oldest committed cursor forward, so its retention is sized to the longest
outage the projection must replay from, not to a history requirement (the kdb capture is the history).

| | 400K tier (hxb shape) | `traderx-demo` (the repo's manifests) |
|---|---|---|
| Stored bytes per trade side (measured, JSON wire) | 462 | 462 |
| Stored bytes per order update (measured) | 588 | 588 |
| Rate | 400K sides/s + 600K order updates/s = 537.6 MB/s | 10K + 10K msgs/s sized peak = 10.5 MB/s |
| Longest outage replayed from | 30 min (a node replacement plus a database restart, back to back) | 30 min |
| Bytes | 968 GB | 18.9 GB |
| Stream limits | `max_bytes` 1 TiB, `max_age` 2 h | `PROJECTION_FEED_STREAM_MAX_BYTES` 20 GiB, `_MAX_AGE_HOURS` 2 |
| NATS store | 1.2 TB local SSD/PD | `max_file_store` 24G on a 32 Gi `standard-rwo` PVC |

Set in the manifests: the trade-processor deployment carries the two stream limits (applied to an existing stream
too: `ensureStream` now updates limits that differ), and `cluster/gke/nats.yaml` gets a YU18 override that moves
JetStream from `emptyDir` to a PVC with a store cap (`prepare.py` prefers the YU18 file). On `emptyDir` a NATS restart
recreated the stream at sequence 1 under the cursor; replaying it would have ignored every sequence below the cursor.

Two preconditions make a limit that was too small loud instead of silent. Both stop the process (exit 3) with the
reason in the log:
- `RETENTION_GAP`: the stream's first sequence is above `cursor + 1`, so something this partition needed was
  discarded.
- `STREAM_RESET`: the stream's last sequence is below the cursor, so it is a different stream.

An idle partition does not trip `RETENTION_GAP`, because its cursor follows the stream head while it is caught up
(empty fetch, nothing pending).

Shown on the rig with the stream capped at 200,000 messages. The projection stopped at cursor 100,000, and 200,000
sides arrived during the outage, so the stream held 100,001..300,000. The oldest retained message was exactly the next
one needed. The restart replayed from it, and all 1,960 positions matched the recomputation over both batches. Two
more sides moved the first sequence to 300,003 above a cursor of 300,000, and the restart refused with
`RETENTION_GAP ... sequences 300001..300002 were discarded`. Recreating the stream under the cursor refused with
`STREAM_RESET`.

### 7.8 Live: `BRIDGE_PARTITIONS=4` on a real three-member cluster

`traderx-demo`, 2026-10-27, three members on their own tainted pool (3× c4d-standard-8) and the support pods on a second
one (2× e2-standard-4), so production's pools were untouched. Members `BRIDGE_PARTITIONS=4`; trade-processor
`PROJECTION_FEED_MODE=jetstream`, `PROJECTION_FEED_PARTITIONS=4`, `TRADE_PROJECTION_MODE=snapshot`; `cluster-node` and
`trade-processor` built from this branch on an empty-root render, the other images at the rollout plan's digests. Load:
crossing AAPL limit orders between the 11 seeded accounts through the gateway's `/orders/batch`, 8 in flight. The
independent check nets the fills in the ENGINE's own blotter (`/recon/trades/blotter`, the members' committed log) and
compares them with SQL `positions` as per-run deltas.

**Final run, one build, clean namespace** (`traderx-dsq2`, 11:32 am ET; both fixes below):

| Check | Result |
|---|---|
| Orders | 200,000 at 37.5K/s, all accepted |
| Engine fills (trade sides), p0 / p1 / p2 / p3 | 9,091 / 54,546 / 72,727 / 63,636 = 200,000 |
| Projected sides, p0 / p1 / p2 / p3 | 9,091 / 54,546 / 72,727 / 63,636 = **200,000** |
| Drops | **0**: no bridge drop line on any member, no NATS ingest warning, trade-processor `dropped` 0 |
| Per-partition lag, max pending messages | 6,437 / 49,263 / 65,675 / 57,458; all partitions back to 0 within 12 s |
| Positions vs the engine | 24 (account, security) keys, **0 wrong** |
| Snapshot writes | 23, **0 failed** |
| Ledger recon (§7.6) | **matched 200,000, missing 0, field mismatch 0** |

**How we got there** (`traderx-dsq`, 10:08–10:54 am ET). Run 1, the same burst at 38K/s on the manifests as they
were, found silent loss. The bridges core-publish; NATS dropped what overflowed the stream's ingest queue ("excessive
stream ingest rate ... IPQ len limit reached"). The order bridge's queue also filled (≥30,000 order updates dropped,
counted in its log). Only 104,281 of 200,400 trade sides reached the projection. The ledger recon named every lost
side by id; after two further runs it stood at **matched 504,281, missing 96,119, field mismatch 0**, exactly
104,281 + 200,000 + 200,000 and 200,400 − 104,281. With the ingest queue raised, runs 2 and 3 (29K/s and 75K/s)
projected 200,000 of 200,000 each with 0 wrong positions.

Two fixes, both in the repo:
- **NATS ingest queue** sized per tier under its memory limit: GKE `max_buffered_msgs` 2,000,000 / `max_buffered_size`
  1GB with 1 CPU and 2 Gi (`cluster/gke/nats.yaml`); kind 500,000 / 256MB at 768 Mi; base tier 1,000,000 / 512MB.
  The stream survived the NATS restart on its PVC and trade-processor resumed every partition at its cursor.
  The production `traderx` render (`scripts/gcp-compsci/prepare.py`) takes the GKE file, so it carries these limits;
  production today runs the core feed with one partition and no stream captures `/trades` or `/orders`, so this drop
  cannot occur there until it moves to `PROJECTION_FEED_MODE=jetstream`.
- **One lock row for every partition's snapshot write.** Partitions own disjoint accounts, yet their window writes
  deadlocked (8 in run 1's first minute; each retried at the next tick, so nothing was lost).
  `TradeSnapshotStoreConcurrencyIT` reproduces it on the shipped schema under REPEATABLE READ and READ COMMITTED
  alike, and passes 3 of 3 with the shared row. READ COMMITTED alone did not fix it; run 3's zero was load, not the fix.

### 7.9 Making the bridge's publish loss-detecting

A bigger ingest queue moves the cliff; it does not remove it. What NATS tells anyone when it drops, from the
nats-server v2.14.8 source (`server/stream.go`, `queueInbound`): a `RateLimitWarnf` log line, deduplicated by its text,
so it says "drops happened", not how many; and, **only if the message carried a reply subject**, a
`429 Too Many Requests` reply (`JSStreamTooManyRequestsError`) for that message. There is no advisory and no counter
for stream ingest drops (`jetstream_events.go`'s `api_limit_reached` advisory covers the JS API request queue, not
stream ingest). A core publish, which is what the bridges do, gets nothing.

Measured on a dedicated VM (`dsq-bench`, c4d-standard-16, 2026-10-27 ~11:55 am ET): NATS 2.14 with 4 CPUs, a file
stream, one publisher thread, 450-byte messages over 4 partition subjects, 2,000,000 offered at full speed. "Receipt"
is a core publish with a reply subject: the publisher never waits, and one async subscription counts the stream's
replies.

| Publish | Default ingest queue | Raised queue (2M / 1GB) | NATS CPU per message |
|---|---|---|---|
| Core (today's bridge) | 1.35M msgs/s; **893,759 lost, publisher told nothing** | 1.68M msgs/s, 0 lost | 1.5–2.1 µs |
| **Receipt** (reply subject, no wait) | 683K msgs/s; 727,058 lost and **727,058 counted as 429 by the publisher** | 506K msgs/s, 0 lost, 2,000,000 acks | 2.8 µs |
| JetStream acked, window 1K / 8K | 363K / 344K msgs/s, 0 lost | 348K / 334K msgs/s, 0 lost | 4.3–4.6 µs |

Cost at 400K orders/s (~400K trade sides + ~600K order updates = ~1M msgs/s), from those rates:

| | Knows about every drop | Prevents the drop | Publisher threads on the leader | NATS CPU | Size |
|---|---|---|---|---|---|
| Core + raised queue (done) | no | bursts up to the queue (~2 s at 1M/s) | 1 | 1.5–2.1 cores | done |
| Log alert on the warning (Loki/promtail are in the render) | yes/no only (deduplicated) | no | 1 | same | config |
| **Receipt** | **yes, exact count** (bridge's `dropped`) | no; the side is still lost, but counted at its source and re-sendable | 2 (one per partition gives 4×) plus one reply-dispatcher thread taking ~1M acks/s | ~2.8 cores (+0.7–1.3) | small–medium: publish with a reply subject + one subscription in `TradeNatsPublisher`/`OrderNatsPublisher` |
| JetStream acked, bounded window | yes | **yes**: a full window stalls the bridge thread, its queue fills, and drops move into the bridge's own counted queue | 3 (one per partition) | ~4.4 cores (+2.3–3) | medium |
| Trade-side contiguity alarm in trade-processor | yes for trades (tradeSeq holes), not order updates | no | 0 | 0 (~0.4 core in trade-processor) | small |

Recommendation (built, §7.10): the **receipt publish**, one publisher per partition. It gives an exact drop count at the source for
~1 extra NATS core and no wait on the bridge thread, and its count is the trigger for a replay from the archive once
the archive-reader bridge of §8 item 1 exists. Move to the JetStream-acked publish only if back-pressure is wanted over
counting (it costs ~2.5 more NATS cores and three times the publisher threads).

### 7.10 Receipt publish (built)

`ReceiptLanes` (order-matcher, `cluster/`) replaces both bridges' single publish thread. The apply thread still only
offers to a lock-free queue, now the queue of the record's partition (`accountId mod BRIDGE_PARTITIONS`). Each partition
has its own queue, publisher thread and NATS connection, so a partition's messages leave in the order the engine produced
them. Every message carries a reply subject (the trade bridge uses `<inbox>.<trade id>`, so a reply names its side; the
order bridge one inbox per lane), and one async subscription per lane counts the stream's replies: PubAck = stored,
429 = dropped at ingest. Connections use `noNoResponders`, so a core-feed tier with nothing captured draws no 503s.

| Signal | Where |
|---|---|
| `traderx_bridge_ingest_rejected{member,bridge}` (+ `_published`, `_dropped`) | member `/metrics` |
| `"bridges":{"trades":{"published","acked","rejected","rejectedByPartition":[...]},"orders":{...}}` | member `/health` |
| `ALERT trade-nats-bridge partition 2 (/trades.2): NATS dropped N message(s) ...` (first, then every 10,000) | member log |
| `ALERT trade-nats-bridge partition 2 (/trades.2) NATS-rejected ids: 183750-S 183752-S ...` (batches of 500) | member log |

**Alarm, not retry.** A 429'd message is not resent. By the time its reply arrives the partition's later messages may
already be stored, so a resend lands after them: the projection would book that partition out of engine order, and
average cost is order-dependent. Resending in order needs a stop-and-wait per message (the acked publish's throughput,
§7.9) or a replay from a source that can resume at a position, which is the Aeron archive (§8 item 1). So the lane makes
the loss exact and loud, with the id, and the repair is a replay.

Tests: `ReceiptLanesIT` runs a real NATS 2.14 with a 64-message ingest queue and pushes 60,000 trade sides through the
trade bridge on two partitions. It requires stored + rejected = published exactly, at least one forced drop, and
strictly increasing tradeSeq per partition in the stream. With the 429 counting disabled it fails ("a drop went
uncounted ... expected 60000 but was 4330x"); as shipped it passes 3 of 3.

**Live** (`traderx-dsq3` on `traderx-demo`, own tainted pools, 2026-10-27 12:47 pm–1:09 pm ET; members on the receipt
build, `BRIDGE_PARTITIONS=4`, trade store P=4):

| | Burst A: default ingest queue (12:48 pm) | Burst B: 2M queue (1:06 pm) |
|---|---|---|
| Orders | 200,000 at 17K/s | 200,000 at 78K/s |
| Engine fills, p0 / p1 / p2 / p3 | 9,091 / 54,546 / 72,727 / 63,636 | same |
| Stored and projected | 8,364 / 50,113 / 66,855 / 58,476 = 183,808 | 9,091 / 54,546 / 72,727 / 63,636 = 200,000 |
| **Bridge 429s (trades), by partition** | **727 / 4,433 / 5,872 / 5,160 = 16,192** = fills − stored, per partition | **0** |
| Bridge 429s (orders) | 32,308 of 400,000 | 0 of 400,000 |
| Ids the bridge named | 16,192, **identical** to (engine blotter ids − stream ids) | — |
| Ledger recon | matched 383,808 (183,808 + 200,000), **missing 16,192, the same id set**, mismatch 0 | |
| Positions vs the engine | 6 keys wrong (the lost sides) | 24 keys, 0 wrong |

A's losses were all in the burst's tail, past each partition's last stored side, so the recon held them as WAIT until
burst B moved every partition on, then named exactly those ids. The receipt work also exposed a recon assumption: with
one lane per partition the stream interleaves partitions, so "the stream has moved past this side" must be judged per
partition subject (`PartitionHeads`); against a global head the recon had called 897 still-arriving sides MISSING.

## 8. Open items and next steps

1. **Failover gap.** Only the leader publishes, from a queue the apply thread fills; a leader kill loses up to 64K
   events per bridge. Options, in order of cost: (i) make the new leader republish from its own last-published sequence
   (needs a published-sequence handshake between members — off-consensus state, so a protocol addition); (ii) replace the
   bridge with an Aeron Archive reader that publishes from the log and can resume at any position (DOWNSTREAM.md §5 item
   3). (ii) is the class-closing fix and also removes hop 1's drop-on-full.
2. **Order view after a long restart.** The view rebuilds from the last `replay-seconds` of the stream, so an open order
   older than the window that received no update since is absent until it changes. The complete fix is an order-book
   snapshot from the engine at a sequenced marker — the position cut's mechanism applied to resting orders — and it is the
   same change that would give FIX a true "as of N" status. Until then, size the window to the session, or keep
   `persist=true` on tiers below 100K/s where the SQL rows are affordable.
3. **Stream sizing.** At ~450 MB/s of JSON the capture stream is a disk budget: ~1.6 TB/hour. A compact binary encoding of
   the bridge wire (SBE-shaped, the same fields) is a ~5× reduction and is the next lever on both the stream and the
   consumer's parse cost (`ParseBench`: ~1 µs per message of JSON). It is a wire change for the projection consumers only
   (the cluster's own wire is untouched) and must be called out as such.
4. **Run transitions vs partitions.** `ProjectionTransitionService` takes lock row 1 only; before running partitions
   alongside a transition it must take rows 1..P.
5. **Trade store: decided and prototyped (§7).** The append-only log is the JetStream capture stream; SQL holds
   snapshots. Bond fail-closed, settlement and recon on the ledger, and retention sizing are done (§7.3, §7.6, §7.7).
   A forced settlement (`POST /trades/{id}/settlement/force`) reaches only rows still in the window.
6. **Live cluster.** Done on a real three-member cluster (§7.8).
