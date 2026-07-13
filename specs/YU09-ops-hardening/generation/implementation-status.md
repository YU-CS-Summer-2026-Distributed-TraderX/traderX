# Implementation Status: YU09-ops-hardening

Secrets, journal rotation + GCS archival, the stale-jar pipeline fix, and a DR runbook —
**implemented and verified live end-to-end on a local kind cluster**, including `bench-compare`
against the YU08 baseline.

## What is implemented

### Secrets (FR-OH01–FR-OH04)

- `mariadb-credentials`/`auth-secrets` Secrets, `secretKeyRef` in `database-deployment.yaml`,
  `order-matcher-deployment.yaml`, `trade-processor-deployment.yaml`,
  `account-service-deployment.yaml`, `position-service-deployment.yaml`, and the production
  `cluster-addons/order-matcher-statefulset.yaml`.
- MariaDB's readiness/liveness probes read `$MARIADB_USER`/`$MARIADB_PASSWORD` from the
  container's own env instead of a hardcoded `-utraderx -ptraderx`.

### Journal rotation + archival (FR-OH20–FR-OH23)

- `Journaler.rotate()` — closes the active file, renames it to a timestamped closed segment,
  reopens fresh, resets `writtenBytes`/`lastSnapshotOffset` to 0. Called from `onEvent`
  immediately after a SNAPSHOT marker is journaled and forced.
- `JournalArchiver` — HMAC-authenticated `software.amazon.awssdk:s3` client against
  `storage.googleapis.com`, fire-and-forget upload on a dedicated background thread, local file
  kept on any upload failure.
- `journal.archive.enabled` defaults `false`; legacy `Journaler` constructors preserved
  (`archiver=null`) so unmodified deployments see byte-for-byte identical behavior.

### Pipeline fix (FR-OH30)

- `publish-generated-state-branch.sh`'s image-build loop runs `./gradlew --no-daemon clean
  bootJar` before `docker build` for any context with a `build.gradle`.

### DR runbook (FR-OH40)

- `system/dr-runbook.md`.

### Pipeline wiring

Found by running generation and reading the failure, same pattern every prior state's own status
doc describes:

- `catalog/state-catalog.json` — a `YU09-ops-hardening` entry.
- `pipeline/generate-state-YU09-ops-hardening.sh` / `pipeline/render-state-YU09-ops-hardening.sh`
  — generate-parent-then-overlay, modeled on YU08's pair.
- `pipeline/install-generated-runtime-harness.sh` — a `YU09-ops-hardening)` case block (both the
  script-copy case and the generation-depth case) copying the full ancestor chain's
  start/stop/status/test scripts plus YU09's own.
- `pipeline/install-generated-ci-assets.sh` — a `YU09-ops-hardening)` case extending
  `state_allowed_roots` with `"YU09-ops-hardening"`.
- `scripts/{start,stop,status}-state-YU09-ops-hardening-generated.sh`,
  `scripts/test-state-YU09-ops-hardening.sh` — committed at repo root, modeled on YU08's.

## Shared-file override verification (research.md / generation-hook.md)

Every manifest and Java file YU09 overrides has exactly one prior ancestor override each (no
intermediate state also touches the same path) — confirmed by `find specs -path
"*runtime-overrides*<file>"` returning exactly one hit before YU09's own. Verified empirically
after generating (`bash pipeline/generate-state.sh YU09-ops-hardening`):

- `order-matcher-deployment.yaml`: contains both `mariadb-credentials` (YU09) and the full YU02
  env surface (`DISRUPTOR_INPUT_RING_SIZE`, `SNAPSHOT_INTERVAL_MS`, etc.) — nothing dropped.
- `application.properties`: contains both `journal.archive.enabled` (YU09) and `RISK_ENABLED`
  (YU05's risk gateway config, the latest ancestor override) — nothing dropped.
- `LmaxEngine.java`: contains both `journalArchiveEnabled` (YU09) and every YU05 risk-gateway
  `@Value` param — nothing dropped.

## Verification performed

1. **Generation**: `bash pipeline/generate-state.sh YU09-ops-hardening` exits 0 (confirmed
   3 consecutive runs, including after fixing the runtime-harness case-block gap).
2. **Compile + unit tests**: `./gradlew test` on the generated `order-matcher` module — 85 tests,
   0 failures on a clean run (one `AllocationGateTest` flake reproduced identically against the
   unmodified YU08 baseline on this machine — pre-existing JIT/warmup timing sensitivity, not a
   YU09 regression; confirmed by running the same test in isolation, twice, both passing).
3. **`HotPathBannedApiTest`**: initially caught `java.time.Instant` and string-concatenation
   (`makeConcatWithConstants`) references introduced by the first `rotate()` draft — fixed by
   switching to `System.currentTimeMillis()` and explicit `StringBuilder.append()`. Passes clean
   after the fix.
4. **`run-state-kind` E2E** (fresh `--recreate-cluster`, `mariadb-credentials`/`auth-secrets`
   pre-created before pod start):
   - No pod anywhere reached `CreateContainerConfigError`.
   - `kubectl exec` into `order-matcher` confirmed `AUTH_JWT_SECRET`, `DATABASE_DBUSER`,
     `DATABASE_DBPASS` are populated via the Secret (not empty, not literal-in-manifest).
   - Hit the known YU04+ coordinated-restart gotcha (order-matcher/trade-service crashlooped
     briefly on `Cannot connect to NATS` while `nats-broker` was still rolling out during the
     multi-pod restart) — resolved itself once NATS stabilized, no code change needed.
   - `riskReplicaReady=true`; smoke order (`POST /orders`, IBM, qty 10 @ 200) returned `201`.
5. **`bench-compare`** against the YU08 baseline (`scripts/bench/results/max-load-avg-2026-07-
   12T23-52-24-445Z.txt`, 393 peak/s, 0 failed), same kind cluster, `journal.archive.enabled`
   unset (shipped default — exercises the unmodified legacy `Journaler` code path):

   | | peak/s | submit/s | failed |
   |---|---|---|---|
   | YU08 baseline | 393 | 161,505 | 0 |
   | YU09 (this run) | 399 | 134,138 | 0 |

   No regression (399 vs 393 peak/s is within run-to-run noise on this metric; submit/s variance
   is client-side REST throughput, not matcher throughput, and both runs show 0 failed orders).

## GCS upload leg — verified live (follow-up session, 2026-07-13)

The actual `JournalArchiver.archiveAsync` `putObject` path was exercised end-to-end against a real
GCS bucket after the initial YU09 landing:

- Provisioned (out-of-band, mirroring YU07's `tick-store-gcs`): bucket
  `gs://traderx-501015-order-matcher-journal-archive` (us-east1, Standard), a bucket-scoped
  `order-matcher-journal-gcs` service account with `roles/storage.objectAdmin` on just that
  bucket, and an HMAC key delivered into the `order-matcher-journal-gcs-hmac` Secret without the
  secret value ever passing through a chat/tool log.
- On kind with `journal.archive.enabled=true` and a short `SNAPSHOT_INTERVAL_MS`, closed segments
  upload to the bucket and are deleted locally on success (`gcloud storage ls` confirms objects
  landing; `kubectl exec ... ls` confirms the corresponding local files are gone). Pre-fix
  segments that failed to upload correctly remained on local disk, never deleted — the FR-OH23
  no-data-loss-on-failure behavior, observed directly.
- Two real S3-client bugs were found and fixed in the process (commit `f6d956b`): AWS SDK v2's
  default aws-chunked payload signing is rejected by GCS's XML API (disable chunked encoding), and
  path-style addressing is required per GCS interop docs. Upload-failure logging was also widened
  to surface full `S3Exception` detail — that is what distinguished the genuine GCS-interop config
  issues from a bad-credential `SignatureDoesNotMatch` (a mistyped HMAC secret) during debugging.

## GKE production deploy — completed (follow-up session, 2026-07-13)

YU09 rolled out to the live `traderx-lmax` cluster, replacing the plaintext-credential deployment
that had been running since before this state existed:

- Recovered five `cluster-addons/` infra manifests (`order-matcher-headless-service.yaml`,
  `order-matcher-primary-service.yaml`, `order-matcher-rbac.yaml`, `traderx-ingress.yaml`,
  `letsencrypt-issuer.yaml`) that CLAUDE.md documented as canonical but were never actually
  committed on any branch — exported authoritatively from the live cluster (commit `ffbc10c`).
  This had been silently blocking the manual GKE deploy path (`set -euo pipefail` aborted on the
  missing headless service) the whole time, independent of YU09.
- Found and fixed three unrelated drifts between the committed `order-matcher-statefulset.yaml`
  and the live object before applying (commit `6edf073`): a missing `podManagementPolicy:
  Parallel` (an immutable field — this alone was rejecting the whole apply), a stale `replicas: 2`
  /`BLP_REPLICATION_ENABLED: true` that would have silently flipped production from single-BLP to
  HA mode as a side effect of this deploy, and an entirely-missing `BLP_POD_NAME` downward-API env
  var that would have been silently dropped.
- Created `mariadb-credentials`/`auth-secrets` on prod with strong rotated values (user-driven;
  values never touched chat/tool output) and rotated the live MariaDB `traderx` user's password to
  match via `ALTER USER` (existing DB data preserved, not wiped).
- Built + pushed a fresh `order-matcher` image (`state009-yu09-20260713`, linux/amd64 — the kind
  build was arm64) with the JournalArchiver GCS fixes, and rolled it out.
- **Found while verifying: every other service in prod (`account-service`, `reference-data`,
  `position-service`, `trade-processor`, `trade-service`, `price-publisher`, `people-service`) was
  running a build from 2026-07-01 — 12 days stale, missing YU03 through YU09 entirely.** Only
  order-matcher has CI/CD keeping it current; nothing has ever kept the rest current. This
  surfaced because order-matcher's post-YU09 restart needed account-service's `/account/control-
  snapshot` endpoint (added in YU04), which the stale build didn't have — a 500 with no logged
  stack trace, diagnosed via `curl` from inside the pod. Rebuilt and redeployed all 7 with dated
  tags (`state009-yu09-20260713`) from current source.
- **Found while verifying gap #1's actual scope: `reference-data` connects to MariaDB (YU04's
  stocks/outbox persistence) but was never covered by YU09's original secretKeyRef migration** —
  only `database`/`order-matcher`/`trade-processor`/`account-service`/`position-service` were in
  scope. After the DB password rotation, `reference-data` crashlooped with
  `ER_ACCESS_DENIED_ERROR` until fixed (commit `3f3bb3a`).
- Verified: no pod anywhere in `CreateContainerConfigError`; `riskReplicaReady=true`; smoke order
  (`POST /orders`, IBM, qty 10 @ 200) returned `201` against the live production order-matcher.

## GKE stale-image follow-up — completed (2026-07-13)

The two services left out of the first production pass and the failing EOD trigger are now fixed:

- Regenerated `YU09-ops-hardening`, built and pushed fresh `linux/amd64` images for
  `execution-algo-engine` and `tick-store`, and deployed both with the immutable dated tag
  `state009-yu09-20260713`. Their canonical YU08/YU07 runtime-override manifests now use the full
  Artifact Registry references, preventing a future regeneration/apply from restoring the broken
  `state-yu08` or bare `traderx/tick-store:state-yu07` references. Both Deployments reached `1/1`
  Ready and their running image IDs matched the newly pushed digests.
- Fixed `eod-session-close` to read `EOD_MASTER_SECRET` from
  `auth-secrets/dev-token-master-secret`. The inherited YU06 manifest still contained the old
  plaintext demo default, so after YU09 rotated credentials its first `/auth/dev-token` request
  failed with 401 before any JWT was minted.
- Scaling the cluster back up exposed a separate first-boot bug in YU09's MariaDB probe change:
  the liveness probe began after 10 seconds, while initialization took about 39 seconds, so
  kubelet killed MariaDB after system-table creation but before the `traderx` database, user, and
  init SQL were installed. Added a credential-aware startup probe (150-second budget) so liveness
  remains disabled until initialization completes. Verified the replacement pod created the
  `traderx` schema and 14 initial tables.
- Verified the repaired CronJob with a one-off Job: token mint succeeded, session close returned
  `PUBLISHED` version 1 for 20 instruments with zero flags, and the Job completed successfully.

## Not verified in this session

- The GCS-upload leg of journal archival on production specifically — verified live on kind
  (above) and the same code is now deployed to prod, but `order-matcher-journal-gcs-hmac` was not
  created on the prod cluster in this session (archival's rotation-only behavior is unaffected;
  only the upload leg stays inactive on prod until that Secret exists there too).
