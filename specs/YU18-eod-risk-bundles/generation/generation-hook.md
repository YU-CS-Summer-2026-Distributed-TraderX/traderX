# Generation Hook: EOD Risk Bundles

`pipeline/generate-state-YU18-eod-risk-bundles.sh` composes YU17 through the standard sequential generator, generates architecture documentation, and renders this state's additive component.

Owner: `specs/YU18-eod-risk-bundles/generation/runtime-overrides/eod-risk-bundles/`.
Generated component: `${TRADERX_GENERATED_ROOT:-generated}/code/target-generated/eod-risk-bundles/`.
The renderer copies Python source, tests and synthetic fixtures without bytecode/cache files. State artifacts live under target-generated/YU18-eod-risk-bundles/spec-source. No inherited runtime file is overlaid by this component.
