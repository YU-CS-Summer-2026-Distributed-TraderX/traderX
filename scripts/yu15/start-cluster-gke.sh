#!/usr/bin/env bash
# Inspect rendered intended artifacts offline by default. An explicit --apply is
# a separate operator action; successful admission grants no deployment permission.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PYTHON="${IMAGE_ADMISSION_PYTHON:-python3}"
apply=0
args=()
ctx=''; ns=''
seen=''
while [[ $# -gt 0 ]]; do
  [[ " $seen " != *" $1 "* ]] || { echo "[fail] duplicate option $1" >&2; exit 1; }
  seen+=" $1"
  case "$1" in
    --apply) apply=1; shift ;;
    --context|--namespace)
      [[ $# -ge 2 ]] || { echo "[fail] $1 requires a value" >&2; exit 1; }
      if [[ "$1" == '--context' ]]; then ctx="$2"; else ns="$2"; fi
      args+=("$1" "$2"); shift 2 ;;
    --project|--location|--cluster|--state|--manifest-pack|--expected|--artifacts)
      [[ $# -ge 2 ]] || { echo "[fail] $1 requires a value" >&2; exit 1; }
      args+=("$1" "$2"); shift 2 ;;
    *) echo "[fail] unsupported option $1; use separate explicit option/value arguments" >&2; exit 1 ;;
  esac
done
# No default context, ambient kubeconfig lookup, project inference or highest-state guess.
[[ -n "$ctx" && -n "$ns" ]] || { echo '[fail] explicit --context and --namespace required; see image-admission component contract' >&2; exit 1; }
work="$(mktemp -d "${TMPDIR:-/tmp}/ri20-admission.XXXXXXXX")"
trap 'rm -rf -- "$work"' EXIT
"$PYTHON" "${ROOT}/scripts/yu15/image-admission.py" render --root "$ROOT" --output-dir "$work" "${args[@]}"
cat "$work/admission.json"
if [[ "$apply" != 1 ]]; then
  echo '[inspect] admission complete; no cluster contacted or changed. --apply is a separate authorized operator action.'
  exit 0
fi
# Apply exactly the validated snapshots, rather than rerendering a moving source.
# Preserve schema-before-first-database-boot ordering from the legacy entrypoint.
kubectl --context "$ctx" get namespace "$ns" >/dev/null 2>&1 || kubectl --context "$ctx" create namespace "$ns"
kubectl --context "$ctx" -n "$ns" apply -f "$work/database-init.yaml"
kubectl --context "$ctx" -n "$ns" apply -f "$work/rendered.yaml"
echo '[wait] member rollout'
kubectl --context "$ctx" -n "$ns" rollout status statefulset/order-matcher-cluster --timeout=600s
for deployment in nats eod-price-db trade-processor cluster-gateway risk-extract price-publisher position-service; do
  kubectl --context "$ctx" -n "$ns" rollout status "deployment/$deployment" --timeout=600s
done
kubectl --context "$ctx" -n "$ns" get pods -o wide
echo "[apply] admitted snapshots submitted and rollout waits completed on $ctx/$ns; live artifact/retained-state qualification remains separate"
