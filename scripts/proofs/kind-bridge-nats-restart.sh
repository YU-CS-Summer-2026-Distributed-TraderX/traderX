#!/usr/bin/env bash
# FR-DRS-38 and FR-DRS-40 at the effect end on kind: NATS is killed under load with a steady leader, and the stream
# still holds every trade side and every order event the members applied, once each. The rig is production's default
# shape (kind-yu18-rig.py --history --binary --partitions 1): legacy ids, no run descriptor. The load is production's
# driver, binary-load.mjs. NATS is killed in place (SIGKILL on its node, as kind's OOM kills of 2026-11-08 were), so
# its emptyDir and the stream survive and only what was in flight is at stake.
#
#   trade sides   G3h (rig.sh's g3_history, sourced): the store holds sides 1..N of the members' N, each once
#   order events  the load books two per side (accepted, filled): the store's orders grow by exactly 2 x the sides
#   no repeat     the store holds exactly the stream's messages (history-feed leaves a repeated position out)
#   not vacuous   NATS restarted once per kill, the leader did not change, and the leader republished records it had
#                 already sent (`replayed` > 0 on both bridges) with no gap
#
#   EXPECT_LOSS=1 is the control, for a member image before FR-DRS-40: the same run must LOSE order events (trade
#   sides are still held, FR-DRS-38). It fails if nothing was lost: the kills did not discriminate.
#
#   CTX=kind-<c> NS=traderx [SECS=180 SESSIONS=4 RATE=250 KILL_AT="30 100" EXPECT_LOSS=1] \
#     bash scripts/proofs/kind-bridge-nats-restart.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${CTX:?set CTX (a kind context)}"; NS=${NS:-traderx}
[[ $CTX == kind-* ]] || { echo "kind only: this kills NATS on its node" >&2; exit 1; }
EVIDENCE=${EVIDENCE:-${TMPDIR:-/tmp}/kind-bridge-nats-restart-$$}; mkdir -p "$EVIDENCE"
RIG_LIB=1 . "$ROOT/scripts/gcp-compsci/rig/rig.sh"
# A lane's queue holds 65,536 records while NATS is down: 4 x 250 orders/s is 2,000 order events/s, about 30 s.
SECS=${SECS:-180}; SESSIONS=${SESSIONS:-4}; RATE=${RATE:-250}; PX=${TICKER_PX:-50}; KILL_AT=${KILL_AT:-30 100}
NODE_IMAGE=${NODE_IMAGE:-node:20-alpine}
fail(){ echo "[FAIL] $*" >&2; exit 1; }
q(){ kc exec sts/clickhouse -c clickhouse -- clickhouse-client -q "$1"; }
# The operator token goes in on stdin (curl -H @-), never on argv.
op(){ kc get secret auth-secrets -o jsonpath='{.data.risk-control-token}' | base64 -d \
        | awk '{print "X-Risk-Control-Token: " $0; print "X-Risk-Operator: kind-bridge-nats-restart"; print "Content-Type: application/json"}' \
        | kc exec -i deploy/trade-processor -- curl -s -m 30 -o /dev/null -w '%{http_code}' -H @- -X POST "http://order-matcher:18110$1" -d "$2"; }
health(){ kc exec "order-matcher-cluster-$1" -c cluster-node -- wget -qO- localhost:8080/health; }
trades(){ member 0 | cut -d' ' -f3; }
leader(){ local i; for i in 0 1 2; do [[ $(health $i | jq -r .role) == LEADER ]] && { echo $i; return; }; done; echo none; }
restarts(){ kc get pod -l app=nats -o jsonpath='{.items[0].status.containerStatuses[0].restartCount}'; }
stream(){ kc exec deploy/nats -- wget -qO- 'http://localhost:8222/jsz?streams=true' \
  | jq '[.account_details[].stream_detail[] | select(.name == "TRADERX_FEED") | .state.messages] | add // -1'; }

step "1. admission: business day, binary-load's accounts 9900010-17, a fresh ticker at $PX"
T=$(python3 -c 'import random;print("NR%06d" % random.randrange(10**6))')
[[ $(op /session/business-day "{\"businessDate\":\"$(TZ=America/New_York date +%F)\"}") == 200 ]] || fail "business day"
# not seq: macOS prints 9900010 as 9.90001e+06
for ((a = 9900010; a <= 9900017; a++)); do [[ $(op /risk/control/account "{\"accountId\":$a,\"enabled\":true}") == 200 ]] || fail "admit $a"; done
[[ $(op /seed "{\"accountId\":9900010,\"tickers\":\"$T\",\"price\":$PX}") == 200 ]] || fail "seed $T"
until history_caught_up; do sleep 2; done
t0=$(trades); o0=$(q "SELECT count() FROM orders"); r0=$(restarts); l0=$(leader); L=$l0
[[ $l0 != none ]] || fail "no leader"
# kubelet doubles a crashed container's restart delay (10 s, 20 s, 40 s, ...) and forgets it after 10 minutes up. A
# kill inside that window keeps NATS down longer than a lane's queue holds (2026-11-09: 49 s on the fourth restart).
up=$(kc get pod -l app=nats -o jsonpath='{.items[0].status.containerStatuses[0].state.running.startedAt}')
[[ $r0 == 0 ]] || python3 -c 'import sys, datetime as d
sys.exit(0 if (d.datetime.now(d.timezone.utc) - d.datetime.fromisoformat(sys.argv[1].replace("Z", "+00:00"))).total_seconds() >= 600 else 1)' "$up" \
  || fail "NATS last restarted at $up: its crash backoff resets after 10 minutes up; run after that"
b0=$(health "$L" | jq -c '.bridges | {t: .trades.replayed, o: .orders.replayed, g: (.trades.gaps + .orders.gaps)}')
note "ticker $T; members' trades $t0; store orders $o0; leader member $l0; NATS restarts $r0; leader's bridges before $b0"

step "2. the load ($SESSIONS sessions x $RATE/s for ${SECS}s); NATS killed at ${KILL_AT// / and } s"
kc delete job binary-load --ignore-not-found --wait=true >/dev/null
kc create configmap binary-load --from-file=binary-load.mjs="$ROOT/scripts/gcp-compsci/rig/binary-load.mjs" --dry-run=client -o yaml | kc apply -f - >/dev/null
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
                  --sessions, "$SESSIONS", --rate, "$RATE", --secs, "$SECS", --phase-secs, "${PHASE_SECS:-60}", --pause-ms, "${PAUSE_MS:-10000}", --out, /tmp/results.json]
        volumeMounts: [{name: load, mountPath: /load}]
      volumes: [{name: load, configMap: {name: binary-load}}]
YAML
kc wait --for=condition=Ready pod -l job-name=binary-load --timeout=120s >/dev/null
s0=$SECONDS; kills=0
for at in $KILL_AT; do
  while ((SECONDS - s0 < at)); do sleep 1; done
  node=$(kc get pod -l app=nats -o jsonpath='{.items[0].spec.nodeName}')
  docker exec "$node" pkill -9 -x nats-server || fail "no nats-server to kill on $node"
  kills=$((kills + 1)); note "$(et)  SIGKILL nats-server on $node (members' trades $(trades))"
done
until [[ -n $(kc get job binary-load -o jsonpath='{.status.succeeded}{.status.failed}') ]]; do sleep 5; done
[[ $(kc get job binary-load -o jsonpath='{.status.succeeded}') == 1 ]] || { kc logs job/binary-load | tail -5 >&2; fail "binary-load did not complete clean"; }
kc logs job/binary-load | grep '^RESULT' | tee "$EVIDENCE/load-result.txt" | cut -c1-300
t=$(trades); sides=$((t - t0)); note "members' trades after: $t ($sides sides booked by the load)"
[[ $sides -gt 0 ]] || fail "the load booked nothing"
for m in 1 2; do [[ $(member $m | cut -d' ' -f3) == "$t" ]] || fail "member $m disagrees on trades"; done
[[ $(leader) == "$l0" ]] || fail "the leader changed ($l0 to $(leader)): this run is not a steady leader"
[[ $(restarts) == $((r0 + kills)) ]] || fail "NATS restarted $(( $(restarts) - r0 )) times for $kills kills"

step "3. the stream against the members"
out=$( (g3_history "$t") 2>&1) || fail "G3h: $out"
echo "$out" | tee "$EVIDENCE/g3h.txt" | grep -F "G3h ok" || fail "G3h printed no verdict: $out"
for i in 0 1 2; do health $i | jq -c "{member: $i, role, bridges}"; done > "$EVIDENCE/bridges.txt"
b=$(health "$L" | jq -c '.bridges | {t: .trades.replayed, o: .orders.replayed, tg: .trades.gaps, og: .orders.gaps}')
note "leader's bridges after: $b"
[[ $(jq '.tg + .og' <<<"$b") == $(jq .g <<<"$b0") ]] || fail "a lane counted a gap (NATS was down longer than its queue holds): lower RATE and rerun ($b)"
[[ $(jq .t <<<"$b") -gt $(jq .t <<<"$b0") ]] || fail "the trade lane republished nothing: no kill caught a side in flight, rerun"
stored=$(q "SELECT (SELECT count() FROM trades) + (SELECT count() FROM orders)"); msgs=$(stream)
[[ $stored == "$msgs" ]] || fail "the store holds $stored rows, the stream $msgs messages: a repeat was stored on the stream"
o=$(q "SELECT count() FROM orders"); got=$((o - o0)); want=$((2 * sides))
summary="$sides sides, G3h ok; order events $got of $want; store = stream $stored; $kills NATS kills; leader's bridges $b"
if [[ ${EXPECT_LOSS:-0} == 1 ]]; then
  [[ $got -lt $want ]] || fail "the control lost no order event ($got of $want): the kills did not discriminate"
  echo "$summary" > "$EVIDENCE/summary.txt"
  echo; echo "KIND-BRIDGE-NATS-RESTART CONTROL LOST $((want - got)) order events ($summary) evidence $EVIDENCE"; exit 0
fi
[[ $got == "$want" ]] || fail "order events on the stream: $got, the load's sides need $want ($((want - got)) missing)"
[[ $(jq .o <<<"$b") -gt $(jq .o <<<"$b0") ]] || fail "the order lane republished nothing: no kill caught an order event in flight, rerun"
echo "$summary" > "$EVIDENCE/summary.txt"
echo; echo "KIND-BRIDGE-NATS-RESTART PASS ($summary) evidence $EVIDENCE"
