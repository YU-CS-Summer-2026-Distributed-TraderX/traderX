#!/usr/bin/env bash
# start-frontend-gke.sh — attach the UI/app tier to the YU17 Aeron cluster tier on GKE.
#
# The GKE kustomization is CLUSTER-TIER ONLY: members, gateways, NATS, the DB, trade-processor, the
# EOD chain, risk-extract and the algo engine. It has never carried account/people/trade-service,
# the edge proxy or any frontend — so a rig brought up from it has no UI at all. This is the GKE
# twin of start-frontend-kind.sh and follows the same two rules for the same reasons.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CTX="${CTX:-gke_traderx-505400_us-east1-b_traderx-bench}"
NS="${NS:-traderx}"
REG="${REG:-us-east1-docker.pkg.dev/traderx-505400/traderx}"
TAG="${TAG:-yu17-gke}"
GEN="${ROOT}/generated/code/target-generated/kubernetes-runtime/manifests/base"
K=(kubectl --context "${CTX}" -n "${NS}")

STAGE="$(mktemp -d)"; trap 'rm -rf "${STAGE}"' EXIT

# RULE 1: take the NEWEST spec-layer override of each manifest, falling back to the generated base.
# Same last-wins rule generation itself uses — a hand-picked path silently pins an older layer.
# trade-service in particular MUST come from its latest override: that one points at the cluster
# gateway rather than the single-BLP order-matcher.
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
cp "${f}" "${STAGE}/edge-proxy-configmap.yaml"

# THE RESOLVER IP IS RIG-SPECIFIC AND THE CONFIG HARDCODES KIND'S. nginx needs a literal address
# for `resolver`, and the shipped value (10.96.0.10) is kube-dns on kind's service CIDR. GKE hands
# out a different one, so every location that resolves through a variable — the per-member /m0../m2
# routes and /tempo — fails with "could not be resolved (110: Operation timed out)" and returns 502.
# The console then renders "members diverge or unreachable", which reads as a cluster fault and is
# really one wrong IP; the members were healthy and agreeing the whole time.
# Discovered rather than hardcoded, so this is right on any cluster.
KUBEDNS="$(kubectl --context "${CTX}" -n kube-system get svc kube-dns -o jsonpath='{.spec.clusterIP}')"
[[ -n "${KUBEDNS}" ]] || { echo "[fail] could not read kube-dns clusterIP"; exit 1; }
echo "[stage] repointing nginx resolver at this cluster's kube-dns (${KUBEDNS})"
sed -i.bak -E "s/resolver [0-9.]+ /resolver ${KUBEDNS} /g" "${STAGE}/edge-proxy-configmap.yaml"
rm -f "${STAGE}"/*.bak
grep -q "resolver ${KUBEDNS} " "${STAGE}/edge-proxy-configmap.yaml" \
  || { echo "[fail] resolver rewrite did not take"; exit 1; }

# RULE 2: REPOINT EVERY IMAGE AT THE REGISTRY. The staged manifests carry local dev tags
# (traderx/<svc>:state009) which GKE cannot pull. Leaving one behind does NOT fail loudly: the
# apply succeeds, the new ReplicaSet's pod sits ImagePullBackOff, and the OLD pod keeps serving —
# so the change looks landed while nothing changed. See deploy-gke §2b.
echo "[stage] repointing images at ${REG} (tag ${TAG})"
sed -i.bak -E "s#image: traderx/([a-z0-9-]+):[A-Za-z0-9._-]+#image: ${REG}/\1:${TAG}#g" "${STAGE}"/*.yaml
rm -f "${STAGE}"/*.bak
if grep -rn 'image: traderx/' "${STAGE}"/*.yaml; then
  echo "[fail] a local dev image tag survived the rewrite — it would ImagePullBackOff silently"; exit 1
fi

# THE INTEGRATION. The UI services address the single-BLP tier's service names; this tier uses its
# own. Aliases rather than patched env, so the upstream manifests stay byte-identical to what the
# other tier deploys and there is no second copy to keep in sync.
#
# nats-broker carries 8081 AS WELL AS 4222, which the kind twin does not: the console's blotter
# subscribes over the edge proxy's /nats-ws route, which targets nats-broker:8081. Without the
# second port the page loads, the REST panels work, and only the live feed is silently dead.
cat > "${STAGE}/aeron-tier-aliases.yaml" <<'YAML'
apiVersion: v1
kind: Service
metadata: { name: database }
spec:
  selector: { app: eod-price-db }
  ports: [{ name: mysql, protocol: TCP, port: 3306, targetPort: 3306 }]
---
# order-matcher: the edge proxy's nginx config names this upstream, and nginx REFUSES TO START on
# an unknown upstream rather than 502-ing later — "host not found in upstream" in a CrashLoopBackOff
# is the whole diagnosis. The cluster tier publishes the gateway as order-matcher-gw (the
# LoadBalancer), so this alias is what makes the single-BLP-era proxy config work unchanged here.
apiVersion: v1
kind: Service
metadata: { name: order-matcher }
spec:
  selector: { app: cluster-gateway }
  ports:
    - { name: http, protocol: TCP, port: 18110, targetPort: 18110 }
    - { name: fix,  protocol: TCP, port: 18130, targetPort: 18130 }
---
apiVersion: v1
kind: Service
metadata: { name: nats-broker }
spec:
  selector: { app: nats }
  ports:
    - { name: client,    protocol: TCP, port: 4222, targetPort: 4222 }
    - { name: websocket, protocol: TCP, port: 8081, targetPort: 8081 }
YAML

echo "[apply] UI stack + tier aliases"
"${K[@]}" apply -f "${STAGE}"
for d in people-service account-service trade-service web-front-end-angular edge-proxy; do
  "${K[@]}" rollout status "deployment/${d}" --timeout=300s >/dev/null && echo "   ${d} ready"
done
echo "[ok] UI/app tier attached to the YU17 cluster tier on GKE."
