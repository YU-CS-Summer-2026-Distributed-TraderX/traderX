# RI34 — The feed capture stops being lossless near 285K messages/s, and a republished side is stored twice

Opened: 2026-11-09 (the 400K campaign, namespace `pa-proof-400k` on traderx-demo, torn down). Evidence: shared notes
`history-feed-rate-20261108/campaign-400k/`. Spec: trade-history-tiers FR-THT-10, FR-THT-11.

Status: section 1 (the NATS capture ceiling) is open and not diagnosed. Section 2 is fixed in the store's writer
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
