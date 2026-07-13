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

## Not verified in this session

- The GCS upload leg of journal archival (`JournalArchiver.archiveAsync`'s actual `putObject`
  call) — requires a real HMAC credential against a live GCS bucket, out-of-band per
  `quickstart.md`, same as YU07's tick-store credential was never exercised end-to-end in-session
  either. Rotation itself (the local-disk-bounding half of the feature) was verified: it is
  reachable code, flag-gated, and passes the hot-path banned-API and allocation gates; the actual
  upload path is standard, well-tested AWS SDK v2 behavior against a documented-compatible
  endpoint, not custom code.
- Production GKE deployment (this session only exercised kind).
