# Risk pipeline specification

Updated: 2026-09-23. Status: review needed; local milestone implemented and tested. Owner: Codex RI-03, isolated codex/risk-pipeline at d6ca3330.

## Responsibilities and boundary

The existing sequenced TraderX service and RiskExtract exporters own positions, contracts and cut receipts. shared_examples/bridge validate the receipt and assemble a hash-addressed bundle with explicit synthetic reference terms. Bundle validation owns input custody. Coordinator owns private snapshots, SQLite jobs, exclusive single-host lock and attempts. The new container adapter stages these exact bytes under a read-only /data/inputs mount and calls the accepted synchronous /eod/price API. Engine 992db30 owns pricing; EOD terminal attempts live in its external store. Consumer intake owns schema, identity, coverage and provenance validation plus immutable original HTTP bytes. Existing readEodJobs and RiskPage own read-only display.

/portfolio/price accepts a separate simulation portfolio and has process-local asynchronous job lookup. It is not an interchangeable bundle endpoint and is not used. No hand-transcription of TraderX economics into that API. One synthetic exported bill portfolio, two signed positions, is the closed supported milestone. The dated original fixture identity is pinned; additional portfolios/observed markets require a separately reviewed profile.

## Requirements

- FR-RP01: Rebuild the supported bundle from original TraderX exporter CSVs and explicit terms. Validate cut/receipt provenance and retain hashes. Reject other economics; never substitute synthetic inputs for a supplied real job.
- FR-RP02: Separate container profile pins engine revision, image identity, result schema, mapping and named assumed flat-3pct-v1 curve. Prior mock/W0/pricing profiles retain behavior. Unsupported currency/calculation options fail locally before any POST.
- FR-RP03: Mount staging read-only and external engine results read-write; endpoints are literal loopback HTTP. Container paths derive from bundle identity, never arbitrary remote upload claims. Staged bytes must match coordinator custody.
- FR-RP04: Persist immutable request intent with consumer attempt/submission ID and workload key before POST. Preserve raw response and attempt lookup bytes before validation. Reconcile only by GET after uncertain submission; never auto-resubmit. Unknown after restart becomes UNCERTAIN, not never-ran. Cached results must prove matching submission binding through the attempt endpoint. No exactly-once guarantee.
- FR-RP05: Validate pinned published schema, result input identities, full item ordering, calculations/coverage counts, assumed provenance and attempt binding before accepting. Require finite numeric fields, nonzero NPV with the signed bill-face direction, zero structural-zero accrual, and consistent explicit USD amount units; reject unreviewed calculation extensions without implementing discounting. Store raw HTTP envelope unchanged and separately extracted result with hash linkage. Revalidate offline at every status read. Reject mismatches/corruption without display promotion.
- FR-RP06: Expose QUEUED/RUNNING/FAILED/UNCERTAIN/CONTAINER_PRICING_VALIDATED honestly. Only verified intake permits price rows. Keep usableForRisk=false and portfolioRiskAvailable=false; display unsupported calculations and synthetic/assumed labels. No worker call from UI reads.
- FR-RP07: Restart consumer without creating a second attempt; reuse completed custody offline. Container terminal attempt recovery is verified with external store retained. Lost active state remains uncertain. Manual retry is disabled for this profile until a reviewed reconciliation contract exists.

## Nonfunctional requirements

- NFR-RP01: Single host, one lock, no parallel workers or distributed durability claims. Private consumer directories outside Git; only synthetic fixture responses may be tracked. No cloud dependency, push, registry or engine source changes.
- NFR-RP02: Preflight schema/dependency availability before state/staging/submission; CI and packaged status runtime install the pinned requirement. Bounded HTTP time/bytes, no redirects/proxies, fsync intent before submission. Container uses accepted nonroot/read-only/resource-bounded launcher settings and task-specific ports/name.
- NFR-RP03: Financial calculations stay in canonical engine; independent Decimal checks run only in acceptance tooling. Engine four failing acceptance cases remain a separate red gate (cache binding, overlap, unsupported currency, unknown calculation).

## Observable acceptance cases

| ID | Input/action | Expected |
|---|---|---|
| SC-RP01 | Fresh in-process TraderX bill export, explicit assumed curve, real image | hash-valid bundle, one request/attempt, priced long/short, exact HTTP custody, verified UI rows |
| SC-RP02 | Missing terms/artifact/market profile; unsupported scope/options | local or engine refusal; no invented economics or accepted result |
| SC-RP03 | Wrong bundle/item/workload/submission identity, schema/provenance/coverage mutation | rejection; original bytes retained; no pricing display |
| SC-RP04 | Consumer dies after persisted intent or response | GET-only recovery; known bound completed result accepted; unknown becomes UNCERTAIN; no second POST |
| SC-RP05 | Container replaced with same external store | completed/failed EOD attempt GET survives; consumer accepted bytes unchanged |
| SC-RP06 | Corrupt accepted result, failed job, uncertain request | read-only API/UI clears prices and shows failure/uncertainty |
| SC-RP07 | Existing independent bill pricing reference | signed NPV within USD 1e-8; no broader numerical risk claim |
| SC-RP08 | Unchanged upstream proposed-contract starter | four known failures recorded, not skipped or adapter-fixed |

Source tests, generated parity, actual container HTTP, exporter execution and browser-rendered existing UI are separate evidence levels. Full live consensus/trading stack and real-market valuation are outside this synthetic in-process export milestone.

Evidence and reproduction: [RI-03 local container proof](../../../../docs/risk-integration/ri03-local-container.md). Broader distributed readiness remains open.

## Current regular-portfolio CPU profile

- FR-CPU01: Platform packaging exports committed clean sources, records numerical and owned-service identities separately, pins the base/wheels, verifies installed engine/consumer bytes, and reproduces its source package without temporary pilot files.
- FR-CPU02: Reuse v2 bundle/terms validation; require explicit account/run/bundle and matching synthetic curve. Preserve epoch/cut, signed face units and all selected positions. Refuse unsupported or incomplete selected coverage before submission.
- FR-CPU03: Keep regular portfolio submission/recovery semantics. A separate connected receipt binds checked export lineage to the retained request, job and result. Offline admission requires the caller's retained package fingerprint.
- FR-CPU04: Prove a nonzero single-account value using fresh generated exporter evidence and a real installed CPU container, plus negative identity/custody controls and the frozen golden regression. Empty initialized EOD directories are allowed; actual EOD work is refused.
- FR-CPU05: This candidate provides local mapping/packaging/intake only. Existing EOD orchestration/UI are unchanged. Cloud Build configuration preparation is not qualified cloud image admission; Batch UID/receipt handoff remains pending. No production account authorization or distributed replay guarantee is claimed.

[Profile, evidence and reproduction](../../../../docs/risk-integration/cpu-export-platform.md).

## Local Batch result ingestion, October 15

FR-RP-BATCH-IMPORT: admit only a fully verified copied Batch result, bound to trusted package/build/job/control/account/run/cut. Explicit operator selection and an independently retained inventory digest are required; exact replay is idempotent and conflicting replay is refused. Reads revalidate offline and never submit work. UI displays synthetic NPV with unavailable scenario/general financial measures and clears failed refreshes.

## VaR and ES end to end, October 26

Owner: `generation/runtime-overrides/order-matcher/.../cluster/VarEsEndToEndTest.java` (TraderX half) and
`generation/runtime-overrides/risk-portfolio-tools/var_es_e2e.py` (engine half, wrapped by
`scripts/risk-var-es-e2e.py`); `scripts/proofs/yu18-var-es-e2e.sh` runs both, back to back, with no rig.
Report to the engine owner: `docs/risk-integration/alex-var-es-e2e-report.md`.

- FR-RP-VARES-01: The TraderX half SHALL book every instrument class through real ingress against three
  in-process members and a child-JVM `ClusterGatewayMain`: listed equity over JWT-bound REST `/orders`,
  a listed option over FIX 4.4, a Treasury bill over the binary path, and two OTC swaps over `/swaps`
  (USD-SOFR and, after a sequenced FX rate, GBP-SONIA). It SHALL then close the venue, sequence the EOD
  marker, require a byte-identical cut on all three members equal to the announced hash and the N+1
  quiescence witness, and write the production exporters' `positions.csv`, `contracts.csv` and the
  write-once ready receipt to `TRADERX_VAR_ES_E2E_OUTPUT`. A counterparties table missing one trading
  account SHALL make the extract refuse.
- FR-RP-VARES-02: The engine half SHALL package the receipt with eod-risk-bundles and map each row to a
  `POST /runs` trade of the engine, or refuse it by name. It SHALL never substitute a term. One run with
  analytics `npv` and `market_risk` SHALL produce VaR and ES at 99 and 97.5 that are finite and non-zero
  for the portfolio and attributed to every priced trade.
- SC-RP-VARES-01: `VarEsEndToEndTest` passes in the order-matcher suite on an empty-root render, and
  `test_var_es_e2e.py` passes in the risk-portfolio-tools render.

## Every instrument class, and large books, November 6

Engine measured: Alex's upstream `17ac757e8ffe9b180fa6e98ca6114c474ab0e6ad` (main, October 29), unmodified, which
`var_es_e2e.ENGINE_REVISION` now names (it was `f9cc4c1`). Requests for the engine's gaps:
`coordination/alex-engine-requests-20261106.md`. Results: `docs/risk-integration/full-pricing-and-large-books-20261106.md`.

- FR-RP-VARES-03: The TraderX half SHALL also book a coupon Treasury note and a corporate bond (binary path),
  and European, Bermudan and American GBP-SONIA swaptions plus a USD-SOFR European over `/swaptions`. The
  engine half SHALL map:
  - a coupon Treasury with the extract's own schedule (semiannual, every date measured back from
    `maturityDate`, clamped to month end, from `lastCouponDate` to maturity; a `lastCouponDate` off that walk
    is refused);
  - a swaption with the booking account as holder (`long_short` Long, since `/swaptions` has no short side),
    `fixedRate` as strike and `PAY_FIXED` as payer. EUROPEAN exercises on `expiryDate`. BERMUDAN exercises on
    `expiryDate` and every underlying period start after `effectiveDate` before maturity. AMERICAN exercises
    from `expiryDate` to the last such period start. Bermudans and Americans calibrate on the trade's own
    index conventions (1Y fixed, ACT/365, the index tenor), one tenor per run.
  - a flat synthetic ATM normal swaption surface per currency.

  Only an ACT/365(F) underlying maps, because the engine's swaption legs carry no day count. The run's
  idempotency key SHALL hash the whole request, so a changed setting is a new run.
- FR-RP-VARES-04: Every refusal SHALL name its owner. `engine` refusals carry the request id (R1 equity,
  R2 listed option, R3 ACT/360 OIS, R4 corporate credit) of the coordination note; anything else is
  `traderx` and is a defect of ours. The verdict is unchanged: the proof exits 0 only when every row prices.
- FR-RP-VARES-05: `ore_parity.py`, run in the engine's environment, SHALL check the engine's t=0 NPV of
  every sampled trade:
  - bonds and 6M-index trades against the engine's own ORE oracle (`tests/support/ore_npv_oracle.py`), one
    currency group at a time, relabelled USD (the engine builds every currency alike);
  - 12M-index swaps and Europeans against QuantLib on the engine's own `MakeVanillaSwap` schedule
    (`DiscountingSwapEngine`, `BachelierSwaptionEngine`);
  - Bermudans and Americans by European <= Bermudan <= American on identical terms.

  Flat curves only, and refused otherwise.
- FR-RP-BOOK-01: `book_gen.py` SHALL emit a deterministic booking plan (same arguments, same bytes) across
  every class TraderX books, on accounts grouped into desks. The plan contains terms only, never values.
  It SHALL refuse a plan past one epoch's capacity (64 accounts, 1,024 securities, 4,096 contracts) and SHALL
  respect the booked records' limits: int notional (a JPY booking tops out near JPY 2.1bn) and
  `MAX_ORDER_QUANTITY` of 1,000,000 units an order (1mm face of one bond).
- FR-RP-BOOK-02: `VarEsEndToEndTest.largeBookThroughTheRealIngress` SHALL book a plan named by
  `TRADERX_LARGE_BOOK_PLAN` through the real gateway (binary crosses, REST swaps and swaptions). It SHALL raise
  the per-account position limit through the sequenced operator policy (it defaults to 1,000,000 units). It
  SHALL then cut, verify the cut on all three members and export to `TRADERX_LARGE_BOOK_OUTPUT`, printing
  booking and cut timings. With no plan set it is skipped.
- FR-RP-BOOK-03: `large_book.py` SHALL package such a fixture, map it as the proof does and price it as ONE
  run. A portfolio's VaR/ES is never split. It SHALL record wall time, the engine process tree's peak
  resident memory, request/reply bytes, the priced mix and refusals by class/owner. `--tile k` repeats the
  priced trades with `~k` identities; a tiled run is labelled tiled, not a cut.
- FR-RP-BOOK-04: Large-book correctness is spot-checked with `ore_parity.py --sample N` on the run's own request
  and reply. A large run completing is not financial validation.
- SC-RP-BOOK-01: `test_book_gen.py` passes (determinism, capacity refusals, every class present, OCC symbols
  that `OccSymbol` reads as options).

## TraderX's engine fork, November 7

**ADR (November 7): TraderX owns a fork of the engine.** Alex gave permission to branch off his engine. The
fork is branch `traderx/engine-fork` of his repository, in the worktree `~/dev/lmax/jax-risk-traderx`, cut
from his `17ac757`. It is committed locally and never pushed; his `main` is untouched. R1-R6 of
`coordination/alex-engine-requests-20261106.md` are implemented there instead of requested. Each comes
with an engine test whose expected value is ORE/QuantLib's, not the engine's
(`tests/test_traderx_products.py`). `var_es_e2e.ENGINE_REVISION` names the fork commit (`a5bd7c3`, the engine lane's final November 7 commit; TraderX moves the pin). A run whose
`engine.revision` is any other commit, or is marked modified, fails the proof.
Consequence: rebasing onto a later upstream is TraderX's work, and the fork's products are ours to keep in
parity with ORE.

- FR-RP-VARES-06: The engine half SHALL price every class with no refusal:
  - an EQUITY row as `equity` (quantity x multiplier x spot);
  - an OPTION row as `equity_option`, parsed from its OCC symbol (root, expiry, call/put, strike / 1000),
    American;
  - a CORPORATE row as a bond accruing `30/360` (the extract's stated `bondDayCount`) with a flat synthetic
    `credit_spread`;
  - every contract as an OIS on its `floatIndex` (`overnight_index`): USD-SOFR, EUR-ESTR, GBP-SONIA or
    JPY-TONA, accruing the convention's day count.

  The synthetic market SHALL take each equity's spot from its EQUITY rows' `closingMark` (rows of one
  security must agree), with a flat volatility and dividend yield per name. An equity trade whose name has
  no EQUITY row in the cut is refused, owner `traderx`. Equity spots are risk factors
  (`equity:<symbol>/spot`, lognormal, pairwise correlated, independent of rates). Bermudans and Americans
  SHALL calibrate on their own underlying's conventions (`swap_index_from_trade`), and Americans also
  exercise on every period start in their window (`exercise_on_period_starts`). The TraderX half SHALL also
  book a EUR-ESTR swap. One negative control per new input SHALL be refused with a 400 naming it:
  - the OIS index curve;
  - the equity's spot;
  - the option's volatility;
  - a spread bond's Greeks.
- FR-RP-VARES-07: `ore_parity.py` SHALL check the fork's products against QuantLib built in the parity
  script itself:
  - equities exactly;
  - options with `FdBlackScholesVanillaEngine` or the analytic European, to 3e-4;
  - OIS swaps and Europeans with `OvernightIndexedSwap` on the index's calendar and payment lag, and
    `BachelierSwaptionEngine`;
  - spread bonds with `FixedRateBond` on a `ZeroSpreadedTermStructure`.

  OIS Bermudans and Americans SHALL be checked by the no-arbitrage bounds.
- FR-RP-VARES-08 (GKE, November 8): the fork SHALL run on GKE as an HTTP image built by
  `risk-portfolio-tools/packaging/Dockerfile.engine-http`:
  - the batch pilot's hash-locked Linux wheels;
  - the pinned commit's clean checkout with its `.git`, so the result's `engine.revision` is that commit and
    `modified` is false. The build SHALL fail otherwise.

  A node that runs it on CPU SHALL have `vm.max_map_count` of at least 1,048,576. At the Linux default
  (65,530), XLA's CPU JIT fails with `LLVM compilation error: Cannot allocate memory` partway through a
  book of calibrated swaptions, and the worker dies. A 256-scenario run of the genuine book peaked at
  116,416 mappings. The CUDA image needs `LD_LIBRARY_PATH=/usr/local/nvidia/lib64` on GKE's driver.
- SC-RP-VARES-02: The engine fork's `tests/test_traderx_products.py` passes. `scripts/proofs/yu18-var-es-e2e.sh`
  against the fork exits 0 with every class priced and every negative control refused.
