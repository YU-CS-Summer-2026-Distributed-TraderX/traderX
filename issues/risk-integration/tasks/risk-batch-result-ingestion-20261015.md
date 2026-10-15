# Local Batch result ingestion and Risk UI

- Parent: [RI-03](../03-risk-pipeline.md); component: [risk-pipeline](../../../specs/YU18-risk-integration/components/risk-pipeline/README.md).
- Owner: chat `01a138ba-f509-7f50-8884-3d0b8fd3a5b4`; coordinator `01a12b5c-4c93-7e62-b90a-3e56223dee78`.
- Status: review needed, October 15, 2026; isolated `codex/risk-cpu-batch`.
- Base: `2bdc5e60`, depending on unintegrated cloud handoff commits after canonical `f01619cc`.
- Authorized scope: local ingestion from verified Batch artifacts and status/results in existing TraderX Risk UI.
- Paths: risk-portfolio-tools importer/locator/tests, import/proof CLI, console batch reader/server/proxy/Dockerfile/Risk component/tests, existing local UI host and risk-pipeline documentation.
- Excluded: cloud operations, GKE, Alex repository changes, pushes, canonical integration, live-current-position comparison, general financial validation, scheduling and GPU/TPU.
- Admission: immutable copied local evidence, independent operator pin, full offline verification on reads, explicit run selection, idempotent exact replay and conflicting replay refusal.
- Live disposition: actual downloaded cloud artifacts admitted locally, actual built UI and HTTP reader exercised; no cluster rig required for this bounded read-only import. Existing deployment/LR obligations remain open.
- Evidence: `/private/tmp/risk-ingestion-proof-final`, `/private/tmp/risk-ingestion-*-tests.log`, generated root `/private/tmp/risk-ingestion-generated-20261015`.
- Cloud source evidence: `/private/tmp/risk-mapped-cloud.IaGQhS/mapped-run/batch-download`; retained cleanup query is project-wide instances/disks filtered solely by the actual Batch UID. Both saved lists empty; not a claim that the project has no other compute.
- Next: complete coordinator review and report candidate; separate human approval required for canonical integration.

See [contract and reproduction](../../../docs/risk-integration/batch-result-ingestion.md).

Validation: 62 source and 62 generated Python tests; 13 Angular tests (including non-3% market display); 3 Node reader tests; 13 real-artifact admission/replay/negative-control checks. Actual built development UI and HTTP route verified, including empty/refused states. Five repeated offline reads preserved the retained inventory. One local HTTP read took 0.26 seconds; this is not a load benchmark. Coordinator independently reproduced the real-artifact checks and requested the now-fixed curve-label correction.
