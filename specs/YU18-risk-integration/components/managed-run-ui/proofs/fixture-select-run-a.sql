-- PROOF FIXTURE ONLY (disposable DB). Simulates the END STATE of a run selection directly in SQL so
-- the console can be observed across a switch. It does NOT exercise the RI-06 transition workflow
-- (no freeze/verify witness); the console never performs this step and cannot reach the routes that do.
INSERT INTO projection_runs(projection_scope,cluster_epoch,event_id_scheme,descriptor_hash,storage_lineage,phase)
  VALUES ('run_a','run_a','epoch-v1',REPEAT('a',64),'fixture','ACTIVE') ON DUPLICATE KEY UPDATE phase='ACTIVE';
UPDATE projection_runs SET phase='SEALED' WHERE projection_scope='legacy-unknown';
UPDATE projection_active SET projection_scope='run_a' WHERE singleton_id=1;
