# ADR-098: The full order and fill history lives in ClickHouse, fed by its own service from the capture stream

Status: accepted 2026-10-30 (yaakov approved a full-rate history store; ClickHouse won the bake-off, 388ae266).
Code on `traderX-risk-integration`; local and kind only, not deployed to GKE. Pack:
[history store](../components/history-store/spec.md) (FR-HST-01..07).

## Context

The snapshot read model ([ADR-097](adr-097-production-read-model-at-volume.md)) keeps current state in SQL and
the trades in the stream for its retention window. Blotter history, TCA and the regulatory/EOD export need every
order event and every fill, kept, at the full 400K orders/s (1M rows/s). MariaDB cannot take that rate (§4 of
`docs/engineering/downstream-at-400k.md`). The bake-off (`docs/engineering/history-store-bakeoff.md`) put ClickHouse,
QuestDB and KDB-X Community through the same 600M-event drain with a `kill -9`. ClickHouse kept up with the rate on
3.8 cores, and it needed the least disk per hour. It answered the per-account reads in about 5 ms and exported
the day 5–18× faster than the others. KDB-X Community matched its ingest, but the licence caps the heap at 16 GiB
per instance and forbids third-party hosting, and per-account reads scan until an end-of-day sort. QuestDB stayed
below the rate.

## Decision

1. **ClickHouse is the history store.** Tables `trades` and `orders`: MergeTree, `PARTITION BY toDate(ts)`,
   `ORDER BY (account, ts)`, fsync after every insert. Every row carries its partition and its stream sequence.
2. **A separate service, `history-feed`, writes it.** It is a reader of the capture streams only, with the
   trade-processor environment names (`PROJECTION_FEED_STREAM`, `_PARTITIONS`, `_STREAM_LAYOUT`), so it reads exactly
   the streams trade-processor creates. It never creates or sizes a stream. Its own process and its own durables
   (`history-<feed>-p<p>`) keep its failures and its lag away from the projection.
3. **The store is the cursor.** A lane opens its consumer at the store's max(seq) + 1. It does so at every start,
   on any jump in the consumer sequence (FR-DRS-33), and after any failed write. The ack follows the durable insert
   and moves only the stream's ack floor. Exactly-once does not rest on the ack: resuming a durable lost 19 of 360M
   events in the bake-off. A batch never crosses a UTC day, because one INSERT is atomic only within one date partition.
   The cursor is bound to the stream's incarnation, its creation time (FR-HST-07). A lane refuses to resume on a
   stream recreated under the same name, because the number alone would skip that stream's first messages. Rows with no recorded stream are refused
   too (`STREAM_UNBOUND`) until an operator reconciles them and attests. The attestation is the binding itself, the
   reconciled stream's creation time, written only while the lane is unbound at the reconciled cursor, so a replay
   grants nothing and a stream recreated after it is refused as a reset (codeX pass 3 §16). There is no sentinel; the
   rule is trade-processor's (FR-DRS-36).
4. **Exactly one writer.** The lanes are not fenced, so the Deployment has one replica and `Recreate`. Scaling past
   one process means one process per partition set, by configuration, not replicas.
5. **The queries go through the service**, never straight to ClickHouse: the four paths the bake-off measured, plus
   the per-day CSV export. Every caller value is a ClickHouse bound parameter.
6. **Opt-in manifests** (`history-feed/k8s/`): applied by `scripts/history-feed/kind-smoke.sh` on kind. Neither the
   base kustomization, the CompSci render nor GKE includes them. Amended 2026-11-07 (ADR-102, FR-THT-02): the CompSci
   render includes them, sized on their own `history-pool`; still not in the base kustomization.

## Consequences

- A history-feed outage is lag, not loss, while it is shorter than the stream's retention. A longer one is a
  retention gap the store cannot fill from the stream.
- A restart pays the cold consumer create, up to ~100 s per lane on a 150M-message stream (bake-off §5.2).
- ClickHouse's `du` overstates its size for some minutes after heavy ingest (merged-away parts); the size is the
  active parts in `system.parts`.
- Putting it on the production rig needs a ClickHouse volume and host size (21.8 GiB per 10 full-rate minutes,
  ~131 GiB/hour) and the ingest ceiling of the capture stream itself, which one NATS server does not yet give at 1M
  msgs/s (bake-off §5.1; the feed-capture lane).

**Amendment 2026-11-07 (production e1107b, `tip-aba349f8-j21`):** the store recorded nothing. The default render runs
one partition (no `BRIDGE_PARTITIONS`, `PROJECTION_FEED_PARTITIONS=1`, layout `one`), so the bridges publish bare
`/trades` and `/orders` on TRADERX_FEED; history-feed always suffixed `.<p>` and its lanes filtered `/trades.0` and
`/orders.0`, matched nothing, and reported nothing pending (cursor 0, 0 rows against the members' 6 sides). G3h passed
because the epoch was empty at bring-up. Every proof before it ran 4 partitions on per-partition streams
(`kind-yu18-rig.py`'s default; kind `traderx-tht2` and GKE `pa-proof-tht`), where the suffix is the bridges' rule too, so
they did not cover the default shape. The lane now takes the bridges' rule (`HistoryFeedMain.subjectFor`, pinned
against it in `RowsTest`), and `/health` names any stream subject no lane reads, which G3h refuses (FR-HST-02,
FR-THT-06). Tests: `HistoryFeedSubjectsIT` (NATS 2.14 + ClickHouse 25.8: P=1 bare subjects stored, `unowned {}`; a
2-partition feed over them stores 0 and reports `{"/orders":3,"/trades":6}`; with the old always-suffixed rule the first
half times out) and `test_rig.Up.test_a_stream_subject_no_lane_reads_is_refused`.

**Amendment 2026-11-08 (production e1108b, `tip-1ef5e86e-j21`):** the feed ingested about 1,500 rows/s. Its consumers
asked for `maxAckPending(-1)`, and NATS 2.14 stored the server default, 1000. The ack follows the INSERT, so a lane held
1000 unacked, each fetch waited out its 100 ms expiry, and the batch flushed on its 500 ms timer. A 9.66M-trade load
left 3.07M trades in the store and 16.2M order events queued, and G3h timed out after 600 s. The bake-off never hit
this: it acked to a store-reported watermark (FR-HST-02) instead. A lane now asks for 2 x batch + fetch, reads the
granted value back, and does not start below one batch. G3h's wait is 600 s plus the backlog at 20,000 rows/s
(FR-THT-10). Locally the same 5M backlog now drains in about 13 s at P=1, against about 30 minutes before.

**Amendment 2026-11-09 (GKE `pa-proof-400k`, 8 partitions):** the capture stream is the ceiling this ADR's consequences
named. One NATS server (44 CPUs, 160Gi) was lossless at 202K feed messages/s (67K orders/s, every count equal) and
dropped at stream ingest from 285K messages/s. The feed and ClickHouse drained at 640K to 813K rows/s once NATS
delivered. A side the bridge published twice was stored twice: the store's identity is the stream sequence, and
G3h refuses it. Both are RI34.

**Amendment 2026-11-09 (each side once, FR-THT-11):** the store's identity is the side, not the stream message. The
bridges republish by design (FR-DRS-37/38, at least once), and ClickHouse is the trade system of record (ADR-102), so
a republished side must not be a second row. Decision: the single writer leaves repeats out at the INSERT.
- A lane's positions only rise (`tradeSeq`; (`consensusSequence`, `outputOrdinal`)). The lane keeps the highest it
  has stored. A batch above it is written as before. A batch that goes back over it is written by an INSERT whose
  `WHERE ... NOT IN (SELECT ... FROM <table> WHERE part = p AND <the batch's range>)` and `LIMIT 1 BY` leave out the
  rows the store or the batch already holds.
- A row is a repeat only if it equals a stored row in everything the engine produced: every column but `seq`, and for
  a trade side `ts`. The same id with other content is stored and G3h refuses the store. A dropped row can never be a
  different fact.
- Exactly one writer per (table, part) (decision 4) is what makes look-up-then-write safe without a lock.
- Order events are keyed by (`consensusSequence`, `outputOrdinal`), which only epoch-v1 ids carry. Under the legacy
  id scheme, which the production render runs today (no run descriptor), an order event has no position: it is never
  left out, and the bridges do not republish it. Trade sides are covered under both schemes by `tradeSeq`. Holding
  legacy order events once would mean treating equal content as the same event at a lookup on every batch; not done.
  From FR-DRS-40 (2026-11-09) the bridge writes the position under the legacy scheme too, so a legacy order event
  is left out like any other; only a message from an earlier member build still has none.
- What a query sees before a background merge: nothing different. The tables stay plain MergeTree and hold each side
  once from the moment the INSERT returns, so no reader changed.

Rejected:
- **ReplacingMergeTree, readers on `FINAL` or `argMax`.** Repeats stay visible until a merge, so G3h, the blotter,
  TCA, the export, the recon and trade-processor's settlement lookup would each have to read `FINAL` or double-count.
  Merges never cross a date partition, and a republished side's `ts` is its new publish time, so a repeat across
  midnight UTC would never merge away. The existing tables would need a rebuild (engine and sort key).
- **ClickHouse insert deduplication (a token, or the block-hash window).** It drops a retried identical block. A
  republished side arrives in a different block.
- **A high-water mark alone in the feed** (refuse any position at or below the lane's highest, as trade-processor
  refuses by `tradeSeq`). It drops without asking the store, so a record that arrives late, behind newer ones, would
  be lost from the system of record. The high-water mark only chooses which INSERT to use.

Consequences: the store's rows are the stream's messages less the repeats, so "store rows = stream messages" holds
only for a stream with none; `/health` `duplicates` counts what a process left out. The leaving-out INSERT reads the
lane's stored rows in the batch's range: 4-8 ms locally on unmerged parts, 70-120 ms inside one merged 40M-row part,
and only for a batch that goes back. A store that already holds repeats is not cleaned by this.
