# Runtime Topology: YU09-ops-hardening

Parent state: `YU08-execution-algo-engine`

## Entrypoints

No new HTTP or NATS entrypoint. This state changes configuration surface (Secrets, env vars) and
one internal file-format detail (journal segmentation) on existing components — every existing
entrypoint from `009` through `YU08` is unchanged.

## Components

- Inherits every Kubernetes/C3/FDC3/EOD/tick-store/algo-engine runtime component already present
  in `YU08-execution-algo-engine` unchanged. No new Deployment, Service, or PVC.
- `database`, `order-matcher`, `trade-processor`, `account-service`, `position-service` now read
  their database credential from the `mariadb-credentials` Secret instead of a literal manifest
  value.
- `order-matcher` and `trade-processor` now read their JWT/dev-token credential from the
  `auth-secrets` Secret.
- `order-matcher`'s `Journaler`, when `journal.archive.enabled=true`, rotates its journal file at
  each snapshot boundary and hands the closed segment to a new `JournalArchiver`, which uploads it
  to GCS (`gs://traderx-501015-order-matcher-journal-archive`) on its own background thread,
  authenticated via the optional `order-matcher-journal-gcs-hmac` Secret.

## Networking

- No new network path. `JournalArchiver`'s GCS upload is the one new egress call from
  `order-matcher` (to `storage.googleapis.com`, HTTPS, S3-compatible XML API) — off the hot path,
  from a dedicated background thread, only when archival is enabled and configured.

## Startup / Health Order

1. `mariadb-credentials` and `auth-secrets` Secrets must exist before `database`, `order-matcher`,
   `trade-processor`, `account-service`, or `position-service` can reach Ready — neither is
   `optional`, so a missing Secret is a visible `CreateContainerConfigError`, not a silent
   fallback to a hardcoded value (see `system/adr-033-secrets-via-out-of-band-kubectl-secrets.md`).
2. Everything else follows the `YU08-execution-algo-engine` startup order unchanged.
3. If `journal.archive.enabled=true`, `order-matcher` starts normally regardless of whether
   `order-matcher-journal-gcs-hmac` exists — the Secret is `optional`, and its absence only
   disables the GCS upload leg (rotation, which bounds local disk, still happens).

## Degraded Behavior

| Condition | Effect |
|---|---|
| `mariadb-credentials` or `auth-secrets` Secret missing | Affected pods stay in `CreateContainerConfigError`, never reach Ready — visible in `kubectl get pods`/`describe pod`, not a silent dev-default fallback. |
| `order-matcher-journal-gcs-hmac` Secret missing, archival enabled | Rotation still happens at every snapshot (local disk stays bounded); each closed segment logs a warning and stays on the PVC instead of uploading. |
| GCS unreachable or a `putObject` call fails, archival enabled and configured | The failing segment is logged and left on local disk (never deleted without a confirmed upload); the next snapshot's rotation is unaffected — it produces its own new segment independently. |
| Journal rotation itself fails (e.g. a filesystem error renaming the active file) | Logged and swallowed; journaling continues on the current (unrotated) file — the same availability-over-durability posture `Journaler` already takes on an append failure. |
