# Data Model: YU11-aeron-replication

## Input message (`InputEventMessage`, 64 bytes)

The message contains the standard 8-byte SBE header followed by a fixed 56-byte root block.
All multi-byte fields use little-endian encoding.

### SBE header

| Offset | Field | Type | Value |
|---:|---|---|---|
| 0 | `blockLength` | `uint16` | 56 |
| 2 | `templateId` | `uint16` | schema-assigned input template ID |
| 4 | `schemaId` | `uint16` | YU11 replication schema ID |
| 6 | `version` | `uint16` | encoded schema version |

### Root block

| Root offset | Field | Type | Meaning |
|---:|---|---|---|
| 0 | `inputSeq` | `uint64` | Contiguous logical sequence within the leader epoch. |
| 8 | `eventTimeMillis` | `int64` | Sequenced event time. |
| 16 | `limitPx` | `int64` | Existing fixed-point limit-price slot. |
| 24 | `priceTicks` | `int64` | Existing fixed-point market-price/client-key slot. |
| 32 | `orderRef` | `uint32` | Internal order reference. |
| 36 | `accountId` | `uint32` | Trading account ID. |
| 40 | `securityId` | `uint32` | Symbol-table ID. |
| 44 | `qty` | `int32` | Signed quantity. |
| 48 | `leaderEpoch` | `uint32` | Witness/Lease transition epoch. |
| 52 | `commandType` | `uint8` enum | Existing `InputEvent.type`. |
| 53 | `side` | `uint8` enum | Existing buy/sell value. |
| 54 | `flags` | `uint16` set | Defined compatible fixed flags; unknown required bits reject. |

Logical identity is `(clusterId,leaderEpoch,inputSeq)`. Aeron session/stream/position values are
transport coordinates and do not replace that identity.

## Durable ACK (`DurableAckMessage`, 32-byte root)

| Offset | Field | Type | Meaning |
|---:|---|---|---|
| 0 | `leaderEpoch` | `uint32` | Epoch of the accepted data stream. |
| 4 | `flags` | `uint32` | `ON_RING`, `JOURNALED`, `APPLIED`, `REPLAYING`, `DEGRADED`. |
| 8 | `inputSeq` | `uint64` | Highest contiguous sequence covered by the flags. |
| 16 | `recordingPosition` | `int64` | Follower Archive position associated with the ACK. |
| 24 | `journalForceNanos` | `uint64` | Monotonic force-completion time for latency accounting. |

The primary accepts an ACK only for its current epoch and only when `inputSeq` is monotonic.
Durable mode requires `JOURNALED`; promotion readiness separately requires `APPLIED`.

## Session hello/challenge

The control handshake binds:

| Field | Meaning |
|---|---|
| `clusterId` | Deployment identity shared by both replicas. |
| `podUid` | Kubernetes pod UID. |
| `ordinal` | StatefulSet ordinal (0 or 1). |
| `leaderEpoch` | Current Lease/witness epoch. |
| `transport` | `NATS` or `AERON`. |
| `schemaChecksum` | SHA-256 of the committed SBE schema and generated codec metadata. |
| `dataSessionId` / `ackSessionId` | Accepted Aeron session identifiers. |
| `nonce` | Fresh challenge nonce. |
| `hmac` | HMAC-SHA256 over all preceding fields with the replication Secret. |

The handshake state is `DISCONNECTED -> CHALLENGED -> AUTHENTICATED -> CATCHING_UP -> LIVE`.
Any identity/schema/epoch mismatch transitions to `REJECTED` and readiness remains false.

## Snapshot/Archive checkpoint

```text
(leaderEpoch, inputSeq, recordingId, recordingPosition, sbeSchemaVersion)
```

The checkpoint is persisted with the application snapshot and is valid only when all fields
match one complete snapshot manifest.

### Snapshot manifest

| Field | Meaning |
|---|---|
| `bundleId` | Unique immutable bundle identifier. |
| `leaderEpoch` / `inputSeq` | Logical snapshot boundary. |
| `recordingId` / `recordingPosition` | Replay start coordinate. |
| `schemaVersion` / `schemaChecksum` | Decoder contract. |
| `snapshotLength` / `symbolsLength` | Expected file sizes. |
| `snapshotSha256` / `symbolsSha256` | Content checksums. |
| `chunkCount` | Complete chunk count for atomic install. |

Bundle installation writes temporary files, verifies lengths/checksums/schema, fsyncs, and
atomically renames both files before journal/Archive replay starts.

## Shadow comparison record

Shadow mode tracks a fixed rolling window keyed by `(leaderEpoch,inputSeq)`:

| Field | Meaning |
|---|---|
| `natsChecksum` | 64-bit checksum of the authoritative NATS-decoded payload. |
| `aeronChecksum` | 64-bit checksum of the Aeron-decoded payload. |
| `natsSeen` / `aeronSeen` | Presence bits. |
| `deadlineNanos` | Bounded comparison deadline. |

Matched entries are cleared; mismatches, gaps, duplicate-different payloads, and expired
one-sided entries increment the shadow-failure counter and block a successful shadow result.

## Fast witness record

The `TRADERX_BLP_FAST_WITNESS` KV value contains:

| Field | Meaning |
|---|---|
| `clusterId` | Deployment identity. |
| `holderIdentity` | Pod identity allowed to open admission. |
| `leaderEpoch` | Monotonic epoch assigned by a successful compare-and-set. |
| `previousRevision` | KV revision the contender observed before its claim. |
| `claimedAtMillis` | Witness-server-observed claim time. |
| `expiresAtMillis` | Bounded fast-witness term. |
| `schemaChecksum` | Serving pair's schema identity. |

The KV revision is part of the in-process admission fence. A contender opens admission only for
the exact revision returned by its successful atomic claim.

## Runtime state model

Transport state:

```text
DISABLED | NATS_LIVE | AERON_SHADOW | AERON_CATCHING_UP | AERON_LIVE | DEGRADED_SOLO | STRICT_REFUSING
```

Watermarks:

- `offeredSeq`: highest primary sequence offered to the selected transport;
- `recordedPosition`: primary Archive recorded position;
- `followerReceivedSeq`: highest contiguous decoded follower sequence;
- `followerJournaledSeq`: mapped primary sequence covered by follower journal force;
- `followerAppliedSeq`: mapped primary sequence applied by the follower BLP;
- `acknowledgedSeq`: highest accepted peer ACK for the active mode;
- `shadowComparedSeq`: highest contiguous payload-equal shadow sequence.

Readiness requires a legal state/mode combination and no gap. Promotion eligibility additionally
requires `followerJournaledSeq` and `followerAppliedSeq` at the observed live high watermark.
