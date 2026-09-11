# Verified baseline, staleness and joint decisions

Inspection date: 2026-09-11. TraderX base: `592bc3c66faccba04e12a9307bce36119aac67f2` on `YU17-otc-rates`. JAX public main: `2f69827494858120f379aba2ac63013c4936bee9` (2026-09-03). The new branch starts at exactly the TraderX base. No combined pricing run, cluster inspection or fresh performance proof was performed to write this plan.

Local links below resolve within this YU17-derived worktree. JAX links pin inspected source. Source inspection establishes code behavior/availability, not that a deployed service currently runs it or that a claimed model has been independently validated here.

## Existing foundations and observed gaps

| Area | Source-inspected baseline | Planning consequence |
|---|---|---|
| Position export | `RiskExtractCsv.SCHEMA = 3`; netted positions with marks and bond terms/accrual | Reuse the existing EOD artifact; do not invent a replacement position transport first |
| OTC export | `SwapContractCsv.SCHEMA = 2`; individual swaps/swaptions, terms only, stable-within-epoch contract IDs | Reuse for EOD; add full identity and missing semantics, plus live events |
| Ready announcement | Includes separate position/contracts hashes, shared cut stamp and URIs; uses `nats.publish` | Verify the right hashes and implement durable discovery/retry semantics |
| Live trade bridge | Nonblocking bounded queue can drop; publication is best effort; trade ID is sequence plus side; source order carries epoch | A subscription alone is not a complete recoverable portfolio feed |
| Live source watermark | Inspected trade envelope lacks an explicit consensus watermark/ordinal | Add integration cursor/completeness semantics; do not infer state-at-N from trade count |
| OTC conventions | Includes USD-SOFR ACT360 and other overnight currencies; frequency represents fixed leg | Require real contractual mapping, not just float-index-name substitution |
| Treasury market inputs | FRED reader contains 11 CMT points and linear yield interpolation; fetches latest observations and holds points in memory | Existing reference yield input is neither a zero bootstrap nor historical SOFR market packaging |
| Console | Trading, EOD, admin, replay, sandbox, corpus and operational surfaces exist | Extend current flows rather than rebuilding a separate trading app |
| JAX API | Async POST/GET portfolio pricing exists; jobs kept in in-process futures | Reuse behind a durable logical job coordinator; expose restart behavior |
| JAX product configs | Swap and swaption types exist; no cash-equity/bond instrument pricers found in inspected instrument tree | Add actual portfolio pricers; equity-factor simulation is not equity holdings valuation |
| JAX swap builder | Generic `SimIndex` IBOR, ACT365, TARGET and tenor-based MakeVanillaSwap | Does not faithfully accept current TraderX USD-SOFR/ACT360 explicit-date bookings |
| Swap Greeks | Direct swap sensitivity function exists, but portfolio Greek dispatcher skips `SwapConfig` | Wire explicit curves through API orchestration |
| Greeks/results | Per-trade Greeks keyed by input index; base NPV aggregate; full cube serialized to JSON | Add opaque IDs, per-item base NPV and bounded artifact outputs |
| Ageing | Swap source documents elapsed/already-fixed coupon limitation; TraderX OTC export states lifecycle not modelled | Multi-day risk requires lifecycle/fixing work on both sides |
| Swaption completeness | Export includes expiry/style but no explicit Bermudan exercise schedule or option holder long/short term | Style presence alone does not imply arbitrary swaption contracts are priceable |
| JAX precision | Config exposes simulation/pricing/risk/calibration 32/64 choices and process isolation machinery | Extend and measure these capabilities; do not start from an assumption that precision support is absent |
| End-to-end integration | No TraderX-specific ingestion implementation found in JAX tree or JAX result integration found in inspected console | M1 is still a needed proof, with M2–M6 defining the broader system |

## TraderX evidence

- [Position schema and renderer](../../specs/YU17-otc-rates/generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/cluster/RiskExtractCsv.java)
- [OTC schema, identity and lifecycle preamble](../../specs/YU17-otc-rates/generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/cluster/SwapContractCsv.java)
- [Extract orchestration and ready announcement](../../specs/YU17-otc-rates/generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/cluster/RiskExtractMain.java)
- [GCS artifact sink](../../specs/YU17-otc-rates/generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/cluster/RiskExtractGcsSink.java)
- [Trade bridge with bounded drop and best-effort publish paths](../../specs/YU12-aeron-cluster/generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/cluster/TradeNatsPublisher.java)
- [OTC convention table](../../specs/YU17-otc-rates/generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/lmax/SwapConventions.java)
- [FRED CMT reader and interpolation](../../specs/YU16-cdm-instruments/generation/runtime-overrides/price-publisher/src/fred-curve.js)
- [Current console pages](../../web-front-end-console/src/app/pages.ts)
- [Console types, controls and identity handling](../../web-front-end-console/src/app/api.ts)
- [Existing algo lifecycle service](../../specs/YU08-execution-algo-engine/generation/runtime-overrides/execution-algo-engine/src/main/java/finos/traderx/algoengine/service/AlgoOrderService.java)

The operative copy can live in an ancestor layer when no later override exists. For example, the inspected trade bridge is under YU12 and the FRED reader under YU16; their location does not mean their behavior is absent from YU17. Recheck composition before implementation.

## JAX evidence

- [Async API routes and in-memory job store](https://github.com/AlexNeugroschl/JAX_Risk_Engine/blob/2f69827494858120f379aba2ac63013c4936bee9/engine/api/routes.py)
- [Request/result schemas](https://github.com/AlexNeugroschl/JAX_Risk_Engine/blob/2f69827494858120f379aba2ac63013c4936bee9/engine/api/schemas.py)
- [Generic ORE swap builder](https://github.com/AlexNeugroschl/JAX_Risk_Engine/blob/2f69827494858120f379aba2ac63013c4936bee9/engine/models/ore_builders.py)
- [Swap configs, cashflow-grid requirement and ageing limitation](https://github.com/AlexNeugroschl/JAX_Risk_Engine/blob/2f69827494858120f379aba2ac63013c4936bee9/engine/instruments/swap.py)
- [Portfolio orchestration, precision config and skipped swap Greeks](https://github.com/AlexNeugroschl/JAX_Risk_Engine/blob/2f69827494858120f379aba2ac63013c4936bee9/engine/portfolio/request.py)
- [Sensitivity functions and their unit conventions](https://github.com/AlexNeugroschl/JAX_Risk_Engine/blob/2f69827494858120f379aba2ac63013c4936bee9/engine/risk/greeks.py)

## Stale assumptions to avoid

- The August [integration status](../engineering/risk-engine-integration-status.md) predates the current OTC and market-data work. Its claims that TraderX has no swaps or only synthetic prices are not the baseline for this plan.
- The [consumer guide](../engineering/risk-extract-consumer-guide.md) describes an older live artifact and dated deployment/proof commands. Use code and newly produced fixtures for current schema/runtime evidence.
- The [outbound handoff](../handoff/INTEGRATION-jax-risk-engine-2026-08-17.md) usefully identifies separate OTC artifacts, but its example JAX field mappings/market-data gaps must be rechecked against the pinned code.
- The [inbound handoff](../handoff/INTEGRATION-jax-risk-engine-inbound-2026-08-17.md) emphasizes replicated controls. Its suggestion that analytics themselves must always be bit-identically recomputable is stronger than necessary for an externally accepted sequenced value. Cluster members must replay identical accepted payloads; numerical research compares recalculations under declared tolerances and pinned inputs. A seed alone is not a cross-device bitwise guarantee.
- The [continuous-risk handoff](../../issues/open/HANDOFF-continuous-portfolio-risk.md) is useful design history, not proof of current feed completeness, cloud status or market-data provenance. Its own feed description must be checked against source drop paths.
- Alex's README says the live API is planned, but route/schema implementations exist. Conversely, existing formulas and passing tests reported in his docs do not establish integration with TraderX's exact product terms.

## Decisions to resolve together

All entries start **open**. Defaults are proposals enabling concrete planning; they are not implied agreement from Alex. Resolve the relevant decision before its dependent financial/control implementation, while continuing independent work.

| ID | Decision and proposed default | Owners | Required before |
|---|---|---|---|
| D01 | Contract package ownership/versioning. Publish shared schemas/examples from the integration branch; Alex pins a released hash/version. Define extension and unsupported-schema rules. | Both | M0 consumer implementation |
| D02 | Durable event source, epoch identity and recovery. Prefer authoritative snapshot/cut plus replayable tail or proven durable egress; define watermarks for filtered events and OTC bookings. | Yaakov lead; Alex consumes | M2 completeness claim |
| D03 | First exact USD-SOFR product. Agree fixed/overnight schedules, currency, notional, direction, payment lag and compounding conventions; refuse generic-IBOR substitution. | Both; Alex validates | M1 swap proof |
| D04 | Calendar, stubs, valuation/settlement dates and fixings. Preserve explicit booked dates and remaining lifecycle; use a documented supported convention subset initially. | Both | Product/pricing schema freeze |
| D05 | Pricing market inputs and curve ownership. Yaakov supplies dated observations; Alex owns validated curve construction/role mapping. Label initial assumed curves. | Both | M1 reference; M5 market-derived claim |
| D06 | Initial supported universe. Cash equities plus one faithful USD-SOFR product; expand options/bonds/swaptions by explicit capability profiles. | Both | M1 scope/fixtures |
| D07 | Historical execution model. Inventory actual TAQ products; choose and label queue/liquidity/impact assumptions rather than implying observed fills. | Yaakov lead; Alex evaluation | M3 execution comparison |
| D08 | Risk units, signs, P&L and aggregation. Use explicit factor/currency/scaling and +1bp P&L semantics; agree VaR measure/horizon and cashflow treatment. | Alex lead; Yaakov UI/contracts | M1 displayed numbers |
| D09 | Freshness/cadence and incomplete-data policy. Configure separate live refresh/full-risk budgets; suppress automatic risk-increasing actions on ineligible results. | Both | M2/M4 |
| D10 | Policy scope and concurrency. Begin advisory/shadow, then sandbox-only bounded actions; define working-order reservations, retries, disable and hysteresis. | Yaakov lead; Alex objectives | M4 active mode |
| D11 | Historical availability and universe policy. Choose timezones, releases/revisions, corporate actions and train/evaluation splits; ban future-information leakage. | Both | M3 results |
| D12 | Reference tolerances and precision profiles. Agree per-product absolute/relative/Greek error, actual arithmetic and Monte Carlo uncertainty reporting. | Alex lead; Yaakov acceptance | V2/V6/V7 |
| D13 | Worker protocol and job ownership. Coordinator owns durable logical jobs; Alex's API/worker owns computation attempts, capacity and artifact output. | Both | M1 retry semantics |
| D14 | Cloud budget, quota and experiment sizes. Verify credits/quotas, run small cost measurements and cap research workloads before scaling. | Alex research; Yaakov platform | M6 cloud runs |
| D15 | Persistent source ownership. Assign generation layers or a new state pack before production edits; planning branch name alone is not a generation state. | Yaakov | Implementation changes |
| D16 | Lifecycle and option semantics. Agree supported exercise/holder/settlement/premium/deliverable cases and who emits versus consumes each event. | Both | M3 multi-day scope; M5 options |
| D17 | Integration language/storage packaging. Prefer a small coordinator and durable job/result store compatible with the current stack; choose concrete libraries after interface fixtures. | Yaakov; Alex API compatibility | M1 service implementation |

## Known risks and mitigation ownership

| Risk | Response |
|---|---|
| Live risk quietly misses a fill/booking | Y-E01–Y-E06: authoritative recovery, completeness and exact reconciliation |
| A priced contract differs from the booked contract | Y-C01–Y-C03 and A-P02/A-P07: compare terms/schedules before prices |
| Historical or superseded results trigger live actions | Y-R02, Y-I05/Y-I06, Y-P06: run isolation, version ordering and eligibility |
| Precision speedups change financial decisions | A-N01–A-N07 and Y-B04: reference/error bounds plus decision comparisons |
| Research jobs consume live capacity or credits unexpectedly | Y-O03/Y-O05, A-O06: quotas, resource isolation, measured costs and teardown |
| Cross-asset coverage appears complete despite missing data/models | A-F03/A-F05 and Y-C06: explicit capability/coverage and action eligibility |

When implementation starts, add dated evidence entries naming the task IDs, source commits, input hashes and V-gates actually passed. Keep unknowns visible; do not copy benchmark numbers or deployment assumptions from older documents.
