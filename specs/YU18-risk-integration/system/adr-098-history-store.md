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
   too (`STREAM_UNBOUND`) until an operator reconciles and attests them with the binding `0`, which the next bind
   uses up: the same values as trade-processor's `projection_cursor` (NULL unbound, 0 attested).
4. **Exactly one writer.** The lanes are not fenced, so the Deployment has one replica and `Recreate`. Scaling past
   one process means one process per partition set, by configuration, not replicas.
5. **The queries go through the service**, never straight to ClickHouse: the four paths the bake-off measured, plus
   the per-day CSV export. Every caller value is a ClickHouse bound parameter.
6. **Opt-in manifests** (`history-feed/k8s/`): applied by `scripts/history-feed/kind-smoke.sh` on kind. Neither the
   base kustomization, the CompSci render nor GKE includes them.

## Consequences

- A history-feed outage is lag, not loss, while it is shorter than the stream's retention. A longer one is a
  retention gap the store cannot fill from the stream.
- A restart pays the cold consumer create, up to ~100 s per lane on a 150M-message stream (bake-off §5.2).
- ClickHouse's `du` overstates its size for some minutes after heavy ingest (merged-away parts); the size is the
  active parts in `system.parts`.
- Putting it on the production rig needs a ClickHouse volume and host size (21.8 GiB per 10 full-rate minutes,
  ~131 GiB/hour) and the ingest ceiling of the capture stream itself, which one NATS server does not yet give at 1M
  msgs/s (bake-off §5.1; the feed-capture lane).
