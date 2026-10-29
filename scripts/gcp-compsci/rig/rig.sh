#!/usr/bin/env bash
# ADR-096: the production rig (namespace traderx on traderx-demo) up at bench parity and back down to zero.
#
#   scripts/gcp-compsci/rig/rig.sh pools|up|down|status [--dry-run]
#     GATEWAYS=N      gateways, one per gateway-pool node, 1..18 (default 2)
#     AUTOSCALE=on    gateways also scale on CPU between GATEWAYS and 18 (default off)
#
# pools   creates nats-pool, gateway-pool, the client VM and its IAP rule if missing; puts the sysctls on
#         engine-pool (only while it has no nodes, since GKE recreates the nodes to apply them).
# up      nodes, then members, then the restore gate (gateways stay at 0 until it passes; a mismatch stops here),
#         then N gateways, the internal LB, autoscaling, and a smoke order checked at the effect end.
# down    gateways to 0 first (they drain), a snapshot barrier and a shutdown baseline, members stopped (each must
#         exit 143), then every rig pool to 0 and the client VM stopped, verified by `gcloud compute instances list`.
# status  read-only.
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
MAX_GATEWAYS=18
DEFAULT_NODES=${DEFAULT_NODES:-3}
CLIENT_VM=${CLIENT_VM:-traderx-client-1}
GATE_SHADOW_PATH=${GATE_SHADOW_PATH:-/recon/shadow-state}
EVIDENCE=${EVIDENCE:-$PWD/rig-evidence/$(date +%Y%m%d-%H%M%S)}
DRY=0

# pool -> machine type, nodes when up, taint key (empty: existing pool, never created here)
pool_machine(){ case $1 in default-pool) echo e2-standard-4;; engine-pool) echo c4d-standard-8;;
  nats-pool) echo c3-standard-8;; gateway-pool) echo c4d-standard-8;; esac; }
pool_nodes(){ case $1 in default-pool) echo "$DEFAULT_NODES";; engine-pool) echo 3;; nats-pool) echo 1;;
  gateway-pool) echo "$GATEWAYS";; esac; }
RIG_POOLS="default-pool engine-pool nats-pool gateway-pool"

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
ready_replicas(){ [ "$(kc get "$1" -o jsonpath='{.status.readyReplicas}' 2>/dev/null)" = "$2" ]; }
ilb_has_ip(){ [ -n "$(kc get svc order-matcher-gw-ilb -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null)" ]; }
no_rig_instances(){ [ -z "$(gc compute instances list \
  --filter="name~^gke-${CLUSTER}-(default-pool|engine-pool|nats-pool|gateway-pool)-" --format='value(name)')" ]; }
vm_status_is(){ [ "$(gc compute instances describe "$CLIENT_VM" --zone "$ZONE" --format='value(status)')" = "$1" ]; }
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
  step "pools: nats-pool and gateway-pool exist with the right machine type; engine-pool carries the sysctls"
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
  if [ "$DRY" = 0 ] && gc compute instances describe "$CLIENT_VM" --zone "$ZONE" >/dev/null 2>&1; then note "$CLIENT_VM present"; else
    do_ gcloud compute instances create "$CLIENT_VM" --project "$PROJECT" --zone "$ZONE" --machine-type c4d-standard-8 \
      --subnet "$sub" --no-address --image-family debian-12 --image-project debian-cloud \
      --boot-disk-type hyperdisk-balanced --boot-disk-size 20GB --tags traderx-client --shielded-secure-boot
    do_ gcloud compute instances stop "$CLIENT_VM" --project "$PROJECT" --zone "$ZONE"
  fi
}

require_pools(){
  [ "$DRY" = 1 ] && return 0
  for p in nats-pool gateway-pool; do pool_exists $p || die "$p is missing: run 'rig.sh pools' first"; done
  engine_has_sysctls || die "engine-pool has no sysctls: run 'rig.sh pools' while it is at 0"
  gc compute instances describe "$CLIENT_VM" --zone "$ZONE" >/dev/null 2>&1 || die "$CLIENT_VM missing: run 'rig.sh pools'"
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
row_status_is(){ [ "$(sql "select status from orderbook where orderid like '%-$1'")" = "$2" ]; }
sql(){ kc exec deploy/eod-price-db -c mariadb -- sh -c "mariadb -uroot -p\"\$MARIADB_ROOT_PASSWORD\" -N -B -e \"$1\" traderx" 2>/dev/null; }
md5of(){ python3 -c 'import hashlib,sys; print(hashlib.md5(sys.stdin.buffer.read()).hexdigest()[:16])'; }
read_model(){
  echo "trades=$(sql 'select id,accountid,security,side,quantity,price,state from trades order by 1' | md5of)"
  echo "positions=$(sql 'select accountid,security,quantity,averagecostbasis from positions order by 1,2' | md5of)"
  echo "orderbook=$(sql 'select orderid,accountid,security,side,status,quantity,remainingquantity from orderbook order by 1' | md5of)"
}
member(){ # member <i>: applied engineApplied trades orderHash positionHash snapshots
  local h m
  h=$(kc exec "order-matcher-cluster-$1" -c cluster-node -- wget -qO- localhost:8080/health)
  m=$(kc exec "order-matcher-cluster-$1" -c cluster-node -- wget -qO- localhost:8080/metrics)
  printf '%s %s %s %s %s %s\n' "$(jq -r .applied <<<"$h")" "$(jq -r .engineApplied <<<"$h")" "$(jq -r .trades <<<"$h")" \
    "$(awk '/^traderx_book_order_hash/{print $NF}' <<<"$m")" "$(awk '/^traderx_book_position_hash/{print $NF}' <<<"$m")" \
    "$(awk '/^traderx_cluster_snapshots/{print $NF}' <<<"$m")"
}
snapshots_past(){ local i n; for i in 0 1 2; do n=$(member "$i" | cut -d' ' -f6); [ "${n:-0}" -gt "$1" ] || return 1; done; }
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
# log with ClusterTool; any such error means that member already came up partial once, and the bring-up stops.
PAST_RESTORE_ERRORS='snapshot corrupt|NEWER|unknown snapshot record type'
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
    command: [sh, -c, 'java -cp "/opt/app/classes:/opt/app/lib/*" io.aeron.cluster.ClusterTool /data/cluster errors']
    env: [{name: JAVA_TOOL_OPTIONS, value: "-Xmx128m --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"}]
    resources: {requests: {cpu: 250m, memory: 512Mi}, limits: {cpu: "1", memory: 512Mi}}
    volumeMounts: [{name: data, mountPath: /data, readOnly: true}]
  volumes: [{name: data, persistentVolumeClaim: {claimName: data-order-matcher-cluster-$1, readOnly: true}}]
EOF
}
pod_done(){ case "$(kc get pod "$1" -o jsonpath='{.status.phase}' 2>/dev/null)" in Succeeded|Failed) return 0;; *) return 1;; esac; }
past_restore_errors(){
  step "before the members start: no member's error log holds a refused snapshot record (G-pre)"
  if [ "$DRY" = 1 ]; then
    note "would run per claim data-order-matcher-cluster-{0,1,2}: a read-only pod, ClusterTool /data/cluster errors"
    note "would refuse on: $PAST_RESTORE_ERRORS, or a pod that cannot read the log"; return 0; fi
  [ "$(kc get sts order-matcher-cluster -o jsonpath='{.status.replicas}')" = 0 ] || die "G-pre: members are running"
  local image i phase
  image=$(kc get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].image}')
  for i in 0 1 2; do
    kc delete pod "rig-errlog-$i" --ignore-not-found --wait >/dev/null
    errlog_pod "$i" "$image" | kc apply -f - >/dev/null
    wait_for 300 "member $i's error log read" pod_done "rig-errlog-$i"
    phase=$(kc get pod "rig-errlog-$i" -o jsonpath='{.status.phase}')
    kc logs "rig-errlog-$i" > "$EVIDENCE/past-errors-$i.txt" 2>&1 || true
    kc delete pod "rig-errlog-$i" --wait >/dev/null
    [ "$phase" = Succeeded ] || die "G-pre: member $i's error log could not be read ($phase): $EVIDENCE/past-errors-$i.txt"
    ! grep -Eiq "$PAST_RESTORE_ERRORS" "$EVIDENCE/past-errors-$i.txt" \
      || die "G-pre: member $i restored a snapshot partially in the past: $EVIDENCE/past-errors-$i.txt"
  done
  note "G-pre ok: no refused snapshot record in any member's error log"
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
  [ "$a" -gt 0 ] && [ "$a" = "$e" ] || die "G0: applied $a, engineApplied $e"
  note "G0 ok: applied $a, trades $t, hashes $oh $ph on all three"
  token=$(admin_token); [ -n "$token" ] || die "G1: could not mint an admin token in trade-processor"
  for i in 0 1 2; do
    rep=$(kc exec -i "order-matcher-cluster-$i" -c cluster-node -- sh -c 'read -r t; wget -qO- --post-data="" \
      --header="Authorization: Bearer $t" http://localhost:8080/recon/full-history/reindex' <<<"$token")
    [ "$(jq -r .replayedAppliedSeq <<<"$rep")" = "$a" ] && [ "$(jq -r .indexedTrades <<<"$rep")" = "$t" ] \
      || die "G1: member $i's replay from 0 gives $(jq -c '{replayedAppliedSeq,indexedTrades}' <<<"$rep"), the restored state applied $a trades $t"
  done
  note "G1 ok: each replay from 0 reaches applied $a with $t trades"
  # G2, check A (FR-ERH-F13): a strict replay of the whole log into a fresh shadow must hold exactly the restored state,
  # in the terms of /health?digest=1. Hashes are decimal strings (a 64-bit hash read as a JSON number loses digits).
  local digest open_rows
  for i in 0 1 2; do
    shadow=$(kc exec -i "order-matcher-cluster-$i" -c cluster-node -- sh -c "read -r t; wget -qO- \
      --header=\"Authorization: Bearer \$t\" http://localhost:8080$GATE_SHADOW_PATH" <<<"$token" 2>/dev/null || true)
    if [ -z "$shadow" ] || ! jq -e '.bookHash and .positionHash' <<<"$shadow" >/dev/null 2>&1; then
      [ "${GATE_ALLOW_NO_SHADOW:-0}" = 1 ] && [ "$NS" != traderx ] \
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
  if [ -n "$shadow" ]; then
    note "G2 ok: each member's replay from 0 holds its restored book, positions and trades"
    # Check B's member side: the open orders the members restored are the open rows the read model holds.
    open_rows=$(sql "select count(*) from orderbook where status in ('NEW','PARTIALLY_FILLED')")
    [ "$(jq -r .openOrders <<<"$digest")" = "$open_rows" ] \
      || die "G3: members restored $(jq -r .openOrders <<<"$digest") open orders, the read model holds $open_rows"
  fi
  after=$(read_model); echo "$after" > "$EVIDENCE/read-model-after.txt"
  [ "$after" = "$before" ] || die "G3: the read model moved while the members started: $EVIDENCE/read-model-{before,after}.txt"
  note "G3 ok: read model unchanged"
  base=$(kc get configmap rig-shutdown-baseline -o jsonpath='{.data.members}' 2>/dev/null || true)
  if [ -n "$base" ]; then
    [ "$(cut -d' ' -f1-5 <<<"$base")" = "$(cut -d' ' -f1-5 <<<"$m0")" ] \
      || die "G4: shutdown baseline '$base' != restored '$m0'"
    note "G4 ok: equals the shutdown baseline"
  else
    note "G4: no shutdown baseline recorded (first bring-up under this script)"
  fi
  kc create configmap rig-gate --from-literal=status=PASS --from-literal=at="$(date -u +%FT%TZ)" \
    --from-literal=members="$(kc get pods -l app=order-matcher-cluster -o jsonpath='{range .items[*]}{.metadata.uid} {end}')" \
    --dry-run=client -o yaml | kc apply -f - >/dev/null
  note "gate PASS recorded in ConfigMap rig-gate"
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
  step "internal passthrough LB to the gateways (FIX 18130, REST 18110), reachable only from $CLIENT_VM"
  local ip='<client VM IP>'
  [ "$DRY" = 1 ] || ip=$(gc compute instances describe "$CLIENT_VM" --zone "$ZONE" --format='value(networkInterfaces[0].networkIP)')
  if [ "$DRY" = 1 ]; then note "would apply: $HERE/gateway-ilb.yaml with loadBalancerSourceRanges $ip/32"; else
    sed "s#CLIENT_CIDR#$ip/32#; s#namespace: traderx#namespace: $NS#" "$HERE/gateway-ilb.yaml" | kc apply -f -
  fi
  wait_for 300 "internal LB address" ilb_has_ip
}

autoscale_on(){
  step "autoscale: HPA $GATEWAYS..$MAX_GATEWAYS on CPU, gateway-pool $GATEWAYS..$MAX_GATEWAYS nodes"
  do_ gcloud container node-pools update gateway-pool --cluster "$CLUSTER" --zone "$ZONE" --project "$PROJECT" \
    --enable-autoscaling --min-nodes "$GATEWAYS" --max-nodes "$MAX_GATEWAYS"
  if [ "$DRY" = 1 ]; then note "would apply: $HERE/gateway-hpa.yaml with minReplicas $GATEWAYS"; else
    sed "s#MIN_GATEWAYS#$GATEWAYS#; s#namespace: traderx#namespace: $NS#" "$HERE/gateway-hpa.yaml" | kc apply -f -
  fi
}

# ---- smoke order ---------------------------------------------------------------------------------------------------
smoke(){
  step "smoke: a FIX order and its cancel through a gateway, then its row in the read model"
  if [ "$DRY" = 1 ]; then note "would run: fix-smoke.mjs in web-front-end-console (resting buy inside the collar, cancel)"
    note "would check: the order's orderbook row reaches CANCELED"; return 0; fi
  local out ref
  out=$(kc exec -i deploy/web-front-end-console -c console -- node - < "$HERE/fix-smoke.mjs")
  echo "$out" > "$EVIDENCE/smoke.txt"
  grep -q '^NEW ' <<<"$out" && grep -q '^CANCELED ' <<<"$out" || die "smoke: no New + Canceled pair: $EVIDENCE/smoke.txt"
  ref=$(awk '/^NEW /{print $2}' <<<"$out")
  wait_for 60 "orderbook row for order $ref CANCELED (the read model, FR-PRG-04)" row_status_is "$ref" CANCELED
}

# ---- up / down -----------------------------------------------------------------------------------------------------
rig_up(){
  validate_args; preflight; require_pools
  note "rig up: GATEWAYS=$GATEWAYS AUTOSCALE=$AUTOSCALE context=$CTX namespace=$NS evidence=$EVIDENCE"
  if members_gated; then
    # A re-run on a rig that is up: nothing is stopped, nothing re-gated; only the gateway count and what follows.
    note "members are up and covered by the restore gate: holding nothing, re-gating nothing"
    for p in $RIG_POOLS; do
      do_ gcloud container clusters resize "$CLUSTER" --node-pool "$p" --num-nodes "$(pool_nodes $p)" --zone "$ZONE" --project "$PROJECT" --quiet --async
    done
    do_ gcloud compute instances start "$CLIENT_VM" --zone "$ZONE" --project "$PROJECT"
    for p in $RIG_POOLS; do wait_for 900 "$p: $(pool_nodes $p) nodes Ready" pool_ready "$p" "$(pool_nodes $p)"; done
  else
    hold_gateways
    hold_members
    step "nodes: default-pool $DEFAULT_NODES, engine-pool 3, nats-pool 1, gateway-pool $GATEWAYS; $CLIENT_VM started"
    for p in $RIG_POOLS; do
      do_ gcloud container clusters resize "$CLUSTER" --node-pool "$p" --num-nodes "$(pool_nodes $p)" --zone "$ZONE" --project "$PROJECT" --quiet --async
    done
    do_ gcloud compute instances start "$CLIENT_VM" --zone "$ZONE" --project "$PROJECT"
    for p in $RIG_POOLS; do wait_for 900 "$p: $(pool_nodes $p) nodes Ready" pool_ready "$p" "$(pool_nodes $p)"; done
    step "support workloads Ready (everything but the members and the gateways)"
    if [ "$DRY" = 1 ]; then note "would wait: every Deployment except cluster-gateway rolled out"; else
      for d in $(kc get deploy -o name | grep -v '/cluster-gateway$'); do
        kc rollout status "$d" --timeout=600s >/dev/null || die "$d not Ready"
      done; note "ok"
    fi
    local before=''
    if [ "$DRY" = 0 ]; then before=$(read_model); echo "$before" > "$EVIDENCE/read-model-before.txt"; fi
    past_restore_errors
    step "members: 3, from their retained claims"
    do_ kubectl --context "$CTX" -n "$NS" scale statefulset/order-matcher-cluster --replicas=3
    wait_for 900 "3 members Ready (election closed)" ready_replicas sts/order-matcher-cluster 3
    restore_gate "$before"
  fi
  gateways_up
  ilb_up
  [ "$AUTOSCALE" = on ] && autoscale_on
  smoke
  step "rig up: done. Evidence in $EVIDENCE"
}

member_exit_codes(){ # stop the members and require each to exit 143 (FR-GMR-04)
  local watch=$EVIDENCE/members-watch.json log=$EVIDENCE/member-exits.txt
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
    grep -q "^order-matcher-cluster-$i 143$" "$log" || { echo "FAILED: member $i did not exit 143: $log" >&2; DOWN_FAILED=1; }
  done
  [ "${DOWN_FAILED:-0}" = 1 ] || note "ok: members 0, 1, 2 exited 143"
}

rig_down(){
  preflight
  note "rig down: context=$CTX namespace=$NS evidence=$EVIDENCE"
  step "gateways to 0 first (each drains: FIX sessions log out and resume elsewhere, in-flight orders answered)"
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
      local s0 leader i
      s0=$(member 0 | cut -d' ' -f6)
      leader=$(for i in 0 1 2; do kc exec "order-matcher-cluster-$i" -c cluster-node -- wget -qO- localhost:8080/health \
        | jq -r --arg i "$i" 'select(.role=="LEADER") | $i'; done)
      [ -n "$leader" ] || die "no leader to snapshot"
      # Its own JAVA_TOOL_OPTIONS: inheriting the member's -Xms6g -XX:+AlwaysPreTouch would pre-touch another 6 GB
      # inside the member's 10Gi pod and get the member OOM-killed.
      kc exec "order-matcher-cluster-$leader" -c cluster-node -- env \
        JAVA_TOOL_OPTIONS="-Xmx128m --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED" \
        java -cp "/opt/app/classes:/opt/app/lib/*" io.aeron.cluster.ClusterTool /data/cluster snapshot >/dev/null \
        || die "ClusterTool snapshot failed on member $leader"
      wait_for 120 "snapshot counter past $s0 on all three" snapshots_past "$s0"
      local m; m=$(member 0)
      kc create configmap rig-shutdown-baseline --from-literal=members="$m" --from-literal=readModel="$(read_model)" \
        --from-literal=at="$(date -u +%FT%TZ)" --dry-run=client -o yaml | kc apply -f - >/dev/null
      echo "$m" > "$EVIDENCE/shutdown-baseline.txt"; note "baseline: $m"
    fi
    step "members stopped (each exits 143)"
    member_exit_codes
  else
    note "members not 3/3 Ready ($ready): no barrier or baseline possible; stopping them as they are"
    do_ kubectl --context "$CTX" -n "$NS" scale statefulset/order-matcher-cluster --replicas=0
  fi
  step "every rig pool to 0; $CLIENT_VM stopped"
  for p in gateway-pool nats-pool engine-pool default-pool; do
    do_ gcloud container clusters resize "$CLUSTER" --node-pool "$p" --num-nodes 0 --zone "$ZONE" --project "$PROJECT" --quiet --async
  done
  do_ gcloud compute instances stop "$CLIENT_VM" --zone "$ZONE" --project "$PROJECT"
  step "verified with gcloud compute instances list, not the cluster's node count"
  wait_for 900 "no rig node instance left" no_rig_instances
  wait_for 300 "$CLIENT_VM TERMINATED" vm_status_is TERMINATED
  [ "$DRY" = 1 ] || gc compute instances list --format='table(name,status,machineType.basename())' | tee "$EVIDENCE/instances-after.txt"
  [ "${DOWN_FAILED:-0}" = 0 ] || { echo "rig down: done, but a member did not exit 143 (see above)" >&2; exit 3; }
  step "rig down: done"
}

rig_status(){
  preflight
  for p in $RIG_POOLS; do note "$p: $(pool_size $p) instance(s)"; done
  note "$CLIENT_VM: $(gc compute instances describe "$CLIENT_VM" --zone "$ZONE" --format='value(status)' 2>/dev/null || echo absent)"
  kc get sts,deploy,hpa,svc order-matcher-gw-ilb 2>/dev/null | grep -E 'order-matcher|cluster-gateway|hpa|NAME' || true
  kc get configmap rig-gate -o jsonpath='{.data.status} {.data.at}{"\n"}' 2>/dev/null || note "no gate record"
}

main(){
  local action=${1:-}; shift || true
  [ "${1:-}" = --dry-run ] && DRY=1
  case $action in
    pools) validate_args; preflight; rig_pools;;
    up) rig_up;;
    down) rig_down;;
    status) rig_status;;
    *) echo "usage: GATEWAYS=N AUTOSCALE=on|off $0 pools|up|down|status [--dry-run]" >&2; exit 64;;
  esac
}
[ "${RIG_LIB:-0}" = 1 ] || main "$@"
