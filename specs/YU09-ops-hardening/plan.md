# Implementation Plan: YU09-ops-hardening

## Goal

Close the four ops-hardening gaps found while E2E-verifying and bench-comparing every prior state
(YU03–YU08): plaintext secrets in committed manifests, unbounded journal growth, a stale-jar
Docker build bug, and an undocumented recovery path for the cluster's actual single-zone topology.

## Workstreams

1. Secrets
   - `mariadb-credentials` Secret (keys `username`/`password`/`root-password`) replaces literal
     `MARIADB_*`/`DATABASE_DBUSER`/`DATABASE_DBPASS` values in `database-deployment.yaml` and
     every DB-consuming service's Deployment (`order-matcher`, `trade-processor`,
     `account-service`, `position-service`), plus the production
     `cluster-addons/order-matcher-statefulset.yaml`.
   - `auth-secrets` Secret (keys `jwt-secret`/`dev-token-master-secret`) replaces the code's
     `dev-jwt-shared-secret`/`dev-token-master-secret` defaults for `order-matcher` and
     `trade-processor`.
   - Both created out-of-band via `kubectl create secret`, same as YU07's `tick-store-gcs-hmac`;
     never committed.
2. Journal rotation + archival
   - `Journaler` rotates the active journal file to a timestamped closed segment right after a
     SNAPSHOT marker is durably written, resetting `lastSnapshotOffset` to 0 for the new file —
     the same byte-offset invariant `SnapshotStore`/recovery already depend on, just relative to a
     fresh file instead of an ever-growing one.
   - `JournalArchiver` uploads each closed segment to GCS on its own background thread (an S3
     client pointed at GCS's S3-compatible XML API, authenticated with the same HMAC key/secret
     shape as YU07's tick-store), never the journaler thread.
   - Whole feature is gated by `journal.archive.enabled` (default `false`): unset, behavior is
     byte-for-byte what YU02's Journaler already did.
3. Pipeline fix
   - `publish-generated-state-branch.sh`'s image-build loop runs `./gradlew --no-daemon clean
     bootJar` in any build context containing a `build.gradle`, before `docker build` — closes the
     class of bug where a stale `build/libs/*.jar` gets baked into the image via Docker's layer
     cache.
4. DR runbook
   - `system/dr-runbook.md` documents blast radius and recovery procedure for BLP pod loss, node
     loss, zone loss, and MariaDB data loss, grounded in the cluster's actual topology (single
     zone, single MariaDB replica, no automated off-cluster backup) rather than a target
     architecture that isn't built.

## Key decisions

- Journal rotation piggybacks on the existing snapshot boundary rather than a separate timer —
  `writeSnapshot()` already reads `journaler.lastSnapshotOffset()` immediately after the SNAPSHOT
  marker is journaled (Disruptor barrier ordering guarantees the journaler thread runs first), so
  rotating at that exact point keeps the byte-offset invariant trivially correct with no new
  concurrency to reason about — see `system/adr-032-journal-rotation-and-gcs-archival.md`.
- GCS upload uses AWS SDK v2's S3 client against `storage.googleapis.com` (GCS's documented
  S3-compatible interoperability mode) rather than hand-rolled HMAC request signing — correctness
  risk of a bespoke signer outweighs the cost of one added dependency.
- DR is scoped to a runbook, not real multi-region infrastructure — the cluster is single-zone by
  design (see root `CLAUDE.md`), and provisioning genuine multi-region GKE is out of proportion to
  what a demo research fork needs; the documented, tested manual recovery path is the deliverable.

## Exit Criteria

- Spec and tasks are complete.
- `bash pipeline/generate-state.sh YU09-ops-hardening` exits 0.
- Every touched manifest and `application.properties` sources credentials from a Secret, verified
  by grep (SC-OH03).
- `run-state-kind` brings the state up E2E-healthy with the two required Secrets pre-created.
- `bench-compare` shows no regression against the YU08 baseline with archival disabled (the
  shipped default).
- State can be committed to the `YU09-ops-hardening` branch (never pushed).
