# Synthetic exporter examples for Alex

These are synthetic inputs, not private portfolio data or computed risk results. Business date:
2025-06-02. See the state contract `contracts/bundle-v2-and-terms.md` for exact semantics.

- `bill/`: two signed Treasury bill positions; coupon schedule is absent.
- `note/`: long and short 100,000 USD face in one regular 4% note. Issue/schedule/reference
  assumptions are in v2/instrument-terms.json. Same-day settlement and unadjusted payments are
  deliberate fixture assumptions, not claimed real-market conventions.
- `sofr/`: a synthetic booking using the platform's current USD-SOFR convention. Incomplete
  conventions are enumerated. Expected unsupported is an acceptance target for Alex, not a mock
  pricing result. Dates and identity do not refer to the private live transaction.

Each `v1/` or `v2/` directory is a complete bundle. CSV bytes are identical between the two versions.
The note's single security-terms entry applies to both position accounts. OTC terms are keyed by
contract and synthetic epoch. Cut files and case metadata sit outside the bundle directories.
No curves are supplied and no price/Greek baselines are claimed.

From the repository root, regenerate YU18 and run:

```bash
bash scripts/demo-state-YU18-shared-examples.sh
```

This executes the Java engine/exporter tests, checks receipts, reproduces these frozen case files,
and exercises local mock consumption. It does not rewrite this package or its expected outputs.
`provenance.json` pins the generator and directly used exporter sources. Base commit identifies
inherited code; the generator hash identifies the added test. These checks are reproducibility
checks, not cryptographic authentication of a producer.

For Alex: choose one bundle version per case. Preserve security/contract identity in returned rows;
use an explicitly named assumed curve for Treasury valuation, agree numeric tolerances and
rate-sensitivity units, and report SOFR unsupported with a reason. The v1 hash-vector package is
in the neighboring `golden-v1/` directory. The HTTP mock draft currently accepts v1 only.
