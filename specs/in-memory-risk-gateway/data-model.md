# Data Model: In-Memory Risk Gateway

## Scope

This state extends the `009b` input journal, BLP snapshot, Gateway state, and decision outputs. The
relational order/trade/position schema remains an asynchronous query projection and is not promoted to
risk authority.

## External Source Records

Every source record carries `sourceEpoch` and monotonic `sourceVersion` in addition to its key/value.

### SecurityState

| Field | Type | Notes |
| --- | --- | --- |
| `securityId` | `int` | Authoritative numeric id; never client-created |
| `ticker` | edge string | Gateway/output mapping only |
| `enabled` | boolean/byte | Admission eligibility |
| `tradingStatus` | enum byte | `OPEN`, `HALTED`, `CLOSED`, `DISABLED` |
| `sourceEpoch` | `long` | Source reset/snapshot generation |
| `sourceVersion` | `long` | Monotonic change version |

### AccountState / EntitlementState

| Record | Fields |
| --- | --- |
| `AccountState` | `accountId`, status (`ACTIVE|SUSPENDED|CLOSED`), source epoch/version |
| `EntitlementState` | `principalId`, `accountId`, permission bits, source epoch/version |

Principal identity originates from trusted authentication context. Raw client payload cannot set or
override it.

### RiskPolicy

| Field group | Fields |
| --- | --- |
| Identity | `policyId`, `policyVersion`, source epoch/version, effective timestamp/sequence |
| Price validity | max age, allowed status, missing-price behavior |
| Single order | max quantity, max notional, buy/sell price-collar basis points |
| Aggregate account | gross/notional credit limit, position quantity/notional limit |
| Concentration | security/sector maximum where configured |
| Degraded mode | explicitly allowed cancel/risk-reducing operations |
| Resting-order treatment | retain, reduce-only, or explicit cancel generation |
| Provenance | authorized source/operator id and reason code |

### RestrictionState / KillSwitchState

- Restriction key: scope type (`PLATFORM|ACCOUNT|PRINCIPAL`), scope id, security id or wildcard, side
  mask, active flag, source version, provenance.
- Kill-switch key: scope type (`PLATFORM|ACCOUNT|SECURITY`), scope id, active flag, source version,
  provenance/reason.

## Replica Bootstrap Model

### SnapshotEnvelope

- `replicaType`
- `sourceEpoch`
- `watermark`
- `recordCount`
- deterministic checksum/schema version
- complete records for that replica type

### DeltaEnvelope

- `replicaType`, `sourceEpoch`, `sourceVersion`
- aggregate key
- operation (`UPSERT|DELETE|STATUS_CHANGE`)
- complete decision-relevant value (not a remote-query pointer)
- source event time and provenance

Consumer state tracks installed epoch, snapshot watermark, last applied version, observed high
watermark, readiness, and last heartbeat/source time. Version `<= lastApplied` is idempotently ignored;
version `> lastApplied + 1` is a gap and invalidates readiness.

## Sequenced Input Delta

The inherited input event adds:

- command types `ORDER_SUBMITTED`, `TRADE_SUBMITTED`;
- control types `SECURITY_UPSERTED`, `SECURITY_STATUS_CHANGED`, `ACCOUNT_UPSERTED`,
  `ENTITLEMENT_CHANGED`, `RESTRICTION_CHANGED`, `RISK_POLICY_CHANGED`, `KILL_SWITCH_CHANGED`;
- command fields `clientOrderKey`, authenticated `principalId`, Gateway control watermark, price source
  timestamp/version, trace/correlation id;
- control fields source epoch/version, effective event time, policy/provenance ids, and encoded value.

Strings remain outside the ring/BLP. External `clientOrderId` and principal identity map to bounded
primitive/fixed-width keys at the Gateway with collision-safe verification at the edge.

## BLP State Delta

Preallocated single-writer structures:

| State | Suggested shape | Invariant |
| --- | --- | --- |
| Account status | byte array/map by account id | Known active account required |
| Entitlements | primitive composite-key set/bitset | Principal must control account |
| Security status | byte array by security id | Id assigned only by reference feed |
| Prices | fixed-point price + source time/version arrays | Missing distinct from zero |
| Policies/limits | precompiled primitive policy table | One active version per scope |
| Restrictions | bounded primitive key table/bitsets | Stable lookup order |
| Kill switches | platform/account/security flags | Active flag rejects applicable risk |
| Positions | inherited account/security store | Exact executed exposure |
| Reservations | account/security buy/sell qty/notional | Never negative; one release |
| Idempotency | fixed-capacity key -> decision record | Duplicate returns immutable original |
| Decision counters | plain single-writer longs | Published via inherited sequence visibility |

## Exposure and Reservation Semantics

- Use checked fixed-point `long` arithmetic. Overflow is `INTERNAL_POLICY_ERROR`/explicit rejection,
  never wraparound.
- Worst-case order notional is defined by policy from quantity and validated reference/limit price.
- Aggregate used headroom includes executed exposure plus all applicable open-order reservations.
- Acceptance checks limits and writes reservation before the next event is processed.
- Partial fill decrements reservation for filled quantity/notional and updates executed position.
- Full fill removes remaining reservation. Cancel/expiry releases remaining reservation exactly once.
- Rejected commands create no reservation.

## Decision Record / Output Delta

| Field | Purpose |
| --- | --- |
| `commandSequence` | Global journal position of submitted command |
| `decision` | `ACCEPTED|REJECTED` |
| `reason` | Stable bounded reason enum |
| `clientOrderKey` | Idempotency lookup/correlation |
| `policyVersion` | Policy used by authoritative decision |
| control versions | Account/security/restriction/kill-switch versions used |
| price version/time | Market state used for price-dependent checks |
| reservation delta | Exact qty/notional reserved or released |
| `ingressNanos` | Inherited latency correlation; never decision time source |

Stable reasons: `INVALID`, `NOT_ENTITLED`, `UNKNOWN_ACCOUNT`, `ACCOUNT_DISABLED`,
`UNKNOWN_SECURITY`, `SECURITY_DISABLED`, `RESTRICTED`, `KILL_SWITCH`, `PRICE_MISSING`,
`PRICE_STALE`, `PRICE_COLLAR`, `ORDER_SIZE`, `ORDER_NOTIONAL`, `CREDIT_LIMIT`,
`POSITION_LIMIT`, `CONCENTRATION_LIMIT`, `DUPLICATE`, `CONTROL_STATE_STALE`, `CAPACITY`,
`INTERNAL_POLICY_ERROR`.

## Snapshot Delta

The `009b` BLP snapshot adds all risk/control/price/reservation/idempotency structures, their capacities,
active policy versions, source watermarks, and idempotency retention frontier. Recovery rejects a
snapshot whose schema/capacity contract is incompatible with runtime configuration.

Gateway snapshots are replaceable source projections and are never used as BLP recovery authority.

## Compatibility and Traceability

- Relational order/trade/position rows and accepted NATS payloads remain `009b` compatible.
- Required `clientOrderId` and rejection response are defined in `contracts/contract-delta.md`.
- Replica and source structures trace to FR-IMRG02..FR-IMRG09 and FR-IMRG32..FR-IMRG34.
- BLP structures trace to FR-IMRG10..FR-IMRG27.
- Snapshot/replay structures trace to FR-IMRG21..FR-IMRG23 and NFR-IMRG03..NFR-IMRG05.
- Allocation structures trace to NFR-IMRG02 and `requirements/no-gc-conformance.md`.

