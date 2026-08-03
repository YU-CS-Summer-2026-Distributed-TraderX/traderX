# Data Model: YU14-listed-equity-options

## Instrument (reference-data view)

One row per tradeable security. Derived fields come from the OCC identifier; nothing here is
stored in the cluster beyond the ticker→securityId mapping and the multiplier.

| Field | Source | Equity example | Option example |
|---|---|---|---|
| `ticker` | seeded identifier | `AAPL` | `AAPL260918C00240000` |
| `securityId` | cluster symbol registration | 3 | 21 |
| `type` | derived: OCC parse | `equity` | `option` |
| `underlying` | derived: OCC root | — | `AAPL` |
| `expiry` | derived: OCC yymmdd | — | `2026-09-18` |
| `callPut` | derived: OCC C/P | — | `C` |
| `strike` | derived: OCC 8 digits / 1000 | — | `240.000` |
| `multiplier` | derived at registration; **cluster state** | 1 | 100 |
| `currency` | reference data | `USD` | `USD` |

`reference-data/instruments.csv` materializes this view for the seeded universe.

## Counterparty (reference-data)

`reference-data/counterparties.csv`, keyed by accountId; joined to positions at extract time.

| Field | Meaning |
|---|---|
| `accountId` | the engine's account identifier |
| `counterpartyId` | counterparty legal-entity identifier (`CPTY-…`) |
| `nettingSetId` | netting set / CSA grouping (`NS-…`) |
| `currency` | settlement currency, `USD` |

## Derived notional

Wherever positions are surfaced from reference data:

```
notional = position quantity x last price x contract multiplier
```

Order reservation and executed exposure inside the risk gate are stored already-multiplied
(`quantity x validation price x multiplier`), so in-cluster credit/order-notional/concentration
accounting and the derived extract field agree by construction.

## Cluster state delta (vs YU13)

| State | YU13 | YU14 |
|---|---|---|
| per-security risk row | enabled, restricted, lastPrice, lastPriceTime | + `contractMultiplier` |
| snapshot security record (T_SECURITY) | 5 columns | 6 columns (format 3) |
| snapshot header format | 2 | 3; format ≠ 3 fails closed |
| restore validation | inherited | + multiplier < 1 fails closed |

Order rows, position rows, book records, symbol records, idempotency records: unchanged.

## SBE delta (vs YU12 schema)

`SymbolRegisterMessage` ticker field widens `char[16]` → `char[32]` (blockLength 24 → 40) so
unpadded OCC symbols (~19 chars) register; template id and message semantics are unchanged.
