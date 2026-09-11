# Yaakov's itemized implementation backlog

Status: proposed additions to TraderX and the integration platform. Existing functionality is reused; a task labelled extend does not mean the underlying subsystem is absent. Each item names its earliest milestone and observable completion condition. Dependencies refer to [milestones](03-delivery-plan.md), [Alex's tasks](05-alex-backlog.md) and [joint decisions](07-baseline-and-decisions.md).

## Foundation and integration ownership

- [ ] **Y-F01 · M0 — Publish agreed exchange schemas.** Add versioned portfolio/event/market/request/result schemas, examples and compatibility policy. Done when both repositories validate the same package; coordinate A-F01 and D01.
- [ ] **Y-F02 · M0 — Define source identity and lineage.** Specify cluster epoch, run ID, consensus sequence, ordinals and order/trade/contract joins. Done when a reset cannot join a new trade to an old result; coordinate D02.
- [ ] **Y-F03 · M0 — Capture genuine integration fixtures.** Produce nonempty equity and OTC artifacts plus original booking lineage and a labelled market package. Done when hashes/counts and source commits are reproducible.
- [ ] **Y-F04 · M0 — Add a local contract-test environment.** Run the adapter, result store and clearly labelled test worker without cloud resources. Done when success, failure, duplicate and unsupported inputs can be exercised without claiming pricing correctness.
- [ ] **Y-F05 · M0 — Assign implementation layers.** Record owner branches and generated targets for each planned source change; audit ancestor/descendant overrides. Done before any production edit, with a chosen state-pack/branch strategy under D15.

## Committed events and portfolio projection

- [ ] **Y-E01 · M2 — Add a recoverable fill envelope.** Carry authoritative epoch, source sequence/ordinal, account, trade/order IDs, instrument, exact quantity/price and integration cursor. Done when gaps can be distinguished from intentionally filtered consensus events.
- [ ] **Y-E02 · M2 — Add live OTC booking events.** Publish contract terms and identity through the same recoverable boundary. Done when a booked swap appears in risk before EOD without converting it into a fungible position.
- [ ] **Y-E03 · M2 — Implement durable capture/recovery.** Choose and build D02's log/snapshot-tail or durable-egress design. Done when overflow, disconnect and restart recover missing committed economics without blocking consensus apply.
- [ ] **Y-E04 · M2 — Build the portfolio projector.** Maintain exact signed positions/cost bases and separate OTC contracts; deduplicate by full identity. Done when opposing trade sides, reversals and repeated events have correct accounting results.
- [ ] **Y-E05 · M2 — Add completeness checkpoints.** Expose complete-through source sequence, cursor, gaps and recovery progress; handle multi-record transitions. Done when no partial source transition is advertised as complete.
- [ ] **Y-E06 · M2 — Reconcile against EOD.** Load a cut at N, compare the projection at N and recover from a valid baseline plus tail. Done when an injected omitted fill causes a failure and subsequent repair restores equality.
- [ ] **Y-E07 · M2 — Track working-order exposure separately.** Project new/replace/cancel/fill and in-flight algo children independently from executed holdings. Done when risk and policy can account for potential fills without double-counting actual positions.

## Risk coordinator, jobs and results

- [ ] **Y-I01 · M0/M1 — Implement the EOD importer.** Validate position schema 3, contracts schema 2, preambles, counts, shared stamps and each distinct hash. Done when corrupt/mixed artifacts fail and empty versus missing files differ.
- [ ] **Y-I02 · M0/M1 — Build the JAX adapter.** Translate agreed data into supported engine requests and map output rows back to TraderX identities. Done with explicit unit conversion and capability errors; coordinate A-F02–A-F04.
- [ ] **Y-I03 · M1 — Add a durable job ledger.** Persist logical job IDs, attempts, immutable inputs, worker IDs, retries and terminal outcomes. Done when a process restart or repeated ready notification cannot lose or double-count a result.
- [ ] **Y-I04 · M2 — Add scheduling and backpressure.** Separate interactive, live-refresh and batch/research quotas; coalesce superseded refreshes and bound queues/retries. Done when overloaded analytics leaves OMS service healthy and queue age visible.
- [ ] **Y-I05 · M1/M2 — Store immutable results and version ordering.** Index by account, portfolio, source versions and run; retain historical results. Done when an old job finishing late cannot overwrite the latest eligible result.
- [ ] **Y-I06 · M2 — Compute result eligibility.** Track completeness, market/portfolio lag, age, coverage and precision/model profile separately from job success. Done when ineligible results remain viewable but cannot drive automated policy.
- [ ] **Y-I07 · M1 — Expose compact result APIs.** Add paginated trade/position results and artifact links, with authentication on job/control mutations. Done when the console does not need an entire scenario cube or direct worker access.

## Market data and historical inputs

- [ ] **Y-D01 · M0 — Build a dated market-package manifest.** Record observation time, availability time, source, units, quality, transformations and hashes. Done when Alex can load the same inputs offline; coordinate A-M01.
- [ ] **Y-D02 · M1 — Supply explicit test curves and observations.** Ship labelled synthetic/assumed inputs for initial financial parity fixtures. Done when the UI and results cannot confuse these with market-calibrated data.
- [ ] **Y-D03 · M3 — Inventory and normalize TAQ partitions.** Identify the actual trades/quotes products, symbols, timezones, conditions and correction records; create deterministic filtered partitions. Done with counts and data-quality reports, not an assumed 3-TB homogeneous format.
- [ ] **Y-D04 · M3/M5 — Add historical rates and FX inputs.** Extend beyond the current latest-FRED reader; select observations available at valuation time and preserve each point's date. Done without treating CMT yields as zero/SOFR rates; coordinate D05 and A-M02/A-M03.
- [ ] **Y-D05 · M3/M5 — Add reference actions and lifecycle observations.** Provide corporate actions, dividends, fixing histories and required calendar/reference versions for enabled products. Done when multi-day experiments distinguish unknown cashflows from zero cashflows.
- [ ] **Y-D06 · M5 — Package optional market inputs by capability.** Add vol quotes/assumptions, credit spreads and additional currency observations as product coverage expands. Done when unavailable/licence-constrained inputs cause explicit coverage restrictions; Alex owns calibration.
- [ ] **Y-D07 · M3 — Export aligned risk panels.** Generate returns/spot panels with reproducible sampling, missingness and point-in-time universe selection. Done when train/evaluation windows cannot leak future observations; coordinate A-H01/A-H02.

## Console and operator workflow

- [ ] **Y-U01 · M1 — Link risk to the original transaction.** Add order/trade/contract-to-result navigation with epoch/run context. Done when one real submission can be followed through extract and engine output.
- [ ] **Y-U02 · M1 — Extend EOD risk status.** Show queued/running/succeeded/partial/failed outcomes and exact cut/market provenance beside existing artifacts. Done when extraction success is not mistaken for pricing success.
- [ ] **Y-U03 · M2 — Build the live risk overview.** Show account/portfolio NPV, factor exposures, scenario losses and contributions with currency/units. Done when unsupported items and aggregation scope are visible.
- [ ] **Y-U04 · M2 — Add freshness and explanation.** Display portfolio sequence, market time, queue delay, approximate/full method and missing inputs. Done when a trader can distinguish a fill-driven risk move from a mark/calibration change.
- [ ] **Y-U05 · M3 — Add experiment comparison views.** Configure frozen/trading portfolios and compare run curves, executions, risk forecasts and costs. Done when every chart is tied to its manifest and execution assumptions.
- [ ] **Y-U06 · M4 — Add what-if and policy controls.** Show before/after risk, proposed hedges, shadow/active mode, caps and reasons. Done when server-authorized enable/disable and audit history work independently of browser state.
- [ ] **Y-U07 · M2/M6 — Add analytics operations status.** Show stream gaps, projection lag, queue depth/age, failures, worker device/precision and result age. Done with low-cardinality metrics and per-job trace links.

## Historical experiment runner

- [ ] **Y-R01 · M3 — Create immutable experiment manifests.** Pin corpus, starting state, model/market versions, policy, fees, seeds and clock rules. Done when a run can be reconstructed on another machine.
- [ ] **Y-R02 · M3 — Isolate replay state and clocks.** Coordinate existing seek/reset with OMS, portfolio, jobs and policy state. Done when pending work from an earlier run cannot affect a new run or a live account.
- [ ] **Y-R03 · M3 — Implement declared execution models.** Support available quote/trade information, partial fills, spread, latency, participation and chosen impact assumptions. Done with explicit limitations where queue/depth cannot be reconstructed.
- [ ] **Y-R04 · M3 — Add an economic cash/P&L ledger.** Track fills, fees, cash, realised/unrealised P&L and supported dividends/coupons/lifecycle flows. Done with conservation/reconciliation checks separate from existing clean-mark accounting.
- [ ] **Y-R05 · M3 — Compare Direct and TWAP fairly.** Freeze comparable order objectives, observations and stochastic inputs across isolated runs. Done with implementation shortfall, completion, exposure and inventory metrics.
- [ ] **Y-R06 · M3/M4 — Add policy experiment orchestration.** Run advisory/shadow/active variants under identical inputs, preserving decisions and actual fills. Done when policy benefit/cost is attributable rather than confounded by a different tape.
- [ ] **Y-R07 · M3 — Join forecast and realised outcomes.** Supply correctly timed frozen-portfolio P&L for Alex's forecast evaluation; separately report trading P&L. Done with matching horizon, holdings and no future calibration data.

## Risk-aware execution and hedging

- [ ] **Y-P01 · M4 — Implement versioned what-if requests.** Freeze base holdings, working orders and proposed orders with expected versions. Done when submission after state changes forces revalidation rather than reusing an old approval.
- [ ] **Y-P02 · M4 — Build the shadow policy controller.** Consume eligible risk/hedge analytics and record proposed actions without order side effects. Done with explainable rules and before/after estimates; coordinate A-X01–A-X03.
- [ ] **Y-P03 · M4 — Define bounded policy configuration.** Scope accounts/instruments, max quantity/notional/participation, loss/exposure thresholds and action frequency. Done with server authorization, version history and explicit enabled mode.
- [ ] **Y-P04 · M4 — Make action execution idempotent.** Track decision IDs, order acknowledgements, partial fills and in-flight exposure. Done when retry/restart/ambiguous ack cannot generate duplicate hedges.
- [ ] **Y-P05 · M4 — Extend algo execution controls.** Add supported pause/resume/rate/remaining-quantity changes and hedge-child handling to existing TWAP lifecycle. Done when parent totals and reserved risk reconcile after adjustment/cancel.
- [ ] **Y-P06 · M4 — Add stale/gap/concurrency defenses.** Recheck result eligibility and current exposure before acting, including concurrent orders. Done when stale results and incomplete projections cannot increase risk automatically.
- [ ] **Y-P07 · M4 — Add control-loop stability and disable behavior.** Implement hysteresis, cooldowns, minimum benefit after costs, action caps and kill/disable precedence. Done with rapid-price oscillation and repeated-result failure tests.
- [ ] **Y-P08 · M4/M5 — Sequence any hard-control changes correctly.** Quantize accepted external values and use owned deterministic control/state mechanisms with replay coverage. Done without remote lookups in apply; advisory analytics alone does not alter admission limits.

## Instrument and portfolio coverage

- [ ] **Y-C01 · M1/M5 — Publish explicit instrument semantics.** Extend terms/reference schemas for exact schedules, day counts, calendar rules, underlying IDs and currency. Done when Alex need not infer financial meaning from ticker spelling.
- [ ] **Y-C02 · M5 — Extend listed-option terms.** Represent exercise style, settlement/deliverables, adjusted multipliers and expiry semantics. Done when ordinary and adjusted contracts are distinguishable; coordinate A-P06.
- [ ] **Y-C03 · M5 — Extend rates and swaption terms.** Add agreed overnight conventions, option holder direction, settlement/premium semantics and explicit exercise schedules/windows where needed. Done with versioned booking/export compatibility; coordinate A-P02/A-P07/A-P09.
- [ ] **Y-C04 · M3/M5 — Add supported contract lifecycle events.** Represent fixings, cash payments, expiry/exercise, termination and maturity as needed by enabled products. Done when replay at a later date values the original remaining contract, not its original unaged terms.
- [ ] **Y-C05 · M5 — Separate model valuation from execution/mark accounting.** Store model NPV and risk alongside actual fills and source marks. Done with clean/dirty bond reconciliation and no overwriting historical accounting values.
- [ ] **Y-C06 · M5 — Expose supported cross-asset scope.** Join capabilities to tickets/risk views and maintain reporting-currency conversions. Done when a mixed book reports exclusions explicitly and shocks have compatible factor identities.

## Research interface and deployment

- [ ] **Y-B01 · M1/M6 — Export frozen research workloads.** Bundle portfolio/market/schedule versions, requested analytics, input hashes and lineage for offline use. Done when Alex runs a workload without a live OMS.
- [ ] **Y-B02 · M6 — Supply representative workload families.** Export small/large, homogeneous/heterogeneous, offsetting and boundary-sensitive books, labelled real-book versus synthetic scaling fixtures. Done with reproducible generators/manifests.
- [ ] **Y-B03 · M6 — Measure end-to-end platform latency.** Separate capture, projection, queue, transfer, compute and presentation timings. Done without comparing engine-only timing to full OMS-to-UI latency.
- [ ] **Y-B04 · M6 — Compare policy decisions across research results.** Join precision/runtime profiles to the same frozen and execution experiments. Done with action divergence, risk/cost effects and time-delay assumptions stated.
- [ ] **Y-O01 · M2 — Add private authenticated service boundaries.** Restrict job/policy mutations and worker access; isolate experiment and live identities. Done with authorization checks on the server, not browser gating alone.
- [ ] **Y-O02 · M2 — Implement durable storage and recovery operations.** Preserve manifests/jobs/results/checkpoints and a retention policy with restart procedures. Done when notification loss or worker loss cannot erase accounting provenance.
- [ ] **Y-O03 · M6 — Separate interactive and research capacity.** Configure queues, quotas, priorities and workload limits. Done when a benchmark flood cannot starve live risk or slow the matcher through resource contention.
- [ ] **Y-O04 · M6 — Add cloud deployment profiles.** Package coordinator/result services and integrate Alex's worker endpoints/artifacts using the same local contracts. Done with explicit owner/state manifests and measured cold-start behavior.
- [ ] **Y-O05 · M6 — Track costs and lifecycle.** Apply job/run labels, budget/quota controls and teardown/TTL procedures. Done with compute/storage/transfer costs attributable per experiment; pending credits are not assumed spendable capacity.
- [ ] **Y-O06 · M6 — Add bounded artifact export.** Store large arrays as chunked objects with hashes, schema and retention controls. Done when browser/API/control messages remain bounded as scenario count grows.

## Acceptance evidence

- [ ] **Y-V01 · M1 — Automate the genuine transaction proof.** Capture original submission, committed position/contract, extract, worker request and returned result. Done with V1/V2 evidence and no manual re-entry of trade economics.
- [ ] **Y-V02 · M2 — Automate reconciliation and failure tests.** Exercise dropped/duplicate events, stale jobs, restart and epoch reset. Done when V3 demonstrates both detection and repair, not just a happy-path equality.
- [ ] **Y-V03 · M3 — Automate replay reproducibility checks.** Validate manifest hashes, time boundaries, economic accounting and reset isolation. Done with V4 evidence and a future-data injection that is refused.
- [ ] **Y-V04 · M4 — Automate policy safety and outcome comparisons.** Exercise ambiguous acknowledgements, partial fills, overload, stale analytics, disable and oscillation. Done with V5 evidence before active-mode demos.
- [ ] **Y-V05 · M5/M6 — Publish integrated acceptance records.** Link Alex's financial/numerical results to platform runs, coverage, costs and failure behavior. Done when V6–V8 are reproducible from named inputs and source commits.
