# Quickstart: YU12-aeron-cluster

## 1. Generate

```bash
bash pipeline/generate-state.sh YU12-aeron-cluster
```

Confirm the generated state and cluster artifacts:

```bash
rg -n "YU12-aeron-cluster|ClusteredService|aeron-cluster" \
  generated/code/target-generated/YU12-aeron-cluster \
  generated/code/target-generated/order-matcher
```

## 2. Single-member cluster proof

The clustered-service spike proof runs inside the generated order-matcher test suite: it boots a
single-member Aeron Cluster in-process, round-trips orders through the consensus log into the
inherited `MatchingEngine`, takes a snapshot, restarts the cluster from disk, and asserts the
strict no-ID-reuse invariant.

```bash
cd generated/code/target-generated/order-matcher
./gradlew test --tests 'finos.traderx.ordermatcher.cluster.*'
```

## 3. Full order-matcher regression

```bash
cd generated/code/target-generated/order-matcher
./gradlew test
./gradlew --no-daemon noGcTest
```

## 4. State checks

```bash
TRADERX_SKIP_GENERATE=1 bash scripts/test-state-YU12-aeron-cluster.sh
```
