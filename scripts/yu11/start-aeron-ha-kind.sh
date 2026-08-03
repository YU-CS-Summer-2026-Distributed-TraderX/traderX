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

if [[ "${TRADERX_SKIP_BUILD:-0}" != "1" ]]; then
  (
    cd "${TARGET}/order-matcher"
    ./gradlew --no-daemon bootJar
  )
  jar_file="$(find "${TARGET}/order-matcher/build/libs" -maxdepth 1 -type f \
    -name '*.jar' ! -name '*-plain.jar' -print -quit)"
  [[ -n "${jar_file}" ]] || { echo "[error] order-matcher boot jar not found"; exit 1; }
  jar_context_path="${jar_file#"${TARGET}/order-matcher/"}"
  docker build -f "${TARGET}/order-matcher/Dockerfile.kind" \
    --build-arg "JAR_FILE=${jar_context_path}" \
    -t traderx/order-matcher:yu11-aeron-replication "${TARGET}/order-matcher"
  docker build -f "${TARGET}/aeron-replication-sidecar/Dockerfile.compose" \
    -t traderx/aeron-replication-sidecar:yu11-aeron-replication \
    "${TARGET}/aeron-replication-sidecar"
fi

registry_name="traderx-yu11-registry"
registry_host_port="${TRADERX_KIND_REGISTRY_PORT:-5001}"
if ! docker inspect "${registry_name}" >/dev/null 2>&1; then
  docker run -d --restart=unless-stopped \
    -p "127.0.0.1:${registry_host_port}:5000" --name "${registry_name}" registry:2
elif [[ "$(docker inspect -f '{{.State.Running}}' "${registry_name}")" != "true" ]]; then
  docker start "${registry_name}" >/dev/null
fi
if ! docker network inspect kind --format '{{json .Containers}}' | \
    grep -q "${registry_name}"; then
  docker network connect kind "${registry_name}"
fi
for node in "${CLUSTER}-control-plane" "${CLUSTER}-worker" "${CLUSTER}-worker2"; do
  docker exec "${node}" mkdir -p \
    "/etc/containerd/certs.d/${registry_name}:5000"
  docker exec "${node}" sh -c \
    "printf '%s\\n' 'server = \"http://${registry_name}:5000\"' '' \
      '[host.\"http://${registry_name}:5000\"]' \
      '  capabilities = [\"pull\", \"resolve\"]' \
      > /etc/containerd/certs.d/${registry_name}:5000/hosts.toml"
done

image_sources=(
  traderx/order-matcher:yu11-aeron-replication
  traderx/aeron-replication-sidecar:yu11-aeron-replication
  traderx/reference-data:state009
  traderx/account-service:state009
  traderx/position-service:state009
  traderx/price-publisher:state009
)
image_paths=(
  order-matcher aeron-replication-sidecar reference-data account-service
  position-service price-publisher
)
run_tag="kind-$(date -u +%Y%m%d%H%M%S)"
external_registry="localhost:${registry_host_port}/traderx"
internal_registry="${registry_name}:5000/traderx"
for index in "${!image_sources[@]}"; do
  docker tag "${image_sources[$index]}" \
    "${external_registry}/${image_paths[$index]}:${run_tag}"
  docker push "${external_registry}/${image_paths[$index]}:${run_tag}"
done

kubectl --context "${CONTEXT}" create namespace traderx \
  --dry-run=client -o yaml | kubectl --context "${CONTEXT}" apply -f -
kubectl --context "${CONTEXT}" -n traderx create secret generic mariadb-credentials \
  --from-literal=username=traderx \
  --from-literal=password=traderx \
  --from-literal=root-password=traderx \
  --dry-run=client -o yaml | kubectl --context "${CONTEXT}" apply -f -
kubectl --context "${CONTEXT}" -n traderx create secret generic auth-secrets \
  --from-literal=jwt-secret=dev-jwt-shared-secret \
  --from-literal=dev-token-master-secret=dev-token-master-secret \
  --dry-run=client -o yaml | kubectl --context "${CONTEXT}" apply -f -

kubectl --context "${CONTEXT}" apply -k "${TARGET}/kubernetes-runtime/manifests/base"
kubectl --context "${CONTEXT}" -n traderx scale deployment \
  edge-proxy people-service trade-processor trade-service web-front-end-angular \
  execution-algo-engine api-explorer tick-store blackbox-exporter grafana loki \
  otel-collector prometheus tempo --replicas=0
kubectl --context "${CONTEXT}" -n traderx delete daemonset promtail --ignore-not-found
kubectl --context "${CONTEXT}" -n traderx patch cronjob eod-session-close \
  --type=merge -p '{"spec":{"suspend":true}}'
kubectl --context "${CONTEXT}" -n traderx delete job -l app=eod-session-close --ignore-not-found
for index in $(seq 2 $((${#image_paths[@]} - 1))); do
  component="${image_paths[$index]}"
  kubectl --context "${CONTEXT}" -n traderx set image \
    "deployment/${component}" "${component}=${internal_registry}/${component}:${run_tag}"
done
kubectl --context "${CONTEXT}" -n traderx delete deployment order-matcher --ignore-not-found
kubectl --context "${CONTEXT}" apply -k \
  "${TARGET}/YU11-aeron-replication/runtime/kubernetes/kind"
# The checked-in manifest names production images. Stage at zero before selecting the immutable
# local-registry tag; otherwise Parallel pod creation can race `set image`, and StatefulSet rollout
# ordering then waits forever on an old image that kind cannot pull.
kubectl --context "${CONTEXT}" -n traderx scale statefulset/order-matcher --replicas=0
kubectl --context "${CONTEXT}" -n traderx delete pod -l app=order-matcher \
  --wait=true --ignore-not-found
# This dedicated kind harness always recreates the full matcher pair. Once both replicas are
# stopped, the prior holder is gone and retaining its Lease only makes repeat runs inherit a stale
# election. Never delete this Lease while either replica is running.
kubectl --context "${CONTEXT}" -n traderx delete lease order-matcher-leader --ignore-not-found
kubectl --context "${CONTEXT}" -n traderx set image statefulset/order-matcher \
  order-matcher="${internal_registry}/order-matcher:${run_tag}" \
  aeron-replication-sidecar="${internal_registry}/aeron-replication-sidecar:${run_tag}"
kubectl --context "${CONTEXT}" -n traderx scale statefulset/order-matcher --replicas=2
kubectl --context "${CONTEXT}" -n traderx rollout status statefulset/order-matcher --timeout=10m

echo "[done] dedicated YU11 multi-node kind profile is running"
echo "[next] bash scripts/yu11/test-aeron-ha-kind.sh"
