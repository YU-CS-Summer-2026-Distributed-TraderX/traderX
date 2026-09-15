# Feature Pack: YU18-eod-risk-bundles

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: Implemented local transport prototype  
Track: `functional`  
Lineage role: `optional`  
Previous state: `YU17-otc-rates`

Primary intent:

- Package the two YU17 EOD exports as a consistent, versioned local bundle.
- Validate source schemas, provenance, identities and artifact integrity.
- Exercise consumption using explicit mock results with zero priced coverage.

Core artifacts:

- `spec.md`, `plan.md`, `research.md`, `data-model.md`, `quickstart.md`, `tasks.md`
- `requirements/functional-delta.md`, `requirements/nonfunctional-delta.md`
- `contracts/contract-delta.md`
- `system/architecture.model.json`, generated `system/architecture.md`
- `system/runtime-topology.md`, `system/messaging-subject-map.md`, `system/adr-066-local-eod-bundles.md`
- `generation/generation-hook.md`, `generation/implementation-status.md`
- `generation/runtime-overrides/eod-risk-bundles/`

Target runtime behavior:

- Python standard-library CLI runs locally against supplied exports.
- Bundle and result directories publish once; an existing destination is refused.
- `marketInputs.status=NOT_SUPPLIED` and mock `usableForRisk=false` are explicit.
- Caller-supplied epoch distinguishes identities across cluster resets.

The local coordinator adds SQLite job/attempt tracking, duplicate discovery suppression, process-restart recovery and strict mock-result ingestion. See quickstart section 5. Exchange schemas in contracts/proposed are negotiation drafts, not released runtime contracts.

The completed-export bridge consumes private readiness receipts and feeds the coordinator. Quickstart section 6 exercises real in-process engine bookings and production CSV renderers. The live EOD service chain is not part of that proof.

Optional GCS staging downloads generation-pinned objects into private local storage. A captured completion receipt can feed the existing bridge; an archive without a receipt is verified separately and cannot create ready work automatically. See quickstart section 9.

A live GKE EOD → GCS staging → local coordinator proof was completed on 2026-09-15: four position
rows and one SOFR contract, matching cut evidence from all three members, and one deduplicated mock
job. The user explicitly approved historical-tape mark overrides for this transport demonstration.
See generation/implementation-status.md for evidence and limits; no Alex pricing was performed.
