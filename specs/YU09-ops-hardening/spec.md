# Feature Specification: Ops Hardening

**Feature Branch**: `YU09-ops-hardening`
**Created**: 2026-07-12
**Status**: Implemented
**Input**: Backlog item #3 in `issues/HANDOFF-idea-INDEX.md`, parented on `YU08-execution-algo-engine`

## User Stories

- As the cluster operator, I want database and JWT/dev-token credentials sourced from Kubernetes
  Secrets instead of literal values in committed manifests, so a repo checkout never exposes a
  working credential.
- As the cluster operator, I want the order-matcher journal to stop growing without bound on its
  PVC, so a long-lived pod doesn't eventually exhaust local disk.
- As the cluster operator, I want closed journal segments archived off-box, so historical journal
  data survives a PVC loss instead of only living on the pod's own volume.
- As a developer running `run-state-kind` or a fresh GKE deploy, I want the Docker build step to
  always build from freshly compiled bytecode, so a stale `build/libs/*.jar` can never be
  silently deployed under a Docker layer cache hit.
- As the cluster operator, I want a written runbook for the cluster's actual failure modes (node,
  zone, database, journal loss) given its current single-zone topology, so a real incident has a
  documented recovery path instead of an ad hoc one.

## Functional Requirements

- FR-OH01: `database-deployment.yaml`, `order-matcher-deployment.yaml`,
  `trade-processor-deployment.yaml`, `account-service-deployment.yaml`, and
  `position-service-deployment.yaml` SHALL source `MARIADB_USER`/`MARIADB_PASSWORD`/
  `MARIADB_ROOT_PASSWORD`/`DATABASE_DBUSER`/`DATABASE_DBPASS` from the `mariadb-credentials`
  Secret via `secretKeyRef`, not literal values.
- FR-OH02: `order-matcher-deployment.yaml` and `trade-processor-deployment.yaml` SHALL source
  `AUTH_JWT_SECRET` (and, for trade-processor, `AUTH_DEV_TOKEN_MASTER_SECRET`) from the
  `auth-secrets` Secret via `secretKeyRef`, not the code's dev-default.
- FR-OH03: The production StatefulSet (`cluster-addons/order-matcher-statefulset.yaml`) SHALL use
  the same `mariadb-credentials`/`auth-secrets` Secrets as the kind/GKE-rendered Deployment
  manifests, not its own literal values.
- FR-OH04: No committed manifest anywhere in the repository SHALL contain a literal database or
  JWT/dev-token credential value.
- FR-OH20: When `journal.archive.enabled` is true, the order-matcher journal SHALL rotate to a new
  active file at every snapshot boundary, closing the previous file off as an immutable segment.
- FR-OH21: Each closed journal segment SHALL be uploaded to the GCS bucket named by
  `journal.archive.bucket`, authenticated via HMAC key/secret, the same interoperability mode
  YU07's tick-store capture uses.
- FR-OH22: When `journal.archive.enabled` is false (the default), the journal SHALL behave exactly
  as before this state — one file, growing without rotation.
- FR-OH23: A journal segment that fails to upload SHALL remain on local disk; archival SHALL never
  delete a segment it did not confirm was uploaded.
- FR-OH30: `pipeline/publish-generated-state-branch.sh`'s image build step SHALL run `./gradlew
  --no-daemon clean bootJar` in a JVM service's build context before `docker build`, for every
  context containing a `build.gradle`.
- FR-OH40: `system/dr-runbook.md` SHALL document, for the cluster as actually deployed
  (single-zone GKE, single-replica MariaDB), the blast radius and recovery procedure for: BLP pod
  loss, node loss, zone loss, and MariaDB data loss.

## Non-Functional Requirements

- NFR-OH01: Journal archival SHALL introduce no order-matcher throughput regression when disabled
  (the default) — verified by `bench-compare` against the YU08 baseline.
- NFR-OH02: The GCS upload of a closed journal segment SHALL run on a dedicated background thread,
  never the journaler thread that services the input Disruptor ring.
- NFR-OH03: No Secret value (`mariadb-credentials`, `auth-secrets`,
  `order-matcher-journal-gcs-hmac`) SHALL be committed to git; each is created out-of-band via
  `kubectl create secret`, documented in `quickstart.md`.
- NFR-OH04: The `mariadb-credentials` and `auth-secrets` Secrets SHALL be required for `database`,
  `order-matcher`, `trade-processor`, `account-service`, and `position-service` pods to reach
  Ready; the `order-matcher-journal-gcs-hmac` Secret SHALL be optional — its absence disables only
  the GCS upload leg of archival, not pod startup or journal rotation.

## Success Criteria

- SC-OH01: `bash pipeline/generate-state.sh YU09-ops-hardening` exits 0.
- SC-OH02: On kind, with `mariadb-credentials` and `auth-secrets` Secrets created before pod
  start, every pod reaches Ready and a smoke order is accepted end-to-end.
- SC-OH03: `grep`ing every committed manifest and `application.properties` under
  `generation/runtime-overrides/` and `cluster-addons/` for a literal `traderx`/`dev-jwt-shared-
  secret`/`dev-token-master-secret` credential value in a `MARIADB_*`/`DATABASE_DBUSER`/
  `DATABASE_DBPASS`/`AUTH_JWT_SECRET`/`AUTH_DEV_TOKEN_MASTER_SECRET` env entry finds none —
  every one is a `secretKeyRef`.
- SC-OH04: With `journal.archive.enabled=true` and a running order-matcher, a snapshot boundary
  produces a new `input-events-<epoch-millis>.journal` segment file distinct from the active
  `input-events.journal`.
- SC-OH05: `bench-compare` against the YU08 baseline shows no throughput regression with
  `journal.archive.enabled=false` (the shipped default).
- SC-OH06: `pipeline/publish-generated-state-branch.sh` runs `gradlew bootJar` before `docker
  build` for every context with a `build.gradle`, verified by a stale-jar regression check
  (`scripts/test-state-YU09-ops-hardening.sh`).
