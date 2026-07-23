#!/usr/bin/env bash
# run-bin-blast-gke.sh — Phase 1 ladder runner for BinGen against a live 3-member cluster on GKE.
#
# Launches an Indexed Job of JDK pods each running BinGen (scripts/bench/BinGen.java via ConfigMap; no
# image build — `java BinGen.java` source-launches inside a temurin JDK container), synchronised on a
# shared START epoch, and reads the PER-HOP FUNNEL over exactly that window:
#
#   offered/s (generator)  ->  per-gateway decoded/s -> offer-success/s (gateway /metrics, if present)
#                          ->  member nextOrderRef delta/s   <-- GROUND TRUTH (never the gateway
#                                                                'accepted' counter; it is booked-only
#                                                                and has lied — collapsed to 330/s when
#                                                                true was 1,750/s)
#
# The generator side (offered, acks, write-stalls = client backpressure, max in-flight) comes from the
# pod logs; the authoritative apply rate and gateway counters are sampled externally off the leader and
# the gateways, so BinGen itself stays pure-wire.
#
# Ladder: raise TOTAL (aggregate offered/s) step by step until member nextOrderRef delta/s STOPS
# tracking offered/s AND backpressure appears (write-stalls climb, in-flight balloons, or gateway
# offer-success < decoded). That divergence + backpressure is a REAL limit, not a harness one. Once
# near the knee, MODE=blast finds the absolute ceiling.
#
# Load shape on every row: fresh keys (RUN_ID differs per invocation), two-account crossing
# (even conns BUY on ACCT_BUY, odd SELL on ACCT_SELL — a single self-crossing account books 0 post-STP).
#
#   PODS=4 SESSIONS_PER_POD=250 TOTAL=40000 SECS=30 bash scripts/bench/run-bin-blast-gke.sh
#   MODE=blast PODS=4 SESSIONS_PER_POD=250 SECS=30 bash scripts/bench/run-bin-blast-gke.sh   # find ceiling
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NAMESPACE="${NAMESPACE:-traderx}"
PODS="${PODS:-4}"
SESSIONS_PER_POD="${SESSIONS_PER_POD:-250}"
MODE="${MODE:-paced}"                 # paced = ladder step at TOTAL/s; blast = find the ceiling
TOTAL="${TOTAL:-40000}"               # aggregate offered/s (paced); ignored in blast
SECS="${SECS:-30}"
BATCH="${BATCH:-64}"
PRICE="${PRICE:-150}"    # MUST equal the seeded price (seed-accounts-job seeds 150) so the two-account
                         # flow is marketable and crosses; a stale/off price rejects at the risk gate
QTY="${QTY:-10}"
# Numeric securityId the binary wire carries. Default 1 = the reject-path probe (no such security =>
# every order rejects at UNKNOWN_SECURITY, measures pure ingress). For a BOOKING run, /resolve the
# ticker first and pass its real id (e.g. JPM=2) so orders actually cross and the trades counter moves.
SECURITY="${SECURITY:-1}"
# Two-account crossing pair. Each booked pair pushes ACCT_BUY long / ACCT_SELL short; at the default
# 1M position cap that walls after ~1M fills/account, so rotate to a FRESH seeded pair per booking rung
# (seeded: 42422 22214 44044 52355 10031 62654 11413). Ignored on the reject path.
ACCT_BUY="${ACCT_BUY:-42422}"
ACCT_SELL="${ACCT_SELL:-22214}"
WARMUP_MS="${WARMUP_MS:-5000}"
START_DELAY_SECS="${START_DELAY_SECS:-45}"
RUN_ID="${RUN_ID:-$(( $(date +%s) % 65535 + 1 ))}"
JOB="${JOB:-binary-load}"
IMAGE="${IMAGE:-eclipse-temurin:21}"  # JDK (has javac for source-launch), not a JRE image
# Heap + trimmed stacks so a deep in-flight window fits: 250 conns/pod x 2 threads ~= 500 threads;
# -Xss256k keeps that ~128 MiB off-heap, -Xmx1200m leaves headroom in the 2Gi limit for the arrays.
JAVA_OPTS="${JAVA_OPTS:--Xmx1200m -Xss256k}"
# n2 load-node placement — set to your n2 pool's node label so the generator never lands on a member.
GEN_NODESELECTOR="${GEN_NODESELECTOR:-cloud.google.com/gke-nodepool=n2-load-pool}"
RESULTS_DIR="${RESULTS_DIR:-${ROOT}/scripts/bench/results}"

context="$(kubectl config current-context)"
case "${context}" in gke_*) ;; *) echo "[fail] GKE context required; current is ${context}" >&2; exit 1;; esac

if (( PODS < 1 || PODS > 255 )); then echo "[fail] PODS must be 1..255 (uint64 key partition)" >&2; exit 1; fi
if [[ "${MODE}" == "paced" ]] && (( TOTAL % PODS != 0 )); then
  echo "[fail] TOTAL=${TOTAL} must divide evenly across PODS=${PODS}" >&2; exit 1
fi
total_per_pod=$(( TOTAL / PODS ))

# --- resolve the three gateway IPs (exact per-gateway placement by construction) ---
gateway_lines="$(kubectl -n "${NAMESPACE}" get pods -l app=cluster-gateway \
  --field-selector=status.phase=Running \
  -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.podIP}{"\n"}{end}' | sort)"
gateway_count="$(printf '%s\n' "${gateway_lines}" | awk 'NF { n++ } END { print n+0 }')"
if [[ "${gateway_count}" != "3" ]]; then
  echo "[fail] exactly 3 Running gateways required; found ${gateway_count}" >&2
  printf '%s\n' "${gateway_lines}" >&2; exit 1
fi
gateway_csv=""; gateway_pods=()
while IFS=' ' read -r name ip; do
  [[ -n "${ip}" ]] || continue
  gateway_csv="${gateway_csv}${gateway_csv:+,}${ip}:18140"
  gateway_pods+=("${name}")
done <<< "${gateway_lines}"

# one immutable gateway digest — a mixed rollout would blend two builds into one number
image_ids="$(kubectl -n "${NAMESPACE}" get pods -l app=cluster-gateway \
  -o jsonpath='{range .items[*]}{.status.containerStatuses[0].imageID}{"\n"}{end}' | sort -u)"
if [[ "$(printf '%s\n' "${image_ids}" | awk 'NF{n++} END{print n+0}')" != "1" ]]; then
  echo "[fail] gateways do not share one immutable image digest:" >&2; printf '%s\n' "${image_ids}" >&2; exit 1
fi

# --- resolve the leader (role==1) fresh, every run (member 0 is NOT always leader) ---
leader_pod=""
for member in order-matcher-cluster-0 order-matcher-cluster-1 order-matcher-cluster-2; do
  role="$(kubectl -n "${NAMESPACE}" exec "${member}" -- curl -s localhost:8080/metrics 2>/dev/null \
    | awk '/^traderx_cluster_role/ { print $2 }')"
  if [[ "${role}" == "1" ]]; then leader_pod="${member}"; break; fi
done
if [[ -z "${leader_pod}" ]]; then echo "[fail] no member reports traderx_cluster_role=1" >&2; exit 1; fi

# Always emit a number. A bare `kubectl exec curl` can return empty under heavy load (that empty read
# is what killed a prior run's `set -e` and orphaned its Job); retry a few times, then default 0.
leader_metric() { # $1 = metric name; retries, defaults 0
  local v="" i
  for i in 1 2 3 4 5; do
    v="$(kubectl -n "${NAMESPACE}" exec "${leader_pod}" -- curl -s --max-time 4 localhost:8080/metrics 2>/dev/null \
      | awk -v m="^$1" '$0 ~ m && $1 !~ /^#/ { print $2 }')"
    [[ -n "${v}" ]] && break
  done
  echo "${v:-0}"
}
next_ref()    { leader_metric traderx_cluster_next_order_ref; }
next_trades() { leader_metric traderx_cluster_trades; }   # engine-authoritative MATCHED-trade count
gw_stage() { # $1=pod $2=family $3=stage  — always prints a number
  kubectl -n "${NAMESPACE}" exec "$1" -- curl -s --max-time 4 localhost:18110/metrics 2>/dev/null \
    | awk -v re="$2{stage=\"$3\"}" '$0 ~ re { v=$2 } END { print (v==""?0:v) }'
}

start_at_ms=$(( ( $(date +%s) + START_DELAY_SECS ) * 1000 ))
mkdir -p "${RESULTS_DIR}"
tag="${MODE}-${PODS}pods-$([[ ${MODE} == paced ]] && echo "${TOTAL}sched" || echo blast)"
result="${RESULTS_DIR}/$(date -u +%Y%m%dT%H%M%SZ)-binary-${tag}-gke.log"

kubectl -n "${NAMESPACE}" create configmap binary-load-bingen \
  --from-file=BinGen.java="${ROOT}/scripts/bench/BinGen.java" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "${NAMESPACE}" delete job "${JOB}" --ignore-not-found=true --wait=true

sel_key="${GEN_NODESELECTOR%%=*}"; sel_val="${GEN_NODESELECTOR#*=}"
kubectl apply -f - <<YAML
apiVersion: batch/v1
kind: Job
metadata: { name: ${JOB}, namespace: ${NAMESPACE}, labels: { app: binary-load } }
spec:
  completionMode: Indexed
  completions: ${PODS}
  parallelism: ${PODS}
  backoffLimit: 0
  activeDeadlineSeconds: $(( SECS + START_DELAY_SECS + 300 ))
  template:
    metadata: { labels: { app: binary-load } }
    spec:
      restartPolicy: Never
      nodeSelector: { ${sel_key}: "${sel_val}" }
      containers:
        - name: generator
          image: ${IMAGE}
          imagePullPolicy: IfNotPresent
          command: ["java", "/scripts/BinGen.java"]
          env:
            - { name: GATEWAYS,   value: "${gateway_csv}" }
            - { name: SESSIONS,   value: "${SESSIONS_PER_POD}" }
            - { name: MODE,       value: "${MODE}" }
            - { name: TOTAL,      value: "$([[ ${MODE} == paced ]] && echo "${total_per_pod}" || echo 0)" }
            - { name: BATCH,      value: "${BATCH}" }
            - { name: SECS,       value: "${SECS}" }
            - { name: PRICE,      value: "${PRICE}" }
            - { name: QTY,        value: "${QTY}" }
            - { name: SECURITY,   value: "${SECURITY}" }
            - { name: ACCT_BUY,   value: "${ACCT_BUY}" }
            - { name: ACCT_SELL,  value: "${ACCT_SELL}" }
            - { name: WARMUP_MS,  value: "${WARMUP_MS}" }
            - { name: START_AT_MS, value: "${start_at_ms}" }
            - { name: RUN_ID,     value: "${RUN_ID}" }
            - { name: JAVA_TOOL_OPTIONS, value: "${JAVA_OPTS}" }
            - name: POD_INDEX
              valueFrom: { fieldRef: { fieldPath: "metadata.annotations['batch.kubernetes.io/job-completion-index']" } }
          resources:
            requests: { cpu: "2", memory: "1Gi" }
            limits:   { cpu: "4", memory: "2Gi" }
          volumeMounts: [ { name: scripts, mountPath: /scripts, readOnly: true } ]
      volumes: [ { name: scripts, configMap: { name: binary-load-bingen } } ]
YAML

echo "[context] branch=$(git -C "${ROOT}" branch --show-current) head=$(git -C "${ROOT}" rev-parse --short HEAD)"
echo "[context] kubectl=${context}"
echo "[context] gateways=${gateway_csv}"
echo "[context] gateway image=${image_ids}"
echo "[context] leader=${leader_pod}"
echo "[load] mode=${MODE} pods=${PODS} sessions/pod=${SESSIONS_PER_POD} $([[ ${MODE} == paced ]] && echo "target=${TOTAL}/s") run=${RUN_ID}"
echo "[load] synchronized epoch=${start_at_ms} (in ${START_DELAY_SECS}s) duration=${SECS}s"

# --- sample the authoritative funnel over EXACTLY the synchronized window ---
sleep_until() { local target_ms=$1; local now_ms; now_ms=$(( $(date +%s) * 1000 )); local d=$(( target_ms - now_ms )); (( d > 0 )) && sleep "$(awk "BEGIN{print $d/1000}")"; }
sleep_until "${start_at_ms}"
ref0="$(next_ref)"; trades0="$(next_trades)"; win_t0="$(date +%s)"
dec0=0; ofs0=0
for gp in "${gateway_pods[@]}"; do
  dec0=$(( dec0 + $(gw_stage "${gp}" traderx_binary_frames_total decoded || echo 0) ))
  ofs0=$(( ofs0 + $(gw_stage "${gp}" traderx_gateway_pipeline_total offer_success || echo 0) ))
done
sleep "${SECS}"
ref1="$(next_ref)"; trades1="$(next_trades)"; win="$(( $(date +%s) - win_t0 ))"
dec1=0; ofs1=0
for gp in "${gateway_pods[@]}"; do
  dec1=$(( dec1 + $(gw_stage "${gp}" traderx_binary_frames_total decoded || echo 0) ))
  ofs1=$(( ofs1 + $(gw_stage "${gp}" traderx_gateway_pipeline_total offer_success || echo 0) ))
done
(( win > 0 )) || win=${SECS}

kubectl -n "${NAMESPACE}" wait --for=condition=complete "job/${JOB}" \
  --timeout="$(( SECS + START_DELAY_SECS + 240 ))s" || true
kubectl -n "${NAMESPACE}" logs -l "job-name=${JOB}" --all-containers=true \
  --prefix=true --max-log-requests="${PODS}" --tail=-1 | tee "${result}"

# --- generator-side totals from the pod logs ---
sum_field() { grep -hoE "$1"'[0-9]+' "${result}" | grep -oE '[0-9]+' | awk '{s+=$1} END{print s+0}'; }
offered="$(sum_field 'offered \(in window\): +')"
acks="$(sum_field 'acks \(in window\): +')"
stalls="$(sum_field 'write stalls \(>1ms\): +')"
refD=$(( ref1 - ref0 )); decD=$(( dec1 - dec0 )); ofsD=$(( ofs1 - ofs0 )); tradesD=$(( trades1 - trades0 ))

echo
echo "=== PER-HOP FUNNEL (over ${win}s synchronized window; fresh keys, two-account crossing) ==="
printf 'offered (generator):       %10d  = %8d/s\n' "${offered}" "$(( offered / SECS ))"
printf 'acks read (generator):     %10d  = %8d/s\n' "${acks}"    "$(( acks / SECS ))"
if (( decD > 0 || ofsD > 0 )); then
  printf 'gateway decoded:           %10d  = %8d/s\n' "${decD}" "$(( decD / win ))"
  printf 'gateway offer-success:     %10d  = %8d/s\n' "${ofsD}" "$(( ofsD / win ))"
else
  echo    'gateway decoded/offer:      n/a  (deployed gateway lacks diagnostic counters; deploy :yu13-binary-ceiling-r1 for the middle hops)'
fi
printf 'member nextOrderRef delta: %10d  = %8d/s   <-- SUBMIT/s (orders sequenced+applied)\n' "${refD}" "$(( refD / win ))"
printf 'member trades delta:       %10d  = %8d/s   <-- MATCH/s (booked trades; 0 on the reject path)\n' "${tradesD}" "$(( tradesD / win ))"
printf 'client backpressure:       write-stalls=%d  (climbing across the ladder = a real limit)\n' "${stalls}"
echo "[ok] result=${result}"

if [[ "${KEEP_JOB:-0}" != "1" ]]; then kubectl -n "${NAMESPACE}" delete job "${JOB}" --wait=true; fi
