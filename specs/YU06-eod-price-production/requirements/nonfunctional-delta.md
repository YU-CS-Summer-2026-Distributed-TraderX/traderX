# Non-Functional Delta: YU06-eod-price-production over YU05-post-trade-compliance

| Req | Status | Notes |
|---|---|---|
| NFR-EOD01 consistency: single source of truth | **Done** | Every consumer reads the same `(session_date, version)` snapshot; corrections create a new version, never an in-place edit (deck 02 s24). |
| NFR-EOD02 durability across restart | **Done** | Gate event on JetStream file-storage stream with a durable consumer — a batch job that boots after publish still receives it (redelivery). |
| NFR-EOD03 off the hot path | **Done** | No BLP/journal changes, no new output-ring event; producer reads the existing `PriceHistoryStore`, consumer is a separate process. Bench-compare not required (nothing on the order/tick path changed). |
| NFR-EOD04 bounded observability cardinality | **Done** | Chain metrics are aggregate counters/gauges; `eod_pnl_halted_total` is the only per-account-ish signal and is a bounded counter, not a per-account label series. |
| NFR-EOD05 idempotent at-least-once processing | **Done** | Consumer upserts `eod_position_pnl` by `(session_date, version, account_id, security)`; durable redelivery of the same event is a no-op. |
| NFR-EOD06 auth reuse, no new mechanism | **Done** | `/eod/*` uses YU05's `JwtAuthenticator`/`admin` claim; no new auth surface. |
| NFR-EOD07 orchestration altitude | **Done** | Lightweight JetStream event chain, no workflow engine (Airflow/Control-M explicitly rejected — ADR-027). |
| NFR-EOD08 generation-propagation safety | **Done** | Shared-file overrides (`application.properties`, `database-init-configmap.yaml`, `PubSubConfig`, `build.gradle`) started from the latest ancestor copy and verified empirically post-generation (ancestor + YU06 markers both present). See `generation/implementation-status.md`. |
