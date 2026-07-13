# Functional Delta: YU09-ops-hardening over YU08-execution-algo-engine

New requirement namespace `OH`.

| Req | Status | Notes |
|---|---|---|
| FR-OH01 DB creds via Secret | **Done** | `mariadb-credentials` Secret, `secretKeyRef` in `database-deployment.yaml` + all four DB-consuming service Deployments. |
| FR-OH02 JWT/dev-token creds via Secret | **Done** | `auth-secrets` Secret, `secretKeyRef` in `order-matcher-deployment.yaml` + `trade-processor-deployment.yaml`. |
| FR-OH03 production StatefulSet uses same Secrets | **Done** | `cluster-addons/order-matcher-statefulset.yaml` edited directly (hand-maintained, not part of the generation overlay). |
| FR-OH04 no literal credential anywhere | **Done** | Verified by SC-OH03 grep. |
| FR-OH20 journal rotates at snapshot boundary | **Done** | `Journaler.rotate()`, called from `onEvent` right after a SNAPSHOT marker is journaled and forced. |
| FR-OH21 closed segments uploaded to GCS | **Done** | `JournalArchiver.archiveAsync`, AWS SDK v2 S3 client against GCS's S3-compatible XML API, HMAC-authenticated. |
| FR-OH22 archival off by default preserves prior behavior | **Done** | `journal.archive.enabled` defaults `false`; `Journaler`'s legacy constructors still exist and pass `archiver=null`. |
| FR-OH23 failed upload never deletes local segment | **Done** | `JournalArchiver.archiveAsync` only calls `Files.deleteIfExists` after `putObject` returns successfully. |
| FR-OH30 gradlew bootJar before docker build | **Done** | `publish-generated-state-branch.sh`'s build loop, gated on `build.gradle` presence in the context dir. |
| FR-OH40 DR runbook | **Done** | `system/dr-runbook.md`. |
