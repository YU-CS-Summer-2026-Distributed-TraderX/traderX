# Managed-run UI specification

Status: implemented, review needed. Owner: Claude. Dependencies: RI-06 registry/readers/subjects (recovery-identity), unchanged.

- FR-MUI01 (revised R1): The blotter shows which run it is reading. On a managed backend the active scope is read only from `GET /v2/projections/active`, rows are read through the scoped readers for that scope, and the pointer is revalidated after the reads. A pointer failure or a pointer outside the registry list makes the view unavailable; no inference from rows or phases. (The first delivery inferred; superseded.)
- FR-MUI02: Every registry run can be browsed by name through the explicit `/v2/projections/{scope}` readers for positions, trades and orders. Such views are labelled read-only, with phase/epoch/checkpoint, and offer no cancel, replace, settlement or other business action on their rows (controls absent and handlers refuse).
- FR-MUI03: Notifications are scoped: managed subjects for managed scopes, legacy subjects for `legacy-unknown`. Subscriptions are released on every account or run switch and not held while scope is unknown or reads fail.
- FR-MUI04: No reply or event from a previous context (account/run) or an older overlapping read affects the view. A read containing rows from more than one run, or rows outside the requested run, is discarded.
- FR-MUI05: An active-run change is announced, the view is reset and re-read from the new run.
- FR-MUI06: A failed managed read (registry or any reader) shows no rows and names the failure; it never falls back to unversioned reads or another run. Empty results are distinguished from failed reads. Only a registry 404 selects unmanaged legacy mode.
- FR-MUI08 (R2): Every refused read goes through one fail-closed path that clears rows, row-detail/source-order state and the subscription, so nothing actionable from a refused context remains.
- FR-MUI09 (R3): Each poll's reads share a bounded deadline raced against the requests; exceeding it fails closed. Row actions exist only while the active view's state is confirmed. Context switch and destroy abort pending reads; late replies cannot change state or subscriptions.
- FR-MUI10: The Admin trade list follows FR-MUI01/06/08/09 for its trades (pointer, scoped reader, revalidation, row-scope check, deadline, fencing, withheld force-settle).
- FR-MUI07: The console never changes the active scope and exposes no operator control or credential for it; its proxies refuse projection-control, projection-recovery and gateway run-control routes in every path form they forward.

Acceptance (open items): SC-MUI07 real RI-06 transition (real Aeron runs, gateways, controller) observed through the console at every phase, 21 checks incl. Admin list.

Acceptance (corrections): SC-MUI05 pure 5 + mock 17 incl. R2/R3 regressions, all seven guards mutation-checked; SC-MUI06 live fixture on the integrated reader, 27 checks incl. dangling pointer, stopped and paused database.

Original acceptance: SC-MUI01 pure rules (run-scope.spec, 6); SC-MUI02 fencing with a stubbed Api, labelled mock (10); SC-MUI03 proxy refusal forms (operator-control.test); SC-MUI04 live disposable fixture: legacy, unknown mid-transition, change A→B→C, scoped notifications timed against real NATS, history isolation by run and account, legacy-unknown history subjects, named active run read-only, failed read without fallback (browser-proof, 20 checks).
