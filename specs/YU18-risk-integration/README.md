# YU18 — Risk Integration

This state inherits YU17-otc-rates. It combines EOD bundles and result intake, seven order types, a local risk-container pipeline, managed projection recovery and trader workspace components. Local implementation and tests do not establish complete portfolio pricing, retained HA or deployment-profile performance.

See [state spec](spec.md), [plan](plan.md), [tasks](tasks.md), [quickstart](quickstart.md) and [components](components/README.md). Runtime directory `eod-risk-bundles` and existing wire/schema identities remain unchanged.

## Existing EOD foundations

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

The optional HTTP mock adapter now exercises submission, workload lookup, polling, uncertain-response recovery and immutable result ingestion against a loopback fake worker. It uses a distinct profile and remains non-pricing. Run `python3 scripts/demo-state-YU18-http.py`; see quickstart section 10 and contracts/http-mock-draft-1.md.

Bundle v2 adds hash-pinned instrument terms while preserving v1 bytes and identities. See [terms and shared examples](contracts/bundle-v2-and-terms.md) and [frozen v1 hash vectors](contracts/golden-v1.md). The [quickstart](quickstart.md#11-frozen-v1-hashes-and-exporter-produced-bundle-v2-examples) describes the POSIX local demo for fresh exporter fixtures and both bundle versions.

## Operational checks and readouts

The [maintenance index](maintenance/README.md) includes proof cleanup, image provenance/admission, scoped reconciliation proofs and replay-anchor validation. Member surfaces expose observed peer coverage, risk capacity and admin account state; the regulatory report includes sequenced OTC refusals through shadow replay. Tape status and gateway ACK metrics distinguish expected behavior from unknown or failed observations. Projector heap-OOM exit and a bounded book-memory tool provide separate process-failure and memory evidence.

All October 7 changes are local integrations. Retained upgrades, durable EOD event recovery, external-engine portfolio acceptance and cloud performance work remain separate.

Feature specs live in [components](components/README.md); issue fixes, reliability hardening and diagnostics live in [maintenance](maintenance/README.md). Shared generation/runtime inputs remain at the state parent.
