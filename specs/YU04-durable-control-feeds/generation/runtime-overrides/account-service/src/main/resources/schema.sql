-- Spring Boot auto-runs schema.sql only against a detected *embedded* datasource
-- (spring.sql.init.mode=embedded is the default) — this file has no effect against the real
-- MariaDB datasource in any deployed environment, where the schema instead lives in that
-- environment's own cluster-addons/*/database.yaml ConfigMap (see YU04's research.md for why).
-- It exists so account-service's test suite (H2 in-memory) has the tables AccountRepository and
-- the new outbox repositories expect.

CREATE SEQUENCE IF NOT EXISTS accounts_seq START WITH 65000 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS Accounts (
  ID INTEGER PRIMARY KEY,
  DisplayName VARCHAR(50)
);

CREATE TABLE IF NOT EXISTS account_control_outbox (
  version BIGINT AUTO_INCREMENT PRIMARY KEY,
  account_id INTEGER NOT NULL,
  display_name VARCHAR(50),
  published BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS account_source_epoch (
  epoch BIGINT NOT NULL
);
