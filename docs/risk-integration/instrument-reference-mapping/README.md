# Instrument reference mapping

Reviewed against TraderX `22d4c9067ce6a01f33678e7cb061c8d0e677e9c3` on October 7, 2026 (Eastern). Public documents were retrieved on October 8, 2026 (UTC). Owner: RI09 reference-mapping lane. Status: documentation and synthetic illustrations ready for review; financial conventions and ingestion remain undecided.

[Field mapping](field-mapping.md) connects TreasuryDirect and OCC explanations to the operative reference, export, terms and accepted engine boundaries. [Four examples](examples.json) include an existing synthetic bill, an existing synthetic fixed-coupon note, a new fictional standard option and its fictional adjusted counterpart. [Decisions](decisions.md) records gaps requiring human/Alex agreement.

The JSON is a documentation envelope, not a new wire schema or a submission file. Treasury examples reproduce fields from existing immutable fixtures and point to their complete bundles. Option examples illustrate fields and omissions; their export rows have no authentic cut or exporter receipt. No example establishes a real security, observed price, calibrated curve or financial correctness.

The accepted container profile admits only the original bill bundle. The separate historical provisional local profile admits the original bill and note; neither admits these option illustrations. Terms v1/v2 have no listed-option entry shape. A row passing a CSV parser does not establish that it can be priced, exercised or settled.

Validation runs only existing local parsers, terms validators and input allowlists, plus the actual pure symbol parsers. It never invokes a pricing function, engine service, market feed or Alex's checkout. Code and schema hashes, commands and outcomes are recorded in the coordinator's private RI09 review evidence; the maintained mapping identifies the pinned owners for future rechecks.

See the [component specification](../../../specs/YU18-risk-integration/components/instrument-reference-mapping/spec.md). Shared component/queue indexes and publication routes are maintained by the coordinator. This delivery changes no runtime, schema, generated output or source policy.
