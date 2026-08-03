# Runtime Topology: YU06-eod-price-production

Parent state: `YU05-post-trade-compliance`

## Entrypoints

- `POST /eod/session/close`, `GET /eod/prices/{date}[/versions/{v}]`, `POST /eod/prices/{date}/override`,
  `POST /eod/prices/{date}/publish` — `trade-processor`, admin-JWT gated.
- `eod-session-close` CronJob (k8s) — calls `/eod/session/close` on a schedule, using the same
  admin-token mint + call path an operator uses manually.
- No new HTTP entrypoint on `position-service`; it is driven entirely by the `eod.prices.ready`
  JetStream subscription.

## Components

- Inherits the Kubernetes/C3/FDC3 runtime components already present in `YU05-post-trade-compliance`.
- `trade-processor` gains the EOD producer: quality classifier, versioned snapshot repository,
  durable event publisher, and the `/eod/*` controller — all reading from its existing
  `PriceHistoryStore` price feed and writing to its existing MariaDB datasource.
- `position-service` gains the EOD consumer: a durable JetStream subscriber, a read-only snapshot
  reader, and an idempotent P&L writer against its own MariaDB datasource.
- `NATS JetStream` gains one new stream, `TRADERX_EOD` (file storage), carrying `eod.prices.ready`
  and `eod.pnl.done`.
- MariaDB gains three tables (`eod_price_session`, `eod_price_snapshot`, `eod_position_pnl`) added
  to the shared runtime schema alongside every table prior states already defined there.

## Networking

- `trade-processor` and `position-service` each connect directly to the NATS broker; no new
  service-to-service HTTP calls are introduced between them.
- Publication order in `trade-processor` is always snapshot rows committed, then status flipped to
  published, then the event emitted — the event can never reference an uncommitted version.
- `position-service` never queries live prices; it reads only the exact `(session_date, version)`
  named in the event it received.

## Startup / Health Order

1. Generate and verify the inherited `YU05-post-trade-compliance` baseline assets.
2. Start MariaDB, NATS, and inherited support services as in the parent state.
3. Start `trade-processor` and `position-service`; both connect to NATS in a background thread and
   retry independently of application readiness, so neither blocks on broker availability at boot.
4. The `TRADERX_EOD` JetStream stream is created on first publish/subscribe if it does not already
   exist.
5. The `eod-session-close` CronJob becomes active on its configured schedule once the cluster is up.

## Degraded Behavior

| Condition | Effect |
|---|---|
| `position-service` unreachable/down at publish time | `EOD_PRICES_READY` is retained on the durable stream; the consumer receives it on reconnect and marks the session. |
| NATS unreachable from `trade-processor` at publish time | The snapshot version is committed as published in MariaDB but the event publish fails; re-running `/publish` is an idempotent retry that re-emits the (deduplicated) event. |
| A held security is missing or unresolved in the snapshot | The consumer halts that account's marking and increments an alert counter; other accounts in the same session are still marked. |
| Duplicate `EOD_PRICES_READY` delivery | The consumer's write is an idempotent upsert keyed by `(session_date, version, account_id, security)`. |
| MariaDB unreachable | The current producer or consumer operation fails its cycle via the existing datasource retry/backoff; no impact on order-matcher or the BLP. |
