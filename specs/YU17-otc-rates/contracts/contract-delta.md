# Contract Delta: YU17 over YU16-cdm-instruments

All inherited order/trade/position/risk/post-trade/EOD REST, NATS, FIX and cluster contracts are
retained unchanged. Every delta below is additive except two schema identifiers of this state's own
artifacts: the cut (1 → 3) and the contracts file (1 → 2). Both are appends — every existing line
and column keeps its position and meaning.

## 1. Cluster gateway REST (extended)

| Route | Change | Shape |
|---|---|---|
| `POST /swaps` | new | request below; `200: {contractId, sequence, booked:true}` \| `400: {error}` \| `422: {booked:false, reason}` \| `504: {error}` |
| `POST /swaptions` | new | the same body plus `expiryDate` and `exerciseStyle`; same status codes, `contractId` is `SWPT-<N>` |

Request body:

```json
{
  "clientOrderId": "recv-1",
  "accountId": 22214,
  "payReceive": "Receive",
  "notional": 10000000,
  "fixedRate": 0.042,
  "effectiveDate": "2026-08-17",
  "maturityDate": "2031-08-17",
  "conventions": "USD-SOFR-1Y-ACT360"
}
```

- `payReceive` is `Pay` or `Receive`, case-insensitive, naming the direction of the FIXED leg.
- `notional` is whole currency units, `1..2147483647`.
- `fixedRate` is an annual decimal fraction; `0.042` is 4.2%. Zero is refused.
- Dates are ISO `yyyy-MM-dd`, between `1970-01-01` and `2149-06-06`, maturity strictly after
  effective.
- `conventions` is a name from the table in `data-model.md`; an unknown name is refused.
- `clientOrderId` is optional. Supplied, it makes the booking idempotent: a repeat returns the
  original `contractId`. Omitted, each request is a distinct contract.

A swaption body adds two fields and reinterprets none:

```json
{
  "clientOrderId": "swpt-1",
  "accountId": 22214,
  "payReceive": "Pay",
  "notional": 25000000,
  "fixedRate": 0.0415,
  "effectiveDate": "2027-02-15",
  "maturityDate": "2032-02-15",
  "conventions": "USD-SOFR-1Y-ACT360",
  "expiryDate": "2027-02-15",
  "exerciseStyle": "European"
}
```

Every field except the last two describes the UNDERLYING swap: `fixedRate` is the strike and
`payReceive` is the direction of the underlying's fixed leg, so `"Pay"` is a payer swaption.
`exerciseStyle` is `European`, `Bermudan` or `American`, case-insensitive; an unknown style is 400.
`expiryDate` must be on or before `effectiveDate` — an option that expires after the swap it is an
option on has nothing to be exercised into.

Status codes carry different meanings and are not interchangeable: **400** the term could not be
represented and nothing was sequenced; **422** the booking was sequenced and the risk gate decided
against it, with the reason named; **504** no decision committed within the timeout — ambiguous,
not a rejection.

## 2. Cluster ingress (extended)

`InputEventMessage`, SBE template 1, schema version unchanged. New `commandType` value:

| Value | Name | Payload |
|---|---|---|
| 12 | `TYPE_SWAP_BOOK` | `accountId`; `side` 0=receive-fixed / 1=pay-fixed; `qty`=notional; `limitPx`=fixed rate ×1e6; `priceTicks`=`clientOrderKey`; `securityId`=convention index; `orderRef`= effective epoch day in bits 0-15, maturity epoch day in bits 16-31 |
| 13 | `TYPE_SWAPTION_BOOK` | every slot above keeps its meaning for the UNDERLYING swap; `securityId` becomes the option word — convention index in bits 0-7, exercise style in bits 8-15, expiry epoch day in bits 16-31 |

No new SBE template and no template id claimed.

## 3. Cluster egress (extended)

| Kind | Name | Bytes |
|---|---|---|
| 102 | `KIND_SWAP_BOOKED` | `0..7` contract id (0 when refused); `8..11` 1=booked / 0=refused; `12` kind; `13..20` `clientOrderKey` for correlation; `22` `RiskReason` ordinal |

Deliberately outside `OutputEvent`'s 1..8 range, so it can never be mistaken for an order-lifecycle
ack by the gateway's egress correlation.

## 4. The cut (schema 2 → 3)

Header gains one field and the body gains one section, whose rows gain the three option columns.
Everything above the marker is byte-identical to what schema 1 rendered for the same state — no
position column has ever changed.

```
#cut schema=3 seq=<N> sessionDateEpochDay=<D> priceVersion=<V> rows=<R> contracts=<C>
accountId,security,quantity,avgCostTicks,contractMultiplier,lastTradePxTicks
… R position rows, sorted (accountId, securityId) …
#contracts
contractId,accountId,payFixed,notional,fixedRateTicks,conventionIndex,effectiveEpochDay,maturityEpochDay,productType,expiryEpochDay,exerciseStyle
… C contract rows, ascending contractId …
```

The `#contracts` section is present at `contracts=0`. A cut without it is refused by the contracts
renderer with a message saying the producer is older — never rendered as an empty portfolio.

## 5. The EOD artifacts

| Object | Change | Schema |
|---|---|---|
| `<date>/v<version>/seq-<N>.cut` | unchanged path, schema-3 content | cut schema 3 |
| `<date>/v<version>/seq-<N>.csv` | **unchanged**, every column identical | CSV schema 3 |
| `<date>/v<version>/seq-<N>-contracts.csv` | new | contracts schema 2 |

Contracts artifact columns:

```
contractId,accountId,payReceive,notional,fixedRate,floatIndex,effectiveDate,maturityDate,paymentFrequency,dayCount,currency,counterpartyId,nettingSetId,productType,expiryDate,exerciseStyle
SW-22940,22214,RECEIVE_FIXED,25000000,0.041500,USD-SOFR,2027-02-15,2032-02-15,1Y,ACT/360,USD,CPTY-CASCADE-AM,NS-CASC-ISDA-01,SWAP,,
SWPT-22939,22214,PAY_FIXED,25000000,0.041500,USD-SOFR,2027-02-15,2032-02-15,1Y,ACT/360,USD,CPTY-CASCADE-AM,NS-CASC-ISDA-01,SWAPTION,2027-02-15,BERMUDAN
```

Every column left of `productType` describes the UNDERLYING swap, which is what lets both products
share one file: a SWAP row simply leaves the two option columns empty.

`currency` is the CONTRACT's, from its conventions — not the account's base currency. No valuation
column exists, by design (ADR-064, ADR-065).

All three objects are written write-once under one consensus sequence. On the GCS sink they are
delivered in one call.

## 6. `risk.extract.ready` (extended)

Existing fields unchanged; four added:

```json
{
  "schema": 3, "uri": "…/seq-2900.csv", "sha256": "…", "rows": 4,
  "consensusSequence": 2900, "sessionDate": "2026-08-17", "priceSnapshotVersion": 3,
  "cutSha256": "…", "quiesceWitnessSequence": 2901,
  "contractsSchema": 2, "contractsUri": "…/seq-2900-contracts.csv",
  "contracts": 2, "contractsSha256": "…"
}
```

`consensusSequence`, `sessionDate` and `cutSha256` are shared by both artifacts: one cut, one
instant, two files.

## 7. Rebuild CLI (extended)

```
RiskExtractMain --rebuild <cut> <positions.csv> [<contracts.csv>]
```

The fourth argument is optional and additive; the three-argument form behaves exactly as before.

## 8. Snapshot

`SNAPSHOT_FORMAT` 4 → 5 for the new `T_CONTRACT` (12) record, then 5 → 6 for its three
option-wrapper columns. `MIN_READABLE_SNAPSHOT_FORMAT` unchanged at 3, so format-3, -4 and -5
snapshots all restore here — a format-5 `T_CONTRACT` is read at its own eight-column width and
restores as a SWAP. A format-6 snapshot handed to an older build is refused at the header with the
existing direction-of-mismatch message.
