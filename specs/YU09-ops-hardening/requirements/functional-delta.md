# Functional Delta: YU09-ops-hardening (vs YU08-execution-algo-engine)

No trading behaviour changes here: every REST, NATS, and UI contract from
`YU08-execution-algo-engine` is retained, and the deploy/runtime harness, the observability stack,
and every service other than `order-matcher` and the MariaDB container stay inherited unchanged. What
this state changes is how the system is operated: credentials move out of committed manifests into
Kubernetes Secrets, the order-matcher gains an opt-in path for rotating its journal and archiving
it off-box, the shared build pipeline can no longer ship a stale jar, and the cluster's real
failure modes get a written recovery procedure.

## Added

- `mariadb-credentials` and `auth-secrets` Kubernetes Secrets, created out-of-band and never committed, so a repo checkout never exposes a working credential.
- Journal rotation at every snapshot boundary when `journal.archive.enabled` is true (`Journaler.rotate()`), closing the active file off as an immutable, timestamped segment.
- A `JournalArchiver` that uploads each closed segment to the GCS bucket named by `journal.archive.bucket`, so journal history survives loss of the pod's own volume.
- HMAC-authenticated uploads over GCS's S3-compatible XML API — the same interoperability mode the tick-store capture already uses.
- A dedicated background upload thread, so the journaler thread servicing the input Disruptor ring never waits on network I/O.
- A closed segment that fails to upload, kept on local disk — deletion follows only a confirmed upload, so archival never loses journal data.
- A `journal.archive.enabled` flag defaulting to `false`, so the shipped default reproduces the parent state's single growing journal file exactly.
- An optional `order-matcher-journal-gcs-hmac` Secret whose absence disables only the upload leg — pod startup and journal rotation are unaffected.
- `system/dr-runbook.md`, documenting blast radius and recovery for BLP pod loss, node loss, zone loss, and MariaDB data loss on the single-zone deployment.

## Changed

- Database credentials in the `database`, `order-matcher`, `trade-processor`, `account-service`, and `position-service` manifests now resolve through `secretKeyRef` rather than literal values.
- `AUTH_JWT_SECRET` on `order-matcher` and `trade-processor`, plus `AUTH_DEV_TOKEN_MASTER_SECRET` on `trade-processor` alone, now sourced from the `auth-secrets` Secret.
- The production `cluster-addons/order-matcher-statefulset.yaml` reads the same two Secrets as the kind/GKE-rendered Deployments rather than carrying its own credentials.
- MariaDB's readiness and liveness probes authenticate with the container's own `$MARIADB_USER`/`$MARIADB_PASSWORD` instead of a hardcoded user and password.
- `pipeline/publish-generated-state-branch.sh` runs `./gradlew --no-daemon clean bootJar` before every JVM service's `docker build`, so a Docker layer-cache hit can no longer deploy a stale jar.

## Removed

- Literal database and JWT/dev-token credential values, which no longer appear in any committed manifest in the repository.
