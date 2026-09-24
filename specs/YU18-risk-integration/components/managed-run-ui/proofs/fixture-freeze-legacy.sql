-- PROOF FIXTURE ONLY (disposable DB): the FROZEN/VERIFIED window as the registry shows it — old
-- scope DRAINING, fresh run PREPARED, active pointer unchanged. Not the RI-06 workflow itself.
INSERT INTO projection_runs(projection_scope,cluster_epoch,event_id_scheme,descriptor_hash,storage_lineage,phase)
  VALUES ('run_a','run_a','epoch-v1',REPEAT('a',64),'fixture','PREPARED');
UPDATE projection_runs SET phase='DRAINING', frozen_seq=0 WHERE projection_scope='legacy-unknown';
