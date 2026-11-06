#!/usr/bin/env bash
# FR-PRG-32: the demo's binary load on the idle pods `LOADGEN=N rig.sh up` left waiting, so the load starts in seconds.
#
#   scripts/gcp-compsci/rig/loadgen.sh start RATE [SECS] [TICKER] [--dry-run]
#       RATE orders/s in total, spread over every idle pod x 16 sessions (default 1800 s, MSFT at the members' mark).
#       With AUTOSCALE=on, ~120000/s on 3 gateways holds their CPU above the HPA's 60% and the HPA adds gateways.
#   scripts/gcp-compsci/rig/loadgen.sh stop       kills the load in every pod; the pods stay, idle. A kill between phase
#       boundaries can leave that phase's unbalanced test-account orders resting: prefer letting SECS run out.
#   scripts/gcp-compsci/rig/loadgen.sh results    each pod's RESULT line from its last run; FAILED (exit 3) if NATS
#       restarted since `up` (FR-PRG-28), so a load whose trades NATS dropped never reads as clean
#   scripts/gcp-compsci/rig/loadgen.sh manifest   the idle Deployment that rig.sh up applies (LOADGEN_PODS replicas)
#
# The load itself is binary-load.mjs (FR-PRG-16): its own test accounts 9900010-17, book-neutral, one price.
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
RIG_LIB=1 . "$HERE/rig.sh"
ACTION=${1:-}; shift || true
ARGS=(); for a in "$@"; do [ "$a" = --dry-run ] && DRY=1 || ARGS+=("$a"); done
SESSIONS=16
# FR-PRG-36: the pause between 10 s phases, in which every phase's orders must be answered or binary-load.mjs stops
# ("not balanced at the phase boundary"). 1000 ms stopped a ~185K/s run after its first phase on 2026-11-02 (~500 orders per session unanswered).
LOADGEN_PAUSE_MS=${LOADGEN_PAUSE_MS:-3000}   # binary-load.mjs's own default

manifest(){
  local image=${LOADGEN_IMAGE:-}
  [ -n "$image" ] || image=$(kc get deploy web-front-end-console -o jsonpath='{.spec.template.spec.containers[0].image}')
  [ -n "$image" ] || die "no console image to run the load with (set LOADGEN_IMAGE)"
  kubectl create configmap binary-load --from-file=binary-load.mjs="$HERE/binary-load.mjs" -n "$NS" --dry-run=client -o yaml
  cat <<YAML
---
apiVersion: apps/v1
kind: Deployment
metadata: {name: loadgen, namespace: $NS, labels: {app: loadgen}}
spec:
  replicas: $LOADGEN_PODS
  selector: {matchLabels: {app: loadgen}}
  template:
    metadata: {labels: {app: loadgen}}
    spec:
      nodeSelector: {cloud.google.com/gke-nodepool: loadgen-pool}
      tolerations: [{key: traderx-loadgen, operator: Equal, value: "true", effect: NoSchedule}]
      terminationGracePeriodSeconds: 5
      containers:
      - name: load
        image: $image
        command: ["sleep", "infinity"]
        resources: {requests: {cpu: "400m", memory: 1Gi}, limits: {memory: 2Gi}}
        volumeMounts: [{name: load, mountPath: /load}]
      volumes: [{name: load, configMap: {name: binary-load}}]
YAML
}

pods(){ kc get pods -l app=loadgen --field-selector=status.phase=Running -o name | sed 's#^pod/##'; }
# The members' mark (else reference) for TICKER, to 2 dp: inside the collar by construction.
mark_of(){ kc exec order-matcher-cluster-0 -c cluster-node -- wget -qO- localhost:8080/bbo \
  | jq -r --arg t "$1" '.books[] | select(.ticker == $t) | (if .mark > 0 then .mark else .ref end) | select(. > 0)' | head -1; }
# PIDs whose command line STARTS with the load: a plain grep for "binary-load.mjs" also matched this probe's own `sh -c`.
PIDS='for p in /proc/[0-9]*; do case "$(tr "\\0" " " < "$p/cmdline" 2>/dev/null)" in "node /load/binary-load.mjs"*) echo "${p#/proc/}";; esac; done'
running(){ kc exec "$1" -- sh -c "$PIDS" 2>/dev/null || true; }

sending(){ kc exec "$1" -- sh -c 'grep -q "sent=[1-9]" /tmp/load.log' 2>/dev/null; }
all_sending(){ local p; for p in "$@"; do sending "$p" || return 1; done; }

start(){
  local rate=${ARGS[0]:-} secs=${ARGS[1]:-1800} ticker=${ARGS[2]:-MSFT} list n per px p
  [[ "$rate" =~ ^[1-9][0-9]*$ ]] || die "usage: loadgen.sh start RATE [SECS] [TICKER]  (RATE orders/s in total)"
  [[ "$secs" =~ ^[1-9][0-9]*$ ]] || die "SECS must be a whole number of seconds"
  [[ "$LOADGEN_PAUSE_MS" =~ ^[0-9]+$ ]] && [ "$LOADGEN_PAUSE_MS" -ge 100 ] && [ "$LOADGEN_PAUSE_MS" -le 60000 ] \
    || die "LOADGEN_PAUSE_MS must be 100..60000, got '$LOADGEN_PAUSE_MS'"
  if [ "$DRY" = 1 ]; then n=${LOADGEN_PODS:-8}; px='<mark>'
  else
    members_gated || die "the restore gate does not cover the members now running; run rig.sh up"
    list=$(pods); n=$(grep -c . <<<"$list" || true)
    [ "$n" -ge 1 ] || die "no idle load pod: bring the rig up with LOADGEN=N (rig.sh up)"
    px=$(mark_of "$ticker"); [[ "$px" =~ ^[0-9]+(\.[0-9]+)?$ ]] || die "$ticker has no mark on member 0"
    px=$(printf '%.2f' "$px")
    for p in $list; do [ -z "$(running "$p")" ] || die "$p is already running a load (loadgen.sh stop first)"; done
    local np; np=$(nats_problems "$(nats_uid)"); [ -z "$np" ] || die "NATS (FR-PRG-28): $np"
  fi
  per=$(( (rate + n * SESSIONS - 1) / (n * SESSIONS) ))
  step "load: $rate orders/s ($n pods x $SESSIONS sessions x $per/s), $ticker @ $px, ${secs}s"
  local cmd="node /load/binary-load.mjs --resolve http://order-matcher:18110 --ticker $ticker --px $px --sessions $SESSIONS --rate $per --secs $secs --phase-secs 10 --pause-ms $LOADGEN_PAUSE_MS --out /tmp/results.json"
  if [ "$DRY" = 1 ]; then note "would exec in each idle pod: nohup $cmd"; return 0; fi
  for p in $list; do kc exec "$p" -- sh -c "nohup $cmd > /tmp/load.log 2>&1 &" & done
  wait
  for p in $list; do [ -n "$(running "$p")" ] || die "$p did not start: $(kc exec "$p" -- tail -3 /tmp/load.log 2>&1)"; done
  # A process is not a load: binary-load.mjs reports sent=0 forever when it reaches no binary ingress (kind has no
  # :18140), so each pod must report orders sent (its first phase starts on a 10 s wall-clock boundary).
  local end=$((SECONDS + ${LOADGEN_SEND_WAIT:-25})) first=${list%%$'\n'*}
  until all_sending $list; do
    [ $SECONDS -lt $end ] || { stop >/dev/null
      die "the load sent no orders within 25 s (no binary ingress at order-matcher-gw-binary:18140?); stopped it. $first: $(kc exec "$first" -- tail -1 /tmp/load.log 2>&1)"; }
    sleep 2
  done
  note "every pod is sending"
  note "started on $n pods at $(et); watch the System tab (gateways) or: kubectl -n $NS get hpa cluster-gateway -w"
}

stop(){
  local p
  [ "$DRY" = 1 ] && { note "would kill binary-load.mjs in every loadgen pod"; return 0; }
  for p in $(pods); do
    kc exec "$p" -- sh -c "for pid in \$($PIDS); do kill \$pid; done; true"
  done
  note "load stopped at $(et); the pods stay idle"
}

results(){
  local p np
  for p in $(pods); do echo "$p $(kc exec "$p" -- sh -c 'grep ^RESULT /tmp/load.log || tail -2 /tmp/load.log' 2>&1)"; done
  # FR-PRG-28: the pods' counts are the gateways' answers; a NATS restart meanwhile dropped the bridges' trade sides
  # and the FIX session store's Logons without touching them (production 2026-10-30).
  np=$(nats_problems "$(nats_uid)")
  [ -z "$np" ] || { echo "FAILED: NATS did not stay up through the load (FR-PRG-28): $np" >&2; exit 3; }
  note "NATS intact (FR-PRG-28)"
}

[ "${LOADGEN_LIB:-0}" = 1 ] && return 0
case $ACTION in
  manifest) manifest;;
  start) start;;
  stop) stop;;
  results) results;;
  *) echo "usage: $0 start RATE [SECS] [TICKER] | stop | results | manifest [--dry-run]" >&2; exit 64;;
esac
