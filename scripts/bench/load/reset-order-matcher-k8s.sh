#!/usr/bin/env bash
# RESET_CMD for avg-max-load.mjs on a kind/k8s deployment (no docker compose available).
# Restarts the order-matcher Deployment, waits for rollout, then re-establishes the
# port-forward since kubectl port-forward does not follow a pod replacement automatically.
set -euo pipefail
CTX="${KUBE_CTX:-kind-traderx-state-014}"
NS="${NAMESPACE:-traderx}"
PF_PID_FILE="/tmp/pf-order-matcher.pid"

kubectl --context "${CTX}" rollout restart deployment/order-matcher -n "${NS}" >/dev/null
kubectl --context "${CTX}" rollout status deployment/order-matcher -n "${NS}" --timeout=90s >/dev/null

if [[ -f "${PF_PID_FILE}" ]]; then
  kill "$(cat "${PF_PID_FILE}")" 2>/dev/null || true
fi
kubectl --context "${CTX}" port-forward svc/order-matcher 18110:18110 -n "${NS}" > /tmp/pf-order-matcher.log 2>&1 &
echo $! > "${PF_PID_FILE}"
sleep 2
