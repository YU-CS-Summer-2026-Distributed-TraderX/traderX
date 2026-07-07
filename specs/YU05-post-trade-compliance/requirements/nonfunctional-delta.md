# Non-Functional Delta: YU05-post-trade-compliance over YU03-in-memory-risk-gateway

| Req | Status | Notes |
|---|---|---|
| NFR-PTC01 no hot-path impact | **Done** | Blotter capture happens on the existing output-ring consumer thread (already off the BLP's decision path, same as every other output handler); recon/settlement run entirely in trade-processor, no synchronous call into order-matcher's admission path. |
| NFR-PTC02 deterministic, idempotent reconciliation | **Done** | Classification is a pure function of (blotter state, DB state) at sweep time; rerunning against unchanged inputs reproduces the same result. |
| NFR-PTC03 bounded metric cardinality | **Done** | Reason/classification labels only; no trade-id/account/security labels. |
| NFR-PTC04 authenticated control-plane pattern | **Done, upgraded mid-state** | New endpoints (`/recon/*`, `/regulatory/report`, `/trades/{id}/settlement/force`, `/tca/report/{tradeId}`) started on the token+operator header pattern from `/risk/control/*`, then were upgraded to real JWT + entitlement gating (ADR-025) once FR-PTC40/41 landed — `/risk/control/*` (YU03) itself is unchanged, out of scope. |
| NFR-PTC05 bounded blotter memory | **Done** | `recon.blotter.capacity` (default 500,000) with oldest-first eviction; no unbounded growth. The full-history index (FR-PTC10) is deliberately unbounded but only ever populated on-demand, never automatically. |
| NFR-PTC06 inherited build/publish/deploy intact | **Done** | State touches order-matcher + trade-processor overrides only; YU03 (→ YU02) runtime/deploy harness unchanged. |
| NFR-PTC07 no new dependencies | **Done** | No new libraries added — JDK `javax.crypto`/`HttpClient`, existing Jackson, existing Micrometer (`spring-boot-starter-actuator` + `micrometer-registry-prometheus`, already present), Spring `@Scheduled`/JDBC/JPA all reused. |
| NFR-PTC08 recon/settlement never mutate journal/BLP | **Done** | Read-only against order-matcher (including the full-history reindex and regulatory report, both shadow-engine replays that never touch the live BLP); writes only to trade-processor's own MariaDB rows. |
| NFR-PTC09 order admission path untouched | **Done** | No file under this state's scope modifies order submission/admission (`OrderMatcherService`, `GatewayReplicaStore`, `BlpRiskState`) — verified by review, not just by intent; this is why FR-PTC42 (principalKey wiring) is explicitly deferred rather than half-done. |
