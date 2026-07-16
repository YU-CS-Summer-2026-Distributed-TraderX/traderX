# Quickstart: YU11-aeron-replication

## 1. Generate

```bash
bash pipeline/generate-state.sh YU11-aeron-replication
```

Confirm the generated state and transport artifacts:

```bash
rg -n "YU11-aeron-replication|BLP_REPLICATION_TRANSPORT|AeronReplicator" \
  generated/code/target-generated/YU11-aeron-replication \
  generated/code/target-generated/order-matcher
```

## 2. Compose-first transport proof

The compose profile is isolated from kind and starts a NATS broker plus two order-matcher
application/Archiving-Media-Driver pairs.

```bash
bash scripts/yu11/start-aeron-ha-compose.sh
bash scripts/yu11/test-aeron-ha-compose.sh
```

Run the stored three-run transport comparison:

```bash
RUNS=3 RUN_SECONDS=30 \
  bash scripts/bench/run-yu11-aeron-transport.sh
```

Shadow proof keeps NATS authoritative:

```bash
BLP_REPLICATION_TRANSPORT=nats \
BLP_REPLICATION_AERON_SHADOW=true \
  bash scripts/yu11/test-aeron-shadow.sh
```

Stop the isolated pair:

```bash
bash scripts/yu11/stop-aeron-ha-compose.sh
```

## 3. Dedicated multi-node kind profile

Create the named YU11 cluster; this does not modify the shared single-node cluster:

```bash
bash scripts/kind/create-yu11-ha-cluster.sh
kubectl config use-context kind-traderx-yu11-ha
```

Generate/apply and prove readiness, order acceptance, Archive catch-up, and failover:

```bash
bash generated/code/target-generated/scripts/start-state-YU11-aeron-replication-generated.sh
bash generated/code/target-generated/scripts/test-state-YU11-aeron-replication.sh
```

Inspect transport state:

```bash
kubectl get pods -n traderx -l app=order-matcher -o wide
kubectl logs -n traderx order-matcher-0 -c aeron-replication-sidecar --tail=100
kubectl exec -n traderx order-matcher-0 -c order-matcher -- \
  wget -qO- http://127.0.0.1:18110/health
```

## 4. Policy proofs

Default degraded-solo behavior:

```bash
BLP_REPLICATION_FAILURE_POLICY=degraded-solo \
  bash scripts/yu11/test-follower-loss-policy.sh
```

Strict durable behavior:

```bash
BLP_REPLICATION_ACK_MODE=durable \
BLP_REPLICATION_FAILURE_POLICY=strict \
  bash scripts/yu11/test-follower-loss-policy.sh
```

Fast-witness behavior:

```bash
BLP_FAILOVER_MODE=fast-witness \
  bash scripts/yu11/test-fast-witness-failover.sh
```

## 5. GKE user-run capacity preparation

The user performs pool scaling before the GKE comparison:

```bash
gcloud container clusters resize traderx-lmax \
  --project traderx-501015 --zone us-east1-b \
  --node-pool blp-pool --num-nodes 2

gcloud container clusters resize traderx-lmax \
  --project traderx-501015 --zone us-east1-b \
  --node-pool default-pool --num-nodes 3
```

Verify capacity without mutating workloads:

```bash
kubectl get nodes -L cloud.google.com/gke-nodepool
kubectl get storageclass -o custom-columns=NAME:.metadata.name,EXPAND:.allowVolumeExpansion
```

Apply the PVC expansion runbook in `contracts/contract-delta.md` before selecting Aeron on
existing claims.

## 6. GKE A/B/A comparison

```bash
bash scripts/bench/run-yu11-gke-comparison.sh \
  --runs 3 --seconds 30 --batch 1000
```

The runner records File-backed NATS HA, Aeron HA, NATS HA again, and a same-day single-BLP
control with exact image/schema/config/node identities. It reports the ≥35k and ≥25% ship gates
without changing the selected deployment value after the comparison.

## 7. Allocation and recovery gates

```bash
cd generated/code/target-generated/order-matcher
./gradlew allocationGateTest riskAllocationGateTest noGcTest
./gradlew aeronReplicationAllocationTest
./gradlew test --tests '*Aeron*' --tests '*Replication*'
```

```bash
bash scripts/yu11/test-aeron-loss-replay.sh
bash scripts/yu11/test-aeron-archive-faults.sh
```

## 8. Stop

```bash
bash generated/code/target-generated/scripts/stop-state-YU11-aeron-replication-generated.sh
```
