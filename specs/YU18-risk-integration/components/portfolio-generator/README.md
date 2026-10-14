# Synthetic portfolio generator (RI-14)

Owner: local CPU integration task. Status: current CPU profile reviewed and integrated at c249adc5; broader load and connected-pipeline acceptance remain open.

Seeded file generator and bounded local submission/polling client for USD Treasury bills/notes. The current CPU profile targets numerical revision `c3d7c6a`; the original `2df78cb` profile remains explicit and unchanged. This is synthetic input tooling, separate from TraderX exports and the accepted EOD service. The direct portfolio API does not provide exporter provenance.

Source: [state-parent runtime tooling](../../generation/runtime-overrides/risk-portfolio-tools/README.md).
Contract: [portfolio-generator-v1](../../contracts/portfolio-generator-v1.md).
Current contract: [portfolio-current-cpu-v1](../../contracts/portfolio-current-cpu-v1.md).
Run guide: [local CPU loop](../../../../docs/risk-integration/local-cpu-loop.md).
Dependencies: RI-15 economics/schema acceptance; RI-16 additive submission identity service. No cloud scaling, production activation, mixed-currency market risk, new engine models or Risk UI changes.

Each manifest reports synthetic provenance and `financial_validation=false`. Current intake independently checks every selected regular flat-curve bill/note cashflow; the original four-position golden remains a separate control. Future scenario exposure has no independent model oracle here. See [tasks](tasks.md) for source/generated/live evidence boundaries.

Completed bounded task: [local CPU integration](../../../../issues/risk-integration/resolved/local-cpu-portfolio-integration-20261014.md). Separate owned engine profile `270a907a` is approved for this local scope; accepted C8/EOD engine `e770f912` remains unchanged.
