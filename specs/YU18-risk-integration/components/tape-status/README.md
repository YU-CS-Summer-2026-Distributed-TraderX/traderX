# Producer tape status

Date: 2026-10-07. Owner: codex-tape-status. Status: integrated locally, 2026-10-07; see the evidence and limits below.

This component documents the additive price-publisher availability contract and the replay-clock consumer. It does not acquire, normalize, ingest or store tape, expand the replay universe, or establish financial validity.

Source owner is `specs/YU16-cdm-instruments/generation/runtime-overrides/price-publisher/src/taq-replay.js`, with tests beside it. No later layer overrides the file. The accepted integration branch owns this correction; its YU16, YU17 and YU18 compositions inherit the additive fields. No historical home branch or peer checkout is updated. A duplicate YU18 full-file override would unnecessarily fork the clock and leave earlier compositions ambiguous.

Consumer: `web-front-end-console/src/app/tape-status.ts`, called by `replay-clock.ts`; acceptance: `web-front-end-console/tape-status.test.mjs` (Node 22.18+ with TypeScript stripping). Existing SYNTHETIC, TAPE, HELD and TAPE ERROR labels remain. Sandbox displays detail; collar-reference/replay-tape read source/position; proof and epoch scripts assert positions/errors but do not classify absence using prose. The demo runbook describes the new contract and older-producer fallback.

Run `node --test specs/YU16-cdm-instruments/generation/runtime-overrides/price-publisher/test/{taq-replay,print-replay}.test.js` and `node --test web-front-end-console/tape-status.test.mjs`. Generate YU18 with `TRADERX_GENERATED_ROOT=<private output> TRADERX_SKIP_LOCKFILE_REFRESH=1 bash pipeline/generate-state.sh YU18-risk-integration`, then run `npm test --prefix <private output>/code/target-generated/price-publisher` with installed publisher dependencies. The override directory is incomplete by design: full `npm test` requires inherited source/tests in the generated composition. See [spec](spec.md), [plan](plan.md), [tasks](tasks.md). Shared generation remains at the state parent and `pipeline/generate-state.sh`.

R1 rejects unrepresentable day/window timestamps at load with EXTRACT_INVALID_SCHEMA and nonfinite/unaddressable runtime clock arithmetic with CLOCK_UNADDRESSABLE. Inclusive Date boundaries and ordinary valid replay/pause/held outputs remain exercised. See FR-TS-04.

October 7 integration: combined source/generated checks passed in the coordinator candidate. The scoped implementation is integrated locally; original delivery notes retain their dates. Evidence is recorded in the shared coordination review directory `class-window-integration-20261007`. Live deployment, retained HA, performance and financial acceptance are not inferred from these checks.
