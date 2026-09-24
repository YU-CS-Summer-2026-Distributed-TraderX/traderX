#!/usr/bin/env bash
# Attach the TraderX web UI to the Aeron cluster rig.
#
# The cluster tier is backend-only: it ships the 3 members, the gateway, and the services the
# proofs need, but no UI. The upstream UI stack was always MEANT to sit on top of it — the rig
# deliberately names a Service `order-matcher` fronting `cluster-gateway`, and the gateway accepts
# both `ticker` and `security` in an order body precisely so trade-service works unmodified. What
# was missing was never code, only three Service names.
#
# Verified end to end on 2026-08-07: an order posted to edge-proxy /trade-service/trade/ validated
# its ticker against reference-data and its account against account-service, reached the cluster
# gateway, and was applied by ALL THREE members (applied 1347 -> 1348, trades 6 -> 7, identical on
# every member).
#
# Usage: bash scripts/yu15/start-frontend-kind.sh
set -euo pipefail

CTX="${CTX:-kind-${KIND_CLUSTER_NAME:-traderx-yu12-cluster}}"
NS="${NS:-traderx}"
CLUSTER="${CLUSTER:-${KIND_CLUSTER_NAME:-traderx-yu12-cluster}}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GEN="${ROOT}/generated/code/target-generated/tilt-kubernetes-dev-loop/manifests/base"
K="kubectl --context ${CTX} -n ${NS}"

[[ -d "${GEN}" ]] || { echo "[fail] generated manifests missing; run: bash pipeline/generate-state.sh YU15-eod-risk-extract"; exit 1; }

# The operative edge-proxy config (YU17) names tempo, grafana, prometheus and api-explorer as STATIC
# upstreams, and nginx refuses to start when any of them does not resolve: on a fresh rig the proxy
# crash-looped with `host not found in upstream "tempo"` (2026-09-24, RI-07). Order the bring-up
# cluster -> observability -> this script, and deploy api-explorer here, which nothing else did.
missing_svc=()
for svc in tempo grafana prometheus; do
  kubectl --context "${CTX}" -n "${NS}" get svc "${svc}" >/dev/null 2>&1 || missing_svc+=("${svc}")
done
if [[ ${#missing_svc[@]} -gt 0 ]]; then
  echo "[fail] edge-proxy needs services that are not on ${CTX}: ${missing_svc[*]}"
  echo "       run scripts/yu15/start-observability-kind.sh first (same KIND_CLUSTER_NAME)"
  exit 1
fi
RUNTIME_BASE="${ROOT}/generated/code/target-generated/kubernetes-runtime/manifests/base"
EXPLORER_IMAGE="traderx-api-explorer:local"
docker image inspect "${EXPLORER_IMAGE}" >/dev/null 2>&1 || {
  echo "[fail] ${EXPLORER_IMAGE} not in the local Docker daemon (edge-proxy routes /api/docs to it)"
  echo "[hint] docker build -t ${EXPLORER_IMAGE} ${ROOT}/generated/code/target-generated/api-explorer"
  exit 1
}

STAGE="$(mktemp -d)"
trap 'rm -rf "${STAGE}"' EXIT

# Take the newest spec-layer override of each manifest, falling back to the generated base. Same
# last-wins rule generation itself uses -- a hand-picked path would silently pin an older layer.
# trade-service in particular MUST come from YU12: that override is the one that points at the
# cluster gateway rather than the single-BLP order-matcher.
echo "[stage] resolving the operative manifest for each UI service"
for svc in people-service account-service trade-service web-front-end-angular edge-proxy; do
  for kind in deployment service; do
    f="$(find "${ROOT}/specs" -name "${svc}-${kind}.yaml" 2>/dev/null | sort | tail -1)"
    [[ -n "${f}" ]] || f="${GEN}/${svc}-${kind}.yaml"
    [[ -f "${f}" ]] || { echo "[fail] no manifest for ${svc}-${kind}"; exit 1; }
    cp "${f}" "${STAGE}/${svc}-${kind}.yaml"
    printf '   %-34s %s\n' "${svc}-${kind}" "${f#"${ROOT}/"}"
  done
done
f="$(find "${ROOT}/specs" -name 'edge-proxy-configmap.yaml' 2>/dev/null | sort | tail -1)"
[[ -n "${f}" ]] || f="${GEN}/edge-proxy-configmap.yaml"
cp "${f}" "${STAGE}/edge-proxy-configmap.yaml"
for kind in deployment service; do
  cp "${RUNTIME_BASE}/api-explorer-${kind}.yaml" "${STAGE}/api-explorer-${kind}.yaml" \
    || { echo "[fail] no rendered api-explorer-${kind}.yaml under ${RUNTIME_BASE}"; exit 1; }
done

# THE ENTIRE INTEGRATION. The UI services address the single-BLP tier's names; this rig uses its
# own. Aliases rather than patched env, so the upstream manifests stay byte-identical to what the
# other tier deploys and there is no second copy to keep in sync.
#
#   database         -> eod-price-db   (same MariaDB schema: accounts, positions, trades, stocks)
#   nats-broker      -> nats           (client 4222 and the 8081 websocket /nats-ws uses)
#   order-matcher-gw -> cluster-gateway (YU12's trade-service targets this name; without it the
#                       order validates fully and then dies on UnknownHostException at the last hop)
cat > "${STAGE}/aeron-tier-aliases.yaml" <<'YAML'
apiVersion: v1
kind: Service
metadata: { name: database }
spec:
  selector: { app: eod-price-db }
  ports: [{ name: mysql, protocol: TCP, port: 3306, targetPort: 3306 }]
---
apiVersion: v1
kind: Service
metadata: { name: nats-broker }
spec:
  selector: { app: nats }
  # 8081 is the websocket the edge proxy's /nats-ws targets: without it the console's live prices
  # and live blotter feed silently fall back to "no live price" / polling (RI-07, 2026-09-24).
  ports:
    - { name: client, protocol: TCP, port: 4222, targetPort: 4222 }
    - { name: websocket, protocol: TCP, port: 8081, targetPort: 8081 }
---
apiVersion: v1
kind: Service
metadata: { name: order-matcher-gw }
spec:
  selector: { app: cluster-gateway }
  ports:
    - { name: http, protocol: TCP, port: 18110, targetPort: 18110 }
    - { name: fix,  protocol: TCP, port: 18130, targetPort: 18130 }
YAML

echo "[load] UI images into the kind nodes"
for img in traderx/people-service:state009 traderx/account-service:state009 \
           traderx/trade-service:state009 traderx/web-front-end-angular:state009 "${EXPLORER_IMAGE}"; do
  docker image inspect "${img}" >/dev/null 2>&1 \
    || { echo "[fail] ${img} not present locally — build the single-BLP tier's images first"; exit 1; }
  kind load docker-image "${img}" --name "${CLUSTER}" >/dev/null 2>&1 \
    || echo "   [warn] could not kind-load ${img}; assuming it is already on the nodes"
done

echo "[apply] UI stack + name aliases"
${K} apply -f "${STAGE}" >/dev/null
# nginx resolves static upstreams once, at start: a proxy that crash-looped before its upstreams
# existed sits in back-off long past the moment they appear. Restarting is idempotent and cheap.
${K} rollout restart deployment/edge-proxy >/dev/null
for d in people-service account-service trade-service web-front-end-angular api-explorer edge-proxy; do
  ${K} rollout status "deployment/${d}" --timeout=180s >/dev/null
  echo "   ${d} ready"
done

echo
echo "[ok] UI attached to the Aeron cluster tier."
echo "     kubectl --context ${CTX} -n ${NS} port-forward svc/edge-proxy 8080:8080"
echo "     then open http://localhost:8080"
