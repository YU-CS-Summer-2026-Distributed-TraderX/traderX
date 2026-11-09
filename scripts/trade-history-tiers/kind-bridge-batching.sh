#!/usr/bin/env bash
# FR-DRS-41 at the effect end on kind: the bridges publish their drains as batched messages under production's default
# shape (kind-yu18-rig.py --history --binary --partitions 1), and every reader still holds every record once. A binary
# load runs with everything up; meanwhile the leader's per-thread CPU and the NATS process's CPU are sampled from /proc
# (no tools in the images). At the end: G3h (rig.sh's g3_history, sourced), the orders table holds each event once, the
# store holds exactly the records the bridges published, and the stream exactly the messages that carried them.
#
# Run it twice for the A/B: as rendered (BRIDGE_BATCH_MAX unset: 64) and with the members at BRIDGE_BATCH_MAX=1 (the
# one-record wire): kubectl set env sts/order-matcher-cluster BRIDGE_BATCH_MAX=1, then wait for the roll.
#
#   CTX=kind-<c> [NS=traderx SECS=120 SESSIONS=4 RATE=375 LABEL=on] bash scripts/trade-history-tiers/kind-bridge-batching.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${CTX:?set CTX (a kind context)}"; NS=${NS:-traderx}
( . "$ROOT/scripts/proofs/lib-yu18.sh"; disposable_rig ) || { echo "disposable rig only: this drives load at the rig" >&2; exit 1; }
LABEL=${LABEL:-on}
EVIDENCE=${EVIDENCE:-${TMPDIR:-/tmp}/kind-bridge-batching-$LABEL-$$}; mkdir -p "$EVIDENCE"
RIG_LIB=1 . "$ROOT/scripts/gcp-compsci/rig/rig.sh"
SECS=${SECS:-120}; SESSIONS=${SESSIONS:-4}; RATE=${RATE:-375}; PX=${TICKER_PX:-50}
fail(){ echo "[FAIL] $*" >&2; exit 1; }
q(){ kc exec sts/clickhouse -c clickhouse -- clickhouse-client -q "$1"; }
# The operator token goes in on stdin (curl -H @-), never on argv.
op(){ kc get secret auth-secrets -o jsonpath='{.data.risk-control-token}' | base64 -d \
        | awk '{print "X-Risk-Control-Token: " $0; print "X-Risk-Operator: kind-bridge-batching"; print "Content-Type: application/json"}' \
        | kc exec -i deploy/trade-processor -- curl -s -m 30 -o /dev/null -w '%{http_code}' -H @- -X POST "http://order-matcher:18110$1" -d "$2"; }
trades(){ member 0 | cut -d' ' -f3; }
counts(){ q "SELECT (SELECT count() FROM trades), (SELECT count() FROM orders) FORMAT TSV" | tr '\t' ' '; }
stream(){ kc exec deploy/nats -- wget -qO- 'http://localhost:8222/jsz?streams=true' \
  | jq '[.account_details[].stream_detail[] | select(.name == "TRADERX_FEED") | .state.messages] | add // -1'; }
# The members' bridge counters, summed over both bridges, from the leader (the only publisher).
bridges(){ kc exec "order-matcher-cluster-$1" -c cluster-node -- wget -qO- localhost:8080/health \
  | jq -c '[.bridges | .. | objects | select(has("messages"))] | {published: ([.[].published] | add), messages: ([.[].messages] | add), acked: ([.[].acked] | add), rejected: ([.[].rejected] | add)}'; }
leader(){ for m in 0 1 2; do [[ $(kc exec "order-matcher-cluster-$m" -c cluster-node -- wget -qO- localhost:8080/health | jq -r '.role // .leader // empty') =~ ^(LEADER|true)$ ]] && { echo $m; return; }; done
          # no role field: the leader is the member whose bridges publish
          for m in 0 1 2; do [[ $(bridges $m | jq .published) -gt 0 ]] && { echo $m; return; }; done; echo 0; }
# Per-thread CPU ticks (user+system, 100 Hz) of the JVM in a member pod: "<comm> <ticks>" per thread.
threads(){ kc exec "order-matcher-cluster-$1" -c cluster-node -- sh -c '
  for p in /proc/[0-9]*; do grep -q "^java" "$p/comm" 2>/dev/null && { J=${p#/proc/}; break; }; done
  for t in /proc/$J/task/*; do c=$(cat "$t/comm"); set -- $(cat "$t/stat" | sed "s/.*) //"); echo "$c $(( ${12} + ${13} ))"; done' 2>/dev/null; }
# The NATS process's CPU ticks.
nats_ticks(){ kc exec deploy/nats -- sh -c 'set -- $(sed "s/.*) //" /proc/1/stat); echo $(( ${12} + ${13} ))'; }

step "1. admission and the rig's counters before the load"
T=$(python3 -c 'import random;print("BB%06d" % random.randrange(10**6))')
[[ $(op /session/business-day "{\"businessDate\":\"$(TZ=America/New_York date +%F)\"}") == 200 ]] || fail "business day"
for ((a = 9900010; a <= 9900017; a++)); do [[ $(op /risk/control/account "{\"accountId\":$a,\"enabled\":true}") == 200 ]] || fail "admit $a"; done
[[ $(op /seed "{\"accountId\":9900010,\"tickers\":\"$T\",\"price\":$PX}") == 200 ]] || fail "seed $T"
until history_caught_up; do sleep 2; done
t0=$(trades); c0=$(counts); s0=$(stream); bL=$(bridges "$(leader)")
batch=$(kc get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="BRIDGE_BATCH_MAX")].value}')
note "BRIDGE_BATCH_MAX=${batch:-unset (64)}; members' trades $t0; store (trades orders) $c0; stream $s0 messages"

step "2. ${SECS}s load ($SESSIONS sessions x $RATE/s), sampling the leader's threads and NATS every 10 s"
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
        image: node:20-alpine
        imagePullPolicy: IfNotPresent
        command: [node, /load/binary-load.mjs, --resolve, "http://order-matcher:18110", --ticker, "$T", --px, "$PX",
                  --sessions, "$SESSIONS", --rate, "$RATE", --secs, "$SECS", --phase-secs, "60", --pause-ms, "3000", --out, /tmp/results.json]
        volumeMounts: [{name: load, mountPath: /load}]
      volumes: [{name: load, configMap: {name: binary-load}}]
YAML
sleep 15
L=$(leader); note "leader: member $L"
threads "$L" > "$EVIDENCE/threads-start.txt"; n0=$(nats_ticks); w0=$(date +%s); b0=$(bridges "$L")
until [[ -n $(kc get job binary-load -o jsonpath='{.status.succeeded}{.status.failed}') ]]; do
  printf '%s\t%s\t%s\t%s\n' "$(date +%s)" "$(trades)" "$(stream)" "$(bridges "$L")" >> "$EVIDENCE/load.tsv"; sleep 10
done
threads "$L" > "$EVIDENCE/threads-end.txt"; n1=$(nats_ticks); w1=$(date +%s); b1=$(bridges "$L")
[[ $(kc get job binary-load -o jsonpath='{.status.succeeded}') == 1 ]] || { kc logs job/binary-load | tail -5 >&2; fail "binary-load did not complete clean"; }
secs=$((w1 - w0))
# Per-thread CPU over the window, in cores, for the bridge lanes, jnats and the engine's own threads.
join -j1 <(sort "$EVIDENCE/threads-start.txt") <(sort "$EVIDENCE/threads-end.txt") 2>/dev/null \
  | awk -v s="$secs" '{printf "%-16s %6.3f\n", $1, ($3 - $2) / 100 / s}' | sort -k2 -nr > "$EVIDENCE/threads-cores.txt"
recs=$(( $(jq .published <<<"$b1") - $(jq .published <<<"$b0") )); msgs=$(( $(jq .messages <<<"$b1") - $(jq .messages <<<"$b0") ))
nats_cores=$(awk -v a="$n0" -v b="$n1" -v s="$secs" 'BEGIN { printf "%.3f", (b - a) / 100 / s }')
lane_cores=$(awk '/nats-brid/ { c += $2 } END { printf "%.3f", c }' "$EVIDENCE/threads-cores.txt")
jnats_cores=$(awk '/^nats/ { c += $2 } END { printf "%.3f", c }' "$EVIDENCE/threads-cores.txt")
per_msg=$(awk -v r="$recs" -v m="$msgs" 'BEGIN { if (m > 0) printf "%.2f", r / m; else print 0 }')   # macOS awk: no ?: in printf args
note "window ${secs}s: $recs records in $msgs messages ($per_msg per message); leader lane threads $lane_cores cores, jnats threads $jnats_cores cores; NATS $nats_cores cores"
head -12 "$EVIDENCE/threads-cores.txt" | sed 's/^/   /'

step "3. the far end: G3h, each order event once, store = records, stream = messages"
end=$((SECONDS + 120)); until [[ $(member 1 | cut -d' ' -f3) == $(trades) && $(member 2 | cut -d' ' -f3) == $(trades) ]]; do
  [ $SECONDS -lt $end ] || fail "the members disagree on trades"; sleep 5; done
t=$(trades)
until history_caught_up; do sleep 2; done
out=$( (g3_history "$t") 2>&1) || fail "G3h: $out"
echo "$out" | tee "$EVIDENCE/g3h.txt" | grep -F "G3h ok" || fail "G3h printed no verdict: $out"
o=$(q "SELECT count(), if(max(cseq) > 0, uniqExact(cseq, ordinal), uniqExact(part, seq, order_ref, status, last_qty, remaining)) FROM orders FORMAT TSV")
[[ $(cut -f1 <<<"$o") == $(cut -f2 <<<"$o") ]] || fail "orders holds a duplicated event: $o"
# Counted from before the load: a member's counters start at its process start, the store and stream keep earlier runs.
c1=$(counts); s1=$(stream); b=$(bridges "$L")
rows=$(( ${c1% *} + ${c1#* } - ${c0% *} - ${c0#* } )); published=$(( $(jq .published <<<"$b") - $(jq .published <<<"$bL") )); messages=$(( $(jq .messages <<<"$b") - $(jq .messages <<<"$bL") ))
[[ $rows == "$published" ]] || fail "the store gained $rows rows, the bridges published $published records (in $messages messages; stream +$((s1 - s0)))"
[[ $((s1 - s0)) == "$messages" ]] || fail "the stream gained $((s1 - s0)) messages, the bridges published $messages"
[[ $(jq .rejected <<<"$b") == 0 && $(jq .acked <<<"$b") == "$(jq .published <<<"$b")" ]] || fail "receipts: $b"
echo "$LABEL: BRIDGE_BATCH_MAX=${batch:-64}; $t sides ($((t - t0)) by the load); store +$rows rows = $published records in $messages messages = stream growth; $recs records in $msgs messages ($per_msg per message) in ${secs}s; leader lanes $lane_cores + jnats $jnats_cores cores; NATS $nats_cores cores; orders $o" > "$EVIDENCE/summary.txt"
echo; echo "KIND-BRIDGE-BATCHING PASS ($(cat "$EVIDENCE/summary.txt")) evidence $EVIDENCE"
