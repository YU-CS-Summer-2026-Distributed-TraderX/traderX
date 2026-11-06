#!/usr/bin/env bash
# FR-PRG-28 on kind: NATS under a sustained core-publish flood into a file stream (the trade bridges' shape), in two
# arms, then rig.sh's own NATS check against the real pod.
#   control  production 2026-10-30: 2Gi, no GOMEMLIMIT, max_memory_store unset, the 1GB ingest queue -> OOMKilled
#   fixed    FR-PRG-28's ratios scaled to a laptop: 6Gi, GOMEMLIMIT 5GiB (83%, as 20/24), max_memory_store 256MB
# PASS: control restarts OOMKilled and nats_problems names it; fixed has 0 restarts and 0 cgroup oom_kill for SECS.
#   scripts/proofs/yu18-nats-memory-kind.sh control|fixed [SECS=600] [RATE=400000]   (needs kind, docker ~9 GB free)
set -euo pipefail
ARM=${1:?control|fixed}; SECS=${2:-600}; RATE=${3:-400000}; CL=nats-mem
ROOT=$(cd "$(dirname "$0")/../.." && pwd); OUT=${OUT:-$PWD/nats-mem-$ARM-$(date +%Y%m%d-%H%M%S)}; mkdir -p "$OUT"
K=(kubectl --context kind-$CL -n traderx)
case $ARM in control) LIM=2Gi GML='' MS='';; fixed) LIM=6Gi GML=5GiB MS=256MB;; *) echo "control|fixed" >&2; exit 64;; esac
kind get clusters | grep -qx $CL || kind create cluster --name $CL
kubectl --context kind-$CL create ns traderx --dry-run=client -o yaml | kubectl --context kind-$CL apply -f - >/dev/null
until "${K[@]}" get serviceaccount default >/dev/null 2>&1; do sleep 1; done   # a new namespace's pods need it
"${K[@]}" delete deploy nats --ignore-not-found --wait >/dev/null
"${K[@]}" apply -f - >/dev/null <<Y
apiVersion: v1
kind: ConfigMap
metadata: {name: nats-server-config}
data:
  nats.conf: |
    jetstream { store_dir: /data, ${MS:+max_memory_store: $MS,} max_file_store: 24G, max_buffered_msgs: 2000000, max_buffered_size: 1GB }
    http: 8222
---
apiVersion: v1
kind: Service
metadata: {name: nats}
spec: {selector: {app: nats}, ports: [{name: client, port: 4222}]}
---
apiVersion: apps/v1
kind: Deployment
metadata: {name: nats}
spec:
  selector: {matchLabels: {app: nats}}
  template:
    metadata: {labels: {app: nats}}
    spec:
      containers:
        - name: nats
          image: nats:2.14-alpine
          args: ["-c", "/etc/nats/nats.conf"]
          env: [{name: GOMEMLIMIT, value: "${GML:-off}"}]
          resources: {requests: {memory: $LIM}, limits: {memory: $LIM}}
          volumeMounts: [{name: data, mountPath: /data}, {name: config, mountPath: /etc/nats}]
      volumes: [{name: data, emptyDir: {}}, {name: config, configMap: {name: nats-server-config}}]
---
apiVersion: v1
kind: Pod
metadata: {name: load}
spec:
  containers: [{name: box, image: "natsio/nats-box:latest", command: ["sleep", "infinity"], resources: {limits: {memory: 2Gi}}}]
Y
"${K[@]}" rollout status deploy/nats --timeout=300s >/dev/null   # not `wait -l`: it latches onto a terminating pod
"${K[@]}" wait --for=condition=Ready pod/load --timeout=300s >/dev/null
nats(){ "${K[@]}" exec load -- nats -s nats://nats:4222 "$@"; }
nats stream add FEED --subjects feed --storage file --discard old --max-bytes 6GB --max-age 2h --defaults >/dev/null 2>&1 || true
sample(){ while :; do
  echo "$(date -u +%T) $("${K[@]}" get pods -l app=nats -o json | jq -r '.items[0].status.containerStatuses[0] | "restarts=\(.restartCount) last=\(.lastState.terminated.reason // "-")"') $("${K[@]}" exec deploy/nats -- sh -c 'echo cur=$(( $(cat /sys/fs/cgroup/memory.current)/1048576 ))MiB oom_kill=$(sed -n "s/^oom_kill //p" /sys/fs/cgroup/memory.events)' 2>/dev/null)"
  sleep 5; done; }
sample > "$OUT/samples.txt" & SP=$!
"${K[@]}" exec load -- nats -s nats://nats:4222 bench pub feed --clients 8 --throughput "$RATE" --duration "${SECS}s" --size 600B --no-progress > "$OUT/flood.txt" 2>&1 || true
kill $SP
( CTX=kind-$CL NS=traderx RIG_LIB=1; . "$ROOT/scripts/gcp-compsci/rig/rig.sh"; nats_problems '' ) > "$OUT/nats-problems.txt"
restarts=$("${K[@]}" get pods -l app=nats -o jsonpath='{.items[0].status.containerStatuses[0].restartCount}')
echo "$ARM: restarts=$restarts; rig.sh nats_problems: $(cat "$OUT/nats-problems.txt")"; echo "evidence: $OUT"
case $ARM in control) [ "$restarts" -gt 0 ] && grep -q OOMKilled "$OUT/nats-problems.txt";;
             fixed) [ "$restarts" = 0 ] && ! grep -Eq 'oom_kill=[1-9]' "$OUT/samples.txt" && [ ! -s "$OUT/nats-problems.txt" ];; esac \
  && echo PASS || { echo FAIL; exit 1; }
