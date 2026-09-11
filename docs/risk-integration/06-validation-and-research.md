# Validation, experiments and evidence

Status: acceptance design. No result below is a claim of a completed runtime test. The documentation checks for this planning change are separate from these future system proofs.

## Acceptance gates

| Gate | Milestones | Required evidence | Lead |
|---|---|---|---|
| V1: contract compatibility | M0/M1 | Shared fixtures accepted; corrupt/mixed/unsupported inputs refused; identity/units round-trip | Both |
| V2: real transaction and financial reference | M1 | Original order/booking to exported economics to same-contract pricing and sensitivities | Yaakov platform; Alex mathematics |
| V3: live-state completeness | M2 | Portfolio at N matches authoritative cut; loss/restart/reset detected and repaired | Yaakov; Alex full/incremental comparison |
| V4: historical experiment correctness | M3 | Point-in-time inputs, reproducible state, explicit fills/cash/P&L and no leakage | Both |
| V5: policy behavior and bounded execution | M4 | What-if, shadow/active transitions, idempotency, stale/concurrent-state handling and outcome comparison | Yaakov; Alex recommendation checks |
| V6: product coverage | M5 | Same-terms reference cases, lifecycle and financial conventions for each enabled product | Alex; Yaakov booking/export fidelity |
| V7: numerical/compute research | M6 | Reproducible accuracy, runtime, memory and cost profiles with actual arithmetic recorded | Alex |
| V8: operational isolation/recovery | M2/M6 | Worker/queue/storage failures do not corrupt books or enable invalid actions; durable job recovery | Both |

## V1 — Contracts and identity

Test schema 3 positions and schema 2 contracts, comment preambles, counts, separate hashes and shared stamps. Cover empty files, missing companions, unknown convention indexes, wrong units, unsupported option terms and a changed reference-data version. Round-trip large sequences without JSON integer truncation. Reorder request rows and verify opaque IDs still join results correctly.

Submit the same logical request twice; accept one logical result with traceable attempts. Change one byte of market data without changing its immutable ID; require an error. Replay an old epoch's event after a reset; it must not affect the new portfolio. Unsupported products remain in coverage accounting.

## V2 — The professor's transaction chain

For the equity case, capture the original order, committed fill, position, extract row, engine request and identified result. For the OTC case, capture the original booking, committed contract ID, terms artifact, request and result. A rejected/unfilled order must not masquerade as an executed position. A manually retyped sample trade does not pass this gate.

Compare NPV to an ORE/reference implementation using exactly the same dates, conventions, curves and units. Check quantity/notional scaling, payer/receiver sign, multiplier application and independent +1bp revaluation versus the returned rate sensitivity. Check zero-position and missing-curve cases. When using assumed curves/vols, assert the label survives to the UI.

Numerical tolerances are case-specific artifacts agreed under D12: absolute currency error, relative error away from zero, and sensitivity error in the published units. Near-zero values require an absolute tolerance; percentage-only error is misleading. Do not choose tolerances after seeing a failing number merely to pass it.

## V3 — Live desk and reconciliation

Use a nonempty book with at least one partial fill, a working-order cancel/replace and an OTC contract. Inject duplicate records, a dropped record, delayed delivery, queue overflow, a consumer restart and a fresh epoch. Recover from authoritative state/history and compare quantity, accounting basis and contract terms at the exact source boundary.

Verify completeness watermarks across multi-record events and filtered streams. Ordinary gaps in consensus numbers are not evidence of lost trades; missing integration records must be distinguishable. Test EOD baseline N plus tail >N without duplication.

Submit two risk jobs on different portfolio/market versions and force the older one to finish last. History retains both, but latest-result selection and action eligibility must select correctly. Compare incremental and full calculations on identical frozen inputs. Test unsupported product, stale market, incomplete projection and numerical failure independently.

## V4 — Historical laboratory

Freeze a small corpus, starting positions/cash, order program, execution model and clock rules. Repeat the run; compare deterministic event/accounting outputs exactly and numerical outputs under the pinned profile/tolerance. Inject an observation or model fitted after the simulated decision time; exclude/refuse it and record the reason.

Cover market close, overnight gap, holiday/session conventions, split/dividend where supported, and seek/reset while jobs or hedges are pending. Include expired instruments and a contract whose first fixing is now in the past. Refuse unsupported lifecycle cases rather than valuing an unaged contract silently.

Report two distinct result families:

| Experiment | Fixed inputs | Outcomes |
|---|---|---|
| Forecast evaluation | Frozen holdings; information available at forecast origin | Predicted versus realised horizon P&L, VaR breaches, tail diagnostics, effective sample size |
| Execution/policy comparison | Starting book; order objective; observations; fill/latency/fee assumptions | Fill/completion rate, implementation shortfall, spread/fees, inventory exposure, drawdown, economic P&L |

Mark-based revaluation may differ from executable liquidation P&L. A hypothetical fill model must state queue/liquidity assumptions. Use sensitivity experiments for latency, spread, participation and impact; avoid a universal claim that a policy improves execution based on one convenient configuration.

## V5 — What-if, risk-aware execution and hedging

Begin with a shadow policy and show its decisions without order side effects. In an isolated sandbox, explicitly enable a scoped policy and test bounded order changes/hedges. Prove actual fills feed the next decision; planned hedges are not treated as already filled.

Required negative cases: a stale result, portfolio mutation after what-if, concurrent parent orders, in-flight partial hedge, duplicate result, ambiguous order acknowledgement, restart after submission, account disable, policy disable, breached quantity/participation caps, missing FX/vol, gap in portfolio state and numerical failure.

Test hysteresis/cooldown under oscillating inputs and verify bounded action rates. A hedge must pass ordinary admission checks. An action that reduces one modelled factor can increase another, so policy scope and residual risk must remain explicit.

Compare the policy with Direct/TWAP on matched historical runs. Report both risk reduction and its execution/fee cost. Show what happens when risk computation is delayed; accelerated analytics has practical value only if the system can use the earlier result correctly.

## V6 — Cross-asset acceptance matrix

| Product | Minimum targeted cases |
|---|---|
| Equity/ETF | Long/short, scale, FX conversion, stable security identity, action-adjusted return/quantity |
| Treasury | Bill and coupon bond, fraction-of-par, face, accrued interest, clean/dirty, coupon date and maturity |
| Corporate bond | Treasury-curve component plus stated spread model, spread shock, maturity and missing-credit-input refusal |
| Listed option | Put/call, chosen exercise style, multiplier, dividend treatment, expiry/near-strike cases and adjusted-contract refusal/support |
| SOFR swap | Pay/receive, explicit irregular dates/stubs if supported, ACT360, fixed/overnight schedules, payment lag, fixings and aged state |
| European swaption | Underlying identity, expiry, settlement, holder direction, volatility/calibration source, near-expiry behavior |
| Early-exercise swaption | Actual exercise dates/window, exercise boundary, underlying lifecycle and consistent pricing/Greek behavior |

Compare input-derived schedules/cashflows before comparing prices. Agreement between two pricers using the same wrong schedule does not establish faithful booking integration. Separate static plumbing, numerical parity and market-model adequacy in the acceptance record.

For mixed portfolios, use synchronized scenario IDs and common reporting currency. Reconcile sum of component scenario P&L with portfolio scenario P&L. Assert portfolio VaR is calculated from that distribution rather than summed stand-alone risk measures.

## V7 — Alex's precision and compute research

Start with immutable small reference portfolios, then scale along independent axes:

| Axis | Proposed experiments, not existing capabilities |
|---|---|
| Portfolio size | Tens, hundreds, thousands and larger synthetic copies of identified instruments |
| Scenario count | Increase until error targets or memory/runtime budgets bind |
| Time grid | Single valuation, short horizon and lifecycle-aware multi-date exposure |
| Instrument mix | Linear equity, curve-sensitive swaps/bonds, nonlinear options, early exercise |
| Precision | CPU FP64 reference; supported FP32/mixed-stage profiles; actual accelerator arithmetic |
| Device layout | One CPU/GPU/TPU configuration, then measured multi-device/host scaling where justified |
| Numerical stress | Offset/cancellation, near-zero NPV, exercise boundaries, long maturity and calibration difficulty |

Distinguish storage dtype from arithmetic used inside operations. `jax_enable_x64` is not proof of a fast/native FP64 TPU path; verify the selected device/kernel behavior. Compare supported matched profiles and report unsupported combinations rather than presenting them as measured zero/error-free cases. JAX documents both [distributed execution](https://docs.jax.dev/en/latest/parallel.html) and [matrix arithmetic precision](https://docs.jax.dev/en/latest/201/precision.html).

For Monte Carlo, use common scenario inputs or controlled random keys when comparing numerical profiles. Pin PRNG algorithm, seed/scenario artifacts and partitioning policy; the same integer seed alone does not guarantee identical scenarios after changing sharding or random-key splitting. Report Monte Carlo sampling uncertainty separately from precision error.

Synchronize device work before stopping timers. Separate preparation/calibration, JIT compile, warmed execution, transfer/serialization and full coordinator-to-result latency. Repeat enough times to report dispersion and record which caches were warm. Compare cost per useful accepted result, not only raw scenario throughput.

Chunk/reduce large arrays: 10,000 trades times 100,000 scenarios yields one billion values, about 8 GB of FP64 output at just one time point before intermediates. This is an illustrative sizing calculation, not a proposed mandatory allocation. Avoid full browser JSON cubes and unnecessary all-gathers. Record peak memory and communication as well as compute time.

The financial research output should answer: which precision profiles preserve price/Greek tolerances, which change hedge/limit decisions, and what latency/cost benefit they deliver. Yaakov can replay the decision stream with measured delays to distinguish numerical effects from speed effects.

## V8 — Operational isolation

Stop a worker, lose an in-memory worker job, interrupt a batch and make artifact storage unavailable. Require durable request identity, bounded retries, explicit failure/unknown state and no accepted partial output disguised as complete. Test a missed EOD-ready notification and discover/reprocess the immutable artifact.

Saturate benchmark capacity while live risk is running. Verify matcher health, bounded queues, priority/fairness and honest freshness. Keep service credentials and experiment/live authorization scopes separate. Test result/API pagination limits and retention/recovery procedures.

Before cloud scale, verify the credits/project/quota are available, allocate cost labels and cleanup policies, and measure a small run's compute/storage/transfer cost. Google [Batch GPU jobs](https://docs.cloud.google.com/batch/docs/create-run-job-gpus) are one possible GPU execution option; Alex owns selection of the actual research environment. No expensive cloud deployment is implied by this planning pack.

## Evidence bundle per accepted run

Save source commits and environment/dependency versions; booking/order identities; corpus and input manifests/hashes; terms/market/model/calibration/scenario versions; requested and actual precision/device; run/attempt IDs; raw outputs; relevant reference outputs/tolerances; clock and timing definitions; coverage/failures; commands or reproducible entrypoint; and cost/resource metadata when applicable.

A passing integration test, financial reference comparison, live deployment observation and throughput benchmark are distinct pieces of evidence. Record which were actually run. An older document's result is not current evidence for a changed source composition.
