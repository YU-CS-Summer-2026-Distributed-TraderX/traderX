#!/usr/bin/env bash
# ADR-096 §3: set the gateway count on a running rig, one gateway per gateway-pool node.
#
#   scripts/gcp-compsci/rig/gateways.sh N [--dry-run]        N = 1..18
#
# Up: the pool grows to N nodes first, then the Deployment to N; each new gateway lands on its own node.
# Down: the newest gateways go (pod-deletion-cost). Each drains (FR-PRG-07): it keeps serving while the internal LB
#   deregisters its node, logs its FIX sessions out, waits for its in-flight orders, then releases their leases. The
#   clients resume on the remaining gateways (ADR-086), and this script reports each session's logout -> logon time.
#   Then exactly the emptied nodes are removed from the pool, never a node that still runs a gateway.
# Autoscaled (an HPA exists): N becomes the HPA's and the pool's minimum; the HPA keeps at least N.
#
# Refuses unless the restore gate passed for the members now running (ConfigMap rig-gate, written by rig.sh up).
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
RIG_LIB=1 . "$HERE/rig.sh"

N=${1:-}; [ "${2:-}" = --dry-run ] && DRY=1
GATEWAYS=$N; validate_args; preflight

gate_covers_members(){
  [ "$DRY" = 1 ] && { note "would require: ConfigMap rig-gate PASS for the running members' pod UIDs"; return 0; }
  members_gated || die "the restore gate does not cover the members now running; run rig.sh up"
}

one_per_node(){
  [ "$DRY" = 1 ] && return 0
  local pods nodes
  pods=$(kc get pods -l app=cluster-gateway --field-selector=status.phase=Running -o name | grep -c . || true)
  nodes=$(kc get pods -l app=cluster-gateway -o jsonpath='{range .items[*]}{.spec.nodeName}{"\n"}{end}' | sort -u | grep -c . || true)
  [ "$pods" = "$N" ] && [ "$nodes" = "$N" ] || die "$pods gateways on $nodes nodes, want $N on $N"
  note "ok: $N gateways on $N distinct nodes"
}

gone(){ ! kc get "$1" >/dev/null 2>&1; }

grow(){
  step "gateways $1 -> $N: gateway-pool to $N nodes, then the Deployment"
  do_ gcloud container clusters resize "$CLUSTER" --node-pool gateway-pool --num-nodes "$N" --zone "$ZONE" --project "$PROJECT" --quiet
  wait_for 900 "gateway-pool: $N nodes Ready" pool_ready gateway-pool "$N"
  do_ kubectl --context "$CTX" -n "$NS" scale deploy/cluster-gateway --replicas="$N"
  wait_for 600 "$N gateways Ready" ready_replicas deploy/cluster-gateway "$N"
  one_per_node
}

shrink(){
  local cur=$1 drop=$(( $1 - N )) victims v t0 mig node
  step "gateways $cur -> $N: the $drop newest drain, their sessions resume elsewhere, then their nodes go"
  if [ "$DRY" = 1 ]; then
    note "would mark the $drop newest gateway pods with controller.kubernetes.io/pod-deletion-cost=-1000"
    do_ kubectl --context "$CTX" -n "$NS" scale deploy/cluster-gateway --replicas="$N"
    note "would require: each drained gateway logs GATEWAY-DRAIN done inflight=0"
    note "would report: each FIX session's logout on a drained gateway -> its next logon on a remaining one"
    note "would cordon and delete exactly the gateway-pool nodes left without a gateway (MIG delete-instances)"
    return 0
  fi
  victims=$(kc get pods -l app=cluster-gateway --sort-by=.metadata.creationTimestamp -o name | tail -n "$drop")
  t0=$(date -u +%FT%TZ)
  for v in $victims; do
    kc annotate "$v" controller.kubernetes.io/pod-deletion-cost=-1000 --overwrite >/dev/null
    kc logs -f --timestamps "$v" > "$EVIDENCE/drained-${v#pod/}.log" 2>&1 &   # held until the pod is gone
  done
  do_ kubectl --context "$CTX" -n "$NS" scale deploy/cluster-gateway --replicas="$N"
  for v in $victims; do
    wait_for 180 "$v gone" gone "$v"
  done
  wait
  for v in $victims; do
    grep -q 'GATEWAY-DRAIN done inflight=0 ' "$EVIDENCE/drained-${v#pod/}.log" \
      || die "${v#pod/} did not finish its drain with nothing in flight: $EVIDENCE/drained-${v#pod/}.log"
  done
  note "ok: every drained gateway ended with inflight=0"
  for p in $(kc get pods -l app=cluster-gateway -o name); do
    kc logs --timestamps --since-time="$t0" "$p" > "$EVIDENCE/remaining-${p#pod/}.log" 2>&1
  done
  python3 - "$EVIDENCE" <<'EOF' | tee "$EVIDENCE/fix-resume.txt"
import glob, re, sys
from datetime import datetime
def ts(line): return datetime.fromisoformat(line.split(' ', 1)[0].replace('Z', '+00:00')[:26] + '+00:00')
out, back = [], []
for f in glob.glob(sys.argv[1] + '/drained-*.log'):
    out += [(ts(l), m.group(1)) for l in open(f) if (m := re.search(r'FIX logout: (\S+)', l))]
for f in glob.glob(sys.argv[1] + '/remaining-*.log'):
    back += [(ts(l), m.group(1)) for l in open(f) if (m := re.search(r'FIX logon: (\S+)', l))]
if not out: print('no FIX session was logged on at the drained gateways'); sys.exit(0)
worst = 0
for t, comp in sorted(out):
    nxt = min((b for b, c in back if c == comp and b >= t), default=None)
    if nxt is None: print(f'{comp}: logged out {t:%H:%M:%S.%f}Z, not back on a remaining gateway yet'); continue
    ms = (nxt - t).total_seconds() * 1000; worst = max(worst, ms)
    print(f'{comp}: logout -> logon on a remaining gateway {ms:.0f} ms')
print(f'sessions {len(out)}, worst resume {worst:.0f} ms')
EOF
  mig=$(basename "$(gc container node-pools describe gateway-pool --cluster "$CLUSTER" --zone "$ZONE" --format='value(instanceGroupUrls)' | tr ';' '\n' | head -1)")
  for node in $(kubectl --context "$CTX" get nodes -l cloud.google.com/gke-nodepool=gateway-pool -o name | sed 's#node/##'); do
    if [ -z "$(kc get pods -l app=cluster-gateway --field-selector spec.nodeName="$node" -o name)" ]; then
      do_ kubectl --context "$CTX" cordon "$node"
      do_ gcloud compute instance-groups managed delete-instances "$mig" --instances="$node" --zone "$ZONE" --project "$PROJECT" --quiet
    fi
  done
  wait_for 600 "gateway-pool at $N instances" pool_instances_are gateway-pool "$N"
  one_per_node
}

note "gateways: set $N on context=$CTX namespace=$NS evidence=$EVIDENCE"
gate_covers_members
if [ "$DRY" = 0 ] && hpa_exists; then
  step "autoscaled: N becomes the minimum of the HPA and of gateway-pool's autoscaler"
  do_ kubectl --context "$CTX" -n "$NS" patch hpa cluster-gateway --type merge -p "{\"spec\":{\"minReplicas\":$N}}"
  do_ gcloud container node-pools update gateway-pool --cluster "$CLUSTER" --zone "$ZONE" --project "$PROJECT" \
    --enable-autoscaling --min-nodes "$N" --max-nodes "$MAX_GATEWAYS"
  exit 0
fi
cur=$([ "$DRY" = 1 ] && echo "${CURRENT:-2}" || gateway_replicas)
if [ "$N" -gt "$cur" ]; then grow "$cur"; elif [ "$N" -lt "$cur" ]; then shrink "$cur"; else note "already $N"; one_per_node; fi
step "gateways: $N. Evidence in $EVIDENCE"
