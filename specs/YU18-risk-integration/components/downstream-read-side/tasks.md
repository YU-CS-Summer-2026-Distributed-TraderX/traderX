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
- [x] The FR-DRS-33 order-fill recon is in the repo; its orphan query excludes `/trades` ticket bookings, which since
      ADR-100 carry no `sourceorderid` (`bookingSource: TICKET`); the `<epoch>-0` suffix line covers only older rows.
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
- [x] A promoted member publishes the trades and orders its predecessor applied but the stream does not hold, after
      the subject's last stored message, exactly once and in order (FR-DRS-37; production e1108 sides 16169..16172).
      `ThreeMemberClusterTest#tradesInFlightAtALeaderKillReachTheStreamExactlyOnce`, `ReceiptLanesIT`.
- [x] A NATS restart under a steady leader: each lane replaces its connection and runs the same catch-up, so the
      sides that were in flight at the kill are stored once, in order. An outage past the ring is a counted, ALERTed
      gap (FR-DRS-38; kind 2026-11-08, 952 sides). `ThreeMemberClusterTest#tradesInFlightAtANatsRestartReachTheStreamExactlyOnce`,
      `ReceiptLanesIT#aNatsRestartRepublishesWhatTheStreamLost`, `ReceiptLanesIT#aNatsOutageLongerThanTheRingIsALoudGap`.
- [x] A record refused by a full lane queue is a counted gap with an ALERT, at the catch-up or at once while leading
      (FR-DRS-38). `/health` `bridges.*.queuedByPartition` shows each lane's backlog.
      `ReceiptLanesIT#aRecordDroppedAtTheQueueIsACountedGapAtTheCatchUp`.
- [x] Each lane warms its encode-and-publish path on a scratch subject before its first record, so a cold start or a
      promotion under load does not overflow the queue (FR-DRS-39; 0 to 400K locally: startup backlog 56K -> under 4K).
      `ReceiptLanesIT#aLaneWarmsItsPublishPathOnAScratchSubjectBeforeItPublishes`.
- [x] Order events carry their position under the legacy id scheme too, so the order lane's catch-up works where
      production and kind run; a last message without one is a counted gap; the SQL order projection accepts it
      (FR-DRS-40). `ThreeMemberClusterTest#tradesInFlightAtANatsRestartReachTheStreamExactlyOnce` (order events),
      `ReceiptLanesIT` under both schemes, `OrderBatchProjectionIT#aLegacyUpdateWithAPositionIsWrittenOnBothPathsAndCountsForNoRun`.
      On kind: `scripts/proofs/kind-bridge-nats-restart.sh` (291,180 of 291,180 order events; the control lost 45).
- [x] trade-processor as N replicas, each owning a disjoint block of the partitions; a partition with a live owner is a
      start-up refusal (PARTITION_OWNED); health and accounting report the owned partitions and name them; the recon
      runs on partition 0's owner; a StatefulSet render for N > 1 (FR-DRS-45, RI36). `PartitionOwnershipTest`,
      `PartitionOwnershipIT`, `test_prepare.SnapshotReadModel`, `scripts/proofs/kind-tp-ownership.sh`.
- [x] The order view's reads keep their contract with N replicas: a replica forwards a read of an account it does
      not own to the owner, a routed read is never routed twice, no owner is 503; measured against fan-out-and-merge
      (FR-DRS-46, ADR-103 §3). The production snapshot profile renders 4 replicas; `rig.sh` drives either shape.
      `PartitionOwnershipIT`, `test_rig`, `kind-tp-ownership.sh` steps 5-6.

## Still open
- The GKE hour at 450K on 4 read-model replicas (RI36); the readmodel-pool must hold 4 × 12 CPU.

- The production render sets `PROJECTION_FEED_MODE=jetstream`, but the next rollout has not deployed it yet.
- In snapshot mode no consumer replays a rejected trade side from the archive yet. The recon reports it
  MISSING, and the bridge names it (FR-DRS-16, FR-DRS-18).
- [x] FR-DRS-36 migration for a persistent eod-price-db volume (`migrate-fr-drs-36.sql`), idempotent, with its rollback note.
- [x] FR-DRS-36 first binding: an unbound cursor past 0 is refused (STREAM_UNBOUND) until reconciled and attested
  (`stream_created_ns=0`); the migration lists those rows (codeX re-review, 2026-10-30).
- [x] FR-DRS-36 attestation names the reconciled stream: the refusal prints each row's conditional statement with that
  stream's creation time; a stream recreated after it is refused and a replay changes nothing (codeX §16, AttestationIT).
