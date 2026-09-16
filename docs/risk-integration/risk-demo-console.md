# Risk tab (read-only synthetic pricing demo)

The console's Risk tab (`/risk`) presents the EOD risk integration for demonstration. It is read-only:
no submission, rerun, or other mutation control exists on the page or its routes. Everything it
shows is **synthetic fixture validation** for business date 2025-06-02 under the assumed
`flat-3pct-v1` curve and is **not usable for production risk**.

## Sections and their sources

1. **Integration overview**: `GET /eod/jobs` (unchanged; see [local-job-status.md](local-job-status.md)).
   Each job shows three stages (input bundle, Alex result, TraderX validation), identity, cut,
   profile, attempts, integrity and selection. Bill and note are separate jobs and are never merged.
   Queued, awaiting result, failed, mock, W0 and synthetic pricing validated are distinct labels.
   The worker is shown as not probed. When the fixtures share a cut in one coordinator, the reader
   reports `selectionAmbiguous`; the page states that plainly rather than selecting either result.
2. **Synthetic pricing comparison**: `GET /risk/demo`. Bill and note, long and short: Alex NPV,
   the independent Decimal reference, difference, tolerance and pass/fail. The note adds clean NPV, exported accrued,
   the dirty − clean − accrued residual, exported vs recomputed ICMA accrual with its unrounded bound,
   and the signed USD price change P(3.01%) − P(3.00%) for the whole position (not per unit and not per-pillar DV01).
3. **Coverage and remaining work**: per-calculation outcomes from each job's validated result
   coverage, plus fixed rows: bill sensitivity unsupported (not zero), note gamma/theta unsupported,
   SOFR and equity unsupported, portfolio VaR/ES unavailable, and terms-v2, versioned result schemas and a durable
   EOD HTTP service pending. The pending rows cite the dated review of Alex's engine at `bb9cf0e`
   (2026-09-16 16:53 UTC). They are not a live capability probe. The profile stays pinned to `e7246e1`.

A failed, unconfigured or invalid read clears that section's data and says unavailable, with no
stale values and no zero placeholders. "Last successful read" is only a timestamp.

## /risk/demo configuration

`RISK_DEMO_ARTIFACT` is an absolute path to one JSON file, set by the server operator and never taken
from a request. The file is re-read and re-validated on every GET (`Cache-Control: no-store`; non-GET → 405).

| Condition | Response |
|---|---|
| unset | 503 `NOT_CONFIGURED` |
| relative path | 503 `INVALID_CONFIGURATION` |
| missing, >1 MiB, not JSON, or fails validation | 503 `ARTIFACT_INVALID` (no detail, no path) |
| valid | 200 `{schema, availability: AVAILABLE, servedAt, artifact}` |

The console image must ship `risk-demo.mjs` beside `server.mjs`.

## Artifact contract `traderx.risk-demo.v1`

This is a closed shape: `web-front-end-console/risk-demo.mjs` `validateRiskDemo` is the executable
definition, and `test-fixtures/risk-demo-synthetic.json` is an example built from the committed producer
outputs and `pricing_result.reference`. Summary:

- Root: `schema`, `scope=synthetic-fixture-validation`, `usableForRisk=false`, `portfolioRiskAvailable=false`,
  `workerConnectivity=NOT_PROBED`, `businessDate=2025-06-02`, `valuationTime=2025-06-02T16:00:00-04:00`,
  `assumedMarketProfile=flat-3pct-v1`, `marketProvenance=assumed`, `generatedAt`, `traderxCommit` (40 hex),
  `compatibilityProfile={adapter: alex-pricing-local-provisional-v1, engineCommit: e7246e1…}`,
  `producerExecution={location: LOCAL, completedAt}`, `jobs`.
- `jobs`: exactly bill then note, with distinct `jobId`, each with the pinned fixture `bundleId`, `status=SYNTHETIC_PRICING_VALIDATED`,
  `resultIntegrity=VERIFIED`, `resultSha256` (raw results.json hash), `validatedAt`, `attemptCount≥1`,
  `clusterEpoch`, `cut`, `positions` and `coverage` (copied from the validated result).
- `positions`: exactly long then short, with `signedFaceUsd` ±100000, `npv` comparison, and `noteDetail`
  (null for the bill; for the note, clean/accrued/+1bp comparisons, `dirtyMinusCleanMinusAccruedUsd` and
  `accrualReconciliation{exportedFraction, recomputedFraction, differenceUsd, boundUsd, withinBound}`).
- Comparison: `{alexUsd, referenceUsd, differenceUsd, toleranceUsd: "0.00000001", withinTolerance: true}`.
  Money and fractions are plain-decimal strings with no exponent.

The reader cross-checks difference arithmetic, tolerance, bound, residual, face sign, and coverage
(bill sensitivity unsupported, note sensitivity ok, gamma/theta unsupported, vega/VaR-ES not applicable,
counts summing to the item count). Any failure rejects the whole artifact.

## Verification

```sh
cd web-front-end-console
node --test risk-demo.test.mjs eod-jobs.test.mjs
CHROME_BIN="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" npx ng test --watch=false --browsers=ChromeHeadless
npx ng build
PORT=8097 AUTH_MASTER_SECRET='' RISK_DEMO_ARTIFACT=/abs/risk-demo.json \
  EOD_COORDINATOR_STATE=/abs/state EOD_STATUS_SCRIPT=/abs/eod-risk-bundles/job_status.py node server.mjs
```

The console is root source only: the state pipeline renders no copy of it, so there is no generated tree to compare.
