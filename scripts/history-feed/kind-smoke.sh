#!/bin/bash
# kind-smoke.sh: the history feed's shipped image and manifests on a throwaway kind cluster (FR-HST-06, SC-HST-03).
# NATS JetStream with the per-partition TRADERX_FEED_P<p> streams (P=2), ClickHouse, history-feed; load on the
# bridges' wire (scripts/history-store-bakeoff HsbLoad) while the history-feed pod is SIGKILLed (force delete);
# PASS when every captured message is stored exactly once and the query paths answer. KEEP=1 keeps the cluster.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MOD=$ROOT/specs/YU18-risk-integration/generation/runtime-overrides/history-feed
BAKE=$ROOT/scripts/history-store-bakeoff
CLUSTER=${CLUSTER:-hf-smoke}; NS=traderx; ORDERS=${ORDERS:-40000}
K="kubectl --context kind-$CLUSTER -n $NS"
say() { echo "$(date +%T) $*"; }
pf_pids=()
cleanup() { for p in ${pf_pids[@]+"${pf_pids[@]}"}; do kill "$p" 2>/dev/null || true; done
  [ "${KEEP:-}" = 1 ] || kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

say "build: history-feed jar + image, HsbLoad"
(cd "$MOD" && ./gradlew -q jar)
docker build -q -t traderx/history-feed:yu18 "$MOD" >/dev/null
"$BAKE/fetch-deps.sh" >/dev/null
CP=$BAKE/build/classes:$BAKE/build/lib/jnats-2.20.5.jar

kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER" >/dev/null
for img in traderx/history-feed:yu18 clickhouse/clickhouse-server:25.8 nats:2.14-alpine; do
  docker image inspect "$img" >/dev/null 2>&1 || docker pull -q "$img" >/dev/null
  # One platform's archive: `kind load docker-image` cannot import a multi-platform image from Docker's containerd store.
  tar=$(mktemp -t hf-image); docker save --platform "linux/$(docker version -f '{{.Server.Arch}}')" -o "$tar" "$img"
  kind load image-archive --name "$CLUSTER" "$tar" >/dev/null; rm -f "$tar"
done
kubectl --context "kind-$CLUSTER" create namespace $NS --dry-run=client -o yaml | kubectl --context "kind-$CLUSTER" apply -f - >/dev/null
# A bare JetStream under the base manifests' service name (the smoke isolates the history pieces).
$K apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Service
metadata: { name: nats-broker }
spec: { selector: { app: nats-broker }, ports: [ { name: client, port: 4222 } ] }
---
apiVersion: apps/v1
kind: Deployment
metadata: { name: nats-broker }
spec:
  replicas: 1
  selector: { matchLabels: { app: nats-broker } }
  template:
    metadata: { labels: { app: nats-broker } }
    spec: { containers: [ { name: nats, image: "nats:2.14-alpine", args: ["-js"], ports: [ { containerPort: 4222 } ] } ] }
YAML
$K apply -f "$MOD/k8s/clickhouse.yaml" >/dev/null
$K rollout status deploy/nats-broker --timeout=120s >/dev/null
$K rollout status statefulset/clickhouse --timeout=300s >/dev/null
$K port-forward svc/nats-broker 14222:4222 >/dev/null 2>&1 & pf_pids+=($!)
$K port-forward svc/clickhouse 18123:8123 >/dev/null 2>&1 & pf_pids+=($!)
sleep 3
ch() { curl -sf "http://127.0.0.1:18123/" --data-binary "$1"; }

say "streams TRADERX_FEED_P0/P1 and a first backlog ($((ORDERS / 4)) orders)"
java -cp "$CP" HsbLoad url=nats://127.0.0.1:14222 total=$((ORDERS / 4)) threads=2 partitions=2 streams=2 accounts=200 maxBytesGiB=2 create=true | tail -1
$K apply -f "$MOD/k8s/history-feed.yaml" >/dev/null
$K set env deploy/history-feed PROJECTION_FEED_PARTITIONS=2 PROJECTION_FEED_STREAM_LAYOUT=partition HISTORY_BATCH_ROWS=5000 >/dev/null
$K rollout status deploy/history-feed --timeout=300s >/dev/null

say "load $((ORDERS * 3 / 4)) more orders; SIGKILL the history-feed pod twice while it runs"
java -cp "$CP" HsbLoad url=nats://127.0.0.1:14222 total=$((ORDERS * 3 / 4)) threads=2 partitions=2 streams=2 accounts=200 seqBase=1000000000 > /tmp/hf-load.$$ 2>&1 &
LOAD=$!
for k in 1 2; do
  sleep 8
  pod=$($K get pod -l app=history-feed -o name)
  say "kill -9 $pod (rows so far: $(ch 'SELECT (SELECT count() FROM trades)+(SELECT count() FROM orders)'))"
  $K delete "$pod" --grace-period=0 --force >/dev/null 2>&1
done
wait $LOAD; tail -1 /tmp/hf-load.$$; rm -f /tmp/hf-load.$$
EXPECT_T=$((ORDERS)); EXPECT_O=$((ORDERS * 3 / 2))   # 1 trade side and 1.5 order events per order
say "drain: expect $EXPECT_T trade rows, $EXPECT_O order rows"
for i in $(seq 1 120); do
  got=$(ch "SELECT (SELECT count() FROM trades)+(SELECT count() FROM orders)" 2>/dev/null || echo 0)
  [ "$got" -ge $((EXPECT_T + EXPECT_O)) ] && break; sleep 5
done
sleep 5
fail=0
for t in trades orders; do
  key=$([ $t = trades ] && echo "trade_seq*2+(side='S')" || echo "order_ref*4+ordinal")
  want=$([ $t = trades ] && echo $EXPECT_T || echo $EXPECT_O)
  read -r n ds dk < <(ch "SELECT count(), uniqExact(part, seq), uniqExact($key) FROM $t FORMAT TSV")
  verdict=$([ "$n" = "$want" ] && [ "$ds" = "$want" ] && [ "$dk" = "$want" ] && echo EXACTLY-ONCE || echo MISMATCH)
  [ "$verdict" = EXACTLY-ONCE ] || fail=1
  say "CHECK $t rows=$n distinct(part,seq)=$ds distinctKey=$dk stream=$want -> $verdict"
done

$K port-forward svc/history-feed 18095:18095 >/dev/null 2>&1 & pf_pids+=($!)
sleep 3
day=$(ch "SELECT toDate(max(ts)) FROM trades"); to=$(ch "SELECT toUnixTimestamp64Milli(max(ts)) FROM trades"); from=$((to - 300000))
acct=$(ch "SELECT account FROM trades LIMIT 1")
for path in "accounts/$acct/fills?day=$day&limit=100" "accounts/$acct/tca?from=$from&to=$to" \
            "accounts/$acct/positions?asOf=$to" "vwap?from=$from&to=$to"; do
  body=$(curl -sf "http://127.0.0.1:18095/history/$path") || { say "QUERY $path FAILED"; fail=1; continue; }
  say "QUERY $path -> $(echo "$body" | grep -o '"rows": [0-9]*' | head -1)"
done
lines=$(curl -sf "http://127.0.0.1:18095/history/export/trades?day=$day" | wc -l | tr -d ' ')
say "EXPORT trades $day -> $((lines - 1)) rows"
[ "$((lines - 1))" = "$EXPECT_T" ] || fail=1
say "health: $(curl -sf http://127.0.0.1:18095/health | cut -c1-160)"
[ $fail = 0 ] && say PASS || { say FAIL; exit 1; }
