# Synthetic portfolio generator (RI-14)

Owner: codex-portfolio-generator. Status: local implementation, coordinator review pending.

Seeded file generator and bounded local submission/polling client for the supported USD Treasury bill/note profile of Alex engine `2df78cb`. This is synthetic input tooling, separate from TraderX exports and the accepted EOD service. The direct portfolio API does not provide exporter provenance.

Source: [state-parent runtime tooling](../../generation/runtime-overrides/risk-portfolio-tools/README.md).
Contract: [portfolio-generator-v1](../../contracts/portfolio-generator-v1.md).
Dependencies: RI-15 economics/schema acceptance; RI-16 additive submission identity service. No cloud scaling, production activation, mixed-currency market risk, new engine models or Risk UI changes.

Each manifest reports synthetic provenance and `financial_validation=false`. The independent reference covers four regular flat-curve positions; scale completion is not financial validation. See [tasks](tasks.md) for source/generated/live evidence boundaries.
