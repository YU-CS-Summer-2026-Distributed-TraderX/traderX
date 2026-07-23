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

# Outside the kustomization — see the note in kustomization.yaml. The schema configmap goes FIRST:
# mariadb runs init SQL once, at first boot on an empty datadir, against whatever configmap exists
# at that moment. In a reused namespace carrying an older database-init-sql, applying it after the
# kustomization lets eod-price-db initialize against the narrow pre-YU15 schema (the OCC blocker).
echo "[apply] database schema configmap"
kubectl --context "${CTX}" -n traderx apply \
  -f "${ROOT}/specs/YU15-eod-risk-extract/generation/runtime-overrides/kubernetes-runtime/manifests/base/database-init-configmap.yaml"

echo "[apply] cluster kustomization"
kubectl --context "${CTX}" apply -k "${KDIR}"

echo "[apply] gateway"
kubectl --context "${CTX}" -n traderx apply -f "${KDIR}/gateway.yaml"

echo "[wait] 3/3 members ready"
kubectl --context "${CTX}" -n traderx rollout status statefulset/order-matcher-cluster --timeout=300s
for d in nats eod-price-db trade-processor cluster-gateway risk-extract; do
  echo "[wait] ${d}"
  kubectl --context "${CTX}" -n traderx rollout status "deployment/${d}" --timeout=300s
done
kubectl --context "${CTX}" -n traderx get pods -o wide
echo "[ok] YU15 cluster up on ${CTX}"
