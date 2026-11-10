#!/usr/bin/env bash
# FR-DRS-45/46 at the effect end on kind: trade-processor as N replicas (production renders 4), each owning a block of
# the eight feed partitions, under production's binary load; every partition read exactly once across them; a third
# claimant refused; the order view's read answered as before through the router, and timed against fan-out-and-merge.
# The rig: kind-yu18-rig.py --binary --partitions 8 --tp-replicas N (StatefulSet trade-processor, pod k owning
# replica:k/N; production's snapshot read model on one stream per partition).
#
#   1. ownership: each pod reports its block, projection_partition_owner names it for each partition
#   2. the load: binary-load.mjs (production's driver) for SECS on accounts 9900010-17, one per partition
#   3. exactly once: each partition's trade and order cursors advanced on exactly one replica, the other never reported
#      it; G3 (rig.sh's g3_snapshot) passes against the union of the two accountings, which refuses a partition
#      answered twice or not at all; the order events the views read (applied + ignored, summed over the replicas)
#      equal the order records the members' bridges published (a partition read twice counts its events twice)
#   4. a third trade-processor claiming partition 0 is refused (PARTITION_OWNED, exit 3) and partition 0 stays read by -0
#   5. the contract (FR-DRS-46): every load account's /accounts/{id}/orders, open, ?status=all and ?clientOrderKey=, read
#      on every replica and through the Service, answers 200 with the owner's body (hashed in the pod; status=all
#      non-empty); the non-owners forwarded them (their orderReads.forwarded)
#   6. the router against fan-out-and-merge: order-read-bench.mjs in a pod, per mode: router (the Service), fanout (ask
#      every replica, merge), owner (the floor); reads/s and latency percentiles. Two shapes: open orders (small) for
#      BENCH_SECS at BENCH_C concurrency, and ?status=all (megabytes after the load) for BENCH_SECS at 2
#
#   CTX=kind-<c> NS=traderx [SECS=120 SESSIONS=4 RATE=375 TICKER_PX=50 BENCH_SECS=30 BENCH_C=16] \
#     bash scripts/proofs/kind-tp-ownership.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${CTX:?set CTX (a kind context)}"; NS=${NS:-traderx}
( . "$ROOT/scripts/proofs/lib-yu18.sh"; disposable_rig ) || { echo "disposable rig only: this starts a refused trade-processor" >&2; exit 1; }
EVIDENCE=${EVIDENCE:-${TMPDIR:-/tmp}/kind-tp-ownership-$$}; mkdir -p "$EVIDENCE"
RIG_LIB=1 . "$ROOT/scripts/gcp-compsci/rig/rig.sh"
SECS=${SECS:-120}; SESSIONS=${SESSIONS:-4}; RATE=${RATE:-375}; PX=${TICKER_PX:-50}; P=8
BENCH_SECS=${BENCH_SECS:-30}; BENCH_C=${BENCH_C:-16}
NODE_IMAGE=${NODE_IMAGE:-node:20-alpine}
fail(){ echo "[FAIL] $*" >&2; exit 1; }
tp(){ local i=$1; shift; kc exec "trade-processor-$i" -c trade-processor -- curl -sf -m15 "localhost:18091$1"; }
op(){ kc get secret auth-secrets -o jsonpath='{.data.risk-control-token}' | base64 -d \
        | awk '{print "X-Risk-Control-Token: " $0; print "X-Risk-Operator: kind-tp-ownership"; print "Content-Type: application/json"}' \
        | kc exec -i trade-processor-0 -c trade-processor -- curl -s -m 30 -o /dev/null -w '%{http_code}' -H @- -X POST "http://order-matcher:18110$1" -d "$2"; }
trades(){ member 0 | cut -d' ' -f3; }
published_orders(){ local i s=0 n; for i in 0 1 2; do
  n=$(kc exec "order-matcher-cluster-$i" -c cluster-node -- wget -qO- localhost:8080/health | jq '.bridges.orders.published // 0'); s=$((s + n)); done; echo $s; }
# G3 reads rig.sh's accounting(): with a StatefulSet, the union of the replicas' answers, each partition from exactly one.
owner_of(){ echo $(( $(( $1 % P )) * N / P )); }

N=$(kc get sts trade-processor -o jsonpath='{.spec.replicas}') && [[ $N =~ ^[1-9][0-9]*$ && $N -le $P ]] || fail "sts/trade-processor: '$N' replicas"
step "1. ownership: each of the $N replicas owns its block of the $P partitions, in its health and in projection_partition_owner"
kc rollout status sts/trade-processor --timeout=600s >/dev/null
for ((i = 0; i < N; i++)); do
  own=$(tp $i /v2/projection-feed | jq -c '.ownership'); echo "trade-processor-$i $own" >> "$EVIDENCE/ownership.txt"
  want=$(jq -cn --argjson k $i --argjson n $N --argjson p $P '[range($p) | select(. * $n / $p | floor == $k)]')
  [[ $(jq -c .owned <<<"$own") == "$want" && $(jq -r .replica <<<"$own") == "trade-processor-$i" ]] || fail "trade-processor-$i reports $own, want owned $want"
done
rows=$(sql "SELECT partition_id, replica_id, owned FROM projection_partition_owner ORDER BY partition_id") || fail "projection_partition_owner unreadable"
printf '%s\n' "$rows" | tee "$EVIDENCE/owners-before.tsv"
want=$(for ((q = 0; q < P; q++)); do printf '%d=trade-processor-%d ' $q $((q * N / P)); done)
[[ $(awk '{print $1 "=" $2}' <<<"$rows" | tr '\n' ' ') == "$want" ]] || fail "projection_partition_owner: $rows, want $want"
note "ok: $(tr '\n' ' ' < "$EVIDENCE/ownership.txt")"

step "2. the load: business day, accounts 9900010-17 (one per partition), a fresh ticker at $PX; $SESSIONS sessions x $RATE/s for ${SECS}s"
T=$(python3 -c 'import random;print("PO%06d" % random.randrange(10**6))')
[[ $(op /session/business-day "{\"businessDate\":\"$(TZ=America/New_York date +%F)\"}") == 200 ]] || fail "business day"
for ((a = 9900010; a <= 9900017; a++)); do [[ $(op /risk/control/account "{\"accountId\":$a,\"enabled\":true}") == 200 ]] || fail "admit $a"; done
# The trade store's snapshot upserts positions, whose accountid references accounts: production's render seeds the
# load accounts (population); kind's database has only the reference seeds, and every snapshot would be refused.
sql "INSERT IGNORE INTO accounts(id,displayname) VALUES $(for ((a = 9900010; a <= 9900017; a++)); do printf "%s(%d,'binary-load')" "${sep:-}" $a; sep=,; done); SELECT 1" >/dev/null \
  || fail "the load accounts could not be added to SQL accounts"
[[ $(op /seed "{\"accountId\":9900010,\"tickers\":\"$T\",\"price\":$PX}") == 200 ]] || fail "seed $T"
status0=(); for ((i = 0; i < N; i++)); do status0+=("$(tp $i /v2/projection-feed)"); done
t0=$(trades); note "ticker $T; members' trades before: $t0"
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
                  --sessions, "$SESSIONS", --rate, "$RATE", --secs, "$SECS", --out, /tmp/results.json]
        volumeMounts: [{name: load, mountPath: /load}]
      volumes: [{name: load, configMap: {name: binary-load}}]
YAML
until [[ -n $(kc get job binary-load -o jsonpath='{.status.succeeded}{.status.failed}') ]]; do sleep 5; done
[[ $(kc get job binary-load -o jsonpath='{.status.succeeded}') == 1 ]] || { kc logs job/binary-load | tail -5 >&2; fail "binary-load did not complete clean"; }
kc logs job/binary-load | grep '^RESULT' | tee "$EVIDENCE/load-result.txt"
t=$(trades); sides=$((t - t0)); note "members' trades after: $t ($sides sides booked by the load)"
[[ $sides -ge 10000 ]] || fail "the load booked $sides sides"
for m in 1 2; do [[ $(member $m | cut -d' ' -f3) == "$t" ]] || fail "member $m disagrees on trades"; done

step "3. exactly once: each partition's cursors moved on one replica only; G3 on the union; order events read = published"
wait_for 600 "every partition committed up to its stream's last message, on the union (G3's wait)" accounting_caught_up
for ((i = 0; i < N; i++)); do tp $i /v2/projection-feed > "$EVIDENCE/status-after-$i.json"; printf '%s\n' "${status0[$i]}" > "$EVIDENCE/status-before-$i.json"; done
verdict=$(python3 - "$EVIDENCE" $N $P <<'PY'
import json,sys
ev,n,p=sys.argv[1],int(sys.argv[2]),int(sys.argv[3])
before=[json.load(open(f"{ev}/status-before-{i}.json")) for i in range(n)]
after=[json.load(open(f"{ev}/status-after-{i}.json")) for i in range(n)]
bad,lines=[],[]
for q in range(p):
    k=str(q)
    tr=[i for i in range(n) if k in after[i]["trades"].get("partitions",{})]
    od=[i for i in range(n) if k in after[i]["orders"].get("committed",{})]
    if len(tr)!=1 or tr!=od: bad.append(f"partition {q}: trade view on replicas {tr}, order view on {od}"); continue
    i=tr[0]
    t0=before[i]["trades"]["partitions"][k]["streamSeq"]; t1=after[i]["trades"]["partitions"][k]["streamSeq"]
    o0=before[i]["orders"]["committed"][k]; o1=after[i]["orders"]["committed"][k]
    if not (t1>t0 and o1>o0): bad.append(f"partition {q} on replica {i}: trade cursor {t0}->{t1}, order cursor {o0}->{o1}")
    lines.append(f"p{q}: replica {i} trades {t0}->{t1} orders {o0}->{o1}")
read=sum(a["orders"]["applied"]+a["orders"]["ignoredStale"] for a in after)
print(("BAD " + "; ".join(bad)) if bad else "OK " + "; ".join(lines)); print(read)
PY
)
echo "$verdict" | tee "$EVIDENCE/cursors.txt"
[[ ${verdict%% *} == OK ]] || fail "cursors: $verdict"
digest=$(kc exec order-matcher-cluster-0 -c cluster-node -- wget -qO- 'localhost:8080/health?digest=1')
[[ $(jq -r '.projectionScope // "null"' <<<"$digest") == null ]] || fail "the members run a managed scope; G3's closed form reads one legacy numbering"
out=$( (g3_snapshot "$t" "$digest") 2>&1) || fail "G3: $out"
echo "$out" | tee "$EVIDENCE/g3.txt" | grep -F "G3 ok" || fail "G3 printed no verdict: $out"
read=$(tail -1 <<<"$verdict"); pub=$(published_orders)
echo "order events read by the views $read, published by the bridges $pub" | tee "$EVIDENCE/order-events.txt"
[[ $read == "$pub" && $pub -gt 0 ]] || fail "the order views read $read order events, the members' bridges published $pub"
note "ok: $(cat "$EVIDENCE/order-events.txt")"

step "4. a third trade-processor claiming partition 0 is refused"
kc get sts trade-processor -o json | jq '{apiVersion: "v1", kind: "Pod",
  metadata: {name: "tp-intruder", labels: {app: "tp-intruder"}},
  spec: (.spec.template.spec | .restartPolicy = "Never" | del(.containers[0].readinessProbe, .containers[0].livenessProbe)
    | .containers[0].env = ([.containers[0].env[] | select(.name | IN("TP_REPLICA_INDEX", "PROJECTION_REPLICA_ID", "PROJECTION_FEED_PARTITIONS_OWNED") | not)]
       + [{name: "PROJECTION_REPLICA_ID", value: "tp-intruder"}, {name: "PROJECTION_FEED_PARTITIONS_OWNED", value: "0"}]))}' \
  > "$EVIDENCE/intruder.json"
kc delete pod tp-intruder --ignore-not-found --wait=true >/dev/null; kc apply -f "$EVIDENCE/intruder.json" >/dev/null
for _ in $(seq 1 120); do ph=$(kc get pod tp-intruder -o jsonpath='{.status.phase}'); [[ $ph == Failed || $ph == Succeeded ]] && break; sleep 2; done
code=$(kc get pod tp-intruder -o jsonpath='{.status.containerStatuses[0].state.terminated.exitCode}')
kc logs tp-intruder > "$EVIDENCE/intruder.log" 2>&1 || true
grep -F "PARTITION_OWNED replica tp-intruder" "$EVIDENCE/intruder.log" | head -1 | cut -c1-400 | tee "$EVIDENCE/intruder-refusal.txt"
[[ $ph == Failed && $code == 3 ]] || fail "tp-intruder: phase $ph exit ${code:-?}, want Failed exit 3"
grep -qF "partition 0 is owned by replica trade-processor-0" "$EVIDENCE/intruder.log" || fail "the refusal does not name partition 0's owner: $EVIDENCE/intruder.log"
! grep -qF "trade store: partition 0 loaded" "$EVIDENCE/intruder.log" || fail "tp-intruder loaded partition 0 before it refused"
[[ $(sql "SELECT replica_id FROM projection_partition_owner WHERE partition_id=0") == trade-processor-0 ]] || fail "partition 0 changed owner"
kc delete pod tp-intruder --wait=false >/dev/null
note "ok: exit 3, partition 0 still trade-processor-0's"

step "5. the contract (FR-DRS-46): each load account's order read on every replica and through the Service = its owner's"
ACCOUNTS="9900010 9900011 9900012 9900013 9900014 9900015 9900016 9900017"   # not seq: macOS prints 9.90001e+06
# contract <pod> <base>: one line per account and shape, "account shape code md5 bytes", the body hashed in the pod
contract(){
  kc exec -i "$1" -c trade-processor -- sh -s "$2" $ACCOUNTS <<'SH'
base=$1; shift
for a in "$@"; do for s in "" "?status=all" "?clientOrderKey=1"; do
  c=$(curl -s -m60 -o /tmp/order-read -w '%{http_code}' "$base/accounts/$a/orders$s")
  echo "$a ${s:-?open} $c $(md5sum </tmp/order-read | cut -c1-16) $(wc -c </tmp/order-read | tr -d ' ')"
done; done
SH
}
fwd(){ tp "$1" /v2/projection-feed | jq '.ownership.orderReads.forwarded'; }
f0=(); for ((i = 0; i < N; i++)); do f0+=("$(fwd $i)"); done
for ((i = 0; i < N; i++)); do contract "trade-processor-$i" http://localhost:18091 > "$EVIDENCE/contract-replica-$i.txt"; done
contract trade-processor-0 http://trade-processor:18091 > "$EVIDENCE/contract-service.txt"
f1=(); for ((i = 0; i < N; i++)); do f1+=("$(fwd $i)"); done
bad=$(python3 - "$EVIDENCE" $N $P <<'PY'
import sys
ev,n,p=sys.argv[1],int(sys.argv[2]),int(sys.argv[3])
load=lambda f:{(l.split()[0],l.split()[1]):l.split()[2:] for l in open(f"{ev}/{f}") if l.strip()}
replicas=[load(f"contract-replica-{i}.txt") for i in range(n)]; svc=load("contract-service.txt")
bad=[]
for (a,shape),_ in replicas[0].items():
    k=(int(a)%p)*n//p; code,md5,size=replicas[k][(a,shape)]
    if code!="200": bad.append(f"{a}{shape} on its owner {k}: {code}"); continue
    if shape=="?status=all" and int(size)<=2: bad.append(f"{a}{shape}: an empty answer from its owner")
    for name,got in [(f"replica {i}",r[(a,shape)]) for i,r in enumerate(replicas)]+[("the Service",svc[(a,shape)])]:
        if got[:2]!=["200",md5]: bad.append(f"{a}{shape} through {name}: {got[0]} {got[1]}, owner {code} {md5}")
print("; ".join(bad))
PY
)
[[ -z $bad ]] || fail "contract: $bad"
for ((i = 0; i < N; i++)); do
  own=$(( P / N )); want=$(( (8 - own) * 3 ))   # its own pass forwards every account it does not own, in three shapes
  [[ $(( f1[i] - f0[i] )) -ge $want ]] || fail "trade-processor-$i forwarded $(( f1[i] - f0[i] )) reads, want at least $want"
done
echo "forwarded per replica: $(for ((i = 0; i < N; i++)); do printf '%s ' $(( f1[i] - f0[i] )); done)" | tee "$EVIDENCE/contract.txt"
note "ok: 8 accounts x 3 shapes x ($N replicas + the Service) = the owner's answer; $(cat "$EVIDENCE/contract.txt")"

step "6. the router against fan-out-and-merge (order-read-bench.mjs): open orders at $BENCH_C, ?status=all at 2, ${BENCH_SECS}s a mode"
kc create configmap order-read-bench --from-file=order-read-bench.mjs="$ROOT/scripts/proofs/order-read-bench.mjs" --dry-run=client -o yaml | kc apply -f - >/dev/null
: > "$EVIDENCE/bench.txt"
for shape in "open $BENCH_C" "?status=all 2"; do
  set -- $shape; q=$1; c=$2; [[ $q == open ]] && q=""
  kc delete job order-read-bench --ignore-not-found --wait=true >/dev/null
  kc apply -f - >/dev/null <<YAML
apiVersion: batch/v1
kind: Job
metadata: {name: order-read-bench}
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
      - name: bench
        image: $NODE_IMAGE
        imagePullPolicy: IfNotPresent
        command: [node, /bench/order-read-bench.mjs, --replicas, "$N", --partitions, "$P", --accounts, "9900010-9900017",
                  --secs, "$BENCH_SECS", --concurrency, "$c", --query, "$q"]
        volumeMounts: [{name: bench, mountPath: /bench}]
      volumes: [{name: bench, configMap: {name: order-read-bench}}]
YAML
  until [[ -n $(kc get job order-read-bench -o jsonpath='{.status.succeeded}{.status.failed}') ]]; do sleep 5; done
  [[ $(kc get job order-read-bench -o jsonpath='{.status.succeeded}') == 1 ]] || { kc logs job/order-read-bench | tail -5 >&2; fail "order-read-bench ($shape) did not complete"; }
  kc logs job/order-read-bench | grep '^RESULT' | tee -a "$EVIDENCE/bench.txt"
done
[[ $(grep -c '^RESULT' "$EVIDENCE/bench.txt") == 6 ]] || fail "order-read-bench: $(grep -c '^RESULT' "$EVIDENCE/bench.txt") results, want 6"
bench=$(sed 's/^RESULT //' "$EVIDENCE/bench.txt" | jq -rs 'map("\(.query) \(.mode) \(.readsPerSec)/s p50 \(.p50) p99 \(.p99) ms") | join("; ")')
note "$bench"
[[ $(sed 's/^RESULT //' "$EVIDENCE/bench.txt" | jq -s 'map(.errors) | add') == 0 ]] || fail "order-read-bench: reads failed: $bench"

summary="$sides sides by the load ($t in all); each of $P partitions read by exactly one of $N replicas; $(grep -F "G3 ok" "$EVIDENCE/g3.txt" | sed 's/^ *//'); order events read $read = published $pub; third claimant refused (exit 3); order reads = the owner's through every replica and the Service; $bench"
echo "$summary" > "$EVIDENCE/summary.txt"
echo; echo "KIND-TP-OWNERSHIP PASS ($summary) evidence $EVIDENCE"
