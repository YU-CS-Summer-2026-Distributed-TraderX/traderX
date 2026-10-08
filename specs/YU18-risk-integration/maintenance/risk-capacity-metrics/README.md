# Member risk-capacity metrics

Status: integrated locally, 2026-10-07; see the evidence and limits below.

The existing member `/metrics` endpoint reports open-order reservation and accumulated executed/booked gross notional using the engine's current accounting. It helps inspect capacity already consumed; it does not compute available credit, prices, risk-model results, or financial acceptance.

Implementation: the operative YU18 `generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/cluster/ClusterNodeMain.java`, its `riskCapacityMetrics` cold formatter, and `src/test/java/finos/traderx/ordermatcher/cluster/RiskCapacityMetricsTest.java`. The existing YU18 `risk/BlpRiskState.java` and `lmax/MatchingEngine.java` accessors supply values without modification. See [spec](spec.md), [plan](plan.md), [tasks](tasks.md), and the parent [quickstart](../../quickstart.md).

| Gauge | Labels | Meaning |
| --- | --- | --- |
| `traderx_risk_state_available` | member | 1 when this scrape can access an initialized risk instance, otherwise 0; not a readiness or restore-completion assertion |
| `traderx_risk_accounts` | member | Occupied engine account slots, including disabled accounts |
| `traderx_risk_reserved_notional_ticks` | member | Existing saturating aggregate open-order reservation |
| `traderx_risk_account_reserved_notional_ticks` | member, account | Open-order reservation for each occupied account, including zero after release |
| `traderx_risk_account_executed_notional_ticks` | member, account | Accumulated executed/booked gross notional for each occupied account |

All money values are the raw long accounting values, with 1,000,000 ticks per engine money unit. Order reservation is quantity × validation price ticks × contract multiplier (default 1); a fill releases its proportional reservation and accumulates quantity × actual execution price ticks × multiplier. Cancel/release clears only the remaining reservation. Executed exposure accumulates on either side and is not net position, a marked value, or money returned by cancellation. The credit gate reads executed plus reserved accounting; these gauges add no derived credit number.

The service values OTC booking notional using its sequenced USD-per-currency fixed-point FX rate before invoking the risk gate. USD is identity; a missing non-USD rate refuses the booking. That USD-valued booked notional appears in executed exposure, never reservation. The renderer applies no new conversion or currency inference to orders. It reports the engine convention rather than asserting that every mixed-instrument value is a financially validated USD valuation. See the existing `MatchingEngineClusteredService.onSwapBook` and `BlpRiskState.decideAndReserve`, `consume`, `release`, and `decideSwapBooking` paths.

A missing engine/risk instance emits only availability=0 for this component. No money or account samples are emitted then. Initialized empty state instead emits availability=1, accounts=0, aggregate=0. An occupied account has both account samples on every scrape, including zero and disabled accounts; unknown rejected account IDs never become labels. There is no label cache. A replaced state no longer emits its former accounts, so scrapers should honor ordinary missing-series staleness rather than forward-fill them. A scrape racing replacement may still finish sampling the prior captured risk instance.

Two account series per occupied backing account slot bounds current scrape cardinality. The account table rounds `2 * maxAccounts` to a power of two; this is the actual backing bound, not a promise that logical `maxAccounts` is independently enforced by these metrics. Membership turnover across resets can still grow a time-series store's historical cardinality. No client-controlled metric label or arbitrary query-account enumeration is added.

Scrapes read off-thread while apply can write. Account enumeration, each reservation read, and the saturating aggregate scan are separate observations with no atomic snapshot, barrier, freshness, or cross-member equality guarantee. The aggregate may saturate at `Long.MAX_VALUE`; even an idle arithmetic sum beyond that limit will differ from it. Prometheus numerical storage may round large integers. Do not use exact row-sum equality as a live proof.

Each member holds replicated accounting. Select a deliberate member/role and preserve member identity; do not sum replicas as independent venue capacity. This change prescribes no alert threshold or polling rate. Endpoint routing, authentication posture and existing metrics remain as before. Local HTTP/component tests and generated checks are accounting regressions, not retained-cluster, HA, throughput, deployment, or financial validation.

October 7 integration: combined source/generated checks passed in the coordinator candidate. The scoped implementation is integrated locally; original delivery notes retain their dates. Evidence is recorded in the shared coordination review directory `class-window-integration-20261007`. Live deployment, retained HA, performance and financial acceptance are not inferred from these checks.
