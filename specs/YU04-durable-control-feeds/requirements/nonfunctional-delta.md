# Non-Functional Delta: YU04-durable-control-feeds over YU03-in-memory-risk-gateway

| Req | YU03 status | YU04 status | Notes |
|---|---|---|---|
| NFR-IMRG04 single-writer discipline | Done | **Done (unchanged)** | The new `ControlFeedSubscriber`/outbox publisher machinery runs off the BLP thread entirely (background JVM threads in order-matcher; separate scheduled tasks in account-service/reference-data) — no lock/atomic/clock-read/randomness is added to the BLP decision path itself. |
| NFR-IMRG05 recovery target | Done (mechanism) | **Done (unchanged)** | BLP recovery still restores from snapshot v3 + journal tail only; the Gateway's durable-feed catchup runs independently and does not gate BLP recovery time. |
| NFR-IMRG06 admission readiness gating | Done | **Done (extended)** | Readiness now depends on both sources reaching their observed high watermark, not just seed-image + SymbolTable alignment. |
| NFR-IMRG07 staleness detection bounds | Partial | **Done** | Control-feed staleness is now bounded: a source that stops publishing (no traffic AND no periodic heartbeat delta within `risk.feed.max-silence-ms`) is treated as suspect and logged; a genuine gap/regression/epoch-change quarantines outright (FR-IMRG34). |
| NFR-IMRG08 observability retained + extended | Partial | **Partial → extended** | New bounded-cardinality metrics: `traderx_replica_source_watermark{source}`, `traderx_replica_quarantine_total{source,reason}`, `traderx_outbox_publish_lag_seconds{source}` (account-service/reference-data), `traderx_outbox_unpublished_rows{source}`. Grafana dashboard/alert wiring for these is a follow-up task (`tasks.md` T-14), same reasoning as YU03's deferred alert thresholds — not part of this state. |
| NFR-IMRG09 authenticated control transport | Partial | **Partial (unchanged)** | Outbox publishers connect to the same in-cluster NATS broker order-matcher already trusts (no new external trust boundary); TLS/OIDC on the control-plane admin API remains deferred to the auth roadmap item, same as YU03. |
| NFR-IMRG10 bounded metric cardinality | Done | **Done (unchanged)** | New metrics above are labeled only by `source` (`account`/`security`) and `reason` (quarantine cause) — no account/security/principal labels. |
| NFR-IMRG11 inherited build/publish/deploy intact | Done | **Done (unchanged)** | order-matcher's YU02 harness is untouched; account-service/reference-data's existing build/deploy paths gain new source files and one new dependency each (JetStream client), no removal or restructuring of what's there. |
| NFR-IMRG12 new dependencies | Done (none added) | **New dependencies added (justified)** | `account-service`: `io.nats:jnats` (same client + version order-matcher already depends on — see `research.md`). `reference-data`: the NATS JetStream Node client (`nats` npm package). Both are the minimum needed to publish to JetStream; no CDC/Kafka-Connect/Debezium infra added (ADR-021). |

## New for this state

| Req | Status | Notes |
|---|---|---|
| NFR-IMRG-OUTBOX-01 outbox atomicity | **Done** | Each control-relevant business write and its outbox row insert happen in the same local database transaction (account-service: JPA `@Transactional`; reference-data: same-transaction insert via its ORM) — no dual-write window where one succeeds and the other doesn't. |
| NFR-IMRG-OUTBOX-02 publish idempotency | **Done** | Each outbox row's JetStream publish carries a `Nats-Msg-Id` equal to `"<source>:<version>"`; JetStream's built-in duplicate-window dedup makes a publisher-crash-and-retry safe without double-delivery. Consumer-side apply is also idempotent (version must be strictly greater than the last applied version for that source). |
| NFR-IMRG-OUTBOX-03 bootstrap buffer bound | **Done** | `ControlFeedSubscriber`'s pre-snapshot delta buffer is capacity-bounded (`risk.bootstrap.buffer-capacity`, default matches `risk.max-accounts` + `blp.books.max-securities`); overflow quarantines and forces a fresh bootstrap attempt rather than growing unbounded (consistent with FR-IMRG26's bounded-capacity requirement, extended to this state's new buffer). |
