#!/usr/bin/env bash
# FR-BSP-02 on kind: a pipelined FIX session carries many orders at once, so a few sessions reach the rate many reach.
# The rig as for kind-binary-pipelining.sh (kind-yu18-rig.py --history --binary --throughput --partitions 1, one gateway):
# egress coalescing on, the gateway at production's 4 CPUs, FIX sessions in JetStream (ADR-086) with group commit
# (ADR-099), so FR-GFS-19's held-back target is on the path. The load is fix-multi.mjs, open loop at the same total
# offer (TOTAL_RATE) in every arm, split over that arm's sessions (LOAD0000.., even sessions buy on 9900010, odd sell on
# 9900011 at one price, so they cross). The gateway is given those CompIDs and a password each.
# kind's NATS holds about one arm (kind-binary-pipelining.sh), and the FIX store writes every report to it as well:
# run one arm per fresh rig.
#
#   for each arm "<sessions>[:<gateway image>]" (default "4 16 64"): the rate is the gateway's accepted-orders counter
#   over the load window (fix-multi's SERVER-SIDE line); /latency is reset before and read after; an arm on the image
#   under test must end clean (every order answered once, none rejected, none in flight after the drain)
#   then: G3h (rig.sh's g3_history, sourced) over every side the arms booked
#
# A control arm names the pre-fix gateway image, e.g. ARMS="4:traderx/cluster-node:bsp"; it is not held to "clean": a
# serial session falls behind an open-loop offer above its rate and ends with a backlog.
#
#   CTX=kind-<c> [NS=traderx ARMS="4 16 64" SECS=40 TOTAL_RATE=8000] bash scripts/proofs/kind-fix-pipelining.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${CTX:?set CTX (a kind context)}"; NS=${NS:-traderx}
( . "$ROOT/scripts/proofs/lib-yu18.sh"; disposable_rig ) || { echo "disposable rig only: this drives load at the rig" >&2; exit 1; }
EVIDENCE=${EVIDENCE:-${TMPDIR:-/tmp}/kind-fix-pipelining-$$}; mkdir -p "$EVIDENCE"
RIG_LIB=1 . "$ROOT/scripts/gcp-compsci/rig/rig.sh"
[[ $(kc get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="CLUSTER_EGRESS_COALESCE")].value}') == true ]] \
  || { echo "render the rig with kind-yu18-rig.py --throughput (egress coalescing)" >&2; exit 1; }
ARMS=${ARMS:-4 16 64}; SECS=${SECS:-40}; TOTAL_RATE=${TOTAL_RATE:-8000}; PX=${TICKER_PX:-50}
NODE_IMAGE=${NODE_IMAGE:-node:20-alpine}
MAX_SESSIONS=64
fail(){ echo "[FAIL] $*" >&2; exit 1; }
op(){ kc get secret auth-secrets -o jsonpath='{.data.risk-control-token}' | base64 -d \
        | awk '{print "X-Risk-Control-Token: " $0; print "X-Risk-Operator: kind-fix-pipelining"; print "Content-Type: application/json"}' \
        | kc exec -i deploy/trade-processor -- curl -s -m 30 -o /dev/null -w '%{http_code}' -H @- -X POST "http://order-matcher:18110$1" -d "$2"; }
trades(){ member 0 | cut -d' ' -f3; }
gw_image(){ kc get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].image}'; }
COMPIDS=$(for ((i = 0; i < MAX_SESSIONS; i++)); do printf 'LOAD%04d,' "$i"; done); COMPIDS=${COMPIDS%,}
AUTH=$(for c in ${COMPIDS//,/ }; do printf '%s:$(FIX_SESSION_PASSWORD):*,' "$c"; done); AUTH=${AUTH%,}
set_gateway(){ # set_gateway <image>: one gateway pod on that image, the load's CompIDs, the latency decomposition on
  kc patch deploy cluster-gateway --type merge -p '{"spec":{"strategy":{"type":"Recreate","rollingUpdate":null}}}' >/dev/null
  kc set image deploy/cluster-gateway "$(kc get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].name}')=$1" >/dev/null
  kc set env deploy/cluster-gateway LATENCY_DECOMP=1 "FIX_SESSION_COMPIDS=$COMPIDS" "FIX_SESSION_AUTH=$AUTH" >/dev/null
  kc rollout status deploy/cluster-gateway --timeout=300s >/dev/null
  start_forward; until curl -sf -m 2 "localhost:$LP/latency" >/dev/null; do sleep 2; done; }
FWD=; LP=
start_forward(){ [[ -n $FWD ]] && kill "$FWD" 2>/dev/null; LP=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')
  kc port-forward deploy/cluster-gateway "$LP:18110" >/dev/null 2>&1 & FWD=$!; sleep 2; }
trap '[[ -n $FWD ]] && kill $FWD 2>/dev/null; true' EXIT
result(){ sed -n 's/^RESULT //p' "$1"; }
restarts(){ kc get pods -l "app in (nats,trade-processor,cluster-gateway)" -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}'; }
lat(){ awk -v k="traderx_gateway_latency_us{segment=\"$1\",pct=\"$2\"}" '$1 == k {print $2}' "$3"; }

step "1. admission: business day, accounts 9900010-11, a fresh ticker at $PX"
T=$(python3 -c 'import random;print("FP%06d" % random.randrange(10**6))')
[[ $(op /session/business-day "{\"businessDate\":\"$(TZ=America/New_York date +%F)\"}") == 200 ]] || fail "business day"
for a in 9900010 9900011; do [[ $(op /risk/control/account "{\"accountId\":$a,\"enabled\":true}") == 200 ]] || fail "admit $a"; done
[[ $(op /seed "{\"accountId\":9900010,\"tickers\":\"$T\",\"price\":$PX}") == 200 ]] || fail "seed $T"
kc create configmap fix-load --from-file=fix-multi.mjs="$ROOT/scripts/bench/load/fix-multi.mjs" --dry-run=client -o yaml | kc apply -f - >/dev/null
UNDER_TEST=$(gw_image); t0=$(trades); note "ticker $T; gateway under test $UNDER_TEST; members' trades before: $t0"

run_arm(){ # run_arm <sessions> <image>
  local n=$1 img=$2 tag="$1-${2##*:}"
  (( n <= MAX_SESSIONS )) || fail "at most $MAX_SESSIONS sessions"
  [[ $(gw_image) == "$img" ]] || set_gateway "$img"
  curl -sf -m 5 "localhost:$LP/latency?reset=1" >/dev/null || fail "latency reset"
  local r0; r0=$(restarts)
  kc delete job fix-load --ignore-not-found --wait=true >/dev/null
  kc apply -f - >/dev/null <<YAML
apiVersion: batch/v1
kind: Job
metadata: {name: fix-load}
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
      - name: load
        image: $NODE_IMAGE
        imagePullPolicy: IfNotPresent
        command: [node, /load/fix-multi.mjs]
        env:
        - {name: GATEWAYS, value: "order-matcher:18130"}
        - {name: MEMBER, value: "none"}
        - {name: SESSIONS, value: "$n"}
        - {name: TOTAL, value: "$TOTAL_RATE"}
        - {name: SECS, value: "$SECS"}
        - {name: ACCT_BUY, value: "9900010"}
        - {name: ACCT_SELL, value: "9900011"}
        - {name: TICKER, value: "$T"}
        - {name: PRICE, value: "$PX"}
        - {name: QTY, value: "1"}
        - {name: FIX_PASSWORD, valueFrom: {secretKeyRef: {name: auth-secrets, key: fix-session-password}}}
        volumeMounts: [{name: load, mountPath: /load}]
      volumes: [{name: load, configMap: {name: fix-load}}]
YAML
  kc wait --for=condition=complete --timeout=$((SECS + 180))s job/fix-load >/dev/null || true
  kc logs job/fix-load > "$EVIDENCE/load-$tag.log"
  [[ $(restarts) == "$r0" ]] || fail "arm $tag: NATS, trade-processor or the gateway restarted during the arm ($r0-> $(restarts))"
  curl -sf -m 5 "localhost:$LP/latency" > "$EVIDENCE/latency-$tag.txt" || fail "arm $tag: /latency"
  local r; r=$(result "$EVIDENCE/load-$tag.log"); [[ -n $r ]] || { tail -5 "$EVIDENCE/load-$tag.log" >&2; fail "arm $tag: no RESULT"; }
  local line="sessions=$n image=$img $(jq -r '"rate=\(.serverRate|floor)/s up=\(.sessionsUp) offered=\(.offered) completed=\(.completed) in_flight=\(.inFlight) rejected=\(.rejected) unknown=\(.unknown) client_p50=\(.p50ms|floor)ms client_p99=\(.p99ms|floor)ms"' <<< "$r") queue_p50=$(lat queue p50 "$EVIDENCE/latency-$tag.txt")us queue_p99=$(lat queue p99 "$EVIDENCE/latency-$tag.txt")us cluster_p50=$(lat cluster p50 "$EVIDENCE/latency-$tag.txt")us total_p50=$(lat total p50 "$EVIDENCE/latency-$tag.txt")us total_p99=$(lat total p99 "$EVIDENCE/latency-$tag.txt")us"
  note "$line"; echo "$line" >> "$EVIDENCE/arms.txt"
  [[ $(jq .sessionsUp <<< "$r") == "$n" ]] || fail "arm $tag: not every session logged on"
  if [[ $img == "$UNDER_TEST" ]]; then
    jq -e '.inFlight == 0 and .rejected == 0 and .unknown == 0' <<< "$r" >/dev/null \
      || fail "arm $tag: not clean (every order answered once, none rejected, none left in flight)"
  fi
}

step "2. arms: $ARMS, $TOTAL_RATE orders/s offered in total, ${SECS}s each"
set_gateway "$UNDER_TEST"   # the load's CompIDs and LATENCY_DECOMP=1
for arm in $ARMS; do run_arm "${arm%%:*}" "$([[ $arm == *:* ]] && echo "${arm#*:}" || echo "$UNDER_TEST")"; done
[[ $(gw_image) == "$UNDER_TEST" ]] || set_gateway "$UNDER_TEST"

step "3. G3h over every side the arms booked"
t=$(trades); for m in 1 2; do [[ $(member $m | cut -d' ' -f3) == "$t" ]] || fail "member $m disagrees on trades"; done
out=$( (g3_history "$t") 2>&1) || fail "G3h: $out"
echo "$out" | tee "$EVIDENCE/g3h.txt" | grep -F "G3h ok" || fail "G3h printed no verdict: $out"
echo; cat "$EVIDENCE/arms.txt"
echo "KIND-FIX-PIPELINING PASS ($((t - t0)) sides booked; evidence $EVIDENCE)"
