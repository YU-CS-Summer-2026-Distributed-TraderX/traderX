#!/usr/bin/env bash
#
# One gated row of the gateway throughput campaign.
#
#   ./run-gateway-campaign-once.sh <label> <account> <batch> <conc> <secs>
#
# Runs a single sustained batch-path measurement against the live cluster and appends ONE row
# to the results CSV — but only if every gate below passes. Any gate failure exits non-zero
# and writes nothing, so a bad run cannot silently become a data point.
#
# GATES ENFORCED (a run that violates any of these is not a measurement):
#   - gateway placement is one-per-node (replicas == distinct nodes)
#   - a leader exists before the run
#   - fence accounting balances: applied == booked + controls + committed fences,
#     and committed fences never exceed offered fences
#   - the high-water fence timeout counter does not move
#   - the book-open gauge is 0 before AND after (nothing left resting)
#   - member and gateway restart counts are unchanged across the run
#   - all three members agree on (applied, trades) afterwards
#
# The booked rate comes from the LEADER'S OWN trade counter (`/health` .trades), not from
# anything the client counted. Elapsed time is wall clock around the in-cluster run.
#
# DRAIN DISCIPLINE: after the load stops, the script waits for the gateway fence counters to
# stop moving, then requires the leader's applied position to be unchanged for two consecutive
# polls before reading final state. Closing a row early undercounts booked work.
#
# PREREQUISITES (the run will fail confusingly without these):
#   1. A `bench-runner` pod in the `traderx` namespace with these copied to / :
#          kubectl -n traderx cp scripts/bench/batch-load.mjs            bench-runner:/batch-load.mjs
#          kubectl -n traderx cp scripts/bench/gateway-price-refresh.mjs bench-runner:/gateway-price-refresh.mjs
#   2. The seven real SQL accounts seeded. Rotate accounts between long runs — per-account
#      executedNotional accumulates forever and walls an account after ~30M orders.
#   3. Pinned image digests on members and gateways (imagePullPolicy: Always silently swaps
#      binaries between runs). The digests are recorded per row so a mixed run is detectable.
#
# Override the results file with BENCH_RESULTS=/path/to.csv
#
set -euo pipefail

LABEL="$1"
ACCOUNT="$2"
BATCH="$3"
CONC="$4"
SECS="$5"
NS=traderx
SVC=http://order-matcher-gw.traderx.svc.cluster.local:18110
TICKERS=JPM,GS,COF

# Odd ticker count is deliberate: the batch harness rotates tickers per order while alternating
# sides by index, so an EVEN count gives each symbol only one side and nothing ever crosses.

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
RESULTS="${BENCH_RESULTS:-$SCRIPT_DIR/results/gateway-throughput-campaign-2026-07-21.csv}"
mkdir -p "$(dirname "$RESULTS")"

health() {
  kubectl -n "$NS" get --raw "/api/v1/namespaces/$NS/pods/$1:8080/proxy/health"
}

gateway_metric_sum() {
  local metric="$1" sum=0 pod value
  while read -r pod; do
    value=$(kubectl -n "$NS" get --raw "/api/v1/namespaces/$NS/pods/${pod}:18110/proxy/metrics" \
      | awk -v wanted="$metric" '$0 ~ wanted {print $2; exit}')
    sum=$((sum + ${value:-0}))
  done < <(kubectl -n "$NS" get pods -l app=cluster-gateway -o name | sed 's#pod/##' | sort)
  echo "$sum"
}

gateway_pods=$(kubectl -n "$NS" get pods -l app=cluster-gateway -o json)
gateway_count=$(jq '.items | length' <<<"$gateway_pods")
distinct_nodes=$(jq '[.items[].spec.nodeName] | unique | length' <<<"$gateway_pods")
gateway_restarts_before=$(jq '[.items[].status.containerStatuses[0].restartCount] | add' <<<"$gateway_pods")
member_restarts_before=$(kubectl -n "$NS" get pods -l app=order-matcher-cluster -o json \
  | jq '[.items[].status.containerStatuses[0].restartCount] | add')
if [[ "$gateway_count" -ne "$distinct_nodes" ]]; then
  echo "[fail] gateway placement is not one-per-node: replicas=$gateway_count nodes=$distinct_nodes" >&2
  exit 1
fi

# Prime the seed cache on EVERY gateway, not just the one carrying load. A gateway that has
# never seen a ticker emits first-use symbol-registration events into the consensus log, which
# breaks the applied/booked equality gate below and invalidates the row.
refresh_ip=$(jq -r '.items | sort_by(.metadata.name) | .[0].status.podIP' <<<"$gateway_pods")
while read -r prime_ip; do
  kubectl -n "$NS" exec bench-runner -- env REFRESH_URL="http://${prime_ip}:18110" ACCOUNT="$ACCOUNT" TICKERS="$TICKERS" node -e '
    const body=JSON.stringify({accountId:Number(process.env.ACCOUNT),tickers:process.env.TICKERS,price:150});
    fetch(process.env.REFRESH_URL+"/seed",{method:"POST",headers:{"content-type":"application/json"},body})
      .then(async r=>{console.log(r.status,await r.text()); if(r.status!==200) process.exitCode=1})
      .catch(e=>{console.error(e);process.exitCode=1})' >/dev/null
done < <(jq -r '.items | sort_by(.metadata.name) | .[].status.podIP' <<<"$gateway_pods")

h0=$(health order-matcher-cluster-0)
h1=$(health order-matcher-cluster-1)
h2=$(health order-matcher-cluster-2)
leader=$(printf '%s\n%s\n%s\n' "$h0" "$h1" "$h2" | jq -r 'select(.role=="LEADER") | .memberId')
if [[ -z "$leader" ]]; then
  echo "[fail] no leader before run" >&2
  exit 1
fi
before=$(health "order-matcher-cluster-${leader}")
before_applied=$(jq -r '.applied' <<<"$before")
before_trades=$(jq -r '.trades' <<<"$before")
before_open=$(kubectl -n "$NS" get --raw "/api/v1/namespaces/$NS/pods/order-matcher-cluster-${leader}:8080/proxy/metrics" \
  | awk '/traderx_book_open_orders/ && $1 !~ /^#/ {print $2; exit}')
before_fill_acks=$(gateway_metric_sum 'event="fill"')
before_fences=$(gateway_metric_sum 'state="offered"')
before_fence_timeouts=$(gateway_metric_sum 'outcome="timeout"')

start=$(date +%s.%N)
out=$(kubectl -n "$NS" exec bench-runner -- env \
  MATCHER_URL="$SVC" REFRESH_URL="http://${refresh_ip}:18110" ACCOUNT="$ACCOUNT" \
  TICKERS="$TICKERS" SIDES=alternate LIMIT=150 QTY=500 sh -c '
    node /gateway-price-refresh.mjs &
    refresher=$!
    node /batch-load.mjs --batch "$1" --conc "$2" --secs "$3"
    rc=$?
    kill "$refresher" 2>/dev/null || true
    wait "$refresher" 2>/dev/null || true
    exit "$rc"
  ' sh "$BATCH" "$CONC" "$SECS" 2>&1)
end=$(date +%s.%N)
sleep 2
elapsed=$(echo "$end - $start" | bc)

# Drain gate 1: wait for gateway fence offers to stop moving.
last_fences=-1
for _ in 1 2 3 4 5 6 7 8 9 10; do
  after_fences=$(gateway_metric_sum 'state="offered"')
  [[ "$after_fences" -eq "$last_fences" ]] && break
  last_fences="$after_fences"
  sleep 1
done
after_fill_acks=$(gateway_metric_sum 'event="fill"')
after_fence_timeouts=$(gateway_metric_sum 'outcome="timeout"')

# Drain gate 2: require the leader's applied position to be stable across two consecutive
# polls. Final fence retries can still be in the committed ingress tail seconds after the
# timed client stops.
last_applied=-1
stable_applied_polls=0
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  after=$(health "order-matcher-cluster-${leader}")
  current_applied=$(jq -r '.applied' <<<"$after")
  if [[ "$current_applied" -eq "$last_applied" ]]; then
    stable_applied_polls=$((stable_applied_polls + 1))
    [[ "$stable_applied_polls" -ge 2 ]] && break
  else
    stable_applied_polls=0
    last_applied="$current_applied"
  fi
  sleep 1
done
after_applied=$(jq -r '.applied' <<<"$after")
after_trades=$(jq -r '.trades' <<<"$after")
after_open=$(kubectl -n "$NS" get --raw "/api/v1/namespaces/$NS/pods/order-matcher-cluster-${leader}:8080/proxy/metrics" \
  | awk '/traderx_book_open_orders/ && $1 !~ /^#/ {print $2; exit}')

submitted=$(grep -o 'submitted=[0-9]*' <<<"$out" | tail -1 | cut -d= -f2)
failed=$(grep -o 'failed=[0-9]*' <<<"$out" | tail -1 | cut -d= -f2)
submitted=${submitted:-0}
failed=${failed:-0}
applied_delta=$((after_applied - before_applied))
trades_delta=$((after_trades - before_trades))
fill_ack_delta=$((after_fill_acks - before_fill_acks))
refresh_successes=$(grep -c '\[refresh\] status=200' <<<"$out" || true)
control_delta=$((refresh_successes * 7))
fence_offer_delta=$((after_fences - before_fences))
fence_delta=$((applied_delta - trades_delta - control_delta))
booked_rate=$(echo "scale=2; $trades_delta / $elapsed" | bc)
submit_rate=$(echo "scale=2; $submitted / $elapsed" | bc)
ack_drops=$((trades_delta - fill_ack_delta))
ack_drop_pct=$(echo "scale=6; if ($trades_delta == 0) 0 else 100 * $ack_drops / $trades_delta" | bc)

# A positive Aeron offer() is a publication-position result, not a commit receipt, so offered
# fences can exceed committed ones by a few records. Committed fences EXCEEDING offered ones,
# or a negative count, means the accounting identity is wrong and the row is not trustworthy.
if [[ "$fence_delta" -lt 0 || "$fence_delta" -gt "$fence_offer_delta" ]]; then
  echo "[fail] fence accounting mismatch: applied=$applied_delta booked=$trades_delta controls=$control_delta committed_fences=$fence_delta offered_fences=$fence_offer_delta" >&2
  exit 1
fi
if [[ "$after_fence_timeouts" -ne "$before_fence_timeouts" ]]; then
  echo "[fail] high-water timeout count changed: before=$before_fence_timeouts after=$after_fence_timeouts" >&2
  exit 1
fi
if [[ "$before_open" -ne "$after_open" || "$after_open" -ne 0 ]]; then
  echo "[fail] book-open gauge changed: before=$before_open after=$after_open" >&2
  exit 1
fi

final_members=$(kubectl -n "$NS" get pods -l app=order-matcher-cluster -o json)
final_gateways=$(kubectl -n "$NS" get pods -l app=cluster-gateway -o json)
member_restarts_after=$(jq '[.items[].status.containerStatuses[0].restartCount] | add' <<<"$final_members")
gateway_restarts_after=$(jq '[.items[].status.containerStatuses[0].restartCount] | add' <<<"$final_gateways")
if [[ "$member_restarts_before" -ne "$member_restarts_after" || "$gateway_restarts_before" -ne "$gateway_restarts_after" ]]; then
  echo "[fail] restart count changed during run" >&2
  exit 1
fi

parity=$(for p in 0 1 2; do health "order-matcher-cluster-$p"; echo; done)
parity_pairs=$(jq -s '[.[] | [.applied,.trades]] | unique | length' <<<"$parity")
if [[ "$parity_pairs" -ne 1 ]]; then
  echo "[fail] member applied/trades parity lost" >&2
  printf '%s\n' "$parity" >&2
  exit 1
fi

if [[ ! -f "$RESULTS" ]]; then
  printf 'label,account,batch,conc,seconds,elapsed_s,booked,booked_per_s,submitted,submit_per_s,failed,applied_delta,refresh_controls,batch_fences_committed,batch_fence_offers,fill_acks_seen,ack_drops,ack_drop_pct,open_orders,gateway_replicas,distinct_gateway_nodes,member_restarts,gateway_restarts,member_image_digest,gateway_image_digest,head\n' > "$RESULTS"
fi
member_image_digest=$(jq -r '.items[0].spec.containers[0].image' <<<"$final_members" | sed 's#.*@##')
gateway_image_digest=$(jq -r '.items[0].spec.containers[0].image' <<<"$final_gateways" | sed 's#.*@##')
head=$(git -C "$REPO_ROOT" rev-parse HEAD)
printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
  "$LABEL" "$ACCOUNT" "$BATCH" "$CONC" "$SECS" "$elapsed" "$trades_delta" "$booked_rate" \
  "$submitted" "$submit_rate" "$failed" "$applied_delta" "$control_delta" "$fence_delta" "$fence_offer_delta" \
  "$fill_ack_delta" "$ack_drops" "$ack_drop_pct" "$after_open" "$gateway_count" "$distinct_nodes" "$member_restarts_after" \
  "$gateway_restarts_after" "$member_image_digest" "$gateway_image_digest" "$head" | tee -a "$RESULTS"
printf '%s\n' "$out"
