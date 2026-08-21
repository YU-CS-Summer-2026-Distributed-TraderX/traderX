#!/usr/bin/env bash
# start-observability-gke.sh — the LGTM stack (Loki, Grafana, Tempo, Prometheus) + OTel collector
# on the GKE rig. GKE twin of start-observability-kind.sh.
#
# Every image here is public (grafana/*, prom/*, otel/*), so unlike the rest of the GKE tier there
# is nothing to build or push — this is manifests only.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CTX="${CTX:-gke_traderx-505400_us-east1-b_traderx-bench}"
NS="${NS:-traderx}"
BASE="${ROOT}/generated/code/target-generated/kubernetes-runtime/manifests/base"
K=(kubectl --context "${CTX}" -n "${NS}")

STAGE="$(mktemp -d)"; trap 'rm -rf "${STAGE}"' EXIT

resolve() { # resolve <name> -> newest spec-layer override, else the generated base
  local f; f="$(find "${ROOT}/specs" -name "${1}.yaml" 2>/dev/null | sort | tail -1)"
  [[ -n "${f}" ]] || f="${BASE}/${1}.yaml"
  [[ -f "${f}" ]] || { echo "[fail] no manifest for ${1}" >&2; exit 1; }
  cp "${f}" "${STAGE}/${1}.yaml"
}

# Configmaps BEFORE the deployments that mount them, so nothing starts against a missing volume.
echo "[stage] resolving manifests (newest spec override wins, same rule generation uses)"
for f in observability-otel-configmap observability-tempo-configmap \
         observability-prometheus-configmap observability-loki-configmap \
         observability-promtail-configmap observability-grafana-datasources-configmap \
         observability-grafana-dashboard-providers-configmap observability-grafana-dashboards-configmap; do
  resolve "${f}"
done
for f in otel-collector tempo prometheus loki grafana; do
  resolve "${f}-deployment"; resolve "${f}-service"
done
for f in promtail-serviceaccount promtail-clusterrole promtail-clusterrolebinding promtail-daemonset; do
  resolve "${f}"
done

echo "[apply] configmaps"
for f in "${STAGE}"/observability-*.yaml; do "${K[@]}" apply -f "${f}" >/dev/null; done
echo "[apply] deployments + services"
for f in otel-collector tempo prometheus loki grafana; do
  "${K[@]}" apply -f "${STAGE}/${f}-deployment.yaml" >/dev/null
  # These Services replace the resolvable-but-unbacked placeholders created during bring-up so the
  # edge proxy's nginx could start at all — nginx refuses to boot on an unknown upstream.
  "${K[@]}" apply -f "${STAGE}/${f}-service.yaml" >/dev/null
done
echo "[apply] promtail (log shipper — needs its own RBAC)"
for f in promtail-serviceaccount promtail-clusterrole promtail-clusterrolebinding promtail-daemonset; do
  "${K[@]}" apply -f "${STAGE}/${f}.yaml" >/dev/null
done

# PROMTAIL MUST TOLERATE EVERY TAINT, and the shipped manifest tolerates none. This rig taints two
# of its three pools (workload=blp on the members, role=gateway on the gateways), so an untolerated
# DaemonSet lands ONLY on the support node — collecting logs from the database and the batch chain
# while silently collecting nothing from the three cluster members or the three gateways, which are
# the services anyone actually opens Grafana to look at. The gap does not error: Explore shows
# fewer streams, not a failure.
echo "[patch] promtail tolerates all taints, so member and gateway logs are collected"
"${K[@]}" patch daemonset promtail --type merge \
  -p '{"spec":{"template":{"spec":{"tolerations":[{"operator":"Exists"}]}}}}' >/dev/null

# Grafana's root_url is "%(protocol)s://%(domain)s/grafana/" and NOTHING sets %(domain)s, so it
# falls back to Grafana's default — `localhost`. Everything that reaches Grafana on a path already
# under /grafana/ works, which is every route the console proxies, so the defect is invisible from
# the app. But the DEDICATED HOST is broken at its root: https://grafana.yaakovseif.dev/ answers
# 301 -> http://localhost/grafana/, and anyone who types the hostname lands nowhere.
#
# A config value true where it was authored (a laptop, where localhost IS the host) and false where
# it runs. Setting the domain fixes the bare host and — verified on the rig, both arms — leaves the
# console-proxied path untouched, because that one is already inside the sub-path and Grafana emits
# a relative redirect for it.
#
# PROTOCOL stays http on purpose: GF_SERVER_PROTOCOL is the LISTENER's protocol, not the scheme in
# generated URLs. Setting it to https makes Grafana try to terminate TLS itself and fail. The LB
# terminates TLS and its own http->https redirect upgrades the one intermediate hop.
GRAFANA_DOMAIN="${GRAFANA_DOMAIN:-grafana.yaakovseif.dev}"
echo "[patch] grafana root_url domain -> ${GRAFANA_DOMAIN} (else the bare host 301s to localhost)"
"${K[@]}" set env deployment/grafana "GF_SERVER_DOMAIN=${GRAFANA_DOMAIN}" >/dev/null

for d in otel-collector tempo prometheus loki grafana; do
  "${K[@]}" rollout status "deployment/${d}" --timeout=300s >/dev/null && echo "   ${d} ready"
done

# Grafana reads its provisioning ONCE at startup, and the datasources file is a subPath mount, which
# kubelet never refreshes on a ConfigMap change. On a re-run against an already-running Grafana the
# applied datasources would silently still be the previous ones.
echo "[restart] grafana, so it re-reads provisioning"
"${K[@]}" rollout restart deployment/grafana >/dev/null
"${K[@]}" rollout status deployment/grafana --timeout=300s >/dev/null
"${K[@]}" rollout status daemonset/promtail --timeout=300s >/dev/null && echo "   promtail ready on all nodes"
echo "[ok] observability stack up."
