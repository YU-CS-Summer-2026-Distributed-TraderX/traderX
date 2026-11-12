#!/usr/bin/env bash
# Latency L1 + L2 A/B (FR-OPL-07): one rig, one session, the L0 window protocol (l0-baseline.sh) per arm at 0, ~300K
# and the 500K setting (saturation), gateways on lowpark throughout. Five arms, each a member roll:
#   N    today's member: CLUSTER_IDLE_STRATEGY=lowpark, SHARED archive, 15 CPU, engine nodes WITHOUT the static CPU manager
#   ---- the engine pool moves onto node-engine.yaml (static CPU manager); members at MEMBER_CPU exclusive CPUs ----
#   S    N's member on exclusive cores (the core budget alone)
#   L1   S + sender, receiver, consensus module and clustered service busy-spinning (conductor and archive lowpark)
#   L2   S + CLUSTER_ARCHIVE_THREADING=DEDICATED
#   L12  L1 + L2
# N vs S bounds the static CPU manager, S vs L1/L2/L12 the agents' idle and the archive threading. The probe (500/s,
# 16 sessions) runs per arm, so no roll lands inside it.
#   CTX=... EVIDENCE=dir CLUSTER=... ZONE=... PROJECT=... bash l1-ab.sh [ARM...]     # default: N S L1 L2 L12
#   ... bash l1-ab.sh Np Sp Npp   # the order control: shared, static, shared again (500K only)
set -euo pipefail
: "${CTX:?}" "${EVIDENCE:?}" "${CLUSTER:?}" "${ZONE:?}" "${PROJECT:?}"
HERE=$(cd "$(dirname "$0")" && pwd); B="$HERE/l0-baseline.sh"; export CTX EVIDENCE CLUSTER ZONE PROJECT
MEMBER_CPU=${MEMBER_CPU:-14}; PX=${PX:-50}; PER300=586; PER500=977   # 8 pods x 64 sessions: 300,032/s and 500,224/s
log(){ echo "[$(TZ=America/New_York date '+%-I:%M:%S %p ET')] $*" | tee -a "$EVIDENCE/l0.log"; }

idle(){ echo "sender=$1 receiver=$1 conductor=lowpark archive=lowpark consensus=$1 service=$1"; }
SPIN=(CLUSTER_IDLE_STRATEGY_SENDER=busyspin CLUSTER_IDLE_STRATEGY_RECEIVER=busyspin
      CLUSTER_IDLE_STRATEGY_CONSENSUS=busyspin CLUSTER_IDLE_STRATEGY_SERVICE=busyspin)
NOSPIN=(CLUSTER_IDLE_STRATEGY_SENDER- CLUSTER_IDLE_STRATEGY_RECEIVER- CLUSTER_IDLE_STRATEGY_CONSENSUS-
        CLUSTER_IDLE_STRATEGY_SERVICE- CLUSTER_IDLE_STRATEGY_CONDUCTOR- CLUSTER_IDLE_STRATEGY_ARCHIVE-)
# Cycles over 1 ms are counted per agent (Aeron's defaults, 100 ms to 1 s, count nothing a latency tail cares about).
CYCLE='-Daeron.driver.conductor.cycle.threshold=1ms -Daeron.driver.sender.cycle.threshold=1ms -Daeron.driver.receiver.cycle.threshold=1ms -Daeron.cluster.cycle.threshold=1ms -Daeron.cluster.service.cycle.threshold=1ms -Daeron.archive.conductor.cycle.threshold=1ms -Daeron.archive.recorder.cycle.threshold=1ms -Daeron.archive.replayer.cycle.threshold=1ms'

arm(){   # arm LABEL SPIN(busyspin|lowpark) ARCHIVE(SHARED|DEDICATED) [RATES="0 300 500"]
  local label=$1 a=$3 rates=${4:-${RATES:-0 300 500}} env=(CLUSTER_IDLE_STRATEGY=lowpark)
  if [ "$2" = busyspin ]; then env+=("${SPIN[@]}"); else env+=("${NOSPIN[@]}"); fi
  if [ "$a" = DEDICATED ]; then env+=(CLUSTER_ARCHIVE_THREADING=DEDICATED); else env+=(CLUSTER_ARCHIVE_THREADING-); fi
  local kv; for kv in ${ARM_ENV:-}; do env+=("$kv"); done   # FR-OPL-14: an arm's own member env, KEY=VAL or KEY-
  EXPECT_IDLE=$(idle "$2") EXPECT_ARCHIVE=$a EXPECT_DRIVER=${EXPECT_DRIVER:-} bash "$B" arm "$label" "${env[@]}"
  [ -z "${ARM_POST:-}" ] || bash "$B" $ARM_POST   # e.g. ARM_POST="pin 14": after the roll, before the probe
  for kv in ${ARM_ENV:-}; do   # refuse unless every member runs the env the arm declares
    local k=${kv%%[=-]*} want="" m got; case $kv in *=*) want=${kv#*=} ;; esac
    for m in 0 1 2; do
      got=$(kubectl --context "$CTX" -n "${NS:-pa-proof-400k}" exec "order-matcher-cluster-$m" -c cluster-node -- sh -c "printenv $k || true")
      [ "$got" = "$want" ] || { log "REFUSED arm $label: m$m has $k='$got', the arm declares '$want'"; exit 1; }
    done
  done
  # A member roll restarts the gateways' cluster sessions: wait for every gateway to be Ready before the probe, and
  # refuse the arm unless the probe is running and has written its first window (S' 2026-11-11 measured no client
  # latency: its probe died on ECONNREFUSED and the blocks ran without it).
  kubectl --context "$CTX" -n "${NS:-pa-proof-400k}" wait --for=condition=Ready pod -l app=cluster-gateway --timeout=600s >/dev/null
  # ARM_HOOK (FR-OPL-10): a per-arm collector, started on the rolled members and stopped before the next roll
  [ -z "${ARM_HOOK:-}" ] || bash "$ARM_HOOK" start "$label" || { log "REFUSED arm $label: the hook did not start"; exit 1; }
  bash "$B" probe "LP$label" "$PX" 1800
  # a window is printed one window after it closes, after a 20 s warm-up: up to ~200 s, so poll for 300 s
  local active="" i
  for i in $(seq 1 30); do
    sleep 10; active=$(kubectl --context "$CTX" -n "${NS:-pa-proof-400k}" get job l0-probe -o jsonpath='{.status.active}')
    [ "$active" = 1 ] || { log "REFUSED arm $label: the probe is not running (active=$active)"; exit 1; }
    case "$(kubectl --context "$CTX" -n "${NS:-pa-proof-400k}" logs job/l0-probe 2>/dev/null)" in   # whole read, not grep -q
      *PROBE-WINDOW*) break ;; esac
    [ "$i" -lt 30 ] || { log "REFUSED arm $label: the probe has written no window in 300 s"; exit 1; }
  done
  case " $rates " in *" 0 "*) bash "$B" block "$label-0" 0 "LP$label" "$PX" 2 ;; esac
  case " $rates " in *" 300 "*) bash "$B" block "$label-300" "$PER300" "L3$label" "$PX" 3 ;; esac
  case " $rates " in *" 500 "*) bash "$B" block "$label-500" "$PER500" "L5$label" "$PX" 3 ;; esac
  kubectl --context "$CTX" -n "${NS:-pa-proof-400k}" logs job/l0-probe > "$EVIDENCE/probe-$label.log" 2>&1 || true
  [ -z "${ARM_HOOK:-}" ] || bash "$ARM_HOOK" stop "$label" || log "arm $label: the hook's stop failed"
  kubectl --context "$CTX" -n "${NS:-pa-proof-400k}" delete job l0-probe --wait=true >/dev/null   # before the next roll
  log "arm $label done"
}

ensure_cycle_flags(){   # once: the thresholds go into the members' JVM options (a roll, folded into the first arm's)
  local cur; cur=$(kubectl --context "$CTX" -n "${NS:-pa-proof-400k}" get sts order-matcher-cluster \
    -o jsonpath='{.spec.template.spec.containers[?(@.name=="cluster-node")].env[?(@.name=="JAVA_TOOL_OPTIONS")].value}')
  case $cur in *aeron.driver.sender.cycle.threshold*) ;; *)
    kubectl --context "$CTX" -n "${NS:-pa-proof-400k}" set env sts/order-matcher-cluster -c cluster-node \
      "JAVA_TOOL_OPTIONS=$cur $CYCLE" >/dev/null ;; esac
}

# Shared cores = the member's cpuset is wider than its request. Not nproc: coreutils counts the CFS quota (15 on shared
# cores, 2026-11-11), so it cannot tell a 15-CPU shared member from exclusive cores.
cpuset_width(){ kubectl --context "$CTX" -n "${NS:-pa-proof-400k}" exec order-matcher-cluster-0 -c cluster-node -- \
  cat /sys/fs/cgroup/cpuset.cpus.effective | tr ',' '\n' | awk -F- '{n += ($2 == "" ? 1 : $2 - $1 + 1)} END {print n}'; }
# binary-load is sequenced by default since ADR-090; members built before it (the l1-eab81f9f images) never answer its
# stream probes, so it sends nothing (2026-11-11). SEQUENCED=1 for exactly-once builds.
[ "${SEQUENCED:-0}" = 1 ] || export LOAD_FLAGS=${LOAD_FLAGS:---unsequenced}
kc_(){ kubectl --context "$CTX" -n "${NS:-pa-proof-400k}" "$@"; }
OFF_BUSY=net.core.busy_read=0,net.core.busy_poll=0
OFF_WMEM=net.core.wmem_max=212992,net.core.wmem_default=212992   # the kernel's defaults, stated
ARMS=("$@"); [ ${#ARMS[@]} -gt 0 ] || ARMS=(N S L1 L2 L12)
bash "$B" seed "$(for a in "${ARMS[@]}"; do printf 'LP%s,L3%s,L5%s,' "$a" "$a" "$a"; done | sed 's/,$//')" "$PX"
bash "$B" gw-idle lowpark
ensure_cycle_flags
for a in "${ARMS[@]}"; do
  case $a in
    # The order control (2026-11-11): N' on shared cores, S' on static, N'' back on shared at the saturation setting, so
    # static's capacity cost is measured N-S-N within one session instead of after a filling disk.
    Np)  cs=$(cpuset_width)
         [ "$cs" -gt 15 ] || { log "REFUSED arm Np: m0's cpuset is $cs CPUs, already exclusive; Np needs the shared pool"; exit 1; }
         arm Np lowpark SHARED ;;
    Sp)  bash "$B" static "$MEMBER_CPU"; arm Sp lowpark SHARED ;;
    Npp) bash "$B" shared 15; arm Npp lowpark SHARED 500 ;;
    Spp) [ "${STATIC_DONE:-0}" = 1 ] || bash "$B" static "$MEMBER_CPU"; arm Spp lowpark SHARED ;;
    # Latency session 3 (2026-11-11): busy-spin on SHARED cores (production keeps shared, latency-static-cpu-20261111),
    # kernel busy-read and wider send buffers on that shape, then static cores with and without per-thread pinning.
    # Each node-config arm states every knob it touches, so no arm inherits the previous one's sysctls.
    Nsh)    NODE_SYSCTLS=$OFF_BUSY,$OFF_WMEM bash "$B" shared 15; arm Nsh lowpark SHARED ;;
    L1sh)   arm L1sh busyspin SHARED ;;
    L1shbr) NODE_SYSCTLS=$OFF_WMEM,net.core.busy_read=50,net.core.busy_poll=50 bash "$B" shared 15; arm L1shbr busyspin SHARED ;;
    L1shwm) NODE_SYSCTLS=$OFF_BUSY,net.core.wmem_max=16777216,net.core.wmem_default=4194304 bash "$B" shared 15; arm L1shwm busyspin SHARED ;;
    L1st)   NODE_SYSCTLS=$OFF_BUSY,$OFF_WMEM bash "$B" static "$MEMBER_CPU"; arm L1st busyspin SHARED ;;
    L1pin)  ARM_POST="pin $MEMBER_CPU" arm L1pin busyspin SHARED ;;
    Nsh2)   NODE_SYSCTLS=$OFF_BUSY,$OFF_WMEM bash "$B" shared 15; arm Nsh2 lowpark SHARED 500 ;;
    # L6a (FR-GMR-14): the external-mode build on the embedded driver at 15 and at 12 CPUs (the member's share in
    # the C arm), then the C driver sidecar (3 CPUs) beside a 12-CPU member. E vs E12 is the CPU share alone,
    # E12 vs C is the driver alone. After C the StatefulSet is restored, so later arms run the embedded driver.
    # A newer engine: never roll one member at a time onto it (mixed versions replaying one log can diverge). Stop
    # all three, switch, start all three; the gateways take the matching image (its egress format).
    L1shE)  : "${L6A_IMAGE:?}" "${L6A_GW_IMAGE:?}"
            kc_ scale sts/order-matcher-cluster --replicas=0 >/dev/null
            until [ -z "$(kc_ get pods -l app=order-matcher-cluster -o name)" ]; do sleep 5; done
            kc_ set image sts/order-matcher-cluster "cluster-node=$L6A_IMAGE" >/dev/null
            kc_ set resources sts/order-matcher-cluster -c cluster-node --requests=cpu=15 --limits=cpu=15 >/dev/null
            kc_ scale sts/order-matcher-cluster --replicas=3 >/dev/null
            kc_ set image deploy/cluster-gateway "gateway=$L6A_GW_IMAGE" >/dev/null
            kc_ rollout status deploy/cluster-gateway --timeout=1200s >/dev/null
            EXPECT_DRIVER=embedded arm L1shE busyspin SHARED ;;
    L1shE12) kc_ set resources sts/order-matcher-cluster -c cluster-node --requests=cpu=12 --limits=cpu=12 >/dev/null
            EXPECT_DRIVER=embedded arm L1shE12 busyspin SHARED ;;
    L1shC)  : "${AERONMD_IMAGE:?}"
            kc_ set resources sts/order-matcher-cluster -c cluster-node --requests=cpu=15 --limits=cpu=15 >/dev/null
            kc_ get sts order-matcher-cluster -o json > "$EVIDENCE/sts-embedded.json"
            # the arm's env first, so the sidecar mirrors the busy-spin agents (sender/receiver -> noop)
            kc_ set env sts/order-matcher-cluster -c cluster-node "${SPIN[@]}" CLUSTER_IDLE_STRATEGY=lowpark CLUSTER_ARCHIVE_THREADING- >/dev/null
            kc_ get sts order-matcher-cluster -o json | python3 "$HERE/aeronmd-sidecar.py" --image "$AERONMD_IMAGE" --cpu 3 \
              > "$EVIDENCE/sts-cdriver.json"
            kc_ replace -f "$EVIDENCE/sts-cdriver.json" >/dev/null
            EXPECT_DRIVER=external arm L1shC busyspin SHARED
            for m in 0 1 2; do kc_ logs "order-matcher-cluster-$m" -c aeronmd > "$EVIDENCE/aeronmd-m$m.log" 2>&1 || true; done
            jq 'del(.metadata.resourceVersion, .metadata.uid, .metadata.creationTimestamp, .metadata.generation, .status)' \
              "$EVIDENCE/sts-embedded.json" | kc_ replace -f - >/dev/null
            kc_ rollout status sts/order-matcher-cluster --timeout=1800s >/dev/null; log "L1shC: StatefulSet restored to the embedded driver" ;;   # S again, last: shared, static, shared, static
    # FR-OPL-14: the service-thread cost knobs (FR-OPL-12) on today's member, shared cores. Nk, Nk2, Nk3 are the
    # control at the start, between and at the end; RT both knobs, Rk the ring alone, Tk the timing alone.
    Nk|Nk2|Nk3) ARM_ENV="CLUSTER_OUTPUT_RING_WAIT- CLUSTER_ENGINE_TIMING-" arm "$a" lowpark SHARED ;;
    RT)  ARM_ENV="CLUSTER_OUTPUT_RING_WAIT=none CLUSTER_ENGINE_TIMING=off" arm RT lowpark SHARED ;;
    Rk)  ARM_ENV="CLUSTER_OUTPUT_RING_WAIT=none CLUSTER_ENGINE_TIMING-" arm Rk lowpark SHARED ;;
    Tk)  ARM_ENV="CLUSTER_OUTPUT_RING_WAIT- CLUSTER_ENGINE_TIMING=off" arm Tk lowpark SHARED ;;
    N)   cs=$(cpuset_width)
         [ "$cs" -gt 15 ] || { log "REFUSED arm N: m0's cpuset is $cs CPUs, already exclusive; N needs the shared pool"; exit 1; }
         arm N lowpark SHARED ;;
    S)   [ "${STATIC_DONE:-0}" = 1 ] || bash "$B" static "$MEMBER_CPU"; arm S lowpark SHARED ;;   # STATIC_DONE=1: resume
    L1)  arm L1 busyspin SHARED ;;
    L2)  arm L2 lowpark DEDICATED ;;
    L12) arm L12 busyspin DEDICATED ;;
    *) echo "unknown arm: $a" >&2; exit 2 ;;
  esac
done
log "A/B done: ${ARMS[*]}"
