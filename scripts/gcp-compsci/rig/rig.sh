#!/usr/bin/env bash
# ADR-096: the production rig (namespace traderx on traderx-demo) up at bench parity and back down to zero.
#
#   scripts/gcp-compsci/rig/rig.sh pools|up|down|status [--dry-run]
#   scripts/gcp-compsci/rig/rig.sh loadgen N          loadgen-pool to N nodes for binary-load.sh (FR-PRG-16), 0..16
#     GATEWAYS=N      gateways, one per gateway-pool node, 1..18 (default 2)
#     AUTOSCALE=on    gateways also scale on CPU between GATEWAYS and 18 (default off); HPA_CPU_TARGET=N% (default 60)
#     LOADGEN=N       up also brings loadgen-pool to N nodes and LOADGEN_PODS idle load pods (default 4 per node), so
#                     `loadgen.sh start RATE` begins the binary load in seconds (FR-PRG-32); 0..16 (default 0)
#
# pools   creates nats-pool, gateway-pool, history-pool, the client VM and its IAP rule if missing; puts the sysctls on
#         engine-pool (only while it has no nodes, since GKE recreates the nodes to apply them).
# up      nodes, then members, then the restore gate (gateways stay at 0 until it passes; a mismatch stops here),
#         then N gateways, the internal LB, autoscaling, and a smoke order checked at the effect end.
# down    a NATS restart or replacement during the run is reported FAILED (FR-PRG-28); then
#         gateways to 0 first (they drain), a snapshot barrier and a shutdown baseline, members stopped (each must
#         exit 143), then every rig pool to 0 and the client VM stopped, verified by `gcloud compute instances list`.
# status  read-only.
# archive-members LABEL  members stopped: GCE snapshots traderx-LABEL-m{0,1,2} of the three member disks (FR-PRG-29).
# fresh-epoch LABEL      members stopped and those snapshots READY: the old gate records into rig-epoch-LABEL-history,
#         the member claims (and their disks) deleted. The next `up` archives the read model into arc_LABEL_* and starts
#         the members empty; its gate accepts an empty epoch and no baseline only then (ADR-101).
#
# Idempotent: every step checks the live state first. Fail-closed: any check that does not hold stops the run with
# REFUSED and a non-zero exit, and nothing after it runs. --dry-run prints the plan and contacts nothing.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT=${PROJECT:-project-compsci-484414}
ZONE=${ZONE:-us-east1-b}
CLUSTER=${CLUSTER:-traderx-demo}
NS=${NS:-traderx}
CTX=${CTX:-gke_${PROJECT}_${ZONE}_${CLUSTER}}
GATEWAYS=${GATEWAYS:-2}
AUTOSCALE=${AUTOSCALE:-off}
LOADGEN=${LOADGEN:-0}
# FR-PRG-33: the HPA's CPU target (% of the gateway's 4-CPU request). The binary path costs a gateway ~0.6 core per 100K
# orders/s, so at the engine's ~230K/s three gateways sit near 44%: below 60 they never scale. The demo runs 30.
HPA_CPU_TARGET=${HPA_CPU_TARGET:-60}
LOADGEN_PODS=${LOADGEN_PODS:-}   # default 4 per loadgen node, set once LOADGEN is validated
MAX_GATEWAYS=18
DEFAULT_NODES=${DEFAULT_NODES:-3}
CLIENT_VM=${CLIENT_VM:-traderx-client-1}
# FR-PRG-17: CLIENTS=N client VMs (traderx-client-1..N, 1..3) for a combined run's FIX load; each is its own /32 on the LB.
CLIENTS=${CLIENTS:-1}
[[ "$CLIENTS" =~ ^[1-3]$ ]] || { echo "CLIENTS is 1..3" >&2; exit 2; }
# FR-PRG-26: not `seq 2 $CLIENTS`; BSD seq counts DOWN for CLIENTS=1 ("2 1"), which started and stopped client-2 too.
CLIENT_VMS=$CLIENT_VM; for ((i = 2; i <= CLIENTS; i++)); do CLIENT_VMS="$CLIENT_VMS traderx-client-$i"; done
# FR-PRG-35: the KDB-X query VM (scripts/demo-kdb) starts and stops with the rig; KDB_VM= runs without it.
KDB_VM=${KDB_VM-traderx-kdb}
RIG_VMS="$CLIENT_VMS${KDB_VM:+ $KDB_VM}"
GATE_SHADOW_PATH=${GATE_SHADOW_PATH:-/recon/shadow-state}
EVIDENCE=${EVIDENCE:-$PWD/rig-evidence/$(date +%Y%m%d-%H%M%S)}
DRY=0
SMOKE_ACCOUNT=42422   # fix-smoke.mjs's default account, whose orders the smoke reads back

# pool -> machine type, nodes when up, taint key (empty: existing pool, never created here)
pool_machine(){ case $1 in default-pool) echo e2-standard-4;; engine-pool) echo c4d-standard-8;;
  nats-pool) echo c3-standard-8;; gateway-pool) echo c4d-standard-8;; history-pool) echo c4d-standard-8;; esac; }
pool_nodes(){ case $1 in default-pool) echo "$DEFAULT_NODES";; engine-pool) echo 3;; nats-pool) echo 1;;
  gateway-pool) echo "$GATEWAYS";; history-pool) echo 1;; esac; }
# history-pool (FR-THT-02): ClickHouse and history-feed, the trade history's system of record (ADR-102).
RIG_POOLS="default-pool engine-pool nats-pool gateway-pool history-pool"

et(){ TZ=America/New_York date '+%-I:%M:%S %p ET'; }
step(){ echo; echo "== $(et)  $*"; }
note(){ echo "   $*"; }
die(){ echo "REFUSED ($(et)): $*" >&2; exit 2; }
# A mutating command: printed, and run unless --dry-run.
do_(){ if [ "$DRY" = 1 ]; then echo "   would: $*"; else echo "   + $*"; "$@"; fi; }
kc(){ kubectl --context "$CTX" -n "$NS" "$@"; }
gc(){ gcloud --project "$PROJECT" --quiet "$@"; }
# wait_for <seconds> <description> <command...>: poll until the command succeeds, or refuse.
wait_for(){
  local secs=$1 what=$2; shift 2
  if [ "$DRY" = 1 ]; then echo "   would wait (≤${secs}s): $what"; return 0; fi
  local end=$((SECONDS + secs))
  until "$@" >/dev/null 2>&1; do
    [ $SECONDS -lt $end ] || die "timed out after ${secs}s waiting for: $what"
    sleep 5
  done
  note "ok: $what"
}

validate_args(){
  case $GATEWAYS in ''|*[!0-9]*) die "GATEWAYS must be a whole number, got '$GATEWAYS'";; esac
  [ "$GATEWAYS" -ge 1 ] && [ "$GATEWAYS" -le $MAX_GATEWAYS ] || die "GATEWAYS must be 1..$MAX_GATEWAYS, got $GATEWAYS"
  case $AUTOSCALE in on|off) ;; *) die "AUTOSCALE must be on or off, got '$AUTOSCALE'";; esac
  [ "$AUTOSCALE" = off ] || [ "$GATEWAYS" -lt $MAX_GATEWAYS ] || die "AUTOSCALE=on needs GATEWAYS < $MAX_GATEWAYS"
  [[ "$LOADGEN" =~ ^([0-9]|1[0-6])$ ]] || die "LOADGEN must be 0..16, got '$LOADGEN'"
  [[ "$HPA_CPU_TARGET" =~ ^[1-9][0-9]$ ]] || die "HPA_CPU_TARGET must be 10..99, got '$HPA_CPU_TARGET'"
  [ -n "$LOADGEN_PODS" ] || LOADGEN_PODS=$(( LOADGEN * 4 ))
  [[ "$LOADGEN_PODS" =~ ^[0-9]+$ ]] && [ "$LOADGEN_PODS" -le 64 ] || die "LOADGEN_PODS must be 0..64, got '$LOADGEN_PODS'"
  [ "$LOADGEN" = 0 ] || [ "$LOADGEN_PODS" -ge 1 ] || die "LOADGEN=$LOADGEN needs LOADGEN_PODS >= 1"
}

preflight(){
  [ "$DRY" = 1 ] && return 0
  for t in kubectl gcloud jq; do command -v $t >/dev/null || die "$t not on PATH"; done
  gcloud auth print-access-token >/dev/null 2>&1 || die "gcloud is not signed in (gcloud auth login)"
  kc get namespace "$NS" >/dev/null 2>&1 || die "namespace $NS not reachable on $CTX"
  mkdir -p "$EVIDENCE"
}

pool_exists(){ gc container node-pools describe "$1" --cluster "$CLUSTER" --zone "$ZONE" --format='value(name)' >/dev/null 2>&1; }
pool_size(){ gc compute instances list --filter="name~^gke-${CLUSTER}-$1-" --format='value(name)' | wc -l | tr -d ' '; }
pool_ready(){ [ "$(kubectl --context "$CTX" get nodes -l cloud.google.com/gke-nodepool="$1" --no-headers 2>/dev/null \
  | awk '$2=="Ready"' | wc -l | tr -d ' ')" = "$2" ]; }
engine_has_sysctls(){ [ -n "$(gc container node-pools describe engine-pool --cluster "$CLUSTER" --zone "$ZONE" \
  --format='value(config.linuxNodeConfig.sysctls."net.core.rmem_max")' 2>/dev/null)" ]; }
gateway_replicas(){ kc get deploy cluster-gateway -o jsonpath='{.spec.replicas}'; }
hpa_exists(){ kc get hpa cluster-gateway >/dev/null 2>&1; }
no_pods(){ [ -z "$(kc get pods -l "app=$1" -o name 2>/dev/null)" ]; }
# Every replica on the current template and available, whatever the Progressing condition says (FR-PRG-34).
deploy_rolled_out(){ local v; v=$(kc get "$1" -o jsonpath='{.metadata.generation} {.status.observedGeneration} {.spec.replicas} {.status.updatedReplicas} {.status.availableReplicas} {.status.replicas}' 2>/dev/null) || return 1
  read -r g og want upd avail total <<<"$v"; [ "$g" = "$og" ] && [ "${upd:-0}" = "$want" ] && [ "${avail:-0}" = "$want" ] && [ "${total:-0}" = "$want" ]; }
ready_replicas(){ [ "$(kc get "$1" -o jsonpath='{.status.readyReplicas}' 2>/dev/null)" = "$2" ]; }
ilb_has_ip(){ [ -n "$(kc get svc order-matcher-gw-ilb -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null)" ]; }
# One plain-prefix query per pool: gcloud crashes on an alternation in this filter, and a crashed query printed nothing,
# so `down` once reported "no rig node instance left" with default-pool still RUNNING (2026-10-29). A failed query is
# not an empty one.
no_rig_instances(){ local p out
  for p in $RIG_POOLS loadgen-pool; do
    out=$(gc compute instances list --filter="name~^gke-${CLUSTER}-${p}-" --format='value(name)') || return 1
    [ -z "$out" ] || return 1
  done; }
vm_status_is(){ local v; for v in $RIG_VMS; do
  [ "$(gc compute instances describe "$v" --zone "$ZONE" --format='value(status)')" = "$1" ] || return 1; done; }
pool_instances_are(){ [ "$(pool_size "$1")" = "$2" ]; }
pool_autoscaling(){ [ "$(gc container node-pools describe gateway-pool --cluster "$CLUSTER" --zone "$ZONE" \
  --format='value(autoscaling.enabled)' 2>/dev/null)" = True ]; }
autoscale_off(){
  if [ "$DRY" = 1 ] || hpa_exists; then do_ kubectl --context "$CTX" -n "$NS" delete hpa cluster-gateway --ignore-not-found; fi
  if [ "$DRY" = 1 ] || pool_autoscaling; then
    do_ gcloud container node-pools update gateway-pool --cluster "$CLUSTER" --zone "$ZONE" --project "$PROJECT" --no-enable-autoscaling
  fi
}

# ---- pools -------------------------------------------------------------------------------------------------------
rig_pools(){
  step "pools: nats-pool, gateway-pool and history-pool exist with the right machine type; engine-pool carries the sysctls"
  local sa scopes net sub
  if [ "$DRY" = 1 ]; then sa='<engine-pool SA>'; scopes='<engine-pool scopes>'; net=default; sub=default; else
    sa=$(gc container node-pools describe engine-pool --cluster "$CLUSTER" --zone "$ZONE" --format='value(config.serviceAccount)')
    scopes=$(gc container node-pools describe engine-pool --cluster "$CLUSTER" --zone "$ZONE" --format='value(config.oauthScopes)' | tr ';' ',')
    net=$(gc container clusters describe "$CLUSTER" --zone "$ZONE" --format='value(network)')
    sub=$(gc container clusters describe "$CLUSTER" --zone "$ZONE" --format='value(subnetwork)')
  fi
  local common=(--cluster "$CLUSTER" --zone "$ZONE" --project "$PROJECT" --num-nodes 0 --enable-autorepair
                --service-account "$sa" --scopes "$scopes")
  if [ "$DRY" = 0 ] && pool_exists nats-pool; then
    [ "$(gc container node-pools describe nats-pool --cluster "$CLUSTER" --zone "$ZONE" --format='value(config.machineType)')" = c3-standard-8 ] \
      || die "nats-pool exists with another machine type"
    note "nats-pool present"
  else
    # c3, not c4d: NATS keeps JetStream on a pd-balanced claim (nats-data), and C4D attaches only Hyperdisk.
    do_ gcloud container node-pools create nats-pool "${common[@]}" --machine-type c3-standard-8 \
      --disk-type pd-balanced --disk-size 100 --node-taints traderx-nats=true:NoSchedule --node-labels traderx-role=nats \
      --system-config-from-file "$HERE/node-nats.yaml"
  fi
  if [ "$DRY" = 0 ] && pool_exists gateway-pool; then
    [ "$(gc container node-pools describe gateway-pool --cluster "$CLUSTER" --zone "$ZONE" --format='value(config.machineType)')" = c4d-standard-8 ] \
      || die "gateway-pool exists with another machine type"
    note "gateway-pool present"
  else
    do_ gcloud container node-pools create gateway-pool "${common[@]}" --machine-type c4d-standard-8 \
      --disk-type hyperdisk-balanced --disk-size 100 --node-taints traderx-gateway=true:NoSchedule \
      --node-labels traderx-role=gateway --system-config-from-file "$HERE/node-gateway.yaml"
  fi
  if [ "$DRY" = 0 ] && pool_exists history-pool; then
    [ "$(gc container node-pools describe history-pool --cluster "$CLUSTER" --zone "$ZONE" --format='value(config.machineType)')" = c4d-standard-8 ] \
      || die "history-pool exists with another machine type"
    note "history-pool present"
  else
    # FR-THT-02: the store's claim is Hyperdisk Balanced (class clickhouse-hot), which C4D attaches.
    do_ gcloud container node-pools create history-pool "${common[@]}" --machine-type c4d-standard-8 \
      --disk-type hyperdisk-balanced --disk-size 100 --node-taints traderx-history=true:NoSchedule \
      --node-labels traderx-role=history
  fi
  if [ "$DRY" = 0 ] && pool_exists loadgen-pool; then note "loadgen-pool present"; else
    # FR-PRG-16: the binary load's own nodes, tainted so nothing else lands there; at 0 until `rig.sh loadgen N`.
    do_ gcloud container node-pools create loadgen-pool "${common[@]}" --machine-type c4d-standard-8 \
      --disk-type hyperdisk-balanced --disk-size 100 --node-taints traderx-loadgen=true:NoSchedule \
      --node-labels traderx-role=loadgen
  fi
  if [ "$DRY" = 0 ] && engine_has_sysctls; then note "engine-pool sysctls present"; else
    [ "$DRY" = 1 ] || [ "$(pool_size engine-pool)" = 0 ] \
      || die "engine-pool needs its sysctls, and GKE recreates its nodes to apply them: run 'rig.sh down' first"
    do_ gcloud container node-pools update engine-pool --cluster "$CLUSTER" --zone "$ZONE" --project "$PROJECT" \
      --system-config-from-file "$HERE/node-engine.yaml"
  fi
  if [ "$DRY" = 0 ] && gc compute firewall-rules describe traderx-client-iap-ssh >/dev/null 2>&1; then note "IAP rule present"; else
    do_ gcloud compute firewall-rules create traderx-client-iap-ssh --project "$PROJECT" --network "$net" \
      --direction INGRESS --allow tcp:22 --source-ranges 35.235.240.0/20 --target-tags traderx-client
  fi
  local v
  for v in $CLIENT_VMS; do
    if [ "$DRY" = 0 ] && gc compute instances describe "$v" --zone "$ZONE" >/dev/null 2>&1; then note "$v present"; else
      do_ gcloud compute instances create "$v" --project "$PROJECT" --zone "$ZONE" --machine-type c4d-standard-8 \
        --subnet "$sub" --no-address --image-family debian-12 --image-project debian-cloud \
        --boot-disk-type hyperdisk-balanced --boot-disk-size 20GB --tags traderx-client --shielded-secure-boot
      do_ gcloud compute instances stop "$v" --project "$PROJECT" --zone "$ZONE"
    fi
  done
}

require_pools(){
  [ "$DRY" = 1 ] && return 0
  for p in nats-pool gateway-pool history-pool; do pool_exists $p || die "$p is missing: run 'rig.sh pools' first"; done
  engine_has_sysctls || die "engine-pool has no sysctls: run 'rig.sh pools' while it is at 0"
  local v; for v in $CLIENT_VMS; do
    gc compute instances describe "$v" --zone "$ZONE" >/dev/null 2>&1 || die "$v missing: run 'rig.sh pools'"; done
  [ -z "$KDB_VM" ] || gc compute instances describe "$KDB_VM" --zone "$ZONE" >/dev/null 2>&1 \
    || die "$KDB_VM missing: create it (scripts/demo-kdb/README.md) or run with KDB_VM="
}

# ---- gateways held at 0 --------------------------------------------------------------------------------------------
hold_gateways(){
  step "gateways held at 0: no gateway runs before the restore gate passes"
  autoscale_off
  do_ kubectl --context "$CTX" -n "$NS" scale deploy/cluster-gateway --replicas=0
  wait_for 120 "no gateway pod" no_pods cluster-gateway
}

# ---- members held at 0 -------------------------------------------------------------------------------------------
# A StatefulSet left at 3 replicas while its nodes were at 0 has Pending members, and they would start the moment
# engine-pool has nodes: before G-pre and the restore gate. So they are held at 0 first, like the gateways.
hold_members(){
  step "members held at 0 until their past error logs are read (G-pre)"
  do_ kubectl --context "$CTX" -n "$NS" scale statefulset/order-matcher-cluster --replicas=0
  wait_for 300 "no member pod" no_pods order-matcher-cluster
}
# The running members are all Ready and are the ones the restore gate passed (ConfigMap rig-gate, pod UIDs).
members_gated(){
  [ "$DRY" = 1 ] && return 1
  local status recorded now
  ready_replicas sts/order-matcher-cluster 3 || return 1
  status=$(kc get configmap rig-gate -o jsonpath='{.data.status}' 2>/dev/null || true)
  recorded=$(kc get configmap rig-gate -o jsonpath='{.data.members}' 2>/dev/null | tr ' ' '\n' | grep . | sort | tr '\n' ' ')
  now=$(kc get pods -l app=order-matcher-cluster -o jsonpath='{range .items[*]}{.metadata.uid}{"\n"}{end}' | grep . | sort | tr '\n' ' ')
  [ "$status" = PASS ] && [ -n "$now" ] && [ "$recorded" = "$now" ]
}

# ---- read-model and member fingerprints ------------------------------------------------------------------------------
# order_status_is <account> <ref> <status> (bearer $ORDER_TOKEN, passed on stdin, never on argv): trade-processor's order read model, the one FIX status
# requests read (FR-FIX04): the memory order view in snapshot mode (ORDER_FEED_PERSIST=false never writes SQL
# orderbook, production 2026-11-07), the orderbook rows otherwise. One effect end for either render.
order_status_is(){ kc exec -i deploy/trade-processor -- sh -c 'read -r t; curl -sf -m10 -H "Authorization: Bearer $t" \
    "localhost:18091/accounts/$1/orders?status=all"' sh "$1" <<<"$ORDER_TOKEN" \
  | jq -e --arg r "-$2" --arg s "$3" 'any(.[]; (.id | endswith($r)) and .status == $s)' >/dev/null; }
sql(){ kc exec deploy/eod-price-db -c mariadb -- sh -c "mariadb -uroot -p\"\$MARIADB_ROOT_PASSWORD\" -N -B -e \"$1\" traderx" 2>/dev/null; }
md5of(){ python3 -c 'import hashlib,sys; print(hashlib.md5(sys.stdin.buffer.read()).hexdigest()[:16])'; }
# FR-PRG-19: a query that fails is not an empty table. sql() discards stderr, so an empty answer is a failure.
# FR-PRG-27: each table is fingerprinted inside MariaDB in one pass: its row count and two exact sums of each row's md5
# (64-bit halves; SUM over BIGINT UNSIGNED is an exact DECIMAL). That is a multiset hash over every column, as strong a
# comparison as the sorted dump it replaces, but no sort, no result set and no client transfer: the dump of ~3.4M rows
# ran eod-price-db out of memory at shutdown (production 2026-10-30 12:45 pm). QUOTE keeps NULL apart from 'NULL'.
rm_fingerprint(){ local table=$1 cols=$2 row
  row="md5(concat_ws(',',$(sed 's/[^,][^,]*/quote(&)/g' <<<"$cols")))"
  sql "select count(*), sum(cast(conv(left($row,16),16,10) as unsigned)), sum(cast(conv(right($row,16),16,10) as unsigned)) from $table" \
    | tr '\t' ':'; }
read_model(){ local q name fp
  for q in "trades|id,accountid,security,side,quantity,price,state" \
           "positions|accountid,security,quantity,averagecostbasis" \
           "orderbook|orderid,accountid,security,side,status,quantity,remainingquantity"; do
    name=${q%%|*}; fp=$(rm_fingerprint "$name" "${q#*|}") && [[ $fp =~ ^[0-9]+:([0-9]+|NULL):([0-9]+|NULL)$ ]] || return 1
    echo "$name=$fp"
  done
  # Snapshot read model (ADR-097): its committed accounting, without the cursors (an idle partition's follows the head).
  local mode acct; mode=$(tp_mode) || return 1
  if [ "$mode" = snapshot ]; then acct=$(accounting) || return 1
    echo "accounting=$(jq -cS '[(.trades // {} | to_entries[] | .value | del(.cursor, .streamLast)),
      (.orders // {} | to_entries[] | .value | del(.cursor, .streamLast))]' <<<"$acct" | md5of)"; fi; }
sql_ready(){ [ "$(sql 'select 1')" = 1 ]; }
# FR-PRG-22: the read model's mode, failing closed. Unset or "snapshot" (trade-processor's only mode since FR-THT-01) is
# snapshot; "rows" was retired and trade-processor refuses to start on it. A Deployment that cannot be read, or any
# other value, fails, and the gate refuses.
tp_mode(){ local v
  v=$(kc get deploy trade-processor -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="TRADE_PROJECTION_MODE")].value}') \
    || { echo "trade-processor's TRADE_PROJECTION_MODE cannot be read" >&2; return 1; }
  case $v in ''|snapshot) echo snapshot;; *) echo "trade-processor's TRADE_PROJECTION_MODE is '$v' (rows was retired, FR-THT-01)" >&2; return 1;; esac; }
# FR-PRG-22: a failed or empty fetch fails (curl -f: an HTTP error is a failure, not a body to hash).
accounting(){ local a
  a=$(kc exec deploy/trade-processor -- curl -sf -m15 localhost:18091/v2/projection-feed/accounting) && [ -n "$a" ] || return 1
  printf '%s\n' "$a"; }
# Every partition's committed trade and order cursor at its subject's last stream message.
accounting_caught_up(){
  accounting | jq -e '([.trades[]] + [(.orders // {})[]]) | length > 0 and all(.cursor >= .streamLast)' >/dev/null
}

# ---- FR-PRG-28: NATS must not restart under a run ---------------------------------------------------------------------
# 2026-10-30: NATS was OOM-killed 7 times under the binary arm and came back each time. The rig looked up while the FIX
# session store refused every Logon and ~89.7M trade sides were lost. A run is clean only if the NATS pod the rig came up
# with is still the only one, Running, and never restarted; `up` records it (ConfigMap rig-nats), `down` checks it.
nats_problems(){ # nats_problems <recorded pod uid, or empty>: one line per problem, nothing when intact
  local pods
  pods=$(kc get pods -l app=nats -o json) || { echo "the NATS pods could not be listed"; return 0; }
  jq -r --arg uid "$1" '(.items | length) as $n
    | (if $n != 1 then "\($n) NATS pods, want 1: \([.items[] | "\(.metadata.name) \(.status.phase)"] | join(", "))" else empty end),
      (.items[] | select(.status.phase != "Running") | "\(.metadata.name) is \(.status.phase), not Running"),
      (.items[] | .metadata.name as $p | .status.containerStatuses[]? | select(.name == "nats" and .restartCount > 0)
       | "\($p) restarted \(.restartCount)x; last \(.lastState.terminated.reason // "?") (exit \(.lastState.terminated.exitCode // "?")) at \(.lastState.terminated.finishedAt // "?")"),
      (if $uid != "" and $n == 1 and .items[0].metadata.uid != $uid
       then "\(.items[0].metadata.name) is not the NATS pod the rig came up with: it was replaced" else empty end)' <<<"$pods"
}
nats_uid(){ kc get configmap rig-nats -o jsonpath='{.data.uid}' 2>/dev/null || true; }
nats_record(){ # refuse a NATS that already restarted, then record the pod `down` will check
  step "NATS intact (FR-PRG-28): one pod, Running, never restarted; recorded for down"
  if [ "$DRY" = 1 ]; then note "would check and record the NATS pod"; return 0; fi
  local np; np=$(nats_problems "$1")
  [ -z "$np" ] || die "NATS (FR-PRG-28): $np"
  kc create configmap rig-nats --from-literal=uid="$(kc get pods -l app=nats -o jsonpath='{.items[0].metadata.uid}')" \
    --from-literal=at="$(date -u +%FT%TZ)" --dry-run=client -o yaml | kc apply -f - >/dev/null
  note "ok"
}
member(){ # member <i>: applied engineApplied trades orderHash positionHash snapshots
  local h m
  h=$(kc exec "order-matcher-cluster-$1" -c cluster-node -- wget -qO- localhost:8080/health)
  m=$(kc exec "order-matcher-cluster-$1" -c cluster-node -- wget -qO- localhost:8080/metrics)
  printf '%s %s %s %s %s %s\n' "$(jq -r .applied <<<"$h")" "$(jq -r .engineApplied <<<"$h")" "$(jq -r .trades <<<"$h")" \
    "$(awk '/^traderx_book_order_hash/{print $NF}' <<<"$m")" "$(awk '/^traderx_book_position_hash/{print $NF}' <<<"$m")" \
    "$(awk '/^traderx_cluster_snapshots/{print $NF}' <<<"$m")"
}
# snapshots_past <s0> <s1> <s2>: each member past ITS OWN count. The counter is per process, so a member whose pod
# was replaced (a failover) restarts it from 0; one shared baseline could never be passed (2026-11-06, e1106).
snapshots_past(){ local i n b=("$@"); for i in 0 1 2; do n=$(member "$i" | cut -d' ' -f6); [ "${n:-0}" -gt "${b[$i]:-0}" ] || return 1; done; }
admin_token(){ # minted inside trade-processor, so the master secret never leaves the cluster; never printed
  kc exec deploy/trade-processor -- sh -c 'curl -s -m8 -X POST localhost:18091/auth/dev-token \
    -H "X-Auth-Master-Secret: $AUTH_DEV_TOKEN_MASTER_SECRET" -H "Content-Type: application/json" \
    -d "{\"subject\":\"rig-gate\",\"accounts\":[],\"admin\":true,\"ttlSeconds\":600}"' \
    | python3 -c 'import sys,json
s=sys.stdin.read().strip()
try: d=json.loads(s); print(d.get("token") or d.get("accessToken") or d.get("jwt") or "")
except Exception: print(s.strip(chr(34)))'
}

# ---- before the members start: did a past restore load a snapshot partially? (FR-PRG-03, G-pre) ------------------
# Before the FR-ERH-F12 fix a refused or corrupt snapshot record was skipped and the load completed partial; its error
# stayed in the member's cluster error log on the claim. One read-only pod per claim, while no member runs, prints that
# log from both mark files; any such error means that member already came up partial once, and the bring-up stops.
PAST_RESTORE_ERRORS='snapshot corrupt|NEWER|unknown snapshot record type'
# Not `ClusterTool errors`: in Aeron 1.51 it prints the consensus module's log, then throws on the absent driver's
# cnc.dat before it reaches the service container's mark file, which is where a refused snapshot record is logged; and
# it maps the mark file read-write. An error log stores each error's text as UTF-8, so this reads the bytes of both
# mark files on the read-only claim, and fails if either file is missing or unreadable. Its first line names the mark
# files and its last is "errlog complete", so G-pre can tell a whole log from an empty or truncated fetch (FR-PRG-23).
# sh -c "$ERRLOG_SCRIPT" errlog <cluster dir> <pattern>
ERRLOG_SCRIPT='d=$1
c=$(ls "$d"/cluster-mark.dat 2>/dev/null | wc -l | tr -d " "); s=$(ls "$d"/cluster-mark-service-*.dat 2>/dev/null | wc -l | tr -d " ")
echo "mark files in $d: consensus=$c service=$s"
[ "$c" -ge 1 ] && [ "$s" -ge 1 ] || { echo "no consensus or no service mark file"; exit 3; }
for f in "$d"/cluster-mark.dat "$d"/cluster-mark-service-*.dat; do
  [ -r "$f" ] || { echo "cannot read $f"; exit 4; }
  out=$(grep -aoiE "[[:print:]]{0,160}($2)[[:print:]]{0,160}" "$f"); rc=$?
  [ "$rc" -le 1 ] || { echo "cannot read $f"; exit 4; }
  [ -z "$out" ] || printf "%s\n" "$out" | sed "s|^|$f: |"
done
echo "errlog complete"'
errlog_pod(){ # errlog_pod <ordinal> <image>: the pod manifest
  cat <<EOF
apiVersion: v1
kind: Pod
metadata: {name: rig-errlog-$1, namespace: $NS, labels: {app: rig-errlog}}
spec:
  restartPolicy: Never
  nodeSelector: {cloud.google.com/gke-nodepool: engine-pool}
  tolerations: [{key: traderx-engine, operator: Equal, value: "true", effect: NoSchedule}]
  containers:
  - name: errlog
    image: $2
    command:
    - sh
    - -c
    - |
$(printf '%s\n' "$ERRLOG_SCRIPT" | sed 's/^/      /')
    - errlog
    - /data/cluster
    - '$PAST_RESTORE_ERRORS'
    resources: {requests: {cpu: 250m, memory: 128Mi}, limits: {cpu: "1", memory: 128Mi}}
    volumeMounts: [{name: data, mountPath: /data, readOnly: true}]
  volumes: [{name: data, persistentVolumeClaim: {claimName: data-order-matcher-cluster-$1, readOnly: true}}]
EOF
}
pod_done(){ case "$(kc get pod "$1" -o jsonpath='{.status.phase}' 2>/dev/null)" in Succeeded|Failed) return 0;; *) return 1;; esac; }
past_restore_errors(){
  step "before the members start: no member's error log holds a refused snapshot record (G-pre)"
  if [ "$DRY" = 1 ]; then
    note "would run per claim data-order-matcher-cluster-{0,1,2}: a read-only pod reading both mark files' error logs"
    note "would refuse on: $PAST_RESTORE_ERRORS, or a pod that cannot read the log"; return 0; fi
  [ "$(kc get sts order-matcher-cluster -o jsonpath='{.status.replicas}')" = 0 ] || die "G-pre: members are running"
  local es; es=$(epoch_state) || die "G-pre: ConfigMap rig-epoch cannot be read"
  if [ "${es%% *}" = provisioned ]; then
    # FR-PRG-29: up turns wiped into provisioned only with no member claim, so claims under provisioned are this epoch's
    # own, from an up refused after the members started (production 2026-11-07). They are read like any other, and G0
    # still refuses unless the members are empty; no hand deletion.
    local n; n=$(kc get pvc -o name) || die "G-pre: the claims cannot be listed"
    n=$(grep -c '/data-order-matcher-cluster-' <<<"$n" || true)
    [ "$n" = 0 ] && { note "G-pre: fresh epoch ${es#* }: no member claim, so no past error log to read"; return 0; }
    [ "$n" = 3 ] || die "G-pre: epoch ${es#* } is provisioned but $n of 3 member claims exist"
    note "G-pre: fresh epoch ${es#* }: its own three claims from an earlier start; G0 must find them empty"
  fi
  local image i phase
  image=$(kc get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].image}')
  for i in 0 1 2; do
    kc delete pod "rig-errlog-$i" --ignore-not-found --wait >/dev/null
    errlog_pod "$i" "$image" | kc apply -f - >/dev/null
    wait_for 300 "member $i's error log read" pod_done "rig-errlog-$i"
    phase=$(kc get pod "rig-errlog-$i" -o jsonpath='{.status.phase}')
    # FR-PRG-23: a failed fetch is a log not read, never an empty one; the pod is kept for a look.
    kc logs "rig-errlog-$i" > "$EVIDENCE/past-errors-$i.txt" 2> "$EVIDENCE/past-errors-$i.err" \
      || die "G-pre: member $i's error log could not be fetched (pod rig-errlog-$i kept): $EVIDENCE/past-errors-$i.err"
    kc delete pod "rig-errlog-$i" --wait >/dev/null
    [ "$phase" = Succeeded ] || die "G-pre: member $i's error log could not be read ($phase): $EVIDENCE/past-errors-$i.txt"
    ! grep -Eiq "$PAST_RESTORE_ERRORS" "$EVIDENCE/past-errors-$i.txt" \
      || die "G-pre: member $i restored a snapshot partially in the past: $EVIDENCE/past-errors-$i.txt"
    grep -q '^mark files in ' "$EVIDENCE/past-errors-$i.txt" && [ "$(tail -n 1 "$EVIDENCE/past-errors-$i.txt")" = "errlog complete" ] \
      || die "G-pre: member $i's error log is incomplete (no reader start or end line): $EVIDENCE/past-errors-$i.txt"
  done
  note "G-pre ok: no refused snapshot record in any member's error log"
}

# ---- G3 for the snapshot read model (ADR-097, FR-PRG-21) ----------------------------------------------------------------
# SQL holds snapshots, not a row per trade side, so check B reads the committed accounting (FR-DRS-29/30) instead. It is
# exact in O(P): a partition refuses a side whose tradeSeq is not above its last (duplicates), so the booked sides are
# distinct; then count, highest and the wrapped sums of seq and seq^2 must equal the closed form over the expected set,
# (base, trades] minus the `rig.sh unprojected` ranges. base is the rows-era count recorded at the switch to snapshot
# mode (ConfigMap rig-snapshot-base, kept until the epoch is archived); absent, it is 0: since rows mode was retired
# (FR-THT-01) every read model is born in snapshot mode, and a wrong 0 fails closed (the closed form disagrees). The order view's committed open orders
# must equal the members' restored openOrders. Every committed cursor must have reached its subject's last message.
g3_snapshot(){
  local t=$1 digest=$2 acct base ranges verdict
  base=$(kc get configmap rig-snapshot-base -o jsonpath='{.data.base}' 2>/dev/null || true)
  base=${base:-0}
  [[ $base =~ ^[0-9]+$ ]] || die "G3 (snapshot read model): rig-snapshot-base holds '$base', not a number"
  accounting >/dev/null || die "G3 (snapshot read model): the accounting could not be read (/v2/projection-feed/accounting)"
  wait_for 600 "every read-model partition committed up to the stream's last message (G3)" accounting_caught_up
  acct=$(accounting) || die "G3 (snapshot read model): the accounting could not be read (/v2/projection-feed/accounting)"
  echo "$acct" > "$EVIDENCE/gate-accounting.json"
  ranges=$(kc get configmap rig-unprojected -o jsonpath='{.data.ranges}' 2>/dev/null || true)
  verdict=$(python3 -c '
import json,sys
acct,t,base,ranges,open_=json.loads(sys.argv[1]),int(sys.argv[2]),int(sys.argv[3] or 0),sys.argv[4].split(),int(sys.argv[5])
M=1<<64
def S(k,n): return [n, n*(n+1)//2, n*(n+1)*(2*n+1)//6][k]       # sum of i^k, i=1..n
def span(k,a,b): return S(k,b)-S(k,a-1)                          # sum of i^k, i=a..b
want=[span(k,base+1,t) for k in range(3)]
for r in ranges:
    a,b=map(int,r.split("-")); a,b=max(a,base+1),min(b,t)
    if a<=b: want=[w-span(k,a,b) for k,w in enumerate(want)]
tr=list(acct["trades"].values())
got=[sum(p["sides"] for p in tr), sum(int(p["sumSeq"]) for p in tr)%M, sum(int(p["sumSq"]) for p in tr)%M]
dups=sum(p["duplicates"] for p in tr); top=max([p["maxSeq"] for p in tr] or [0])
resting=sum(p.get("resting",0) for p in (acct.get("orders") or {}).values())
bad=[]
if dups: bad.append(f"{dups} duplicate side(s) refused")
if top>t: bad.append(f"highest tradeSeq {top} above the members trades {t}")
if got!=[want[0],want[1]%M,want[2]%M]: bad.append(f"booked {got[0]} sides (sums {got[1]},{got[2]}), want {want[0]} ({want[1]%M},{want[2]%M})")
if "orders" not in acct: bad.append("no order view snapshot (ORDER_VIEW_SNAPSHOT_MS unset): open orders cannot be checked")
elif resting!=open_: bad.append(f"order view holds {resting} open orders, the members restored {open_}")
print("; ".join(bad) or f"OK {got[0]} sides")
' "$acct" "$t" "$base" "$ranges" "$(jq -r .openOrders <<<"$digest")")
  [ "${verdict%% *}" = OK ] || die "G3 (snapshot read model): $verdict (base ${base:-0}, unprojected ${ranges:-none}): $EVIDENCE/gate-accounting.json"
  note "G3 ok: the read model booked sides $((${base:-0} + 1))..$t${ranges:+ except $ranges}, each once; $(jq -r .openOrders <<<"$digest") open orders"
}

# ---- the history store's check (FR-THT-06, ADR-102) -----------------------------------------------------------------
# ClickHouse's trades are checked like the snapshot accounting: per partition the count, highest trade_seq, wrapped sums
# of trade_seq and trade_seq^2 (UInt64 arithmetic wraps mod 2^64 as the closed form does) and the duplicates, summed and
# compared with the closed form over the epoch's sides 1..t minus the unprojected ranges. The store is fed from cursor 0
# (FR-THT-07), so there is no base. A deleted row breaks the count, a duplicated one is counted, a swapped one the sums.
HISTORY_CHECK_SQL="SELECT part, count(), max(trade_seq), sum(trade_seq), sum(trade_seq * trade_seq), count() - uniqExact(trade_seq) FROM trades GROUP BY part ORDER BY part FORMAT TSV"
history_health(){ kc exec deploy/trade-processor -- curl -sf -m15 http://history-feed:18095/health; }
# Every lane open and nothing pending: with the gateways at 0 the store then holds all the stream has.
history_caught_up(){ history_health | jq -e '.ready and (.lanes | length > 0 and all(.pending == 0))' >/dev/null; }
# The verdict on HISTORY_CHECK_SQL's rows for members' trades t and the unprojected ranges: "OK n sides" or why not.
history_verdict(){ python3 -c '
import sys
t,ranges,rows=int(sys.argv[1]),sys.argv[2].split(),[l.split("\t") for l in sys.argv[3].splitlines() if l.strip()]
M=1<<64
def S(k,n): return [n, n*(n+1)//2, n*(n+1)*(2*n+1)//6][k]
want=[S(k,t) for k in range(3)]
for r in ranges:
    a,b=map(int,r.split("-")); a,b=max(a,1),min(b,t)
    if a<=b: want=[w-(S(k,b)-S(k,a-1)) for k,w in enumerate(want)]
got=[sum(int(r[1]) for r in rows), sum(int(r[3]) for r in rows)%M, sum(int(r[4]) for r in rows)%M]
dups=sum(int(r[5]) for r in rows); top=max([int(r[2]) for r in rows] or [0])
bad=[]
if dups: bad.append(f"{dups} duplicated trade side(s)")
if top>t: bad.append(f"highest trade_seq {top} above the members trades {t}")
if got!=[want[0],want[1]%M,want[2]%M]: bad.append(f"holds {got[0]} sides (sums {got[1]},{got[2]}), want {want[0]} ({want[1]%M},{want[2]%M})")
print("; ".join(bad) or f"OK {got[0]} sides")
' "$@"; }
g3_history(){
  local t=$1 ranges rows verdict
  # FR-THT-02 renders the store into the production manifests: its absence is a refusal, never a skip.
  kc get sts clickhouse -o name >/dev/null 2>&1 || die "G3h: no history store (sts/clickhouse) on $NS (FR-THT-02)"
  wait_for 600 "every history-feed lane open with nothing pending (G3h)" history_caught_up
  # Nothing pending is also true of a lane whose filter matches nothing (production 2026-11-07: lanes on /trades.0 while
  # the members published /trades; 0 rows). history-feed names the stream subjects no lane reads; any is a refusal.
  local health unowned
  health=$(history_health) || die "G3h: history-feed's health could not be read"
  unowned=$(jq -ce '.unowned | objects' <<<"$health") \
    || die "G3h: history-feed reports no unread-subject check (an image before it, or NATS unreadable: $(jq -r '.unownedError // "none"' <<<"$health"))"
  [ "$unowned" = '{}' ] || die "G3h: no history-feed lane reads $unowned on the capture stream: the store misses those messages (FR-THT-02)"
  rows=$(kc exec -i deploy/trade-processor -- curl -sf -m120 --data-binary @- http://clickhouse:8123/ <<<"$HISTORY_CHECK_SQL") \
    || die "G3h: the history store could not be read"
  printf '%s\n' "$rows" > "$EVIDENCE/gate-history.tsv"
  ranges=$(kc get configmap rig-unprojected -o jsonpath='{.data.ranges}' 2>/dev/null || true)
  verdict=$(history_verdict "$t" "$ranges" "$rows")
  [ "${verdict%% *}" = OK ] || die "G3h (history store): $verdict (unprojected ${ranges:-none}): $EVIDENCE/gate-history.tsv"
  note "G3h ok: the history store holds trade sides 1..$t${ranges:+ except $ranges}, each once"
}

# ---- the restore gate (FR-PRG-03) --------------------------------------------------------------------------------
# Applied alone proves nothing: a snapshot load that skipped a record still ends at the full log's applied. The
# reference never comes from a snapshot: (A) a replay from log position 0, (B) the read model, which the members do
# not write while the gateways are at 0, and, when the last `down` recorded one, (C) the shutdown baseline.
restore_gate(){
  local before=$1
  step "restore gate (gateways at 0 until it passes)"
  if [ "$DRY" = 1 ]; then
    note "would check: G0 all three members agree on applied, engineApplied, trades, order and position hashes"
    note "would check: G1 each member's replay from 0 reaches its applied and reproduces its trade count"
    note "would check: G2 (check A) each member's $GATE_SHADOW_PATH book/position hashes equal its own; refuse if absent"
    note "would check: G3 (check B) read-model md5s equal those read before the members started"
    note "would check: G3h (FR-THT-06) the history store holds sides 1..trades each once; no sts/clickhouse is refused"
    note "would check: G4 the members equal the rig-shutdown-baseline ConfigMap, when one exists"
    note "would record: ConfigMap rig-gate (PASS, member pod UIDs)"
    return 0
  fi
  local m0 m1 m2 token i a e t oh ph rep shadow after base
  m0=$(member 0); m1=$(member 1); m2=$(member 2)
  printf 'member0 %s\nmember1 %s\nmember2 %s\n' "$m0" "$m1" "$m2" | tee "$EVIDENCE/gate-members.txt"
  [ "$(cut -d' ' -f1-5 <<<"$m0")" = "$(cut -d' ' -f1-5 <<<"$m1")" ] && [ "$(cut -d' ' -f1-5 <<<"$m0")" = "$(cut -d' ' -f1-5 <<<"$m2")" ] \
    || die "G0: the members disagree (gateways stay at 0): $EVIDENCE/gate-members.txt"
  read -r a e t oh ph _ <<<"$m0"
  local es fresh=0; es=$(epoch_state) || die "G0: ConfigMap rig-epoch cannot be read"
  if [ "${es%% *}" = provisioned ]; then
    # FR-PRG-29: a fresh epoch's members start empty; anything else under a provisioned record is refused.
    [ "$a" -le 0 ] && [ "$t" = 0 ] || die "G0: epoch ${es#* } is provisioned (empty) but the members hold applied $a, trades $t"
    fresh=1; note "G0 ok: fresh epoch ${es#* }: all three empty (applied $a, trades 0); G1 and G2 have nothing to replay"
  fi
  local digest='' open_rows shadow='' cap g1_deferred=0
  if [ "$fresh" = 0 ]; then
  # engineApplied is the engine's blpSeq: equal to applied once an engine event has applied, and -1 on a member
  # restored from its snapshot into an idle cluster, which has applied no engine event since (ClusterNodeMain /health).
  [ "$a" -gt 0 ] && { [ "$e" = "$a" ] || [ "$e" = -1 ]; } || die "G0: applied $a, engineApplied $e"
  note "G0 ok: applied $a, trades $t, hashes $oh $ph on all three"
  # FR-PRG-25: each member's replay request gets a token minted just before it. A strict replay of the log takes minutes
  # per member, so one token minted up front expires (ttlSeconds 600) before the last member is asked, and its 401 reads
  # as "no endpoint".
  # FR-PRG-18: the full-history index refuses past RECON_FULL_HISTORY_MAX trades (members' default 200000), so above it
  # G1 cannot answer; it defers to G2, whose whole-log replay into a shadow proves the same property by hashes, uncapped.
  cap=$(kc get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[?(@.name=="cluster-node")].env[?(@.name=="RECON_FULL_HISTORY_MAX")].value}')
  [[ "${cap:=200000}" =~ ^[0-9]+$ ]] || die "G1: RECON_FULL_HISTORY_MAX is not a number: $cap"
  if [ "$t" -gt "$cap" ]; then
    g1_deferred=1
    note "G1 deferred to G2: $t trades are over the full-history index cap $cap (RECON_FULL_HISTORY_MAX)"
    echo "G1 deferred: trades $t > RECON_FULL_HISTORY_MAX $cap" >> "$EVIDENCE/gate-g1.txt"
  else
    for i in 0 1 2; do
      token=$(admin_token); [ -n "$token" ] || die "G1: could not mint an admin token in trade-processor"
      rep=$(kc exec -i "order-matcher-cluster-$i" -c cluster-node -- sh -c 'read -r t; wget -qO- --post-data="" \
        --header="Authorization: Bearer $t" http://localhost:8080/recon/full-history/reindex' <<<"$token") \
        || die "G1: member $i's replay request failed (HTTP error)"
      [ "$(jq -r .replayedAppliedSeq <<<"$rep")" = "$a" ] && [ "$(jq -r .indexedTrades <<<"$rep")" = "$t" ] \
        || die "G1: member $i's replay from 0 gives $(jq -c '{replayedAppliedSeq,indexedTrades}' <<<"$rep"), the restored state applied $a trades $t"
    done
    note "G1 ok: each replay from 0 reaches applied $a with $t trades"
  fi
  # G2, check A (FR-ERH-F16): a strict replay of the whole log into a fresh shadow must hold exactly the restored state,
  # in the terms of /health?digest=1. Hashes are decimal strings (a 64-bit hash read as a JSON number loses digits).
  for i in 0 1 2; do
    token=$(admin_token); [ -n "$token" ] || die "G2: could not mint an admin token in trade-processor"
    shadow=$(kc exec -i "order-matcher-cluster-$i" -c cluster-node -- sh -c "read -r t; wget -qO- \
      --header=\"Authorization: Bearer \$t\" http://localhost:8080$GATE_SHADOW_PATH" <<<"$token" 2>/dev/null || true)
    if [ -z "$shadow" ] || ! jq -e '.bookHash and .positionHash' <<<"$shadow" >/dev/null 2>&1; then
      [ "${GATE_ALLOW_NO_SHADOW:-0}" = 1 ] && [ "$NS" != traderx ] && [ "$g1_deferred" = 0 ] \
        || die "G2: member $i has no $GATE_SHADOW_PATH (check A); this build cannot prove its snapshot restore"
      note "G2 SKIPPED on $NS (GATE_ALLOW_NO_SHADOW=1, never allowed on traderx)"; shadow=''; break
    fi
    digest=$(kc exec "order-matcher-cluster-$i" -c cluster-node -- wget -qO- 'localhost:8080/health?digest=1')
    echo "member$i shadow $shadow digest $digest" >> "$EVIDENCE/gate-shadow.txt"
    [ "$(jq -r .complete <<<"$shadow")" = true ] && [ "$(jq -r .applied <<<"$shadow")" = "$a" ] \
      || die "G2: member $i's replay is not complete at applied $a: $shadow"
    [ "$(jq -c '{bookHash,positionHash,openOrders,positions,digestTrades}' <<<"$shadow")" \
      = "$(jq -c '{bookHash,positionHash,openOrders,positions,digestTrades}' <<<"$digest")" ] \
      || die "G2: member $i's restored state differs from its replay from 0: $EVIDENCE/gate-shadow.txt"
  done
  [ -z "$shadow" ] || note "G2 ok: each member's replay from 0 holds its restored book, positions and trades"
  fi
  local mode; mode=$(tp_mode) || die "G3: trade-processor's projection mode is unreadable or unknown; neither G3 is assumed (FR-PRG-22)"
  [ "$mode" = snapshot ] || die "G3: trade-processor's projection mode is '$mode'"
  [ -n "$digest" ] || digest=$(kc exec order-matcher-cluster-0 -c cluster-node -- wget -qO- 'localhost:8080/health?digest=1')
  # The closed form counts one tradeSeq numbering from 1; a managed run restarts it per epoch, so it is refused.
  [ "$(jq -r '.projectionScope // "null"' <<<"$digest")" = null ] \
    || die "G3: the members run a managed projection scope; the accounting check reads one legacy tradeSeq numbering"
  g3_snapshot "$t" "$digest"
  after=$(read_model) || die "G3: the read model could not be read after the members started"
  echo "$after" > "$EVIDENCE/read-model-after.txt"
  [ "$after" = "$before" ] || die "G3: the read model moved while the members started: $EVIDENCE/read-model-{before,after}.txt"
  note "G3 ok: read model unchanged"
  g3_history "$t"
  # FR-PRG-23: any failed read, or a baseline that exists without a well-formed members line, refuses: a read error
  # must never stand in for "no baseline". FR-PRG-29: nor may a missing one. Only a provisioned fresh epoch (whose
  # baseline `rig.sh fresh-epoch` moved into its history) starts without one.
  local cm base
  if cm=$(kc get configmap rig-shutdown-baseline -o json 2> "$EVIDENCE/g4-baseline.err"); then
    [ "$fresh" = 0 ] || die "G4: epoch ${es#* } is provisioned (empty) but a shutdown baseline exists"
    base=$(jq -r '.data.members // ""' <<<"$cm") || die "G4: rig-shutdown-baseline is not readable JSON"
    rig_shutdown_baseline_ok "$base" \
      || die "G4: rig-shutdown-baseline exists but its members line is empty or malformed: '$base'"
    [ "$(cut -d' ' -f1,3-5 <<<"$base")" = "$(cut -d' ' -f1,3-5 <<<"$m0")" ] \
      || die "G4: shutdown baseline '$base' != restored '$m0'"
    note "G4 ok: equals the shutdown baseline"
  elif grep -q '(NotFound)' "$EVIDENCE/g4-baseline.err"; then
    [ "$fresh" = 1 ] || die "G4: no shutdown baseline, and no provisioned fresh epoch (rig.sh fresh-epoch): a missing baseline is not a first bring-up"
    note "G4 ok: fresh epoch ${es#* }: no baseline (the old one is in rig-epoch-${es#* }-history)"
  else
    die "G4: rig-shutdown-baseline could not be read: $(head -c 300 "$EVIDENCE/g4-baseline.err")"
  fi
  kc create configmap rig-gate --from-literal=status=PASS --from-literal=at="$(date -u +%FT%TZ)" \
    --from-literal=members="$(kc get pods -l app=order-matcher-cluster -o jsonpath='{range .items[*]}{.metadata.uid} {end}')" \
    --dry-run=client -o yaml | kc apply -f - >/dev/null
  note "gate PASS recorded in ConfigMap rig-gate"
  if [ "$fresh" = 1 ]; then set_epoch "${es#* }" consumed; note "epoch ${es#* } consumed: from now on G4 needs its baseline"; fi
}

# A members line as `rig.sh down` records it: applied engineApplied trades orderHash positionHash snapshots.
rig_shutdown_baseline_ok(){ [[ $1 =~ ^[0-9]+\ -?[0-9]+\ [0-9]+\ [^\ ]+\ [^\ ]+\ [0-9]+$ ]]; }

# ---- a fresh epoch (FR-PRG-29, ADR-101): archive the member disks, wipe them, archive the read model ---------------
# ConfigMap rig-epoch {label, state}: wiped (claims deleted; the read model not yet archived) -> archived (the read
# model and history store in arc_<label>_*, the capture streams deleted; trade-processor and history-feed not yet
# restarted) -> provisioned (both answering; the members start empty) -> consumed (a restore gate passed on the empty
# epoch). archived exists so a retry skips the archive: the restarted trade-processor writes its seq-0 cursors into the
# live tables, and an archive re-run would refuse them as not empty (production 2026-11-08, e1108).
# The gates accept an empty epoch, and a missing shutdown baseline, only while it is provisioned.
EPOCH_TABLES="trades positions orderbook order_view_snapshot projection_accounting projection_cursor projection_trade_days projection_recon"
epoch_label_ok(){ [[ $1 =~ ^[a-z][a-z0-9]{0,15}$ ]]; }
epoch_state(){ # "<state> <label>", "none" when no record; fails when the record cannot be read (never "none")
  local cm err; err=$(mktemp) || return 1
  if cm=$(kc get configmap rig-epoch -o json 2>"$err"); then rm -f "$err"
    jq -r '"\(.data.state // "") \(.data.label // "")"' <<<"$cm"
  elif grep -q '(NotFound)' "$err"; then rm -f "$err"; echo none
  else rm -f "$err"; return 1; fi; }
set_epoch(){ kc create configmap rig-epoch --from-literal=label="$1" --from-literal=state="$2" \
  --from-literal=at="$(date -u +%FT%TZ)" --dry-run=client -o yaml | kc apply -f - >/dev/null; }
# The members and the gateways only: trade-processor and history-feed are not members, and a refused fresh-epoch up may
# leave them at 0 (archive_read_model restarts them at their recorded counts).
members_stopped(){ [ "$(kc get sts order-matcher-cluster -o jsonpath='{.spec.replicas}')" = 0 ] \
  && no_pods order-matcher-cluster && no_pods cluster-gateway; }
member_disk(){ # member_disk <i>: "<zone> <disk>" of the claim's persistent disk
  local pv h; pv=$(kc get pvc "data-order-matcher-cluster-$1" -o jsonpath='{.spec.volumeName}') && [ -n "$pv" ] || return 1
  h=$(kubectl --context "$CTX" get pv "$pv" -o jsonpath='{.spec.csi.volumeHandle}') || return 1
  [[ $h =~ ^projects/[^/]+/zones/([^/]+)/disks/([^/]+)$ ]] || return 1
  echo "${BASH_REMATCH[1]} ${BASH_REMATCH[2]}"; }
snapshot_of(){ # snapshot_of <name>: "<status> <source disk url>", empty when absent; fails on any other error
  local out err; err=$(mktemp) || return 1
  if out=$(gc compute snapshots describe "$1" --format='value(status,sourceDisk)' 2>"$err"); then rm -f "$err"; echo "$out"
  elif grep -qi 'was not found' "$err"; then rm -f "$err"
  else rm -f "$err"; return 1; fi; }
snapshot_ready(){ [ "$(snapshot_of "$1" | cut -f1)" = READY ]; }
claims_gone(){ [ -z "$(kc get pvc -o name | grep '/data-order-matcher-cluster-')" ]; }
disks_gone(){ local d; for d in "$@"; do gc compute disks describe "$d" --zone "$ZONE" >/dev/null 2>&1 && return 1; done; return 0; }

rig_archive_members(){
  local label=${1:-} i zone disk name have
  epoch_label_ok "$label" || die "archive-members: want a label [a-z][a-z0-9]{0,15}, got '$label'"
  preflight
  step "member disks to GCE snapshots traderx-$label-m{0,1,2} (the old epoch stays replayable)"
  members_stopped || die "archive-members: members and gateways must be stopped first (rig.sh down)"
  for i in 0 1 2; do
    read -r zone disk <<<"$(member_disk "$i")"; [ -n "${disk:-}" ] || die "archive-members: member $i's claim or disk cannot be resolved"
    name=traderx-$label-m$i
    have=$(snapshot_of "$name") || die "archive-members: snapshot $name cannot be read"
    if [ -n "$have" ]; then
      [[ $(cut -f2 <<<"$have") == */zones/$zone/disks/$disk ]] || die "archive-members: $name exists but is not of member $i's disk $disk"
      note "$name exists, of $disk"
    else
      do_ gcloud compute snapshots create "$name" --source-disk "$disk" --source-disk-zone "$zone" \
        --labels "traderx-epoch=$label,traderx-member=$i" --project "$PROJECT" --quiet
    fi
    wait_for 3600 "$name READY" snapshot_ready "$name"
  done
}

rig_fresh_epoch(){
  local label=${1:-} es st lbl i zone disk have disks='' c data args=()
  epoch_label_ok "$label" || die "fresh-epoch: want a label [a-z][a-z0-9]{0,15}, got '$label'"
  preflight
  step "fresh epoch $label: wipe the member claims; the next rig.sh up archives the read model"
  members_stopped || die "fresh-epoch: members and gateways must be stopped first (rig.sh down)"
  es=$(epoch_state) || die "fresh-epoch: ConfigMap rig-epoch cannot be read"
  read -r st lbl <<<"$es"
  case $st in
    none|consumed|initialized) ;;
    archived) die "fresh-epoch: epoch $lbl is archived and not yet provisioned; finish it with rig.sh up first";;
    wiped) [ "$lbl" = "$label" ] || die "fresh-epoch: epoch $lbl is wiped and not yet provisioned; finish it first"
      note "resuming epoch $label (claims already being wiped)";;
    *) die "fresh-epoch: epoch $lbl is $st; it must pass a restore gate (consumed) first";;
  esac
  if [ "$st" != wiped ]; then
    # Preconditions, all before anything is changed: every member disk is in a READY snapshot of exactly that disk.
    for i in 0 1 2; do
      read -r zone disk <<<"$(member_disk "$i")"; [ -n "${disk:-}" ] || die "fresh-epoch: member $i's claim or disk cannot be resolved"
      have=$(snapshot_of "traderx-$label-m$i") || die "fresh-epoch: snapshot traderx-$label-m$i cannot be read"
      [ "$(cut -f1 <<<"$have")" = READY ] && [[ $(cut -f2 <<<"$have") == */zones/$zone/disks/$disk ]] \
        || die "fresh-epoch: no READY snapshot traderx-$label-m$i of member $i's disk $disk (rig.sh archive-members $label)"
      disks="$disks $disk"
    done
    # The old epoch's gate records move into one history ConfigMap; written with create, so never overwritten.
    for c in rig-shutdown-baseline rig-unprojected rig-snapshot-base rig-gate; do
      if data=$(kc get configmap "$c" -o json 2>/dev/null); then args+=(--from-literal="$c=$(jq -c '.data // {}' <<<"$data")"); fi
    done
    [ "$DRY" = 1 ] || kc create configmap "rig-epoch-$label-history" "${args[@]}" --from-literal=disks="${disks# }" >/dev/null \
      || die "fresh-epoch: rig-epoch-$label-history cannot be created (does it exist already?)"
    [ "$DRY" = 1 ] || set_epoch "$label" wiped
    for c in rig-shutdown-baseline rig-unprojected rig-snapshot-base rig-gate; do do_ kubectl --context "$CTX" -n "$NS" delete configmap "$c" --ignore-not-found; done
  else
    disks=$(kc get configmap "rig-epoch-$label-history" -o jsonpath='{.data.disks}') || die "fresh-epoch: rig-epoch-$label-history cannot be read"
  fi
  do_ kubectl --context "$CTX" -n "$NS" delete pvc data-order-matcher-cluster-0 data-order-matcher-cluster-1 data-order-matcher-cluster-2 --ignore-not-found --wait=false
  # The StatefulSet is at 0, so nothing re-binds a claim; still, wait until none exists and every disk is gone.
  wait_for 600 "member claims deleted" claims_gone
  wait_for 900 "member disks deleted (reclaim Delete):$disks" disks_gone $disks
  note "epoch $label: claims wiped; rig.sh up archives the read model, then starts the members empty"
}

# FR-PRG-29 (production 2026-11-07): the fresh epoch's read model starts at cursor 0, so its capture streams must start at
# seq 1. The old TRADERX_FEED (since 2026-10-28) held 0 messages from first_seq 371203273 (all aged out), and the snapshot
# trade store rightly refused with RETENTION_GAP. Read from NATS's monitor, deleted by one JetStream API request over the
# client protocol (busybox nc in the nats pod: no other image). Deleting a stream deletes its consumers: trade-processor
# recreates the stream and its tp-* durables on start, and the gateways' fill feed is an ordered (ephemeral) consumer.
feed_streams(){ # "<stream> <messages>" per capture stream, TRADERX_FEED or TRADERX_FEED_P<p>; fails when NATS cannot be read
  local j; j=$(kc exec deploy/nats -c nats -- wget -qO- 'http://localhost:8222/jsz?accounts=true&streams=true') || return 1
  jq -e 'has("streams")' <<<"$j" >/dev/null || return 1   # a JetStream report, not an error page
  jq -r '.account_details[]?.stream_detail[]? | select(.name | test("^TRADERX_FEED(_P[0-9]+)?$")) | "\(.name) \(.state.messages)"' <<<"$j"; }
nats_api(){ # nats_api <subject>: one JetStream API request, its JSON reply
  kc exec deploy/nats -c nats -- sh -c '(printf "CONNECT {\"verbose\":false}\r\nSUB r 1\r\nPUB %s r 0\r\n\r\n" "$1"; sleep 2) | nc localhost 4222' sh "$1" \
    | grep -o '^{.*}'; }
reset_feed(){
  local streams s n
  streams=$(feed_streams) || die "fresh epoch: the capture streams cannot be listed"
  while read -r s n; do
    [ -z "$s" ] || [ "$n" = 0 ] || die "fresh epoch: $s still holds $n messages of the old epoch; it must be empty (aged out, or reconciled and purged) before the epoch restarts it at seq 1"
  done <<<"$streams"
  while read -r s n; do
    [ -n "$s" ] || continue
    nats_api "\$JS.API.STREAM.DELETE.$s" | jq -e '.success == true' >/dev/null || die "fresh epoch: $s could not be deleted"
    note "capture stream $s (0 messages) deleted; trade-processor recreates it at seq 1"
  done <<<"$streams"
}

# FR-PRG-29: a stopped Deployment restarts at the count recorded on it when the archive first stopped it, never the live
# count (production 2026-11-08: a refused up had left trade-processor and history-feed at 0, and the retry "restored" 0).
HELD=traderx-rig-held-replicas
hold(){ # hold <deploy>: record its replica count on it, once (a retry keeps the first), then stop it
  local r; r=$(kc get deploy "$1" -o jsonpath="{.metadata.annotations.$HELD}") || die "read-model archive: $1 cannot be read"
  if [ -z "$r" ]; then
    r=$(kc get deploy "$1" -o jsonpath='{.spec.replicas}') || die "read-model archive: $1 cannot be read"
    [[ $r =~ ^[1-9][0-9]*$ ]] || die "read-model archive: $1 is at ${r:-no} replicas with none recorded: scale it to its rendered count, then rerun"
    kc annotate deploy "$1" "$HELD=$r" --overwrite >/dev/null || die "read-model archive: $1's replica count cannot be recorded"
  fi
  kc scale "deploy/$1" --replicas=0 >/dev/null || die "read-model archive: $1 cannot be stopped"
  wait_for 300 "$1 stopped (restarts at $r)" no_pods "$1"; }
release(){ # release <deploy>: back to its recorded count, the record removed; with none, an earlier retry released it
  local r; r=$(kc get deploy "$1" -o jsonpath="{.metadata.annotations.$HELD}") || die "read-model restart: $1 cannot be read"
  if [ -z "$r" ]; then
    r=$(kc get deploy "$1" -o jsonpath='{.spec.replicas}') && [[ $r =~ ^[1-9][0-9]*$ ]] \
      || die "read-model restart: $1 is at ${r:-no} replicas with none recorded: scale it to its rendered count, then rerun"
    note "$1 already restarted ($r replicas)"; return 0
  fi
  [[ $r =~ ^[1-9][0-9]*$ ]] || die "read-model restart: $1's recorded replica count '$r' is not a count"
  kc scale "deploy/$1" --replicas="$r" >/dev/null || die "read-model archive: $1 cannot be restarted"
  kc annotate deploy "$1" "$HELD-" >/dev/null || die "read-model archive: $1's replica record cannot be removed"; }

# FR-THT-08 (production 2026-11-08, e1108): the history store is the old epoch's too. history-feed bound its lanes to the
# deleted stream's incarnation and refused STREAM_RESET. Its tables move to arc_<label>_* while it is stopped; it
# recreates them empty on start. Renamed one at a time (a multi-table RENAME is not atomic in ClickHouse), so a retry
# skips what moved; the live tables it then finds are an earlier attempt's, empty, whose cursor binds a stream
# incarnation this retry deletes again, so the cursor is emptied.
HISTORY_TABLES="trades orders history_cursor"
chq(){ kc exec sts/clickhouse -c clickhouse -- clickhouse-client -q "$1"; }
archive_history(){
  local label=$1 t live arc n
  for t in $HISTORY_TABLES; do
    live=$(chq "EXISTS TABLE $t") && arc=$(chq "EXISTS TABLE arc_${label}_$t") && [[ $live$arc =~ ^[01][01]$ ]] \
      || die "history archive: $t cannot be looked up"
    if [ "$arc" = 0 ]; then
      [ "$live" = 0 ] || chq "RENAME TABLE $t TO arc_${label}_$t" >/dev/null || die "history archive: $t cannot be renamed"
    elif [ "$live" = 1 ] && [ "$t" = history_cursor ]; then
      chq "TRUNCATE TABLE history_cursor" >/dev/null || die "history archive: an earlier attempt's history_cursor cannot be emptied"
    elif [ "$live" = 1 ]; then
      n=$(chq "SELECT count() FROM $t") && [ "$n" = 0 ] || die "history archive: arc_${label}_$t exists and the live $t holds ${n:-unreadable} rows"
    fi
  done
  note "history store archived: arc_${label}_trades holds $(chq "SELECT count() FROM arc_${label}_trades" 2>/dev/null || echo '?') sides; history-feed recreates the live tables"
}

archive_read_model(){ # FR-PRG-29: the old epoch's read model into arc_<label>_*, the live tables recreated empty
  local label=$1 t renames='' have hf present
  step "read model: the old epoch's tables into arc_${label}_*, recreated empty (trade-processor stopped meanwhile)"
  [ "$DRY" = 1 ] && { note "would rename: $EPOCH_TABLES; history store: $HISTORY_TABLES"; return 0; }
  # FR-THT-02: history-feed binds its lanes to the capture stream's incarnation at start, so it is stopped across the
  # reset and started after trade-processor recreates the streams (production 2026-11-07 11:28 pm ET: one started
  # before the reset kept reading the deleted stream, and G3h timed out). Empty when no history store is rendered.
  hf=$(kc get deploy history-feed --ignore-not-found -o name) || die "read-model archive: history-feed cannot be read"
  [ -z "$hf" ] || hold history-feed
  hold trade-processor
  if [ -n "$hf" ]; then
    [ -n "$(kc get sts clickhouse --ignore-not-found -o name)" ] || die "history archive: history-feed is rendered but sts/clickhouse is not"
    archive_history "$label"
  fi
  have=$(sql "select count(*) from information_schema.tables where table_schema=database() and table_name='arc_${label}_trades'") \
    && [[ $have =~ ^[01]$ ]] || die "read-model archive: the archive cannot be looked up"
  # Only the epoch tables this database has: a volume older than a table's first-boot SQL never got it (init scripts
  # run on an empty data directory only), and the archive leaves the set of tables as it found it.
  if [ "$have" = 0 ]; then
    present=$(printf "'%s'," $EPOCH_TABLES)
    present=$(sql "select table_name from information_schema.tables where table_schema=database() and table_name in (${present%,})") \
      || die "read-model archive: the epoch tables cannot be listed"
    grep -qx trades <<<"$present" || die "read-model archive: no trades table to archive"
    for t in $EPOCH_TABLES; do if grep -qx "$t" <<<"$present"; then renames="$renames, $t TO arc_${label}_$t"; fi; done
    note "archiving: $(tr '\n' ' ' <<<"$present")"
    sql "RENAME TABLE ${renames#, }" >/dev/null || die "read-model archive: RENAME TABLE failed (nothing moved: it is atomic)"
  fi
  present=$(sql "select substring(table_name, length('arc_${label}_')+1) from information_schema.tables where table_schema=database() and table_name like 'arc\\_${label}\\_%'") \
    || die "read-model archive: the archived tables cannot be listed"
  EPOCH_TABLES=$(for t in $EPOCH_TABLES; do if grep -qx "$t" <<<"$present"; then echo "$t"; fi; done | tr '\n' ' ')
  for t in $EPOCH_TABLES; do
    sql "CREATE TABLE IF NOT EXISTS $t LIKE arc_${label}_$t" >/dev/null || die "read-model archive: $t cannot be recreated"
  done
  # LIKE copies columns, keys (each foreign key's index included) and CHECKs, but not the foreign keys themselves. Each
  # one on the archived table is re-added with its rules, unless the live table already has it (a rerun). An auto-named
  # key (<table>_ibfk_N, which InnoDB renamed with its table) is re-added unnamed, so it gets its old name back and reuses
  # the copied index; naming it explicitly would make MariaDB build a second index under the key's name instead.
  local fkq fks live name cols rt rcols ur dr
  fkq="select k.constraint_name, group_concat(k.column_name order by k.ordinal_position), k.referenced_table_name, group_concat(k.referenced_column_name order by k.ordinal_position), r.update_rule, r.delete_rule from information_schema.key_column_usage k join information_schema.referential_constraints r on r.constraint_schema=k.constraint_schema and r.constraint_name=k.constraint_name and r.table_name=k.table_name where k.table_schema=database() and k.table_name='TABLE' group by k.constraint_name, k.referenced_table_name, r.update_rule, r.delete_rule"
  for t in $EPOCH_TABLES; do
    fks=$(sql "${fkq/TABLE/arc_${label}_$t}") || die "read-model archive: $t's archived foreign keys cannot be read"
    live=$(sql "${fkq/TABLE/$t}" | cut -f2-4) || die "read-model archive: $t's foreign keys cannot be read"
    while IFS=$'\t' read -r name cols rt rcols ur dr; do
      [ -n "$name" ] || continue
      grep -qxF "$cols"$'\t'"$rt"$'\t'"$rcols" <<<"$live" && continue
      name=${name#arc_${label}_}
      [[ $name =~ ^${t}_ibfk_[0-9]+$ ]] && name='' || name="CONSTRAINT $name"
      sql "ALTER TABLE $t ADD $name FOREIGN KEY ($cols) REFERENCES $rt($rcols) ON UPDATE $ur ON DELETE $dr" >/dev/null \
        || die "read-model archive: a foreign key of $t ($cols -> $rt) cannot be restored"
    done <<<"$fks"
  done
  for t in $EPOCH_TABLES; do
    [ "$(sql "select count(*) from $t")" = 0 ] || die "read-model archive: $t is not empty after the archive"
  done
  note "archived: arc_${label}_trades holds $(sql "select count(*) from arc_${label}_trades") rows; live tables empty"
  reset_feed   # with trade-processor, the members and the gateways at 0
}

restart_read_model(){ # FR-PRG-29: after the archive (rig-epoch archived), trade-processor then history-feed back up
  step "read model: trade-processor, then history-feed, restarted on the new streams"
  [ "$DRY" = 1 ] && { note "would restart: trade-processor, history-feed at their recorded counts"; return 0; }
  local hf; hf=$(kc get deploy history-feed --ignore-not-found -o name) || die "read-model restart: history-feed cannot be read"
  release trade-processor
  kc rollout status deploy/trade-processor --timeout=600s >/dev/null || die "read-model archive: trade-processor not Ready"
  # FR-PRG-29: Ready is not listening on a render without trade-processor's readiness probe (production 2026-11-07
  # 11:01 pm ET: G3's accounting read got connection refused 10 s before it listened), so wait for what G3 reads.
  wait_for 300 "trade-processor's accounting answers (/v2/projection-feed/accounting)" accounting
  if [ -n "$hf" ]; then   # the accounting answering means the streams exist again, at their new incarnation
    release history-feed
    wait_for 300 "history-feed Ready on the new streams" deploy_rolled_out deploy/history-feed
  fi
}

# ---- gateways, LB, autoscale -------------------------------------------------------------------------------------
gateways_up(){
  step "gateways: $GATEWAYS, one per gateway-pool node"
  do_ kubectl --context "$CTX" -n "$NS" scale deploy/cluster-gateway --replicas="$GATEWAYS"
  wait_for 600 "$GATEWAYS gateways Ready" ready_replicas deploy/cluster-gateway "$GATEWAYS"
  if [ "$DRY" = 0 ]; then
    local nodes; nodes=$(kc get pods -l app=cluster-gateway -o jsonpath='{range .items[*]}{.spec.nodeName}{"\n"}{end}' | sort -u | grep -c . || true)
    [ "$nodes" = "$GATEWAYS" ] || die "$GATEWAYS gateways on $nodes nodes: one per node does not hold"
    note "ok: $GATEWAYS gateways on $nodes distinct nodes"
  fi
}

ilb_up(){
  step "internal passthrough LB to the gateways (FIX 18130, REST 18110), reachable only from $CLIENT_VMS"
  local v ip ranges='' y
  for v in $CLIENT_VMS; do
    ip='<client VM IP>'
    [ "$DRY" = 1 ] || ip=$(gc compute instances describe "$v" --zone "$ZONE" --format='value(networkInterfaces[0].networkIP)')
    [ "$DRY" = 1 ] || [[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "$v has no internal IP"
    ranges="${ranges:+$ranges$'\n'    - }$ip/32"
  done
  if [ "$DRY" = 1 ]; then note "would apply: $HERE/gateway-ilb.yaml with loadBalancerSourceRanges ${ranges//$'\n'    - / }"; else
    y=$(<"$HERE/gateway-ilb.yaml"); y=${y//- CLIENT_CIDR/- $ranges}; y=${y//namespace: traderx/namespace: $NS}
    kc apply -f - <<<"$y"
  fi
  wait_for 300 "internal LB address" ilb_has_ip
}

autoscale_on(){
  step "autoscale: HPA $GATEWAYS..$MAX_GATEWAYS on CPU (target $HPA_CPU_TARGET%), gateway-pool $GATEWAYS..$MAX_GATEWAYS nodes"
  do_ gcloud container node-pools update gateway-pool --cluster "$CLUSTER" --zone "$ZONE" --project "$PROJECT" \
    --enable-autoscaling --min-nodes "$GATEWAYS" --max-nodes "$MAX_GATEWAYS"
  if [ "$DRY" = 1 ]; then note "would apply: $HERE/gateway-hpa.yaml with minReplicas $GATEWAYS"; else
    sed "s#MIN_GATEWAYS#$GATEWAYS#; s#CPU_TARGET#$HPA_CPU_TARGET#; s#namespace: traderx#namespace: $NS#" "$HERE/gateway-hpa.yaml" | kc apply -f -
  fi
}

# ---- smoke order ---------------------------------------------------------------------------------------------------
members_bridged(){ kc get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].env[*].name}' \
  | tr ' ' '\n' | grep -qx TRADE_BRIDGE_NATS_URL; }
smoke(){
  step "smoke: a FIX order and its cancel through a gateway, then its state in the order read model"
  if [ "$DRY" = 1 ]; then note "would run: fix-smoke.mjs in web-front-end-console (resting buy inside the collar, cancel)"
    note "would check: the order reaches CANCELED in trade-processor's order read model"; return 0; fi
  local out ref
  if ! out=$(kc exec -i deploy/web-front-end-console -c console -- node - < "$HERE/fix-smoke.mjs" 2>&1); then
    echo "$out" > "$EVIDENCE/smoke.txt"; die "smoke: the FIX script failed: $EVIDENCE/smoke.txt"; fi
  echo "$out" > "$EVIDENCE/smoke.txt"
  grep -q '^NEW ' <<<"$out" && grep -q '^CANCELED ' <<<"$out" || die "smoke: no New + Canceled pair: $EVIDENCE/smoke.txt"
  ref=$(awk '/^NEW /{print $2}' <<<"$out")
  if members_bridged; then
    ORDER_TOKEN=$(admin_token); [ -n "$ORDER_TOKEN" ] || die "smoke: no admin token for the order read model"
    wait_for 60 "order $ref CANCELED in the order read model (FR-PRG-04)" order_status_is "$SMOKE_ACCOUNT" "$ref" CANCELED
  else   # FR-PRG-14: members rendered without bridges never feed the read model; the FIX answers are the effect end
    note "smoke: members run without bridges (FR-PRG-14): order $ref New and Canceled over FIX; no read-model row expected"
  fi
}

# ---- up / down -----------------------------------------------------------------------------------------------------
# A fresh epoch's engine knows no account and no security: the directory and the catalog go in through the sequenced
# control endpoint before the first order (FR-PRG-30). Both initializers verify on all three members and exit non-zero
# on any mismatch, so a failure refuses here, before the smoke; rerunning `up` retries until rig-epoch is initialized.
initialize(){ python3 "$HERE/../initialize-$1.py" --apply; }
fresh_epoch_initialize(){
  [ "$DRY" = 1 ] && { note "would run on a consumed fresh epoch: initialize-accounts.py --apply, then initialize-reference-data.py --apply"; return 0; }
  local es; es=$(epoch_state) || die "ConfigMap rig-epoch cannot be read"
  [ "${es%% *}" = consumed ] || return 0
  step "fresh epoch ${es#* }: the account directory and the instrument catalog into the empty engine (FR-PRG-30)"
  initialize accounts > "$EVIDENCE/initialize-accounts.txt" 2>&1 \
    || die "fresh epoch: initialize-accounts failed: $EVIDENCE/initialize-accounts.txt"
  initialize reference-data > "$EVIDENCE/initialize-reference-data.txt" 2>&1 \
    || die "fresh epoch: initialize-reference-data failed (its last check compares every security's enabled state): $EVIDENCE/initialize-reference-data.txt"
  set_epoch "${es#* }" initialized
  note "epoch ${es#* } initialized: accounts and reference data verified on all three members"
}

# FR-PRG-31: the engine never reads a clock (FR-OT37), and a fresh epoch starts with no business date, so every DAY order
# was REJECTED NO_TRADING_DAY. Every `up` opens today's US Eastern date; SESSION_START is idempotent (STALE when it is
# already the engine's date), and opening a later date expires the previous date's DAY orders, as a new trading day does.
business_day(){
  local d out; d=${BUSINESS_DATE:-$(TZ=America/New_York date +%F)}
  step "business day $d open, so DAY orders are admitted (FR-PRG-31)"
  [ "$DRY" = 1 ] && { note "would POST /session/business-day $d through the console pod (APPLIED or STALE)"; return 0; }
  if ! out=$(kc exec -i deploy/web-front-end-console -c console -- node --input-type=module - "$d" < "$HERE/business-day.mjs" 2>&1); then
    echo "$out" > "$EVIDENCE/business-day.txt"; die "business day $d not opened: $EVIDENCE/business-day.txt"; fi
  echo "$out" > "$EVIDENCE/business-day.txt"; note "${out##*$'\n'}"
}

# FR-PRG-32: the demo's load generators, up and idle with the rig, so a later `loadgen.sh start` waits on nothing.
loadgen_idle(){
  [ "$LOADGEN" = 0 ] && return 0
  step "load generators idle: loadgen-pool $LOADGEN nodes, $LOADGEN_PODS pods (loadgen.sh start RATE begins the load)"
  [ "$DRY" = 1 ] || pool_exists loadgen-pool || die "loadgen-pool is missing: run 'rig.sh pools' first"
  [ "$DRY" = 1 ] || wait_for 900 "loadgen-pool: $LOADGEN nodes Ready" pool_ready loadgen-pool "$LOADGEN"
  if [ "$DRY" = 1 ]; then note "would apply: Deployment loadgen ($LOADGEN_PODS pods, sleep, binary-load.mjs mounted)"; else
    LOADGEN_PODS=$LOADGEN_PODS "$HERE/loadgen.sh" manifest | kc apply -f - >/dev/null
  fi
  wait_for 600 "$LOADGEN_PODS idle load pods Ready" ready_replicas deploy/loadgen "$LOADGEN_PODS"
}
loadgen_nodes(){   # started with the other pools, waited for in loadgen_idle
  [ "$LOADGEN" = 0 ] && return 0
  do_ gcloud container clusters resize "$CLUSTER" --node-pool loadgen-pool --num-nodes "$LOADGEN" --zone "$ZONE" --project "$PROJECT" --quiet --async
}

rig_up(){
  validate_args; preflight; require_pools
  note "rig up: GATEWAYS=$GATEWAYS AUTOSCALE=$AUTOSCALE LOADGEN=$LOADGEN context=$CTX namespace=$NS evidence=$EVIDENCE"
  if members_gated; then
    # A re-run on a rig that is up: nothing is stopped, nothing re-gated; only the gateway count and what follows.
    note "members are up and covered by the restore gate: holding nothing, re-gating nothing"
    nats_record "$(nats_uid)"
    for p in $RIG_POOLS; do
      do_ gcloud container clusters resize "$CLUSTER" --node-pool "$p" --num-nodes "$(pool_nodes $p)" --zone "$ZONE" --project "$PROJECT" --quiet --async
    done
    loadgen_nodes
    for v in $RIG_VMS; do do_ gcloud compute instances start "$v" --zone "$ZONE" --project "$PROJECT"; done
    for p in $RIG_POOLS; do wait_for 900 "$p: $(pool_nodes $p) nodes Ready" pool_ready "$p" "$(pool_nodes $p)"; done
  else
    hold_gateways
    hold_members
    step "nodes: default-pool $DEFAULT_NODES, engine-pool 3, nats-pool 1, gateway-pool $GATEWAYS, history-pool 1; $RIG_VMS started"
    for p in $RIG_POOLS; do
      do_ gcloud container clusters resize "$CLUSTER" --node-pool "$p" --num-nodes "$(pool_nodes $p)" --zone "$ZONE" --project "$PROJECT" --quiet --async
    done
    loadgen_nodes
    for v in $RIG_VMS; do do_ gcloud compute instances start "$v" --zone "$ZONE" --project "$PROJECT"; done
    for p in $RIG_POOLS; do wait_for 900 "$p: $(pool_nodes $p) nodes Ready" pool_ready "$p" "$(pool_nodes $p)"; done
    step "support workloads Ready (everything but the members and the gateways)"
    if [ "$DRY" = 1 ]; then note "would wait: every Deployment except cluster-gateway rolled out"; else
      # FR-PRG-34: not `rollout status`: images applied while the rig sat at 0 nodes leave rollouts flagged
      # ProgressDeadlineExceeded, and rollout status gives up on that flag at once while the pods are still scheduling.
      for d in $(kc get deploy -o name | grep -v '/cluster-gateway$'); do
        wait_for 900 "$d Ready" deploy_rolled_out "$d"
      done
      wait_for 900 "sts/clickhouse Ready (the history store, FR-THT-02)" ready_replicas sts/clickhouse 1
      note "ok"
    fi
    nats_record ''
    local before='' es st lbl
    if [ "$DRY" = 1 ]; then es=none; else es=$(epoch_state) || die "ConfigMap rig-epoch cannot be read"; fi
    read -r st lbl <<<"$es"
    if [ "$st" = wiped ] || [ "$st" = archived ]; then
      [ "$DRY" = 1 ] || claims_gone || die "epoch $lbl is $st but a member claim exists: finish rig.sh fresh-epoch $lbl first"
      if [ "$st" = wiped ]; then
        [ "$DRY" = 1 ] || wait_for 300 "the read model answers" sql_ready
        archive_read_model "$lbl"
        [ "$DRY" = 1 ] || set_epoch "$lbl" archived
      else note "epoch $lbl already archived: the archive is skipped (its live tables are the new epoch's)"; fi
      restart_read_model
      [ "$DRY" = 1 ] || set_epoch "$lbl" provisioned
    fi
    if [ "$DRY" = 0 ]; then
      wait_for 300 "the read model answers" sql_ready
      before=$(read_model) || die "G3: the read model could not be read before the members started"
      echo "$before" > "$EVIDENCE/read-model-before.txt"
    fi
    past_restore_errors
    step "members: 3, from their retained claims"
    do_ kubectl --context "$CTX" -n "$NS" scale statefulset/order-matcher-cluster --replicas=3
    wait_for 900 "3 members Ready (election closed)" ready_replicas sts/order-matcher-cluster 3
    restore_gate "$before"
  fi
  gateways_up
  ilb_up
  fresh_epoch_initialize
  business_day
  [ "$AUTOSCALE" = on ] && autoscale_on
  smoke
  loadgen_idle
  step "rig up: done. Evidence in $EVIDENCE"
}

member_exit_codes(){ # stop the members and require each to exit 143 (FR-GMR-04)
  local watch=$EVIDENCE/members-watch.json log=$EVIDENCE/member-exits.txt bad=0
  if [ "$DRY" = 1 ]; then do_ kubectl --context "$CTX" -n "$NS" scale statefulset/order-matcher-cluster --replicas=0
    note "would require: each member's container terminated with exit code 143"; return 0; fi
  kc get pods -l app=order-matcher-cluster -w --output-watch-events -o json > "$watch" &   # roll 4's capture
  local watcher=$!
  sleep 2
  do_ kubectl --context "$CTX" -n "$NS" scale statefulset/order-matcher-cluster --replicas=0
  wait_for 180 "no member pod" no_pods order-matcher-cluster
  command sleep 3   # let the watch flush the last termination events before it is stopped
  kill $watcher 2>/dev/null || true
  jq -r 'select(.object.status.containerStatuses) | .object as $p | $p.status.containerStatuses[]
         | select(.name == "cluster-node" and .state.terminated) | "\($p.metadata.name) \(.state.terminated.exitCode)"' "$watch" \
    | sort -u > "$log"
  # Not a refusal: the members are already stopped, and stopping here would leave every pool billing. The teardown
  # finishes and the run exits non-zero (FR-PRG-02).
  for i in 0 1 2; do
    grep -q "^order-matcher-cluster-$i 143$" "$log" || { echo "FAILED: member $i did not exit 143: $log" >&2; DOWN_FAILED=1; bad=1; }
  done
  [ "$bad" = 1 ] || note "ok: members 0, 1, 2 exited 143"
}

rig_down(){
  preflight
  note "rig down: context=$CTX namespace=$NS evidence=$EVIDENCE"
  # Not a refusal, like the member exits: the run is over either way, and the teardown must still reach zero.
  step "NATS stayed up through the run (FR-PRG-28)"
  if [ "$DRY" = 1 ]; then note "would check: the NATS pod the rig came up with, Running, never restarted"; else
    local np; np=$(nats_problems "$(nats_uid)")
    if [ -z "$np" ]; then note "ok"; else
      printf '%s\n' "$np" > "$EVIDENCE/nats-restarts.txt"
      echo "FAILED: NATS did not stay up through the run; this run is not clean (FR-PRG-28):" >&2
      printf '   %s\n' "$np" >&2; DOWN_FAILED=1
    fi
  fi
  step "binary load stopped (FR-PRG-16), then gateways to 0 (each drains: FIX sessions resume elsewhere, in-flight answered)"
  do_ kubectl --context "$CTX" -n "$NS" delete job binary-load --ignore-not-found --wait=true
  do_ kubectl --context "$CTX" -n "$NS" delete deploy loadgen --ignore-not-found --wait=true
  autoscale_off
  do_ kubectl --context "$CTX" -n "$NS" scale deploy/cluster-gateway --replicas=0
  wait_for 180 "no gateway pod" no_pods cluster-gateway
  do_ kubectl --context "$CTX" -n "$NS" delete svc order-matcher-gw-ilb --ignore-not-found
  local ready=0
  [ "$DRY" = 1 ] || ready=$(kc get sts order-matcher-cluster -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo 0)
  if [ "$DRY" = 1 ] || [ "${ready:-0}" = 3 ]; then
    step "snapshot barrier and shutdown baseline"
    if [ "$DRY" = 1 ]; then note "would run: ClusterTool snapshot on the leader; wait traderx_cluster_snapshots +1 on all three"
      note "would record: ConfigMap rig-shutdown-baseline (applied, trades, hashes; read model)"; else
      local s leader i
      s=$(for i in 0 1 2; do member "$i" | cut -d' ' -f6; done | tr '\n' ' ')
      leader=$(for i in 0 1 2; do kc exec "order-matcher-cluster-$i" -c cluster-node -- wget -qO- localhost:8080/health \
        | jq -r --arg i "$i" 'select(.role=="LEADER") | $i'; done)
      [ -n "$leader" ] || die "no leader to snapshot"
      # Its own JAVA_TOOL_OPTIONS: inheriting the member's -Xms6g -XX:+AlwaysPreTouch would pre-touch another 6 GB
      # inside the member's 10Gi pod and get the member OOM-killed.
      kc exec "order-matcher-cluster-$leader" -c cluster-node -- env \
        JAVA_TOOL_OPTIONS="-Xmx128m --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED" \
        java -cp "/opt/app/classes:/opt/app/lib/*" io.aeron.cluster.ClusterTool /data/cluster snapshot >/dev/null \
        || die "ClusterTool snapshot failed on member $leader"
      wait_for 120 "snapshot counter past each member's own (${s% }) on all three" snapshots_past $s
      local m rm; m=$(member 0)
      rm=$(read_model) || die "baseline: the read model could not be read (FR-PRG-19); the rig is still up"
      kc create configmap rig-shutdown-baseline --from-literal=members="$m" --from-literal=readModel="$rm" \
        --from-literal=at="$(date -u +%FT%TZ)" --dry-run=client -o yaml | kc apply -f - >/dev/null
      echo "$m" > "$EVIDENCE/shutdown-baseline.txt"; note "baseline: $m"
    fi
    step "members stopped (each exits 143)"
    member_exit_codes
  else
    note "members not 3/3 Ready ($ready): no barrier or baseline possible; stopping them as they are"
    do_ kubectl --context "$CTX" -n "$NS" scale statefulset/order-matcher-cluster --replicas=0
  fi
  step "every rig pool to 0; $RIG_VMS stopped"
  { [ "$DRY" = 1 ] || pool_exists loadgen-pool; } && do_ gcloud container clusters resize "$CLUSTER" --node-pool loadgen-pool \
    --num-nodes 0 --zone "$ZONE" --project "$PROJECT" --quiet --async
  for p in gateway-pool nats-pool history-pool engine-pool default-pool; do
    do_ gcloud container clusters resize "$CLUSTER" --node-pool "$p" --num-nodes 0 --zone "$ZONE" --project "$PROJECT" --quiet --async
  done
  local v; for v in $RIG_VMS; do do_ gcloud compute instances stop "$v" --zone "$ZONE" --project "$PROJECT"; done
  step "verified with gcloud compute instances list, not the cluster's node count"
  wait_for 900 "no rig node instance left" no_rig_instances
  wait_for 300 "$RIG_VMS TERMINATED" vm_status_is TERMINATED
  [ "$DRY" = 1 ] || gc compute instances list --format='table(name,status,machineType.basename())' | tee "$EVIDENCE/instances-after.txt"
  [ "${DOWN_FAILED:-0}" = 0 ] || { echo "rig down: done, but a check FAILED (see above)" >&2; exit 3; }
  step "rig down: done"
}

# ---- member disks for a full-throughput hour (FR-PRG-15): `rig.sh member-disk 250Gi` --------------------------------
# The archive grows ~125 B per order (~180 GB/h at 400K/s) and production runs no purge. Grows each member claim (never
# shrinks: Hyperdisk cannot), after allowing expansion on the StorageClass, and waits for each bound volume's size.
pv_size(){ kc get pv "$(kc get pvc "$1" -o jsonpath='{.spec.volumeName}')" -o jsonpath='{.spec.capacity.storage}'; }
rig_member_disk(){
  local size=${1:-} i cur
  [[ $size =~ ^[1-9][0-9]*Gi$ ]] || die "member-disk: want a size like 250Gi"
  step "member disks to $size (order-matcher-archive, allowVolumeExpansion)"
  for i in 0 1 2; do
    cur=$(kc get pvc "data-order-matcher-cluster-$i" -o jsonpath='{.spec.resources.requests.storage}')
    [[ $cur =~ ^[0-9]+Gi$ ]] || die "member-disk: claim $i has size '$cur'"
    [ "${cur%Gi}" -le "${size%Gi}" ] || die "member-disk: claim $i is $cur; a claim never shrinks"
  done
  [ "$(kubectl --context "$CTX" get storageclass order-matcher-archive -o jsonpath='{.allowVolumeExpansion}')" = true ] \
    || do_ kubectl --context "$CTX" patch storageclass order-matcher-archive -p '{"allowVolumeExpansion":true}'
  for i in 0 1 2; do
    do_ kubectl --context "$CTX" -n "$NS" patch pvc "data-order-matcher-cluster-$i" \
      -p "{\"spec\":{\"resources\":{\"requests\":{\"storage\":\"$size\"}}}}"
  done
  [ "$DRY" = 1 ] && return 0
  for i in 0 1 2; do
    wait_for 600 "member $i's volume at $size" pv_size_is "data-order-matcher-cluster-$i" "$size"
  done
  note "member disks at $size (the filesystem grows when each member next mounts it)"
}
pv_size_is(){ [ "$(pv_size "$1")" = "$2" ]; }

# ---- a window the bridges did not project (FR-PRG-03): `rig.sh unprojected A-B` --------------------------------------
# Record the members' trade-side numbers A..B (their `trades` before and after a run with the bridges off) so the next
# gate excludes exactly them. Appends; never removes.
rig_unprojected(){
  local range=${1:-} have
  [[ $range =~ ^[1-9][0-9]*-[1-9][0-9]*$ ]] && [ "${range%-*}" -le "${range#*-}" ] || die "unprojected: want A-B with 1 <= A <= B"
  have=$(kc get configmap rig-unprojected -o jsonpath='{.data.ranges}' 2>/dev/null || true)
  [ "$DRY" = 1 ] && { note "would record: rig-unprojected ranges=${have:+$have }$range"; return 0; }
  kc create configmap rig-unprojected --from-literal=ranges="${have:+$have }$range" --dry-run=client -o yaml | kc apply -f - >/dev/null
  note "recorded: trade sides $range were not projected (G3 excludes them)"
}



rig_status(){
  preflight
  for p in $RIG_POOLS; do note "$p: $(pool_size $p) instance(s)"; done
  local v; for v in $RIG_VMS; do
    note "$v: $(gc compute instances describe "$v" --zone "$ZONE" --format='value(status)' 2>/dev/null || echo absent)"; done
  kc get sts,deploy,hpa,svc order-matcher-gw-ilb 2>/dev/null | grep -E 'order-matcher|cluster-gateway|hpa|NAME' || true
  kc get configmap rig-gate -o jsonpath='{.data.status} {.data.at}{"\n"}' 2>/dev/null || note "no gate record"
  local np; np=$(nats_problems "$(nats_uid)"); note "NATS: ${np:-intact (FR-PRG-28)}"
}

# FR-PRG-16: size the binary load's pool. `rig.sh pools` creates it at 0; `down` returns it to 0.
rig_loadgen(){
  local n=$1
  [[ "$n" =~ ^([0-9]|1[0-6])$ ]] || die "loadgen N: N is 0..16"
  preflight
  [ "$DRY" = 1 ] || pool_exists loadgen-pool || die "loadgen-pool is missing: run 'rig.sh pools' first"
  step "loadgen-pool to $n nodes"
  do_ gcloud container clusters resize "$CLUSTER" --node-pool loadgen-pool --num-nodes "$n" --zone "$ZONE" --project "$PROJECT" --quiet
  [ "$DRY" = 1 ] || wait_for 900 "loadgen-pool: $n nodes Ready" pool_ready loadgen-pool "$n"
}

main(){
  local action=${1:-} a rest=(); shift || true
  # Anywhere after the action: `archive-members LABEL --dry-run` once ignored it and snapshotted production.
  for a in "$@"; do if [ "$a" = --dry-run ]; then DRY=1; else rest+=("$a"); fi; done
  set -- ${rest[@]+"${rest[@]}"}
  case $action in
    pools) validate_args; preflight; rig_pools;;
    up) rig_up;;
    down) rig_down;;
    status) rig_status;;
    unprojected) rig_unprojected "${1:-}";;
    archive-members) rig_archive_members "${1:-}";;
    fresh-epoch) rig_fresh_epoch "${1:-}";;
    member-disk) rig_member_disk "${1:-}";;
    loadgen) rig_loadgen "${1:-}";;
    *) echo "usage: GATEWAYS=N AUTOSCALE=on|off LOADGEN=N $0 pools|up|down|status|unprojected A-B|archive-members LABEL|fresh-epoch LABEL|member-disk SIZE|loadgen N [--dry-run]" >&2; exit 64;;
  esac
}
[ "${RIG_LIB:-0}" = 1 ] || main "$@"
