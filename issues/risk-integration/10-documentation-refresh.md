# RI-10 — Docusaurus feature and testing documentation refresh

Updated: 2026-09-30. Status: ready for integration review. Owner: codex-docs-refresh. Local branch `codex/ri10-docs-refresh`, based on `4c2c4eb2`; not published.

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

- [x] Produce a coverage inventory with each feature's owning state/component, current behavior, public page and evidence reference.
- [x] Update feature and learning content across the lineage, with particular attention to additions since the site's last substantive update.
- [x] Rewrite affected prose in plain language and review it as a reader unfamiliar with recent project conversations.
- [x] Preserve published performance values and attach their measurement context.
- [x] Update test/script inventory and copyable commands, including order lifecycle, risk container/intake, identity, recovery and browser checks where implemented.
- [x] Validate front matter, links/navigation, applicable documentation gates and the Docusaurus build; inspect the rendered local site.
- [x] Record unverified commands and remaining documentation gaps explicitly. Publishing requires a separate instruction.

## Delivery and evidence

The public feature map covers fourteen upstream numbered states, YU01–YU18 and ten YU18 components. Homepage, both UI workflows, testing, historical measurements, recovery boundaries and generated learning pages are reconciled. `website/public-features.json` supplies the generated feature/evidence summaries; the site-source map documents active routes and publication exclusions.

The integration base had an older fifteen-state What's new page. The user-supplied deployed page has seventeen states and newer tape/reference-price/operator-attribution content. Those additions and the reported YU17 test totals were preserved, then extended for YU18. Local main `ff2b1eda` matches that inspected content; this is not an authenticated deployed SHA.

Validation: production build, front matter, root SpecKit gates, readiness, spec coverage, component validation and generated-doc drift checks pass. Public artifact audit covers 714 HTML pages with zero broken local targets or anchors and zero tested private markers across HTML, search and LLM exports. Three retained em dashes are literal quotations. Prose-node tests preserve code, quotes and original heading anchors. Representative desktop/mobile pages were inspected; wide tables scroll within the document.

Evidence is retained outside the public site under the coordinator's `review-evidence/ri10-docs-refresh-20260930/` directory, including route inventory, build/check logs, deployed captures and browser screenshots. `npm --prefix website run check:public` repeats the prose and artifact checks after building.

Remaining limits: runtime/test commands were source-checked, not executed for this documentation task. Historical performance hardware/date gaps and conflicting failover definitions are recorded in the measurements page. Specs retain source-owned planning/status text; this refresh does not claim every spec requirement is complete. Concurrent Desk work after the base revision needs coordinator reconciliation. AFDocs local checks report missing per-page Markdown/content negotiation; the hosted page check has the same Markdown limitations, and nested-page index discovery is incomplete. External links were not comprehensively verified. No trading rig, hosted CI, financial validation or deployment was performed.

Next action: coordinator reviews and integrates the docs commit with concurrent Desk changes, updates the coordinator-owned queue index and decides when to publish.

Related: [acceptance and demos](07-acceptance-and-demo.md), [build and deployment](08-build-and-deployment.md), [trader workspace](11-trader-workspace-ui.md).
