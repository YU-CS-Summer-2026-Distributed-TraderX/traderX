# Managed-run UI (console blotter)

Updated 2026-09-24 (review corrections R1–R3 applied). Owner: Claude (managed-run UI lane). Status: implemented on branch `claude/managed-run-ui`, review needed. Not deployed; no retained rig or installation was touched. Component pack: [managed-run-ui](../../specs/YU18-risk-integration/components/managed-run-ui/README.md).

## What the operator sees

The Trading page's **Blotter & positions** card has a **Run** selector above the account picker.

- **Active run** (default). On a managed backend the scope comes only from the server pointer, `GET /v2/projections/active`; rows are read through the explicit `/v2/projections/{scope}/…` readers for that scope, then the pointer is read again. The line under the selector reads `Active run: <scope> (selected pointer, GET /v2/projections/active)`. If the pointer cannot be read (503 for a missing/dangling pointer, timeout, other error) or names a scope absent from the registry list, the line reads `not confirmed` with the reason, and **nothing is inferred** from rows or phases.
- **A named run** (every registry row, `<scope> (<phase> · <epoch>) · read-only`). Banner `HISTORICAL · READ-ONLY` (or `NAMED RUN (registry phase ACTIVE) · READ-ONLY`) with phase, epoch and checkpoint. No cancel, replace or force-settle.
- **Actions** (cancel, force-settle) exist only on the active view and only while its state is confirmed (`ok`). While loading, unavailable or timed out they are absent and their handlers refuse.
- **Active-run change.** A different pointer between polls, or between a poll's first and second pointer read, discards that read, resets the context (aborting in-flight reads) and announces `Active run changed: A → B.`
- **Refusals (fail closed).** A failed registry, pointer or reader call, a read past the deadline (2.5 s per poll, raced so a request that ignores abort still cannot hold the view), or any row whose `projectionScope` is not the run read: one path clears rows, expanded rows, trade detail, the source-order cache and the subscription, and shows the reason. Tables then say `not shown — read failed`, never `no trades`.
- **Notifications.** Managed subjects for the scope read (`/v2/projections/{scope}/accounts/{a}/trades|positions`), legacy subjects for `legacy-unknown`. Released on every account/run switch, refusal and destroy.
- **Admin page trade list.** Uses the same rule through `readActiveRunTrades()`: pointer → scoped trades reader → pointer again → row-scope check, bounded by the same 2.5 s deadline and fenced against account switches. A line above the list names the run (`Active run: fresh-live (selected pointer)`); on any refusal the list is empty, says `read failed`, and force-settle is withheld.
- **Unmanaged (pre-RI06) backend.** A registry **404** alone selects it: unversioned reads and legacy subjects, labelled `unmanaged backend (no run registry)`.

The console never changes the active scope. Both console proxies answer **403 `operator_control_refused`** for projection-control, projection-recovery and gateway `/run/control`, including via `/legacy/…` and `/gw/<n>/…`.

## Contracts used (read from source at 30800162)

| Use | Route / subject | Source |
|---|---|---|
| Active pointer | `GET /position-service/v2/projections/active` → exactly `{"projectionScope": s}`; missing/dangling → 503 | integrated at 3f8db417 (`ScopedProjectionController.active()`) |
| Registry | `GET /position-service/v2/projections` → `[{projection_scope, cluster_epoch, event_id_scheme, descriptor_hash, phase, checkpoint_seq}]` | `ScopedProjectionController.scopes()` |
| Unmanaged reads | `/position-service/positions/{a}`, `/position-service/trades/{a}`, `/trade-processor/accounts/{a}/orders[?status=all]` (only when the registry is 404) | `PositionRepository`, `TradeRepository`, `OrderController` |
| Named-run reads | `/position-service/v2/projections/{s}/accounts/{a}/positions|trades`, `/trade-processor/v2/projections/{s}/accounts/{a}/orders[?status=all]` | same |
| Subjects | managed `/v2/projections/{s}/accounts/{a}/trades|positions`; `legacy-unknown` → `/accounts/{a}/trades|positions` | `TradeService.projectionTopic` |

A registry **404** (route absent: a pre-RI06 backend) is the only signal treated as unmanaged; then the card uses the legacy reads and subjects and says `unmanaged backend (no run registry)`.

### History of the contract

The first delivery (a5885bfb) predated the pointer reader and inferred the active scope from rows or phases. The coordinator approved and integrated the reader (3f8db417) and required its use without inference (R1); the scoped-orders 404 is also integrated. Inference code was removed.

## Verification

Corrections (R1–R3), merged over integration 3f8db417:

| Level | What | Result |
|---|---|---|
| Source, pure | `run-scope.spec.ts` — registry parsing, exact pointer contract, subjects, URLs | 5/5 |
| Source, **mock** Api (stub ignores abort) | pointer read + scoped reads + revalidation; 503 without inference; pointer moving mid-poll and between polls; pointer outside list; R2 populate-then-refuse (foreign rows, registry gone, pointer failure, active rows from another run); R3 hung read times out and late replies restore nothing, newer poll wins after a timeout, destroy disposes; account A→B→A; stale bus event; history read-only; unmanaged 404; out-of-order polls | 17/17 |
| Mutation | revalidation, fail-closed clearing, deadline race, context fence, sequence fence, action gating, row-scope check: each removed in turn | every one fails ≥1 test |
| Whole Angular suite / node | `ng test` / node suites | 119/119 / 15/15 |
| **Live, disposable fixture rig** | shipped `server.mjs` + production build in headless Chrome vs services generated from the merged tree (integrated pointer reader), MariaDB from its ConfigMap, real NATS | 27/27 checks, 11 screenshots (09 and 10 are byte-identical: the stopped database also hit the deadline first) |

Live steps added for the corrections: frozen window still confirmed by the pointer; dangling pointer → 503 → no rows, no inference, no actions, no subscription; database stopped → refusal (the deadline fired before the 500, recorded in `log.json`); database paused (a genuinely hung read) → `read timed out after 2500 ms`, rows/actions/subscription gone, recovery after unpause. Registry phases, pointer moves and rows are still **SQL fixtures**, not the RI-06 transition workflow.

The original a5885bfb evidence (20/20, inference-based) is kept unchanged in `review-evidence/managed-run-ui-20260924`; the corrections' evidence is in `review-evidence/managed-run-ui-r1-r3-20260924`.

## Real RI-06 transition proof (2026-09-24)

`proofs/real-transition-proof.mjs` mirrors the setup of `RunMigrationLiveIT`: two single-member Aeron runs (ClusterNodeMain) and their gateways (ClusterGatewayMain) from the generated matcher classpath, the real trade-processor controller, position- and account-service, NATS and MariaDB, all inside the managed-UI lease. Real orders cross on the old run's gateway, then the operator phases `adopt-legacy → prepare → freeze → verify → select → activate` are POSTed **directly to the controller** (never through the console, which refuses them), and a real order crosses on the fresh run. After each phase the shipped console in headless Chrome is checked and screenshotted.

Result: 21/21. The active view stays on `legacy-unknown`, with its real trade, through PREPARED, FROZEN (old scope DRAINING) and VERIFIED (SEALED). At SELECTED it announces `legacy-unknown → fresh-live`, drops the legacy rows and moves to the managed subjects. After COMPLETE it shows the real `e1-freshlive-1-B` trade and not the old one. The sealed legacy run can be browsed read-only on the legacy subjects. The Admin trade list shows the fresh trade via the pointer. The console refuses the operator routes three ways, and the transition stays COMPLETE.

Finding: on a database initialised from the generated ConfigMap, `verify` refuses with `RUN_TRADE_POPULATION_MISMATCH` because the initializer's demo trades and positions exist in no archive. That is the controller behaving correctly. The proof removes those rows first (`fixture-empty-legacy-history.sql`, matching the IT's empty schema). Any real adoption of a demo-seeded installation needs that attribution question resolved separately.

## Limitations

- Not deployed and not run on a retained managed installation: deployment is outside this lane's authorization (local only; cloud hold).
- The 2.5 s per-poll deadline is deliberate (review R3: a read that cannot complete in time fails closed). A backend that is merely slow shows as unavailable until a read completes in time.

Coordinator review correction R4 (2026-09-24): Admin trade and algo requests share an abort-raced 2.5-second deadline; a hung dependency cannot keep stale trades actionable. Account changes and destruction abort pending requests; late responses remain fenced. Three actual AdminPanel tests with abort-ignoring mock transport cover the timeout, late-response/newer-poll and lifecycle cases. Integrated-source Angular125/125, node15/15 and production build passed. Earlier disposable browser/transition proofs are supplied lane evidence, not rerun for this small correction.
