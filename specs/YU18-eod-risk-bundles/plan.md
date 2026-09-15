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
