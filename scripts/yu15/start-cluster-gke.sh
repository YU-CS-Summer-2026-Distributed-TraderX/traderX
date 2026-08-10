#!/usr/bin/env bash
# YU15 GKE one-shot deploy. Assumes images are already built (--platform linux/amd64) and pushed:
#   cluster-node:yu15-idempfix, trade-processor:yu15-extract, position-service:yu15-extract,
#   price-publisher:yu15-extract — and node pools scaled up (blp-c4d-tuned-pool + gateway nodes).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KDIR="${ROOT}/specs/YU16-cdm-instruments/generation/kubernetes/cluster/gke"
CTX="${YU15_GKE_CONTEXT:-gke_traderx-501015_us-east1-b_traderx-lmax}"

kubectl --context "${CTX}" get namespace traderx >/dev/null 2>&1 \
  || kubectl --context "${CTX}" create namespace traderx

# Outside the kustomization root — see the note in kustomization.yaml. Skipping this leaves the
# database unschema'd and the whole EOD chain (and therefore the extract trigger) dead.
# MUST be applied BEFORE the kustomization: mariadb runs init SQL once, at first boot on an empty
# datadir, against whatever configmap exists at that moment. In a namespace that already carried
# an older database-init-sql (the YU09 stack), applying it after let eod-price-db initialize
# against the NARROW pre-YU15 schema — VARCHAR(15) security columns, the exact OCC blocker this
# state fixed — and the option fill silently never reached SQL. Order is the fix.
echo "[apply] database schema configmap"
kubectl --context "${CTX}" -n traderx apply \
  -f "${ROOT}/specs/YU16-cdm-instruments/generation/runtime-overrides/kubernetes-runtime/manifests/base/database-init-configmap.yaml"

echo "[apply] gke kustomization"
kubectl --context "${CTX}" apply -k "${KDIR}"

echo "[wait] 3/3 members ready"
kubectl --context "${CTX}" -n traderx rollout status statefulset/order-matcher-cluster --timeout=600s
for d in nats eod-price-db trade-processor cluster-gateway risk-extract price-publisher position-service; do
  echo "[wait] ${d}"
  kubectl --context "${CTX}" -n traderx rollout status "deployment/${d}" --timeout=600s
done
kubectl --context "${CTX}" -n traderx get pods -o wide
echo "[ok] YU15 cluster up on ${CTX}"
