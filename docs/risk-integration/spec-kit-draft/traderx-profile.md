# TraderX consumer profile — draft binding

The generic engine spec must remain usable without TraderX. This profile maps the first producer into that boundary. It references current maintained source contracts instead of rewriting them:

- [Bundle and terms](../../../specs/YU18-eod-risk-bundles/contracts/bundle-v2-and-terms.md)
- [Terms-v2/accrual compatibility](../../../specs/YU18-eod-risk-bundles/contracts/compatibility-v3.md)
- [Market-input package](../../../specs/YU18-eod-risk-bundles/contracts/market-input-package-v1.md)
- [Existing provisional pricing profile](../../../specs/YU18-eod-risk-bundles/contracts/provisional-pricing-local-v1.md)

Initial acceptance includes signed bill/note values, explicit assumed curve provenance, clean/dirty/accrued reconciliation, exported versus structural-zero accrued source, and note +1bp sensitivity with the agreed sign and units. Keep the original dated synthetic examples immutable; add new fixtures for new conventions.

Distinguish bundle-v2 from instrument-terms-v2: a v2 bundle may contain terms-v1. Test both accepted terms versions explicitly. Freeze existing accrual-basis meanings; new settlement/calendar semantics require an agreed version rather than silently changing an enum's interpretation.

The original profile is fixture-bound and pins an older engine. This proposal does not repin it, loosen W0, or make current HTTP outputs acceptable automatically. A separately named profile must pass acceptance before consumer migration.

Observed at e4ca50b: terms-v2, schemas and accrued-source fields are delivered; HTTP terminal results have filesystem persistence. Independent probes found cached submission conflict bypass, duplicate concurrent execution, and ignored request options. Equity spot/FX and faithful SOFR remain separate open work. These observations describe that revision only.
