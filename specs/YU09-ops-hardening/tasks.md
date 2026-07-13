# Tasks: YU09-ops-hardening

Build order follows the recommended v1 order: secrets → journal archival → pipeline fix → DR
runbook.

## Secrets
- [x] T01 `mariadb-credentials` Secret shape (`username`/`password`/`root-password`); `secretKeyRef`
  in `database-deployment.yaml`.
- [x] T02 `secretKeyRef` for `DATABASE_DBUSER`/`DATABASE_DBPASS` in `order-matcher`,
  `trade-processor`, `account-service`, `position-service` Deployments.
- [x] T03 `auth-secrets` Secret shape (`jwt-secret`/`dev-token-master-secret`); `secretKeyRef` for
  `AUTH_JWT_SECRET` (order-matcher, trade-processor) and `AUTH_DEV_TOKEN_MASTER_SECRET`
  (trade-processor).
- [x] T04 Same `secretKeyRef` wiring on the production `cluster-addons/order-matcher-statefulset.yaml`.
- [x] T05 MariaDB readiness/liveness probes read `$MARIADB_USER`/`$MARIADB_PASSWORD` from the
  container's own env instead of a hardcoded `-utraderx -ptraderx`.

## Journal rotation + archival
- [x] T10 `Journaler.rotate()`: close + rename the active file to a timestamped closed segment,
  reopen fresh, reset `writtenBytes`/`lastSnapshotOffset` to 0 — called from `onEvent` right after
  a SNAPSHOT marker is journaled and forced.
- [x] T11 `JournalArchiver`: HMAC-authenticated S3 client against GCS's XML API, fire-and-forget
  upload on its own background thread, local file kept on any failure.
- [x] T12 Wire `journal.archive.*` properties + `@Value` params through `LmaxEngine` into the new
  `Journaler`/`JournalArchiver` constructor overload; legacy constructors preserved
  (`archiver=null`) so existing callers/tests are unaffected.
- [x] T13 `journal.archive.enabled` defaults `false` in `application.properties`; manifests turn it
  on explicitly.

## Pipeline fix
- [x] T20 `publish-generated-state-branch.sh`'s build loop: run `./gradlew --no-daemon clean
  bootJar` in any context with a `build.gradle`, before `docker build`.

## DR runbook
- [x] T30 `system/dr-runbook.md`: blast radius + recovery procedure for BLP pod loss, node loss,
  zone loss, MariaDB data loss, grounded in the cluster's actual single-zone topology.

## Verification
- [x] T40 Generation hook + render wiring; `pipeline/generate-state.sh YU09-ops-hardening` exits 0.
- [x] T41 `scripts/test-state-YU09-ops-hardening.sh`: grep-verifies no literal credential remains
  in any touched manifest, and that the build loop's `gradlew bootJar` step is present.
- [x] T42 `run-state-kind` E2E bring-up with both required Secrets pre-created; smoke order
  accepted.
- [x] T43 `bench-compare` against the YU08 baseline with `journal.archive.enabled=false`.
