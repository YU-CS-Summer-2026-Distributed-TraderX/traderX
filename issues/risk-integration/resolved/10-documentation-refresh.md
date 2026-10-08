# RI-10 — Docusaurus feature and testing documentation refresh

Updated: 2026-10-07. Status: done, delivered and published by the other Codex task, confirmed by Yaakov. Historical entries below retain the earlier workflow record.

## Outcome

Make the public documentation explain what TraderX does today, how its states build on each other, and how a reader can verify the behavior. Review the whole project before choosing what to highlight; recent recovery and risk conversations must not dominate the project story.

## Scope

Build a state-to-feature-to-evidence inventory across YU01–YU18, preserving the distinction between the upstream numbered states and the YU lineage. Use current source, specs and accepted evidence to establish what each feature actually supports. The inventory should cover:

- Sequencing, Kubernetes runtime, pre-trade risk, durable control feeds and post-trade compliance (YU01–YU05).
- EOD prices, historical ticks, execution algorithms, operational hardening and FIX ingress (YU06–YU10).
- Replication, consensus, recovery and the limit order book (YU11–YU13).
- Listed options, risk extracts, instrument representation and OTC rates (YU14–YU17).
- Risk bundles, market-input provenance, container integration, result intake and Risk UI (YU18), plus component additions such as order types and lifecycle handling, managed run identity and projection recovery.

These are audit topics, not assertions that every capability is complete or deployed. Include later changes to earlier states and explain dependencies and limitations. Reconcile the public overview, feature summaries, learning pages, state catalog, testing pages, diagrams and navigation.

The portal reads `docs/` and `specs/`; start with `website/docusaurus.config.js`, `website/sidebars.js`, `docs/home.mdx`, `docs/engineering/`, `docs/learning/` and `docs/learning-paths/`. Edit authoritative inputs for generated pages. Keep internal coordination notes and private data out of the public site.

## Writing and measurement rules

Use direct explanations of behavior, concrete examples and meaningful headings. Remove generic praise, repetitive summaries, inflated claims and AI-like filler. Explain specialist terms when a new reader needs them.

Preserve existing published latency and throughput numbers. Retain or restore their source, date, workload, hardware/runtime and measurement scope; do not present historical measurements as current deployment results. Record unsupported or contradictory claims for review instead of silently replacing numbers. New benchmarks are not required for this task.

Refresh testing documentation from actual tests and runnable entrypoints. Distinguish test cases, test files, scripts and scenarios; state the counting method, revision and exclusions for any totals. Document prerequisites, commands, expected evidence and what each check proves. Separate unit, generated-output, integration, browser, recovery, local-cluster and performance checks, including negative/refusal cases. Do not equate local proof with GKE, HA or financial validation.

## Acceptance

- [ ] Produce a coverage inventory with each feature's owning state/component, current behavior, public page and evidence reference.
- [ ] Update feature and learning content across the lineage, with particular attention to additions since the site's last substantive update.
- [ ] Rewrite affected prose in plain language and review it as a reader unfamiliar with recent project conversations.
- [ ] Preserve published performance values and attach their measurement context.
- [ ] Update test/script inventory and copyable commands, including order lifecycle, risk container/intake, identity, recovery and browser checks where implemented.
- [ ] Validate front matter, links/navigation, applicable documentation gates and the Docusaurus build; inspect the rendered local site.
- [ ] Record unverified commands and remaining documentation gaps explicitly. Publishing requires a separate instruction.

Next action: routine maintenance as features change. This refresh is complete; do not restart the original assignment.

Related: [acceptance and demos](../07-acceptance-and-demo.md), [build and deployment](../08-build-and-deployment.md), [trader workspace](../11-trader-workspace-ui.md).


2026-10-07 status reconciliation: Delivered for review September30 at5f8ec0d in traderX-ri10-docs-refresh; newer rendered routes and full-lineage coverage addressed by lane report. Coordinator review, reconciliation with5677b007 and integration pending. Not published.


2026-10-07 correction: Yaakov confirms the other task delivered and published the refresh. Supersedes the pending-review note above. Publication not independently rechecked here; exact deployed revision and integration ancestry are not inferred.
