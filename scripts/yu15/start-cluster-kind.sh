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

# ${IMAGE} USED TO REACH ONLY THE `kind load` LOOP. The workloads came from `apply -k`, which pins
# whatever the manifests declare, so naming a CLUSTER_IMAGE loaded one build onto the nodes and ran
# a different one — silently, under a [state] line that read as though the override had taken. A
# lane brought up what it believed was a YU17 tier that way on 2026-08-17: :yu17-ackfix faithfully
# loaded, members, gateway and risk-extract all on :yu16. The escape hatch delivered the exact
# failure the refusal printing it exists to prevent.
#
# The promise is that the image you name is the image that RUNS, so the apply below substitutes it
# into the rendered manifests. Two things that promise cannot do, both refusals rather than
# surprises:
#
#   * It cannot roll a LIVE tier. Changing the members' image on a cluster that already has state
#     is an engine roll, not a bring-up: the mixed-version window diverges the members permanently,
#     whether you get there by `set image` or by substituting into the manifests. The safe form is
#     scale to zero, wipe the PVCs, fresh epoch — which is what run-proofs.sh's rebuild_fresh_epoch
#     does, and why it is the right tool for that case.
#   * It cannot invent an image. `kind load` below already refuses on anything missing from the
#     local daemon.
DECLARED="$(declared_cluster_image "${ROOT}" 2>/dev/null || true)"
RUNNING="$(kubectl --context "${CTX}" -n traderx get statefulset order-matcher-cluster \
  -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"
if [[ -n "${RUNNING}" && "${RUNNING}" != "${IMAGE}" ]]; then
  cat >&2 <<EOF
[fail] the members are already running ${RUNNING}; you are asking for ${IMAGE}.
       Applying that would roll the deterministic core under a live tier, and a mixed-version
       window diverges the members permanently. Refusing.
       The safe form wipes the PVCs and mints a fresh epoch:
           CLUSTER_IMAGE=${IMAGE} bash scripts/yu15/run-proofs.sh <proof>
       Or tear the tier down first if you want this script to build it up clean.
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

# Render, substitute, apply — rather than `apply -k` — so that the named image reaches the
# workloads and not just the nodes. When no override is in play DECLARED == IMAGE and the sed is a
# no-op, so the normal path is byte-identical to what `apply -k` would have sent.
pin() { # a filter, so each apply keeps its own namespace handling: the kustomize render carries
        # explicit namespaces, gateway.yaml carries none and needs -n traderx.
  if [[ -n "${DECLARED}" && "${IMAGE}" != "${DECLARED}" ]]; then sed "s|${DECLARED}|${IMAGE}|g"; else cat; fi
}

if [[ -n "${DECLARED}" && "${IMAGE}" != "${DECLARED}" ]]; then
  echo "[apply] cluster kustomization, pinning ${DECLARED} -> ${IMAGE}"
else
  echo "[apply] cluster kustomization"
fi
kubectl kustomize "${KDIR}" | pin | kubectl --context "${CTX}" apply -f -

echo "[apply] gateway"
pin < "${KDIR}/gateway.yaml" | kubectl --context "${CTX}" -n traderx apply -f -

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

# An out-of-band pin is invisible to the next person, and invisibility is how it gets reverted.
# The substitution above means the running tier no longer matches what specs/ declares, so ANY
# plain `kubectl apply -k` from that directory — for a wholly unrelated reason — silently rolls the
# tier back to the declared image. That is not hypothetical twice over: it took a lane's
# :yu16-ackfix gateway back to :yu16 mid-measurement this morning, and my own `apply -k` reverted
# four service tags to :yu15 the same way. Say it at the end, where a handback gets read.
if [[ -n "${DECLARED}" && "${IMAGE}" != "${DECLARED}" ]]; then
  cat <<EOF
[warn] this tier is pinned OUT OF BAND: running ${IMAGE}, while
       ${KDIR#"${ROOT}/"} still declares ${DECLARED}.
       Any plain \`kubectl apply -k\` from that directory will revert it, silently and without
       an epoch. If you hand this rig over, hand over this line with it.
       To make the pin durable instead, change the tag in the manifests and commit it.
EOF
fi
