# Managed-run UI plan

Status: executed 2026-09-24.

1. Read the RI-06 contracts from source (registry columns, readers, `projectionTopic`, `RunRegistry`), not from docs. Registry lacks the active pointer → confirm from row `projectionScope`, infer narrowly, else unknown; request an additive read on the board.
2. Pure rules in `run-scope.ts` (registry parse, active scope, subjects, URLs, context counter) so isolation is unit-testable.
3. Blotter: run selector, banners, per-context stamp on every read and subscription, sequence fence for overlapping polls, read-only guards.
4. Proxies: one shared matcher (`operator-control.mjs`) in `server.mjs` before the admin gate and as a `bypass` on every `proxy.conf.mjs` prefix (the Vite dev proxy ignores function contexts — measured).
5. Prove on a disposable fixture rig with real services/NATS; label SQL fixtures and the stubbed-Api specs.

Out of scope: backend changes, a real transition, deployment, other panels.

## Corrections (2026-09-24)

R1 replaces step 1's inference with the integrated pointer reader plus revalidation. R2 folds every refusal into one clearing path. R3 adds a per-poll deadline raced against the requests, abort on switch/destroy, and state-gated actions.
