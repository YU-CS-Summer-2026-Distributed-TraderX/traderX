#!/usr/bin/env bash
# FR-BSP-01 / NFR-BSP-01 on kind: a pipelined binary session carries many orders at once, so a few sessions reach the
# rate many reach. The rig is production's default shape (kind-yu18-rig.py --history --binary --throughput --partitions 1, one
# gateway; --throughput: the members at the throughput run's settings and the gateway at production's 4 CPUs. Without
# coalescing one pipelined session's acks overran the members' best-effort egress at 14K/s and 7 of 720,819 were
# dropped, answered ambiguous after the reap; at kind's 1-CPU gateway limit the owner thread was CFS-throttled and
# queue p99 read 22.7 ms); the load is production's driver, binary-load.mjs, at the same total offer (TOTAL_RATE) and the same total
# in flight (CLIENT_INFLIGHT) in every arm, split over that arm's sessions. The gateway runs with LATENCY_DECOMP=1.
# Size TOTAL_RATE above what the pre-fix gateway's sessions reach and below what the rig's downstream holds: kind's
# NATS (768Mi, file store) was OOM-killed at ~29K orders/s on 2026-11-10, and the members then stalled the order path.
# An arm during which NATS or trade-processor restarts fails. kind's NATS also holds only about one arm's stream (it was
# OOM-killed ~1.3M orders into a second arm), so on kind run one arm per fresh rig: delete the namespace, re-apply the
# render, run with ARMS set to that one arm (each run checks G3h over its own sides).
#
#   for each arm "<sessions>[:<gateway image>]" (default "16 64"): the rate is the gateway's accepted-orders counter
#   over 5 s windows that lie inside the load's sending windows (median); /latency is reset before and read after
#   (queue p99 must stay under QUEUE_P99_US for an arm on the image under test); the load must end clean (no
#   ambiguous, unanswered, duplicate or unbalanced order)
#   then: G3h (rig.sh's g3_history, sourced) over every side the arms booked
#
# A control arm names the pre-fix gateway image, e.g. ARMS="16 64 16:traderx/cluster-node:bat"; the gateway is set back
# to the image under test afterwards. Compare arms run on the same rig only.
#
#   CTX=kind-<c> [NS=traderx ARMS="16 64" SECS=70 TOTAL_RATE=15000 CLIENT_INFLIGHT=2048 QUEUE_P99_US=50] \
#     bash scripts/proofs/kind-binary-pipelining.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${CTX:?set CTX (a kind context)}"; NS=${NS:-traderx}
( . "$ROOT/scripts/proofs/lib-yu18.sh"; disposable_rig ) || { echo "disposable rig only: this drives load at the rig" >&2; exit 1; }
EVIDENCE=${EVIDENCE:-${TMPDIR:-/tmp}/kind-binary-pipelining-$$}; mkdir -p "$EVIDENCE"
RIG_LIB=1 . "$ROOT/scripts/gcp-compsci/rig/rig.sh"
[[ $(kc get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="CLUSTER_EGRESS_COALESCE")].value}') == true ]] \
  || { echo "render the rig with kind-yu18-rig.py --throughput (egress coalescing)" >&2; exit 1; }
ARMS=${ARMS:-16 64}; SECS=${SECS:-70}; TOTAL_RATE=${TOTAL_RATE:-15000}; CLIENT_INFLIGHT=${CLIENT_INFLIGHT:-2048}; PX=${TICKER_PX:-50}; QUEUE_P99_US=${QUEUE_P99_US:-50}
PHASE_SECS=60; PAUSE_MS=10000   # sending from 0.25 s to 50 s of each wall-clock minute
NODE_IMAGE=${NODE_IMAGE:-node:20-alpine}
fail(){ echo "[FAIL] $*" >&2; exit 1; }
op(){ kc get secret auth-secrets -o jsonpath='{.data.risk-control-token}' | base64 -d \
        | awk '{print "X-Risk-Control-Token: " $0; print "X-Risk-Operator: kind-binary-pipelining"; print "Content-Type: application/json"}' \
        | kc exec -i deploy/trade-processor -- curl -s -m 30 -o /dev/null -w '%{http_code}' -H @- -X POST "http://order-matcher:18110$1" -d "$2"; }
trades(){ member 0 | cut -d' ' -f3; }
gw_image(){ kc get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].image}'; }
set_gateway(){ # set_gateway <image>: one gateway pod on that image with the latency decomposition on. Recreate: a
  # guaranteed 4-CPU gateway leaves no room on kind for a surge pod beside the old one.
  kc patch deploy cluster-gateway --type merge -p '{"spec":{"strategy":{"type":"Recreate","rollingUpdate":null}}}' >/dev/null
  kc set image deploy/cluster-gateway "$(kc get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].name}')=$1" >/dev/null
  kc set env deploy/cluster-gateway LATENCY_DECOMP=1 >/dev/null
  kc rollout status deploy/cluster-gateway --timeout=300s >/dev/null
  start_forward; until curl -sf -m 2 "localhost:$LP/latency" >/dev/null; do sleep 2; done; }
FWD=; LP=
start_forward(){ [[ -n $FWD ]] && kill "$FWD" 2>/dev/null; LP=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')
  kc port-forward deploy/cluster-gateway "$LP:18110" >/dev/null 2>&1 & FWD=$!; sleep 2; }
trap '[[ -n $FWD ]] && kill $FWD 2>/dev/null; true' EXIT
accepted(){ curl -sf -m 5 "localhost:$LP/metrics" | awk '$1 == "traderx_order_events_total{event=\"accepted\"}" {print $2}'; }
result(){ sed -n 's/^RESULT //p' "$1"; }
restarts(){ kc get pods -l "app in (nats,trade-processor)" -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}'; }
lat(){ awk -v k="traderx_gateway_latency_us{segment=\"$1\",pct=\"$2\"}" '$1 == k {print $2}' "$3"; }

step "1. admission: business day, binary-load's accounts 9900010-17, a fresh ticker at $PX"
T=$(python3 -c 'import random;print("BP%06d" % random.randrange(10**6))')
[[ $(op /session/business-day "{\"businessDate\":\"$(TZ=America/New_York date +%F)\"}") == 200 ]] || fail "business day"
for ((a = 9900010; a <= 9900017; a++)); do [[ $(op /risk/control/account "{\"accountId\":$a,\"enabled\":true}") == 200 ]] || fail "admit $a"; done
[[ $(op /seed "{\"accountId\":9900010,\"tickers\":\"$T\",\"price\":$PX}") == 200 ]] || fail "seed $T"
kc create configmap binary-load --from-file=binary-load.mjs="$ROOT/scripts/gcp-compsci/rig/binary-load.mjs" --dry-run=client -o yaml | kc apply -f - >/dev/null
UNDER_TEST=$(gw_image); t0=$(trades); note "ticker $T; gateway under test $UNDER_TEST; members' trades before: $t0"

run_arm(){ # run_arm <sessions> <image>: prints "<sessions> <image> <rate> <queue p99> ..." and leaves evidence
  local n=$1 img=$2 rate=$(( TOTAL_RATE / $1 )) tag="$1-${2##*:}"
  if [[ $(gw_image) == "$img" ]]; then start_forward; else set_gateway "$img"; fi
  curl -sf -m 5 "localhost:$LP/latency?reset=1" >/dev/null || fail "latency reset"
  local r0; r0=$(restarts)
  kc delete job binary-load --ignore-not-found --wait=true >/dev/null
  kc apply -f - >/dev/null <<YAML
apiVersion: batch/v1
kind: Job
metadata: {name: binary-load}
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
      - name: load
        image: $NODE_IMAGE
        imagePullPolicy: IfNotPresent
        command: [node, /load/binary-load.mjs, --resolve, "http://order-matcher:18110", --ticker, "$T", --px, "$PX",
                  --sessions, "$n", --rate, "$rate", --max-in-flight, "$((CLIENT_INFLIGHT / n))", --secs, "$SECS", --phase-secs, "$PHASE_SECS", --pause-ms, "$PAUSE_MS", --out, /tmp/results.json]
        volumeMounts: [{name: load, mountPath: /load}]
      volumes: [{name: load, configMap: {name: binary-load}}]
YAML
  # Samples "<epoch s> <accepted>" every second while the job runs; a 5 s window counts when both ends are in one
  # sending window (5..45 s past the minute: clear of the 0.25 s start and the 50 s stop) before the load's deadline.
  : > "$EVIDENCE/samples-$tag.txt"
  until [[ -n $(kc get job binary-load -o jsonpath='{.status.succeeded}{.status.failed}') ]]; do
    a=$(accepted) && echo "$(python3 -c 'import time;print(f"{time.time():.3f}")') $a" >> "$EVIDENCE/samples-$tag.txt"; sleep 1
  done
  kc logs job/binary-load > "$EVIDENCE/load-$tag.log"
  [[ $(restarts) == "$r0" ]] || fail "arm $tag: NATS or trade-processor restarted during the arm ($r0-> $(restarts)): the downstream did not hold the rate"
  [[ $(kc get job binary-load -o jsonpath='{.status.succeeded}') == 1 ]] || { tail -5 "$EVIDENCE/load-$tag.log" >&2; fail "arm $tag: binary-load did not end clean"; }
  curl -sf -m 5 "localhost:$LP/latency" > "$EVIDENCE/latency-$tag.txt" || fail "arm $tag: /latency"
  local started; started=$(result "$EVIDENCE/load-$tag.log" | jq -r .startedAt)
  local r; r=$(python3 - "$EVIDENCE/samples-$tag.txt" "$started" "$SECS" <<'PY'
import sys, statistics, datetime
s = [tuple(map(float, l.split())) for l in open(sys.argv[1]) if len(l.split()) == 2]
end = datetime.datetime.fromisoformat(sys.argv[2].replace("Z", "+00:00")).timestamp() + float(sys.argv[3])
w = []
for i, (t0, a0) in enumerate(s):
    for t1, a1 in s[i + 1:]:
        if t1 - t0 >= 5:
            if int(t0 // 60) == int(t1 // 60) and 5 <= t0 % 60 and t1 % 60 <= 45 and t1 <= end:
                w.append((a1 - a0) / (t1 - t0))
            break
print(f"{statistics.median(w):.0f} {min(w):.0f} {max(w):.0f} {len(w)}" if w else "0 0 0 0")
PY
)
  set -- $r
  local line="sessions=$n image=$img rate=$1/s (5s windows min $2 max $3, n=$4) queue_p50=$(lat queue p50 "$EVIDENCE/latency-$tag.txt")us queue_p99=$(lat queue p99 "$EVIDENCE/latency-$tag.txt")us cluster_p50=$(lat cluster p50 "$EVIDENCE/latency-$tag.txt")us total_p50=$(lat total p50 "$EVIDENCE/latency-$tag.txt")us total_p99=$(lat total p99 "$EVIDENCE/latency-$tag.txt")us $(result "$EVIDENCE/load-$tag.log" | jq -r '"sent=\(.sent) accepted=\(.accepted) rejected=\(.rejected) ambiguous=\(.ambiguous) unanswered=\(.unanswered)"')"
  note "$line"; echo "$line" >> "$EVIDENCE/arms.txt"
  [[ $4 -ge 3 ]] || fail "arm $tag: only $4 rate windows inside the sending windows"
  if [[ $img == "$UNDER_TEST" ]]; then
    python3 -c "import sys; sys.exit(0 if float('$(lat queue p99 "$EVIDENCE/latency-$tag.txt")') < $QUEUE_P99_US else 1)" \
      || VERDICT+="arm $tag: queue p99 $(lat queue p99 "$EVIDENCE/latency-$tag.txt") us, not under $QUEUE_P99_US; "
  fi
}

VERDICT=
step "2. arms: $ARMS, $TOTAL_RATE orders/s offered and $CLIENT_INFLIGHT in flight in total, ${SECS}s each"
set_gateway "$UNDER_TEST"   # LATENCY_DECOMP=1 (a roll when it was off)
for arm in $ARMS; do run_arm "${arm%%:*}" "$([[ $arm == *:* ]] && echo "${arm#*:}" || echo "$UNDER_TEST")"; done
[[ $(gw_image) == "$UNDER_TEST" ]] || set_gateway "$UNDER_TEST"

step "3. G3h over every side the arms booked"
t=$(trades); for m in 1 2; do [[ $(member $m | cut -d' ' -f3) == "$t" ]] || fail "member $m disagrees on trades"; done
out=$( (g3_history "$t") 2>&1) || fail "G3h: $out"
echo "$out" | tee "$EVIDENCE/g3h.txt" | grep -F "G3h ok" || fail "G3h printed no verdict: $out"
echo; cat "$EVIDENCE/arms.txt"; [[ -z $VERDICT ]] || fail "$VERDICT(G3h ok)"
echo "KIND-BINARY-PIPELINING PASS ($((t - t0)) sides booked; evidence $EVIDENCE)"
