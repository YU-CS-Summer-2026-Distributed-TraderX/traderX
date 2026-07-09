# Architecture: YU06 — EOD Price Production + Overnight Batch Chain

Parent: `YU05-post-trade-compliance`. This state adds an overnight batch spine downstream of the
BLP. Like YU05 it never sits on the order-admission path and never mutates journal/BLP state
(NFR-EOD03) — the producer reads an existing NATS price feed, the consumer is a separate process.

## Data flow

```
  price-publisher ── pricing.* (last-trade prints) ──▶ trade-processor: PriceHistoryStore (YU05, reused)
                                                                   │  newest sample per ticker
   POST /eod/session/close (CronJob or operator, admin JWT)        ▼
                          └──────────────▶ EodPriceService.produce(sessionDate)
                                              │  classify: OK / STALE / SPIKE / MISSING
                                              ▼
                        MariaDB  eod_price_session (DRAFT, v)  +  eod_price_snapshot (immutable rows)
                                              │
              override (admin) ─▶ new version │  publish (admin, or auto if 0 flags)
                                              ▼  fail-safe: 409 if any unresolved flag
                        eod_price_session.status = PUBLISHED  ──▶ EodEventPublisher
                                                                     │  durable JetStream
                                                                     ▼  stream TRADERX_EOD
                                                            EOD_PRICES_READY {sessionDate, version}
                                                                     │
                                    ┌────────────────────────────────┘  (durable — late/restarted
                                    ▼                                     consumers still receive it)
                    position-service: EodPnlConsumer (durable sub)
                       reads ONLY eod_price_snapshot (sessionDate, version)  ── never live ticks
                       per account × holding: mark = qty × closing_price
                       fail-safe: held security MISSING/flagged ─▶ halt account, alert, no rows
                                    │
                                    ▼
                    MariaDB eod_position_pnl (immutable, per (date,version,account,security))
                                    │
                                    ▼  eod.pnl.done {sessionDate, version, accountsMarked, accountsHalted}
                             (next stage: VaR / NAV — subscribe later, out of scope)
```

## Components

| Component | Location | Role |
|---|---|---|
| `PriceHistoryStore` (reused, YU05) | trade-processor | Per-ticker `(price, timestampMillis)` samples; newest = closing price. Read-only. |
| `EodQualityChecker` (new) | trade-processor | `OK`/`STALE`/`SPIKE`/`MISSING` from `eod.quality.*` + prior published close. |
| `EodPriceSnapshotRepository` (new) | trade-processor | Versioned read/append over `eod_price_session` + `eod_price_snapshot`. |
| `EodPriceService` (new) | trade-processor | `produce` / `override` / `publish`; owns the fail-safe gate + versioning. |
| `EodEventPublisher` (new) | trade-processor | Durable JetStream publish of `EOD_PRICES_READY` (reuses YU04 pattern). |
| `EodController` (new) | trade-processor | `/eod/session/close`, `/eod/prices/{date}`, `/override`, `/publish` — admin JWT. |
| `PubSubConfig` (new) | position-service | NATS connection (mirrors trade-processor's). |
| `EodPriceSnapshotReader` (new) | position-service | Read-only access to `eod_price_snapshot` for a `(date, version)`. |
| `EodPnlRepository` (new) | position-service | Idempotent upsert into `eod_position_pnl`. |
| `EodPnlConsumer` (new) | position-service | Durable subscriber; marks positions fail-safely; emits `eod.pnl.done`. |
| `Position`/`PositionRepository` (reused) | position-service | Positions to mark. |

## Correctness boundary (unchanged invariant, extended)

The BLP decision path is untouched. The producer reads a NATS feed already consumed by YU05 and
writes its own MariaDB tables; the consumer is a separate process reading a versioned snapshot.
MariaDB remains a downstream projection. The consistency guarantee is structural: consumers read a
fixed `(session_date, version)`, so no two consumers can disagree on a closing price (NFR-EOD01).
Durability is structural too: the gate event is on a file-storage JetStream stream with a durable
consumer (NFR-EOD02).

## Deferred

Overnight VaR/ES (teammate-owned, consumes `EOD_PRICES_READY`); closing-auction price source
(stretch); front-end override panel (REST-only v1); wiring YU05 NAV/recon onto `eod.pnl.done`.
