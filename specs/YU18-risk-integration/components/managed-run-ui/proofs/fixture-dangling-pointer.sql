-- PROOF FIXTURE ONLY (disposable DB): point projection_active at a scope with no registry row, so the
-- integrated GET /v2/projections/active answers 503. The console must show nothing, not infer.
UPDATE projection_active SET projection_scope='ghost_scope' WHERE singleton_id=1;
