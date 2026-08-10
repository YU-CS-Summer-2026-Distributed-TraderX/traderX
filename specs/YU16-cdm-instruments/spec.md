# Feature Specification: CDM Instruments

**Feature Branch**: `YU16-cdm-instruments`
**Created**: 2026-08-10
**Status**: In implementation
**Input**: Fold of source packs `016-cdm-generic-instruments` and `017-us-treasury-trading`, parented on `YU15-eod-risk-extract`

## User Stories

- As a trader, I want ETFs and U.S. Treasuries in the same selectors and blotters as equities —
  with honest labels for what the numbers mean (face amount, clean price as % of par, coupon,
  maturity, approximate YTM) — so I trade the wider universe without learning a second workflow.
- As a risk-engine consumer, I want the instrument's type and its bond static (coupon, maturity)
  on every extract row, so I can price a Treasury position without a second lookup.
- As the platform owner, I want the CDM instrument model added without any change to the
  deterministic core, because a matching engine that is price-time priority over integers has no
  business knowing what a coupon is — and because rolling the core cannot be done gradually.
- As the operator of the YU04 durable control feed, I want `/stocks/control-snapshot` to keep
  serving exactly what it serves today, because a replica bootstrap that fails on a renamed route
  is an outage bought for a tidier name.
- As a maintainer, I want the divergence from the folded source packs declared requirement by
  requirement, so keeping `/stocks` reads as a decision with a reason, not an oversight.

## Functional Requirements

### Instrument model (reference-data)

- FR-CDM01: reference-data SHALL expose `GET /instruments` and `GET /instruments/{instrumentKey}`,
  serving CDM-shaped instrument records; an unknown key SHALL return 404.
- FR-CDM02: An instrument record SHALL carry `instrumentKey`, `displayName`, `currency`,
  `securityType` (a CDM `SecurityTypeEnum` literal), its matching CDM sub-type discriminator, and
  `identifiers` (a list of CDM `AssetIdentifier` values); Treasuries additionally carry
  `shortDisplayName`, `assetClass`, `matured` and `debtEconomics`.
- FR-CDM03: `securityType` SHALL use CDM `SecurityTypeEnum` literals; this state serves `Equity`,
  `Fund` and `Debt`.
- FR-CDM04: Exactly one sub-type discriminator SHALL be present and SHALL agree with
  `securityType` — `equityType` for `Equity`, `fundType` for `Fund`, `debtEconomics` for `Debt` —
  enforced at seed load; a disagreement throws rather than loads.
- FR-CDM05: Identifiers SHALL use CDM `AssetIdTypeEnum` literals. Equities and funds carry
  `BBGTICKER` (equal to `instrumentKey`) plus `FIGI` where one is baked into the seed; a seed row
  with no resolvable FIGI keeps `BBGTICKER` only and logs a warning, and SHALL NOT fail startup.
  Treasuries carry `FIGI` plus an `Other` identifier equal to the instrument key, and SHALL NOT
  claim `BBGTICKER`.
- FR-CDM06: Identifiers SHALL be baked into seed data offline; the runtime SHALL NOT call an
  external symbology provider.
- FR-CDM07: The seed universe SHALL include the five ETFs `SPY, QQQ, IWM, VTI, GLD` as
  `Fund`/`ExchangeTradedFund`, and the five Treasuries `UST-20280630`, `UST-20310630`,
  `UST-20360515`, `UST-20460515`, `UST-20560515` as `Debt` with their real FIGIs, coupon, issue
  and maturity dates, original term, and TreasuryDirect auction price provenance.
- FR-CDM08: An ETF SHALL be tradable through the inherited order, trade and position paths exactly
  as an equity — same flow, same position row shape.

### `/stocks` retention and the control feed

- FR-CDM09: `/stocks` and `/stocks/{ticker}` SHALL remain served, unchanged in shape. This
  supersedes source pack 016's FR-01602 ("`/stocks` and `/stocks/{ticker}` SHALL be removed and
  SHALL NOT be aliased or redirected"), and its SC-01607 ("`GET /stocks` returns 404") is NOT
  adopted: `/stocks/control-snapshot` is the YU04 durable control feed's bootstrap source and is
  load-bearing for the `yu04-live-delta` and `yu04-offline-catchup` proofs.
- FR-CDM10: `/stocks/control-snapshot` SHALL keep its exact YU04 contract — watermark fields plus
  `{ticker, companyName}` rows — over the same store and outbox watermark as before.
- FR-CDM11: reference-data SHALL additionally expose `/instruments/control-snapshot`, serving the
  same control-snapshot contract over the same store and the same watermark. New fields are
  additive only; a reader of the `/stocks` snapshot can be repointed to it with no code change.
- FR-CDM12: The order-matcher risk bootstrap SHALL default to `/instruments/control-snapshot` at
  this state's layer — a configuration repoint of `risk.bootstrap.securities-snapshot-url`, not a
  code change — and the two YU04 proofs SHALL probe the general route. The proof-suite readiness
  gate SHALL keep probing `/stocks/control-snapshot`, as the standing check that retention holds.
- FR-CDM13: ETFs and Treasuries SHALL flow through the existing `SECURITY_CONTROL` feed as
  ordinary securities (`ticker` + `companyName`), so the engine registers them with no new
  command type and no feed-contract change.

### Bond price and quantity conventions

- FR-CDM14: Bond prices SHALL be stored as a fraction of par everywhere inside the system — the
  price publisher's emission, the engine's ticks, the read model's rows, and the extract — never
  as a percentage. A bond quoted 99.886% is stored `0.998860`, which is `998,860` ticks at the
  1e6 scale. The contract multiplier for a Treasury SHALL be 1, and notional SHALL be
  `quantity(face) × price(fraction ticks) × 1` through the unchanged engine risk gate.
- FR-CDM15: Bond marks SHALL keep six-decimal precision end to end: the pricing feed's binary
  tick for a Treasury SHALL equal `round(fraction × 1e6)` (the inherited 3-decimal equity
  rounding SHALL NOT apply to Treasury payloads), and every SQL column that carries a bond price
  SHALL hold six decimals.
- FR-CDM16: Treasury order quantity SHALL be a positive integer USD face amount, at least 100 and
  a multiple of 100, validated at the order-entry boundary (the gateway REST validation and the
  UI tickets) with the exact messages "Treasury quantity must be at least 100." and "Treasury
  quantity must be a multiple of 100.". Trade and position quantity columns carry face.
- FR-CDM17: Percent-of-par SHALL be display only: the UI multiplies the stored fraction by 100
  and appends the sign; nothing downstream of a display ever converts back.

### Treasury pricing (price-publisher)

- FR-CDM18: price-publisher SHALL seed the five Treasuries from their auction-derived clean
  prices and walk them with the term-profiled correlated model: per-batch shared roll weighted
  0.8 against a 0.2 local roll, mean reversion of `0.02 × (seed − current)`, and a hard clamp to
  `seed ± maxDistance`, where a longer original term has a larger `maxStep` and a wider
  `maxDistance` — so long-maturity prices move more, exactly as duration says they should.
- FR-CDM19: Treasury payloads on `pricing.<instrumentKey>` SHALL extend the inherited tick shape
  additively: `assetClass`, `cleanPrice` (fraction of par, equal to `price`),
  `priceSemantics: "CLEAN_FRACTION_OF_PAR"`, `approximateYtmPercent`, `quoteTimestamp` (equal to
  `asOf`), `maturityDate`, `matured`, `simulated`, `officialSeedCleanPrice`. Subject names and
  every inherited field are unchanged.
- FR-CDM20: Approximate YTM SHALL be computed only by the publisher —
  `((coupon + (par − clean)/yearsRemaining) / ((par + clean)/2)) × 100` in percent space, `null`
  at or after maturity, carrying the same quote timestamp as the price. The UI parses it and
  SHALL NOT compute it.
- FR-CDM21: A matured Treasury SHALL stop quoting — its payloads are suppressed, its stored quote
  no longer advances — and SHALL be rejected for new order entry at the validation boundary. An
  unknown `UST-`-prefixed key SHALL return 404 with no fallback quote; unknown equities keep the
  inherited lazy fallback.

### Post-trade

- FR-CDM22: trade-processor SHALL book Treasury trades with face-weighted average cost — a buy
  re-weights `(oldAvg × oldFace + price × buyFace) ÷ newFace`, a sell preserves the average, a
  flat position resets it — over the same asynchronous trade path equities use.
- FR-CDM23: Trades SHALL support a `Rejected` state with `rejectionReason` and `sourceOrderId`,
  persisted and published on the account trade subject with no position update — the fail-closed
  landing for booking-time validation failures, including Treasury reference metadata being
  unavailable (`UST-` routing is the discriminator; metadata must still confirm
  `US_TREASURY` + `Debt` before a Treasury booking).
- FR-CDM24: Booking-time Treasury metadata SHALL be resolved before the database transaction with
  configurable timeouts, and non-Treasury bookings SHALL do no metadata lookup.

### Risk extract

- FR-CDM25: The extract's `instrumentType` SHALL gain `TREASURY`, derived by join against the
  state's instrument static exactly as `counterpartyId` already is, and Treasury rows SHALL carry
  `coupon` and `maturityDate` from the same join. The `.cut` format SHALL NOT change.
- FR-CDM26: The extract CSV schema SHALL bump to 2, `risk.extract.ready` SHALL announce
  `schema: 2`, and `docs/engineering/risk-extract-consumer-guide.md` SHALL document the new
  columns and the bond-price convention.

### Frontend

- FR-CDM27: The UI SHALL offer an asset-class filter (All / Stocks / ETFs / U.S. Treasuries) on
  the instrument selectors and blotters, group the selector typeahead by asset class, label
  Treasury inputs honestly (Face Amount; Limit Clean Price (% of par)), estimate clean value as
  `face × fraction`, show coupon, maturity and approximate YTM for Treasuries, format bond prices
  as percentages with no currency prefix, and surface a rejected trade's reason in the blotter.

## Non-Functional Requirements

- NFR-CDM01: The deterministic core SHALL NOT change: no snapshot field, no new command type, no
  risk-gate change, no matching change. Instrument semantics live in reference-data, pricing,
  post-trade and display layers. `MatchingEngineClusteredService`, `BlpRiskState` and the codecs
  are byte-identical to YU15's layers.
- NFR-CDM02: `SNAPSHOT_FORMAT` SHALL remain 4 and `MIN_READABLE_SNAPSHOT_FORMAT` SHALL remain 3.
- NFR-CDM03: The state SHALL NOT require a fresh epoch or a PVC wipe; the running cluster's disks
  and epoch stay valid, and no mixed-version divergence window exists because the replicated
  state machine is unchanged.
- NFR-CDM04: Every inherited proof SHALL remain green — the full `scripts/yu15/run-proofs.sh`
  suite, the order-matcher suite, and all allocation and no-GC gates.
- NFR-CDM05: The adopted CDM subset SHALL be documented in `data-model.md` with enum literals
  quoted from CDM source. The runtime record stays flat — the CDM `Asset → Instrument → Security`
  choice tree is taxonomy documentation, not a runtime discriminated union.
- NFR-CDM06: No messaging subject SHALL be added, removed or renamed. The durable control feed
  keeps stream `TRADERX_CONTROL_SECURITY` and subject `traderx.control.security.deltas`.
- NFR-CDM07: No external symbology dependency, no CUSIP or ISIN values, no live Treasury API, no
  credential. FIGIs and auction prices are baked offline.
- NFR-CDM08: Bond arithmetic SHALL be exact — integer ticks and `BigDecimal`, never floating
  point — in the engine (unchanged), the read model and the extract.
- NFR-CDM09: The fixed-clock contract `TRADERX_FIXED_UTC_INSTANT` SHALL be honored by
  reference-data's `matured` flag and price-publisher's Treasury clock, so maturity behavior is
  testable at a chosen instant.

## Technical Debt Register

- TD-CDM01: One display-name attribute has two wire names: `companyName` on `/stocks` and both
  control snapshots (the YU04 feed reads it by that name), `displayName` on the CDM `/instruments`
  view. The Angular model and service keep their historic `Stock`/`getStocks` names with the new
  fields. Retiring `companyName` is a feed-consumer flag day, not a rename.
- TD-CDM02: The control-feed routes now have a general name (`/instruments/control-snapshot`) but
  the durable stream and subject keep their `SECURITY` names. Renaming a durable stream subject
  moves the consumer's position with it — a flag day this state deliberately does not buy.
- TD-CDM03: Maturity enforcement lives at the validation boundary and the publisher, not in the
  engine; an order injected by a path that skips validation would still match. The earliest seed
  maturity is 2028-06-30, so no seeded instrument can mature in a running session today.

## Success Criteria

- SC-CDM01: `GET /instruments` serves the full universe; the SPY row carries
  `securityType: Fund`, `fundType: ExchangeTradedFund`, and identifiers `BBGTICKER SPY` +
  `FIGI BBG000BDTBL9`; an equity row carries `securityType: Equity` with `equityType.equityType:
  Ordinary` and no `fundType`.
- SC-CDM02: `GET /instruments/UST-20360515` returns `securityType: Debt`, coupon 4.375,
  maturity `2036-05-15`, `FIGI BBG0221YLR31`, `matured: false`, and no `BBGTICKER` identifier.
- SC-CDM03: `GET /stocks` returns 200 with its inherited shape, directly and through the suite
  readiness gate.
- SC-CDM04: `/stocks/control-snapshot` and `/instruments/control-snapshot` both return 200 with
  the same watermark, rows include the ten new keys, and `yu04-live-delta` +
  `yu04-offline-catchup` pass probing the general route.
- SC-CDM05: A Treasury order at face 100,000 and limit 0.998860 books through the cluster; the
  position row shows quantity 100,000 and cost basis 0.998860; the blotter displays 99.886%.
- SC-CDM06: An ETF order books and produces a position row identically to an equity.
- SC-CDM07: `pricing.UST-*` payloads carry `cleanPrice` as a fraction with
  `priceSemantics: CLEAN_FRACTION_OF_PAR` and a YTM; the binary tick equals
  `round(fraction × 1e6)` with all six decimals intact.
- SC-CDM08: A Treasury order at face 50 is rejected with the minimum message; at face 150 with
  the multiple message — at the gateway REST boundary, before the engine sees either.
- SC-CDM09: An extract cut from a session holding a Treasury position carries `schema=2`, an
  `instrumentType` of `TREASURY` on the bond row with its coupon and maturity, and rebuilding the
  fixture from the stored cut reproduces identical bytes.
- SC-CDM10: The full proof suite passes on a rig rolled to this state's image with its PVCs and
  epoch intact; `SNAPSHOT_FORMAT` still reads 4.
- SC-CDM11: The order-matcher, trade-processor and position-service suites pass, including this
  state's new Treasury pricing, booking and validation tests.
