-- The REAL deployed DDL for the tables account-service owns, copied verbatim from
-- kubernetes-runtime/manifests/base/database-init-configmap.yaml (001-initialSchema.sql).
-- Byte-identical across the YU04, YU05, YU06 and YU15 configmap layers as of 2026-07-31, so this
-- is the deployed shape on every branch that carries the outbox.
--
-- NOT src/main/resources/schema.sql: that file is H2-only (Spring Boot runs schema.sql against
-- embedded datasources only) and its widths and types are a convenience copy, not the contract.
-- The deployed services run against exactly the DDL below, so the integration test must too.
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

CREATE SEQUENCE accounts_seq START WITH 65000 INCREMENT BY 1;

CREATE TABLE account_control_outbox (
  version BIGINT AUTO_INCREMENT PRIMARY KEY,
  account_id INTEGER NOT NULL,
  display_name VARCHAR(50),
  published BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE account_source_epoch (
  epoch BIGINT NOT NULL
);
