# TraderX response to EOD contract v3

**Date:** 2026-09-15
**From:** Yaakov / TraderX
**Status:** Local compatibility work implemented; remaining financial/worker semantics below are proposals for confirmation.
**Scope:** EOD inputs → overnight batch → morning results, locally first.

Alex — thanks for independently checking the package and identifying the checkout issue. I accept the W0 → W1 → W2 sequence. The first result we should exchange is the identified SOFR refusal; the bill/note prices are acceptance targets after the new pricers exist, not something expected immediately on receiving the fixtures.

## 1. Your four requests

### Exact bytes across checkouts — implemented

I added a root `.gitattributes` rule scoped to the YU18 fixture directory. It disables text conversion for all files in that directory, including hashed cut/preimage files, without changing unrelated repository JSON/CSV behavior. Existing fixture bytes and expected hashes are unchanged.

A test creates real local Git repositories and clones with `core.autocrlf=true`. Without attributes the golden manifest becomes CRLF and the verifier fails with a specific line-ending diagnostic. With attributes the fixture bytes remain identical and verification succeeds; an unrelated CSV still receives normal CRLF conversion. This exercises Git's checkout filters locally, not a native Windows runtime.

The verifier identifies the affected file and recommends a fresh checkout or pristine committed files. It does not silently normalize inputs or regenerate expected hashes. Existing translated working files need restoration after updating attributes; please preserve any local edits first.

### Blank-accrual note — negative fixture supplied

The new `compatibility/note-missing-accrual/` fixture deliberately blanks accruedInterestFraction on both long and short coupon-bearing rows. Everything else in those CSV bytes is preserved. Its expected metadata distinguishes:

- **TraderX boundary:** reject before bundle publication. Our existing valid-extract rules require accrued interest for coupon-bearing bonds; that rule remains unchanged.
- **Your mapper's negative test:** proposed `unavailable` / `ACCRUED_NOT_SUPPLIED`, never structural zero.

This is mutated raw input, not a completed exporter artifact or valid bundle. It has no manifest or completion receipt. Please confirm this two-boundary test arrangement; if you want a publishable bundle that admits missing accrual, that requires a separately agreed change to our validation contract.

One correction: v1 rows already carry coupon and maturity fields, so zero-coupon and coupon-bearing rows are not indistinguishable. Requiring v2 terms before normalization is fine as an explicit worker policy. Missing note accrual is already invalid on our side.

### Structured accrual basis — implemented as a new terms version

I preserved `traderx.instrument-terms.v1` and the original shared fixtures. The new `traderx.instrument-terms.v2` adds this required field on Treasury entries:

```json
"accrualBasis": {
  "schema": "traderx.accrual-basis.v1",
  "dateBasis": "SESSION_DATE",
  "valuationDate": "2025-06-02",
  "settlementAdjustment": "NONE",
  "fractionDecimals": 6,
  "rounding": "HALF_EVEN"
}
```

The new `compatibility/note-structured-basis/` bundle pins that terms version and has a new bundle identity. Validation checks the date against the extract business date and rejects conflict with the current NONE-calendar, unadjusted, same-day settlement fixture. A changed real-market settlement/calendar basis must not silently reuse these semantics. Existing terms v1 bundles remain accepted and keep their original hashes.

This describes the exporter accrual basis and rounding. It does not turn synthetic reference assumptions into observed facts or establish that every pricing model supports them. Please confirm the field shape before freezing your consumer.

### Synthetic versus assumed — accepted

Please keep `synthetic` as a distinct provenance value from `assumed` for your proposed market/curve/result vocabulary. Synthetic reference terms and an assumed pricing curve must remain separately identifiable in the result. Our reference `provenance.origin` remains `synthetic` or `supplied`; it is not being broadened into a curve-origin enum. `supplied` by itself is not proof of market observation.

## 2. Accrual reconciliation: two details to freeze

Your independent schedule/accrual check is useful evidence of representability. We agree at the exported fraction precision, but the monetary values depend on the rounding path:

| Basis | Long 100,000 face | Short 100,000 face |
|---|---|---|
| Exported fraction 0.018571 × signed face | +1,857.10 USD | −1,857.10 USD |
| Recomputed unrounded fraction, rounded to cents | +1,857.14 USD | −1,857.14 USD |

Please label whether the returned accrued value is taken from the export or recomputed from the schedule. For this fixture, rounding a fraction to six decimals can introduce up to $0.05 at 100,000 face, before any additional currency rounding. We should set the tolerance by this rule rather than compare the two monetary values as exact equals.

Also, quantity is already signed: use **accrual fraction × signed face amount**. Do not multiply by a separate position sign again; that would make a short position positive.

## 3. Worker semantics still needing precision

**Result publication versus lookup pointer.** Your manifest and lookup-pointer updates are distinct operations. Please specify recovery after manifest publication but before pointer advancement, so a completed result remains discoverable after restart. A directory scan/reconciliation or recoverable publication journal could close that gap.

**Lookup with no completed result.** Distinguish an unknown workload from accepted/running work. If a results-only endpoint returns 404 for both, our client needs a separate durable job lookup or an idempotent submission key to avoid interpreting pending work as permission for duplicate execution. A process-memory loss must not automatically become a financial failure.

**Fresh benchmark retries.** `reuseExistingResult=false` expresses fresh execution, but a lost submission response still needs a stable submission-request identity. Retrying that request should recover the same fresh attempt; deliberately starting another benchmark repetition should use a new request identity.

**Identity in W0.** The SOFR refusal must already echo bundle/terms identity and the source contractId, clusterEpoch and account. Array order alone is insufficient. Full cube item-order plumbing can follow later, but per-item refusal identity cannot wait for W1's final task.

**Coverage.** I accept the calculation names and status arithmetic. Our consumer should recompute the counts/flags from per-item outcomes and reject inconsistent summaries. `allOutcomesAccountedFor=true` must never be presented as proof that pricing succeeded. Non-applicability also needs the agreed capability/semantic justification, not just a label that improves coverage.

## 4. Implementation review and next handoff

I checked your pushed commit `2f8656e` against GitHub. The per-trade NPV, swap Greeks, calibrated Bermudan vega and aged-swap warnings are present in source. I have not rerun your numerical suite locally. The aged-swap approximation remains a limitation, and I understand the durable worker contract and bond/equity pricers are still to come.

One source-review follow-up: `_swap_curve_configs` validates curve indices in the Greeks path, but base pricing indexes the curve list directly. Please validate the indices before all pricing and test negative/out-of-range values with `compute_greeks` both true and false. This is a source-level concern, not an executed reproduction.

New files in the TraderX checkout:

- `specs/YU18-eod-risk-bundles/contracts/compatibility-v3.md` — compatibility additions and version boundaries.
- `specs/YU18-eod-risk-bundles/generation/runtime-overrides/eod-risk-bundles/tests/fixtures/compatibility/` — structured-basis bundle and missing-accrual negative fixture.
- `scripts/test-state-YU18-checkout.py` — repeatable Git checkout-filter proof.

**Next from you:** W0 machine-readable request/result/capability schemas and an identified SOFR refusal, with engine version and named test evidence. Please include the lookup/publication recovery behavior above. I will then connect our actual worker client and financial-result validator to that agreed format. The existing mock HTTP draft remains v1-only; it is not your production interface.

Local verification: **99 Python tests passed** against source and generated output. The new `CompatibilityTests` cover structured-basis versioning, inconsistent dates/conventions, missing-accrual rejection and CRLF diagnostics. `scripts/test-state-YU18-checkout.py` passed its real Git checkout-filter proof. Original golden/shared fixture bytes remain unchanged.

No new market-data package, external pricing result or cloud deployment is claimed by this compatibility update.
