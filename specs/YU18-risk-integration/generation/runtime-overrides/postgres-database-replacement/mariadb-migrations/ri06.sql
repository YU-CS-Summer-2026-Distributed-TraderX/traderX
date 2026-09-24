-- RI-06 additive retained-projection migration. Run only under an explicit maintenance barrier.
-- No ID rewrite or row deletion. Each statement is repeatable after interruption.
CREATE TABLE IF NOT EXISTS projection_runs (
 projection_scope VARCHAR(64) PRIMARY KEY,
 cluster_epoch VARCHAR(255), event_id_scheme VARCHAR(16) NOT NULL,
 descriptor_hash VARCHAR(64), descriptor_json LONGTEXT, storage_lineage VARCHAR(64),
 phase VARCHAR(16) NOT NULL, checkpoint_seq BIGINT NOT NULL DEFAULT 0,
 trade_rows BIGINT NOT NULL DEFAULT 0, order_updates BIGINT NOT NULL DEFAULT 0,
 frozen_seq BIGINT, UNIQUE KEY uq_run_epoch (cluster_epoch,event_id_scheme)
);
-- Orders use epoch-ref under BOTH schemes. SQL collation governs this global namespace.
-- Existing cross-scheme collisions fail here; no history is rewritten or silently adopted.
CREATE UNIQUE INDEX IF NOT EXISTS uq_run_order_namespace ON projection_runs(cluster_epoch);
INSERT IGNORE INTO projection_runs (projection_scope,event_id_scheme,phase)
 VALUES ('legacy-unknown','legacy-v0','LEGACY');
CREATE TABLE IF NOT EXISTS projection_active (
 singleton_id INTEGER PRIMARY KEY, projection_scope VARCHAR(64) NOT NULL
);
INSERT IGNORE INTO projection_active VALUES (1,'legacy-unknown');
CREATE TABLE IF NOT EXISTS projection_transitions (
 transition_id VARCHAR(64) PRIMARY KEY, old_scope VARCHAR(64) NOT NULL,
 new_scope VARCHAR(64) NOT NULL, descriptor_hash VARCHAR(64) NOT NULL,
 phase VARCHAR(16) NOT NULL, frozen_seq BIGINT, witness_json LONGTEXT,
 witness_hash VARCHAR(64), UNIQUE KEY uq_transition_new (new_scope)
);
CREATE TABLE IF NOT EXISTS projection_write_lock (lock_id INTEGER PRIMARY KEY);
INSERT IGNORE INTO projection_write_lock VALUES (1);
ALTER TABLE positions ADD COLUMN IF NOT EXISTS projectionscope VARCHAR(64) NOT NULL DEFAULT 'legacy-unknown';
CREATE INDEX IF NOT EXISTS idx_positions_account ON positions(accountid);
ALTER TABLE positions DROP PRIMARY KEY, ADD PRIMARY KEY (projectionscope,accountid,security);
ALTER TABLE trades ADD COLUMN IF NOT EXISTS projectionscope VARCHAR(64) NOT NULL DEFAULT 'legacy-unknown';
ALTER TABLE trades ADD COLUMN IF NOT EXISTS clusterepoch VARCHAR(255);
ALTER TABLE trades ADD COLUMN IF NOT EXISTS eventidscheme VARCHAR(16);
ALTER TABLE trades ADD COLUMN IF NOT EXISTS rundescriptorhash VARCHAR(64);
ALTER TABLE trades ADD COLUMN IF NOT EXISTS consensussequence BIGINT;
CREATE INDEX IF NOT EXISTS idx_trades_scope_account ON trades(projectionscope,accountid);
ALTER TABLE orderbook MODIFY COLUMN orderid VARCHAR(50) NOT NULL;
ALTER TABLE orderbook ADD COLUMN IF NOT EXISTS projectionscope VARCHAR(64) NOT NULL DEFAULT 'legacy-unknown';
ALTER TABLE orderbook ADD COLUMN IF NOT EXISTS clusterepoch VARCHAR(255);
ALTER TABLE orderbook ADD COLUMN IF NOT EXISTS eventidscheme VARCHAR(16);
ALTER TABLE orderbook ADD COLUMN IF NOT EXISTS rundescriptorhash VARCHAR(64);
ALTER TABLE orderbook ADD COLUMN IF NOT EXISTS consensussequence BIGINT;
ALTER TABLE orderbook ADD COLUMN IF NOT EXISTS outputordinal INTEGER;
ALTER TABLE orderbook ADD COLUMN IF NOT EXISTS eventdigest VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_orders_scope_account ON orderbook(projectionscope,accountid);
CREATE TABLE IF NOT EXISTS eod_position_pnl (
 session_date DATE NOT NULL, version INTEGER NOT NULL, account_id INTEGER NOT NULL,
 security VARCHAR(50) NOT NULL, quantity INTEGER NOT NULL, closing_price DECIMAL(18,6) NOT NULL,
 market_value DECIMAL(24,6) NOT NULL, marked_at DATETIME NOT NULL,
 PRIMARY KEY (session_date,version,account_id,security)
);
CREATE INDEX IF NOT EXISTS idx_eod_cut ON eod_position_pnl(session_date,version);
ALTER TABLE eod_position_pnl ADD COLUMN IF NOT EXISTS projectionscope VARCHAR(64) NOT NULL DEFAULT 'legacy-unknown';
ALTER TABLE eod_position_pnl DROP PRIMARY KEY, ADD PRIMARY KEY (projectionscope,session_date,version,account_id,security);
CREATE TABLE IF NOT EXISTS projection_recon (
 projection_scope VARCHAR(64) PRIMARY KEY, cursor_seq BIGINT NOT NULL DEFAULT 0,
 matched BIGINT NOT NULL DEFAULT 0, missing BIGINT NOT NULL DEFAULT 0,
 mismatched BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS projection_schema (singleton_id INTEGER PRIMARY KEY, version INTEGER NOT NULL);
INSERT INTO projection_schema VALUES (1,2) ON DUPLICATE KEY UPDATE version=GREATEST(version,2);
