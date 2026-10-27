# Downstream read side tasks

- [x] Bounded feed queues with counted drops; `TradeSeqGaps`; `GET /v2/projection-feed`.
- [x] Batched writes; `TradeOrder` ignores unknown properties; no flush per publish.
- [x] `PROJECTION_FEED_MODE=jetstream` with durable per-partition consumers, ack after commit.
- [x] `BRIDGE_PARTITIONS` on the leader bridges; per-partition `PROJECTION_WRITE_LOCK`.
- [x] `OrderView` behind `ORDER_FEED_PERSIST=false`, rebuilt from the stream on restart.
- [x] `TradeView` and `TradeSnapshotStore` behind `TRADE_PROJECTION_MODE=snapshot`; `projection_cursor`
      in `ri06.sql` and both init-configmap keys.
- [x] Crash-replay and rate-ladder measurements on a cloud VM.

- [x] Bond fail-closed on the snapshot path; settlement and recon on the ledger; stream retention and
      the `STREAM_RESET` / `RETENTION_GAP` refusals; one global snapshot write lock (0e624848, 1bfe65f9).
- [x] NATS ingest queue sized on every tier (1bfe65f9).
- [x] Bridge receipt publish: per-partition lanes, 429 counting, `/health` and `/metrics`, ALERT ids
      (48655be8, ADR-083).
- [x] Core-mode recon holds an unprojected side for `RECON_PROJECTION_GRACE_MS` (FR-DRS-22).

## Still open

- No deployed tier sets the opt-in env yet.
- In snapshot mode no consumer replays a rejected trade side from the archive yet. The recon reports it
  MISSING, and the bridge names it (FR-DRS-16, FR-DRS-18).
