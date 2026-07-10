# Quickstart: YU08-execution-algo-engine

## Local (kind)

### First run

```bash
bash pipeline/generate-state.sh YU08-execution-algo-engine
bash generated/code/target-generated/scripts/start-state-YU08-execution-algo-engine-generated.sh \
  --provider kind --without-sail
```

UI at **http://127.0.0.1:8080**. This inherits the `YU07-historical-tick-store` kind runtime
unchanged; only `execution-algo-engine` is new (its own Deployment + ClusterIP Service, no PVC —
durable state lives in the `TRADERX_ALGO_ENGINE` JetStream stream).

### Subsequent runs (skip rebuild if code unchanged)

```bash
bash generated/code/target-generated/scripts/start-state-YU08-execution-algo-engine-generated.sh \
  --provider kind --without-sail --skip-build
```

### Validate

```bash
bash scripts/test-state-YU08-execution-algo-engine.sh
```

### Stop

```bash
bash generated/code/target-generated/scripts/stop-state-YU08-execution-algo-engine-generated.sh
```

---

## Run the unit tests (no cluster needed)

```bash
cd specs/YU08-execution-algo-engine/generation/runtime-overrides/execution-algo-engine
./gradlew test
```

## Submit a TWAP parent order (against a running cluster)

```bash
kubectl port-forward svc/execution-algo-engine 18120:18120 -n traderx &

curl -s -X POST http://127.0.0.1:18120/algo/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "accountId": 22214,
    "security": "IBM",
    "side": "Buy",
    "quantity": 500,
    "algoType": "TWAP",
    "durationSeconds": 60,
    "bucketSeconds": 10
  }'
```

Returns the created parent order with its bucket schedule. Poll progress:

```bash
curl -s http://127.0.0.1:18120/algo/orders/<parentOrderId>
```

Each bucket is submitted to `order-matcher`'s `POST /orders` as its `startEpochMs` comes due; watch
`kubectl logs deploy/order-matcher -n traderx` or the account's order blotter in the web UI for the
resulting child orders.

## Submit a VWAP parent order

```bash
curl -s -X POST http://127.0.0.1:18120/algo/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "accountId": 22214,
    "security": "IBM",
    "side": "Sell",
    "quantity": 500,
    "algoType": "VWAP",
    "durationSeconds": 60,
    "bucketSeconds": 10
  }'
```

With the default `ALGO_VOLUME_PROFILE_SOURCE=synthetic`, bucket quantities follow the U-shaped
intraday curve (heavier near the first/last buckets) rather than an equal split. Set
`ALGO_VOLUME_PROFILE_SOURCE=duckdb` on the Deployment to query YU07's real tick store instead — it
falls back to the same synthetic curve automatically for any security with no matching historical
rows (research.md Decision 7), so this is safe to enable before bulk TAQ ingestion unblocks.

## Crash-recovery check

```bash
kubectl delete pod -l app=execution-algo-engine -n traderx
# wait for the replacement pod to become Ready, then:
curl -s http://127.0.0.1:18120/algo/orders/<parentOrderId>
```

The parent order's bucket schedule and any fills observed before the kill are still present —
rebuilt from the `TRADERX_ALGO_ENGINE` JetStream stream on boot, not re-created.
