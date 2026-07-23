#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NAMESPACE="${NAMESPACE:-traderx}"
PODS="${PODS:-3}"
SESSIONS_PER_POD="${SESSIONS_PER_POD:-120}"
TOTAL="${TOTAL:-36000}"
SECS="${SECS:-30}"
WARMUP_MS="${WARMUP_MS:-5000}"
START_DELAY_SECS="${START_DELAY_SECS:-45}"
RUN_ID="${RUN_ID:-$(( $(date +%s) % 65535 + 1 ))}"
JOB="${JOB:-binary-load}"
RESULTS_DIR="${RESULTS_DIR:-${ROOT}/scripts/bench/results}"

context="$(kubectl config current-context)"
case "${context}" in
  gke_*) ;;
  *)
    echo "[fail] GKE context required; current context is ${context}" >&2
    exit 1
    ;;
esac

if (( PODS < 1 || PODS > 255 )); then
  echo "[fail] PODS must be 1..255 (uint64 key partition)" >&2
  exit 1
fi
if (( TOTAL % PODS != 0 )); then
  echo "[fail] TOTAL=${TOTAL} must divide evenly across PODS=${PODS}" >&2
  exit 1
fi
total_per_pod=$(( TOTAL / PODS ))

gateway_lines="$(kubectl -n "${NAMESPACE}" get pods -l app=cluster-gateway \
  --field-selector=status.phase=Running \
  -o jsonpath='{range .items[*]}{.status.podIP}{"\n"}{end}' | sort)"
gateway_count="$(printf '%s\n' "${gateway_lines}" | awk 'NF { n++ } END { print n+0 }')"
if [[ "${gateway_count}" != "3" ]]; then
  echo "[fail] exactly 3 Running gateways required; found ${gateway_count}" >&2
  exit 1
fi

gateway_csv=""
while IFS= read -r ip; do
  [[ -n "${ip}" ]] || continue
  gateway_csv="${gateway_csv}${gateway_csv:+,}${ip}:18140"
done <<< "${gateway_lines}"

member_ip=""
for member in order-matcher-cluster-0 order-matcher-cluster-1 order-matcher-cluster-2; do
  role="$(kubectl -n "${NAMESPACE}" exec "${member}" -- curl -s localhost:8080/metrics \
    | awk '/^traderx_cluster_role/ { print $2 }')"
  if [[ "${role}" == "1" ]]; then
    member_ip="$(kubectl -n "${NAMESPACE}" get pod "${member}" -o jsonpath='{.status.podIP}')"
    leader_pod="${member}"
    break
  fi
done
if [[ -z "${member_ip}" ]]; then
  echo "[fail] no member reports traderx_cluster_role=1" >&2
  exit 1
fi
image_ids="$(kubectl -n "${NAMESPACE}" get pods -l app=cluster-gateway \
  -o jsonpath='{range .items[*]}{.status.containerStatuses[0].imageID}{"\n"}{end}' | sort -u)"
image_count="$(printf '%s\n' "${image_ids}" | awk 'NF { n++ } END { print n+0 }')"
if [[ "${image_count}" != "1" ]]; then
  echo "[fail] gateways do not share one immutable image digest:" >&2
  printf '%s\n' "${image_ids}" >&2
  exit 1
fi

start_at_ms=$(( ( $(date +%s) + START_DELAY_SECS ) * 1000 ))
mkdir -p "${RESULTS_DIR}"
result="${RESULTS_DIR}/$(date -u +%Y%m%dT%H%M%SZ)-binary-per-order-${PODS}pods-${TOTAL}scheduled-gke.log"

kubectl -n "${NAMESPACE}" create configmap binary-load-scripts \
  --from-file=bin-multi.mjs="${ROOT}/scripts/bench/bin-multi.mjs" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "${NAMESPACE}" delete job "${JOB}" --ignore-not-found=true --wait=true

kubectl apply -f - <<YAML
apiVersion: batch/v1
kind: Job
metadata:
  name: ${JOB}
  namespace: ${NAMESPACE}
  labels:
    app: binary-load
spec:
  completionMode: Indexed
  completions: ${PODS}
  parallelism: ${PODS}
  backoffLimit: 0
  activeDeadlineSeconds: $(( SECS + START_DELAY_SECS + 300 ))
  template:
    metadata:
      labels:
        app: binary-load
    spec:
      restartPolicy: Never
      nodeSelector:
        workload: batch-private
      containers:
        - name: generator
          image: node:22-bookworm-slim
          imagePullPolicy: IfNotPresent
          command: ["node", "/scripts/bin-multi.mjs"]
          env:
            - name: GATEWAYS
              value: "${gateway_csv}"
            - name: HTTP_PORT
              value: "18110"
            - name: MEMBER
              value: "${member_ip}:8080"
            - name: SESSIONS
              value: "${SESSIONS_PER_POD}"
            - name: TOTAL
              value: "${total_per_pod}"
            - name: SECS
              value: "${SECS}"
            - name: WARMUP_MS
              value: "${WARMUP_MS}"
            - name: START_AT_MS
              value: "${start_at_ms}"
            - name: RUN_ID
              value: "${RUN_ID}"
            - name: POD_INDEX
              valueFrom:
                fieldRef:
                  fieldPath: metadata.annotations['batch.kubernetes.io/job-completion-index']
          resources:
            requests:
              cpu: "500m"
              memory: "256Mi"
            limits:
              cpu: "1"
              memory: "768Mi"
          volumeMounts:
            - name: scripts
              mountPath: /scripts
              readOnly: true
      volumes:
        - name: scripts
          configMap:
            name: binary-load-scripts
YAML

echo "[context] branch=$(git -C "${ROOT}" branch --show-current) head=$(git -C "${ROOT}" rev-parse HEAD)"
echo "[context] kubectl=${context} gateways=${gateway_csv} image=${image_ids} leader=${leader_pod}"
echo "[load] binary per-order; pods=${PODS} sessions/pod=${SESSIONS_PER_POD} scheduled=${TOTAL}/s run=${RUN_ID}"
echo "[load] synchronized epoch=${start_at_ms} duration=${SECS}s"

kubectl -n "${NAMESPACE}" wait --for=condition=complete "job/${JOB}" \
  --timeout="$(( SECS + START_DELAY_SECS + 240 ))s"
kubectl -n "${NAMESPACE}" logs -l "job-name=${JOB}" --all-containers=true \
  --prefix=true --max-log-requests="${PODS}" --tail=-1 | tee "${result}"

echo "[ok] result=${result}"
if [[ "${KEEP_JOB:-0}" != "1" ]]; then
  kubectl -n "${NAMESPACE}" delete job "${JOB}" --wait=true
fi
