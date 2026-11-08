#!/usr/bin/env bash
# FR-PRG-40 kind proof: the archive purge beside each member, and the restore gate's snapshot+tail G1/G2, on three real
# members in their own kind cluster.
#
#   IMAGE=traderx/cluster-node:<tag> scripts/gcp-compsci/rig/kind-purge.sh [EVIDENCE_DIR]
#
# The members are production's StatefulSet (YU17 gke statefulset-emptydir.yaml) with the agent added by prepare.py's own
# archive_purge(), made to fit one kind node (no pools, affinity or GCS restore; 1 CPU, 768m heap, a 2Gi standard
# claim, shared driver threads at 2 CPUs and a 2 s heartbeat). Kind only: 1 MiB archive segments and 1 MiB log terms (Aeron's own properties, so production's code paths and
# defaults are untouched) and a 60 s snapshot interval, so a few minutes of ClusterProofClient orders span many
# segments. Then, with rig.sh's own functions against this cluster:
#   1. load until every member has purged (archive start > 0, segment files deleted), /data sampled throughout;
#   2. the replay from 0 refuses (the old G2 reference cannot answer a purged log);
#   3. the load stopped 15 s after a snapshot (so orders follow it), the members restarted from their disks as rig.sh up
#      does, and gated before their next snapshot: g12_tail holds the purge and reports G1/G2 ok (snapshot+tail);
#   4. idle past two snapshot intervals, purged behind them: g12_tail INCONCLUSIVE, not ok.
# Refuses when another kind cluster exists (two starve Aeron: memory issue_kind_aeron_starvation). Deletes its cluster
# at the end unless KEEP=1.
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
NAME=rig-retention CTX=kind-rig-retention NS=rr
IMAGE=${IMAGE:?IMAGE=traderx/cluster-node:<tag>, built from this checkout}
EVIDENCE=${1:-$PWD/rig-evidence/kind-purge-$(date +%Y%m%d-%H%M%S)}
PY=${PY:-python3}
mkdir -p "$EVIDENCE"
log(){ echo "== $(TZ=America/New_York date '+%-I:%M:%S %p ET')  $*" | tee -a "$EVIDENCE/run.txt"; }

others=$(kind get clusters 2>/dev/null | grep -vx "$NAME" || true)
[ -z "$others" ] || { echo "REFUSED: another kind cluster runs ($others); two starve Aeron" >&2; exit 2; }
kind get clusters 2>/dev/null | grep -qx "$NAME" || kind create cluster --name "$NAME" --wait 120s >/dev/null
docker save "$IMAGE" -o "$EVIDENCE/image.tar"
kind load image-archive "$EVIDENCE/image.tar" --name "$NAME" >/dev/null
rm -f "$EVIDENCE/image.tar"
k(){ kubectl --context "$CTX" -n "$NS" "$@"; }
kubectl --context "$CTX" create namespace "$NS" --dry-run=client -o yaml | kubectl --context "$CTX" apply -f - >/dev/null
SECRET=$(openssl rand -hex 24)   # this cluster's only; never printed
k create secret generic auth-secrets --from-literal=jwt-secret="$SECRET" \
  --from-literal=cluster-client-secret="$(openssl rand -hex 24)" --dry-run=client -o yaml | k apply -f - >/dev/null

log "members rendered with prepare.archive_purge (image $IMAGE)"
"$PY" - "$ROOT" "$IMAGE" "$NS" > "$EVIDENCE/members.yaml" <<'PYEOF'
import sys, yaml
root, image, ns = sys.argv[1:]
sys.path.insert(0, root + '/scripts/gcp-compsci')
import prepare
docs = [d for d in yaml.safe_load_all(open(root + '/specs/YU17-otc-rates/generation/kubernetes/cluster/gke/statefulset-emptydir.yaml')) if d]
sts = next(d for d in docs if d['kind'] == 'StatefulSet')
pod = prepare.podspec(sts)
for key in ('nodeSelector', 'tolerations', 'affinity', 'initContainers'):
    pod.pop(key, None)
c = pod['containers'][0]
c['image'], c['imagePullPolicy'] = image, 'IfNotPresent'
drop = ('TRADE_BRIDGE', 'RISK_EXTRACT', 'OTEL', 'KDB_TAP')
c['env'] = [e for e in c['env'] if not e['name'].startswith(drop)]
for e in c['env']:
    if e['name'] == 'CLUSTER_HOSTNAMES':
        e['value'] = e['value'].replace('.traderx.svc.', f'.{ns}.svc.')
    if e['name'] == 'CLUSTER_SNAPSHOT_INTERVAL_MS':
        e['value'] = '60000'
    if e['name'] == 'JAVA_TOOL_OPTIONS':   # kind only: small segments and terms, so a few minutes of orders purge
        e['value'] = ('-Xms768m -Xmx768m -Daeron.archive.segment.file.length=1m -Daeron.cluster.log.channel=aeron:udp?term-length=1m '
                      '--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED')
# Kind only: at 1 CPU and production's 200 ms heartbeat the leader was throttled in 74% of CFS periods, elected twice
# and snapshotted once in 11 minutes (2026-11-08). Shared driver threads, 2 CPUs, a 2 s heartbeat timeout.
for e in c['env']:
    if e['name'] == 'CLUSTER_HEARTBEAT_TIMEOUT_MS':
        e['value'] = '2000'
c['env'].append({'name': 'CLUSTER_MEDIA_DRIVER_THREADING', 'value': 'SHARED'})
# /dev/shm stays production's 1Gi: each snapshot opens a ~192 MiB publication there, and at 256Mi every snapshot after
# the first failed with "insufficient usable storage" (2026-11-08).
c['resources'] = {'requests': {'cpu': '2', 'memory': '2Gi'}, 'limits': {'cpu': '2', 'memory': '2Gi'}}
for t in sts['spec']['volumeClaimTemplates']:
    t['spec']['storageClassName'] = 'standard'
    t['spec']['resources']['requests']['storage'] = '2Gi'
prepare.archive_purge(docs)   # the production render's own agent
for d in docs:
    d['metadata']['namespace'] = ns
print(yaml.safe_dump_all(docs, sort_keys=False))
PYEOF
k apply -f "$EVIDENCE/members.yaml" >/dev/null
k rollout status sts/order-matcher-cluster --timeout=600s >/dev/null
log "3 members Ready, each with containers: $(k get pod order-matcher-cluster-0 -o jsonpath='{.spec.containers[*].name}')"

health(){ k exec "order-matcher-cluster-$1" -c cluster-node -- wget -qO- localhost:8080/health; }
du_data(){ local i; for i in 0 1 2; do printf '%s ' "m$i=$(k exec "order-matcher-cluster-$i" -c cluster-node -- du -sk /data/archive | cut -f1)KiB"; done; }
purged_all(){ local i; for i in 0 1 2; do k logs "order-matcher-cluster-$i" -c archive-purge | grep -E '^PURGE s0=.* log[0-9]+=' >/dev/null || return 1; done; }

ENDPOINTS=$(for i in 0 1 2; do printf '%s=order-matcher-cluster-%s.order-matcher-cluster.%s.svc.cluster.local:%s,' "$i" "$i" "$NS" $((21802 + 100 * i)); done)
proof_client(){ cat <<EOF
apiVersion: v1
kind: Pod
metadata: {name: proof-client, namespace: $NS}
spec:
  restartPolicy: Never
  containers:
  - name: proof-client
    image: $IMAGE
    imagePullPolicy: IfNotPresent
    command: [java, -cp, '/opt/app/classes:/opt/app/lib/*', finos.traderx.ordermatcher.cluster.ClusterProofClient]
    env:
    - {name: CLUSTER_CLIENT_SECRET, valueFrom: {secretKeyRef: {name: auth-secrets, key: cluster-client-secret}}}
    - {name: POD_IP, valueFrom: {fieldRef: {fieldPath: status.podIP}}}
    - {name: PROOF_INGRESS_ENDPOINTS, value: "${ENDPOINTS%,}"}
    - {name: PROOF_INTERVAL_MS, value: "1"}
    - {name: PROOF_GAP_MS, value: "5000"}
    - {name: PROOF_EGRESS_PORT, value: "40460"}
    - {name: JAVA_TOOL_OPTIONS, value: "-Xmx256m --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"}
    resources: {requests: {cpu: 500m, memory: 512Mi}, limits: {cpu: "1", memory: 512Mi}}
    volumeMounts: [{name: shm, mountPath: /dev/shm}]
  volumes: [{name: shm, emptyDir: {medium: Memory, sizeLimit: 128Mi}}]
EOF
}
log "1. load (ClusterProofClient, one order per ms) until every member purged behind its second-latest snapshot"
proof_client | k apply -f - >/dev/null
end=$((SECONDS + 600))
until purged_all; do
  [ $SECONDS -lt $end ] || { log "FAILED: no purge on every member within 600 s"; exit 1; }
  echo "$(date -u +%T) applied=$(health 0 | jq -r .applied) $(du_data)" >> "$EVIDENCE/du.txt"; sleep 10
done
passes(){ k logs order-matcher-cluster-0 -c archive-purge | grep -c '^PURGE s0=' || true; }
n=$(passes)   # under load to the next pass (a snapshot just completed), then 15 s more of orders after it
until [ "$(passes)" -gt "$n" ]; do echo "$(date -u +%T) applied=$(health 0 | jq -r .applied) $(du_data)" >> "$EVIDENCE/du.txt"; sleep 5; done
sleep 15
echo "$(date -u +%T) applied=$(health 0 | jq -r .applied) $(du_data)" >> "$EVIDENCE/du.txt"
k delete pod proof-client --wait=true >/dev/null
for i in 0 1 2; do k logs "order-matcher-cluster-$i" -c archive-purge > "$EVIDENCE/purge-$i.log"; done
log "   passes per member: $(for i in 0 1 2; do printf 'm%s=%s ' $i "$(grep -c '^PURGE s0=' "$EVIDENCE/purge-$i.log")"; done)deletes: $(for i in 0 1 2; do printf 'm%s=%s ' $i "$(grep -c '^PURGE_DELETE' "$EVIDENCE/purge-$i.log")"; done)"
log "   /data/archive first and last: $(head -1 "$EVIDENCE/du.txt") | $(tail -1 "$EVIDENCE/du.txt")"

# rig.sh's own gate functions against this cluster; the admin token signed with this cluster's secret (never printed)
cat > "$EVIDENCE/.mint.py" <<'PYEOF'
import base64, hashlib, hmac, json, os, time
b = lambda x: base64.urlsafe_b64encode(x).rstrip(b'=')
h = b(json.dumps({'alg': 'HS256', 'typ': 'JWT'}).encode())
p = b(json.dumps({'sub': 'rig-gate', 'admin': True, 'exp': int(time.time()) + 600}).encode())
print((h + b'.' + p + b'.' + b(hmac.new(os.environ['TOKEN_SECRET'].encode(), h + b'.' + p, hashlib.sha256).digest())).decode())
PYEOF
(
  RIG_LIB=1; . "$HERE/rig.sh"; DRY=0
  admin_token(){ TOKEN_SECRET=$SECRET python3 "$EVIDENCE/.mint.py"; }
  restored(){ local a; a=$(member 0 | cut -d' ' -f1); [ "${a:-0}" -gt 0 ]; }
  step "2. the replay from 0 cannot answer a purged log (the old G2 reference)"
  r=$(kc exec -i order-matcher-cluster-0 -c cluster-node -- sh -c 'read -r t; wget -qO- --header="Authorization: Bearer $t" http://localhost:8080/recon/shadow-state 2>&1' <<<"$(admin_token)" || true)
  echo "$r" > "$EVIDENCE/shadow-state-0.txt"; note "member 0 /recon/shadow-state: ${r:0:200}"
  step "3. members restarted from their own disks (latest snapshot + tail), then G1/G2 by snapshot+tail"
  kc scale sts/order-matcher-cluster --replicas=0 >/dev/null; wait_for 300 "no member" no_pods order-matcher-cluster
  kc scale sts/order-matcher-cluster --replicas=3 >/dev/null; wait_for 600 "3 members Ready" ready_replicas sts/order-matcher-cluster 3
  wait_for 300 "member 0 restored" restored
  read -r a _ t _ <<<"$(member 0)"; note "restored: applied $a, trades $t"
  purge_rendered || die "archive-purge is not rendered"
  g12_tail "$a" "$t"; echo "TAIL=$TAIL" > "$EVIDENCE/gate-3.txt"
  for i in 0 1 2; do kc exec "order-matcher-cluster-$i" -c cluster-node -- test -f /data/archive-purge.hold || die "member $i not held"; done
  note "the hold file is on all three members while the gate reads"
  purge_hold release; note "released, as restore_gate does after PASS"
  step "4. idle past two snapshot intervals, purged behind them: G1/G2 by snapshot+tail again"
  sleep 150
  read -r a _ t _ <<<"$(member 0)"
  g12_tail "$a" "$t"; echo "TAIL=$TAIL" > "$EVIDENCE/gate-4.txt"; purge_hold release
  for i in 0 1 2; do kc logs "order-matcher-cluster-$i" -c archive-purge > "$EVIDENCE/purge-$i-end.log"; done
) 2>&1 | tee -a "$EVIDENCE/run.txt" || true   # the verdict is the gate files below; the teardown still runs
rm -f "$EVIDENCE/.mint.py"
log "result: step 3 $(cat "$EVIDENCE/gate-3.txt" 2>/dev/null || echo missing), step 4 $(cat "$EVIDENCE/gate-4.txt" 2>/dev/null || echo missing)"
[ "${KEEP:-0}" = 1 ] || kind delete cluster --name "$NAME" >/dev/null
[ "$(cat "$EVIDENCE/gate-3.txt")" = TAIL=PASS ] && [ "$(cat "$EVIDENCE/gate-4.txt")" = TAIL=INCONCLUSIVE ]
