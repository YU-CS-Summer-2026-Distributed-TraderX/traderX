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

# Every locally-built image the kustomization references, not just the cluster node.
#
# This used to load ${IMAGE} alone, which worked only by accident: on a REUSED cluster the four
# service images were already in the node's containerd from some earlier manual `kind load`, so
# nothing looked wrong. Create the cluster fresh -- which is the normal thing to do after a
# teardown, and what the quickstart tells you to do -- and trade-processor, position-service and
# price-publisher all ImagePullBackOff, because `traderx/...:yu15` exists nowhere but this laptop's
# Docker daemon and imagePullPolicy is IfNotPresent.
#
# Missing images are named and fatal here rather than surfacing ten minutes later as a rollout
# timeout on a pod whose events you have to go read.
IMAGES=(
  "${IMAGE}"
  "${YU15_TRADE_PROCESSOR_IMAGE:-traderx/trade-processor:yu15}"
  "${YU15_POSITION_SERVICE_IMAGE:-traderx/position-service:yu15}"
  "${YU15_PRICE_PUBLISHER_IMAGE:-traderx/price-publisher:yu15}"
  "${YU15_ALGO_ENGINE_IMAGE:-traderx/execution-algo-engine:yu15}"
  "${YU15_REFERENCE_DATA_IMAGE:-traderx/reference-data:yu15}"
)
missing=()
for img in "${IMAGES[@]}"; do
  docker image inspect "${img}" >/dev/null 2>&1 || missing+=("${img}")
done
if [[ ${#missing[@]} -gt 0 ]]; then
  echo "[fail] not in the local Docker daemon: ${missing[*]}"
  echo "[hint] cluster-node: bash scripts/yu15/build-cluster-image.sh"
  echo "[hint] the Spring services: build from generated/code/target-generated/<svc> with its Dockerfile"
  exit 1
fi
for img in "${IMAGES[@]}"; do
  echo "[kind] loading ${img}"
  kind load docker-image "${img}" --name "${CLUSTER}"
done

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
# price-publisher and position-service were missing from this list even though eod-chain.yaml
# deploys them, so the script could report the tier "up" while the EOD chain was still coming
# round -- and the extract's only trigger is that chain.
for d in nats eod-price-db trade-processor cluster-gateway risk-extract \
         price-publisher position-service execution-algo-engine reference-data; do
  echo "[wait] ${d}"
  kubectl --context "${CTX}" -n traderx rollout status "deployment/${d}" --timeout=300s
done
kubectl --context "${CTX}" -n traderx get pods -o wide
echo "[ok] YU15 cluster up on ${CTX}"
