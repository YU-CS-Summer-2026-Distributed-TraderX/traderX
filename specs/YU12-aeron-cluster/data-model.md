# Data Model: YU12-aeron-cluster

## Cluster ingress message

Ingress reuses the inherited SBE `InputEventMessage` — the standard 8-byte SBE header plus the
fixed 56-byte little-endian root block defined in the parent state's `data-model.md`
(`inputSeq`, `eventTimeMillis`, `limitPx`, `priceTicks`, `orderRef`, `accountId`, `securityId`,
`qty`, `leaderEpoch`, `commandType`, `side`, `flags`). Two fields change meaning under consensus
hosting:

| Field | Meaning under the cluster |
|---|---|
| `inputSeq` | Assigned by the consensus log's commit order, not by the publisher; the client-side value is ignored on ingress. |
| `leaderEpoch` | Superseded by the cluster leadership term carried in the log; retained in the layout for codec compatibility and set from the term on egress. |

Unknown schema, template, version, or required flag rejects the message at the ingress boundary
before sequencing.

## Ingress command families

| Family | `commandType` source | Producer |
|---|---|---|
| Order commands (new/cancel) | inherited `InputEvent.type` values | gateway tier via cluster client |
| Price ticks | inherited market-data type | feed adapter via cluster client |
| Control/policy updates | inherited control-feed types | feed adapter via cluster client |

## Snapshot state

`onTakeSnapshot` writes one snapshot bound to the service's applied log position. It carries the
complete deterministic state:

| Group | Contents |
|---|---|
| Order book | every retained order tuple (the inherited `SnapshotStore.Data` order serialization) |
| Future-output generators | `nextOrderRef`, trade counter, and any other monotonic ID source |
| Idempotency | client-key/duplicate-detection state |
| Risk | reservations, balances, and the deterministic two-tier risk state |
| Reference identity | symbol-table identity mapping |
| Control versions | applied control/policy version watermarks |

Restore asserts every generator strictly exceeds every identifier present in the restored book
and refuses readiness otherwise; the strict form of the assertion — greater than every ID ever
issued — is proven by the recovery matrix, since eviction can remove the highest historical
reference from the book itself.

## Positions and terms

| Value | Authority |
|---|---|
| commit position | consensus module (Raft majority) |
| service position | clustered service container (applied position) |
| snapshot position | recorded with each snapshot; recovery resumes strictly after it |
| leadership term | consensus module; monotonic across elections |

A snapshot is valid only for exactly one applied position; recovery loads the newest valid
snapshot and replays the committed log strictly after it.
