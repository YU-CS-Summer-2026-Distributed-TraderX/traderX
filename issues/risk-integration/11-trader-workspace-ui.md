# RI-11 — Trader desk and separate admin workspace

Updated: 2026-10-07. Status: in progress: combined Desk, Demo console, workspaces and managed order actions integrated locally; production auth/isolation and remaining feature migration open. Maintainer: coordinator; original delivery notes below retain their dates.

## Outcome

Create a clearer trader desk using the current console's visual style and interaction patterns. Preserve the existing demo console as an available experience while the new workspace is developed and evaluated. Keep most existing features, organize them around trading tasks and use plain, concise labels.

The user reports crowded tabs, unrelated content grouped together, stale or non-changeable data, confusing displays and shared UI state between GKE users. Audit these reports against the current implementation before identifying causes. Distinguish intentionally read-only information from missing controls, stale data and broken updates.

## Navigation and workflows

Show four or five primary tabs. A starting proposal for design review is **Overview, Markets, Orders, Positions and Risk**. Put secondary tools behind a three-bar menu; place executions, history, algorithms and reference tools where their workflows fit, rather than assigning them mechanically to a tab. Map every existing feature to its new location or document an explicit retirement decision.

Provide a separate admin workspace for administrative logins, with navigation for operations, service health, observability, configuration, recovery and other privileged tools as supported. A user with both roles should switch workspaces explicitly. Administrative controls must be protected by server-side authorization, not merely hidden in the trader UI.

Walk through the main trader flows: select an authorized account, inspect a market, submit and manage an order, inspect fills and positions, and review available risk results. Preserve working order types, lifecycle actions, price provenance, risk coverage/refusals and active-versus-historical run boundaries.

## User isolation and service design

Translate the requested independent, stateless UI instances into stateless frontend/service replicas and authenticated per-user workspaces. A dedicated pod per logged-in trader is not assumed. Identify where session, identity and preference state belongs in the design; do not store a user's active account, filters or layout in shared process globals.

- Give traders independent selections, layouts, watchlists and navigation state. If preferences persist, scope them to authenticated identity.
- Enforce account and role access on HTTP requests and streaming subscriptions. Shared market and ledger data remain shared where permissions allow; independent UI state does not duplicate trading engines or books.
- Define login/logout, session expiry, refresh/reconnect and multiple-tab behavior. Clear sensitive caches and subscriptions on logout or identity/account change.
- Specify how sessions work across stateless replicas and restarts. Audit existing authentication before choosing an identity provider or adding a new one.
- Test isolation with two simultaneous users and different account permissions, including direct API access and stream subscriptions.

## Data quality and presentation

Show useful units, timestamps, source/provenance and freshness. Make loading, empty, stale, unavailable and refused states distinct. Do not substitute demo values for unavailable live data. Make editable fields clearly editable and explain read-only fields when needed. Use readable tables, consistent number formatting, keyboard-accessible controls and responsive layouts. Avoid AI-like slogans and generic filler in labels, help text and errors.

The user permits useful trader-desk additions. Candidate features include personal watchlists, saved filters/layouts, clear working-order and fill summaries, and freshness or order-status alerts. Select additions during design based on concrete workflows and available data; do not invent P&L, analytics or risk results without a defined calculation and provenance.

## Acceptance

- [ ] Inventory existing tabs/features, stale-data reports, shared-state behavior and authentication/access controls, with source evidence.
- [ ] Create a component spec under YU18 and review navigation, trader/admin mockups, state ownership and feature migration map before implementation.
- [ ] Preserve the existing demo console through a documented separate entrypoint or equivalent reversible migration.
- [ ] Deliver four or five primary trader tabs, secondary menu and a separate role-protected admin workspace.
- [ ] Prove two traders cannot alter each other's private workspace state or read/act on unauthorized accounts; verify logout and reconnect isolation.
- [ ] Exercise order entry/lifecycle, positions, risk, historical read-only views and relevant admin workflows with meaningful browser/API tests.
- [ ] Verify data freshness and failure states, accessibility and readable copy in the rendered UI.
- [ ] Document configuration, local startup, migration and limitations. GKE rollout remains separately authorized.

Dependencies: existing order, market, account and risk APIs; authentication/access-control design; managed-run and recovery contracts. Useful UI design and local work can proceed without cloud credits or broader engine pricing support. Coordinate any backend ownership overlap before edits.

Next: Complete production authentication/account ownership, secondary/OTC migration and wider browser/failure acceptance; reuse the delivered Desk.

Related: [order types](01-order-types.md), [risk pipeline](03-risk-pipeline.md), [recovery](06-recovery.md), [acceptance and demos](07-acceptance-and-demo.md), [documentation refresh](resolved/10-documentation-refresh.md).

2026-09-25: User authorized Claude candidate design and runnable local prototype; assignment 20260925T042529Z-coordinator-claude-ui-design-assignment.txt. Separate worktree, current console preserved, production backend/authentication changes follow design review. Codex candidate explicitly deferred. Handoff: /Users/yaakov/dev/lmax/coordination/eod-integration/HANDOFF-CLAUDE-TRADER-DESK-UI.md.

2026-09-25: Claude candidate delivered at b82a6322 in traderX-ui-claude, review pending. User now authorized independent Codex candidate and coordinator comparison on completion. Codex task queued; candidate remains isolated and must notify coordinator directly when done. Earlier Codex deferral superseded.

2026-09-25: Both candidates delivered (Claude b82a6322; Codex b91761ef). Coordinator compared running UI workflows, screenshots and plans; independent35 Codex/16 Claude tests passed. Recommendation: Codex visual/context direction in Angular with Claude inline ticket reuse. User selection pending; neither integrated. Fix product-switch units/context; preserve existing console and complete feature/auth parity. Report: /Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ui-candidate-comparison/comparison.md.

2026-09-25: User selected combined candidate. Delivered at 1a002399 in traderX-ui-combined (codex/trader-desk-combined): Codex login and hierarchy/context, Claude branding and inline Angular ticket. Build and 20 tests passed; desktop/mobile inspection completed. Fixture-only; user design review and production auth/API/feature migration remain open. Originals preserved. Preview localhost:4320.


### Connected desk delivery — 2026-09-25

Accepted on traderX-risk-integration at e848f71e. The combined desk uses the Codex login and Claude-style light Desk wordmark; it reads the local rig and links the previous UI last in More as Demo console. Local previews: http://127.0.0.1:4320 and http://127.0.0.1:4321. Passed 29 UI tests, one real-socket server regression, the 10-component validator and an isolated live trade probe. Build passes with the existing size warning.

Remaining: production login/authorization, migration of secondary tools/OTC forms, and compatibility verification against the newer rig. This older rig returns no managed-run registry, so advanced order types and unsafe existing-order mutations remain disabled. Account 22214 is listed by the directory but rejected by engine admission as UNKNOWN_ACCOUNT; no account controls changed. Local rig stays running. No cloud, reset, image rollout or push.


2026-09-25 workspace update: b72d1398 adds username workspaces, server-checked optional admin password, new SQL accounts with engine admission, fresh-price autofill and sortable Markets columns.31 UI+8 server/control tests pass; live browser verified account65000 creation/admission. Existing-account repair awaits explicit approval after automatic review rejection; replacing old YU17 rig for typed orders awaits reset/recovery decision. No existing-account repair/reset/cloud/push performed.

2026-09-25 local rig update: user-approved fresh YU18 and eight-account admission completed; e20f1027 integrated. Seven types pass31live cases;32Angular+6Node and16readiness pass. UI4320 and Demo console4321 running. Existing-order mutations still require managed run identity. Evidence: coordination/eod-integration/review-evidence/desk-yu18-rig/.


2026-09-30: Desk managed order management accepted and integrated at 5677b007 (43ceaf9e plus R1 suspended-cancel correction). Account/run checked server route and receiving-gateway identity fence; stale reads/context guarded. Independent 16 Node + 40 Angular tests pass; 87 evidence hashes and source/generated parity verified. Reviewed 79 generated tests and 29 live checks covering partial fill, replace/cancel, retained restart, archive catch-up and managed transition; coordinator did not rerun live proof. Single-member only, not HA/full-platform readiness. Limit-only inline replacement; production authentication excluded. Retained unmanaged rig not activated. All rigs remain stopped per lane shutdown evidence. No push/cloud. Evidence: coordination/eod-integration/review-evidence/desk-managed-recovery-20260930 and desk-managed-recovery-r1-20260930 in parent workspace.

October 7 status reconciliation: the top status and next action supersede earlier queued/implementation-held headers. Historical unchecked planning lists are not a claim that implemented milestones are absent. Remaining acceptance is not marked complete.
