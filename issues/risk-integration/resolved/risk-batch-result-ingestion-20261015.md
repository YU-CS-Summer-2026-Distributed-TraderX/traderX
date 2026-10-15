# Local Batch result ingestion and Risk UI

- Parent: [RI-03](../03-risk-pipeline.md); component: [risk-pipeline](../../../specs/YU18-risk-integration/components/risk-pipeline/README.md).
- Owner: chat `01a138ba-f509-7f50-8884-3d0b8fd3a5b4`; coordinator `01a12b5c-4c93-7e62-b90a-3e56223dee78`.
- Status: done, October 15, 2026; isolated `codex/risk-cpu-batch`.
- Base: `2bdc5e60`, with cloud handoff dependencies now integrated after canonical `f01619cc`.
- Authorized scope: local ingestion from verified Batch artifacts and status/results in existing TraderX Risk UI.
- Paths: risk-portfolio-tools importer/locator/tests, import/proof CLI, console batch reader/server/proxy/Dockerfile/Risk component/tests, existing local UI host and risk-pipeline documentation.
- Excluded: cloud operations, GKE, Alex repository changes, pushes, canonical integration, live-current-position comparison, general financial validation, scheduling and GPU/TPU.
- Admission: immutable copied local evidence, independent operator pin, full offline verification on reads, explicit run selection, idempotent exact replay and conflicting replay refusal.
- Live disposition: actual downloaded cloud artifacts admitted locally, actual built UI and HTTP reader exercised; no cluster rig required for this bounded read-only import. Existing deployment/LR obligations remain open.
- Evidence: `/private/tmp/risk-ingestion-proof-final`, `/private/tmp/risk-ingestion-*-tests.log`, generated root `/private/tmp/risk-ingestion-generated-20261015`.
- Cloud source evidence: `/private/tmp/risk-mapped-cloud.IaGQhS/mapped-run/batch-download`; retained cleanup query is project-wide instances/disks filtered solely by the actual Batch UID. Both saved lists empty; not a claim that the project has no other compute.
- Closeout: human approved the full five-commit range through `860ad0d0`; integrated into canonical October 15. Local ingestion/UI and bounded mapped cloud proof complete; deployment and broader risk qualification remain separate.

See [contract and reproduction](../../../docs/risk-integration/batch-result-ingestion.md).

Validation: 62 source and 62 generated Python tests; 13 Angular tests (including non-3% market display); 3 Node reader tests; 13 real-artifact admission/replay/negative-control checks. Actual built development UI and HTTP route verified, including empty/refused states. Five repeated offline reads preserved the retained inventory. One local HTTP read took 0.26 seconds; this is not a load benchmark. Coordinator independently reproduced the real-artifact checks and requested the now-fixed curve-label correction.

## Approved integration

Human explicitly approved `860ad0d0` and dependencies `cd68259a`, `c314f3e2`, `28e2c860`, `2bdc5e60` into canonical from `f01619cc`. Coordinator independently verified 13 real-artifact checks, three Node reader tests, 32 source/generated identities and actual saved cloud custody; reviewed 62 source/62 generated, 13 Angular tests and built UI. Cloud result: one synthetic position, NPV USD 98,507.14563826029, actual Batch UID `risk-cpu-mapped-20-38c0e0f7-a385-4c270`. Saved project-wide UID-filtered lists contain zero instances/disks; not a new live observation. General financial/scenario-risk validation, current-position comparison and deployed UI remain open. No push, deployment or cloud execution during integration.

Canonical checks: full YU18 generation passed; all 32 risk-portfolio source/generated files match; 62 source and 62 generated tests, three Node reader tests and four repository/specification gates passed. All pre-existing work preserved: 23 original paths byte-identical and the task-index user delta unchanged; initial staging empty.
