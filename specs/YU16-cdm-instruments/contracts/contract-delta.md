# Contract Delta: YU16 over YU15-eod-risk-extract

All inherited order/trade/position/risk/post-trade/EOD REST, NATS, FIX and cluster contracts are
retained. `/stocks` is retained. Every delta below is additive except the two declared
identifier changes (extract schema 1 → 2; SQL price columns 3 dp → 6 dp), both widenings.

## 1. reference-data REST (extended)

| Route | Change | Shape |
|---|---|---|
| `GET /instruments` | new | `200: Instrument[]` — CDM-shaped records (see data-model.md) |
| `GET /instruments/{instrumentKey}` | new | `200: Instrument` \| `404` |
| `GET /instruments/control-snapshot` | new | identical contract to `/stocks/control-snapshot`: same watermark fields, same `{ticker, companyName}` rows over the same store and outbox watermark; additive fields permitted |
| `GET /stocks`, `GET /stocks/{ticker}` | **retained, unchanged** | supersedes source pack 016 FR-01602; source SC-01607 (`GET /stocks` → 404) not adopted |
| `GET /stocks/control-snapshot` | retained, unchanged | the YU04 durable control feed's bootstrap source |
| `POST /stocks` | retained, unchanged | the YU04 write path; new instruments created through it flow to both snapshots |

The route universe is wider than the stock universe: `/instruments` serves ETFs and Treasuries;
`/stocks` serves every row it served before plus the new keys (same store), with its inherited
`{ticker, companyName}` shape.

## 2. Control feed (content only)

Stream `TRADERX_CONTROL_SECURITY`, subject `traderx.control.security.deltas`, delta payload
`{ticker, companyName}`, watermark semantics: all unchanged. The ten new instrument keys appear
as ordinary rows. `risk.bootstrap.securities-snapshot-url` defaults to
`/instruments/control-snapshot` at this state's layer (property default; the env override and
the YU04 `@Value` fallback are untouched).

## 3. Pricing feed (extended payloads, Treasury subjects only)

Subjects unchanged (`pricing.<instrumentKey>`, `pricing-tick-bin.<instrumentKey>`; `UST-*` has
no dot, one token). Treasury JSON payloads extend the inherited tick shape additively:

| Field | Meaning |
|---|---|
| `assetClass` | `US_TREASURY` |
| `cleanPrice` | fraction of par, equal to `price` |
| `priceSemantics` | `CLEAN_FRACTION_OF_PAR` |
| `approximateYtmPercent` | publisher-computed, `null` at/after maturity |
| `quoteTimestamp` | equal to `asOf` — one instant per payload |
| `maturityDate`, `matured`, `simulated`, `officialSeedCleanPrice` | static/provenance echoes |

Binary companion tick = `round(fraction × 1e6)` — six decimals preserved; the inherited
3-decimal HALF_UP rounding remains the equity/option contract. A matured Treasury's payloads
are suppressed. Unknown `UST-` keys: HTTP 404 from the price API, no fallback quote.

## 4. Cluster gateway REST (validation only)

`POST /orders` body and response shapes unchanged; the `ticker`→`security` field fallback is
untouched. For `UST-`-prefixed instruments the gateway rejects, before submission to the
cluster, a quantity below 100 or not a multiple of 100 — error body carries the exact messages
"Treasury quantity must be at least 100." / "Treasury quantity must be a multiple of 100.".
`limitPrice` for a Treasury is the fraction of par (e.g. `0.998860`).

## 5. Trades (extended)

Trade records and `/trades`-family payloads permit `state: Rejected` with `rejectionReason` and
`sourceOrderId`. A rejected trade is published on `/accounts/<id>/trades`; no position message
follows it. Treasury trade `quantity` is face; `price` is fraction of par at 6 dp.

## 6. SQL schema (widened + extended)

`Trades`: state check gains `'Rejected'`; new `RejectionReason VARCHAR(255)`,
`SourceOrderId VARCHAR(50)`; `Price DECIMAL(18,3) → DECIMAL(18,6)`. `Positions`:
`AverageCostBasis DECIMAL(18,3) → DECIMAL(18,6)`. Seeds add account 17017, its three users,
five settled Treasury trades and positions (fraction-of-par prices). No new tables.

## 7. Risk extract (schema 3)

The delivered CSV bumps `# traderx-risk-extract schema=3`; `risk.extract.ready` announces
`schema: 3`. Four columns append after `nettingSetId`, all populated for Treasury rows and empty
otherwise:

- `coupon`, `maturityDate` (schema 2, ADR-059) — joined from the state's instrument static.
- `lastCouponDate`, `accruedInterestFraction` (schema 3, ADR-061) — **derived** from that same
  static plus the session date, not joined; `instruments.csv` is unchanged by the schema-3 bump.

`instrumentType` gains the value `TREASURY`, derived by join against the state's instrument
static. Every schema-1 and schema-2 column keeps its name, position and meaning; bond
`costBasis`/`closingMark` are fractions of par, and so is `accruedInterestFraction`, so
`closingMark + accruedInterestFraction` is the dirty price. `marketValue`/`unrealizedPnl` stay
clean. The `.cut` sidecar stays `#cut schema=1` — engine state, unchanged engine.

`accruedInterestFraction` is the one value in the fixture that rounds (HALF_EVEN at 6 dp);
`elapsed/period` does not terminate, so the exact-or-abort rule cannot apply to it. Rounding is
deterministic, so byte-identical-across-members is unaffected.

## 8. UI (extended)

Asset-class filter and grouped selectors; Treasury tickets label quantity as Face Amount and
price as Limit Clean Price (% of par); clean value estimated as `face × fraction`; bond prices
render as `fraction × 100` with `%` and no `$`; blotters show coupon/maturity/approximate YTM
and a rejected trade's reason.

## Not changed

- Every NATS subject name and the durable stream (`TRADERX_CONTROL_SECURITY`) — NFR-CDM06.
- The engine's ingress/egress SBE contracts, snapshot format (4), idempotency semantics, risk
  gates, and the ack layout.
- The FIX endpoint, the EOD price/P&L flow, the tick-store capture subjects, and the
  `risk.extract.cut` payload.
- `/account/control-snapshot` on account-service (the sibling feed; out of scope here).
- The gateway's `ticker`-wins-over-`security` resolution and `#<securityId>` addressing.
