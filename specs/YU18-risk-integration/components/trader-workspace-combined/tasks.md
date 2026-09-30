# Tasks

- [x] Combine selected login and branding.
- [x] Add overview hierarchy, global account/run context and inline order ticket.
- [x] Prevent historical order mutations and clear drafts on context changes.
- [x] Separate portfolio Risk from reference examples.
- [x] Group admin navigation and verify desktop/mobile rendering.
- [x] Build and run 20 local Angular tests; retain screenshots and logs.
- [ ] User review of combined candidate.
- [ ] Production authentication, API integration, complete feature parity and migration verification.
- [ ] Separately authorized deployment.

## Local connection

- [x] Match selected Desk suffix and place Demo console last in More.
- [x] Connect account/instrument/price/order/position/trade reads and existing risk/admin panels.
- [x] Connect order/algo gateway writes without automatic retries; fence context and run identity.
- [x] Verify WebSocket disconnect handling and local-only launch mode.
- [x] Run connected unit tests and existing isolated live startup proof.
- [x] Replace the old local rig with current YU18 and initialize approved demo-account admission.
- [ ] Complete secondary-tool and OTC ticket migration from Demo console.

## Workspace usability

- [x] Server-checked optional admin login and persisted username workspaces.
- [x] New SQL account creation, owned-account admission and partial-failure retry.
- [x] Fresh-price autofill and clickable sortable Markets columns.
- [x] Browser verification against local SQL and gateway.
- [x] Existing demo-account admission: eight user-approved accounts verified through submitted orders.
- [x] All seven types exercised on fresh YU18: 31 live cases passed; 32 Angular and 6 server tests passed.

## Managed order lifecycle

- [x] Inspect current source ownership and claim isolated worktree from 4c2c4eb2.
- [x] Add workspace/account/run preflight and receiving-gateway descriptor fence.
- [x] Preserve seven submission payloads, STP group assignment, and uncertain-outcome behavior.
- [x] Add source negative tests and actual connected Desk timeout/context tests.
- [x] Generate YU18 and test receiving-gateway identity refusals.
- [x] Complete disposable live partial-fill/replace/cancel, recovery and browser proof.
- [x] Shut down owned proof resources and package evidence for coordinator review.

Validation (2026-09-30): 13 Node source tests, 39 Angular tests, production build, 79 focused
generated engine tests (zero skipped), ten-component validator and four required SpecKit gates
pass. Final live proof: 29 named checks plus SQL/browser assertions, real core/gateway and consumer
restart, archive catch-up and managed transition. Final screenshots inspected. All proof containers
are stopped and retained; original four kind nodes remain stopped. Source/generated gateway/test
byte parity verified. No full-suite, HA, performance or production authentication claim.

Review R1 (2026-09-30): corrected the server's live-status set to include SUSPENDED; suspended
pegged orders remain cancellable. Reused shared LIVE_STATUSES in the Orders page and added the
missing fixture status. Before-fix regression fails on suspended cancellation (409 instead of 200);
after correction 16 Node tests and 40 Angular tests pass, plus production build. Terminal/unknown
statuses, account/run mismatches and unsupported replacement types still refuse. No engine changes
or live-rig rerun; prior live evidence describes 43ceaf9e, and this correction has source/browser-test
evidence only.
