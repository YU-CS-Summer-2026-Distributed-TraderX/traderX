# Functional Delta: YU16-cdm-instruments (vs YU15-eod-risk-extract)

Everything inherited from YU15 and its ancestry is carried forward unchanged unless listed here.

## Added

- A CDM-shaped instrument model on reference-data: `GET /instruments` and
  `GET /instruments/{instrumentKey}` serving `securityType` (`Equity`/`Fund`/`Debt`), the
  matching CDM sub-type, and CDM `AssetIdentifier` lists (`BBGTICKER`, `FIGI`, `Other`), with
  load-time CDM condition assertions.
- Ten instruments in the seed universe: five ETFs (`SPY, QQQ, IWM, VTI, GLD` —
  `Fund`/`ExchangeTradedFund`) and five fixed-rate U.S. Treasuries (`UST-20280630` …
  `UST-20560515` — `Debt`, real FIGIs, coupon, maturity, TreasuryDirect auction provenance).
- `/instruments/control-snapshot` — the general-name control snapshot, identical contract over
  the same store and watermark as `/stocks/control-snapshot`; the order-matcher risk bootstrap
  default repoints to it at this state's layer, and the two YU04 proofs probe it.
- Treasury pricing in price-publisher: term-profiled correlated walk with mean reversion and a
  band clamp, per-batch shared roll, approximate YTM in the payload, maturity quote suppression,
  `UST-` unknown-key 404 with no fallback, fraction-of-par emission at six-decimal tick
  precision.
- Face-amount order validation for `UST-` keys at the cluster gateway REST boundary and in the
  UI tickets (≥100, multiple of 100, exact messages).
- Treasury booking semantics in trade-processor: face-weighted average cost, `Rejected` trade
  state with `rejectionReason`/`sourceOrderId`, fail-closed metadata resolution before the
  transaction with configurable timeouts.
- Extract enrichment by join: `instrumentType` gains `TREASURY`, Treasury rows carry `coupon`
  and `maturityDate`, the CSV schema bumps to 2, and the consumer guide documents it.
- Frontend: asset-class filter, grouped selectors, Treasury labels and validation, clean-value
  estimation, coupon/maturity/YTM display, percent formatting, rejected-trade display.

## Changed

- The instrument display name travels as `displayName` on the CDM view while remaining
  `companyName` on `/stocks` and both control snapshots — two wire names, one attribute, so the
  YU04 feed contract does not move.
- SQL price columns that can carry a bond mark widen from three to six decimals
  (`Trades.Price`, `Positions.AverageCostBasis`).
- The extract CSV schema identifier moves from 1 to 2 (column additions only; every schema-1
  column is unchanged in name, position and meaning).

## Removed

- Nothing. In particular `/stocks` and `/stocks/{ticker}` are NOT removed — this state
  supersedes source pack 016's FR-01602 and does not adopt its SC-01607; retention is
  requirement FR-CDM09, with the YU04 durable control feed as the attached reason.
