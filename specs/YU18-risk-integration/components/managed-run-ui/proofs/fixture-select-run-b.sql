-- PROOF FIXTURE ONLY (disposable DB). Simulates the END STATE of a run selection directly in SQL so
-- the console can be observed across a switch. It does NOT exercise the RI-06 transition workflow
-- (no freeze/verify witness); the console never performs this step and cannot reach the routes that do.
INSERT INTO projection_runs(projection_scope,cluster_epoch,event_id_scheme,descriptor_hash,storage_lineage,phase)
  VALUES ('run_b','run_b','epoch-v1',REPEAT('b',64),'fixture','ACTIVE');
UPDATE projection_runs SET phase='SEALED' WHERE projection_scope='run_a';
UPDATE projection_active SET projection_scope='run_b' WHERE singleton_id=1;
