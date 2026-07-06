# Data Model: YU03 In-Memory Risk Gateway

All risk state is in-memory, preallocated, and primitive (NFR-IMRG02/04). Nothing here is a
database table — the MariaDB read model stays a CQRS projection and is never authoritative for
admission (FR-IMRG41).

## Tier-1 replica (`GatewayReplicaStore`) — edge, concurrent-read

| Structure | Key | Value | Notes |
|---|---|---|---|
| `accounts` | accountId (int) | `AccountRecord{enabled, version}` | seeded + control-fed |
| `securities` | ticker (norm.) | `SecurityRecord{securityId, enabled, halted, priceTicks, priceTimeMillis, version}` | id aligned to SymbolTable; `priceTicks = Long.MIN_VALUE` ⇒ no price (distinct from 0, FR-IMRG09) |
| `restrictions` | ticker | boolean | |
| scalars | — | `policyVersion, killSwitch, sourceEpoch, sourceVersion, highWatermark, ready` | monotonic versions assigned internally in slice 1 |

## Tier-2 authoritative state (`BlpRiskState`) — BLP thread only, preallocated

Open-addressing hash tables and dense arrays, all sized at construction:

| Array(s) | Indexed by | Holds |
|---|---|---|
| `accountIds` / `accountEnabled` | account hash slot | account presence + enabled flag |
| `reservedNotional` / `reservedBuyNotional` / `reservedSellNotional` | account slot | live reserved exposure (Px ticks) |
| `executedNotional` | account slot | filled/executed exposure counted against credit |
| `securityEnabled` / `securityRestricted` | securityId (dense) | control flags |
| `lastPrice` / `lastPriceTime` | securityId | sequenced price + event-carried source time |
| `exposureKeys` / `reservedBuyQtyByExposure` / `reservedSellQtyByExposure` | (account,security) hash | reserved open-order qty for position projection |
| `idempotencyKeys` / `idempotencyOrderRefs` / `idempotencyDecisions` | clientOrderKey hash | one immutable decision per key |
| `idempotencyRetentionKeys` | ring cursor | bounded FIFO frontier (oldest key evicted at capacity, FR-IMRG14/26) |
| `entitlementKeys` / `entitlementEnabled` | (principal,account) hash | unused until the auth roadmap item feeds principals (principalKey 0 skips) |
| scalars | — | `policyVersion, killSwitch, maxOrderQuantity, maxOrderNotionalTicks, creditLimitTicks, maxPositionQuantity, maxConcentrationNotionalTicks, priceMaxAgeMillis` |

**Per-order reservation** lives on the pooled `RestingOrder` (`reservedNotional`, `reservedQty`) via
`ReservationHolder`, not in a BLP array — orderRef is monotonic/unbounded on this branch, so a dense
orderRef-indexed array would exhaust. Account/exposure aggregates are rebuilt from open orders on
snapshot restore, so the two views cannot disagree.

## Event payload slots (`InputEvent`, type-discriminated — no wire-format change)

| Slot | ORDER_NEW / TRADE_NEW | *_CONTROL | PRICE_TICK |
|---|---|---|---|
| `priceTicks` | `clientOrderKey` (idempotency) | `controlVersion` | price |
| `side` | order side | control enabled/kill/restricted bool | — |
| `qty` / `limitPx` | order qty / limit | POLICY: maxPositionQty / maxConcentrationTicks | — |

Records written before this state carry zeros in these slots → decode as "no key" / version 0, so
old journals replay unchanged.

## Snapshot v3 (`SnapshotStore.Data`)

Order rows gain `riskReason`, `reservedNotional`, `reservedQty`. New sections: `riskPolicy`
(policyVersion, killSwitch, maxPositionQty, maxConcentrationTicks), `riskAccounts` (id, enabled,
executedNotional), `riskSecurities` (id, enabled, restricted, lastPrice, lastPriceTime),
`riskIdempotency` (key, orderRef, decision — retention order). v1/v2 snapshots still load (risk
sections absent ⇒ risk starts from seeds + journal tail).

## Stable reason codes (`RiskReason`)

`ACCEPTED, INVALID, NOT_ENTITLED, UNKNOWN_ACCOUNT, ACCOUNT_DISABLED, UNKNOWN_SECURITY,
SECURITY_DISABLED, RESTRICTED, KILL_SWITCH, PRICE_MISSING, PRICE_STALE, PRICE_COLLAR, ORDER_SIZE,
ORDER_NOTIONAL, CREDIT_LIMIT, POSITION_LIMIT, CONCENTRATION_LIMIT, DUPLICATE, CONTROL_STATE_STALE,
CAPACITY, INTERNAL_POLICY_ERROR`. Ordinals are snapshot/journal-encoded, so append-only.
