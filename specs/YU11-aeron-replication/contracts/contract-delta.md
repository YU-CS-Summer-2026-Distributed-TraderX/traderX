# Contract Delta: YU11-aeron-replication

## 1. Existing external contracts

REST `/orders`, REST `/orders/batch`, FIX 4.4 ingress, risk decisions, order lifecycle output,
trade booking, UI routing, and every inherited NATS subject retain their YU10 contracts. Aeron is
cluster-internal BLP replication only.

## 2. Order-matcher configuration

| Variable | Values / default | Contract |
|---|---|---|
| `BLP_REPLICATION_TRANSPORT` | `nats` / `aeron`; default `nats` | Selects the authoritative replication data/ACK leg. Both peers must match. |
| `BLP_REPLICATION_AERON_SHADOW` | boolean; default `false` | With NATS authoritative, runs Aeron record/consume/checksum comparison without BLP injection or gating. |
| `BLP_REPLICATION_ACK_MODE` | `onring` / `durable`; default `onring` | Durable uses the exact follower journal force watermark. |
| `BLP_REPLICATION_FAILURE_POLICY` | `degraded-solo` / `strict`; default `degraded-solo` | Strict requires durable ACK and closes admission on peer durability loss. |
| `BLP_FAILOVER_MODE` | `lease` / `fast-witness`; default `lease` | Selects synchronous Kubernetes Lease or direct-heartbeat + NATS-KV witness promotion. |
| `BLP_CLUSTER_ID` | non-empty string | Replication/witness identity; mismatch rejects handshake. |
| `BLP_REPLICATION_SECRET_FILE` | file path | Shared HMAC secret mounted from a Kubernetes Secret. |
| `BLP_AERON_DIR` | path | Shared application/sidecar Aeron directory. |
| `BLP_AERON_DATA_CHANNEL` | Aeron URI | Primary-to-follower reliable unicast channel. |
| `BLP_AERON_ACK_CHANNEL` | Aeron URI | Follower-to-primary ACK channel. |
| `BLP_AERON_CONTROL_CHANNEL` | Aeron URI | Handshake, heartbeat, replay, snapshot-control channel. |
| `BLP_AERON_STREAM_ID` | integer | Input-event stream ID. |
| `BLP_AERON_ACK_STREAM_ID` | integer | Durable-ACK stream ID. |
| `BLP_AERON_CONTROL_STREAM_ID` | integer | Control stream ID. |
| `BLP_AERON_OFFER_TIMEOUT_MS` | positive integer | Bounded offer/backpressure timeout before policy transition. |
| `BLP_AERON_HEARTBEAT_INTERVAL_MS` | positive integer; default `10` | Direct heartbeat cadence. |
| `BLP_AERON_PEER_STALE_MS` | positive integer; default `40` | Fast-mode peer staleness threshold; must exceed heartbeat interval. |
| `BLP_FAST_WITNESS_BUCKET` | default `TRADERX_BLP_FAST_WITNESS` | NATS KV bucket used for atomic fast promotion. |
| `BLP_FAST_WITNESS_TERM_MS` | positive integer | Witness claim term. |
| `BLP_ARCHIVE_DIR` | path | Sidecar recording/catalog path on the persistent volume. |
| `BLP_ARCHIVE_MIN_FREE_BYTES` | positive integer | Fail-closed disk watermark. |

Unknown enum values and incompatible flag combinations fail startup before readiness.

## 3. Internal Aeron channels

| Channel | Transport | Direction | Payload |
|---|---|---|---|
| data | reliable unicast UDP | current primary -> follower | SBE `InputEventMessage` |
| ACK | reliable unicast UDP | follower -> current primary | SBE `DurableAckMessage` |
| control | reliable unicast UDP | bidirectional | hello/challenge, heartbeat, replay and snapshot control |
| Archive replay | reliable unicast UDP | recording sidecar -> follower application | recorded SBE input/snapshot stream |

Channels resolve peers through StatefulSet ordinal DNS. NetworkPolicy permits these UDP ports
only between pods labeled `app=order-matcher` in the runtime namespace.

## 4. Admission and HTTP outcomes

- Default degraded-solo: peer loss changes health/metrics and transport state but the primary
  continues journal-protected admission. A request that already entered the ring retains the
  inherited acknowledgement semantics.
- Strict: replication gap/timeout closes the synchronous admission fence; new REST requests
  receive 503. A request already sequenced with an ambiguous response retains 504 semantics and
  idempotent retry rules.
- Fast-witness: admission opens only after a successful witness compare-and-set and closes before
  any order when witness revision/epoch/Lease proof becomes foreign or ambiguous.
- A follower, replaying pod, mismatched peer, or gap-bearing pod never accepts orders.

## 5. Health contract

The order-matcher health payload adds:

```json
{
  "replication": {
    "transport": "nats|aeron",
    "shadow": false,
    "ackMode": "onring|durable",
    "failurePolicy": "degraded-solo|strict",
    "state": "AERON_LIVE",
    "leaderEpoch": 7,
    "offeredSeq": 1000,
    "followerReceivedSeq": 1000,
    "followerJournaledSeq": 1000,
    "followerAppliedSeq": 1000,
    "acknowledgedSeq": 1000,
    "shadowComparedSeq": 1000,
    "archiveLagBytes": 0,
    "archiveFreeBytes": 10737418240,
    "schemaChecksum": "sha256:..."
  },
  "failover": {
    "mode": "lease|fast-witness",
    "peerHeartbeatAgeMillis": 8,
    "witnessRevision": 31,
    "witnessHolder": "order-matcher-0",
    "leaseReconciled": true
  }
}
```

Readiness is false for schema/epoch mismatch, replay gap, Archive fault, strict replication loss,
or an unproven promotion. Degraded-solo primary readiness remains true and reports the degraded
state.

## 6. Metrics contract

- `traderx_blp_replication_offered_total{transport}`
- `traderx_blp_replication_consumed_total{transport}`
- `traderx_blp_replication_backpressure_total{transport,result}`
- `traderx_blp_replication_ack_latency_seconds{mode}`
- `traderx_blp_replication_watermark{kind}`
- `traderx_blp_replication_shadow_mismatch_total{reason}`
- `traderx_blp_aeron_retransmits_total`
- `traderx_blp_aeron_loss_gap_total`
- `traderx_blp_archive_lag_bytes`
- `traderx_blp_archive_free_bytes`
- `traderx_blp_fast_witness_claim_total{result}`
- `traderx_blp_fast_failover_seconds{phase}`

## 7. Operational storage contract

The Archive uses the order-matcher PVC. Capacity expansion is an operator procedure:

1. verify the StorageClass has `allowVolumeExpansion=true`;
2. patch each existing order-matcher PVC request to 10Gi;
3. wait for filesystem resize completion;
4. recreate the StatefulSet object with `--cascade=orphan` so its immutable
   `volumeClaimTemplates` records 10Gi without deleting pods/PVCs;
5. verify both retained claims, Archive free-byte metrics, and journal/snapshot paths before
   selecting Aeron.
