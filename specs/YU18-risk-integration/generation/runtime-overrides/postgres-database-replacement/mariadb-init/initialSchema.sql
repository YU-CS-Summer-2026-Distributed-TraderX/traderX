-- MariaDB variant of the TraderX initial schema (state YU01 DB-engine swap experiment).
-- Derived from ../postgres-init/initialSchema.sql. Differences vs the Postgres copy:
--   * TIMESTAMP -> DATETIME: MariaDB TIMESTAMP columns carry implicit DEFAULT/ON UPDATE
--     CURRENT_TIMESTAMP semantics (and a 2038 range cap); DATETIME stores the value verbatim,
--     matching how created/updated/*at are written by the services.
-- Table/column names stay lowercase; the server runs with lower_case_table_names=1 so the
-- mixed-case identifiers used across the services (Accounts, Trades, OrderBook, TRADES, ...)
-- all resolve to these tables (Postgres folded them to lowercase; MariaDB on Linux would not).
-- CHECK constraints (MariaDB 10.2+) and SEQUENCE (MariaDB 10.3+) are supported as written.

DROP TABLE IF EXISTS trades;
DROP TABLE IF EXISTS orderbook;
DROP TABLE IF EXISTS accountusers;
DROP TABLE IF EXISTS positions;
DROP TABLE IF EXISTS accounts;
DROP SEQUENCE IF EXISTS accounts_seq;

CREATE TABLE accounts (
  id INTEGER PRIMARY KEY,
  displayname VARCHAR(50)
);

CREATE TABLE accountusers (
  accountid INTEGER NOT NULL,
  username VARCHAR(15) NOT NULL,
  PRIMARY KEY (accountid, username),
  FOREIGN KEY (accountid) REFERENCES accounts(id)
);

CREATE TABLE positions (
  accountid INTEGER,
  security VARCHAR(15),
  updated DATETIME,
  quantity INTEGER,
  averagecostbasis DECIMAL(18,3),
  PRIMARY KEY (accountid, security),
  FOREIGN KEY (accountid) REFERENCES accounts(id)
);

CREATE TABLE trades (
  id VARCHAR(50) PRIMARY KEY,
  accountid INTEGER REFERENCES accounts(id),
  created DATETIME,
  updated DATETIME,
  security VARCHAR(15),
  side VARCHAR(10) CHECK (side in ('Buy', 'Sell')),
  quantity INTEGER CHECK (quantity > 0),
  price DECIMAL(18,3),
  state VARCHAR(20) CHECK (state in ('New', 'Processing', 'Settled', 'Cancelled'))
);

CREATE TABLE orderbook (
  orderid VARCHAR(32) PRIMARY KEY,
  accountid INTEGER NOT NULL,
  security VARCHAR(16) NOT NULL,
  side VARCHAR(16) CHECK (side in ('Buy', 'Sell')),
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  remainingquantity INTEGER NOT NULL CHECK (remainingquantity >= 0),
  limitprice DECIMAL(18,3) NOT NULL,
  -- YU17 (ADR-069): QUEUED = held in the venue's pre-open queue. Accepted, holding its orderRef,
  -- in no book. A live state, not a terminal one: it becomes NEW/FILLED/REJECTED at the open, or
  -- CANCELED if the session closes on it. Widened here rather than left to the default because a
  -- CHECK constraint refuses the row outright -- the read model would simply lose every pre-open
  -- order, which is the state decision (g) exists to make visible.
  status VARCHAR(24) CHECK (status in ('NEW', 'PARTIALLY_FILLED', 'FILLED', 'CANCELED', 'REJECTED', 'QUEUED', 'PENDING_TRIGGER', 'SUSPENDED')),
  createdat DATETIME NOT NULL,
  updatedat DATETIME NOT NULL,
  lastexecutionprice DECIMAL(18,3),
  lastfillquantity INTEGER,
  -- YU18 order types (FR-OT33): type, TIF and typed parameters; PENDING_TRIGGER and
  -- SUSPENDED are live statuses. stopprice is a TRAILING_STOP's CURRENT level.
  ordertype VARCHAR(16),
  timeinforce VARCHAR(8),
  stopprice DECIMAL(18,6),
  displayquantity INTEGER,
  pegreference VARCHAR(16),
  pegoffset INTEGER,
  pegcap DECIMAL(18,6),
  trailamount DECIMAL(18,6),
  trailpercentbps INTEGER,
  triggered BOOLEAN,
  sessiondate INTEGER,
  suspendreason VARCHAR(16),
  reason VARCHAR(32)
);
CREATE INDEX idx_orderbook_status ON orderbook(status);
CREATE INDEX idx_orderbook_updatedat ON orderbook(updatedat);

CREATE SEQUENCE accounts_seq START WITH 65000 INCREMENT BY 1;

INSERT INTO accounts (id, displayname) VALUES (22214, 'Test Account 20');
INSERT INTO accounts (id, displayname) VALUES (11413, 'Private Clients Fund TTXX');
INSERT INTO accounts (id, displayname) VALUES (42422, 'Algo Execution Partners');
INSERT INTO accounts (id, displayname) VALUES (52355, 'Big Corporate Fund');
INSERT INTO accounts (id, displayname) VALUES (62654, 'Hedge Fund TXY1');
INSERT INTO accounts (id, displayname) VALUES (10031, 'Internal Trading Book');
INSERT INTO accounts (id, displayname) VALUES (44044, 'Trading Account 1');

INSERT INTO accountusers (accountid, username) VALUES (22214, 'user01');
INSERT INTO accountusers (accountid, username) VALUES (22214, 'user03');
INSERT INTO accountusers (accountid, username) VALUES (22214, 'user09');
INSERT INTO accountusers (accountid, username) VALUES (22214, 'user05');
INSERT INTO accountusers (accountid, username) VALUES (22214, 'user07');
INSERT INTO accountusers (accountid, username) VALUES (62654, 'user09');
INSERT INTO accountusers (accountid, username) VALUES (62654, 'user05');
INSERT INTO accountusers (accountid, username) VALUES (62654, 'user07');
INSERT INTO accountusers (accountid, username) VALUES (62654, 'user01');
INSERT INTO accountusers (accountid, username) VALUES (10031, 'user01');
INSERT INTO accountusers (accountid, username) VALUES (10031, 'user03');
INSERT INTO accountusers (accountid, username) VALUES (10031, 'user09');
INSERT INTO accountusers (accountid, username) VALUES (44044, 'user09');
INSERT INTO accountusers (accountid, username) VALUES (44044, 'user05');
INSERT INTO accountusers (accountid, username) VALUES (44044, 'user07');
INSERT INTO accountusers (accountid, username) VALUES (44044, 'user04');
INSERT INTO accountusers (accountid, username) VALUES (44044, 'user01');
INSERT INTO accountusers (accountid, username) VALUES (44044, 'user06');

INSERT INTO trades (id, created, updated, security, side, quantity, price, state, accountid) VALUES ('TRADE-22214-AABBCC', NOW(), NOW(), 'IBM', 'Sell', 100, 136.250, 'Settled', 22214);
INSERT INTO trades (id, created, updated, security, side, quantity, price, state, accountid) VALUES ('TRADE-22214-DDEEFF', NOW(), NOW(), 'MS', 'Buy', 1000, 95.125, 'Settled', 22214);
INSERT INTO trades (id, created, updated, security, side, quantity, price, state, accountid) VALUES ('TRADE-22214-GGHHII', NOW(), NOW(), 'C', 'Sell', 2000, 57.500, 'Settled', 22214);

INSERT INTO positions (accountid, security, updated, quantity, averagecostbasis) VALUES (22214, 'MS', NOW(), 1000, 95.125);
INSERT INTO positions (accountid, security, updated, quantity, averagecostbasis) VALUES (22214, 'IBM', NOW(), -100, 136.250);
INSERT INTO positions (accountid, security, updated, quantity, averagecostbasis) VALUES (22214, 'C', NOW(), -2000, 57.500);

INSERT INTO trades (id, created, updated, security, side, quantity, price, state, accountid) VALUES ('TRADE-52355-AABBCC', NOW(), NOW(), 'BAC', 'Sell', 2400, 41.125, 'Settled', 52355);
INSERT INTO positions (accountid, security, updated, quantity, averagecostbasis) VALUES (52355, 'BAC', NOW(), -2400, 41.125);

-- RI-06: serialize deduplication and position read-modify-write across consumer processes.
CREATE TABLE IF NOT EXISTS projection_write_lock (lock_id INTEGER PRIMARY KEY);
INSERT IGNORE INTO projection_write_lock (lock_id) VALUES (1);

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
