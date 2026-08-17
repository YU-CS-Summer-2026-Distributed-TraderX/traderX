#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=lib-state-image.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib-state-image.sh"

# KDIR and IMAGE are DERIVED from the state this worktree is, not hardcoded. Both used to be
# literals, and both went stale at the YU16 cut in different directions: this path still named
# YU16 on the YU17 branch (so the tip applied its ANCESTOR's manifests), while the manifests it
# pointed at still declared :yu15. Two copies of "which build is this tier", disagreeing, silently.
# cluster_manifest_dir warns loudly when it has to fall back to an ancestor's layer.
KDIR="$(cluster_manifest_dir "${ROOT}")" || exit 1
CLUSTER="traderx-yu12-cluster"
CTX="kind-${CLUSTER}"
# The manifests are the authority: whatever tag they declare is what kubectl will apply, so
# defaulting to anything else would just be a fourth copy of the truth. YU15_CLUSTER_IMAGE stays
# for explicit overrides; CLUSTER_IMAGE is the neutral name (a state id inside a variable used by
# every state is the smell this whole change is about).
IMAGE="${CLUSTER_IMAGE:-${YU15_CLUSTER_IMAGE:-$(declared_cluster_image "${ROOT}")}}"
[[ -n "${IMAGE}" ]] || { echo "[fail] could not determine the cluster image and will not guess one"; exit 1; }
echo "[state] $(state_pack "${ROOT}") -> manifests ${KDIR#"${ROOT}/"}, image ${IMAGE}"

# IMAGE IS LOAD-ONLY IN THIS SCRIPT, and that is worth refusing over rather than documenting.
# Everything below uses ${IMAGE} for `kind load`; the workloads come from `apply -k`, which pins
# whatever the manifests declare. So naming a different CLUSTER_IMAGE here loads a build onto the
# nodes and then runs a different one — silently, while the [state] line above reads as if you got
# what you asked for. A lane brought up what it believed was a YU17 tier this way on 2026-08-17:
# :yu17-ackfix faithfully loaded, members, gateway and risk-extract all running :yu16.
#
# run-proofs.sh is the path that honours the override, because it repins the three workloads after
# applying. This script cannot without turning a bring-up into an engine roll.
DECLARED="$(declared_cluster_image "${ROOT}" 2>/dev/null || true)"
if [[ -n "${DECLARED}" && "${IMAGE}" != "${DECLARED}" ]]; then
  cat >&2 <<EOF
[fail] you named ${IMAGE}, but ${KDIR#"${ROOT}/"} declares ${DECLARED}.
       This script would load yours onto the nodes and then run theirs. Refusing.
       To bring the tier up ON the image you named:
           CLUSTER_IMAGE=${IMAGE} bash scripts/yu15/run-proofs.sh <proof>
       To bring it up on the declared image, just drop the override.
EOF
  exit 1
fi

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
# Also derived: runtime-overrides compose last-wins, so the operative configmap is the one in the
# highest layer carrying it. Hardcoding YU16's here meant the YU17 branch applied YU16's schema
# and seeds — the same silent-ancestor bug as KDIR, surfacing much later as a missing table.
DBCM="$(operative_layer_file "${ROOT}" \
  generation/runtime-overrides/kubernetes-runtime/manifests/base/database-init-configmap.yaml)" || exit 1
echo "[state] db-init configmap: ${DBCM#"${ROOT}/"}"
kubectl --context "${CTX}" -n traderx apply -f "${DBCM}"

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
