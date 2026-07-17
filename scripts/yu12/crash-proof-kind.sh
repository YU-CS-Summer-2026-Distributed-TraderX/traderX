#!/usr/bin/env bash
# Destructive HA proof on kind (traderx-ha-recovery-proof shape, T-AC13/14/20):
#   crash 1: force-kill the leader; client-observed failover = the proof client's GAP line;
#   wipe:    delete the dead member's PVC so it returns with EMPTY disk and must catch up;
#   crash 2: force-kill the new leader with the recovered member in the voting set;
#   verdict: no REUSE lines ever; both GAP measurements printed; 3/3 ready at the end.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KDIR="${ROOT}/specs/YU12-aeron-cluster/generation/kubernetes/cluster"
CTX="kind-traderx-yu12-cluster"
NS="traderx"
K="kubectl --context ${CTX} -n ${NS}"

log() { echo "[proof $(date -u +%H:%M:%S)] $*"; }

health() { # health <ordinal> -> JSON (empty on failure); JRE image has no curl, so use the API proxy
  kubectl --context "${CTX}" get --raw \
    "/api/v1/namespaces/${NS}/pods/order-matcher-cluster-$1:8080/proxy/health" 2>/dev/null || true
}

leader_ordinal() {
  for i in 0 1 2; do
    if health "$i" | grep -q '"role":"LEADER"'; then echo "$i"; return 0; fi
  done
  return 1
}

await() { # await <seconds> <description> <command...>
  local deadline=$(( $(date +%s) + $1 )); shift
  local what="$1"; shift
  while ! "$@" >/dev/null 2>&1; do
    if (( $(date +%s) > deadline )); then log "TIMEOUT waiting for: ${what}"; return 1; fi
    sleep 2
  done
  log "ok: ${what}"
}

applied_of() { health "$1" | sed -n 's/.*"applied":\([0-9-]*\).*/\1/p'; }

caught_up() { # all live members applied within 2 of the max
  local max=0 vals=()
  for i in 0 1 2; do
    local a; a="$(applied_of "$i")"
    [[ -n "$a" && "$a" != "-1" ]] || return 1
    vals+=("$a"); (( a > max )) && max=$a
  done
  for a in "${vals[@]}"; do (( max - a <= 2 )) || return 1; done
  return 0
}

# ----- stage 0: baseline ---------------------------------------------------------------------
${K} delete pod cluster-proof-client --ignore-not-found --wait=true >/dev/null 2>&1 || true
${K} apply -f "${KDIR}/proof-client.yaml" >/dev/null
await 180 "proof client seeded" bash -c "${K} logs cluster-proof-client 2>/dev/null | grep -q SEEDED"
log "baseline traffic for 15s"
sleep 15
leader="$(leader_ordinal)" || { log "no leader found"; exit 1; }
log "leader is ordinal ${leader}"

# ----- stage 1: crash the leader -------------------------------------------------------------
log "CRASH 1: force-deleting order-matcher-cluster-${leader}"
${K} delete pod "order-matcher-cluster-${leader}" --grace-period=0 --force --wait=false
await 120 "new leader elected" bash -c '
  for i in 0 1 2; do
    [[ "$i" == "'"${leader}"'" ]] && continue
    kubectl --context "'"${CTX}"'" get --raw \
      "/api/v1/namespaces/'"${NS}"'/pods/order-matcher-cluster-${i}:8080/proxy/health" 2>/dev/null \
      | grep -q "\"role\":\"LEADER\"" && exit 0
  done
  exit 1'
sleep 10
log "GAP lines so far (client-observed failover):"
${K} logs cluster-proof-client | grep GAP || log "  (none over threshold yet)"

await 300 "crashed member back Ready" bash -c \
  "${K} get pod order-matcher-cluster-${leader} -o jsonpath='{.status.containerStatuses[0].ready}' 2>/dev/null | grep -q true"

# ----- stage 2: wipe that member to empty disk and let it rejoin ------------------------------
log "WIPE: deleting pod + PVC of ordinal ${leader} (empty-disk rejoin)"
${K} delete pod "order-matcher-cluster-${leader}" --grace-period=0 --force --wait=false || true
${K} delete pvc "data-order-matcher-cluster-${leader}" --wait=true
# kind local-path can re-bind races: wait for the pod to be recreated and Ready on a fresh PVC.
await 420 "wiped member caught up" caught_up
log "wiped member ordinal ${leader} rejoined from empty disk"

# ----- stage 3: crash the new leader with the recovered member voting -------------------------
leader2="$(leader_ordinal)" || { log "no leader found"; exit 1; }
log "CRASH 2: force-deleting leader ordinal ${leader2}"
${K} delete pod "order-matcher-cluster-${leader2}" --grace-period=0 --force --wait=false
await 120 "leader after crash 2" bash -c '
  for i in 0 1 2; do
    [[ "$i" == "'"${leader2}"'" ]] && continue
    kubectl --context "'"${CTX}"'" get --raw \
      "/api/v1/namespaces/'"${NS}"'/pods/order-matcher-cluster-${i}:8080/proxy/health" 2>/dev/null \
      | grep -q "\"role\":\"LEADER\"" && exit 0
  done
  exit 1'
sleep 10
await 300 "all members Ready" bash -c \
  "[[ \$(${K} get pods -l app=order-matcher-cluster -o jsonpath='{range .items[*]}{.status.containerStatuses[0].ready}{\"\\n\"}{end}' | grep -c true) -eq 3 ]]"

# ----- verdict -------------------------------------------------------------------------------
log "===== VERDICT ====="
if ${K} logs cluster-proof-client | grep -q REUSE; then
  log "FAIL: reference reuse observed:"
  ${K} logs cluster-proof-client | grep REUSE
  exit 1
fi
log "no ID reuse across two crashes and one empty-disk rejoin"
log "client-observed failover gaps:"
${K} logs cluster-proof-client | grep GAP || log "  none exceeded the ${PROOF_GAP_MS:-500}ms threshold"
acks=$(${K} logs cluster-proof-client | grep -c ACK || true)
log "total accepted orders: ${acks}"
${K} get pods -l app=order-matcher-cluster
log "PASS"
