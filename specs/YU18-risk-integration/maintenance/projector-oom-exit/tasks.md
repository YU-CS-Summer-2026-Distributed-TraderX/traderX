# Projector heap-OOM exit tasks

Owner: Codex RI24. Status: implemented and locally verified; coordinator review pending, 2026-10-07.

- [x] Verify isolated branch/base and claim exact paths; preserve canonical staging/backlog.
- [x] Enumerate operative ancestor files, Docker/Compose build selection and cluster entrypoint behavior.
- [x] Add YU18 image-launcher exit flags and full-runtime env option without changing sizing.
- [x] Add current YU18 GKE demo env patch without changing the image digest.
- [x] Execute SC-POE-01/02 with real bounded Java 21 processes and record before/after output.
- [x] Render actual offline effective configs and verify FR-POE-01/02/03 and NFR-POE-01.
- [x] Execute negative controls, component/generation checks, and inspect nonzero-count test reports.
- [x] Prepare scoped source and review evidence for coordinator handoff.
- [ ] Coordinator reviews/integrates the scoped commit and maintains shared indexes.

Broader RI24 memory bounds, supervisor restart/liveness and RI27 event durability remain unimplemented by this component. Shared indexes and live verification belong to subsequent authorized work.

Local evidence: 13 focused acceptance methods pass, zero failures/errors/skips; both disposable inverse controls fail with exit 1 (all options removed: 10 failures; demo env removed: 2 failures). Full YU18 generation, four repository gates and the 12-pack component check pass. Host Java 21 executes disposable fixture jars using rendered container launch options; this is no container or cluster execution claim.
