# Data Model: CDM Instruments

## Instrument record (served by reference-data)

Flat record; the CDM `Asset → Instrument → Security` choice tree is taxonomy documentation, not
a runtime discriminated union.

| Field | Type | Notes |
|---|---|---|
| `instrumentKey` | string | The transactional key everywhere — engine symbol, DB `Security` columns, subjects. Equities/funds: the ticker. Treasuries: `UST-<yyyymmdd>` maturity-keyed. No surrogate ids. |
| `displayName` | string | Human name on the CDM view. The same attribute travels as `companyName` on `/stocks` and both control snapshots (TD-CDM01). |
| `shortDisplayName` | string? | Treasuries only (`UST 2Y` … `UST 30Y`). |
| `assetClass` | `Stock` \| `ETF` \| `US_TREASURY` | Coarse display/filter classification. |
| `currency` | string | `USD` throughout this state. |
| `securityType` | `SecurityTypeEnum` | `Equity`, `Fund` or `Debt` here. |
| `equityType` | `EquityType?` | Present iff `securityType = Equity`. Wrapping type: `{ equityType: EquityTypeEnum, depositaryReceipt?: DepositaryReceiptTypeEnum }`. |
| `fundType` | `FundProductTypeEnum?` | Present iff `securityType = Fund`. Enum directly — the CDM asymmetry is preserved. |
| `debtEconomics` | `DebtEconomics?` | Present iff `securityType = Debt`. |
| `matured` | boolean | Treasuries: `maturityDate ≤ now` (UTC midnight, inclusive; honors `TRADERX_FIXED_UTC_INSTANT`). Always `false` for equities/funds. |
| `observedAt` | ISO instant | When the record was assembled. |
| `identifiers` | `AssetIdentifier[]` | Min 1. See identifier rules below. |

**CDM enums (literals quoted from `finos/common-domain-model` rosetta source):**
`AssetIdTypeEnum`: `BBGID, BBGTICKER, CUSIP, FIGI, ISDACRP, ISIN, Name, REDID, RIC, Other,
Sicovam, SEDOL, UPI, Valoren, Wertpapier, CurrencyCode, ExchangeCode, ClearingCode`.
`SecurityTypeEnum`: `Debt, Equity, Fund, Warrant, Certificate`.
`EquityTypeEnum`: `Ordinary, NonConvertiblePreference, DepositaryReceipt, ConvertiblePreference`.
`DepositaryReceiptTypeEnum`: `ADR, GDR, IDR, EDR`.
`FundProductTypeEnum`: `MoneyMarketFund, ExchangeTradedFund, MutualFund, OtherFund`.
There is no `TICKER` member — Bloomberg ticker symbology is `BBGTICKER`.

**Identifier rules (asserted at seed load; violation throws):**
- Equity/Fund: `BBGTICKER` equal to `instrumentKey`, plus `FIGI` where baked; a row with no
  resolvable FIGI keeps `BBGTICKER` only and logs a warning.
- Debt: `FIGI` plus `Other` equal to `instrumentKey`; a Debt record claiming `BBGTICKER` throws.
- Exactly one sub-type discriminator, agreeing with `securityType`.

**Seed classification map (OpenFIGI `securityType` → CDM):** `Common Stock` →
Equity/Ordinary; `REIT` → Equity/Ordinary (logged); `ETP` → Fund/ExchangeTradedFund; `ADR` →
Equity/DepositaryReceipt/ADR; `Preference` → Equity/NonConvertiblePreference; unknown → default
Equity/Ordinary with a warning, never silently dropped. Classification keys off `securityType`,
not `securityType2` (which reports "Mutual Fund" for SPY).

## DebtEconomics

| Field | Type | Notes |
|---|---|---|
| `debtType` | `US_TREASURY_NOTE` \| `US_TREASURY_BOND` | |
| `issuer` | string | `United States Department of the Treasury` |
| `fixedInterest` | object | `{ rateType: 'Fixed', couponRatePercent, couponFrequency: 'Semiannual' }` |
| `principalRepayment` | object | `{ style: 'Bullet', parAmount: 100 }` |
| `issueDate`, `maturityDate` | ISO date | |
| `originalTermYears` | `2 \| 5 \| 10 \| 20 \| 30` | |
| `priceProvenance` | object | `{ sourceType: 'US_TREASURY_AUCTION_RESULT', sourceUrl, officialCleanPrice, runtimeSeedCleanPrice, simulated: true }` — clean prices in provenance are quoted percent-of-par, as the auction PDFs state them; everything the runtime stores is fraction of par. |

## Seed universe additions

**Five ETFs** (`Fund` / `ExchangeTradedFund`):

| instrumentKey | displayName | FIGI |
|---|---|---|
| SPY | SPDR S&P 500 ETF Trust | BBG000BDTBL9 |
| QQQ | Invesco QQQ Trust | BBG000BSWKH7 |
| IWM | iShares Russell 2000 ETF | BBG000CGC9C4 |
| VTI | Vanguard Total Stock Market ETF | BBG000HR9779 |
| GLD | SPDR Gold Shares | BBG000CRF6Q8 |

**Five Treasuries** (`Debt`; quoted clean % of par from the TreasuryDirect auction results, and
the fraction the runtime stores):

| instrumentKey | short | coupon % | maturity | term | quoted clean % | stored fraction | FIGI |
|---|---|---|---|---|---|---|---|
| UST-20280630 | UST 2Y | 4.125 | 2028-06-30 | 2 | 99.878 | 0.998780 | BBG022ZR1Z79 |
| UST-20310630 | UST 5Y | 4.125 | 2031-06-30 | 5 | 99.665 | 0.996650 | BBG022ZR1Z51 |
| UST-20360515 | UST 10Y | 4.375 | 2036-05-15 | 10 | 99.257 | 0.992570 | BBG0221YLR31 |
| UST-20460515 | UST 20Y | 5.000 | 2046-05-15 | 20 | 98.481 | 0.984810 | BBG0226BZH97 |
| UST-20560515 | UST 30Y | 5.000 | 2056-05-15 | 30 | 99.293 | 0.992930 | BBG0221YLR40 |

Issue dates: 2026-06-30, 2026-06-30, 2026-05-15, 2026-06-01, 2026-05-15 respectively. Official
(unrounded) auction prices are carried in `priceProvenance.officialCleanPrice`
(99.878432 / 99.664909 / 99.256552 / 98.481099 / 99.292811).

## Price representation — the fraction-of-par convention

| Surface | Representation | Example (UST-20280630) |
|---|---|---|
| TreasuryDirect auction PDF / provenance | percent of par | 99.878 |
| price-publisher walk state + payload `cleanPrice`/`price` | fraction of par, 6 dp | 0.998780 |
| binary tick (`pricing-tick-bin.*`) and engine ticks | `round(fraction × 1e6)` | 998,780 |
| SQL `Trades.Price`, `Positions.AverageCostBasis` | fraction of par, `DECIMAL(18,6)` | 0.998780 |
| extract `costBasis`/`closingMark` | fraction of par, 6 dp | 0.998780 |
| UI display | fraction × 100, `%` sign, no `$` | 99.878% |

Contract multiplier for a Treasury is 1; notional is `face × fraction-ticks × 1` through the
unchanged risk gate. Valuation identities: `costValue = face × avgFraction`,
`marketValue = face × currentFraction`, `pnl = marketValue − costValue`. Face-weighted average
on a buy: `(oldAvg × oldFace + price × buyFace) ÷ newFace`; a sell preserves the average; a flat
position resets it to zero.

## Treasury pricing model (price-publisher)

Term profiles (per-step ceiling and total band, in percent-of-par space; the walk runs in
percent space and converts to fraction at emission):

| term | maxStep | maxDistance |
|---|---|---|
| 2 | 0.005 | 0.15 |
| 5 | 0.010 | 0.30 |
| 10 | 0.020 | 0.50 |
| 20 | 0.035 | 0.75 |
| 30 | 0.050 | 1.00 |

Step: `change = maxStep × (0.8 × sharedRoll + 0.2 × localRoll) + 0.02 × (seed − current)`, then
clamp to `seed ± maxDistance`, round to 3 dp in percent space (= 5 dp in fraction space, inside
the 6-dp budget). One shared roll per publish batch correlates the curve; the local roll breaks
lockstep. Approximate YTM (percent space, per 100 par):
`((coupon + (100 − clean)/years) / ((100 + clean)/2)) × 100`, `years = (maturity − quote) /
365.25d`, `null` at or after maturity.

## SQL schema delta (MariaDB `database-init-configmap.yaml`, this state's layer)

- `Trades`: state check gains `'Rejected'`; `+ RejectionReason VARCHAR(255)`,
  `+ SourceOrderId VARCHAR(50)`; `Price` widens `DECIMAL(18,3) → DECIMAL(18,6)`.
- `Positions`: `AverageCostBasis` widens `DECIMAL(18,3) → DECIMAL(18,6)`.
- Seeds: account `17017` ("U.S. Treasury Trading Account"), users `user02`/`user08`/`user10`,
  five settled trades `SEED-17017-<maturity>` and five positions at face 100,000 — prices stored
  as fractions (`0.998780` …), never percentages.
- No new tables. No `OrderBook` `Pending*` columns (dropped machinery). `Security` columns
  already hold OCC-width strings (YU15 FR-RXT16), which covers `UST-<yyyymmdd>` at 12 chars.

## Extract fixture (schema 3)

Header comment gains the bond and accrual conventions; column list becomes:

```
accountId,security,instrumentType,quantity,contractMultiplier,costBasis,closingMark,markSource,
markQuality,marketValue,unrealizedPnl,currency,counterpartyId,nettingSetId,coupon,maturityDate,
lastCouponDate,accruedInterestFraction
```

- `instrumentType`: `EQUITY` \| `OPTION` \| `TREASURY` — options by OCC shape as before;
  `TREASURY` by join against the instrument static (never by prefix-parsing inside the cut).
- `coupon`, `maturityDate` (schema 2): populated for Treasury rows from the joined static; empty
  for equities and options.
- `lastCouponDate`, `accruedInterestFraction` (schema 3, ADR-061): Treasury rows only, **derived**
  from the joined static plus `sessionDate` rather than joined — the static is unchanged by this
  bump. Schedule is generated backwards from `maturityDate` in 6-month steps measured from the
  maturity anchor (so no issue date is needed, and no short/long first coupon is modelled);
  day count is ACT/ACT (ICMA); accrual runs to `sessionDate`, not to a settlement date.
- Bond `costBasis`/`closingMark`/`marketValue` are fractions of par at 6 dp; `quantity` is face.
  `accruedInterestFraction` is in that same unit, so `closingMark + accruedInterestFraction` is
  the dirty price. `marketValue`/`unrealizedPnl` remain **clean**.
- `accruedInterestFraction` is the only value in the fixture that rounds (HALF_EVEN at 6 dp) —
  `elapsed/period` does not terminate, so the exact-or-abort rule cannot cover it. Deterministic,
  so byte-identical-across-members is unaffected.
- The `.cut` sidecar is unchanged at `#cut schema=1` — the cut is engine state and the engine
  did not change; only the rendered CSV (a join product) bumps.

## Instrument static (`reference-data/instruments.csv`, rendered into order-matcher resources)

Base columns unchanged from YU15; gains `securityType`, `figi`, `couponRatePercent`,
`maturityDate` so the extract join can classify and decorate without a service call. Rows added
for the five ETFs and five Treasuries.

## Read, not written

- The engine's snapshot records: format 4, untouched — no instrument-type field exists or is
  added (`instrumentType` stays derived/joined downstream).
- The control-feed delta payload: `{ticker, companyName}` — Treasuries flow through it unchanged
  in shape.
- `counterparties.csv`: inherited from YU15 verbatim.
