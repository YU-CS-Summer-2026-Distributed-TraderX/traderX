# Local provisional synthetic pricing acceptance

TraderX has a separate acceptance profile for Alex commit `e7246e1765a2f9b4d4dd6049c97d1baa66319be7` with explicitly selected `flat-3pct-v1`. It is deliberately limited to the original dated bill/note bundles. W0 retains its old pin and non-pricing semantics. See [the full contract](../../specs/YU18-eod-risk-bundles/contracts/provisional-pricing-local-v1.md) for accepted fields, independent formulas, units and tolerances.

## Repeatable real-checkout demo

Run from this worktree with Python 3.11+ and Alex's declared numerical dependencies installed in an external environment. The review environment `/private/tmp/traderx-alex-e7246e1-review-env/bin/python` may be reused after verifying it exists and imports Alex successfully; it is temporary, not a repository dependency. The reviewed environment has ORE 1.8.17.0, JAX/JAXlib 0.10.2, numpy 2.4.6, scipy 1.17.1, pandas 3.0.5. The validator and its tests themselves use only the standard library.

```sh
/private/tmp/traderx-alex-e7246e1-review-env/bin/python scripts/demo-state-YU18-alex-pricing.py --engine /Users/yaakov/dev/jax_risk_engine/JAX_Risk_Engine
```

The script checks exact HEAD, clean checkout, source tree and tracked bytes before/after. If the checkout changed or is dirty, it archives the reviewed commit into a separate read-only temporary reference, without altering Alex's checkout. Missing reviewed commit or missing dependencies is a hard failure. It calls the real price_bundle library with explicit market selection, retains stdout, tests pending/restart/one-attempt acceptance, duplicate discovery, W0 rejection and original-byte custody. The printed evidence path is a private temporary directory containing report, incoming original results, SQLite state, local receipts and sanitized status snapshots. Preserve that directory for review; no network service or paid computation is used.

For generated validation pass `--component generated/code/target-generated/eod-risk-bundles` after full YU18 generation.

## Local intake commands

```sh
python3 specs/YU18-eod-risk-bundles/generation/runtime-overrides/eod-risk-bundles/coordinator.py --state /private/tmp/my-pricing-state --pricing-results /private/tmp/my-incoming-results discover /private/tmp/my-bundle-inbox
python3 specs/YU18-eod-risk-bundles/generation/runtime-overrides/eod-risk-bundles/coordinator.py --state /private/tmp/my-pricing-state --pricing-results /private/tmp/my-incoming-results run
```

Supply original results as `BUNDLE_ID.json`. Use an unused private state directory; adapter profiles cannot mix. Completion is SYNTHETIC_PRICING_VALIDATED, never production risk. Read-only status reports explicit synthetic scope and keeps usableForRisk=false; existing console UI is not redesigned or deployed here.

## Verification and remaining limitations

Run `bash scripts/test-state-YU18-eod-risk-bundles.sh` for the source suite, then the same command with the generated component path. New tests cover producer-output leaf tampering, closed shapes, numerical/sign/bump mistakes, unsupported capabilities, nonfinite values, input scope, original retention, real process exit recovery, late results, duplicate discovery, old-cut selection and receipt integrity. Independent references use Decimal exponentials and date arithmetic, not Alex's expected-answer generator. The committed priced JSON files are real producer test inputs.

No terms-v2 acceptance, producer result-schema upgrade, authentication, remote worker service, equity, faithful SOFR, bill sensitivity, additional Greeks, observed market data or production/financial approval is provided. Alex's unrounded-tolerance and impossible-date bugs remain external; exact admitted fixtures avoid them and other inputs fail closed. Changes to his engine require a fresh explicit review/profile decision.

## Measured implementation evidence (2026-09-16)

Dedicated branch `codex/pricing-acceptance-e7246e1`, based on accepted `c020c20d5493fa01a3c9e6afa31b83bc4db7d448`. Authoritative owner is YU18; searches found no competing EOD component overrides. Full `TRADERX_SKIP_LOCKFILE_REFRESH=1 bash pipeline/generate-state.sh YU18-eod-risk-bundles` succeeded, followed by the final YU18 renderer. No generated-only edit or broad propagation was made.

- Source and generated `scripts/test-state-YU18-eod-risk-bundles.sh`: **133 tests passed each** (original 122 plus 11 new tests, including 360 producer-result leaf mutations as subtests).
- Real pinned Alex demo against the generated component: both bill/note pairs accepted as SYNTHETIC_PRICING_VALIDATED, one attempt each after pending/restart, W0 rejected pricing, original result bytes retained, tracked Alex bytes unchanged and checkout clean at e7246e1.
- **88 component files** byte-identical between source/generated; **63 original fixture/W0/verifier files** byte-identical to the accepted base. Original W0 profile definition remains unchanged. The real Git core.autocrlf checkout proof passed, including an unprotected negative control.
- Frontmatter, root SpecKit gates, readiness and spec-coverage commands all exited 0.
- Isolated negative control replaced the true +1bp revaluation with an unbumped price difference. The full suite then failed in seven new pricing tests (3 failures/4 errors); the original 122 tests stayed green. The working source was never mutated for this control.

Preserved logs, checksums and the complete local real-demo directory are under the local coordination directory `review-evidence/pricing-acceptance-e7246e1/`, outside public Git. The READY_FOR_REVIEW post records the final commit and changed paths. The optional archive fallback is implemented but the measured real demo used the clean actual checkout, so it is not evidence of an advanced-checkout fallback run. No financial validation or live deployment is claimed.
