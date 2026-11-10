#!/usr/bin/env bash
# Latency L0 (latency brief 2026-11-10): one rig, one session — the wire floor, then the order round trip per segment at
# constant arrival rates, with the leader's JVM pauses, so fixed cost and queueing can be told apart. Runs against the
# disposable p4k rig (namespace pa-proof-400k), never production.
#   CTX=... EVIDENCE=dir bash l0-baseline.sh floor                  # AeronPingPong RTT: member<->member, gateway<->leader
#   CTX=... EVIDENCE=dir bash l0-baseline.sh probe T PX SECS        # the constant-rate binary probe Job (500/s, 16 sessions)
#   CTX=... EVIDENCE=dir bash l0-baseline.sh block LABEL PER_SESSION T PX [WINDOWS=3]
#        background binary-load at 8 pods x 64 sessions x PER_SESSION/s (0 = none), then WINDOWS aligned 60 s windows:
#        every gateway's and the leader's /latency reset at the window start and read at its end, the leader's
#        per-thread CPU over it. Windows skip the last minute of binary-load's 300 s phase (its 4 s pause).
#   CTX=... EVIDENCE=dir bash l0-baseline.sh alloc                  # L1 input: engine node allocatable vs 15 integer CPUs
# Needs: AeronPingPong compiled to $PP_CLASSES (javac --release 21 against aeron-client/driver 1.51.0, agrona, HdrHistogram).
set -euo pipefail
: "${CTX:?}" "${EVIDENCE:?}"; NS=${NS:-pa-proof-400k}; mkdir -p "$EVIDENCE"
HERE=$(cd "$(dirname "$0")" && pwd); RIG=$HERE/../../gcp-compsci/rig
kc(){ kubectl --context "$CTX" -n "$NS" "$@"; }
et(){ TZ=America/New_York date '+%-I:%M:%S %p ET'; }
log(){ echo "[$(et)] $*" | tee -a "$EVIDENCE/l0.log"; }
member(){ kc exec "order-matcher-cluster-$1" -c cluster-node -- wget -qO- "localhost:8080$2"; }
leader(){ local m; for m in 0 1 2; do [[ $(member $m /health | jq -r .role) == LEADER ]] && { echo $m; return; }; done; return 1; }
node_of(){ kc get pod "$1" -o jsonpath='{.spec.nodeName}'; }
gw_ips(){ kc get pods -l app=cluster-gateway --field-selector=status.phase=Running --no-headers -o custom-columns=:status.podIP | tr '\n' ' '; }
# every gateway's /latency, in one exec (the gateway does not answer kubectl exec inside its own pod under load)
gw_all(){ kc exec deploy/trade-processor -- sh -c "for ip in $(gw_ips); do echo \"== \$ip\"; curl -s -m 5 \"http://\$ip:18110/latency$1\"; echo; done"; }
threads(){ kc exec "order-matcher-cluster-$1" -c cluster-node -- sh -c '
  for p in /proc/[0-9]*; do grep -q "^java" "$p/comm" 2>/dev/null && { J=${p#/proc/}; break; }; done
  for t in /proc/$J/task/*; do c=$(cat "$t/comm"); set -- $(sed "s/.*) //" "$t/stat"); echo "$c $(( ${12} + ${13} ))"; done' 2>/dev/null; }
op(){ kc get secret auth-secrets -o jsonpath='{.data.risk-control-token}' | base64 -d \
        | awk '{print "X-Risk-Control-Token: " $0; print "X-Risk-Operator: l0"; print "Content-Type: application/json"}' \
        | kc exec -i deploy/trade-processor -- curl -s -m 30 -o /dev/null -w '%{http_code}' -H @- -X POST "http://order-matcher:18110$1" -d "$2"; }

# The default policy's maxPositionQuantity is reached inside one 300 s phase at these rates (bridge-batching-gke
# 2026-11-09 item 7); raised for the rig's test accounts, as the campaign did.
policy(){ op /risk/control/policy "{\"policyVersion\":$(date +%s),\"killSwitch\":false,\"maxPositionQuantity\":2000000000,\"maxConcentrationNotionalTicks\":9000000000000000000}"; }
# A window measures the background rate only while every load pod runs and nothing it sent was rejected: a rejecting
# session unbalances, and binary-load stops itself at the next phase boundary (2026-11-10: a whole block measured idle).
load_ok(){ local p bad=0; [ "$(kc get pods -l job-name=binary-load --field-selector=status.phase=Running --no-headers 2>/dev/null | wc -l | tr -d ' ')" = 8 ] || return 1
  for p in $(kc get pods -l job-name=binary-load --no-headers -o custom-columns=:metadata.name); do
    kc logs "$p" --tail=1 | grep -q ' rejected=0 ' || bad=1; done; [ $bad = 0 ]; }

pp_pair(){   # pp_pair LABEL PING_POD PING_CONTAINER PONG_POD PONG_CONTAINER IDLE
  local label=$1 a=$2 ac=$3 b=$4 bc=$5 idle=$6 aip bip run
  [[ $(node_of "$a") != "$(node_of "$b")" ]] || { log "REFUSED $label: $a and $b share node $(node_of "$a") (loopback)"; return 1; }
  aip=$(kc get pod "$a" -o jsonpath='{.status.podIP}'); bip=$(kc get pod "$b" -o jsonpath='{.status.podIP}')
  for p in "$a:$ac" "$b:$bc"; do kc cp "$PP_CLASSES" "${p%%:*}:/tmp/pp" -c "${p##*:}" >/dev/null; done
  run='env -u JAVA_TOOL_OPTIONS java -Xmx256m --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -cp /tmp/pp:/opt/app/lib/* finos.traderx.ordermatcher.cluster.AeronPingPong'
  kc exec "$b" -c "$bc" -- sh -c "MODE=pong IDLE=$idle POD_IP=$bip TARGET=$aip PORT=40570 SECS=40 PP_AERON_DIR=/dev/shm/aeron-pp-pong $run" > "$EVIDENCE/pong-$label-$idle.log" 2>&1 &
  sleep 3
  kc exec "$a" -c "$ac" -- sh -c "MODE=ping IDLE=$idle POD_IP=$aip TARGET=$bip PORT=40570 RATE=1000 WARMUP=5 SECS=30 PP_AERON_DIR=/dev/shm/aeron-pp-ping $run" 2>&1 \
    | tee "$EVIDENCE/ping-$label-$idle.log" | grep AERON-RTT | sed "s/^/$label ($(node_of "$a") -> $(node_of "$b")) /" | tee -a "$EVIDENCE/floor.txt"
  wait
}

case ${1:?} in
floor)
  : "${PP_CLASSES:?}"
  L=$(leader); F=$(( (L + 1) % 3 )); G=$(kc get pods -l app=cluster-gateway --no-headers -o custom-columns=:metadata.name | head -1)
  log "floor: leader m$L, follower m$F, gateway $G; nodes $(for m in 0 1 2; do printf 'm%s=%s ' $m "$(node_of order-matcher-cluster-$m)"; done)"
  for idle in busyspin lowpark; do
    pp_pair leader-follower "order-matcher-cluster-$L" cluster-node "order-matcher-cluster-$F" cluster-node $idle
    pp_pair gateway-leader "$G" gateway "order-matcher-cluster-$L" cluster-node $idle
  done ;;
probe)
  T=${2:?}; PX=${3:?}; SECS=${4:?}
  kc delete job l0-probe --ignore-not-found --wait=true >/dev/null
  kc create configmap l0-probe --from-file=binary-latency-probe.mjs="$HERE/binary-latency-probe.mjs" --dry-run=client -o yaml | kc apply -f - >/dev/null
  kc apply -f - >/dev/null <<YAML
apiVersion: batch/v1
kind: Job
metadata: {name: l0-probe}
spec:
  backoffLimit: 0
  template:
    metadata: {annotations: {cluster-autoscaler.kubernetes.io/safe-to-evict: "false"}}
    spec:
      restartPolicy: Never
      nodeSelector: {cloud.google.com/gke-nodepool: p4k-loadgen}
      tolerations: [{key: traderx-loadgen, operator: Equal, value: "true", effect: NoSchedule}]
      containers:
      - name: probe
        image: node:20-alpine
        command: [node, /probe/binary-latency-probe.mjs, --resolve, "http://order-matcher:18110", --ticker, "$T", --px, "$PX",
                  --rate, "500", --sessions, "16", --secs, "$SECS", --warmup, "20", --window, "60", --accounts, "${PROBE_ACCOUNTS:-9900018,9900019}"]
        resources: {requests: {cpu: "1", memory: 512Mi}, limits: {memory: 1Gi}}
        volumeMounts: [{name: probe, mountPath: /probe}]
      volumes: [{name: probe, configMap: {name: l0-probe}}]
YAML
  log "probe: 500/s x 16 sessions on $T@$PX for ${SECS}s" ;;
block)
  LABEL=${2:?}; PER=${3:?}; T=${4:?}; PX=${5:?}; WINDOWS=${6:-3}; D="$EVIDENCE/$LABEL"; mkdir -p "$D"
  L=$(leader); log "block $LABEL: leader m$L, background $((8 * 64 * PER))/s on $T"
  if [ "$PER" -gt 0 ]; then
    [ "$(policy)" = 200 ] || { log "REFUSED $LABEL: risk policy not raised"; exit 1; }
    kc delete job binary-load --ignore-not-found --wait=true >/dev/null
    kc create configmap binary-load --from-file=binary-load.mjs="$RIG/binary-load.mjs" --dry-run=client -o yaml | kc apply -f - >/dev/null
    kc apply -f - >/dev/null <<YAML
apiVersion: batch/v1
kind: Job
metadata: {name: binary-load}
spec:
  completionMode: Indexed
  completions: 8
  parallelism: 8
  backoffLimit: 0
  template:
    metadata: {annotations: {cluster-autoscaler.kubernetes.io/safe-to-evict: "false"}}
    spec:
      restartPolicy: Never
      nodeSelector: {cloud.google.com/gke-nodepool: p4k-loadgen}
      tolerations: [{key: traderx-loadgen, operator: Equal, value: "true", effect: NoSchedule}]
      containers:
      - name: load
        image: node:20-alpine
        command: [node, /load/binary-load.mjs, --resolve, "http://order-matcher:18110", --ticker, "$T", --px, "$PX",
                  --sessions, "64", --rate, "$PER", --secs, "3600", --ramp, "0.5", --step-secs, "60",
                  --phase-secs, "300", --pause-ms, "4000", --max-in-flight, "800", --out, /tmp/results.json]
        resources: {requests: {cpu: "1500m", memory: 1Gi}, limits: {memory: 2Gi}}
        volumeMounts: [{name: load, mountPath: /load}]
      volumes: [{name: load, configMap: {name: binary-load}}]
YAML
    log "load started; settling 150 s (60 s at half rate, then full)"; sleep 150
  fi
  w=0
  while [ $w -lt "$WINDOWS" ]; do
    now=$(date +%s); start=$(( (now / 60 + 1) * 60 ))
    if [ $(( start % 300 )) -eq 240 ]; then start=$((start + 60)); fi   # the minute that holds binary-load's pause
    sleep $(( start - $(date +%s) ))
    if [ "$PER" -gt 0 ] && ! load_ok; then log "REFUSED $LABEL window $w: the load is not all running with 0 rejected"; exit 1; fi
    gw_all '?reset=1' >/dev/null; member "$L" '/latency?reset=1' >/dev/null; threads "$L" > "$D/threads-$w-start.txt"
    t0=$(date +%s); sleep $(( start + 60 - $(date +%s) ))
    if [ "$PER" -gt 0 ] && ! load_ok; then log "REFUSED $LABEL window $w: the load stopped or rejected during it"; exit 1; fi
    gw_all '' > "$D/gw-$w.txt"; member "$L" /latency > "$D/leader-$w.txt"; threads "$L" > "$D/threads-$w-end.txt"; t1=$(date +%s)
    echo "$t0 $t1 $(date -u -r "$start" +%FT%TZ)" > "$D/window-$w.at"
    log "block $LABEL window $w $(date -u -r "$start" +%TZ) done ($((t1 - t0)) s)"
    w=$((w + 1))
  done
  kc logs -l job-name=binary-load --tail=3 --prefix 2>/dev/null | grep -E 'sent=' > "$D/load-progress.txt" || true
  if [ "$PER" -gt 0 ]; then kc delete job binary-load --wait=false >/dev/null; log "load stopped (job deleted)"; fi ;;
alloc)
  for n in $(kubectl --context "$CTX" get nodes -l cloud.google.com/gke-nodepool=p4k-engine -o name); do
    kubectl --context "$CTX" get "$n" -o jsonpath='{.metadata.name} capacity={.status.capacity.cpu} allocatable={.status.allocatable.cpu}{"\n"}'
    kubectl --context "$CTX" get pods -A --field-selector "spec.nodeName=${n#node/}" -o json \
      | jq -r '.items[] | [.metadata.namespace + "/" + .metadata.name, .status.qosClass, ([.spec.containers[].resources.requests.cpu // "0"] | join("+"))] | @tsv'
  done | tee "$EVIDENCE/alloc.txt" ;;
*) echo "unknown: $1" >&2; exit 2 ;;
esac
