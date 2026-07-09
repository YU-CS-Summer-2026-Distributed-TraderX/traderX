# Quickstart: YU04-durable-control-feeds

Durable control feeds for the risk gateway: `account-service` and `reference-data` publish versioned
control deltas from a transactional outbox to per-source JetStream streams, and `order-matcher`
bootstraps its Gateway replica from a watermarked snapshot plus buffered deltas — so a control change
reaches the replica durably, without a restart.

## Local (kind)

### First run

```bash
bash pipeline/generate-state.sh YU04-durable-control-feeds
bash generated/code/target-generated/scripts/start-state-YU04-durable-control-feeds-generated.sh \
  --provider kind --without-sail
```

UI at **http://127.0.0.1:8080**. Same kind harness as YU03/YU02 (`traderx-state-014` cluster); this
state additionally exercises `account-service` and `reference-data` as durable-feed sources and
NATS JetStream as the transport.

> **If you already have the cluster running from a previous deploy**, wipe and rebuild cleanly:
> ```bash
> bash generated/code/target-generated/scripts/start-state-YU04-durable-control-feeds-generated.sh \
>   --provider kind --without-sail --recreate-cluster
> ```

### Subsequent runs (skip rebuild if code unchanged)

```bash
bash generated/code/target-generated/scripts/start-state-YU04-durable-control-feeds-generated.sh \
  --provider kind --without-sail --skip-build
```

### Validate

```bash
bash scripts/test-state-YU04-durable-control-feeds.sh
```

### Stop

```bash
bash generated/code/target-generated/scripts/stop-state-YU04-durable-control-feeds-generated.sh
# or delete the cluster entirely:
kind delete cluster --name traderx-state-014
```

---

## Demo the durable control feed

The demo injects a new security through `reference-data`'s outbox write path and confirms it reaches
`order-matcher`'s Gateway replica through the JetStream feed — no order-matcher restart.

Port-forward the two services (order-matcher is reachable via the edge-proxy at
`127.0.0.1:8080/order-matcher`):

```bash
kubectl port-forward svc/reference-data 18085:18085 -n traderx &
kubectl port-forward svc/account-service 18088:18088 -n traderx &
```

```bash
# 1. Baseline: the source watermark before the change
curl -s http://localhost:18085/stocks/control-snapshot | jq '{schemaVersion, sourceEpoch, watermark, count}'

# 2. Inject a new security (reference-data's first-ever write path; inserts stocks +
#    stocks_control_outbox in one transaction, which the outbox publisher then ships)
curl -s -X POST http://localhost:18085/stocks \
  -H "Content-Type: application/json" \
  -d '{"ticker":"ZZZZ","companyName":"Demo Instrument Inc."}' | jq .

# 3. The snapshot watermark advances by one
curl -s http://localhost:18085/stocks/control-snapshot | jq '{sourceEpoch, watermark, count}'

# 4. Within a poll interval, order-matcher's replica reflects ZZZZ — no restart
curl -s http://127.0.0.1:8080/order-matcher/risk/control/snapshot | jq '.securities."ZZZZ"'
```

The same flow works for accounts through `account-service` (`GET /account/control-snapshot`; the
account write path records `account_control_outbox`, published to `TRADERX_CONTROL_ACCOUNT`).

## Inspect feed-health metrics

```bash
# order-matcher's per-source watermark + quarantine counters
curl -s http://127.0.0.1:8080/order-matcher/actuator/prometheus \
  | rg "traderx_replica_source_watermark|traderx_replica_quarantine_total|traderx_control_update_rejected_total"
```

---

## Runtime notes

- **Two independent streams**: `TRADERX_CONTROL_ACCOUNT` (subject `traderx.control.account.deltas`)
  and `TRADERX_CONTROL_SECURITY` (subject `traderx.control.security.deltas`). A fault on one source
  quarantines and re-bootstraps that source only.
- **Bootstrap protocol** (ADR-019, per source): subscribe + buffer, fetch the watermarked snapshot,
  verify checksum/count and atomically install, apply buffered deltas above the watermark in order,
  then consume live; the Gateway is marked ready only once every source is caught up (FR-IMRG05).
- **No BLP/journal/snapshot impact**: `GatewayReplicaStore` is edge-only, rebuilt on every boot; the
  BLP decision path, journal/replication wire format, and snapshot format are unchanged from YU03.
- Everything from YU03's and YU02's runtime notes still applies — see their quickstarts.
