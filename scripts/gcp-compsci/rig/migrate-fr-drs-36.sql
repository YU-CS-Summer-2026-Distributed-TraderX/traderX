-- FR-DRS-36 migration for a PERSISTENT eod-price-db volume. database-init-sql (ri06.sql) carries the same two statements,
-- but MariaDB runs init scripts only on an empty data directory, so an existing volume never receives them.
-- Idempotent: safe on a volume with no projection_cursor, one without the column, and one already migrated; run twice.
-- Apply BEFORE the FR-DRS-36 trade-processor runs snapshot mode or order-view snapshots (rows mode never reads it):
--   kubectl -n traderx exec -i deploy/eod-price-db -c mariadb -- \
--     sh -c 'mariadb -uroot -p"$MARIADB_ROOT_PASSWORD" traderx' < scripts/gcp-compsci/rig/migrate-fr-drs-36.sql
-- Rollback: none needed. The pre-FR-DRS-36 trade-processor names its projection_cursor columns, so a NULLable extra
-- column is invisible to it. A cursor it advances keeps the old binding; if the stream was recreated meanwhile, the
-- FR-DRS-36 build then refuses it (STREAM_RESET), which is the rule working. Dropping the column is never required.
-- The last statement lists every cursor past 0 with no binding. The FR-DRS-36 build refuses each (STREAM_UNBOUND), and
-- its refusal prints, per row, the statement that attests it with the stream it found (codeX review §16). Runbook:
--   1. Start the build; it refuses. Collect the statements, all in one refusal per view:
--        kubectl -n traderx logs deploy/trade-processor | grep -o 'UPDATE projection_cursor SET stream_created_ns=[^;]*;'
--   2. With trading quiesced, reconcile the read model: rig G3 and fr-drs-33-order-fill-recon.sql, both clean (that file is now scripts/trade-history-tiers/order-fill-recon.clickhouse.sql).
--   3. Run the collected statements here, unedited. Each changes one row, and only while that row still holds the
--      printed cursor unbound; a replay changes nothing. Never write a binding by hand.
--   4. Restart. Rows bound to the stream that was reconciled resume. If the stream was recreated since step 1, the
--      start refuses (STREAM_RESET): rebuild that row (stream_seq=0, stream_created_ns=NULL), don't re-attest it blind.
CREATE TABLE IF NOT EXISTS projection_cursor (
 projection_scope VARCHAR(64) NOT NULL, partition_id INTEGER NOT NULL,
 stream_seq BIGINT NOT NULL DEFAULT 0, consensus_seq BIGINT NOT NULL DEFAULT 0,
 snapshot_at DATETIME, stream_created_ns BIGINT, PRIMARY KEY (projection_scope, partition_id)
);
ALTER TABLE projection_cursor ADD COLUMN IF NOT EXISTS stream_created_ns BIGINT;
SELECT CONCAT('projection_cursor.stream_created_ns: ', COUNT(*)) FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'projection_cursor' AND column_name = 'stream_created_ns';
SELECT CONCAT('unbound cursor, refused until reconciled and attested: ', projection_scope, ' partition ', partition_id,
 ' stream_seq ', stream_seq) FROM projection_cursor WHERE stream_seq > 0 AND stream_created_ns IS NULL;
