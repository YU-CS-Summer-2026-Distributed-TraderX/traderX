# Data Model: YU09 — Ops Hardening

This state has no application data model change — it changes how credentials are supplied and how
the journal file is laid out on disk. Both shapes below are new.

## Secret shapes (created out-of-band, never committed)

### `mariadb-credentials` (namespace `traderx`)

| Key | Consumed by |
|---|---|
| `username` | `database`, `order-matcher`, `trade-processor`, `account-service`, `position-service` |
| `password` | same five, plus MariaDB's own readiness/liveness probes |
| `root-password` | `database` only (`MARIADB_ROOT_PASSWORD`) |

### `auth-secrets` (namespace `traderx`)

| Key | Consumed by |
|---|---|
| `jwt-secret` | `order-matcher`, `trade-processor` — MUST be identical across both (shared HS256 secret, see YU05 spec.md FR-PTC40) |
| `dev-token-master-secret` | `trade-processor` only (`POST /auth/dev-token`) |

### `order-matcher-journal-gcs-hmac` (namespace `traderx`, optional)

| Key | Consumed by |
|---|---|
| `access-key-id` | `order-matcher`'s `JournalArchiver` |
| `secret-access-key` | `order-matcher`'s `JournalArchiver` |

Same two-key shape as YU07's `tick-store-gcs-hmac`, deliberately a separate Secret scoped to its
own bucket (`gs://traderx-501015-order-matcher-journal-archive`) rather than reused — the two
archival paths never share a credential.

## Journal file layout (`journal.path`, e.g. `/var/lib/traderx-lmax/journal`)

| File | Written by | Lifecycle |
|---|---|---|
| `input-events.journal` | `Journaler`, continuously | The one active file. Recovery always reads this path from byte 0 forward. |
| `input-events-<epoch-millis>.journal` | `Journaler.rotate()`, only when `journal.archive.enabled=true` | An immutable closed segment, created at each snapshot boundary. Handed to `JournalArchiver`; deleted locally only after a confirmed upload. |
| `snapshot.dat` | `SnapshotStore`, at each snapshot boundary | Unchanged by this state — still one file, overwritten each snapshot. `coveredOffset` it stores is now always relative to the *current* `input-events.journal` (0 immediately after a rotation), not a monotonically growing absolute offset. |
| `symbols.tab` | `SymbolTable`, on ticker assignment | Unchanged by this state. |

With `journal.archive.enabled=false` (the default), no `input-events-*.journal` segment files are
ever created — `input-events.journal` behaves exactly as in every prior state.
