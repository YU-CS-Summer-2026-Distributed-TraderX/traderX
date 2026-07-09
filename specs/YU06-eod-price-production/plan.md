# Implementation Plan: YU06 — EOD Price Production + Overnight Batch Chain

Parent: `YU05-post-trade-compliance`. Approach: build the smallest end-to-end vertical slice that
demonstrates the deck's gated overnight workflow — produce a versioned immutable closing-price
snapshot, gate it behind a durable event, and have one genuinely separate downstream job consume it
fail-safely — reusing existing infrastructure at every step rather than standing up new services.

## Slice 1 (this state) — build order (bottom-up)

1. **Schema** — add `eod_price_session`, `eod_price_snapshot`, `eod_position_pnl` to the k8s
   database-init ConfigMap (the real runtime schema; start from YU05's copy so `settlementdate`
   survives — see research.md gotcha).
2. **Producer (trade-processor)**:
   - `EodPriceSnapshotRepository` — versioned read/append over the two producer tables.
   - `EodQualityChecker` — `OK`/`STALE`/`SPIKE`/`MISSING` classification from config thresholds and
     the prior published close.
   - `EodPriceService` — `produce(sessionDate)` (read `PriceHistoryStore` newest sample per ticker →
     classify → write next `DRAFT` version), `override(sessionDate, security, price, reason)` (→ new
     version), `publish(sessionDate)` (fail-safe gate → status `PUBLISHED` → emit event).
   - `EodEventPublisher` — durable JetStream publish of `EOD_PRICES_READY` (reuse YU04 pattern).
   - `EodController` — `POST /eod/session/close`, `/eod/prices/{date}/override`,
     `/eod/prices/{date}/publish`, `GET /eod/prices/{date}` (latest version + quality report), all
     admin-JWT gated.
3. **Consumer (position-service)**:
   - NATS client dep + `PubSubConfig` (mirror trade-processor) + `eod.*` consumer config.
   - `EodPriceSnapshotReader` — read-only access to `eod_price_snapshot` for a `(date, version)`.
   - `EodPnlRepository` — idempotent upsert into `eod_position_pnl`.
   - `EodPnlConsumer` — durable subscriber on `eod.prices.ready`; per account: mark holdings vs. the
     named snapshot version, fail-safe halt on missing/flagged, write results, emit `eod.pnl.done`.
4. **Session trigger** — a k8s CronJob manifest hitting `POST /eod/session/close` on a demo-shortened
   schedule (hourly), plus the on-demand endpoint already built in step 2.
5. **Observability** — Micrometer counters/gauges (quality flags, sessions published, accounts
   marked/halted, per-stage timestamps for chain latency) + a `traderx-eod-batch-chain.json` Grafana
   dashboard.
6. **Tests** — producer quality classification + versioning + fail-safe gate; consumer marking +
   fail-safe halt + idempotent redelivery. Unit/integration in-tree (H2 or mocked snapshot), same
   discipline as YU05; full container smoke deferred to the isolated-staging pass.
7. **State packaging** — this spec pack, generation hook + render script, doc sync.

## Key decisions (see ADRs + research.md)

- Producer in trade-processor, consumer in position-service — reuse the price feed and positions
  where they already live; the only genuinely new infra is position-service's durable subscriber,
  which *is* the feature (ADR-028).
- Closing price = last trade price; versioned immutable snapshot as single source of truth
  (ADR-026).
- Lightweight JetStream event chain, durable, reusing YU04's publish pattern (ADR-027).
- Fail-safe halt-and-alert at both producer (won't publish flagged) and consumer (won't mark
  missing/flagged) ends (ADR-028).

## Explicitly not in this slice

- Overnight VaR/ES (teammate-owned; consumes this state's outputs).
- Closing auction price source (stretch; last-trade for v1).
- Front-end override panel (REST-only v1).
- Wiring YU05 NAV/recon onto `eod.pnl.done`.

## Validation strategy

In-tree unit + integration tests for the producer (classification, versioning, gate fail-safe) and
consumer (marking math, fail-safe halt, idempotent redelivery). Generation-propagation verified
empirically (regenerate → grep ancestor + YU06 markers in shared files). End-to-end container smoke
(close → prices-ready → marks → pnl-done against real MariaDB + JetStream) deferred to the
isolated-staging verification pass, same as YU03/YU05.
