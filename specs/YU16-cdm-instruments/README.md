# Feature Pack: YU16-cdm-instruments

![linux/mac support](https://badgen.net/badge/linux%2Fmac/supported/green?icon=linux) ![windows support](https://badgen.net/badge/windows/not%20supported/red?icon=windows)

Status: In implementation — see generation/implementation-status.md
Track: `functional`
Lineage role: `optional`
Previous state: `YU15-eod-risk-extract`

The FINOS CDM instrument model folded onto this line: reference-data serves CDM-shaped
instruments — `Equity`, `Fund` and `Debt` with FIGI identifiers — covering five ETFs and five
fixed-rate U.S. Treasuries with real auction provenance, while `/stocks` and the YU04 durable
control feed keep serving unchanged. Bond prices are stored as a fraction of par on every
internal surface, so a Treasury's arithmetic is an equity's arithmetic, the contract multiplier
stays 1, and the deterministic core does not change: no snapshot field, no format bump, no
fresh epoch. The state folds two source packs (`016-cdm-generic-instruments`,
`017-us-treasury-trading`) and declares its divergences from them by source id.

Primary intent:

- CDM `securityType` + sub-type discriminators + `AssetIdentifier` lists (`BBGTICKER`, `FIGI`,
  `Other`) on a flat instrument record, asserted at seed load.
- Five ETFs (`SPY, QQQ, IWM, VTI, GLD`) and five Treasuries (`UST-20280630` … `UST-20560515`)
  tradable through the inherited paths.
- `/stocks` retained; `/instruments/control-snapshot` added over the same store and watermark;
  the risk bootstrap repointed by configuration (ADR-058).
- Fraction-of-par bond prices at six-decimal precision, publisher → engine → read model →
  extract (ADR-057).
- Treasury pricing: term-profiled correlated walk, approximate YTM, maturity handling.
- Face-weighted average cost and a fail-closed `Rejected` trade landing in post-trade.
- Extract schema 2: `TREASURY` classification, coupon and maturity by join (ADR-059).

Core artifacts:

- `generation/runtime-overrides/reference-data/...` — the instruments module beside the
  retained stocks module
- `generation/runtime-overrides/price-publisher/...` — `treasury-pricing.js` + seeds + hooks
- `generation/runtime-overrides/trade-processor|position-service|trade-service/...` — the
  post-trade merge onto the operative ancestor copies
- `generation/runtime-overrides/order-matcher/...` — gateway face validation,
  `application.properties` bootstrap repoint, extract join, and the ADR-060 derived bond book
  grid (the state's one deterministic-core change; nothing stored, format 4 intact)
- `generation/runtime-overrides/kubernetes-runtime/manifests/base/database-init-configmap.yaml`
  — the MariaDB schema at this layer
- `reference-data/instruments.csv` — seed + extract-join static
- `system/adr-057 … adr-060`, `system/architecture.model.json`

Target runtime behavior:

- `GET /instruments/UST-20360515` returns `Debt` with coupon 4.375, maturity 2036-05-15 and its
  FIGI; `GET /stocks` still returns 200.
- A face-100,000 Treasury order at limit 0.998860 books through the unchanged cluster and shows
  as 99.886% in the blotter.
- `pricing.UST-*` carries `cleanPrice` as a fraction with `CLEAN_FRACTION_OF_PAR` semantics and
  a publisher-computed YTM; the binary tick preserves all six decimals.
- The extract classifies the bond row `TREASURY` with coupon and maturity, at `schema=2`,
  byte-reproducible as before.
- The full inherited proof suite stays green on a rig rolled without an epoch change or a PVC
  wipe.
