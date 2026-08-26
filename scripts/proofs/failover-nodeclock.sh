#!/usr/bin/env bash
# failover-nodeclock.sh — node-clock-precise failover measurement for the Aeron Cluster.
#
# Why node-clock: timing from the laptop that issues `kubectl delete` folds in kubectl round
# trip, API-server scheduling and laptop/cluster clock skew — tens to hundreds of ms of error on
# a number whose whole point is being sub-second. Both stamps here come from the CLUSTER's clocks:
#   t0 = `date +%s%3N` run INSIDE a pod immediately before the kill
#   t1 = the new leader's own "ROLE-CHANGE role=LEADER atMs=" / "FIRST-APPLY atMs=" stdout stamps
#
# Two numbers are reported and they mean different things:
#   electionMs  = t0 -> ROLE-CHANGE(LEADER).  Raft elected a new leader.
#   servingMs   = t0 -> FIRST-APPLY.          The new leader APPLIED its first committed message,
#                                             i.e. the system is actually serving again.
# servingMs is the system-facing SLO. It is only meaningful with load in flight: FIRST-APPLY
# cannot fire until a client message reaches the new leader, so on an idle cluster it measures
# "time until someone happened to send something" (observed ~10s idle). ALWAYS run with load —
# and DENSE load: a ~1/s probe pump inflates servingMs to the pump's retry cadence, not the
# cluster's actual time-to-serving.
#
# Usage: CONTEXT=<kctx> NS=traderx ROUNDS=3 bash failover-nodeclock.sh
# Prereq: a pod to stamp t0 from (BENCH_RUNNER_POD, default `bench-runner`); falls back to the
# member being killed. ALSO: space the rounds — a kill fired before the previous member has
# rejoined measures a degraded cluster, and a heavy load backlog inflates servingMs (FIRST-APPLY
# waits on the queue, not on promotion). Observed 2026-07-26 under a 40k/s pump with back-to-back
# rounds: rounds 1-2 found no leader at all and round 3 read election 2740ms / serving 7851ms —
# an order of magnitude off the idle 2026-07-18 drills. Measure one clean round at moderate load.
set -uo pipefail
CTX="${CONTEXT:?set CONTEXT}"; NS="${NS:-traderx}"; ROUNDS="${ROUNDS:-3}"
kc() { kubectl --context "$CTX" -n "$NS" --request-timeout=60s "$@"; }

role() { kc exec order-matcher-cluster-"$1" -- sh -c 'curl -s -m 5 localhost:8080/metrics' 2>/dev/null \
         | awk 'index($1,"traderx_cluster_role{")==1{print $2}'; }
leader() { for i in 0 1 2; do [ "$(role $i)" = "1" ] && echo "$i" && return; done; }
# NOT `applied`. That is a GLOBAL counter and the feed adapter advances it by 69 per flush with
# zero load (measured 2026-08-24: applied 3937958 -> 3938027 over ~20s idle, next_order_ref
# unmoved across the same window). Reading it here let the guard below fall SILENT whenever a
# flush happened to land in its 2s sample, so the tool would go on to report servingMs figures
# for a rig carrying no load at all. `next_order_ref` is the order-shaped counter the feed never
# touches. See scripts/proofs/lib-consensus-readings.sh for the full reasoning and the test any
# replacement reading has to pass.
order_refs() { kc exec order-matcher-cluster-"$1" -- sh -c 'curl -s -m 5 localhost:8080/metrics' 2>/dev/null \
            | awk 'index($1,"traderx_cluster_next_order_ref{")==1{print $2}'; }

# Full log, not --tail=N: members emit heavy ELECTION-PHASE output and a small tail window
# silently drops the very stamps we are looking for (this cost a whole measurement run).
stamp() { # <member> <marker> <t0> -> latest atMs >= t0
  kc logs order-matcher-cluster-"$1" 2>/dev/null \
    | awk -v m="$2" -v t0="$3" '$0 ~ m {for(i=1;i<=NF;i++) if ($i ~ /^atMs=/) {split($i,a,"="); if (a[2]+0>=t0+0) v=a[2]}} END{if(v!="")print v}'
}

for r in $(seq 1 "$ROUNDS"); do
  L=$(leader)
  if [ -z "$L" ]; then echo "round $r: no leader yet, waiting"; sleep 15; continue; fi

  # Confirm load is actually flowing, otherwise servingMs is meaningless.
  A1=$(order_refs "$L"); sleep 2; A2=$(order_refs "$L")
  if [ -z "$A1" ] || [ "$A2" = "$A1" ]; then
    echo "round $r: WARNING no order traffic is being sequenced (next_order_ref stuck at ${A1:-?}) — start load first"; fi

  # busybox date has no %3N (prints the format literally), so ms-precision epoch isn't directly
  # readable in the pod. Instead spin until the second rolls over and stamp that edge: accurate
  # to the in-pod exec-loop latency (a few ms), portable to any image with `date +%s`.
  # The stamping pod: any Ready pod with `date` works. Override with BENCH_RUNNER_POD; default
  # `bench-runner` must exist (create one with:
  #   kubectl -n <ns> run bench-runner --image=busybox --restart=Never -- sh -c 'sleep 3600')
  # and if it is absent we fall back to a cluster member, which always exists on a live cluster.
  T0S=$(kc exec "${BENCH_RUNNER_POD:-bench-runner}" -- sh -c 's=$(date +%s); while [ "$(date +%s)" = "$s" ]; do :; done; date +%s' 2>/dev/null | tr -d '\r')
  if ! [[ "$T0S" =~ ^[0-9]{10}$ ]]; then
    T0S=$(kc exec order-matcher-cluster-"$L" -- sh -c 's=$(date +%s); while [ "$(date +%s)" = "$s" ]; do :; done; date +%s' 2>/dev/null | tr -d '\r')
  fi
  if ! [[ "$T0S" =~ ^[0-9]{10}$ ]]; then
    echo "FATAL round $r: t0 capture failed (got '${T0S}') — cannot read a numeric epoch from bench-runner; refusing to print garbage deltas" >&2
    exit 1
  fi
  T0=$((T0S * 1000))
  kc delete pod order-matcher-cluster-"$L" --grace-period=0 --force >/dev/null 2>&1

  RC=""; FA=""; NEW=""
  for _ in $(seq 1 90); do
    for i in 0 1 2; do
      [ "$i" = "$L" ] && continue
      [ -z "$RC" ] && RC=$(stamp "$i" "ROLE-CHANGE role=LEADER" "$T0") && [ -n "$RC" ] && NEW="$i"
      if [ -n "$NEW" ]; then f=$(stamp "$NEW" "FIRST-APPLY" "$T0"); [ -n "$f" ] && FA="$f"; fi
    done
    [ -n "$FA" ] && break
    sleep 0.5
  done

  if [ -n "$FA" ]; then
    echo "FAILOVER round=$r killed=m$L promoted=m$NEW electionMs=$((RC - T0)) servingMs=$((FA - T0))"
  elif [ -n "$RC" ]; then
    echo "FAILOVER round=$r killed=m$L promoted=m$NEW electionMs=$((RC - T0)) servingMs=NO-FIRST-APPLY(no load?)"
  else
    echo "FAILOVER round=$r killed=m$L no promotion observed within 45s"
  fi

  until [ "$(kc get pod order-matcher-cluster-"$L" --no-headers 2>/dev/null | awk '{print $2}')" = "1/1" ]; do sleep 3; done
  sleep 20
done
