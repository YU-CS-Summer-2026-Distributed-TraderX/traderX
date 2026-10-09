# Risk Integration — state tasks

Updated: 2026-09-23. This index links component-owned task lists; their checkboxes are authoritative.

- [x] Establish components/ structure and state-level ownership rules.
- [x] Verify rename, component layout, source/generated parity and existing tests; see [migration evidence](../../issues/risk-integration/yu18-component-migration.md).
- [ ] Reconcile shared architecture/contracts when each component is designed.
- [ ] Review and integrate returned component work with measured evidence.

## Component tasks

- [EOD integration](components/eod-integration/tasks.md): preserved delivery history and remaining integration decisions.
- [Order types](components/order-types/tasks.md): planned; no implementation claim.
- [Risk service](components/risk-service/tasks.md): container pack drafted externally; implementation planned.
- [Risk pipeline](components/risk-pipeline/tasks.md): reuse existing coordinator and intake; service migration planned.

## October 7 integration

- [x] Combine the 13 locally reviewed operational and observability deliveries in an isolated candidate.
- [x] Complete combined script, generated Java/allocation and producer/console checks: 555 focused cases passed, plus the console production build.
- [x] Prepare the reviewed fast-forward delivery for the risk-integration branch; preserve unrelated staging and working edits.

The [component index](components/README.md#october-7-integration) owns links to their task lists. Live HA, retained compatibility, deployment and financial acceptance remain open.

## October 9 generation performance

- [x] Integrate reviewed and directly approved script-installation batching from `c0140ef9`, preserving full-ancestry/current-input admission and exact generated behavior.
- [x] Verify full generation/derivation, six scoped controls and final source/generated maintenance docs; reuse reviewed unchanged-byte timing/failure evidence.

See [generation-performance tasks](maintenance/generation-performance/tasks.md) and [resolved RI29 task](../../issues/risk-integration/resolved/ri29-generation-performance.md). The measured 28–32% elapsed-median improvements apply to warmed/offline/skip-lock-refresh generation and derivation; unavailable cache-dependent profiles and runtime/deployment performance remain unverified.
