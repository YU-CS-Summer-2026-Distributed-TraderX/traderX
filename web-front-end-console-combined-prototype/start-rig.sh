#!/usr/bin/env bash
# Local-only: reuse an already running kind rig. Never reset or deploy it.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
export KUBECONFIG="${KUBECONFIG:?Set KUBECONFIG to the local kind-only kubeconfig}"
CTX="$(kubectl config current-context)"
[[ "$CTX" == kind-* ]] || { echo 'Refusing a non-kind context.'; exit 1; }
export LOCAL_ONLY=1 HOST=127.0.0.1 PORT=4321 EDGE_PROXY=127.0.0.1:30080 POD_HTTP_VIA_EXEC=1
export STATIC_ROOT="$ROOT/web-front-end-console/dist/web-front-end-console/browser"
[[ -f "$STATIC_ROOT/index.html" ]] || { echo 'Build web-front-end-console first.'; exit 1; }
# Existing console server uses its own service credential; never expose it in the browser or logs.
AUTH_MASTER_SECRET="$(kubectl -n traderx get secret auth-secrets -o jsonpath='{.data.dev-token-master-secret}' | base64 --decode)"
export AUTH_MASTER_SECRET
PF_PID=''
API_PID=''
cleanup() { [[ -z "$PF_PID" ]] || kill "$PF_PID" 2>/dev/null || true; [[ -z "$API_PID" ]] || kill "$API_PID" 2>/dev/null || true; }
trap cleanup EXIT INT TERM
if ! nc -z 127.0.0.1 30080 >/dev/null 2>&1; then
  kubectl -n traderx port-forward --address=127.0.0.1 svc/edge-proxy 30080:8080 >/dev/null 2>&1 &
  PF_PID=$!
fi
for attempt in {1..30}; do
  nc -z 127.0.0.1 30080 >/dev/null 2>&1 && break
  sleep 1
done
nc -z 127.0.0.1 30080 >/dev/null 2>&1 || { echo 'The local edge proxy is unavailable.'; exit 1; }
node "$ROOT/web-front-end-console/server.mjs" &
API_PID=$!
while kill -0 "$API_PID" 2>/dev/null; do
  if ! nc -z 127.0.0.1 30080 >/dev/null 2>&1; then
    if [[ -z "$PF_PID" ]] || ! kill -0 "$PF_PID" 2>/dev/null; then
      kubectl -n traderx port-forward --address=127.0.0.1 svc/edge-proxy 30080:8080 >/dev/null 2>&1 &
      PF_PID=$!
    fi
  fi
  sleep 3
done
wait "$API_PID"
