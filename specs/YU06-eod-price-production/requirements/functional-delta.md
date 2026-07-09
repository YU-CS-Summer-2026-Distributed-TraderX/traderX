# Functional Delta: YU06-eod-price-production over YU05-post-trade-compliance

New requirement namespace `EOD`.

| Req | Status | Notes |
|---|---|---|
| FR-EOD01 session-close trigger | **Done** | `POST /eod/session/close?sessionDate=` (trade-processor, admin JWT). One code path; a k8s CronJob calls it on a demo-shortened schedule, operators call it on demand. |
| FR-EOD02 closing price = last trade price | **Done** | Per instrument, the newest `PriceHistoryStore` sample at/before close (the existing `pricing.*` feed carries last-trade prints — no new data source, no BLP involvement). |
| FR-EOD03 idempotent production per session | **Done** | Re-running `close`/`produce` for a `sessionDate` writes a new `DRAFT` version; never mutates a `PUBLISHED` one. |
| FR-EOD04 production never touches the BLP/journal hot path | **Done** | Entirely trade-processor read-side (reads `PriceHistoryStore`, writes MariaDB) — same invariant as YU05's recon/TCA. |
| FR-EOD05 production authenticated | **Done** | All `/eod/*` endpoints require an `admin` JWT (reuses YU05 `JwtAuthenticator`). |
| FR-EOD06 expected-universe / missing detection | **Done** | `eod.universe` config lists expected instruments; one with no sample is `MISSING` and blocks publication until overridden. Default universe = every ticker seen this session. |
| FR-EOD10 staleness check | **Done** | Newest sample older than `eod.quality.staleness-seconds` before close → `STALE`. |
| FR-EOD11 spike check | **Done** | `|close − priorPublishedClose| / priorPublishedClose > eod.quality.max-move-pct` → `SPIKE`. |
| FR-EOD12 manual override (REST) | **Done** | `POST /eod/prices/{date}/override` `{security, price, reason}` (admin) → new version with the instrument `OVERRIDDEN`. Snapshot stays immutable. |
| FR-EOD13 override auditable | **Done** | `override_reason` persisted on the overridden snapshot row; version history preserves every prior value. |
| FR-EOD20 versioned immutable snapshot | **Done** | `eod_price_session` + `eod_price_snapshot`, keyed by `(session_date, version[, security])`; published rows never updated. |
| FR-EOD21 durable gate event | **Done** | `EOD_PRICES_READY` on JetStream stream `TRADERX_EOD` (file storage), subject `eod.prices.ready`, carrying `(sessionDate, version)`. Reuses YU04's durable-publish pattern; late/restarted consumers still receive it. |
| FR-EOD22 event emitted only after commit | **Done** | Publish sequence: write rows → status `PUBLISHED` → emit event. The event can never reference an uncommitted version. |
| FR-EOD23 producer-side fail-safe | **Done** | `POST /eod/prices/{date}/publish` returns **409** if any instrument is unresolved `STALE`/`SPIKE`/`MISSING`; no event emitted. All-clean close auto-publishes (`eod.session.auto-publish`). |
| FR-EOD30 downstream consumer gated by the event | **Done** | position-service durable JetStream consumer on `eod.prices.ready` — the first real overnight job. |
| FR-EOD31 consumer reads only the versioned snapshot | **Done** | Marks positions against the exact `(session_date, version)` the event names — never live ticks (the deck's consistency invariant). |
| FR-EOD32 consumer-side fail-safe | **Done** | A held security missing/flagged in the snapshot halts *that account's* marking, increments `eod_pnl_halted_total`, and logs an alert; the account gets no `eod_position_pnl` rows. |
| FR-EOD33 chain link emitted | **Done** | On completion the consumer emits `eod.pnl.done` `(sessionDate, version, accountsMarked, accountsHalted)` — the next link (VaR/NAV subscribe later, out of scope here). |
| FR-EOD40 batch-chain observability | **Done** | Micrometer counters/gauges (quality flags, sessions published, accounts marked/halted, per-stage timestamps) + `traderx-eod-batch-chain.json` Grafana dashboard showing end-to-end chain latency. |
