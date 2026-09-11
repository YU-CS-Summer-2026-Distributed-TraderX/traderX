# Alex's proposed itemized implementation backlog

Status: proposed joint-project interface and analytics additions, not an assignment Alex has accepted. His primary project remains precision and distributed JAX research (M6). This list connects that work to realistic TraderX workloads. Existing pricers, Greeks functions, simulation, precision configuration and HTTP API should be extended rather than rebuilt.

## Intake, capability and API contracts

- [ ] **A-F01 · M0 — Pin and validate shared schemas.** Consume the agreed versioned contract package and reject unsupported schema/required terms. Done when the same positive/negative examples pass in both repositories; coordinate Y-F01.
- [ ] **A-F02 · M0/M1 — Preserve TraderX identities.** Carry opaque account/position/contract IDs and input hashes through requests/results; map legacy array indexes explicitly. Done when a per-trade result joins without relying on input order alone.
- [ ] **A-F03 · M0/M1 — Publish a capability matrix.** Report supported products, conventions, calculations, lifecycle cases and precision/device profiles. Done when unsupported positions have explicit reasons instead of disappearing from totals.
- [ ] **A-F04 · M0/M1 — Validate economics at ingestion.** Check units, multipliers, direction, explicit dates, curve roles and non-finite values. Done when a SOFR/ACT360 contract cannot silently become SimIndex/ACT365.
- [ ] **A-F05 · M0 — Return structured failures and warnings.** Distinguish bad terms, missing data, unsupported models, numerical failure and infrastructure failure. Done when the coordinator can retry only appropriate failures and display partial coverage.

## Instrument pricing and lifecycle

- [ ] **A-P01 · M1 — Add cash-equity and ETF valuation.** Price signed holdings with supplied spot, multiplier and reporting FX. Done with unit/currency tests and per-position NPV; an equity simulation factor alone is not this pricer.
- [ ] **A-P02 · M1 — Implement faithful USD-SOFR swap construction.** Accept explicit effective/maturity dates and agreed fixed/overnight-leg conventions, calendars and payment rules. Done with matching ORE cashflows/NPV and no generic IBOR substitution; coordinate D03/D04.
- [ ] **A-P03 · M1 — Expose per-contract base NPV.** Return instrument values and their sum, separately from scenario cubes. Done when the portfolio total reconciles with identified rows and correct account signs.
- [ ] **A-P04 · M1 — Wire swap Greeks through the portfolio/API path.** Supply the correct discount/forward curves to existing swap sensitivity functions. Done when `compute_greeks` returns identified swap sensitivities rather than skipping swaps.
- [ ] **A-P05 · M5 — Add fixed-rate Treasury pricing.** Price remaining coupons/principal and reconcile accrued interest/clean versus dirty value under agreed date conventions. Done with ORE parity, maturity/coupon boundary tests and curve risk.
- [ ] **A-P06 · M5 — Add listed-equity option pricing.** Support explicitly chosen European/American exercise, dividends, settlement and adjusted deliverables or refuse them. Done with price/Greek reference tests and no unsupported style treated as European.
- [ ] **A-P07 · M5 — Adapt European swaptions to actual booked terms.** Preserve exact underlying schedules, expiry, holder direction and settlement semantics. Done with parity for the agreed model and clearly labelled calibration/vol assumptions.
- [ ] **A-P08 · M5 — Add corporate-bond spread valuation.** Separate base-curve and credit-spread risk, state the chosen spread/default model and required inputs. Done with validated examples; a Treasury-discounted corporate is not full credit pricing.
- [ ] **A-P09 · M5 — Generalize early exercise and additional rate products.** Accept actual Bermudan exercise dates/American windows and additional currencies/index conventions as enabled. Done per capability profile; current early-exercise math does not imply all contract terms are represented.
- [ ] **A-P10 · M3/M5 — Fix aged-instrument valuation.** Preserve original schedules; consume known fixings and lifecycle state, exclude paid cashflows and handle expiry/exercise. Done with later-date ORE tests for every product permitted in a multi-day experiment.

## Curves, calibration and market inputs

- [ ] **A-M01 · M0 — Define the market-input contract.** Specify curve roles, spot/FX/factor ordering, observation versus derived data and convention requirements. Done with a labelled test market that Yaakov can package unchanged.
- [ ] **A-M02 · M5 — Build/validate Treasury zero-curve construction.** Transform accepted quotes into discount factors/zero rates using named conventions. Done by repricing calibration instruments; do not ingest interpolated FRED CMT yields as zero rates.
- [ ] **A-M03 · M1/M5 — Define SOFR discount/projection inputs.** Accept a labelled initial test curve, then calibrate from appropriate dated instruments. Done with an explicit single/multi-curve convention and source-to-curve diagnostics.
- [ ] **A-M04 · M5 — Add volatility calibration interfaces.** Consume option/swaption quote conventions and return calibrated parameters, fit errors and provenance. Done when assumed/static inputs remain distinguishable from observed implied-vol data.
- [ ] **A-M05 · M3/M5 — Define dependence estimation.** Own covariance/correlation/factor modelling, missingness, shrinkage and PSD handling on Yaakov's aligned observations. Done with no hidden cross-asset factors inferred from equity TAQ alone.
- [ ] **A-M06 · M5 — Support credit and multi-currency pricing inputs.** Define spread/default/recovery assumptions as applicable and synchronized FX/curves. Done when factor/risk aggregation respects currency and model scope.
- [ ] **A-M07 · M3/M5 — Version and cache calibrated inputs.** Key schedules/calibration by original terms, valuation context and market versions; invalidate on changes. Done with no reused future calibration or stale curve after replay seek.

## Sensitivities and portfolio aggregation

- [ ] **A-S01 · M1 — Specify sensitivity units and signs.** Define delta, gamma, vega, theta and rate-bump semantics, including multiplier/currency scaling. Done with examples and shared agreement under D08.
- [ ] **A-S02 · M1 — Validate AD against bump-and-reprice.** Compare swap rate sensitivities with an independent finite-difference/reference path across step sizes. Done with per-case absolute/relative error and tolerances, not a single aggregate assertion.
- [ ] **A-S03 · M2 — Aggregate compatible factors and currencies.** Produce account/portfolio NPV and risk-factor exposures plus constituent contributions. Done when totals reconcile and incompatible factor units are not added.
- [ ] **A-S04 · M2 — Add incremental/full calculation consistency.** Recompute affected positions/factors and compare with full portfolio recomputation. Done with cache invalidation and error-bound tests for approximations.
- [ ] **A-S05 · M4 — Price hypothetical portfolio changes.** Return before/after NPV/exposure and incremental risk for proposals plus working-order assumptions. Done without mutating the booked portfolio or granting execution permission.
- [ ] **A-S06 · M5 — Extend Greeks to supported optionality/lifecycle.** Connect valid vega/theta and early-exercise sensitivities through the API, distinguishing model-parameter from market-quote sensitivities. Done with exercise-boundary and cashflow-aware reference tests.

## Scenarios, VaR/ES and stress

- [ ] **A-R01 · M3 — Accept externally supplied scenario sets.** Reprice against immutable historical/stress scenarios with stable IDs, factor definitions and hashes. Done without requiring every experiment to regenerate the engine's sample paths.
- [ ] **A-R02 · M3 — Separate pricing and forecasting measures.** Label risk-neutral simulation, physical/historical forecasting and deterministic stress. Done with horizon and calibration assumptions that prevent a pricing distribution being advertised as an empirical forecast.
- [ ] **A-R03 · M3 — Calculate portfolio VaR/ES from joint P&L.** Aggregate instruments within each scenario before tail statistics; define losses, quantiles and horizon. Done with hand-checkable fixtures and no sum of standalone VaRs.
- [ ] **A-R04 · M3 — Return uncertainty and contribution diagnostics.** Include sample counts, convergence/stability and supported risk allocation methods. Done with sparse-tail and correlated/offsetting portfolio tests.
- [ ] **A-R05 · M3 — Implement forecast evaluation.** Compare predicted risk with correctly aligned frozen-portfolio realised P&L, using separate calibration/evaluation windows. Done with breach diagnostics and limited-sample caveats.
- [ ] **A-R06 · M5 — Add cross-asset stress valuation.** Apply consistent equity, curve, volatility, FX and spread shocks across supported products. Done with explainable P&L contributions, required-input checks and nonlinear repricing comparisons.

## Historical experiment analytics

- [ ] **A-H01 · M3 — Consume point-in-time market panels.** Honor data availability, session calendars, corrections and corporate-action treatment. Done when an injected future observation is rejected or excluded by a documented policy.
- [ ] **A-H02 · M3 — Implement rolling calibration windows.** Freeze transformations/models at each forecast origin and record fit diagnostics. Done when the test period cannot affect earlier forecasts through preprocessing or calibration.
- [ ] **A-H03 · M3 — Support the simulation clock.** Value as of the experiment time rather than host wall time; preserve contract identity. Done across overnight gaps, pause/resume and date boundaries.
- [ ] **A-H04 · M3 — Reconcile risk P&L with economic outcomes.** Specify which cashflows/fees/marks are included and explain differences from Yaakov's execution ledger. Done with separate frozen-portfolio and trading-portfolio reports.
- [ ] **A-H05 · M3 — Provide reusable historical risk evaluations.** Batch many dates/portfolios under the same input contract. Done with per-run coverage/errors and bounded outputs suitable for comparison charts.
- [ ] **A-H06 · M3/M4 — Evaluate execution-policy risk outcomes.** Return exposure time series, stress/drawdown diagnostics and uncertainty for matched Direct/TWAP/risk-aware runs. Done without claiming better outcomes solely from a lower model estimate.
- [ ] **A-H07 · M3 — Validate short-history limitations.** Report effective samples and sensitivity to sampling/window choices. Done with explicit limits on 99% daily-tail inference from the supplied March–July corpus.

## Hedge analytics and policy inputs

- [ ] **A-X01 · M4 — Define risk objectives and constraints mathematically.** Specify inventory/concentration/delta/curve targets and cost penalties. Done with units and infeasible-case behavior agreed with Yaakov.
- [ ] **A-X02 · M4 — Compute advisory hedge candidates.** Use an explicit permitted instrument universe and current exposures. Done with before/after risk, prices, quantities and rationale; no direct order-submission side effects.
- [ ] **A-X03 · M4 — Account for transaction costs and discrete sizing.** Include lot sizes, spread/fee estimates and exposure from pending fills. Done when small apparent risk gains do not automatically imply a worthwhile hedge.
- [ ] **A-X04 · M4/M5 — Extend to nonlinear and rates hedges.** Support option delta/Greek and curve-factor hedges when their pricers validate. Done with basis/residual-risk reporting rather than equal-notional cancellation assumptions.
- [ ] **A-X05 · M4 — Return actionable diagnostics.** Identify stale/missing inputs, approximation error, optimization failure or infeasibility. Done when the controller can refuse an ineligible recommendation without guessing.
- [ ] **A-X06 · M4/M6 — Measure decision sensitivity.** Quantify how prices, precision and delayed analytics change recommendations near thresholds. Done with a common scenario/workload baseline and stable tie-breaking where appropriate.

## Live analytics performance

- [ ] **A-L01 · M2 — Add a lightweight valuation/sensitivity entrypoint.** Support requested calculations without mandatory large Monte Carlo or full cube serialization. Done when small refresh jobs have an independently measurable cost.
- [ ] **A-L02 · M2 — Cache prepared schedules and compiled shapes.** Batch compatible products and invalidate on terms/date/model changes. Done with full-recompute comparisons and measured cold/warm behavior.
- [ ] **A-L03 · M2 — Support bounded batches and supersession.** Expose useful cancellation/progress boundaries and worker capacity. Done when large stale jobs do not monopolize interactive work indefinitely.
- [ ] **A-L04 · M2 — Report calculation method and timestamps.** Name full repricing versus approximation, error diagnostics and actual inputs. Done when the UI can show a fast estimate without implying it is a fresh exact result.
- [ ] **A-L05 · M2 — Make worker concurrency safe.** Preserve process isolation for ORE/global evaluation dates, precision settings and device assignment. Done with simultaneous different-date/precision jobs producing independent correct results.

## Precision and distributed-compute research — Alex's main lane

- [ ] **A-N01 · M0/M6 — Establish CPU FP64 reference profiles.** Pin ORE/JAX/dependencies, input conventions and tolerances. Done with actual dtype verification and saved numerical baselines for shared fixtures.
- [ ] **A-N02 · M6 — Expose and verify precision by stage.** Extend existing simulation/pricing/risk/calibration controls and report actual arithmetic, including matmul settings. Done when unsupported modes fail rather than silently downgrade/upcast.
- [ ] **A-N03 · M6 — Compare supported CPU/GPU/TPU profiles.** Record device, runtime and operation support; use matched inputs and precision where comparisons permit. Done with uncertainty and unsupported combinations visible, not presumed TPU FP64 equivalence.
- [ ] **A-N04 · M6 — Implement memory-bounded scenario scaling.** Chunk scenarios/trades/time, aggregate results and retain only requested diagnostic arrays. Done with peak-memory measurements and agreement with an unchunked reference.
- [ ] **A-N05 · M6 — Implement distributed execution where useful.** Profile first, then shard suitable independent work while accounting for CPU schedule/calibration stages and communication. Done with measured strong/weak scaling, not just multi-device allocation.
- [ ] **A-N06 · M6 — Separate numerical and sampling error.** Use fixed/common scenario sets and seeds where applicable, convergence runs and exercise/cancellation-sensitive portfolios. Done with price/Greek/VaR errors and confidence diagnostics per precision profile.
- [ ] **A-N07 · M6 — Publish cost/accuracy/runtime tradeoffs.** Compare cold/warm, transfer-inclusive and engine-only costs; measure whether errors alter hedge/limit decisions. Done with raw run records, source versions and reproducible plots/tables.

## Worker/API operations and research artifacts

- [ ] **A-O01 · M1 — Return compact identified results.** Add per-contract base values, requested Greeks and bounded summaries; retain array artifacts separately. Done without serializing every scenario/time/trade value into ordinary API responses.
- [ ] **A-O02 · M1 — Cooperate with durable coordinator retries.** Echo logical request IDs/hashes, expose worker attempt status and clear restart/unknown-job behavior. Done when Yaakov can recover a lost in-memory API job without treating it as a financial failure.
- [ ] **A-O03 · M2 — Publish health, capacity and timing metadata.** Expose worker/build/device and prepare/compile/compute/serialize timings. Done without claiming API-dispatcher device information proves the worker's actual device.
- [ ] **A-O04 · M6 — Package reproducible worker environments.** Supply pinned CPU and chosen accelerator environments plus one offline workload command. Done when a new machine reproduces a shared fixture without local sample-data assumptions.
- [ ] **A-O05 · M6 — Consume/export immutable research artifacts.** Read Yaakov's frozen bundles and write chunked outputs with shape/dtype/factor ordering, hashes and retention hints. Done when a result is auditable without the original worker process.
- [ ] **A-O06 · M6 — Add resource and failure controls.** Bound concurrency/memory, report OOM/numerical errors, handle interruption and preserve completed artifacts. Done with an interrupted batch that can resume through the coordinator without duplicating accepted results.

## Shared acceptance responsibility

Alex supplies financial and numerical evidence for V1/V2/V6/V7 and participates in V3–V5/V8 for incremental equality, replay timing, recommendation eligibility and worker recovery. Yaakov supplies platform/transaction evidence. Neither person's passing unit suite alone establishes the integrated result.
