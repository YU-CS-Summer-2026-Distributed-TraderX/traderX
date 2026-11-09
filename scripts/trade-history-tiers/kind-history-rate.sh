#!/usr/bin/env bash
# FR-THT-10 at the effect end on kind: history-feed drains a production-shaped backlog and G3h passes on its scaled wait.
# The rig is production's default shape (kind-yu18-rig.py --history --binary --partitions 1: one partition, bare
# subjects, history-feed at its production defaults). The load is production's own driver, binary-load.mjs, on the
# gateway's binary port. history-feed is held at 0 through the load, so the whole load is the backlog, as e1108b left it
# (2026-11-08: 3.07M of 9.66M trades stored, 16.2M order events queued).
#
#   arm A (OLD_IMAGE set): the pre-fix image on the backlog for 60 s; its rate is recorded and must be under the floor
#   arm B: the image under test drains the rest; G3h (rig.sh's g3_history, sourced) checks every side once against the
#          members' trades, inside history_wait_secs; the orders table holds each stream message once
#
#   CTX=kind-<c> NS=traderx [TICKER_PX=50 SECS=1500 SESSIONS=4 RATE=375 PHASE_SECS=60 PAUSE_MS=10000 OLD_IMAGE=...] \
#     bash scripts/trade-history-tiers/kind-history-rate.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${CTX:?set CTX (a kind context)}"; NS=${NS:-traderx}
( . "$ROOT/scripts/proofs/lib-yu18.sh"; disposable_rig ) || { echo "disposable rig only: this stops history-feed" >&2; exit 1; }
EVIDENCE=${EVIDENCE:-${TMPDIR:-/tmp}/kind-history-rate-$$}; mkdir -p "$EVIDENCE"
RIG_LIB=1 . "$ROOT/scripts/gcp-compsci/rig/rig.sh"
# kind answers ~1,600 orders/s: 4 x 375 for 25 minutes is ~2.2M sides
SECS=${SECS:-1500}; SESSIONS=${SESSIONS:-4}; RATE=${RATE:-375}; PX=${TICKER_PX:-50}; FLOOR=${HISTORY_DRAIN_FLOOR:-20000}
NODE_IMAGE=${NODE_IMAGE:-node:20-alpine}
fail(){ echo "[FAIL] $*" >&2; exit 1; }
q(){ kc exec sts/clickhouse -c clickhouse -- clickhouse-client -q "$1"; }
# The operator token goes in on stdin (curl -H @-), never on argv.
op(){ kc get secret auth-secrets -o jsonpath='{.data.risk-control-token}' | base64 -d \
        | awk '{print "X-Risk-Control-Token: " $0; print "X-Risk-Operator: kind-history-rate"; print "Content-Type: application/json"}' \
        | kc exec -i deploy/trade-processor -- curl -s -m 30 -o /dev/null -w '%{http_code}' -H @- -X POST "http://order-matcher:18110$1" -d "$2"; }
trades(){ member 0 | cut -d' ' -f3; }
rows(){ q "SELECT (SELECT count() FROM trades) + (SELECT count() FROM orders)"; }

step "1. admission: business day, binary-load's accounts 9900010-17, a fresh ticker at $PX"
T=$(python3 -c 'import random;print("HR%06d" % random.randrange(10**6))')
[[ $(op /session/business-day "{\"businessDate\":\"$(TZ=America/New_York date +%F)\"}") == 200 ]] || fail "business day"
# not seq: macOS prints 9900010 as 9.90001e+06
for ((a = 9900010; a <= 9900017; a++)); do [[ $(op /risk/control/account "{\"accountId\":$a,\"enabled\":true}") == 200 ]] || fail "admit $a"; done
[[ $(op /seed "{\"accountId\":9900010,\"tickers\":\"$T\",\"price\":$PX}") == 200 ]] || fail "seed $T"
t0=$(trades); note "ticker $T; members' trades before: $t0"

step "2. history-feed held at 0; the load ($SESSIONS sessions x $RATE/s for ${SECS}s) builds the backlog"
kc scale deploy/history-feed --replicas=0 >/dev/null; kc wait --for=delete pod -l app=history-feed --timeout=120s >/dev/null 2>&1 || true
rows_before=$(rows)
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
until [[ -n $(kc get job binary-load -o jsonpath='{.status.succeeded}{.status.failed}') ]]; do sleep 5; done
[[ $(kc get job binary-load -o jsonpath='{.status.succeeded}') == 1 ]] || { kc logs job/binary-load | tail -5 >&2; fail "binary-load did not complete clean"; }
kc logs job/binary-load | grep '^RESULT' | tee "$EVIDENCE/load-result.txt"
t=$(trades); sides=$((t - t0)); note "members' trades after: $t ($sides sides booked by the load)"
[[ $sides -ge 1000000 ]] || fail "the load booked $sides sides: under a million is not a production-shaped backlog"
for m in 1 2; do [[ $(member $m | cut -d' ' -f3) == "$t" ]] || fail "member $m disagrees on trades"; done

lanes(){ history_health | jq -r '[.lanes[] | "\(.durable) pending=\(.pending) rows=\(.rows)"] | join("; ")'; }
up(){ kc set image deploy/history-feed history-feed="$1" >/dev/null; kc scale deploy/history-feed --replicas=1 >/dev/null
      kc rollout status deploy/history-feed --timeout=300s >/dev/null; until history_health >/dev/null 2>&1; do sleep 2; done; }
image=$(kc get deploy history-feed -o jsonpath='{.spec.template.spec.containers[0].image}')
if [[ -n ${OLD_IMAGE:-} ]]; then
  step "3A. the pre-fix image ($OLD_IMAGE) on the backlog for 60 s"
  up "$OLD_IMAGE"; r0=$(rows); sleep 60; r1=$(rows); old_rate=$(((r1 - r0) / 60))
  note "$old_rate rows/s ($(lanes))"; echo "old $OLD_IMAGE $old_rate rows/s" > "$EVIDENCE/arm-a.txt"
  [[ $old_rate -lt $FLOOR ]] || fail "the pre-fix image drained at $old_rate rows/s, at or above the floor: the control does not discriminate"
  kc scale deploy/history-feed --replicas=0 >/dev/null; kc wait --for=delete pod -l app=history-feed --timeout=120s >/dev/null 2>&1 || true
fi

step "3B. $image drains the backlog; G3h on its scaled wait"
up "$image"; backlog=$(history_health | jq '[.lanes[].pending | select(. > 0)] | add // 0')
note "backlog $backlog messages; G3h's wait $(history_wait_secs) s"
r0=$(rows); s0=$SECONDS
until history_caught_up; do sleep 1; done
drain=$((SECONDS - s0)); r1=$(rows); note "drained $((r1 - r0)) rows in ${drain} s ($(( (r1 - r0) / (drain > 0 ? drain : 1) )) rows/s)"
out=$( (g3_history "$t") 2>&1) || fail "G3h: $out"
echo "$out" | tee "$EVIDENCE/g3h.txt" | grep -F "G3h ok" || fail "G3h printed no verdict: $out"
o=$(q "SELECT count(), uniqExact(part, seq) FROM orders FORMAT TSV")
[[ $(cut -f1 <<<"$o") == $(cut -f2 <<<"$o") ]] || fail "orders holds a duplicated stream message: $o"
# The stream's own count: at one partition TRADERX_FEED holds only /trades and /orders, so the store holds exactly it.
stream=$(kc exec deploy/nats -- wget -qO- 'http://localhost:8222/jsz?streams=true' \
  | jq '[.account_details[].stream_detail[] | select(.name == "TRADERX_FEED") | .state.messages] | add // -1')
stored=$(rows); [[ $stored == "$stream" ]] || fail "the store holds $stored rows, the stream $stream messages"
note "store = stream: $stored rows, $stream messages on TRADERX_FEED"
echo "$t trades, $sides by the load; store = stream $stored; backlog $backlog; drained in ${drain}s; orders $o; ${old_rate:+old image $old_rate rows/s}" > "$EVIDENCE/summary.txt"
echo; echo "KIND-HISTORY-RATE PASS ($(cat "$EVIDENCE/summary.txt")) evidence $EVIDENCE"
