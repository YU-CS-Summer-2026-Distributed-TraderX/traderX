#!/usr/bin/env bash
# FR-DRS-47 (RI37) at the effect end on kind: the leader is killed under load while NATS is frozen, so the promoted
# member's lanes cannot read the stream for FREEZE_SECS - longer than the 65,536-record queue a lane had before
# FR-DRS-47 holds at this rate - and the stream still holds every trade side once. GKE 2026-11-10 (run D) lost 22.9M
# sides this way at ~510K orders/s. kind's rate cannot fill a queue in the second a healthy catch-up takes, so the
# freeze stands in for the JetStream API stalls of 8-30 s seen at rate (FR-DRS-42). The rig is production's default
# shape (kind-yu18-rig.py --history --binary --partitions 1): one lane per bridge carries the whole rate.
#
#   trade sides   G3h (rig.sh's g3_history, sourced): the store holds sides 1..N of the members' N, each once
#   nothing lost  every member's bridges: queueDropped 0 and gaps unchanged; history-feed left out no repeat
#   not vacuous   the leader changed, NATS did not restart, and a live member's trade lane held more than 65,536
#                 records unpublished (maxBacklog): the stall was longer than the old queue
#   order events  the load books two per side; strict when the load saw no ambiguous order, else reported
#
#   KILL_AFTER (FR-DRS-49, RI38) kills the leader that many seconds into the freeze instead of 2: the dead leader's
#   unstored tail (the members' sides between the freeze and the kill) must then exceed 65,536, what a follower kept
#   before FR-DRS-49, and replaces the maxBacklog check. KILL_AFTER=100 FREEZE_SECS=140 SECS=420 RATE=400.
#
#   EXPECT_LOSS=1 is the control, for a member image before FR-DRS-47: the same run must LOSE trade sides, with
#   queueDropped > 0 on a promoted member (with KILL_AFTER, for one before FR-DRS-49: a new gap). It fails if nothing
#   was lost: the freeze did not discriminate.
#
#   CTX=kind-<c> NS=traderx [SECS=300 SESSIONS=4 RATE=250 KILL_AT=60 FREEZE_SECS=120 KILL_AFTER=2 EXPECT_LOSS=1] \
#     bash scripts/proofs/kind-bridge-failover-at-rate.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${CTX:?set CTX (a kind context)}"; NS=${NS:-traderx}
[[ $CTX == kind-* ]] || { echo "kind only: this freezes NATS and kills a member on their nodes" >&2; exit 1; }
EVIDENCE=${EVIDENCE:-${TMPDIR:-/tmp}/kind-bridge-failover-at-rate-$$}; mkdir -p "$EVIDENCE"
RIG_LIB=1 . "$ROOT/scripts/gcp-compsci/rig/rig.sh"
# 4 x 250 orders/s is about 1,000 sides/s and 2,000 order events/s on one lane each: the old queue held 65 s and 33 s.
SECS=${SECS:-300}; SESSIONS=${SESSIONS:-4}; RATE=${RATE:-250}; PX=${TICKER_PX:-50}
KILL_AT=${KILL_AT:-60}; FREEZE_SECS=${FREEZE_SECS:-120}; KILL_AFTER=${KILL_AFTER:-2}; OLD_QUEUE=65536
((KILL_AFTER < FREEZE_SECS)) || { echo "KILL_AFTER ($KILL_AFTER) must fall inside the freeze ($FREEZE_SECS s)" >&2; exit 1; }
# binary-load's phases are wall-clock aligned and it stops if a session still has an order unanswered at a boundary:
# the kill (KILL_AFTER s after the freeze) lands 10 s to half a phase in, and the pause is long enough for the dead leader's in-flight orders to resolve.
PHASE_SECS=${PHASE_SECS:-120}; PAUSE_MS=${PAUSE_MS:-20000}
NODE_IMAGE=${NODE_IMAGE:-node:20-alpine}
fail(){ echo "[FAIL] $*" >&2; exit 1; }
q(){ kc exec sts/clickhouse -c clickhouse -- clickhouse-client -q "$1"; }
# The operator token goes in on stdin (curl -H @-), never on argv.
op(){ kc get secret auth-secrets -o jsonpath='{.data.risk-control-token}' | base64 -d \
        | awk '{print "X-Risk-Control-Token: " $0; print "X-Risk-Operator: kind-bridge-failover-at-rate"; print "Content-Type: application/json"}' \
        | kc exec -i deploy/trade-processor -- curl -s -m 30 -o /dev/null -w '%{http_code}' -H @- -X POST "http://order-matcher:18110$1" -d "$2"; }
health(){ kc exec "order-matcher-cluster-$1" -c cluster-node -- wget -qO- localhost:8080/health; }
trades(){ member 0 | cut -d' ' -f3; }
leader(){ local i; for i in 0 1 2; do [[ $(health $i 2>/dev/null | jq -r .role) == LEADER ]] && { echo $i; return; }; done; echo none; }
restarts(){ kc get pod -l app=nats -o jsonpath='{.items[0].status.containerStatuses[0].restartCount}'; }
left_out(){ history_health | jq '[.lanes[].duplicates] | add'; }
bridges(){ health "$1" | jq -c '.bridges | {td: .trades.queueDropped, od: .orders.queueDropped, tg: .trades.gaps, og: .orders.gaps,
  tb: (.trades.maxBacklog // 0), ob: (.orders.maxBacklog // 0), tr: .trades.replayed, or: .orders.replayed}'; }
# kill -9 1 inside the container is a no-op (PID 1 in its own namespace): kill the JVM's host pid on its node.
kill_member(){ local pod=order-matcher-cluster-$1 node cid pid
  node=$(kc get pod "$pod" -o jsonpath='{.spec.nodeName}')
  cid=$(kc get pod "$pod" -o jsonpath='{.status.containerStatuses[?(@.name=="cluster-node")].containerID}'); cid=${cid#containerd://}
  pid=$(docker exec "$node" crictl inspect -o go-template --template '{{.info.pid}}' "$cid")
  [[ $pid =~ ^[0-9]+$ && $pid -gt 1 ]] || fail "no host pid for $pod ($pid)"
  docker exec "$node" kill -9 "$pid"; }
NNODE=$(kc get pod -l app=nats -o jsonpath='{.items[0].spec.nodeName}')
thaw(){ docker exec "$NNODE" pkill -CONT -x nats-server 2>/dev/null || true; }
trap thaw EXIT   # never leave NATS stopped

step "1. admission: business day, binary-load's accounts 9900010-17, a fresh ticker at $PX"
T=$(python3 -c 'import random;print("FR%06d" % random.randrange(10**6))')
[[ $(op /session/business-day "{\"businessDate\":\"$(TZ=America/New_York date +%F)\"}") == 200 ]] || fail "business day"
# not seq: macOS prints 9900010 as 9.90001e+06
for ((a = 9900010; a <= 9900017; a++)); do [[ $(op /risk/control/account "{\"accountId\":$a,\"enabled\":true}") == 200 ]] || fail "admit $a"; done
[[ $(op /seed "{\"accountId\":9900010,\"tickers\":\"$T\",\"price\":$PX}") == 200 ]] || fail "seed $T"
until history_caught_up; do sleep 2; done
t0=$(trades); o0=$(q "SELECT count() FROM orders"); r0=$(restarts); l0=$(leader); d0=$(left_out)
[[ $l0 != none ]] || fail "no leader"
for i in 0 1 2; do bridges $i > "$EVIDENCE/bridges-before-$i.json"; done
note "ticker $T; members' trades $t0; store orders $o0; leader member $l0; NATS restarts $r0 on $NNODE; left out $d0"

step "2. the load ($SESSIONS sessions x $RATE/s for ${SECS}s); at ${KILL_AT}s NATS freezes, the leader is killed, NATS thaws ${FREEZE_SECS}s later"
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
                  --sessions, "$SESSIONS", --rate, "$RATE", --secs, "$SECS", --phase-secs, "$PHASE_SECS", --pause-ms, "$PAUSE_MS", --out, /tmp/results.json]
        volumeMounts: [{name: load, mountPath: /load}]
      volumes: [{name: load, configMap: {name: binary-load}}]
YAML
kc wait --for=condition=Ready pod -l job-name=binary-load --timeout=120s >/dev/null
s0=$SECONDS
k(){ echo $(( ($(date +%s) + KILL_AFTER) % PHASE_SECS )); }
while ((SECONDS - s0 < KILL_AT)) || (( $(k) < 10 || $(k) > PHASE_SECS / 2 )); do sleep 1; done
# Nothing is frozen or killed unless the load is booking: a refused driver must not turn into a vacuous failover.
[[ -z $(kc get job binary-load -o jsonpath='{.status.failed}') ]] || { kc logs job/binary-load | tail -3 >&2; fail "binary-load exited before the failover"; }
[[ $(trades) -gt $t0 ]] || fail "the load booked nothing in ${KILL_AT}s"
docker exec "$NNODE" pkill -STOP -x nats-server || fail "no nats-server to freeze on $NNODE"
f0=$SECONDS; tf=$(trades); note "$(et)  NATS frozen (members' trades $tf)"
sleep "$KILL_AFTER"   # the dying leader's publishes go into the frozen socket: in flight, never stored
tk=$(trades); kill_member "$l0"; tail=$((tk - tf)); note "$(et)  killed member $l0's JVM: $tail sides applied since the freeze, none stored"
leaders=""; while ((SECONDS - f0 < FREEZE_SECS)); do l=$(leader); [[ $l == none || $leaders == *"$l"* ]] || leaders+="$l "; sleep 1; done
thaw; note "$(et)  NATS thawed after $((SECONDS - f0)) s; leaders during the freeze: ${leaders:-none}"
[[ -n $leaders && $leaders != "$l0 " ]] || fail "no new leader during the freeze ($leaders)"
until [[ -n $(kc get job binary-load -o jsonpath='{.status.succeeded}{.status.failed}') ]]; do sleep 5; done
kc logs job/binary-load | grep '^RESULT' | tee "$EVIDENCE/load-result.txt" | cut -c1-300 || fail "binary-load printed no RESULT"
amb=$(sed 's/^RESULT //' "$EVIDENCE/load-result.txt" | jq '.ambiguous')
# the killed member rejoins and catches up: all three must agree before the stream is judged
for _ in $(seq 120); do t=$(trades) || { sleep 5; continue; }; [[ $(member 1 | cut -d' ' -f3) == "$t" && $(member 2 | cut -d' ' -f3) == "$t" ]] && break; sleep 5; done
sides=$((t - t0)); note "members' trades after: $t ($sides sides booked by the load; $amb ambiguous orders)"
[[ $sides -gt 0 ]] || fail "the load booked nothing"
for m in 1 2; do [[ $(member $m | cut -d' ' -f3) == "$t" ]] || fail "member $m disagrees on trades"; done
[[ $(restarts) == "$r0" ]] || fail "NATS restarted during the run: this proof is the freeze, not FR-DRS-38's restart"

# The new leader's lanes publish what they held once the stream can be read: judge the stream after they drain.
L=$(leader); drained(){ health "$L" | jq -e '([.bridges.trades.queuedByPartition[], .bridges.orders.queuedByPartition[]] | add) == 0' >/dev/null; }
for _ in $(seq 120); do drained && break; sleep 5; done
drained || fail "the leader's lanes still hold records 10 minutes after the load: $(health "$L" | jq -c .bridges)"
note "$(et)  leader member $L's lanes drained"

step "3. the stream against the members"
for i in 0 1 2; do bridges $i > "$EVIDENCE/bridges-after-$i.json"; health $i | jq -c "{member: $i, role, bridges}" >> "$EVIDENCE/bridges.txt"; done
lost=0; gapped=0; held=0
for i in 0 1 2; do
  a=$(cat "$EVIDENCE/bridges-after-$i.json"); b=$(cat "$EVIDENCE/bridges-before-$i.json")
  lost=$((lost + $(jq '.td + .od' <<<"$a")))
  [[ $i == "$l0" ]] || gapped=$((gapped + $(jq '.tg + .og' <<<"$a") - $(jq '.tg + .og' <<<"$b")))
  held=$(( $(jq .tb <<<"$a") > held ? $(jq .tb <<<"$a") : held ))
done
g3=PASS; out=$( (g3_history "$t") 2>&1) || g3="FAIL: $out"
echo "$out" > "$EVIDENCE/g3h.txt"
o=$(q "SELECT count() FROM orders"); got=$((o - o0)); want=$((2 * sides)); d=$(left_out)
summary="$sides sides, G3h $g3; dead leader's unstored tail $tail sides; order events $got of $want ($amb ambiguous); queueDropped $lost; new gaps $gapped; trade lane held $held unpublished at most; leaders ${leaders}; left out $((d - d0))"
echo "$summary" > "$EVIDENCE/summary.txt"
if [[ ${EXPECT_LOSS:-0} == 1 ]]; then
  # FR-DRS-47's control loses at the promoted lane's queue (queueDropped); FR-DRS-49's past the follower's keep (a gap).
  [[ $g3 != PASS && ( $lost -gt 0 || $gapped -gt 0 ) ]] || fail "the control lost nothing (G3h $g3, queueDropped $lost, new gaps $gapped): the freeze did not discriminate; raise FREEZE_SECS (or KILL_AFTER)"
  echo; echo "KIND-BRIDGE-FAILOVER-AT-RATE CONTROL LOST ($summary) evidence $EVIDENCE"; exit 0
fi
[[ $g3 == PASS ]] || fail "G3h: $out"
[[ $lost == 0 ]] || fail "a lane lost $lost record(s) past its ring ($summary)"
[[ $gapped == 0 ]] || fail "a lane counted $gapped new gap(s) ($summary)"
if ((KILL_AFTER > 2)); then
  [[ $tail -gt $OLD_QUEUE ]] || fail "the dead leader's unstored tail ($tail sides) fit in the $OLD_QUEUE a follower kept before FR-DRS-49; raise KILL_AFTER or RATE ($summary)"
else [[ $held -gt $OLD_QUEUE ]] || fail "the trade lane never held more than $OLD_QUEUE: the freeze was shorter than the old queue at this rate; raise FREEZE_SECS or RATE ($summary)"; fi
[[ $((d - d0)) == 0 ]] || fail "history-feed left out $((d - d0)) repeated message(s): a record was stored twice ($summary)"
if [[ $amb == 0 ]]; then [[ $got == "$want" ]] || fail "order events on the stream: $got, the load's sides need $want"; fi
echo; echo "KIND-BRIDGE-FAILOVER-AT-RATE PASS ($summary) evidence $EVIDENCE"
