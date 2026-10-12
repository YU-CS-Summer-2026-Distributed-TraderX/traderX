# October11 coordinator queue supplement

Current supplement to the preserved issue/task/live indexes; those existing user edits were left byte-for-byte unchanged.

- [Initial CompSci admission](resolved/compsci-gke-admission-20261011.md): done; approved fa074662 integrated.
- [Hosted demo repairs](resolved/compsci-demo-repairs-20261011.md): done; approved1132510f merged at85afc999, with independent generation, focused checks and edit preservation.
- [RI13 performance](13-gke-performance-rebaseline.md) / [LR07](live-rig/lr-07-gke-performance.md): initial C4D REST/gateway baseline measured; complete isolation, workload/recovery qualification remains open.
- [RI30 full-column historical tape](30-lossless-taq-reingestion.md): separately active in original author chat `01a12da7-58ed-7be0-98ff-f32fb3050e59` (Plan RI30 TAQ Parquet workload). Direct human registry/IAM/pilot authorization and full 132-archive run with at most 16 workers after pilot validation were verified October11. All columns and overlapping archives must remain preserved; its owner maintains the newly requested RI30 follow-up, while canonical integration is separate. Its checkout and delivery remain independent of RI31.
- [RI31 suite guards/fixture profiles](31-test-suite-guard-and-fixture-profiles.md): local candidate ready for review in [bounded RI31 task](tasks/ri31-suite-guards.md). All 18 available YU02–YU18 wrapper runs, composed/baseline units, container tiers, current/baseline legacy UI and source/script diagnostics pass in the declared local profile. Integration awaits approval; skipped/unavailable cases and the inconclusive historical HA cause remain explicit.

Claude and further Alex risk-engine upgrades remain paused; no new monitoring, skill authoring, Git push, broad propagation or retained-data cleanup.
