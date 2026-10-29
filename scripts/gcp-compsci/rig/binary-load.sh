#!/usr/bin/env bash
# ADR-096 (FR-PRG-16): run binary-load.mjs as PODS in-cluster pods on loadgen-pool (one Indexed Job, never restarted:
# a restarted pod would hide its first attempt's unanswered orders).
#
#   scripts/gcp-compsci/rig/binary-load.sh PODS IMAGE [--apply] -- CLIENT ARGS...
#   e.g. binary-load.sh 20 node:20.18.0-alpine --apply -- --ticker MSFT --px 412.50 --sessions 16 --rate 1250 --secs 3600
#
# IMAGE is any image with node >= 20 on the PATH. Without --apply it prints the manifest. With --apply it refuses
# unless the restore gate covers the running members, then creates the Job. Results: one RESULT line per pod
# (`kubectl logs -l job-name=binary-load | grep ^RESULT`); the run is clean when every pod exited 0.
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
RIG_LIB=1 . "$HERE/rig.sh"

PODS=${1:-}; IMAGE=${2:-}; shift 2 || true
APPLY=0; [ "${1:-}" = --apply ] && { APPLY=1; shift; }
[ "${1:-}" = -- ] && shift
[[ "$PODS" =~ ^[1-9][0-9]*$ ]] && [ -n "$IMAGE" ] || die "usage: binary-load.sh PODS IMAGE [--apply] -- CLIENT ARGS"
args=""; for a in --resolve http://order-matcher:18110 "$@"; do args="$args, \"$a\""; done

manifest(){
  kubectl create configmap binary-load --from-file=binary-load.mjs="$HERE/binary-load.mjs" -n traderx --dry-run=client -o yaml
  cat <<EOF
---
apiVersion: batch/v1
kind: Job
metadata: {name: binary-load, namespace: traderx}
spec:
  completionMode: Indexed
  completions: $PODS
  parallelism: $PODS
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      nodeSelector: {cloud.google.com/gke-nodepool: loadgen-pool}
      containers:
      - name: load
        image: $IMAGE
        command: ["node", "/load/binary-load.mjs"${args}, "--out", "/tmp/results.json"]
        resources: {requests: {cpu: "1500m", memory: 1Gi}, limits: {memory: 2Gi}}
        volumeMounts: [{name: load, mountPath: /load}]
      volumes: [{name: load, configMap: {name: binary-load}}]
EOF
}

if [ "$APPLY" = 0 ]; then manifest; exit 0; fi
members_gated || die "the restore gate does not cover the members now running; run rig.sh up"
manifest | kc apply -f -
