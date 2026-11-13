#!/usr/bin/env bash
# latency-tail: per-arm hook for l1-ab.sh (ARM_HOOK). start LABEL: HxProbe (1 ms CnC counters, 10 ms OS line) on every
# member. stop LABEL: stop the probes and save, before the next roll deletes the pods: each probe's series, each
# member's GC/safepoint/snapshot/election log lines and memory.events, every gateway's GC/safepoint lines.
set -uo pipefail
: "${CTX:?}" "${EVIDENCE:?}" "${PROBE_JAR:?}"; NS=${NS:-pa-proof-400k}; what=$1; label=$2; out="$EVIDENCE/tail-$label"; mkdir -p "$out"
kc(){ kubectl --context "$CTX" -n "$NS" "$@"; }
case $what in
start)
  for m in 0 1 2; do P=order-matcher-cluster-$m
    kc exec -i "$P" -c cluster-node -- sh -c 'cat > /tmp/hx-probe.jar' < "$PROBE_JAR" || { echo "HOOK-FAIL copy $m"; exit 1; }
    pid=$(kc exec "$P" -c cluster-node -- sh -c 'for p in /proc/[0-9]*; do grep -q "^java" $p/comm 2>/dev/null && grep -q "ClusterNodeMain" $p/cmdline 2>/dev/null && { basename $p; break; }; done')
    cnc=$(kc exec "$P" -c cluster-node -- sh -c 'dirname "$(find /dev/shm -maxdepth 3 -name cnc.dat 2>/dev/null | head -1)"')
    [ -n "$pid" ] && [ -n "$cnc" ] && [ "$cnc" != . ] || { echo "HOOK-FAIL pid '$pid' cnc '$cnc' on $m"; exit 1; }
    now=$(date +%s); start=$(( (now + 5) * 1000 )); end=$(( (now + ${PROBE_SECS:-1800}) * 1000 ))
    kc exec "$P" -c cluster-node -- sh -c "rm -rf /dev/shm/hxprobe; nohup env -u JAVA_TOOL_OPTIONS java -Xmx256m -XX:+UseSerialGC --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -cp /tmp/hx-probe.jar:/opt/app/lib/* hxb.probe.HxProbe $pid $cnc /dev/shm/hxprobe $start $end < /dev/null > /dev/shm/hxprobe.log 2>&1 & echo \$! > /dev/shm/hxprobe.pid" || { echo "HOOK-FAIL start $m"; exit 1; }
    echo "$m pid=$pid cnc=$cnc start=$start end=$end role=$(kc exec "$P" -c cluster-node -- wget -qO- localhost:8080/health | jq -r .role)" | tee -a "$out/probes.txt"
  done
  sleep 8
  for m in 0 1 2; do   # self-test: the probe must be writing
    sz=$(kc exec order-matcher-cluster-$m -c cluster-node -- sh -c 'wc -c < /dev/shm/hxprobe/counters.reg' 2>/dev/null || echo 0)
    [ "${sz:-0}" -gt 1000 ] || { echo "HOOK-FAIL probe on $m wrote nothing"; kc exec order-matcher-cluster-$m -c cluster-node -- cat /dev/shm/hxprobe.log; exit 1; }
  done ;;
stop)
  date -u +%FT%TZ > "$out/stopped.at"
  for m in 0 1 2; do P=order-matcher-cluster-$m
    kc exec "$P" -c cluster-node -- sh -c 'kill $(cat /dev/shm/hxprobe.pid) 2>/dev/null; sleep 1; cd /dev/shm/hxprobe && tar czf - counters.reg counters.bin os.log' > "$out/hxprobe-m$m.tgz" || echo "HOOK-WARN probe fetch $m"
    kc logs "$P" -c cluster-node > "$out/member-$m.full.log" 2>/dev/null
    awk '/\]\[gc|safepoint|[Ss]napshot|ELECTION|role|idle=\[|Allocation Stall/' "$out/member-$m.full.log" > "$out/member-$m.log"; rm -f "$out/member-$m.full.log"
    kc exec "$P" -c cluster-node -- sh -c 'echo "== memory.events"; cat /sys/fs/cgroup/memory.events; echo "== memory.stat"; cat /sys/fs/cgroup/memory.stat; echo "== pressure"; grep . /sys/fs/cgroup/*.pressure; echo "== df"; df -h /data' > "$out/member-$m.cgroup.txt" 2>&1
  done
  for p in $(kc get pods -l app=cluster-gateway -o name); do
    kc logs "$p" -c gateway --since=40m 2>/dev/null | awk '/\]\[gc|safepoint/' > "$out/gw-${p##*/}.log"
  done
  ls -la "$out" | awk '{print $5, $9}' | head -8 ;;
esac
