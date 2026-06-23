# Contract Delta: in-memory-risk-gateway

Parent state: `009b-lmax-sequencer-architecture`

This state intentionally changes admission semantics while preserving accepted order/trade/position
business outputs and the inherited output-disruptor topology.

## External Admission API

### Request delta

New order and market-trade commands add required:

| Field | Type | Contract |
| --- | --- | --- |
| `clientOrderId` | non-empty string | Client-generated idempotency key within authenticated principal scope |

`principalId` is supplied by trusted authentication middleware/context and SHALL NOT be accepted from
an untrusted request body. Existing `accountId`, security/ticker, side, quantity, and price fields retain
their prior external representation.

### Accepted response

The existing success payload remains compatible, but success is returned only after the BLP emits an
authoritative acceptance. The response MAY add non-breaking decision metadata if the existing API
serialization conventions permit it; the minimum correlation is `clientOrderId`.

### Rejected response

Risk/control rejection is a stable 4xx response:

```json
{
  "clientOrderId": "client-123",
  "decision": "REJECTED",
  "reason": "CREDIT_LIMIT",
  "policyVersion": 42,
  "commandSequence": 9182
}
```

The implementation SHALL map reasons to documented HTTP statuses consistently. Domain/risk rejection
is distinct from `503` unready admission and `429`/capacity backpressure where applicable.

Stable reason enum:

`INVALID` | `NOT_ENTITLED` | `UNKNOWN_ACCOUNT` | `ACCOUNT_DISABLED` |
`UNKNOWN_SECURITY` | `SECURITY_DISABLED` | `RESTRICTED` | `KILL_SWITCH` |
`PRICE_MISSING` | `PRICE_STALE` | `PRICE_COLLAR` | `ORDER_SIZE` | `ORDER_NOTIONAL` |
`CREDIT_LIMIT` | `POSITION_LIMIT` | `CONCENTRATION_LIMIT` | `DUPLICATE` |
`CONTROL_STATE_STALE` | `CAPACITY` | `INTERNAL_POLICY_ERROR`

Duplicate idempotency requests return the original decision and original command sequence. They do not
produce a second business mutation.

## Internal Input Contract

The inherited versioned SBE input schema adds:

### Submitted command messages

- `ORDER_SUBMITTED`
- `TRADE_SUBMITTED`

Fields: global `seq`, normalized primitive command fields, fixed-width `clientOrderKey`, trusted
`principalId`, Gateway control watermark, price source timestamp/version, `ingressNanos`, and trace id.

### Control messages

- `SECURITY_UPSERTED`
- `SECURITY_STATUS_CHANGED`
- `ACCOUNT_UPSERTED`
- `ENTITLEMENT_CHANGED`
- `RESTRICTION_CHANGED`
- `RISK_POLICY_CHANGED`
- `KILL_SWITCH_CHANGED`

Every control message carries `sourceEpoch`, `sourceVersion`, aggregate key, effective event time,
authorized provenance, and complete decision-relevant value. The BLP does not dereference a remote id.

Schema evolution SHALL use the existing SBE schema id/version rules. Old journals remain readable by
an explicit upcaster/compatibility path or fail with a clear incompatible-schema diagnostic; they may
not be silently misinterpreted.

## Internal Decision / Output Contract

Decision kinds:

- `ORDER_ACCEPTED`, `ORDER_REJECTED`
- `TRADE_ACCEPTED`, `TRADE_REJECTED`
- `GATEWAY_BLP_MISMATCH` (diagnostic; not a business acceptance)

Decision fields include command sequence, client-order key, decision/reason enum, policy version,
relevant source versions, price version/time, reservation delta, and inherited latency correlation.

Accepted order/trade/position events continue through the same `009b` output ring handlers and retain
the same NATS payloads/subjects:

- `/orders`
- `/accounts/{accountId}/orders`
- `/trades`
- `/accounts/{accountId}/trades`
- `/accounts/{accountId}/positions`

Rejected commands do not produce accepted business events on those subjects. A rejection response may
be completed through the existing Gateway request/response correlation mechanism; an optional
diagnostic stream must not expose credentials or unbounded client identifiers as metric labels.

## Replica Snapshot Contract

Logical snapshot request/response (transport selected during implementation):

```text
SnapshotRequest(replicaType, supportedSchemaVersion)
SnapshotEnvelope(
  replicaType,
  schemaVersion,
  sourceEpoch,
  watermark,
  recordCount,
  checksum,
  records[]
)
```

Requirements:

- snapshot is internally consistent at `watermark`;
- checksum covers deterministic serialized records and metadata;
- records are complete decision values, not links requiring follow-up queries;
- consumer has already subscribed/buffered durable deltas before requesting/installing the snapshot;
- source epoch mismatch forces complete re-bootstrap.

## Replica Delta Contract

```text
DeltaEnvelope(
  replicaType,
  schemaVersion,
  sourceEpoch,
  sourceVersion,
  aggregateKey,
  operation,
  effectiveEventTime,
  provenance,
  value
)
```

Apply semantics:

- version `<= lastApplied`: idempotent duplicate, ignore;
- version `== lastApplied + 1`: apply;
- version `> lastApplied + 1`: gap, mark replica unready and re-bootstrap;
- epoch mismatch: discard current image and re-bootstrap;
- invalid signature/schema/value: quarantine, alert, keep last proven state, fail closed as specified.

## Risk Administration Contract

Risk policy/restriction/kill-switch changes require authenticated authorized provenance and optimistic
versioning. Update acknowledgement means the authoritative source transaction/change log committed; it
does not claim every Gateway/BLP has applied the version. Applied-version observability provides that
operational confirmation.

## Database Contract

- Existing `OrderBook`, trade, and position schema contracts are unchanged.
- The relational database remains an asynchronous query projection.
- No new database table is authoritative for live BLP exposure/reservation state.
- An implementation may persist control-source/outbox tables, but their schema must be documented in
  the generated overlay and remain outside the command decision path.

## Configuration Contract

Proposed keys (final names locked during implementation/spec review):

| Key | Demo default | Purpose |
| --- | --- | --- |
| `replica.<type>.mandatory` | `true` | Readiness requirement |
| `replica.<type>.stale-after-ms` | source-specific | Silence/staleness threshold |
| `replica.bootstrap.timeout-ms` | `30000` | Snapshot/catch-up deadline |
| `replica.buffer.capacity` | bounded | Deltas buffered during snapshot |
| `risk.fail-closed` | `true` | Immutable true in perf/production-like profile |
| `risk.price.max-age-ms` | source-specific | Price freshness rule |
| `risk.idempotency.capacity` | bounded | Preallocated idempotency records |
| `risk.idempotency.retention-events` | bounded | Deterministic replay frontier |
| `risk.max-accounts` | bounded | Account/control store sizing |
| `risk.max-securities` | inherited/bounded | Security/control store sizing |
| `risk.max-restrictions` | bounded | Restriction store sizing |
| `risk.max-open-orders` | inherited/bounded | Reservation sizing |

## Compatibility Notes

- Required `clientOrderId` is an intentional request-contract change.
- A synchronous `200` now means BLP acceptance, not merely that a downstream POST/publish succeeded.
- Rejections gain a stable machine-readable body.
- Accepted business events, UI views, output subjects, and relational query shapes remain compatible.
- `trade-service`/Gateway no longer depends on account/reference REST availability per command.

## Traceability

- External request/decision: FR-IMRG14..FR-IMRG20, FR-IMRG42.
- Input/control schemas: FR-IMRG10..FR-IMRG12, FR-IMRG30..FR-IMRG34.
- Snapshot/delta: FR-IMRG03..FR-IMRG05, FR-IMRG32..FR-IMRG35.
- Output compatibility: FR-IMRG40..FR-IMRG45.

