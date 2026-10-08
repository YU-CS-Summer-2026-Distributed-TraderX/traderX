# Instrument reference mapping component

Date: 2026-10-07. Owner: codex-reference-mapping (RI09). Status: maintained research and synthetic examples; coordinator review pending.

The [maintained mapping](../../../../docs/risk-integration/instrument-reference-mapping/README.md) connects public TreasuryDirect/OCC explanations to current TraderX reference/export/terms and the accepted engine boundary. This component supplies documentation only. It adds no service, financial schema, generation input, feed or production convention.

Source paths: docs/risk-integration/instrument-reference-mapping/{README.md,field-mapping.md,examples.json,decisions.md}. Dependencies: inherited YU14 symbol parsing, YU16 reference catalog, YU17 position exporter and YU18 bundling/consumer profiles. Interfaces remain the state parent's [bundle/terms contract](../../contracts/bundle-v2-and-terms.md), [compatibility follow-up](../../contracts/compatibility-v3.md) and accepted consumer code. Engine implementation belongs to Alex.

[Specification](spec.md), [plan](plan.md), [tasks](tasks.md). Shared indexes remain coordinator-owned. Broad RI09 ingestion and financial agreement are not completed by this milestone.
