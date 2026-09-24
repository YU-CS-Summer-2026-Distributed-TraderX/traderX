-- PROOF FIXTURE ONLY (disposable DB): undo fixture-dangling-pointer.sql.
UPDATE projection_active SET projection_scope='legacy-unknown' WHERE singleton_id=1;
