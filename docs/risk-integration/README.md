# TraderX + JAX: trading and risk system plan

Plan date: 2026-09-11. Status: proposed implementation plan, based on source inspection; no combined runtime proof has been run for this plan.

TraderX base: `YU17-otc-rates` at `592bc3c66faccba04e12a9307bce36119aac67f2`.
JAX baseline: `AlexNeugroschl/JAX_Risk_Engine` at `2f69827494858120f379aba2ac63013c4936bee9`, verified as public `main` on the plan date.
Working branch and directory: `traderX-risk-integration`.

## Product goal

Build a system in which actual orders and OTC bookings create portfolios, market observations change their value, JAX explains the exposure, and explicit trading policies can respond. The same system must replay historical experiments and export frozen workloads for Alex's hardware and numerical-precision research.

The flagship demonstration is: replay a real market session, execute an order program through TraderX, continuously value the resulting portfolio, compare an ordinary execution policy with a risk-aware policy, and explain the change in execution cost and exposure. Re-run a frozen calculation at different precisions and on different hardware to measure financial error, runtime and cost.

## Priorities agreed in the project discussion

| Workstream | Intended outcome | Primary ownership |
|---|---|---|
| Live portfolio risk desk | Account/portfolio valuation, sensitivities, risk contributions, freshness and drill-down to bookings | Yaakov: platform/UI; Alex: analytics |
| Historical trading and risk laboratory | Reproducible market replay, strategy comparisons, forecast evaluation and experiment reports | Yaakov: data/replay/execution; Alex: scenarios/risk evaluation |
| Risk-aware execution and hedging | What-if analysis, advisory hedges, then bounded policies that change order execution | Yaakov: policy enforcement/execution; Alex: calculations/hedge mathematics |
| Cross-asset pricing | Faithfully price the supported equity, option, debt and OTC book | Yaakov: terms and observations; Alex: pricers and calibration |
| Precision and distributed compute | Reference-validated CPU/GPU/TPU experiments on realistic workloads | Alex leads; Yaakov supplies reproducible workloads and displays results |

The first transaction proof is an early integration gate, not the scope of the project. Counterparty XVA/collateral is a potential later expansion, not a dependency of these five workstreams.

## Read the plan

| Document | Purpose |
|---|---|
| [01 — Architecture](01-architecture.md) | Components, live/replay/control flows, clocks, recovery and UI |
| [02 — Contracts and data](02-contracts-and-data.md) | Draft exchange contracts, identity, financial conventions and result semantics |
| [03 — Delivery plan](03-delivery-plan.md) | Dependency-ordered milestones and acceptance gates |
| [04 — Yaakov backlog](04-yaakov-backlog.md) | Separate itemized work for each TraderX subsystem |
| [05 — Alex backlog](05-alex-backlog.md) | Proposed additions for each analytics subsystem |
| [06 — Validation and research](06-validation-and-research.md) | Correctness proofs, experiments, performance and numerical comparisons |
| [07 — Baseline and decisions](07-baseline-and-decisions.md) | Source evidence, stale assumptions and unresolved joint choices |

Backlog IDs are stable: `Y-*` belongs to Yaakov's proposed implementation lane and `A-*` to Alex's proposed lane. Milestones are `M0` through `M6`; validation gates are `V1` through `V8`. All backlog checkboxes start unchecked. Alex's list is a collaboration proposal, not a record of commitments he has already made.

The initial backlogs contain **69 Yaakov items** and **65 Alex items**, organized by subsystem. These are bounded work packages with completion criteria, not equal-size tickets or an estimate of the number of development days. Break them into implementation tickets as each milestone starts.

## System boundary

TraderX owns committed trades, booked terms, account state, order execution and control enforcement. The market-data layer owns dated observations and their provenance. Alex owns pricing models, numerical calibration, scenario methods and the interpretation of analytic sensitivities. The integration layer preserves identities and coordinates work; it does not invent missing contractual terms or silently substitute pricers.

An accepted analytical result can inform a policy. Only an explicit, authenticated, versioned command can change TraderX behavior. Risk math and remote calls remain outside deterministic consensus application.

The platform must distinguish a complete supported portfolio from a partial valuation, a stale result from a current one, an observed input from a model assumption, and a hypothetical order from a committed trade.

## How this branch fits the repository

This is a documentation/planning branch rooted in the tracked YU17 tree. Source-worktree untracked handoff files and runtime artifacts are not part of the base. No new numbered YU state, generation catalog entry or deployment is introduced by this pack.

Before implementation, assign each change to an explicit owner branch and generation layer, or agree a new state pack. Reading a file under a `specs/YUxx-*` directory does not by itself establish ownership. Audit later overrides of the same generated target before changing it. Persistent fixes belong in owned source/generation inputs, not hand-edited generated output.

Existing historical integration documents remain historical evidence. This pack supersedes their integration roadmap for this branch; it does not rewrite their dated results. See [baseline and decisions](07-baseline-and-decisions.md) before treating any old deployment, proof or model claim as current.

## Verification of this planning change

Checked on 2026-09-11:

- All nine new Markdown documents have valid local links, unique backlog IDs, resolvable task references, balanced code fences and clean whitespace.
- Repository front-matter validation passed for the existing 32 learning documents.
- Root Spec Kit gates and Spec Kit readiness passed.
- The wider spec-coverage check passed its earlier stages, then failed because `docs/learning/state-YU17-otc-rates.md` is already out of date relative to its generator. The same failure was reproduced with `pipeline/refresh-state-docs.sh --check` in the original YU17 worktree. This planning change does not edit that inherited generated page.
- Website dependencies are not installed in the new worktree, so a website build was not run. No pricing, cluster, replay, policy or accelerator acceptance gate in this pack has been executed as part of writing the plan.
