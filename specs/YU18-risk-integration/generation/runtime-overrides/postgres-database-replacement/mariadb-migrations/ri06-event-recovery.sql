-- Additive, rerunnable. Apply after ri06.sql; never apply the destructive fresh initializer to retained data.
CREATE TABLE IF NOT EXISTS projection_recovery (
 projection_scope VARCHAR(64) NOT NULL PRIMARY KEY,
 descriptor_hash CHAR(64) NOT NULL,
 complete_seq BIGINT NOT NULL,
 witness_hash CHAR(64) NOT NULL,
 event_count INT NOT NULL,
 CONSTRAINT fk_recovery_scope FOREIGN KEY (projection_scope) REFERENCES projection_runs(projection_scope)
);
