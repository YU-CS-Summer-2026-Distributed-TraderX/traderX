# Implementation Plan: CDM Instruments

## Goal

Fold the source packs' CDM generic-instrument model (standardized security types, FIGI
identifiers, five ETFs) and U.S. Treasury trading (five fixed-rate Treasuries with real FIGIs,
face-amount quantities, clean prices, coupon/maturity/approximate-YTM display) into one state on
this line — Kubernetes runtime, last-wins layer composition, Aeron cluster tier — carrying only
what this stack actually uses, keeping `/stocks` and the YU04 durable control feed intact, and
changing nothing in the deterministic core: bond prices are stored as a fraction of par so bond
arithmetic is equity arithmetic and the engine, snapshot format and epoch all stand still.

## Workstreams

### 1. Reference data

The instruments module on the NestJS service: CDM-shaped `/instruments` +
`/instruments/{instrumentKey}`, seed loader with the classification map and load-time CDM
assertions, `instruments.csv` with FIGI/securityType columns, the five ETFs and five Treasuries,
supplemental FIGIs. `/stocks` and `/stocks/control-snapshot` keep serving unchanged;
`/instruments/control-snapshot` is added over the same store and watermark. All of it lands in
this state's layer based on the YU04 copies it shadows.

### 2. Treasury pricing

`treasury-pricing.js` (term-profiled correlated walk, mean reversion, band clamp, approximate
YTM, maturity handling) plus the five snapshot seeds, hooked into the YU15 `main.js` (shared
roll per batch, matured-quote suppression, `UST-` no-fallback 404, fixed-clock hook). Emission
is fraction of par at six-decimal tick precision; the 3-dp rounding stays equity-only.

### 3. Schema

The MariaDB `database-init-configmap.yaml` at this state's layer, based on the operative YU15
copy: `Rejected` state, `RejectionReason`, `SourceOrderId`, price columns widened to
`DECIMAL(18,6)`, the account-17017 seeds with fraction-of-par prices.

### 4. Post-trade merge

The source pack's processor and position-service deltas merged into the operative copies our
layers already override: `InstrumentMetadata` + `InstrumentMetadataClient` (fail-closed,
configurable timeouts), `TradeState.Rejected`, `Trade`/`TradeOrder` rejection fields,
face-weighted Treasury average cost in `TradeService` (onto the YU05-operative copy),
`RuntimeConfig` clock/HTTP beans, position-service `Trade` + `TradeRepository` rejection
columns, and the trade-service controller validation (fetch-instrument + Treasury order
validation, onto the YU02-operative copy). Every file is grepped across all `specs/*/` layers
first and based on the operative copy.

### 5. Gateway validation

Face-amount validation (≥100, multiple of 100) for `UST-` keys at the cluster gateway's REST
boundary, based on the YU13-operative `ClusterGatewayMain` — rejection before the engine, exact
source-pack messages, no engine involvement.

### 6. Frontend

The source pack's Angular work rebased onto this line's operative frontend files (YU03's
runtime-overrides for the order ticket and trade component, 014's for the rest): asset-class
filter, grouped selectors, Treasury labels and validation messages, clean-value estimation,
coupon/maturity/YTM display, percent formatting off the stored fraction, rejected-trade display.

### 7. Extract

`instrumentType: TREASURY` plus `coupon`/`maturityDate` by join against the extended
`instruments.csv`; CSV schema 2; `risk.extract.ready` announces `schema: 2`; the consumer guide
documents the new columns and the bond convention. The `.cut` is untouched.

### 8. Proofs

The full inherited suite green on the standing rig (rolled image, same PVCs, same epoch), the
two YU04 proofs migrated to `/instruments/control-snapshot`, and new proofs: Treasury pricing
(fraction semantics + six-decimal ticks on the wire) and bond position math (face × fraction
through order → position → extract).

## Key decisions

- **ADR-057** — Bond prices are a fraction of par on every internal surface; the multiplier
  stays 1 and the deterministic core does not change.
- **ADR-058** — `/stocks` is retained; `/instruments/control-snapshot` is added alongside;
  the bootstrap repoints by configuration at this state's layer. Supersedes source FR-01602.
- **ADR-059** — Instrument static reaches the risk extract by join; the CSV schema bumps to 2;
  the cut format does not change.

## Exit Criteria

- `bash pipeline/generate-state.sh YU16-cdm-instruments` exits 0 from clean.
- The order-matcher, trade-processor and position-service suites pass with the new tests; the
  allocation and no-GC gates stay green.
- `scripts/yu15/run-proofs.sh` passes end to end against a rig running this state's image with
  its PVCs and epoch intact — including the migrated YU04 pair and the two new proofs.
- `GET /stocks` 200, `GET /instruments/UST-20360515` correct, and a face-100,000 Treasury order
  books to a position displaying 99.886%-style percent off a stored fraction.
- `SNAPSHOT_FORMAT` still 4; no fresh epoch was minted and no PVC was wiped at any point.
