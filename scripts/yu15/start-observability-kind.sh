#!/usr/bin/env bash
# Deploy the LGTM observability platform into the SAME kind cluster the Aeron cluster tier runs in.
#
# WHY THIS EXISTS. The platform (OTel Collector, Tempo, Prometheus, Grafana) has shipped in the
# kubernetes-runtime manifests since state 007, but `scripts/yu15/start-cluster-kind.sh` deploys
# only the cluster tier — so on kind the two halves land in different clusters and
# OTEL_ENDPOINT=http://otel-collector:4318 resolves to nothing. The trace pipeline is then silently
# dead: orders book fine, spans go nowhere, and the only symptom is an empty Tempo. This applies the
# observability subset into the cluster-tier context so the DNS name the manifests already use
# actually resolves.
#
# Deliberately NOT the whole platform: loki/promtail/blackbox are log and probe plumbing that the
# trace story does not need, and each is another image pull on a laptop.
#
# Usage:  bash scripts/yu15/start-observability-kind.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE="${ROOT}/generated/code/target-generated/kubernetes-runtime/manifests/base"
CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"

[[ -d "${BASE}" ]] || {
  echo "[fail] no rendered kubernetes-runtime at ${BASE}"
  echo "       run: TRADERX_SKIP_LOCKFILE_REFRESH=1 bash pipeline/generate-state.sh YU15-eod-risk-extract"
  exit 1
}
kubectl --context "${CTX}" get ns "${NS}" >/dev/null 2>&1 || {
  echo "[fail] namespace ${NS} not found on ${CTX} — run scripts/yu15/start-cluster-kind.sh first"
  exit 1
}

# Configmaps BEFORE the deployments that mount them, so nothing starts against a missing volume.
echo "[apply] configmaps"
for f in observability-otel-configmap observability-tempo-configmap \
         observability-prometheus-configmap observability-grafana-datasources-configmap \
         observability-grafana-dashboard-providers-configmap observability-grafana-dashboards-configmap; do
  ${K} apply -f "${BASE}/${f}.yaml"
done

echo "[apply] deployments + services"
for f in otel-collector tempo prometheus grafana; do
  ${K} apply -f "${BASE}/${f}-deployment.yaml"
  ${K} apply -f "${BASE}/${f}-service.yaml"
done

for d in otel-collector tempo prometheus grafana; do
  echo "[wait] ${d}"
  ${K} rollout status "deployment/${d}" --timeout=300s
done

# The members and gateway resolve otel-collector at STARTUP; if they booted before the collector
# existed they are exporting into a black hole. Restarting them is cheap and idempotent — the
# cluster re-elects and the log is untouched.
echo "[restart] cluster tier so it re-resolves the collector"
${K} rollout restart deployment/cluster-gateway
${K} rollout restart statefulset/order-matcher-cluster
${K} rollout status deployment/cluster-gateway --timeout=300s
${K} rollout status statefulset/order-matcher-cluster --timeout=300s

echo "[ok] observability platform up on ${CTX}"
echo
echo "Port-forward for the UI:"
echo "  kubectl --context ${CTX} -n ${NS} port-forward svc/grafana 3000:3000 &"
echo "  kubectl --context ${CTX} -n ${NS} port-forward svc/tempo 3200:3200 &"
echo "  kubectl --context ${CTX} -n ${NS} port-forward svc/order-matcher 18110:18110 &"
