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

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
CLUSTER="${CLUSTER:-traderx-yu12-cluster}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GEN="${ROOT}/generated/code/target-generated/tilt-kubernetes-dev-loop/manifests/base"
K="kubectl --context ${CTX} -n ${NS}"

[[ -d "${GEN}" ]] || { echo "[fail] generated manifests missing; run: bash pipeline/generate-state.sh YU15-eod-risk-extract"; exit 1; }

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

# THE ENTIRE INTEGRATION. The UI services address the single-BLP tier's names; this rig uses its
# own. Aliases rather than patched env, so the upstream manifests stay byte-identical to what the
# other tier deploys and there is no second copy to keep in sync.
#
#   database         -> eod-price-db   (same MariaDB schema: accounts, positions, trades, stocks)
#   nats-broker      -> nats
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
  ports: [{ name: client, protocol: TCP, port: 4222, targetPort: 4222 }]
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
           traderx/trade-service:state009 traderx/web-front-end-angular:state009; do
  docker image inspect "${img}" >/dev/null 2>&1 \
    || { echo "[fail] ${img} not present locally — build the single-BLP tier's images first"; exit 1; }
  kind load docker-image "${img}" --name "${CLUSTER}" >/dev/null 2>&1 \
    || echo "   [warn] could not kind-load ${img}; assuming it is already on the nodes"
done

echo "[apply] UI stack + name aliases"
${K} apply -f "${STAGE}" >/dev/null
for d in people-service account-service trade-service web-front-end-angular edge-proxy; do
  ${K} rollout status "deployment/${d}" --timeout=180s >/dev/null
  echo "   ${d} ready"
done

echo
echo "[ok] UI attached to the Aeron cluster tier."
echo "     kubectl --context ${CTX} -n ${NS} port-forward svc/edge-proxy 8080:8080"
echo "     then open http://localhost:8080"
