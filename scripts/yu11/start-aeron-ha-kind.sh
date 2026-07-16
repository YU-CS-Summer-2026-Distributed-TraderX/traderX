#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET="${ROOT}/generated/code/target-generated"
CLUSTER="traderx-yu11-aeron"
CONTEXT="kind-${CLUSTER}"

if [[ "${TRADERX_SKIP_GENERATE:-0}" != "1" ]]; then
  bash "${ROOT}/pipeline/generate-state.sh" YU11-aeron-replication
fi

if ! kind get clusters | grep -qx "${CLUSTER}"; then
  kind create cluster --name "${CLUSTER}" --config \
    "${TARGET}/YU11-aeron-replication/runtime/kubernetes/kind/cluster.yaml"
fi

docker build -f "${TARGET}/order-matcher/Dockerfile.compose" \
  -t traderx/order-matcher:yu11-aeron-replication "${TARGET}/order-matcher"
docker build -f "${TARGET}/aeron-replication-sidecar/Dockerfile.compose" \
  -t traderx/aeron-replication-sidecar:yu11-aeron-replication \
  "${TARGET}/aeron-replication-sidecar"
kind load docker-image --name "${CLUSTER}" \
  traderx/order-matcher:yu11-aeron-replication \
  traderx/aeron-replication-sidecar:yu11-aeron-replication

kubectl --context "${CONTEXT}" apply -k "${TARGET}/kubernetes-runtime/manifests/base"
kubectl --context "${CONTEXT}" -n traderx delete deployment order-matcher --ignore-not-found
kubectl --context "${CONTEXT}" apply -k \
  "${TARGET}/YU11-aeron-replication/runtime/kubernetes/kind"
kubectl --context "${CONTEXT}" -n traderx rollout status statefulset/order-matcher --timeout=10m

echo "[done] dedicated YU11 multi-node kind profile is running"
echo "[next] bash scripts/yu11/test-aeron-ha-kind.sh"
