# Contract Delta: YU06 over YU05-post-trade-compliance

All existing order/trade/position/risk/post-trade REST + NATS + UI contracts are retained. Every
delta below is additive. All new `/eod/*` endpoints require an `admin` JWT
(`Authorization: Bearer <token>`), reusing YU05's `JwtAuthenticator` and `POST /auth/dev-token`
minter — no new auth mechanism.

## 1. Session close (new, trade-processor, `/eod/session/close`)

| Method | Path | Query | Effect |
|---|---|---|---|
| `POST` | `/eod/session/close` | `sessionDate` (ISO date, optional; default = today) | Produces the next `DRAFT` snapshot version for the date (last-trade close per instrument + quality classification). If `eod.session.auto-publish` and zero flags, auto-publishes and emits `EOD_PRICES_READY`. Returns the quality report `{sessionDate, version, status, instrumentCount, flaggedCount, instruments:[{security, closingPrice, quality, sourceTickMillis}]}`. |

Non-admin/invalid JWT → **401**.

## 2. Produce/read snapshot (new, trade-processor, `/eod/prices/{sessionDate}`)

| Method | Path | Effect |
|---|---|---|
| `GET` | `/eod/prices/{sessionDate}` | Latest version's snapshot + quality report for the date. **404** if none produced yet. |
| `GET` | `/eod/prices/{sessionDate}/versions/{version}` | A specific immutable version. **404** if absent. |

## 3. Manual override (new, trade-processor, `/eod/prices/{sessionDate}/override`)

| Method | Path | Body | Effect |
|---|---|---|---|
| `POST` | `/eod/prices/{sessionDate}/override` | `{security, price, reason}` | Creates a new version copying the latest, with `security` set to `price`, `quality=OVERRIDDEN`, `override_reason=reason`. Returns the new version's quality report. Unknown security for the date → **404**; malformed price → **400**. |

## 4. Publish / gate (new, trade-processor, `/eod/prices/{sessionDate}/publish`)

| Method | Path | Effect |
|---|---|---|
| `POST` | `/eod/prices/{sessionDate}/publish` | Publishes the latest version. **200** + emits `EOD_PRICES_READY` if zero unresolved flags; **409** `{flaggedCount, flagged:[...]}` and no event otherwise (fail-safe). Already-published latest version → **200** no-op (idempotent). |

## 5. Gate event (new, NATS JetStream, `eod.prices.ready`)

Durable stream `TRADERX_EOD` (file storage). Payload:

```json
{ "sessionDate": "2026-07-08", "version": 3, "instrumentCount": 42, "publishedAtMillis": 1751990400000 }
```

Emitted only after the version's rows are committed and status is `PUBLISHED` (FR-EOD22).

## 6. Chain-link event (new, NATS JetStream, `eod.pnl.done`)

Emitted by position-service after it finishes marking a session:

```json
{ "sessionDate": "2026-07-08", "version": 3, "accountsMarked": 17, "accountsHalted": 1, "completedAtMillis": 1751990460000 }
```

Not consumed within this state — published for the next stage (VaR/NAV) to subscribe to later.

## 7. Metrics (Prometheus)

Added via standard Micrometer gauges/counters (bounded cardinality, no per-instrument/-account
labels), mirroring YU05's `SystemController` pattern:

- **trade-processor**: `traderx_eod_sessions_published_total`, `traderx_eod_quality_flagged_total`,
  `traderx_eod_last_publish_millis`.
- **position-service**: `traderx_eod_pnl_accounts_marked_total`, `traderx_eod_pnl_halted_total`,
  `traderx_eod_pnl_last_completed_millis`.

Grafana dashboard: `traderx-eod-batch-chain.json` (provisioned via the same ConfigMap mechanism as
every other dashboard in this lineage).

## 8. Session-close CronJob (new, k8s)

A `CronJob` (demo schedule, default hourly) in the k8s manifests posts to `/eod/session/close` with
a service-account admin JWT. Additive manifest; touches no existing workload.

## Not changed

Order/trade/position/risk payload shapes and subjects, matching policy, YU05's
settlement/recon/TCA/regulatory APIs, output-ring topology, the BLP hot path, UI journeys (no
front-end EOD panel in v1 — override is REST-only).
