#!/usr/bin/env bash
# run-fix-ladder.sh — drive fix-multi.mjs from the in-cluster `fixgen` pod and read the AUTHORITATIVE
# server-side throughput around it: each gateway's traderx_order_events_total{event="accepted"} and
# each member's applied/trades counters, before and after. Client-completed under-counts once egress
# ERs drop under flood; the gateway accepted counter does not, so it is the number of record.
#
# Per-gateway accepted deltas ARE the distribution check the ClientIP-affinity trap demands — read,
# not trusted. Usage: SESSIONS=60 TOTAL=3000 SECS=15 ./run-fix-ladder.sh "<label>"
set -euo pipefail
NS=traderx
LABEL="${1:-run}"
: "${SESSIONS:?}" "${SECS:?}"
# gateway pod name<->IP; session i -> GW_IPS[i%3] inside fix-multi.mjs, so the IP order here must
# match the pod order for the labels to line up.
GW_PODS=(); while IFS= read -r p; do GW_PODS+=("$p"); done < <(kubectl -n $NS get pods -l app=cluster-gateway -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' | sort)
GW_IPS=$(for p in "${GW_PODS[@]}"; do kubectl -n $NS get pod "$p" -o jsonpath='{.status.podIP}:18130,'; done); GW_IPS=${GW_IPS%,}

gw_acc() { kubectl -n $NS exec "$1" -- sh -c 'wget -qO- http://localhost:18110/metrics' 2>/dev/null | awk -F' ' '/event="accepted"/{print $2}'; }
mem() { kubectl -n $NS exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics' 2>/dev/null | awk '/^traderx_cluster_applied/{a=$2}/^traderx_cluster_trades/{t=$2}/^traderx_cluster_next_order_ref/{n=$2}/^traderx_book_open_orders/{d=$2}END{print a" "t" "n" "d}'; }

declare -a A0
for i in 0 1 2; do A0[$i]=$(gw_acc "${GW_PODS[$i]}"); done
M0=$(mem 0); T0=$(python3 -c 'import time;print(time.time())')

echo "=== ${LABEL}: SESSIONS=${SESSIONS} TOTAL=${TOTAL:-(rate ${RATE:-5})} SECS=${SECS} across ${GW_IPS} ==="
kubectl -n $NS exec fixgen -- env \
  GATEWAYS="${GW_IPS}" SESSIONS="${SESSIONS}" ${TOTAL:+TOTAL="${TOTAL}"} ${RATE:+RATE="${RATE}"} SECS="${SECS}" \
  TICKER="${TICKER:-JPM}" PRICE="${PRICE:-100.00}" QTY="${QTY:-10}" ACCT_BUY=42422 ACCT_SELL=22214 \
  node /tmp/fix-multi.mjs 2>&1

T1=$(python3 -c 'import time;print(time.time())'); M1=$(mem 0)
declare -a A1; for i in 0 1 2; do A1[$i]=$(gw_acc "${GW_PODS[$i]}"); done
python3 - "$T0" "$T1" "${A0[0]}" "${A0[1]}" "${A0[2]}" "${A1[0]}" "${A1[1]}" "${A1[2]}" "$M0" "$M1" <<'PY'
import sys
t0,t1=float(sys.argv[1]),float(sys.argv[2]); w=t1-t0
a0=[int(sys.argv[3+i]) for i in range(3)]; a1=[int(sys.argv[6+i]) for i in range(3)]
m0=sys.argv[9].split(); m1=sys.argv[10].split()
d=[a1[i]-a0[i] for i in range(3)]; tot=sum(d)
print(f"  SERVER-SIDE window {w:.1f}s")
for i in range(3):
    print(f"    gateway {i}: accepted +{d[i]:>7} ({d[i]/w:>7.0f}/s)")
print(f"    AGGREGATE : accepted +{tot:>7} ({tot/w:>7.0f}/s)   per-gw share {['%d%%'%(x*100//tot if tot else 0) for x in d]}")
print(f"    member-0  : applied +{int(m1[0])-int(m0[0])}  trades +{int(m1[1])-int(m0[1])}  nextRef {m1[2]}  open {m1[3]}")
PY
