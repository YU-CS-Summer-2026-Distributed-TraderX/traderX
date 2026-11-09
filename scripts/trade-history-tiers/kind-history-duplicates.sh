#!/usr/bin/env bash
# FR-THT-11 at the effect end on kind: the stream holds trade sides and order events twice, the store holds each once,
# and G3h passes. The rig is production's default shape (kind-yu18-rig.py --history --binary --partitions 1). A short
# binary load books the sides. Then N stored /trades and N stored /orders messages are read back off the stream and
# published again on their own subjects, byte for byte, which is what a bridge catch-up leaves (FR-DRS-37/38: at least
# once): first with history-feed running, then again across its restart.
#
#   PASS: history-feed left out exactly the republished trade sides (its /health `duplicates`), the stream grew by what
#   was republished, the store's trades did not move, and G3h (rig.sh's g3_history, sourced) finds sides 1..t, each once.
#   Order events: with positions (consensusSequence, outputOrdinal: an epoch-v1 run) they are left out the same way.
#   Without (the legacy id scheme, which the production render and this rig run) there is nothing to tell a repeat by:
#   the bridges do not republish them, and the ones this script republishes are stored. The script says which it saw.
#
#   CTX=kind-<c> [NS=traderx N=2000 SECS=60 SESSIONS=4 RATE=375] bash scripts/trade-history-tiers/kind-history-duplicates.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${CTX:?set CTX (a kind context)}"; NS=${NS:-traderx}
( . "$ROOT/scripts/proofs/lib-yu18.sh"; disposable_rig ) || { echo "disposable rig only: this publishes onto the capture stream" >&2; exit 1; }
EVIDENCE=${EVIDENCE:-${TMPDIR:-/tmp}/kind-history-duplicates-$$}; mkdir -p "$EVIDENCE"
RIG_LIB=1 . "$ROOT/scripts/gcp-compsci/rig/rig.sh"
N=${N:-2000}; SECS=${SECS:-60}; SESSIONS=${SESSIONS:-4}; RATE=${RATE:-375}; PX=${TICKER_PX:-50}
fail(){ echo "[FAIL] $*" >&2; exit 1; }
q(){ kc exec sts/clickhouse -c clickhouse -- clickhouse-client -q "$1"; }
# The operator token goes in on stdin (curl -H @-), never on argv.
op(){ kc get secret auth-secrets -o jsonpath='{.data.risk-control-token}' | base64 -d \
        | awk '{print "X-Risk-Control-Token: " $0; print "X-Risk-Operator: kind-history-duplicates"; print "Content-Type: application/json"}' \
        | kc exec -i deploy/trade-processor -- curl -s -m 30 -o /dev/null -w '%{http_code}' -H @- -X POST "http://order-matcher:18110$1" -d "$2"; }
trades(){ member 0 | cut -d' ' -f3; }
counts(){ q "SELECT (SELECT count() FROM trades), (SELECT count() FROM orders) FORMAT TSV" | tr '\t' ' '; }
stream(){ kc exec deploy/nats -- wget -qO- 'http://localhost:8222/jsz?streams=true' \
  | jq '[.account_details[].stream_detail[] | select(.name == "TRADERX_FEED") | .state.'"$1"'] | add // -1'; }
left_out(){ history_health | jq '[.lanes[].duplicates] | add'; }
# About N stored messages of one subject, from stream sequence $2 on, published again. Prints how many, measured as
# the stream's own growth (nothing else publishes now): the pull subscription ends a little over or under --count.
# --raw prints one body per line.
again(){ local m0; m0=$(stream messages)
  kc run "dup-$RANDOM" --rm -i --restart=Never --quiet --image=natsio/nats-box:latest --image-pull-policy=IfNotPresent -- sh -c \
    "nats -s nats://nats:4222 sub '$1' --stream TRADERX_FEED --start-sequence $2 --count $N --raw 2>/dev/null | nats -s nats://nats:4222 pub '$1' --send-on newline" >/dev/null 2>&1
  echo $(( $(stream messages) - m0 )); }
settled(){ local want=$1 end=$((SECONDS + 300)); until history_caught_up && [[ $(left_out) == "$want" ]]; do
             [ $SECONDS -lt $end ] || fail "history-feed left out $(left_out) messages, not $want"; sleep 2; done; }

step "1. admission and a ${SECS}s load ($SESSIONS sessions x $RATE/s)"
T=$(python3 -c 'import random;print("HD%06d" % random.randrange(10**6))')
[[ $(op /session/business-day "{\"businessDate\":\"$(TZ=America/New_York date +%F)\"}") == 200 ]] || fail "business day"
# not seq: macOS prints 9900010 as 9.90001e+06
for ((a = 9900010; a <= 9900017; a++)); do [[ $(op /risk/control/account "{\"accountId\":$a,\"enabled\":true}") == 200 ]] || fail "admit $a"; done
[[ $(op /seed "{\"accountId\":9900010,\"tickers\":\"$T\",\"price\":$PX}") == 200 ]] || fail "seed $T"
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
                  --sessions, "$SESSIONS", --rate, "$RATE", --secs, "$SECS", --phase-secs, "60", --pause-ms, "10000", --out, /tmp/results.json]
        volumeMounts: [{name: load, mountPath: /load}]
      volumes: [{name: load, configMap: {name: binary-load}}]
YAML
until [[ -n $(kc get job binary-load -o jsonpath='{.status.succeeded}{.status.failed}') ]]; do sleep 5; done
[[ $(kc get job binary-load -o jsonpath='{.status.succeeded}') == 1 ]] || { kc logs job/binary-load | tail -5 >&2; fail "binary-load did not complete clean"; }
until history_caught_up; do sleep 2; done
t=$(trades); before=$(counts); last=$(stream last_seq); msgs=$(stream messages); base=$(left_out)
ct=${before% *}; co=${before#* }
[[ $t -ge $((4 * N)) ]] || fail "the load booked $t sides: too few to republish $N twice"
[[ $ct == "$t" ]] || fail "before any repeat the store holds $ct trade sides against the members' $t"
positioned=$(q "SELECT max(cseq) > 0 FROM orders")
note "members' trades $t; store (trades orders) $before; stream $msgs messages, last seq $last; order events $([[ $positioned == 1 ]] && echo "carry positions" || echo "carry no position (legacy ids): repeats of them are stored")"

step "2. about $N /trades and $N /orders messages published again, history-feed running"
# /trades is a third of the stream: 4N sequences back hold more than N of each subject.
rt=$(again /trades $((last - 4 * N))); ro=$(again /orders $((last - 4 * N)))
[[ $rt -gt $((N / 2)) && $ro -gt $((N / 2)) ]] || fail "republished only $rt /trades and $ro /orders messages"
out1=$((rt + positioned * ro)); kept1=$(((1 - positioned) * ro))
settled $((base + out1))
[[ $(stream messages) == $((msgs + rt + ro)) ]] || fail "the stream holds $(stream messages) messages, not $((msgs + rt + ro)): the repeats were not published"
[[ $(counts) == "$ct $((co + kept1))" ]] || fail "the store holds '$(counts)', not '$ct $((co + kept1))'"
note "republished $rt sides and $ro order events; left out $out1; trades did not move; orders +$kept1"

step "3. about $N more of each, across a history-feed restart"
kc rollout restart deploy/history-feed >/dev/null; kc rollout status deploy/history-feed --timeout=300s >/dev/null
until history_health >/dev/null 2>&1; do sleep 2; done
rt2=$(again /trades $((last - 8 * N))); ro2=$(again /orders $((last - 8 * N)))
[[ $rt2 -gt $((N / 2)) && $ro2 -gt $((N / 2)) ]] || fail "republished only $rt2 /trades and $ro2 /orders messages"
# The store's cursor sits behind the repeats it left out in step 2 (nothing of them was stored), so the new process
# reads those again too.
out2=$((out1 + rt2 + positioned * ro2)); kept2=$((kept1 + (1 - positioned) * ro2))
settled $out2
[[ $(stream messages) == $((msgs + rt + ro + rt2 + ro2)) ]] || fail "the stream holds $(stream messages) messages, not $((msgs + rt + ro + rt2 + ro2))"
[[ $(counts) == "$ct $((co + kept2))" ]] || fail "the store holds '$(counts)', not '$ct $((co + kept2))'"
out=$( (g3_history "$t") 2>&1) || fail "G3h: $out"
echo "$out" | tee "$EVIDENCE/g3h.txt" | grep -F "G3h ok" || fail "G3h printed no verdict: $out"
echo "$t sides, store trades $ct throughout; republished $((rt + rt2)) sides and $((ro + ro2)) order events; left out $out1, then $out2 after the restart; order events $([[ $positioned == 1 ]] && echo "positioned, left out" || echo "unpositioned, $kept2 repeats stored")" > "$EVIDENCE/summary.txt"
echo; echo "KIND-HISTORY-DUPLICATES PASS ($(cat "$EVIDENCE/summary.txt")) evidence $EVIDENCE"
