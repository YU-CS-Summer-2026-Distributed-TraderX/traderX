#!/usr/bin/env bash
# fix-scaling-curve.sh — measure how FIX completed-lifecycle throughput scales with parallel
# sessions, and which stage becomes the ceiling. Each session runs in its OWN node process (so the
# single-threaded node event loop is never the bottleneck), with its own CompID + account + JWT.
# Captures order-matcher process CPU during each level so we can tell CPU-saturation from a
# structural ceiling.
#
# Prereqs: state up on kind; FIX_SESSION_ACCOUNTS maps BENCH01..BENCH07 to 7 accounts; node.
set -uo pipefail

EDGE="${EDGE:-http://localhost:8080}"
NS="${NS:-traderx}"
TP="${TP:-${EDGE}/trade-processor}"
MASTER="${AUTH_MASTER_SECRET:-dev-token-master-secret}"
SECS="${SECS:-25}"
FIX_LOCAL_PORT="${FIX_LOCAL_PORT:-18130}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="$(mktemp -d)"

# CompID -> account map (must match FIX_SESSION_ACCOUNTS)
COMPIDS=(BENCH01 BENCH02 BENCH03 BENCH04 BENCH05 BENCH06 BENCH07)
ACCTS=(22214 11413 42422 44044 52355 62654 10031)
LEVELS=(${LEVELS:-1 2 4 7})

mint() {
  curl -s -m8 -X POST "${TP}/auth/dev-token" -H "X-Auth-Master-Secret: ${MASTER}" \
    -H "Content-Type: application/json" \
    -d "{\"subject\":\"fix-scale-$1\",\"accounts\":[$1],\"admin\":false,\"ttlSeconds\":1800}" | tr -d '"'
}

om_cpu() {  # order-matcher process CPU usage (0..N cores) via prometheus
  local pod; pod=$(kubectl get pods -n "${NS}" -l app=order-matcher --no-headers | awk '{print $1}' | head -1)
  kubectl exec -n "${NS}" "${pod}" -- sh -c 'wget -qO- http://localhost:18110/actuator/prometheus 2>/dev/null' \
    | awk '/^process_cpu_usage /{p=$2} /^system_cpu_count /{c=$2} END{printf "%.2f", p*c}'
}

# port-forward the FIX acceptor once for the whole run
kubectl port-forward -n "${NS}" svc/order-matcher "${FIX_LOCAL_PORT}:18130" >/dev/null 2>&1 &
PF=$!
trap 'kill "${PF}" 2>/dev/null' EXIT
echo "per-session logs in ${out}"
sleep 3

printf "\n%-9s %-14s %-16s %-14s %s\n" "sessions" "total compl/s" "per-session/s" "om CPU(cores)" "scaling"
base=0
for n in "${LEVELS[@]}"; do
  pids=()
  ts=$(date +%s)
  for ((i=0; i<n; i++)); do
    jwt="$(mint "${ACCTS[$i]}")"
    RUN_ID="s${i}-${ts}" FIX_JWT="${jwt}" FIX_COMP_ID="${COMPIDS[$i]}" FIX_PORT="${FIX_LOCAL_PORT}" \
      SIDES=alternate QTY=1 PX=190 TICKERS=JPM,COF,DFS,IBM \
      node "${here}/fix-load.mjs" --secs "${SECS}" > "${out}/s${i}.log" 2>&1 &
    pids+=($!)
  done
  # sample order-matcher CPU mid-run
  sleep $(( SECS / 2 ))
  cpu="$(om_cpu)"
  for p in "${pids[@]}"; do wait "$p"; done
  total=0
  for ((i=0; i<n; i++)); do
    c=$(grep -o 'completed/s=[0-9]*' "${out}/s${i}.log" | tail -1 | cut -d= -f2)
    total=$(( total + ${c:-0} ))
  done
  per=$(( total / n ))
  [ "$n" = "1" ] && base=$total
  scale=$(python3 -c "print(f'{$total/$base:.2f}x vs 1 session')" 2>/dev/null)
  printf "%-9s %-14s %-16s %-14s %s\n" "$n" "$total" "$per" "$cpu" "$scale"
done
echo
echo "(om CPU is the order-matcher process's core-usage sampled mid-run; on kind the client shares"
echo " the same laptop cores, so absolute ceilings are muddied — read the SHAPE, not the absolutes.)"
