# Runtime Topology: YU06

## Deployment topology

Unchanged services from `YU05-post-trade-compliance`: order-matcher BLP StatefulSet, trade-processor
Deployment, position-service Deployment — no new pods for the producer or consumer (they are code
additions to existing services). **One new k8s object:** a `CronJob` that posts to
`/eod/session/close` on a demo schedule. **One new JetStream stream:** `TRADERX_EOD` (file storage),
created on producer startup if absent (same as YU04's control-feed stream provisioning).

## New runtime behavior

- **trade-processor**: new admin endpoints `/eod/*`; reads the existing `PriceHistoryStore`; writes
  `eod_price_session`/`eod_price_snapshot`; publishes `EOD_PRICES_READY` to JetStream. New config:
  `eod.quality.*`, `eod.universe`, `eod.session.auto-publish`, `eod.stream`,
  `eod.subject.prices-ready`. No new outbound service-to-service HTTP dependency (unlike YU05's
  recon poll) — it only touches NATS + MariaDB it already uses.
- **position-service**: gains its first NATS dependency — a durable JetStream consumer
  (`eod.consumer.durable = eod-pnl`) on `eod.prices.ready`; reads `eod_price_snapshot`; writes
  `eod_position_pnl`; publishes `eod.pnl.done`. New config: `eod.stream`, `eod.subject.*`,
  `eod.consumer.durable`, plus the NATS connection URL (mirrors trade-processor's `PubSubConfig`).
- **CronJob**: authenticates with a service-account admin JWT (minted via the same
  `auth.dev-token` mechanism / a mounted secret) and calls `/eod/session/close` hourly by default
  (`eod.cron.schedule`).

## Startup / degraded behavior

| Condition | Effect |
|---|---|
| position-service down at publish time | Durable JetStream retains `EOD_PRICES_READY`; the consumer receives it on reconnect (redelivery) and marks the session late — the core durability guarantee (NFR-EOD02). |
| NATS/JetStream unreachable from trade-processor at publish | Publish fails (409-adjacent 5xx after commit-then-emit ordering); the version stays `PUBLISHED` in MariaDB but no event fires — operator re-runs `/publish` (idempotent no-op re-emits the event). |
| Held security missing/flagged in the snapshot | Consumer halts *that account's* marking, increments `eod_pnl_halted_total`, logs an alert; other accounts still mark (FR-EOD32). |
| Duplicate `EOD_PRICES_READY` delivery | No-op — `eod_position_pnl` upsert is idempotent on `(date, version, account, security)` (NFR-EOD05). |
| MariaDB unreachable | Producer/consumer fail their current operation (Spring datasource retry/backoff); no impact on order-matcher or the BLP. |
| Snapshot has unresolved flags | No event ever fires (producer fail-safe FR-EOD23); consumers simply never run for that session until an override + publish clears it. |

## Deferred

VaR/NAV chain stages; closing-auction pricing; front-end override panel.
