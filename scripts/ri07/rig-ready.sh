#!/usr/bin/env bash
# RI-07 full-rig readiness for the local kind rig: trading, console, algo engine and observability.
# "Ready" here means each part WORKS, not that its pod is Running:
#   workloads   every Deployment/StatefulSet in the namespace has all desired replicas ready
#   consensus   every member answers /health, started, exactly one LEADER, applied > 0 on all, and
#               EVERY member's applied advances across the probe's own orders (an idle rig may
#               legitimately sit still, so a timer-based "is it moving" test would lie both ways)
#   gateway     /ready 200 (it holds a cluster session)
#   read model  trade-processor healthy AND the isolated startup probe books 4 legs end to end
#               (scripts/ri07/startup-probe.sh: own instrument and accounts, nobody else's orders)
#   frontend    account directory and reference data answer through the edge proxy; the /nats-ws
#               websocket listener behind it answers (live prices and live blotter depend on it)
#   console     (if CONSOLE_URL is set) serves the app and reaches the gateway through its proxy
#   algo        execution-algo-engine readiness endpoint UP
#   observ.     collector healthy; Prometheus reports every cluster member target up; Loki and
#               Grafana ready; one deliberately collar-rejected probe-account order (rejects are
#               always traced) whose trace id from the gateway log is then FETCHED from Tempo
#   prices      price-publisher returns quotes (synthetic unless the tape replay was fetched)
#
# Usage: MATCHER_URL=http://localhost:28110 CTX=kind-traderx-ri07-demo [CONSOLE_URL=http://localhost:28090] \
#          bash scripts/ri07/rig-ready.sh
# Exit 0 = every check passed; 1 = at least one failed (each failure is named).
set -uo pipefail

CTX="${CTX:?set CTX, e.g. kind-traderx-ri07-demo}"
NS="${NS:-traderx}"
MATCHER_URL="${MATCHER_URL:?set MATCHER_URL to a port-forward of svc/order-matcher 18110}"
CONSOLE_URL="${CONSOLE_URL:-}"
K=(kubectl --context "${CTX}" -n "${NS}")
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fails=0
ok() { printf '[ok]   %-11s %s\n' "$1" "$2"; }
bad() { printf '[FAIL] %-11s %s\n' "$1" "$2"; fails=$((fails + 1)); }
edge() { "${K[@]}" exec deploy/edge-proxy -- curl -s -m8 "$@" 2>/dev/null; }
code() { edge -o /dev/null -w '%{http_code}' "$1"; }
member_health() { "${K[@]}" exec "$1" -- sh -c 'wget -qO- http://localhost:8080/health' 2>/dev/null; }

kubectl config get-contexts -o name 2>/dev/null | grep -qx "${CTX}" || { echo "[FAIL] context ${CTX} not in KUBECONFIG"; exit 1; }

# workloads
# The parser prints OK or the not-ready list; anything else (no kubectl answer, a parse error) is a
# failure. An earlier draft printed [ok] on a Python SyntaxError because "no output" meant "none".
wl="$("${K[@]}" get deploy,statefulset -o json 2>/dev/null | python3 -c '
import json,sys
items=json.load(sys.stdin)["items"]
bad=["%s/%s %s/%s"%(i["kind"],i["metadata"]["name"],i["status"].get("readyReplicas",0) or 0,i["spec"].get("replicas",1))
     for i in items if i["spec"].get("replicas",1) and (i["status"].get("readyReplicas",0) or 0) < i["spec"].get("replicas",1)]
print("OK %d workloads"%len(items) if items and not bad else "NOT " + " ".join(bad) if bad else "NONE")' 2>&1)"
[[ "${wl}" == OK* ]] && ok workloads "${wl#OK } all at desired ready replicas" || bad workloads "${wl}"

MEMBERS=($("${K[@]}" get pods -l app=order-matcher-cluster -o name | sed 's|pod/||' | sort))
sample() { for m in "${MEMBERS[@]}"; do member_health "${m}"; echo; done; }
s1="$(sample)"

c="$(curl -s -m8 -o /dev/null -w '%{http_code}' "${MATCHER_URL}/ready")"
[[ "${c}" == 200 ]] && ok gateway "/ready 200 at ${MATCHER_URL}" || bad gateway "/ready answered ${c:-none} at ${MATCHER_URL}"

c="$(code http://trade-processor:18091/actuator/health)"
[[ "${c}" == 200 ]] && ok read-model "trade-processor actuator 200" || bad read-model "trade-processor actuator ${c:-none}"
PROBE_TICKER_USED=""
if probe="$(MATCHER_URL="${MATCHER_URL}" CTX="${CTX}" NS="${NS}" bash "${HERE}/startup-probe.sh" 2>&1)"; then
  ok probe "$(printf '%s\n' "${probe}" | tail -1)"
  PROBE_TICKER_USED="$(printf '%s\n' "${probe}" | tail -1 | python3 -c 'import json,sys; print(json.load(sys.stdin)["ticker"])' 2>/dev/null)"
else
  bad probe "$(printf '%s\n' "${probe}" | grep -E 'FAIL|refused' | tail -1)"
fi

# One sequenced, deliberately REJECTED order on the probe's own instrument and account: rejects are
# always traced, so its trace id (from the gateway's ORDER-REJECT line) must be fetchable from Tempo.
TRACE_ID=""
if [[ -n "${PROBE_TICKER_USED}" ]]; then
  cid="ri07-ready-$(date +%s)"
  out="$(curl -s -m20 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${PROBE_A:-880001},\"ticker\":\"${PROBE_TICKER_USED}\",\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":5000,\"clientOrderId\":\"${cid}\"}")"
  for _ in 1 2 3 4 5; do
    TRACE_ID="$("${K[@]}" logs deploy/cluster-gateway --since=2m 2>/dev/null | grep "clordid=${cid} " | sed -n 's/.*trace=\([0-9a-f]\{32\}\).*/\1/p' | tail -1)"
    [[ -n "${TRACE_ID}" ]] && break; sleep 2
  done
fi
s2="$(sample)"
verdict="$(python3 -c '
import json,sys
a=[json.loads(l) for l in sys.argv[1].splitlines() if l.strip()]
b=[json.loads(l) for l in sys.argv[2].splitlines() if l.strip()]
n=int(sys.argv[3])
if len(a)!=n or len(b)!=n: print("FAIL only %d/%d members answered /health"%(len(b),n)); sys.exit()
if not all(m.get("started") for m in b): print("FAIL a member is not started"); sys.exit()
leaders=[m["memberId"] for m in b if m.get("role")=="LEADER"]
if len(leaders)!=1: print("FAIL leaders=%s"%leaders); sys.exit()
ap=[m.get("applied",-1) for m in b]
if min(ap)<=0: print("FAIL applied %s (<=0 means nothing applied)"%ap); sys.exit()
adv=[y.get("applied",-1)-x.get("applied",-1) for x,y in zip(a,b)]
if min(adv)<5: print("FAIL a member applied fewer than the 5 sequenced probe orders: deltas %s"%adv); sys.exit()
print("OK %d members, leader m%s, applied %s -> %s"%(n,leaders[0],[m.get("applied") for m in a],ap))
' "${s1}" "${s2}" "${#MEMBERS[@]}")"
[[ "${#MEMBERS[@]}" -eq 3 && "${verdict}" == OK* ]] && ok consensus "${verdict#OK }" || bad consensus "${verdict#FAIL } (members found: ${#MEMBERS[@]})"


accts="$(edge http://account-service:18088/account/ | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' 2>/dev/null)"
[[ "${accts:-0}" -gt 0 ]] && ok frontend "account directory lists ${accts} accounts via edge proxy" || bad frontend "account directory empty/unreachable"
c="$(code http://reference-data:18085/stocks)"
[[ "${c}" == 200 ]] && ok frontend "reference-data /stocks 200" || bad frontend "reference-data /stocks ${c:-none}"
c="$(code http://nats-broker:8081/)"
# nats answers a plain GET on its websocket port with 400 (upgrade required); 000 = nothing listening
[[ "${c}" == 400 || "${c}" == 101 ]] && ok frontend "nats websocket listener behind /nats-ws answers (${c})" || bad frontend "nats-broker:8081 answered ${c:-none}: console live prices/blotter feed will be dead"

if [[ -n "${CONSOLE_URL}" ]]; then
  c="$(curl -s -m8 -o /dev/null -w '%{http_code}' "${CONSOLE_URL}/")"; g="$(curl -s -m8 -o /dev/null -w '%{http_code}' "${CONSOLE_URL}/order-matcher/ready")"
  [[ "${c}" == 200 && "${g}" == 200 ]] && ok console "app 200, console->edge->gateway /ready 200" || bad console "app ${c:-none}, gateway via console ${g:-none}"
else
  printf '[skip] %-11s %s\n' console "CONSOLE_URL not set"
fi

c="$(code http://execution-algo-engine:18120/actuator/health/readiness)"
[[ "${c}" == 200 ]] && ok algo "execution-algo-engine readiness 200" || bad algo "execution-algo-engine readiness ${c:-none}"

c="$(code http://otel-collector:13133/)"; [[ "${c}" == 200 ]] && ok observ "otel-collector health 200" || bad observ "otel-collector health ${c:-none}"
up="$(edge 'http://prometheus:9090/prometheus/api/v1/query?query=up%7Bjob%3D%22traderx-cluster-members%22%7D' | python3 -c '
import json,sys; r=json.load(sys.stdin)["data"]["result"]; print(sum(1 for x in r if x["value"][1]=="1"), len(r))' 2>/dev/null)"
[[ "${up:-0 0}" == "3 3" ]] && ok observ "prometheus: 3/3 cluster member targets up" || bad observ "prometheus member targets up/total: ${up:-unreadable}"
for u in http://loki:3100/ready http://grafana:3000/api/health; do
  c="$(code "${u}")"; [[ "${c}" == 200 ]] && ok observ "${u#http://} 200" || bad observ "${u#http://} ${c:-none}"
done
tready="$(code http://tempo:3200/ready)"
spans=0
if [[ -n "${TRACE_ID}" ]]; then
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    spans="$(edge "http://tempo:3200/api/traces/${TRACE_ID}" | python3 -c '
import json,sys; print(sum(len(s.get("spans",[])) for b in json.load(sys.stdin).get("batches",[]) for s in b.get("scopeSpans",[])))' 2>/dev/null)"
    [[ "${spans:-0}" -gt 0 ]] && break; sleep 3
  done
fi
if [[ "${spans:-0}" -gt 0 ]]; then ok observ "tempo returns trace ${TRACE_ID} of the rejected probe order (${spans} span(s); /ready says ${tready})"
else bad observ "no fetchable trace for the rejected probe order (trace id '${TRACE_ID:-none in gateway log}'; tempo /ready ${tready})"; fi

n="$(edge http://price-publisher:18100/prices | python3 -c 'import json,sys; print(sum(1 for q in json.load(sys.stdin)["prices"] if q.get("price",0)>0))' 2>/dev/null)"
[[ "${n:-0}" -gt 0 ]] && ok prices "${n} instruments quoted (synthetic walk unless the tape replay was fetched)" || bad prices "price-publisher returned no quotes"

echo
if [[ ${fails} -eq 0 ]]; then echo "[ready] full rig ready on ${CTX}"; exit 0; fi
echo "[not ready] ${fails} check(s) failed on ${CTX}"; exit 1
