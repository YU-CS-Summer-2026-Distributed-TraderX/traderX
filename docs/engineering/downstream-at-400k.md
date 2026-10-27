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
| Leader failover | still a gap: the dead leader's unpublished bridge queues (≤64K events per bridge) | unchanged; see §6 |

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
- **The trade projection is still one MariaDB's worth, ~25–34K rows/s.** It does not run faster through the stream, and
  it does not scale with partitions on one database. The prototype found and fixed one serialization point: Hibernate
  flushed the batch INSERT at commit, after the shared `projection_runs` counter UPDATE had taken its row lock, so a
  second partition waited for the whole bulk insert. After the fix the samples show both partitions inside `INSERT
  INTO TRADES` at once and no lock waits, but scaling stays at 1.2–1.4×. MariaDB ran at ~21% CPU and each replica at
  35–40%, so neither is saturated. The remaining limit is **not identified**. There are three candidates:
  - Each partition's serial commit latency: one connection, one transaction per 1000 rows.
  - The per-batch position prefetch, a row-constructor `IN` over up to 1000 `(account, security)` pairs. All
    partitions were seen inside it at once, and it may scan the table rather than use the key.
  - The NATS container's 2-CPU cap, which sat at ~100% while serving the pulls and the file store.
- **Against 400K orders/s** (~400K trade sides and ~600K order updates per second): the order side is covered by
  ~4 view partitions. The trade side is ~12–16× short of what this prototype measured on one database. Closing it
  needs either shards (separate databases per partition, so the per-partition rate adds up, which this rig did not
  test) or §5 item 7 of DOWNSTREAM.md: an append-only trade store, with SQL keeping only what the UI and EOD read.
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

## 6. Open items and next steps

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
5. **Find the per-partition trade limit** before choosing between shards and an append-only store. Run an `EXPLAIN` on
   the position prefetch, then give the NATS container 4 CPUs, then try 2 connections per partition. Each is a
   one-variable rerun of the P=1/P=4 trade rows above.
6. **Live cluster.** The prototype was measured with the bridges' exact wire from a load generator, not from a running
   leader. A run on the hxb rig with `BRIDGE_PARTITIONS=4` on the members would confirm that the leader's apply thread is
   unaffected (the bridges' offer path is unchanged, so no effect is expected).
