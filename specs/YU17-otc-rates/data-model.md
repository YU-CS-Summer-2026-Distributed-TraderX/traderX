# Data Model: YU17-otc-rates

Everything inherited from `YU16-cdm-instruments` is unchanged. This state adds two command types,
one replicated store, one snapshot record, one cut section and one artifact.

## 1. The booking command — `TYPE_SWAP_BOOK` (12)

Rides the inherited `InputEventMessage` (SBE template 1) on the unchanged 64-byte record.
`AeronReplicationCodec` copies `commandType` through without interpreting it.

| Field | Slot | Type | Meaning |
|---|---|---|---|
| booking account | `accountId` | int | unchanged |
| direction | `side` | byte | `0` = receive fixed, `1` = pay fixed |
| notional | `qty` | int | whole currency units, `1..2147483647` |
| fixed rate | `limitPx` | long | annual decimal fraction × 1e6 (0.042 → 42000) |
| idempotency key | `priceTicks` | long | `clientOrderKey`; 0 = no key |
| conventions | `securityId` | int | index into the convention table, NOT a symbol id |
| effective date | `orderRef` bits 0-15 | uint16 | epoch day |
| maturity date | `orderRef` bits 16-31 | uint16 | epoch day |

Epoch day 65535 is 2149-06-06. The gateway refuses a date outside that range before sequencing;
`InputEvent.setSwapDates` masks, so an out-of-range day would otherwise wrap into a plausible date.

## 1b. The swaption command — `TYPE_SWAPTION_BOOK` (13)

Every slot above keeps its meaning, because a swaption's underlying IS a swap: `fixedRate` is the
strike, `side` is the direction of the underlying's fixed leg (`1` = a payer swaption), `qty` is the
underlying notional. The option wrapper replaces the plain convention index in `securityId`:

| Field | Slot | Type | Meaning |
|---|---|---|---|
| conventions | `securityId` bits 0-7 | uint8 | index into the convention table |
| exercise style | `securityId` bits 8-15 | uint8 | index into the exercise-style table |
| expiry date | `securityId` bits 16-31 | uint16 | epoch day, refused past 2149-06-06 |

A swap sets only the low byte, so both products read the convention index the same way and no
branch is needed. Expiry `0` is therefore what a swap carries — which is exactly why the product is
the command type and never "is the expiry set".

## 2. The convention table

Compiled into the binary, addressed by index, stored nowhere. Append only: an index that has been
journaled keeps its meaning permanently.

| Index | Name | Float index | Frequency | Day count | Currency |
|---|---|---|---|---|---|
| 0 | `USD-SOFR-1Y-ACT360` | `USD-SOFR` | `1Y` | `ACT/360` | USD |
| 1 | `USD-SOFR-3M-ACT360` | `USD-SOFR` | `3M` | `ACT/360` | USD |
| 2 | `EUR-ESTR-1Y-ACT360` | `EUR-ESTR` | `1Y` | `ACT/360` | EUR |
| 3 | `GBP-SONIA-1Y-ACT365F` | `GBP-SONIA` | `1Y` | `ACT/365F` | GBP |
| 4 | `JPY-TONA-1Y-ACT365F` | `JPY-TONA` | `1Y` | `ACT/365F` | JPY |

An index outside the table aborts the render, naming the index and the range this build knows.

### Exercise style

The second index-addressed table, under the same append-only rule.

| Index | Style |
|---|---|
| 0 | `EUROPEAN` |
| 1 | `BERMUDAN` |
| 2 | `AMERICAN` |

A style is a TERM, not lifecycle: no exercise event is modelled, but two swaptions identical in
every other column are different instruments if their styles differ.

## 3. The contract store (replicated state)

Held in `MatchingEngineClusteredService`, beside the symbol table. Capacity `MAX_CONTRACTS` = 4096;
at capacity a booking is refused with `RiskReason.CAPACITY`. Nothing removes a contract.

| Column | Type | Meaning |
|---|---|---|
| `contractId` | long | the consensus sequence the booking landed at |
| `accountId` | long | booking account |
| `payFixed` | long | `0` = receives fixed, `1` = pays fixed |
| `notional` | long | whole currency units |
| `fixedRateTicks` | long | annual decimal fraction × 1e6 |
| `conventionIndex` | long | index into the table above |
| `effectiveEpochDay` | long | of the underlying swap |
| `maturityEpochDay` | long | of the underlying swap |
| `productType` | long | `0` = SWAP, `1` = SWAPTION |
| `expiryEpochDay` | long | `0` for a swap |
| `exerciseStyle` | long | `0` for a swap |

Order is booking order, which is ascending `contractId`. That order is load-bearing twice: the cut
iterates it, and the idempotency table remembers a contract by its INDEX in this list.

## 4. Snapshot — `T_CONTRACT` (12), format 6

One record per contract, written after `T_IDEMPOTENCY` (which stores the index into this list) and
carrying the eleven columns above as longs, in booking order.

Restore fails closed on a `contractId` that is not a sequence at or below the restored applied
sequence, and on records out of ascending id order.

**Width is read from the FORMAT, not the record.** `onSnapshotRecord` receives a buffer and an
offset and no length, so nothing in the record says how wide it is. A format-5 record carries eight
columns and restores as a SWAP with an empty wrapper; reading it at eleven would take the following
record's bytes as an expiry and a style — a silently wrong contract rather than a failure.

`SNAPSHOT_FORMAT` = 6; `MIN_READABLE_SNAPSHOT_FORMAT` = 3, unchanged.

## 5. The cut — schema 3

```
#cut schema=3 seq=<N> sessionDateEpochDay=<D> priceVersion=<V> rows=<R> contracts=<C>
accountId,security,quantity,avgCostTicks,contractMultiplier,lastTradePxTicks
… R position rows …
#contracts
contractId,accountId,payFixed,notional,fixedRateTicks,conventionIndex,effectiveEpochDay,maturityEpochDay,productType,expiryEpochDay,exerciseStyle
… C contract rows …
```

The `#contracts` section is present even at `contracts=0`. Position rows are sorted
`(accountId, securityId)`; contract rows are in ascending `contractId`.

## 6. The contracts artifact — `seq-<N>-contracts.csv`, schema 2

Preamble lines carry `consensusSequence`, `sessionDate`, `priceSnapshotVersion`, `cutSha256`,
`contracts`, and the conventions that state what the file does and does not contain.

| Column | Source |
|---|---|
| `contractId` | `SW-<contractId>` for a swap, `SWPT-<contractId>` for a swaption |
| `accountId` | cut |
| `payReceive` | `PAY_FIXED` / `RECEIVE_FIXED` from `payFixed` |
| `notional` | cut, whole currency units |
| `fixedRate` | cut ticks rendered at six decimals |
| `floatIndex` | convention table |
| `effectiveDate` | cut epoch day as ISO date |
| `maturityDate` | cut epoch day as ISO date |
| `paymentFrequency` | convention table |
| `dayCount` | convention table |
| `currency` | convention table — the CONTRACT's currency, not the account's base currency |
| `counterpartyId` | `counterparties.csv` by account |
| `nettingSetId` | `counterparties.csv` by account |
| `productType` | `SWAP` or `SWAPTION` |
| `expiryDate` | the swaption's expiry; empty for a swap |
| `exerciseStyle` | `EUROPEAN` / `BERMUDAN` / `AMERICAN`; empty for a swap |

Every column left of `productType` describes the UNDERLYING swap, which is why a swaption row and a
swap row share them: for a swaption, `fixedRate` is the strike.

No valuation column exists. No NPV, mark, discount factor, curve, par rate or sensitivity.

## 7. The netted position extract — schema 3, unchanged

Every column keeps its name, position and meaning. The reader stops at the first `#`-prefixed line
after the position rows, so the contracts section is never parsed as positions and no swap row can
appear in this file.
