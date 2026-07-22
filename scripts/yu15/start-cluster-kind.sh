#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KDIR="${ROOT}/specs/YU15-eod-risk-extract/generation/kubernetes/cluster"
CLUSTER="traderx-yu12-cluster"
CTX="kind-${CLUSTER}"
IMAGE="${YU15_CLUSTER_IMAGE:-traderx/cluster-node:yu15}"

if ! kind get clusters | grep -qx "${CLUSTER}"; then
  echo "[kind] creating cluster ${CLUSTER}"
  kind create cluster --config "${KDIR}/kind-cluster.yaml" --wait 120s
fi

echo "[kind] loading ${IMAGE}"
kind load docker-image "${IMAGE}" --name "${CLUSTER}"

kubectl --context "${CTX}" get namespace traderx >/dev/null 2>&1 \
  || kubectl --context "${CTX}" create namespace traderx

echo "[apply] cluster kustomization"
kubectl --context "${CTX}" apply -k "${KDIR}"

# The kind kustomization does not include gateway.yaml (inherited from the YU12 layout).
echo "[apply] gateway"
kubectl --context "${CTX}" -n traderx apply -f "${KDIR}/gateway.yaml"

echo "[wait] 3/3 members ready"
kubectl --context "${CTX}" -n traderx rollout status statefulset/order-matcher-cluster --timeout=300s
for d in nats eod-price-db cluster-gateway risk-extract; do
  echo "[wait] ${d}"
  kubectl --context "${CTX}" -n traderx rollout status "deployment/${d}" --timeout=300s
done
kubectl --context "${CTX}" -n traderx get pods -o wide
echo "[ok] YU15 cluster up on ${CTX}"
