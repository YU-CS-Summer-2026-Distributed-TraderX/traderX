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
- [x] The production render pulls the durable feed with row projection, and refuses otherwise (FR-DRS-25).
- [x] The `tradeSeq` watermark is committed with each batch and counts a lossy restart (FR-DRS-02). On the durable
      feed the recon waits for the replay to catch up (FR-DRS-22).
- [x] The orderbook writer upserts each batch's final rows in multi-row statements, and reads the run row once
      per batch (FR-DRS-28): 929 to 58,315 updates/s across a 1 ms link.
- [x] Exactly-once accounting committed with each trade partition's cursor, with duplicates refused, and
      `/v2/projection-feed/accounting` (FR-DRS-29). The memory-only order view snapshots its open orders with
      a cursor and resumes from them (FR-DRS-30). `SnapshotReadModelIT`, both A/Bs (SC-DRS-07).
- [x] Cursor-acknowledged feeds pull pipelined, and the idle advance requires nothing ack pending (FR-DRS-31; the
      pending-only rule lost 1,408 of 3,000,000 sides at P=16). One stream per partition (FR-DRS-32), with the
      gateway's fill feed reading each (FR-FFR-13).
- [x] No feed acks past a delivery it did not apply: a jump in the consumer sequence, or a failed batch, recreates
      the consumer at the last applied message + 1, and a durable resumes from its ack floor (FR-DRS-33).
      `JetStreamFeedAckGapIT`: kill -9, a lost pull response to the durable and to the pipelined feed.
- [x] A store failure is a barrier, a refusal is skipped and counted (FR-DRS-35, codeX review 2026-10-30 §1): the
      trade store rethrows, the pipelined feed retries the batch before anything behind it, the row projections
      redeliver instead of counting, and a consumer deleted at a barrier is recreated. `FeedBarrierTest`,
      `JetStreamFeedAckGapIT`.
- [x] `yu18-jetstream-feed.sh` establishes its crash window: the restarted process must replay >= 1 side the killed
      one had not snapshotted, or the run is refused (SC-DRS-02; codeX review 2026-10-30 §5).
- [x] A saved cursor is bound to its stream's creation time; a recreated stream is refused at a restart, and a running
      feed never resubscribes across streams (FR-DRS-36, codeX review 2026-10-30 §6). `StreamIdentityIT`,
      `StreamRecreateIT` on NATS 2.10 and 2.14.

## Still open

- The production render sets `PROJECTION_FEED_MODE=jetstream`, but the next rollout has not deployed it yet.
- In snapshot mode no consumer replays a rejected trade side from the archive yet. The recon reports it
  MISSING, and the bridge names it (FR-DRS-16, FR-DRS-18).
