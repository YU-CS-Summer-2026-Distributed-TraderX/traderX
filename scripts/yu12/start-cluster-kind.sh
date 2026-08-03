#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KDIR="${ROOT}/specs/YU12-aeron-cluster/generation/kubernetes/cluster"
CLUSTER="traderx-yu12-cluster"
CTX="kind-${CLUSTER}"
IMAGE="${YU12_CLUSTER_IMAGE:-traderx/cluster-node:yu12}"

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

echo "[wait] 3/3 members ready"
kubectl --context "${CTX}" -n traderx rollout status statefulset/order-matcher-cluster --timeout=300s
kubectl --context "${CTX}" -n traderx get pods -l app=order-matcher-cluster -o wide
echo "[ok] YU12 cluster up on ${CTX}"
