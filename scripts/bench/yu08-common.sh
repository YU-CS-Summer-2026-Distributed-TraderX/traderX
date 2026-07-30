#!/usr/bin/env bash
# YU08 shared helpers for the execution-algo-engine proof scripts.
# The engine is a new service on 18120, NOT behind the edge-proxy, so these manage a private
# port-forward. Because two proofs KILL the engine pod (crash recovery), the port-forward is made
# re-establishable: call algo_pf to (re)start it after any pod restart.
set -uo pipefail

NS=${NS:-traderx}
# Per-process local port so two proofs can run CONCURRENTLY without their port-forwards colliding on
# one localhost port (they all forward to the same svc:18120 — the engine handles concurrent parents).
# ponytail: $$-derived, tiny chance two runs pick the same offset; override ALGO_LOCAL_PORT if so.
ALGO_LOCAL_PORT=${ALGO_LOCAL_PORT:-$(( 18120 + ($$ % 500) ))}
ALGO=${ALGO:-http://127.0.0.1:${ALGO_LOCAL_PORT}}
_ALGO_PF_PID=""

algo_pf() {                       # (re)start a SELF-HEALING port-forward to svc/execution-algo-engine
  # The engine deployment has no readiness probe, so a freshly rescheduled pod reports Ready before
  # Tomcat is listening; a port-forward started that early dies on the first connection-refused.
  # So respawn the forwarder on every retry until the API actually answers (up to ~40s).
  for _ in $(seq 1 40); do
    [ -n "$_ALGO_PF_PID" ] && kill "$_ALGO_PF_PID" 2>/dev/null
    kubectl port-forward "svc/execution-algo-engine" "${ALGO_LOCAL_PORT}:18120" -n "$NS" \
      >/tmp/yu08-pf.log 2>&1 &
    _ALGO_PF_PID=$!
    disown "$_ALGO_PF_PID" 2>/dev/null   # silence bash's async "Terminated" notice on respawn
    sleep 1
    curl -s -m2 -o /dev/null "$ALGO/algo/orders" && return 0
  done
  echo "   [warn] engine API not reachable on $ALGO after ~40s (see /tmp/yu08-pf.log)"
  return 1
}

# Kill the engine pod and block until a genuinely NEW pod is Ready (avoids the
# wait-matched-the-terminating-pod race).
algo_kill_and_wait() {
  local old
  old=$(kubectl get pod -l app=execution-algo-engine -n "$NS" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
  kubectl delete "pod/$old" -n "$NS" --wait=false >/dev/null 2>&1
  kubectl wait --for=delete "pod/$old" -n "$NS" --timeout=60s >/dev/null 2>&1
  kubectl wait --for=condition=ready pod -l app=execution-algo-engine -n "$NS" --timeout=90s >/dev/null 2>&1
}

algo_pf_stop() { [ -n "$_ALGO_PF_PID" ] && kill "$_ALGO_PF_PID" 2>/dev/null; _ALGO_PF_PID=""; }
trap algo_pf_stop EXIT

# POST a parent order from inline JSON on $1, echo the raw response.
algo_post() { curl -s -m10 -X POST "$ALGO/algo/orders" -H 'Content-Type: application/json' -d "$1"; }

# GET a parent order by id.
algo_get() { curl -s -m6 "$ALGO/algo/orders/$1"; }

# Parse one field out of a parent-order JSON on stdin via python3 (no jq dependency; parse
# failures degrade to empty rather than aborting the demo).
jfield() { python3 -c 'import sys,json
try: print(json.load(sys.stdin)['"$1"'])
except Exception: print("")'; }

# Compact bucket summary from a parent-order JSON on stdin:  status submitted/N filled/N [children]
bucket_summary() { python3 -c '
import sys,json
try:
    o=json.load(sys.stdin); b=o["buckets"]
    cids=[x["childOrderId"] for x in b if x.get("childOrderId")]
    print("%-9s submitted=%d/%d filled=%d/%d uniqueChildren=%d %s" % (
        o["status"], sum(1 for x in b if x["submitted"]), len(b),
        sum(1 for x in b if x["filled"]), len(b), len(set(cids)), cids))
except Exception as e:
    print("parse-error:", e)'; }
