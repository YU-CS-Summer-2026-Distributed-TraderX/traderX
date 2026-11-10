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

### 1b. GKE, 2026-11-09 evening: 400K orders/s for an hour, and what stood in the way

The disposable rig (`pa-proof-400k` on `traderx-demo`, the `p4k-*` pools, 3 members c4d-standard-8, 18 then 24
gateways c4d-standard-8, NATS c3-standard-44, trade-processor and ClickHouse+history-feed on c4d-standard-16, 8 load
pods, FIX fill feed off, batching on). Evidence: shared notes `bridge-batching-gke-20261109/` (lag.tsv, kubectl top,
leader per-thread ticks, run logs, G3h per run). Times US Eastern.

- **Batching holds.** 100K/150K/200K/300K orders/s each clean on one NATS server: 19–31 records per message, NATS
  1.6/2.2/2.8 cores, the store 246K/494K/746K rows/s and current, every far end exact (stream = published messages,
  store = published records, 0 rejected, G3h). The control at `BRIDGE_BATCH_MAX=1` on the same rig: NATS 9.2 cores
  at 100K with the store 3.3M rows behind; 14.5M rejected receipts at 200K and G3h refused (42.1M of 46.9M sides).
- **The leader does not need D1.** With batching its 16 lane threads cost 0.21 cores at 100K and 0.59 at 300K of a
  4.0-core leader (jnats readers 0.17–0.24); per-record they were 1.5 cores at 100K. Aeron's sender/receiver/conductor
  and the clustered service are the leader's cost; at 400K the leader runs 6.6 of its 7 cores, the next wall.
- **The ~300K knee was the binary acceptor, not the cluster.** Accepted stayed at 285–300K from 18 to 24 gateways
  with nothing CPU-bound, every load session at its in-flight cap. `GET /latency` on a gateway: residence p50 553 µs,
  of which `cluster` (offer to egress) 541 µs, `queue` 0.45 µs. The acceptor is one thread per TCP connection doing
  `readFully` → `dispatch` (blocks until the committed ack) → write, so a session carries one order at a time:
  128 sessions / 0.54 ms ≈ 240–300K orders/s whatever the gateway count. 256 sessions gave 344–374K, 512 sessions
  400–405K inside the sending windows (RI35: pipeline the acceptor).
- **The NATS volume was the next wall, twice.** A 416 GB pd-balanced saturated at 298 MB/s (io-busy 100%, IO pressure
  `full` 37%) at 300K; a Hyperdisk Balanced provisioned at 1,200 MB/s saturated at 684 MB/s once the streams reached
  `max_bytes` and deleted while writing at 400K. Each stall blocked a partition's JetStream API 8–30 s, cut
  trade-processor and history-feed as slow consumers (jnats 64 MB pending) and left the leader's publishes unacked.
  s2 compression on the streams (FR-DRS-42) cut the write to 311 MB/s at 32% busy at 400K.
- **A dead leader's unacked tail is lost.** With the first rig's 32 Gi archive claims full (259M orders), two members
  died at once; the store ended 1,898,565 sides short, as partition tails and one hole on the partitions the stall had
  left unacked. A lane marks a record sent when written, not acked, and only a leader change republishes from the
  stored cut; inside one leader's life nothing resends past a receipt timeout. That is the FR-DRS-33 ack-gap item, now
  with a measured shape. A real leader change at 240K (run 2) lost nothing: the store equalled both leaders' published
  records.
- **Driver.** 60 s phases and 4,000 in flight deliver the setting; the pause must cover the in-flight drain (10 s at
  these rates, 5 s was not enough when the leader saturated); `maxPositionQuantity` (default policy) is reached inside
  a 60 s phase at 200K+ (`POST /risk/control/policy` raised it for the rig); 512 sessions were needed for the acceptor.
- **The hour.** 8:54–9:54 pm ET, memory-backed streams (`PROJECTION_FEED_STREAM_STORAGE=memory`, 8 × 8 GB, NATS
  `max_memory_store` 96 GB), 8 pods × 64 sessions × 938/s (a 480K setting the leader throttles to what it accepts),
  300 s phases with 10 s pauses: every load pod ran the full 3,600 s, 175,898,606 orders each, all accepted, 0
  rejected, 0 unanswered, balanced at every boundary — **1,407,188,848 orders in the hour, 391K orders/s averaged
  with the driver's pauses in, 387–394K in every 10-minute window, 400–405K inside the sending windows**. Members
  1,408,422,316 sides. Far end: store trades 1,408,422,316 = members' sides; store rows 4,225,266,948 = the leader's
  published records 4,225,266,948 = acked, 0 rejected; stream 136,514,103 messages = published messages (30.9
  records per message); history-feed ready, 0 pending, 0 duplicates; G3h's checks by hand (count, 1..n, sum and
  sum of squares mod 2^64) hold for n = 1,408,422,316 — G3h's own ClickHouse query exceeded the 21 GiB query memory
  on 1.4B rows and must be made leaner (RI36 candidate). The read model (trade-processor) was cut once as a slow
  consumer at 9:10 pm and finished the hour 4–5M messages behind, so the run script's catch-up wait timed out and
  G3 (snapshot) was not run. NATS 4–5 cores and 142 GB RSS at its 140 GiB GOMEMLIMIT; leader 6.6 cores; archive
  113 GB of 295 at the end; ClickHouse 83 GB.

- **The hour at 464K** (11:14 pm–12:14 am ET, members on c4d-standard-16 with a 15-CPU/40 Gi container, cluster
  heartbeat/election timeouts 1000/500 ms, 8 pods × 64 sessions × 920/s, 300 s phases with 4 s pauses, 800 in
  flight, memory-backed streams): every pod 208,885,346 orders in 3,600 s, all accepted, 0 rejected, 0 unanswered,
  balanced at every boundary — **1,671,082,768 orders, 464K orders/s averaged over the hour with the pauses in,
  470–474K inside the windows**; no election, no member restart. Far end: store trades 1,671,136,274 = members'
  sides; 5,013,408,822 store rows = published records = acked, 0 rejected receipts; 136,578,305 stream messages =
  published messages (36.7 records each); history-feed 0 pending, 0 duplicates; G3h's checks by hand hold for
  n = 1,671,136,274. Gateway `/latency` at ~520K (take 4): residence p50 1.08 ms, p99 1.68 ms, `cluster` 1.05 ms of it.
- **What the 16-vCPU members needed.** The first take on them (538K setting, 16 Gi container limit, 200/100 ms
  timeouts) ran 510–527K for 17 minutes and then flapped: leadership moved, members logged `failed to join catchup
  log`, one died on `Media Driver timeout` (its Aeron driver's heartbeat missed for 10 s). The archive's page cache
  and the /dev/shm term buffers count against the container's memory limit; at this write rate a 16 Gi limit keeps
  the cgroup in reclaim and a thread can stall past a 200 ms heartbeat. At 40 Gi and 1000/500 ms the hour ran without
  an election. Production's members are c4d-standard-8 with 16 Gi: budget the page cache when sizing them.
- **The read model died in the hour** (RI36): trade-processor was OOMKilled three times (24 Gi) — its 1,000-message
  fetches of 12 KB messages across 16 feeds, pipeline depth 4 — and then refused to start with `RETENTION_GAP`: its
  snapshot cursor had fallen behind the memory stream's oldest retained message (8 GB a partition is about two
  minutes of this feed). Correct fail-closed behaviour, and a constraint: a memory-backed stream's retention must
  cover the read model's longest restart. FR-DRS-43 (the fetch sized by bytes) bounds both the in-flight memory and
  the slow-consumer cuts; it was proven on the rig after the hour (below).

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
