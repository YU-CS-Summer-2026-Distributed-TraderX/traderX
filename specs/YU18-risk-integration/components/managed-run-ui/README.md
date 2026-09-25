# Managed-run UI

Status: implemented, review corrections R1–R3 applied, review needed (2026-09-24). Owner: Claude managed-run UI lane. Branch `claude/managed-run-ui` from 30800162, merged with integration 3f8db417. Not deployed.

Run context for the console blotter: an active-run view, read-only browsing of named (historical) runs for positions, trades and orders, run-scoped notifications, and fencing so no reply or event from one run or account lands in another. See [spec](spec.md), [plan](plan.md), [tasks](tasks.md) and the operator-facing [doc](../../../../docs/risk-integration/managed-run-ui.md).

Source: `web-front-end-console/src/app/run-scope.ts` (rules), `blotter-panel.ts` and `admin-panel.ts` (UI), `operator-control.mjs` (proxy refusal, used by `server.mjs` and `proxy.conf.mjs`). The console is standalone, not generated from this pack. Depends on the RI-06 readers in [recovery identity](../recovery-identity/README.md); changes no backend.

## Reproduce

Disposable local fixture rig only (ports 26800–26899, containers `traderx-mui-sql`/`traderx-mui-nats`); never point it at a retained database.

1. Generate YU18 into an empty root (`TRADERX_GENERATED_ROOT=…`), `bootJar` position-service, trade-processor and account-service with Java 21.
2. MariaDB 11 with `--lower-case-table-names=1`; load the generated ConfigMap `001-initialSchema.sql` then `900-migrations.sql` (`kubernetes-runtime/manifests/base/database-init-configmap.yaml`). NATS 2.10 with websocket on 8080 and monitoring on 8222.
3. Start the services on 26890/26891/26892 (`DATABASE_PG_PORT`, `NATS_ADDRESS`), `node proofs/fixture-edge.mjs` (26870), `npx ng build`, then `PORT=26800 EDGE_PROXY=127.0.0.1:26870 node web-front-end-console/server.mjs`.
4. `OUT=<dir> node proofs/browser-proof.mjs` — applies the `fixture-*.sql` files between steps, writes PNGs and `log.json`, exits non-zero on the first failed check. Step 10 stops and restarts the SQL container; step 11 pauses and unpauses it.
5. Real transition: `GEN=<target-generated> MATCHER_CLASSPATH_FILE=<file> JAVA=<java21> OUT=<dir> node proofs/real-transition-proof.mjs` on a freshly loaded DB (it applies `fixture-empty-legacy-history.sql` itself). The classpath file comes from the order-matcher `ri06RuntimeClasspath` init script, as in `recovery-identity/test-live-migration.sh`. Needs a backend generated from 3f8db417 or later (active pointer reader).

Combined recovery + managed UI acceptance (2026-09-25): see [reproducible local proof](../../../../docs/risk-integration/managed-recovery-acceptance.md) for the corrected generated-schema runtime, real managed-to-managed transition, unchanged initial failure and bounded claims.
