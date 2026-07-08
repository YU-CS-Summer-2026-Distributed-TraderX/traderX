# Non-Functional Delta: YU05-post-trade-compliance over YU03-in-memory-risk-gateway

| Req | Status | Notes |
|---|---|---|
| NFR-PTC01 no hot-path impact | **Done** | Blotter capture happens on the existing output-ring consumer thread (already off the BLP's decision path, same as every other output handler); recon/settlement run entirely in trade-processor, no synchronous call into order-matcher's admission path. |
| NFR-PTC02 deterministic, idempotent reconciliation | **Done** | Classification is a pure function of (blotter state, DB state) at sweep time; rerunning against unchanged inputs reproduces the same result. |
| NFR-PTC03 bounded metric cardinality | **Done** | Reason/classification labels only; no trade-id/account/security labels. |
| NFR-PTC04 authenticated control-plane pattern reused | **Done** | New endpoints (`/recon/*`, `/trades/{id}/settlement/force`) reuse the token + operator header pattern from `/risk/control/*` until real OIDC (FR-PTC40) lands. |
| NFR-PTC05 bounded blotter memory | **Done** | `recon.blotter.capacity` (default 500,000) with oldest-first eviction; no unbounded growth. |
| NFR-PTC06 inherited build/publish/deploy intact | **Done** | State touches order-matcher + trade-processor overrides only; YU03 (→ YU02) runtime/deploy harness unchanged. |
| NFR-PTC07 no new dependencies | **Done** | No new libraries added (existing Spring `@Scheduled`, JDBC/JPA, HdrHistogram-adjacent metrics infra reused). |
| NFR-PTC08 recon/settlement never mutate journal/BLP | **Done** | Read-only against order-matcher; writes only to trade-processor's own MariaDB rows. |
