-- FR-DRS-36 migration for a PERSISTENT eod-price-db volume. database-init-sql (ri06.sql) carries the same two statements,
-- but MariaDB runs init scripts only on an empty data directory, so an existing volume never receives them.
-- Idempotent: safe on a volume with no projection_cursor, one without the column, and one already migrated; run twice.
-- Apply BEFORE the FR-DRS-36 trade-processor runs snapshot mode or order-view snapshots (rows mode never reads it):
--   kubectl -n traderx exec -i deploy/eod-price-db -c mariadb -- \
--     sh -c 'mariadb -uroot -p"$MARIADB_ROOT_PASSWORD" traderx' < scripts/gcp-compsci/rig/migrate-fr-drs-36.sql
-- Rollback: none needed. The pre-FR-DRS-36 trade-processor names its projection_cursor columns, so a NULLable extra
-- column is invisible to it. A cursor it advances keeps the old binding; if the stream was recreated meanwhile, the
-- FR-DRS-36 build then refuses it (STREAM_RESET), which is the rule working. Dropping the column is never required.
CREATE TABLE IF NOT EXISTS projection_cursor (
 projection_scope VARCHAR(64) NOT NULL, partition_id INTEGER NOT NULL,
 stream_seq BIGINT NOT NULL DEFAULT 0, consensus_seq BIGINT NOT NULL DEFAULT 0,
 snapshot_at DATETIME, stream_created_ns BIGINT, PRIMARY KEY (projection_scope, partition_id)
);
ALTER TABLE projection_cursor ADD COLUMN IF NOT EXISTS stream_created_ns BIGINT;
SELECT CONCAT('projection_cursor.stream_created_ns: ', COUNT(*)) FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'projection_cursor' AND column_name = 'stream_created_ns';
