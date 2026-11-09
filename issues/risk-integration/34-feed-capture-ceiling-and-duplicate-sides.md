# RI34 — The feed capture stops being lossless near 285K messages/s, and a republished side is stored twice

Opened: 2026-11-09 (the 400K campaign, namespace `pa-proof-400k` on traderx-demo, torn down). Status: open, not
diagnosed. Evidence: shared notes `history-feed-rate-20261108/campaign-400k/`. Spec: trade-history-tiers FR-THT-10.

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

To find out: why the bridge republished (a retry on a slow or refused publish, or FR-DRS-37's replay), and whether
history-feed should refuse a trade_seq it already holds for the partition, as trade-processor does.
