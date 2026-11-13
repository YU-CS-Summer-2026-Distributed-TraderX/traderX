#!/usr/bin/env bash
# Latency L0 (latency brief 2026-11-10): one rig, one session — the wire floor, then the order round trip per segment at
# constant arrival rates, with the leader's JVM pauses, so fixed cost and queueing can be told apart. Runs against the
# disposable p4k rig (namespace pa-proof-400k), never production.
#   CTX=... EVIDENCE=dir bash l0-baseline.sh floor                  # AeronPingPong RTT: member<->member, gateway<->leader
#   CTX=... EVIDENCE=dir bash l0-baseline.sh probe T PX SECS        # the constant-rate binary probe Job (500/s, 16 sessions)
#   CTX=... EVIDENCE=dir CREDS=fix-test-clients.json bash l0-baseline.sh fix-probe T PX SECS   # the FIX probe Job (FR-OPL-08):
#        500/s over 6 CompIDs of CREDS paired into 3 lanes; PROBE_RATE, PROBE_SESSIONS, PROBE_ACCOUNTS=A,B, FIX_HOST, FIX_PORT
#   CTX=... EVIDENCE=dir bash l0-baseline.sh block LABEL PER_SESSION T PX [WINDOWS=3]
#        background binary-load at 8 pods x 64 sessions x PER_SESSION/s (0 = none), then WINDOWS aligned 60 s windows:
#        every gateway's and the leader's /latency reset at the window start and read at its end, the leader's
#        per-thread CPU over it. Windows skip the last minute of binary-load's 300 s phase (its 4 s pause).
#   CTX=... EVIDENCE=dir bash l0-baseline.sh alloc                  # L1 input: engine node allocatable vs 15 integer CPUs
# Latency L1/L2 (FR-OPL-05/06), driven by l1-ab.sh:
#   ... seed TICKERS PX         # a fresh epoch: the load's and probe's accounts (SQL row + admitted), the tickers seeded
#   ... gw-idle STRATEGY        # GATEWAY_IDLE_STRATEGY on every gateway, verified on each one's "GATEWAY up" line
#   ... arm LABEL KEY=VAL|KEY-  # set/unset member env, roll the members, refuse unless every member's startup line
#                               # shows the idle and archive threading the arm declares (EXPECT_IDLE, EXPECT_ARCHIVE)
#   ... static CPU              # members to 0, the engine pool onto node-engine.yaml (static CPU manager), members
#                               # back at CPU integer CPUs; refuses unless the kubelet reports the static policy
# Every block window also records, on the leader at its start and end: the driver's Aeron counters (AeronCounters: max
# cycle times, cycles over the threshold, back pressure), the container's cpu.stat and cpuset, /proc/interrupts and
# /proc/softirqs (NIC IRQs on the member's cores), and the CPU each thread last ran on.
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
  for p in /proc/[0-9]*; do grep -q "^java" "$p/comm" 2>/dev/null && grep -q ClusterNodeMain "$p/cmdline" 2>/dev/null && { J=${p#/proc/}; break; }; done
  for t in /proc/$J/task/*; do   # a thread can exit mid-scan (idle JIT compiler threads retire): skip it, do not fail
    c=$(cat "$t/comm" 2>/dev/null) && s=$(sed "s/.*) //" "$t/stat" 2>/dev/null) && [ -n "$s" ] || continue
    set -- $s; echo "$c $(( ${12} + ${13} ))"; done' 2>/dev/null; }
# FR-OPL-06: the leader's state at a window edge, one exec; sections are "== name" (l1-report.py reads them).
leader_state(){ kc exec "order-matcher-cluster-$1" -c cluster-node -- sh -c '
  for p in /proc/[0-9]*; do grep -q "^java" "$p/comm" 2>/dev/null && grep -q ClusterNodeMain "$p/cmdline" 2>/dev/null && { J=${p#/proc/}; break; }; done
  echo "== cpu.stat"; cat /sys/fs/cgroup/cpu.stat
  echo "== cpuset"; cat /sys/fs/cgroup/cpuset.cpus.effective
  echo "== siblings"; cat /sys/devices/system/cpu/cpu*/topology/thread_siblings_list | sort -u
  echo "== interrupts"; grep -E "CPU|gve|virtio|eth" /proc/interrupts
  echo "== softirqs"; grep -E "CPU|NET_RX|NET_TX" /proc/softirqs
  echo "== psr"; for t in /proc/$J/task/*; do echo "$(cat "$t/comm") $(sed "s/.*) //" "$t/stat" | cut -d" " -f37)"; done
  echo "== counters"; env -u JAVA_TOOL_OPTIONS java -Xmx64m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 \
    --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
    -cp "/opt/app/classes:/opt/app/lib/*" finos.traderx.ordermatcher.cluster.AeronCounters "${CLUSTER_AERON_DIR:-/dev/shm/aeron-cluster}"' 2>&1; }
first_line(){ awk -v p="$1" 'index($0, p) && !f {print; f = 1}'; }   # reads to EOF: no SIGPIPE upstream
op(){ kc get secret auth-secrets -o jsonpath='{.data.risk-control-token}' | base64 -d \
        | awk '{print "X-Risk-Control-Token: " $0; print "X-Risk-Operator: l0"; print "Content-Type: application/json"}' \
        | kc exec -i deploy/trade-processor -- curl -s -m 30 -o /dev/null -w '%{http_code}' -H @- -X POST "http://order-matcher:18110$1" -d "$2"; }

# The default policy's maxPositionQuantity is reached inside one 300 s phase at these rates (bridge-batching-gke
# 2026-11-09 item 7); raised for the rig's test accounts, as the campaign did.
policy(){ op /risk/control/policy "{\"policyVersion\":$(date +%s),\"killSwitch\":false,\"maxPositionQuantity\":2000000000,\"maxConcentrationNotionalTicks\":9000000000000000000}"; }
# A window measures the background rate only while every load pod runs and nothing it sent was rejected: a rejecting
# session unbalances, and binary-load stops itself at the next phase boundary (2026-11-10: a whole block measured idle).
# The last line is read whole (case, not grep -q: an early grep exit SIGPIPEs kubectl and pipefail reads it as a miss),
# and one failed read is retried after 3 s, so a refusal is the load's state, not an API hiccup (2026-11-10, arm S).
load_once(){ local p bad=0; [ "$(kc get pods -l job-name=binary-load --field-selector=status.phase=Running --no-headers 2>/dev/null | wc -l | tr -d ' ')" = 8 ] || return 1
  for p in $(kc get pods -l job-name=binary-load --no-headers -o custom-columns=:metadata.name); do
    # sent=0 with rejected=0 passed this check on 2026-11-11 (session 3): a sequenced load against pre-ADR-090
    # members waited on its stream probes and never sent, and four blocks measured an idle leader.
    case $(kc logs "$p" --tail=1 2>/dev/null) in *' sent=0 '*) bad=1 ;; *' rejected=0 '*) ;; *) bad=1 ;; esac; done; [ $bad = 0 ]; }
load_ok(){ load_once || { sleep 3; load_once; }; }

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
fix-probe)
  T=${2:?}; PX=${3:?}; SECS=${4:?}; : "${CREDS:?CREDS=provision-fix-test-clients.json}"
  RATE=${PROBE_RATE:-500}; SESSIONS=${PROBE_SESSIONS:-6}; ACCTS=${PROBE_ACCOUNTS:+, --accounts, \"$PROBE_ACCOUNTS\"}
  kc delete job l0-fix-probe --ignore-not-found --wait=true >/dev/null
  kc create configmap l0-fix-probe --from-file=fix-latency-probe.mjs="$HERE/fix-latency-probe.mjs" --dry-run=client -o yaml | kc apply -f - >/dev/null
  kc create secret generic l0-fix-probe-creds --from-file=creds.json="$CREDS" --dry-run=client -o yaml | kc apply -f - >/dev/null
  kc apply -f - >/dev/null <<YAML
apiVersion: batch/v1
kind: Job
metadata: {name: l0-fix-probe}
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
        command: [node, /probe/fix-latency-probe.mjs, --creds, /creds/creds.json, --host, "${FIX_HOST:-order-matcher}",
                  --port, "${FIX_PORT:-18130}", --ticker, "$T", --px, "$PX", --rate, "$RATE", --sessions, "$SESSIONS",
                  --secs, "$SECS", --warmup, "20", --window, "60"$ACCTS]
        resources: {requests: {cpu: "1", memory: 512Mi}, limits: {memory: 1Gi}}
        volumeMounts: [{name: probe, mountPath: /probe}, {name: creds, mountPath: /creds, readOnly: true}]
      volumes: [{name: probe, configMap: {name: l0-fix-probe}}, {name: creds, secret: {secretName: l0-fix-probe-creds}}]
YAML
  log "fix-probe: ${RATE}/s x $SESSIONS FIX sessions on $T@$PX for ${SECS}s" ;;
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
  # No retries: a load that stops itself (unbalanced at a phase boundary) must fail the block, not restart into a
  # rejecting book (2026-11-11, L12). binary-load retries its own first /resolve on a node that is still coming up.
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
                  --phase-secs, "300", --pause-ms, "4000", --max-in-flight, "800", --out, /tmp/results.json${LOAD_FLAGS:+, $LOAD_FLAGS}]
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
    leader_state "$L" > "$D/state-$w-start.txt"
    gw_all '?reset=1' >/dev/null; member "$L" '/latency?reset=1' >/dev/null; threads "$L" > "$D/threads-$w-start.txt"
    t0=$(date +%s); sleep $(( start + 60 - $(date +%s) ))
    if [ "$PER" -gt 0 ] && ! load_ok; then log "REFUSED $LABEL window $w: the load stopped or rejected during it"; exit 1; fi
    gw_all '' > "$D/gw-$w.txt"; member "$L" /latency > "$D/leader-$w.txt"; threads "$L" > "$D/threads-$w-end.txt"; t1=$(date +%s)
    leader_state "$L" > "$D/state-$w-end.txt"
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
seed)
  # after a member restart the gateways (the order-matcher service) reconnect first: curl exit 7 otherwise (2026-11-11)
  kc wait --for=condition=Ready pod -l app=cluster-gateway --timeout=600s >/dev/null
  T=${2:?}; PX=${3:?}; A="9900010 9900011 9900012 9900013 9900014 9900015 9900016 9900017 9900018 9900019"
  vals=$(for a in $A; do printf '(%s,"l1"),' "$a"; done); sql="insert ignore into accounts(id,displayname) values ${vals%,}"
  n=$(kc exec deploy/eod-price-db -c mariadb -- sh -c 'mariadb -N -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" traderx -e "$1; select count(*) from accounts where id between 9900010 and 9900019"' sh "$sql")
  [ "$n" = 10 ] || { log "REFUSED seed: $n of 10 SQL accounts"; exit 1; }
  # bodies in variables: macOS bash 3.2 mis-parses \" inside "$( ... )" ("[: too many arguments", 2026-11-10)
  for a in $A; do   # a fresh epoch knows no account until it is admitted (RI33): the SQL row alone is UNKNOWN_ACCOUNT
    body='{"accountId":'$a',"enabled":true}'; code=$(op /risk/control/account "$body")
    [ "$code" = 200 ] || { log "REFUSED seed: account $a not admitted ($code)"; exit 1; }
  done
  body='{"accountId":9900010,"tickers":"'$T'","price":'$PX'}'; code=$(op /seed "$body")
  [ "$code" = 200 ] || { log "REFUSED seed: tickers $T ($code)"; exit 1; }
  [ "$(policy)" = 200 ] || { log "REFUSED seed: risk policy not raised"; exit 1; }
  log "seed: accounts admitted, tickers $T at $PX, policy raised" ;;
gw-idle)
  S=${2:?}; kc set env deploy/cluster-gateway -c gateway "GATEWAY_IDLE_STRATEGY=$S" >/dev/null
  kc rollout status deploy/cluster-gateway --timeout=900s >/dev/null
  for p in $(kc get pods -l app=cluster-gateway --field-selector=status.phase=Running --no-headers -o custom-columns=:metadata.name); do
    # first_line, not grep -m1/-q: an early exit SIGPIPEs kubectl logs, and pipefail reads that as a miss (2026-11-10)
    case $(kc logs "$p" -c gateway | first_line 'GATEWAY up') in *"driverIdle=$S"*) ;;
      *) log "REFUSED gw-idle: $p does not report driverIdle=$S"; exit 1 ;; esac
  done; log "gw-idle: every gateway reports driverIdle=$S" ;;
arm)
  LABEL=${2:?}; shift 2; : "${EXPECT_IDLE:?}" "${EXPECT_ARCHIVE:?}"
  [ $# -gt 0 ] && kc set env sts/order-matcher-cluster -c cluster-node "$@" >/dev/null
  kc rollout status sts/order-matcher-cluster --timeout=1800s >/dev/null
  for m in 0 1 2; do
    up=$(kc logs "order-matcher-cluster-$m" -c cluster-node | first_line 'Cluster node up')
    case $up in *"idle=[$EXPECT_IDLE] archiveThreading=$EXPECT_ARCHIVE"*) ;;
      *) log "REFUSED arm $LABEL: m$m started with '${up#*baseDir=}', not idle=[$EXPECT_IDLE] archiveThreading=$EXPECT_ARCHIVE"; exit 1 ;; esac
  done
  if [ -n "${EXPECT_DRIVER:-}" ]; then for m in 0 1 2; do   # FR-GMR-14: the start line names the media driver mode
    case $(kc logs "order-matcher-cluster-$m" -c cluster-node | first_line 'Cluster node up') in *"mediaDriver=$EXPECT_DRIVER"*) ;;
      *) log "REFUSED arm $LABEL: m$m does not report mediaDriver=$EXPECT_DRIVER"; exit 1 ;; esac; done; fi
  until L=$(leader 2>/dev/null); do sleep 5; done
  log "arm $LABEL: members rolled, leader m$L; idle=[$EXPECT_IDLE] archiveThreading=$EXPECT_ARCHIVE; $(kc get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[?(@.name=="cluster-node")].resources.limits.cpu}') CPU" ;;
static|shared)
  # static CPU: the engine pool onto node-engine.yaml, members at CPU exclusive. shared CPU: the same config with
  # cpuManagerPolicy none, members at CPU on shared cores (the order control, N' / N'', FR-OPL-07).
  MODE=$1; CPU=${2:?}; POOL=${ENGINE_POOL:-p4k-engine}; : "${CLUSTER:?}" "${ZONE:?}" "${PROJECT:?}"
  # Both configs are node-engine.yaml with the policy line set, so this works whether or not the production config
  # carries the static policy (FR-PRG-05 is a separate decision).
  POLICY=static; [ "$MODE" = shared ] && POLICY=none; CFG="$EVIDENCE/node-engine-$MODE.yaml"
  awk -v p="$POLICY" '/^  cpuManagerPolicy:/ {next} {print} /^kubeletConfig:$/ {print "  cpuManagerPolicy: " p}' "$RIG/node-engine.yaml" > "$CFG"
  [ "$(grep -c "^  cpuManagerPolicy: $POLICY\$" "$CFG")" = 1 ] || { log "REFUSED $MODE: could not derive the $POLICY config"; exit 1; }
  # NODE_SYSCTLS=k=v,k=v (latency session 3: net.core.busy_read, net.core.wmem_*): added under linuxConfig.sysctl,
  # replacing the same key; unset, the node gets exactly node-engine.yaml's sysctls.
  for kv in $(echo "${NODE_SYSCTLS:-}" | tr ',' ' '); do k=${kv%%=*}; v=${kv#*=}
    awk -v k="$k" -v v="$v" 'index($0, "    " k ":") == 1 {next} {print} /^  sysctl:$/ {print "    " k ": '"'"'" v "'"'"'"}' "$CFG" > "$CFG.tmp" && mv "$CFG.tmp" "$CFG"
    [ "$(grep -c "^    $k: '$v'\$" "$CFG")" = 1 ] || { log "REFUSED $MODE: could not set $k=$v"; exit 1; }
  done
  g(){ gcloud container "$@" --zone "$ZONE" --project "$PROJECT" --quiet; }   # resize takes the cluster positionally
  kc scale sts/order-matcher-cluster --replicas=0 >/dev/null
  until [ -z "$(kc get pods -l app=order-matcher-cluster -o name)" ]; do sleep 5; done
  n=$(kubectl --context "$CTX" get nodes -l "cloud.google.com/gke-nodepool=$POOL" --no-headers | wc -l | tr -d ' ')
  [ "$n" -gt 0 ] || n=3
  g clusters resize "$CLUSTER" --node-pool "$POOL" --num-nodes 0 >/dev/null
  g node-pools update "$POOL" --cluster "$CLUSTER" --system-config-from-file "$CFG" >/dev/null
  g clusters resize "$CLUSTER" --node-pool "$POOL" --num-nodes "$n" >/dev/null
  [ "$(g node-pools describe "$POOL" --cluster "$CLUSTER" --format='value(config.kubeletConfig.cpuManagerPolicy)')" = "$POLICY" ] \
    || { log "REFUSED $MODE: $POOL does not report cpuManagerPolicy $POLICY"; exit 1; }
  for kv in $(echo "${NODE_SYSCTLS:-}" | tr ',' ' '); do k=${kv%%=*}; v=${kv#*=}
    [ "$(g node-pools describe "$POOL" --cluster "$CLUSTER" --format="value(config.linuxNodeConfig.sysctls.\"$k\")")" = "$v" ] \
      || { log "REFUSED $MODE: $POOL does not report $k=$v"; exit 1; }
  done
  kc set resources sts/order-matcher-cluster -c cluster-node --requests="cpu=$CPU" --limits="cpu=$CPU" >/dev/null
  kc scale sts/order-matcher-cluster --replicas=3 >/dev/null
  kc rollout status sts/order-matcher-cluster --timeout=1800s >/dev/null
  for m in 0 1 2; do   # static: the container's cpuset is exactly CPU wide; shared: wider (the node's)
    cs=$(kc exec "order-matcher-cluster-$m" -c cluster-node -- cat /sys/fs/cgroup/cpuset.cpus.effective)
    w=$(echo "$cs" | tr ',' '\n' | awk -F- '{n += ($2 == "" ? 1 : $2 - $1 + 1)} END {print n}')
    if [ "$MODE" = static ]; then [ "$w" = "$CPU" ] || { log "REFUSED static: m$m cpuset $cs is $w CPUs, not $CPU exclusive"; exit 1; }
    else [ "$w" -gt "$CPU" ] || { log "REFUSED shared: m$m cpuset $cs is $w CPUs, still exclusive"; exit 1; }; fi
  done
  until L=$(leader 2>/dev/null); do sleep 5; done
  log "$MODE: $POOL on cpuManagerPolicy $POLICY ($n nodes)${NODE_SYSCTLS:+, sysctls $NODE_SYSCTLS}, members at $CPU CPU (cpuset $w wide), leader m$L" ;;
pin)
  # Latency session 3: each hot agent (sender, receiver, consensus module, clustered service) on its own physical core
  # of the member's exclusive cpuset, and every other JVM thread kept off those cores and their SMT siblings. Needs the
  # static CPU manager (refuses on a cpuset wider than the member's request). Lasts until the member restarts.
  CPU=${2:?}
  for m in 0 1 2; do
    out=$(kc exec "order-matcher-cluster-$m" -c cluster-node -- sh -c '
      set -e; CPU='"$CPU"'
      for p in /proc/[0-9]*; do grep -q "^java" "$p/comm" 2>/dev/null && grep -q ClusterNodeMain "$p/cmdline" 2>/dev/null && { J=${p#/proc/}; break; }; done
      mine=$(cat /sys/fs/cgroup/cpuset.cpus.effective)
      expand(){ for part in $(echo "$1" | tr "," " "); do case $part in
        *-*) i=${part%-*}; while [ "$i" -le "${part#*-}" ]; do echo "$i"; i=$((i + 1)); done ;; *) echo "$part" ;; esac; done; }
      cpus=$(expand "$mine"); [ "$(echo "$cpus" | wc -l)" = "$CPU" ] || { echo "REFUSED cpuset $mine is not $CPU exclusive"; exit 1; }
      # whole physical cores inside the cpuset, as "first,sibling" groups
      groups=$(for c in $cpus; do s=$(cat /sys/devices/system/cpu/cpu$c/topology/thread_siblings_list); all=1
        for x in $(expand "$s"); do echo "$cpus" | grep -qx "$x" || all=0; done; [ $all = 1 ] && echo "$s"; done | sort -u)
      [ "$(echo "$groups" | wc -l)" -ge 5 ] || { echo "REFUSED only $(echo "$groups" | wc -l) whole cores in $mine"; exit 1; }
      i=0; hot=""
      for name in sender receiver consensus-modul clustered-servi; do
        i=$((i + 1)); g=$(echo "$groups" | sed -n "${i}p"); c=${g%%[,-]*}
        for t in /proc/$J/task/*; do [ "$(cat "$t/comm" 2>/dev/null)" = "$name" ] && taskset -p -c "$c" "${t##*/}" >/dev/null && echo "$name -> $c (core $g)"; done
        hot="$hot $(expand "$g" | tr "\\n" " ")"
      done
      rest=$(for c in $cpus; do echo " $hot " | grep -q " $c " || echo "$c"; done | paste -sd, -)
      n=0; for t in /proc/$J/task/*; do case " sender receiver consensus-modul clustered-servi " in *" $(cat "$t/comm" 2>/dev/null) "*) ;;
        *) taskset -p -c "$rest" "${t##*/}" >/dev/null 2>&1 && n=$((n + 1)) ;; esac; done
      echo "others ($n threads) -> $rest"') || { log "REFUSED pin m$m: $out"; exit 1; }
    case $out in *REFUSED*) log "REFUSED pin m$m: $out"; exit 1 ;; esac
    log "pin m$m: $(echo "$out" | tr '\n' ';')"
  done ;;
*) echo "unknown: $1" >&2; exit 2 ;;
esac
