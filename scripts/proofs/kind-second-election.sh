#!/usr/bin/env bash
# RI38 (run D and GKE run E3, 2026-11-10): a leader killed at full trading was followed 4-5 s later by a SECOND
# election, at run D's timeouts (heartbeat 50 ms, heartbeat timeout 200 ms, election timeout 100 ms). This counts the
# elections after each of KILLS leader kills under load, on the survivors' own ELECTION-PHASE log lines (the rig's
# failover readers stop at the first new leader, so they miss a re-won second election), and samples every member's
# cgroup (cpu.stat, memory.events) and PSI each second, so a stall has a measured cause. Report only: no verdict. One
# arm per run; the arms differ in the render (the caller's), e.g. the leader-only kdb tap on or off, and BURST_MB:
# that many MB of buffered writes onto the new leader's /data (the Archive's volume) as soon as it leads, standing in
# for E3's tap writing 256 MB in ~5 s, which kind's rate cannot produce.
#
#   CTX=kind-<c> NS=traderx ARM=<label> [KILLS=3 SESSIONS=4 RATE=400 BURST_MB=0 BASELINE_SECS=120 OBSERVE_SECS=20] \
#     bash scripts/proofs/kind-second-election.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${CTX:?set CTX (a kind context)}"; NS=${NS:-traderx}; : "${ARM:?set ARM (a label for this arm)}"
[[ $CTX == kind-* ]] || { echo "kind only: this kills members on their nodes" >&2; exit 1; }
EVIDENCE=${EVIDENCE:-${TMPDIR:-/tmp}/kind-second-election-$$}; mkdir -p "$EVIDENCE"
RIG_LIB=1 . "$ROOT/scripts/gcp-compsci/rig/rig.sh"
KILLS=${KILLS:-3}; SESSIONS=${SESSIONS:-4}; RATE=${RATE:-400}; PX=${TICKER_PX:-50}; BURST_MB=${BURST_MB:-0}
BASELINE_SECS=${BASELINE_SECS:-120}; OBSERVE_SECS=${OBSERVE_SECS:-20}
# Kills land 10-60 s into a load phase, two phases apart, so the killed member rejoins before the next one and
# binary-load never meets a phase boundary with an order unanswered.
PHASE_SECS=120; PAUSE_MS=20000; SECS=$((BASELINE_SECS + KILLS * 2 * PHASE_SECS + PHASE_SECS))
NODE_IMAGE=${NODE_IMAGE:-node:20-alpine}
fail(){ echo "[FAIL] $*" >&2; exit 1; }
op(){ kc get secret auth-secrets -o jsonpath='{.data.risk-control-token}' | base64 -d \
        | awk '{print "X-Risk-Control-Token: " $0; print "X-Risk-Operator: kind-second-election"; print "Content-Type: application/json"}' \
        | kc exec -i deploy/trade-processor -- curl -s -m 30 -o /dev/null -w '%{http_code}' -H @- -X POST "http://order-matcher:18110$1" -d "$2"; }
# admitted <path> <body>: 200 from op, retried: a loaded laptop times one out now and then.
admitted(){ local _; for _ in 1 2 3 4 5; do [[ $(op "$1" "$2") == 200 ]] && return 0; sleep 5; done; return 1; }
health(){ kc exec "order-matcher-cluster-$1" -c cluster-node -- wget -qO- -T 2 localhost:8080/health; }
role(){ health "$1" 2>/dev/null | jq -r .role 2>/dev/null || echo down; }
leader(){ local i; for i in "$@"; do [[ $(role "$i") == LEADER ]] && { echo "$i"; return; }; done; echo none; }
now_ms(){ python3 -c 'import time; print(int(time.time() * 1000))'; }
kill_member(){ local pod=order-matcher-cluster-$1 node cid pid
  node=$(kc get pod "$pod" -o jsonpath='{.spec.nodeName}')
  cid=$(kc get pod "$pod" -o jsonpath='{.status.containerStatuses[?(@.name=="cluster-node")].containerID}'); cid=${cid#containerd://}
  pid=$(docker exec "$node" crictl inspect -o go-template --template '{{.info.pid}}' "$cid")
  [[ $pid =~ ^[0-9]+$ && $pid -gt 1 ]] || fail "no host pid for $pod ($pid)"
  docker exec "$node" kill -9 "$pid"; }
# sample <tag> <member> <secs>: cgroup and PSI each second, inside the member (one exec), in the background.
sample(){ kc exec "order-matcher-cluster-$2" -c cluster-node -- sh -c "i=0; while [ \$i -lt $3 ]; do
    echo \"T \$(cut -d' ' -f1 /proc/uptime)\"; cat /sys/fs/cgroup/cpu.stat /sys/fs/cgroup/memory.events
    for p in cpu io memory; do echo \"\$p \$(head -1 /proc/pressure/\$p)\"; done; i=\$((i+1)); sleep 1; done" \
    > "$EVIDENCE/sample-$1-m$2.txt" 2>&1 & }
# inits <sinceMs> <member...>: ELECTION-PHASE INIT times on those members' current containers, as ms after sinceMs.
inits(){ local s=$1; shift; local i
  for i in "$@"; do kc logs "order-matcher-cluster-$i" -c cluster-node --since=15m 2>/dev/null \
    | grep -o 'ELECTION-PHASE state=INIT atMs=[0-9]*' | cut -d= -f3; done \
    | awk -v s="$s" '$1 >= s { print $1 - s }' | sort -n; }
# elections: INIT times within 1 s of each other are one election (each survivor logs its own INIT).
elections(){ awk 'NR == 1 || $1 - p > 1000 { printf "%s+%dms", (n++ ? " " : ""), $1 } { p = $1 } END { print "" }'; }
# throttled <file>: the cgroup's throttled time (ms) and memory.high/max events across the sample.
throttled(){ awk '/^throttled_usec/ { t[n++] = $2 } /^high / { h[m++] = $2 } /^max / { x[k++] = $2 }
  END { printf "throttled %d ms, memory.high %d, memory.max %d", (t[n-1] - t[0]) / 1000, h[m-1] - h[0], x[k-1] - x[0] }' "$1"; }
# pauses <sinceMs> <log>: the three longest JVM safepoints (-Xlog:safepoint with timemillis) after sinceMs.
pauses(){ awk -v s="$1" 'match($0, /^\[[0-9]+ms\]/) && /Total: / { t = substr($0, 2, RLENGTH - 4) + 0; if (t < s) next
    n = $0; sub(/.*Total: /, "", n); sub(/ ns.*/, "", n); r = $0; sub(/.*Safepoint "/, "", r); sub(/".*/, "", r)
    printf "%d %d %s\n", n / 1000000, t - s, r }' "$2" | sort -rn | head -3 | awk '{ printf "%s%s ms at +%d ms (%s)", (NR > 1 ? ", " : ""), $1, $2, $3 }'; }
psi(){ awk '$1 == "'"$2"'" { split($3, a, "="); if (a[2] + 0 > m) m = a[2] + 0 } END { printf "%s avg10 max %.2f", "'"$2"'", m }' "$1"; }

step "1. arm $ARM: admission, binary-load's accounts 9900010-17, a fresh ticker at $PX"
T=$(python3 -c 'import random;print("SE%06d" % random.randrange(10**6))')
admitted /session/business-day "{\"businessDate\":\"$(TZ=America/New_York date +%F)\"}" || fail "business day"
for ((a = 9900010; a <= 9900017; a++)); do admitted /risk/control/account "{\"accountId\":$a,\"enabled\":true}" || fail "admit $a"; done
admitted /seed "{\"accountId\":9900010,\"tickers\":\"$T\",\"price\":$PX}" || fail "seed $T"
for i in 0 1 2; do kc get pod "order-matcher-cluster-$i" -o json | jq -c "{member: $i, env: [.spec.containers[0].env[] | select(.name | test(\"HEARTBEAT|ELECTION|KDB_TAP|JAVA_TOOL\")) | {(.name): .value}] | add, resources: .spec.containers[0].resources}"; done | tee "$EVIDENCE/members.txt"

step "2. the load ($SESSIONS sessions x $RATE/s for ${SECS}s); ${BASELINE_SECS}s with no kill first"
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
b0=$(now_ms); for i in 0 1 2; do sample baseline "$i" "$BASELINE_SECS"; done
sleep "$BASELINE_SECS"; wait
t0=$(member 0 | cut -d' ' -f3); [[ $t0 -gt 0 ]] || fail "the load booked nothing in ${BASELINE_SECS}s"
base=$(inits "$b0" 0 1 2 | elections)
note "baseline: members' trades $t0; elections with no kill: ${base:-none}"
for i in 0 1 2; do note "  member $i: $(throttled "$EVIDENCE/sample-baseline-m$i.txt"); $(psi "$EVIDENCE/sample-baseline-m$i.txt" cpu); $(psi "$EVIDENCE/sample-baseline-m$i.txt" io)"; done
echo "baseline ${base:-none}" > "$EVIDENCE/elections.txt"

burst=""; ((BURST_MB == 0)) || burst=", $BURST_MB MB written onto the new leader's /data"
step "3. $KILLS leader kills, ${OBSERVE_SECS}s observed after each$burst"
for ((k = 1; k <= KILLS; k++)); do
  # two phases after the last kill (or the baseline), 10-60 s into a phase
  s0=$SECONDS; while ((SECONDS - s0 < (k == 1 ? 0 : PHASE_SECS))) || (( $(date +%s) % PHASE_SECS < 10 || $(date +%s) % PHASE_SECS > 60 )); do sleep 1; done
  # An election can strand an order across a phase boundary, which ends binary-load: report the kills so far.
  [[ -z $(kc get job binary-load -o jsonpath='{.status.failed}{.status.succeeded}') ]] || { note "binary-load ended before kill $k: stopping at $((k - 1))"; break; }
  l=$(leader 0 1 2); [[ $l != none ]] || fail "no leader before kill $k"
  surv=(); for i in 0 1 2; do [[ $i == "$l" ]] || surv+=("$i"); done
  for i in "${surv[@]}"; do sample "kill$k" "$i" $((OBSERVE_SECS + 5)); done
  sleep 2; t=$(now_ms); kill_member "$l"; note "$(et)  kill $k: member $l (the leader) killed; survivors ${surv[*]}"
  if ((BURST_MB > 0)); then
    nl=none; for _ in $(seq 100); do nl=$(leader "${surv[@]}"); [[ $nl != none ]] && break; sleep 0.1; done
    [[ $nl != none ]] || fail "no new leader within ~10 s of kill $k"
    kc exec "order-matcher-cluster-$nl" -c cluster-node -- sh -c "dd if=/dev/zero of=/data/burst bs=1M count=$BURST_MB 2>&1 | tail -1; rm -f /data/burst" &
    note "  member $nl leads; $BURST_MB MB of buffered writes onto its /data"
  fi
  sleep "$OBSERVE_SECS"; wait
  e=$(inits "$t" "${surv[@]}" | elections); n=$(wc -w <<<"$e" | tr -d ' ')
  note "  elections after the kill: $n (${e:-none}); leader now member $(leader "${surv[@]}")"
  for i in "${surv[@]}"; do note "  member $i: $(throttled "$EVIDENCE/sample-kill$k-m$i.txt"); $(psi "$EVIDENCE/sample-kill$k-m$i.txt" cpu); $(psi "$EVIDENCE/sample-kill$k-m$i.txt" io); $(psi "$EVIDENCE/sample-kill$k-m$i.txt" memory)"; done
  for i in "${surv[@]}"; do kc logs "order-matcher-cluster-$i" -c cluster-node --since=2m > "$EVIDENCE/log-kill$k-m$i.txt" 2>&1 || true
    note "  member $i: longest safepoints after the kill (ms after it): $(pauses "$t" "$EVIDENCE/log-kill$k-m$i.txt")"; done
  echo "kill$k leader=$l elections=$n ${e}" >> "$EVIDENCE/elections.txt"
  # the killed member rejoins before the next kill
  for _ in $(seq 60); do [[ $(role "$l") == FOLLOWER ]] && break; sleep 5; done
  [[ $(role "$l") == FOLLOWER ]] || fail "member $l did not rejoin after kill $k"
done

step "4. the load's result"
until [[ -n $(kc get job binary-load -o jsonpath='{.status.succeeded}{.status.failed}') ]]; do sleep 5; done
kc logs job/binary-load | grep '^RESULT' | tee "$EVIDENCE/load-result.txt" | cut -c1-300 || note "binary-load printed no RESULT"
second=$(awk '/^kill/ { split($3, a, "="); if (a[2] > 1) s++ } END { print s + 0 }' "$EVIDENCE/elections.txt")
echo; echo "KIND-SECOND-ELECTION ARM $ARM: baseline elections ${base:-none}; kills with a second election: $second of $KILLS ($(grep -c '^kill' "$EVIDENCE/elections.txt") run) evidence $EVIDENCE"
