# Generation Hook: YU16-cdm-instruments

- Hook script: `pipeline/generate-state-YU16-cdm-instruments.sh`
- Render script: `pipeline/render-state-YU16-cdm-instruments.sh`
- Feature pack: `specs/YU16-cdm-instruments`
- Parent state: `YU15-eod-risk-extract`
- Overlay model: generate YU15 and its complete YU14…YU02 → 014 ancestry, then apply YU16
  `generation/runtime-overrides/` last-wins to the shared component tree, copy this pack's
  `reference-data/*.csv` into order-matcher resources (over YU15's copies), and copy this spec
  pack into the generated state artifact.

## Hook Responsibilities

1. Generate the parent chain (`pipeline/generate-state.sh YU15-eod-risk-extract`).
2. Overlay `generation/runtime-overrides/` onto `generated/code/target-generated/` (tar-copy,
   file-level, last-wins).
3. Copy `reference-data/*.csv` into `order-matcher/src/main/resources/reference-data/` — the
   extract's join static; YU16's `instruments.csv` supersedes YU15's, `counterparties.csv` is
   inherited untouched.
4. Copy `generation/kubernetes/` (cluster manifests) into the state artifact's runtime dir.
5. Copy the spec pack into `spec-source/`.

## Overridden Files

Every full-file override names the newest ancestor that owned the path — the house defense
against the shadowed-layer trap. "new" = no ancestor carries the path.

| File | Ancestor | YU16 delta |
|---|---|---|
| `reference-data/package.json` | YU04 | instruments module wiring, test script |
| `reference-data/src/app.module.ts` | YU04 | + `InstrumentsModule` beside the retained `StocksModule` |
| `reference-data/src/stocks/stocks.controller.ts` | YU04 | + `/instruments/control-snapshot` sibling route (same handler contract) if routed here; otherwise unchanged retained |
| `reference-data/src/instruments/*` | new | CDM model, controller, service, module |
| `reference-data/src/data-loader/load-csv-data.ts` | YU04 | CDM classification, identifiers, Treasury seeds, assertions |
| `price-publisher/src/main.js` | YU15 | Treasury bootstrap/tick/payload branches, shared roll, 6-dp Treasury tick scaling |
| `price-publisher/src/treasury-pricing.js` | new | the walk model + YTM + maturity |
| `price-publisher/data/snapshot-prices.json` | YU15 | + five Treasury entries |
| `price-publisher/package.json` | YU15 | test script |
| `order-matcher/src/main/resources/application.properties` | YU11 | bootstrap default → `/instruments/control-snapshot`; ticker-list defaults |
| `order-matcher/src/main/java/.../cluster/ClusterGatewayMain.java` | YU13 | `UST-` face validation pre-submission |
| `order-matcher/src/main/java/.../cluster/RiskExtractCsv.java` | YU15 | schema 2, static join, coupon/maturity columns |
| `order-matcher/src/main/java/.../cluster/RiskExtractMain.java` | YU15 | instrument-static loader (header-name parsing), both render sites |
| `order-matcher/src/main/java/.../cluster/MatchingEngineClusteredService.java` | YU15 | ADR-060 grid derivation at registration + T_SYMBOL restore |
| `order-matcher/src/main/java/.../lmax/MatchingEngine.java` | YU13 | per-security book-grid override consulted at cold book creation |
| `order-matcher/src/test/java/.../cluster/RiskExtractTest.java` | YU15 | schema-2 signature, trailing columns, bond cross + restore proof |
| `trade-processor/src/main/java/.../model/Position.java` | composed base | 6-dp average (its setter silently rounded to 3dp) |
| `trade-processor/src/test/java/.../service/TradeServiceBookingTest.java` | YU02 | the price-scale invariant moves to 6 |
| `trade-processor/src/test/java/.../service/TradeServiceIdempotencyTest.java` | YU05 | widened constructor |
| `trade-service/src/test/java/.../controller/TradeOrderControllerTest.java` | YU02 | instruments URLs + Treasury validation cases |
| `trade-processor/src/main/java/.../service/TradeService.java` | YU05 | face-weighted Treasury average cost, Rejected landing, metadata-before-transaction |
| `trade-processor` models/config (`Trade`, `TradeOrder`, `TradeState`, `InstrumentMetadata`, `InstrumentMetadataClient`, `RuntimeConfig`) | mixed (see phase notes) | rejection fields, metadata client, clock/HTTP beans |
| `position-service/src/main/java/.../model/Trade.java` + `TradeRepository` | YU06-era baseline | rejection columns in reads |
| `trade-service/src/main/java/.../controller/TradeOrderController.java` | YU02 | fetch-instrument + Treasury order validation |
| `web-front-end/angular/main/app/...` | YU03 (order-ticket, trade.component.ts, order.model.ts), 014 (rest) | asset-class UI, Treasury semantics |
| `kubernetes-runtime/manifests/base/database-init-configmap.yaml` | YU15 | Rejected state, rejection columns, 6-dp price widening, 17017 seeds |
| `kubernetes-runtime/manifests/base/reference-data-deployment.yaml` | YU09 | supported-tickers env additions |
| `generation/kubernetes/cluster/*` | YU15 (not composed — full per-state copy) | ticker-list env additions |

This table is completed as phases land; a row whose exact ancestor is discovered to differ
during implementation is corrected here in the same commit that lands the file.

## Kubernetes Assets

The cluster-rig manifests under `generation/kubernetes/cluster/` are a full per-state copy (the
cluster tier does not compose manifests across layers); they start from YU15's and change only
environment lists. The composed single-BLP manifests change through `runtime-overrides/` as
listed above.
