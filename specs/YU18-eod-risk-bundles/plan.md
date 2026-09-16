# Implementation Plan: EOD Risk Bundles

## Goal

Provide a local EOD bundle builder, validator and mock consumer over YU17 exports, with reproducible generation and explicit synthetic-output semantics.

## Workstreams

1. Define manifest identity, supported export schemas and local publication rules.
2. Implement byte-preserving packaging and validation in a standalone Python component.
3. Implement the non-pricing mock and synthetic fixtures.
4. Register state lineage, generation hooks and architecture model.
5. Verify local and generated commands and record evidence.

## Key decisions

The CLI consumes exported files explicitly; operator-supplied epoch is part of identity. Hashes detect accidental changes and are not authentication. A local transport prototype exposes no pricing capability.

## Exit Criteria

Source and generated component tests pass. The demo completes without cloud access. The spec pack records generation and validation evidence separately from financial integration evidence.

Local coordination workstreams: immutable input snapshots, serialized SQLite job/attempt transitions, mock adapter validation, recovery after process termination, version-aware status and proposed exchange schemas. Implemented on traderX-risk-integration after the YU18 merge; the YU18 home branch has not yet received this extension.

Exporter integration: centralize the unchanged ready payload, add opt-in private receipt publication, validate receipts into bundles, and prove the path using the actual sequenced service and exporters with synthetic inputs.

GCS input workstream: bounded read-only downloads through gcloud, per-object generation provenance, receipt validation before local readiness publication, and separate archive-only inspection without inferred run context. No cluster deployment or worker API is required.

Local HTTP workstream: isolate a provisional mock wire contract behind an adapter, retain running attempts across uncertain responses, prove durable worker lookup and process-exit recovery, and keep real Alex capabilities/financial schemas unimplemented until agreement.

## Versioned reference inputs and shared exporter cases

Preserve the v1 contract with fixed cross-language hash vectors. Add an explicit v2 terms artifact for the initial Treasury/SWAP exchange, validate coverage and agreement with exported economics, and preserve incomplete SOFR conventions. Reproduce bill, fixed-rate note long/short, and SOFR cases through the in-process exporter. This extends the owning EOD bundle state; no deterministic-core behavior or cloud deployment changes.

I1 adds a standalone dated observation package, exact-byte custody and explicit selected/required
as-of rules. It leaves EOD bundle and W0 identities frozen. Future normalized or calibrated artifacts
need a separately agreed contract, parent hashes and distinct curve assumptions.

I2 reads an existing local coordinator snapshot through the maintained console server, with no
recovery/mutation on GET and explicit mock/W0/unavailable semantics. Deployment and remote
worker liveness/authentication remain separate work.
