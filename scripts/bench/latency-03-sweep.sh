#!/usr/bin/env bash
# LATENCY-03 — one frontier point: offered load x in-flight window depth.
#   TOTAL=<rate> SESS=<sessions per pod> bash sweep.sh <label>
# The in-flight window is the CONNECTION COUNT (PODS*SESS): BinGen's acceptor is synchronous, so a
# connection carries ~1 order in flight. GATEWAY_MAX_INFLIGHT is the gateway-side semaphore on top.
# Emits one TSV line so repeats are directly comparable; full scrape kept alongside.
set -uo pipefail
ROOT=/Users/yaakov/dev/lmax/traderX-YU13-limit-order-book
NS=traderx
LABEL="${1:-run}"
TOTAL="${TOTAL:-75000}"
SESS="${SESS:-67}"
PODS="${PODS:-3}"
SECS="${SECS:-30}"
OUT=/private/tmp/claude-501/-Users-yaakov-dev-lmax--claude-worktrees-ecstatic-rosalind-2750db/689a07e7-af00-4337-a677-4e8c42ec68cb/scratchpad

leader=""
for i in 0 1 2; do
  r=$(kubectl exec -n $NS order-matcher-cluster-$i -c cluster-node -- curl -s -m5 localhost:8080/metrics 2>/dev/null | awk '/^traderx_cluster_role/{print $2}')
  [ "$r" = "1" ] && leader=order-matcher-cluster-$i
done
[ -z "$leader" ] && { echo "NO LEADER"; exit 1; }
gws=$(kubectl get pods -n $NS -o name | grep cluster-gateway | sed 's|pod/||')

kubectl exec -n $NS $leader -c cluster-node -- curl -s -m5 "localhost:8080/latency?reset=1" >/dev/null 2>&1
for g in $gws; do kubectl exec -n $NS $g -- curl -s -m5 "localhost:18110/latency?reset=1" >/dev/null 2>&1; done

cpu0=$(kubectl exec -n $NS $leader -c cluster-node -- sh -c 'awk "{s+=\$14+\$15} END{print s}" /proc/[0-9]*/stat' 2>/dev/null)
t0=$(date +%s)
GEN_NODESELECTOR=cloud.google.com/gke-nodepool=c2d-load-pool PODS=$PODS SESSIONS_PER_POD=$SESS \
  TOTAL=$TOTAL SECS=$SECS bash $ROOT/scripts/bench/run-bin-blast-gke.sh > "$OUT/$LABEL.load" 2>&1
cpu1=$(kubectl exec -n $NS $leader -c cluster-node -- sh -c 'awk "{s+=\$14+\$15} END{print s}" /proc/[0-9]*/stat' 2>/dev/null)
t1=$(date +%s)

lat=$(kubectl exec -n $NS $leader -c cluster-node -- curl -s -m8 localhost:8080/latency 2>/dev/null)
echo "$lat" > "$OUT/$LABEL.leader"
: > "$OUT/$LABEL.gw"
for g in $gws; do kubectl exec -n $NS $g -- curl -s -m8 localhost:18110/latency 2>/dev/null >> "$OUT/$LABEL.gw"; done

# --- client RTT: median across generator pods (each pod prints its own) ---
med(){ sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}'; }
rttline(){ grep -o 'ack RTT p50 [0-9]*us  p99 [0-9]*us  max [0-9]*us' "$OUT/$LABEL.load" \
  | sed -E "s/.*p50 ([0-9]+)us  p99 ([0-9]+)us  max ([0-9]+)us/\\$1/"; }
rtt50=$(rttline 1 | med)
rtt99=$(rttline 2 | med)
rttmax=$(rttline 3 | sort -n | tail -1)
applied=$(grep -o 'nextOrderRef delta:.*=  *[0-9]*/s' "$OUT/$LABEL.load" | grep -o '[0-9]*/s' | tr -d '/s')
offered=$(grep -o 'offered (generator):.*=  *[0-9]*/s' "$OUT/$LABEL.load" | grep -o '[0-9]*/s' | tr -d '/s')
maxinf=$(grep -o 'max in-flight/conn:  *[0-9]*' "$OUT/$LABEL.load" | grep -o '[0-9]*$' | sort -n | tail -1)
stalls=$(grep -o 'write-stalls=[0-9]*' "$OUT/$LABEL.load" | grep -o '[0-9]*$' | tail -1)

g(){ echo "$lat" | awk -F'} ' "/segment=\"$1\",pct=\"$2\"/{print \$2}" | head -1; }
gw(){ awk -F'} ' "/segment=\"$1\",pct=\"$2\"/{print \$2}" "$OUT/$LABEL.gw" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}'; }
cpu=$(echo "scale=2; ($cpu1 - $cpu0) / 100 / ($t1 - $t0)" | bc)

printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
  "$LABEL" "$TOTAL" "$((PODS*SESS))" "$offered" "$applied" \
  "$rtt50" "$rtt99" "$rttmax" "$(g commit mean)" "$(g commit p99)" \
  "$(gw queue p50)" "$(gw queue p99)" "$(gw cluster p50)" "$maxinf" "$cpu" \
  | tee -a "$OUT/frontier.tsv"
