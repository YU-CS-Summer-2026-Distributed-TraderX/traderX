# RI34 — The feed capture stops being lossless near 285K messages/s, and a republished side is stored twice

Opened: 2026-11-09 (the 400K campaign, namespace `pa-proof-400k` on traderx-demo, torn down). Evidence: shared notes
`history-feed-rate-20261108/campaign-400k/`. Spec: trade-history-tiers FR-THT-10, FR-THT-11.

Status: section 1 (the NATS capture ceiling) is diagnosed as a per-message cost and answered in the bridges by drain
batching (FR-DRS-41, 2026-11-09, not deployed, not yet measured on GKE; §1a). Section 2 is fixed in the store's writer
(FR-THT-11, 2026-11-09, not deployed): history-feed leaves out what the store already holds. Why the bridge published
those sides twice in that run is not diagnosed; the bridges republish by design (FR-DRS-37/38).

## 1. One NATS server loses feed messages well below 400K orders/s

Rig: the partitioned render (8 partitions, FR-DRS-34 streams), 18 gateways, the bench's member settings, NATS on
c3-standard-44 with 160Gi (c3-standard-8 / 24Gi was OOM-killed three times in the first attempt). Load: binary-load.mjs.

| Run | Orders/s (effective) | Feed messages/s | Result |
|---|---|---|---|
| flat, 10 min | 67K | 202K | PASS: members = read model = history store = 40,252,682; stream 120,758,046 = store rows |
| flat, 10 min | 100K | 285K | drops from minute 7.4: 1,021,951 sides never reached the stream; consumers behind from the start |
| ramp step 2 | 147K | 320K+ | drops; 31,213,615 of 92,284,970 sides missing from the stream |

- The members' bridges core-publish, and NATS rejects at stream ingest ("IPQ len limit reached", 429). The bridges log
  each loss as an ALERT.
- As ingest rises, NATS stops delivering to the pull consumers. history-feed, ClickHouse and trade-processor sat under
  1 CPU each while NATS used 17 to 29 CPUs and its memory grew (159Gi in the second attempt).
- With the fill feed on, each of the 18 gateways holds a consumer on every partition stream: 23 consumers per stream
  instead of 5. That run dropped earlier. The partitioned render multiplies fill-feed consumers by the partition count.
- history-feed and ClickHouse are not the limit: after a load ended they drained an 83M backlog at 640K rows/s (peak
  813K rows/s in the third attempt).

To find out: whether NATS's cost is the publish path (core publish, no flow control), the consumer count, or the
single server; and whether the bridges should publish with acknowledgement and back-pressure instead of losing.

### 1a. Diagnosis and the first answer (2026-11-09): the cost is per message, so a lane publishes its drain as one

- Where the pipeline stopped, in order. The load driver delivered 174K orders/s at the 400K setting: every pod sat at
  its 16 x 2,000 in-flight cap (`unanswered` steady at about 31.9K) with 10 s phases and a 3 s pause, so the 185K
  "member ceiling" was read behind the driver. The leader with the bridges on held about 185–200K orders/s at 4.8 of
  7 CPUs (16 publisher and 16 receipt threads encoding about 3 JSON messages per order; the bench's 400–450K knee was
  measured with the bridges off). One NATS server at one record per message dropped from about 285K messages/s, and
  FR-DRS-34 had already seen one c4d-standard-32 at 415–505K messages/s with its disk 16% busy and CPU 35–60% idle,
  whatever the stream count: a per-message cost.
- Measured (local, one `nats:2.14` server in docker with 4 CPUs, `nats bench js pub async`, 8 clients, file store):
  540 B messages 363–406K/s (0.4M records/s); 1,080 B 385K/s (0.77M records/s); 5,400 B 150K/s (1.5M records/s);
  54,000 B 22K/s (2.2M records/s). Below about 1 KB the server is bound per message at the same ~400K/s FR-DRS-34
  found; above it, per byte (0.75–1.1 GiB/s on the laptop's disk). Evidence: shared notes `400k-options-20261109.md`.
- The answer in the bridges (FR-DRS-41, ADR-083 decisions 15–18, commit on trade-history-tiers): a lane publishes what
  its queue holds, up to 64 records and 256 KiB, as one newline-separated message with one receipt; counters stay in
  records; readers split. At 400K orders/s (1.2M records/s) that is about 20–40K messages/s on one server and the
  wall moves to bytes (about 540 MB/s of JSON), and the leader's 32 bridge threads pay the jnats publish and receipt
  path per drain, not per record.
- On kind (2026-11-09, 4:02–4:17 pm ET, `scripts/trade-history-tiers/kind-bridge-batching.sh`, production's default
  shape, one partition, about 1,400 orders/s): batching on, 491,616 records in 85,572 messages (5.7 per message), then
  503,478 in 91,019 (5.5); the control at `BRIDGE_BATCH_MAX=1`, 501,672 in 501,672. Every run: G3h ok, each order
  event once, the store gained exactly the published records and the stream exactly the published messages, 0
  rejected. At kind's rate the per-message cost is below the noise in CPU (leader lane threads 0.036 cores in both
  arms, NATS 0.19–0.21 cores); the ratio is the finding, the CPU effect needs the GKE rate.
- Not yet done: a GKE run with batching on (needs yaakov's yes and a cost projection: about $40–60 for 3 hours on
  last night's shape); the load driver's phase and in-flight settings (free, before any measurement); whether the
  leader still needs its bridges moved to a follower after batching; the FIX fill feed's 18x fan-out before any run
  with FIX on; the three-server NATS cluster (FR-DRS-34) if the single server's byte rate has no margin.

## 2. A side the bridge publishes twice is stored twice

Third attempt, 11:49-11:53 pm ET on 2026-11-08, as NATS began refusing: 1,104,397 trade sides are in the stream twice,
about 3 s apart, with different stream sequences (for example trade_seq 31414397 at /trades.3 seq 11075230 and 11226745).

- trade-processor refuses the second copy by tradeSeq ("1145553 duplicate side(s) refused").
- history-feed stores every stream message once, so ClickHouse holds both copies. Its identity is the stream sequence
  (FR-HST-01), not the trade.
- G3h counts them ("1104397 duplicated trade side(s)") and refuses, so the gate catches it. But the store is the trade
  system of record (ADR-102), and a blotter or extract reading it would see the side twice.

Fixed 2026-11-09 (FR-THT-11; ADR-098 amendment): the store holds each trade side and each order event once.
- A lane keeps the highest position it has stored (a side's `tradeSeq`; an order event's (`consensusSequence`,
  `outputOrdinal`)). A batch that goes back over it is written by an INSERT that leaves out every row the store or the
  batch already holds, equal in everything the engine produced. A batch above it is written as before.
- It is not trade-processor's refusal by `tradeSeq`: the store is asked, so a record that arrives late is kept, and the
  same id with other content is stored and refused by G3h rather than dropped.
- The tables stay plain MergeTree, so no reader changed and there is no window before a merge.
- Tests: `HistoryFeedDuplicateIT` (fails without the fix); kind `scripts/trade-history-tiers/kind-history-duplicates.sh`.
- Not covered: a store that already holds repeats is not cleaned, and the fix is not deployed.
- Not covered: order events under the legacy id scheme, which production runs (no run descriptor in the render). They
  carry no `consensusSequence` or `outputOrdinal`, so a repeat cannot be told from a second identical event, and the
  bridges' catch-up does not republish them. Whether order events were in the stream twice in the third attempt was
  not measured: the count taken then was by stream sequence.

Still to find out: why the bridge republished in that run (a retry on a slow or refused publish, or FR-DRS-37's
replay). That belongs with section 1 and the bridge.
