-- Additive, rerunnable. Apply after ri06.sql and ri06-event-recovery.sql. No deletes, no rewrites.
-- Automatic projection catch-up: per-scope completeness cursor, single-worker fenced lease, page audit.
CREATE TABLE IF NOT EXISTS projection_catchup_cursor (
 projection_scope VARCHAR(64) NOT NULL PRIMARY KEY,
 descriptor_hash CHAR(64) NOT NULL,
 protocol_version INT NOT NULL,
 verified_through_seq BIGINT NOT NULL,
 verified_digest CHAR(64) NOT NULL,
 verified_event_count BIGINT NOT NULL,
 source_boundary_seq BIGINT NOT NULL,
 state VARCHAR(16) NOT NULL,
 blocked_reason VARCHAR(512) NULL,
 last_verified_at DATETIME(6) NULL,
 last_attempt_at DATETIME(6) NULL,
 updated_by_fence BIGINT NOT NULL,
 CONSTRAINT fk_catchup_scope FOREIGN KEY (projection_scope) REFERENCES projection_runs(projection_scope)
);
CREATE TABLE IF NOT EXISTS projection_catchup_lease (
 singleton_id INTEGER NOT NULL PRIMARY KEY,
 owner_id VARCHAR(128) NULL,
 fence BIGINT NOT NULL,
 expires_at DATETIME(6) NULL
);
INSERT IGNORE INTO projection_catchup_lease(singleton_id,owner_id,fence,expires_at) VALUES (1,NULL,0,NULL);
CREATE TABLE IF NOT EXISTS projection_catchup_log (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 projection_scope VARCHAR(64) NOT NULL,
 after_seq BIGINT NOT NULL,
 through_seq BIGINT NOT NULL,
 event_count INT NOT NULL,
 inserted_trades INT NOT NULL,
 owner_id VARCHAR(128) NOT NULL,
 fence BIGINT NOT NULL,
 committed_at DATETIME(6) NOT NULL
);
