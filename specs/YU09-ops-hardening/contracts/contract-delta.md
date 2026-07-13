# Contract Delta: YU09 over YU08-execution-algo-engine

All existing REST + NATS + UI contracts are retained unchanged. This state adds no HTTP endpoint,
no NATS subject, and no request/response payload change — every delta is in configuration surface
(env vars, Secrets) and an internal file-format detail (journal segmentation), not a wire contract.

## 1. Environment variable surface (new, `order-matcher`)

| Var | Effect |
|---|---|
| `AUTH_JWT_SECRET` (also `trade-processor`) | Now sourced from `auth-secrets` Secret in every committed manifest; the code's `dev-jwt-shared-secret` default is unchanged and still applies if the var is genuinely unset (e.g. running the jar directly, outside k8s). |
| `ORDER_MATCHER_JOURNAL_ARCHIVE_ENABLED` | Default `false`. `true` enables rotation-at-snapshot + GCS upload. |
| `ORDER_MATCHER_JOURNAL_ARCHIVE_BUCKET` | `gs://` URI; bucket + optional key prefix for closed segments. |
| `JOURNAL_ARCHIVE_GCS_HMAC_KEY_ID` / `JOURNAL_ARCHIVE_GCS_HMAC_SECRET_ACCESS_KEY` | HMAC credential for the upload leg; absence disables uploads only, not rotation. |

`trade-processor` additionally sources `AUTH_DEV_TOKEN_MASTER_SECRET` from `auth-secrets` — same
default-preserving behavior as `AUTH_JWT_SECRET` above.

## 2. Journal file contract (internal, `order-matcher`'s own PVC — not a network contract)

With `journal.archive.enabled=true`, `RECOVERY_SOURCE=journal` recovery still reads
`input-events.journal` from byte 0 and replays forward — unchanged from the reader's point of
view. The only observable difference is that the file is now periodically small (reset at each
snapshot) instead of monotonically growing; closed segments (`input-events-<epoch-millis>.journal`)
are never read by recovery, only by `JournalArchiver`.

## Not changed

Order/trade/position/risk/post-trade/EOD/execution-algo payload shapes and subjects, matching
policy, every existing REST/NATS contract from `009` through `YU08`, the BLP hot-path event
schema, UI journeys. Pod readiness/liveness HTTP contracts (`/actuator/health/*`) are unchanged;
only what feeds their upstream config (Secrets instead of literals) changed.
