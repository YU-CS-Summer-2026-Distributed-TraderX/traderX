# OTC refusal audit implementation plan

Owner: Codex RI22 OTC chat `01a11835-d552-73d1-a720-e7a15fa66cb3`.
Status: implemented and verified locally; coordinator review/integration pending.

1. Verify accepted `a0d6da0b` and current report/egress path; run the inherited missing-row baseline.
2. Own YU18 last-wins `ClusterRecon` and `MatchingEngineClusteredService`; leave ancestor owners,
   NodeMain, Gateway and projection protocols unchanged.
3. Add immutable refusal observation constructed only behind a non-null shadow observer after
   the existing ack. Observe the decided reason and decoded input identity; do not retain it.
4. Wire the regulatory replay alone. Render refusal rows with null IDs/economics and optional
   correlation, using existing range/budget logic and strict archive coverage checks. Keep booked
   contract growth and legacy order output rendering intact.
5. Verify actual source-composed and generated service/report behavior with owned disposable archive
   fixtures. Compare an independently compiled accepted-service null-tap witness; retain runtime
   negative controls, XML counts and exact source/generated parity.
6. Commit explicit owned paths as yaakov, post READY and release claims. Coordinator reconciles
   the imported untracked RI22 task prose and updates shared indexes/integrates after review.

No new state, broad propagation, canonical edits, live rig, cloud deployment, reset or push.
