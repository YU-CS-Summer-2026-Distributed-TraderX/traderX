-- The REAL deployed schema for the tables trade-processor writes, copied verbatim from
-- database-init-configmap.yaml (001-initialSchema.sql) — NOT the H2-only per-service schema.sql.
-- The deployed services run ddl-auto=none against exactly this DDL, so the integration test must
-- too. Tight VARCHAR widths, CHECK constraints, and the accounts FK are the point: they are where
-- this project has shipped silent row drops.
CREATE TABLE accounts (
  id INTEGER PRIMARY KEY,
  displayname VARCHAR(50)
);

CREATE TABLE positions (
  accountid INTEGER,
  security VARCHAR(32),
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
  security VARCHAR(32),
  side VARCHAR(10) CHECK (side in ('Buy', 'Sell')),
  quantity INTEGER CHECK (quantity > 0),
  price DECIMAL(18,3),
  state VARCHAR(20) CHECK (state in ('New', 'Processing', 'Settled', 'Cancelled')),
  settlementdate DATETIME
);
