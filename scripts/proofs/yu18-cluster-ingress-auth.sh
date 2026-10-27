#!/usr/bin/env bash
# yu18-cluster-ingress-auth.sh — ADR-079 §4: the members' Aeron ingress admits a session only on the
# shared CLUSTER_CLIENT_SECRET. This is the control the spec says actually closes the ingress (the
# NetworkPolicy is inert without Dataplane V2), so it is proven through the real protocol:
#
#   positive  the checked-in ClusterProofClient, presenting auth-secrets/cluster-client-secret,
#             CONNECTs to the members (so the members accept a client at all);
#   negative  the SAME image, endpoints and placement with a WRONG secret is seen TRYING
#             (CONNECT-*RETRY) and never CONNECTs, the members' traderx_cluster_ingress_rejected
#             rises, and a member logs CLUSTER-INGRESS-REJECTED — so "refused" is the authenticator
#             speaking, not a pod that never ran.
#
# The only variable between the two pods is the secret's value. Image and placement are read from the
# running StatefulSet, so the client is the build under test.
#
# Usage: CTX=<ctx> NS=<ns> [DEADLINE_S=90] bash scripts/proofs/yu18-cluster-ingress-auth.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
DEADLINE_S="${DEADLINE_S:-90}"
PROOF_CLIENT="${PROOF_CLIENT:-${here}/../../specs/YU17-otc-rates/generation/kubernetes/cluster/gke/proof-client.yaml}"
cleanup() { "${K[@]}" delete pod ingress-auth-good ingress-auth-bad --ignore-not-found --wait=false >/dev/null 2>&1; _lib_yu18_cleanup; }
trap cleanup EXIT

rejected_total() { local m s=0 v; for ((m=0; m<MEMBERS; m++)); do
  v="$(mget "${m}" /metrics | awk '/^traderx_cluster_ingress_rejected[ {]/ {s+=$NF} END {print s+0}')"; s=$((s + ${v%.*})); done; echo "${s}"; }

step "0. preflight"
[[ -f "${PROOF_CLIENT}" ]] || fail "proof-client manifest not found: ${PROOF_CLIENT}"
"${K[@]}" get secret auth-secrets -o go-template='{{index .data "cluster-client-secret"}}' | grep -q . \
  || fail "auth-secrets has no cluster-client-secret"
mget 0 /metrics | grep -q '^traderx_cluster_ingress_rejected' \
  || fail "member 0 exports no traderx_cluster_ingress_rejected (ADR-079 §4 names it)"
STS="$("${K[@]}" get statefulset order-matcher-cluster -o json)"
IMAGE="$(jget "[c['image'] for c in d['spec']['template']['spec']['containers'] if c['name']=='${MEMBER_CONTAINER:-cluster-node}'][0]" <<<"${STS}")"
PLACE="$(jget "{'tolerations': d['spec']['template']['spec'].get('tolerations',[]), 'nodeSelector': d['spec']['template']['spec'].get('nodeSelector',{})}" <<<"${STS}")"
R0="$(rejected_total)"
ok "image ${IMAGE##*/}; rejected counter starts at ${R0}"

render() { # render <name> <secret-mode good|bad>
  python3 - "${PROOF_CLIENT}" "$1" "$2" "${NS}" "${IMAGE}" "${PLACE}" <<'EOF'
import sys,json,re
src,name,mode,ns,image,place=sys.argv[1:7]
t=open(src).read()
t=re.sub(r'(?m)^  name: cluster-proof-client$', f'  name: {name}', t)
t=re.sub(r'(?m)^      image: .*$', f'      image: {image}', t)
t=t.replace('.traderx.svc.cluster.local', f'.{ns}.svc.cluster.local')
p=json.loads(place.replace("'",'"'))
t=re.sub(r'(?m)^  tolerations:\n(    - .*\n)+', '  tolerations: '+json.dumps(p['tolerations'])+'\n  nodeSelector: '+json.dumps(p['nodeSelector'])+'\n', t)
# One probe order at most, not the canary's 100/s: the client sequences a REAL resting order per
# interval (measured: 3,285 resting AAL buys for account 11 in 60 s at the manifest's 10 ms).
t=re.sub(r'(?m)^(        - name: PROOF_INTERVAL_MS\n          value: )"\d+"$', r'\1"600000"', t)
assert '"600000"' in t, 'PROOF_INTERVAL_MS line changed shape in proof-client.yaml'
if mode=='bad':
    t=re.sub(r'(?ms)(        - name: CLUSTER_CLIENT_SECRET\n)          valueFrom:\n            secretKeyRef:\n              name: auth-secrets\n              key: cluster-client-secret\n',
             r'\1          value: "not-the-cluster-client-secret-0123456789abcdef"\n', t)
    assert 'not-the-cluster-client-secret' in t, 'could not replace the secretKeyRef; proof-client.yaml changed shape'
print(t)
EOF
}
# Read the log whole, then grep: `kubectl logs | grep -q` under pipefail fails on SIGPIPE once the
# log outgrows a pipe buffer, which reads as "never tried" on exactly the run that tried hardest.
connected() { local l; l="$("${K[@]}" logs "$1" 2>/dev/null)"; grep -q '^CONNECTED' <<<"${l}"; }
tried()     { local l; l="$("${K[@]}" logs "$1" 2>/dev/null)"; grep -q '^CONNECT-.*RETRY' <<<"${l}"; }
open_orders() { sql "SELECT orderid FROM orderbook WHERE status IN ('NEW','PARTIALLY_FILLED') ORDER BY orderid"; }

step "1. positive control: the real secret connects within ${DEADLINE_S}s"
BEFORE="$(open_orders)"
render ingress-auth-good good | "${K[@]}" apply -f - >/dev/null || fail "could not create ingress-auth-good"
deadline=$((SECONDS + DEADLINE_S))
until connected ingress-auth-good; do
  (( SECONDS < deadline )) || { "${K[@]}" logs ingress-auth-good --tail=20 >&2; fail "positive control never connected"; }
  sleep 3
done
ok "$("${K[@]}" logs ingress-auth-good | grep -m1 '^CONNECTED')"
"${K[@]}" delete pod ingress-auth-good --wait=true >/dev/null
# The canary rests a real order once connected; cancel whatever it left (the read model lags, so wait).
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"; ADMIN="$(mint true '[]')"; sleep 5
left="$(comm -13 <(echo "${BEFORE}") <(open_orders))"
for oid in ${left}; do curl -s -m 10 -o /dev/null -X POST "${GW}/cancel" -H "Authorization: Bearer ${ADMIN}" \
  -H 'Content-Type: application/json' -d "{\"orderRef\":${oid##*-}}"; done
echo "    cancelled $(wc -w <<<"${left}" | tr -d ' ') order(s) the canary rested"

step "2. negative control: a wrong secret is tried, refused, and counted"
render ingress-auth-bad bad | "${K[@]}" apply -f - >/dev/null || fail "could not create ingress-auth-bad"
deadline=$((SECONDS + DEADLINE_S))
while (( SECONDS < deadline )); do
  connected ingress-auth-bad && { "${K[@]}" logs ingress-auth-bad --tail=20 >&2; fail "WRONG secret CONNECTED: the ingress is not authenticated"; }
  sleep 3
done
tried ingress-auth-bad || { "${K[@]}" logs ingress-auth-bad --tail=20 >&2; fail "the negative control never tried to connect, so 'refused' is unproven"; }
R1="$(rejected_total)"
(( R1 > R0 )) || fail "traderx_cluster_ingress_rejected did not rise (${R0} -> ${R1}) while the wrong secret was retrying"
logged=0; for ((m=0; m<MEMBERS; m++)); do
  l="$("${K[@]}" logs "order-matcher-cluster-${m}" -c "${MEMBER_CONTAINER:-cluster-node}" --since=5m 2>/dev/null)"
  grep -q 'CLUSTER-INGRESS-REJECTED' <<<"${l}" && logged=$((logged+1)); done
(( logged > 0 )) || fail "no member logged CLUSTER-INGRESS-REJECTED"
ok "never connected in ${DEADLINE_S}s; rejected ${R0} -> ${R1}; ${logged} member(s) logged CLUSTER-INGRESS-REJECTED"

echo; echo "YU18-CLUSTER-INGRESS-AUTH PASS positive=connected negative=refused rejected+=$((R1 - R0))"
