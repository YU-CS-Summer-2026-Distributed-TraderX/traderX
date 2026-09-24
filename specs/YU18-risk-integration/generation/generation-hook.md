# Generation Hook: EOD Risk Bundles

`pipeline/generate-state-YU18-risk-integration.sh` composes YU17 through the standard sequential generator, generates architecture documentation, and renders this state's additive component.

Owner: `specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles/`.
Generated component: `${TRADERX_GENERATED_ROOT:-generated}/code/target-generated/eod-risk-bundles/`.
The renderer copies Python source, tests and synthetic fixtures without bytecode/cache files. State artifacts live under target-generated/YU18-risk-integration/spec-source. No inherited runtime file is overlaid by this component.

The additive component also includes coordinator.py, its recovery tests and synthetic exchange fixtures.

The renderer now also overlays the YU18 order-matcher helper, RiskExtractMain and EodBundleExportTest. RiskExtractMain is a full-file override of YU17 with only the ready-payload/optional-receipt block changed; the CSV exporter algorithm is inherited unchanged.

Order-types component (RI-01, `components/order-types`): the renderer overlays every YU18 module directory present under `runtime-overrides/`, namely order-matcher, trade-processor, postgres-database-replacement and kubernetes-runtime, last-wins over YU17. The YU18 layer carries full-file overrides of the matching engine, book, order, input/output events, codec and SBE schema (template 9), the cluster service (snapshot format 11), the REST and FIX gateways, the order NATS bridge, the read model, the orderbook DDL with its 900-migrations block, and order-matcher build.gradle (the isolated `orderTypesAllocationGateTest`). Each was copied up unchanged from its highest carrying layer in commit 0ae2c53a before being modified, so `git diff 0ae2c53a` is the order-types delta. The active console (`web-front-end-console/`) is tracked at the repository root and is not rendered.
