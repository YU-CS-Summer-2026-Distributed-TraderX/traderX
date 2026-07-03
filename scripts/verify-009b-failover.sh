#!/usr/bin/env bash
set -euo pipefail

# State 009b warm-standby failover verification (FR-09B30..B32).
#
# Proves, against the RUNNING compose stack:
#   1. roles: primary live+leader-lock, standby following with bounded lag
#   2. warm follower: orders created on the primary are visible on the standby's read model
#   3. failover: kill -9 the primary container -> standby auto-promotes -> the VIP (host
#      port 18110) serves reads AND accepts new orders again, with all pre-failover state intact
#   4. failback: restart the old primary -> it rejoins as a FOLLOWER (lock held by the
#      promoted standby), and the VIP keeps routing to the promoted node
#
# Usage: scripts/verify-009b-failover.sh   (stack must already be up)

VIP="http://localhost:18110"
PRIMARY="http://localhost:18111"
STANDBY="http://localhost:18112"
COMPOSE_PROJECT="${COMPOSE_PROJECT_NAME:-traderx-state-009}"

pass=0
fail=0
step() { echo; echo "== $*"; }
ok()   { echo "   [PASS] $*"; pass=$((pass+1)); }
bad()  { echo "   [FAIL] $*"; fail=$((fail+1)); }

jqget() { python3 -c "import sys,json,functools; d=json.load(sys.stdin); print(functools.reduce(lambda a,k: a[k], '$1'.split('.'), d))"; }

wait_for() { # wait_for <seconds> <description> <command...>
  local deadline=$(( $(date +%s) + $1 )); shift
  local what="$1"; shift
  while true; do
    if "$@" >/dev/null 2>&1; then return 0; fi
    if (( $(date +%s) >= deadline )); then echo "   timed out waiting for: ${what}"; return 1; fi
    sleep 1
  done
}

role_of() { curl -sf --max-time 3 "$1/admin/role" | jqget role; }
live_of() { curl -sf --max-time 3 "$1/admin/role" | jqget live; }

step "0. Preconditions: primary live, standby following"
wait_for 60 "primary ready" curl -sf --max-time 2 "$PRIMARY/admin/ready" || { bad "primary never became ready"; exit 1; }
[[ "$(role_of "$PRIMARY")" == "primary" && "$(live_of "$PRIMARY")" == "True" ]] && ok "primary role=primary live=true" || bad "primary role/live wrong: $(curl -s "$PRIMARY/admin/role")"
[[ "$(role_of "$STANDBY")" == "standby" && "$(live_of "$STANDBY")" == "False" ]] && ok "standby role=standby live=false" || bad "standby role/live wrong: $(curl -s "$STANDBY/admin/role")"
curl -sf "$STANDBY/admin/ready" >/dev/null 2>&1 && bad "standby /admin/ready should be 503 while following" || ok "standby /admin/ready correctly 503"

step "1. Writes through the VIP land on the primary and replicate to the warm follower"
ORDER_JSON='{"accountId":22214,"security":"IBM","side":"Buy","quantity":123,"limitPrice":185.5}'
CREATED=$(curl -sf -X POST -H 'Content-Type: application/json' -d "$ORDER_JSON" "$VIP/orders")
OID=$(echo "$CREATED" | jqget orderId) || { bad "order create via VIP failed: $CREATED"; exit 1; }
ok "created $OID via VIP"
wait_for 15 "follower to apply $OID" bash -c "curl -sf '$STANDBY/orders/$OID' | grep -q '\"quantity\":123'" \
  && ok "warm follower serves $OID (qty 123) from its tailed read model" \
  || bad "follower never saw $OID"
STANDBY_503=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d "$ORDER_JSON" "$STANDBY/orders")
[[ "$STANDBY_503" == "503" ]] && ok "follower refuses direct writes (503)" || bad "follower accepted a write?! http=$STANDBY_503"

step "2. Kill the primary (SIGKILL, no goodbye) and watch the standby promote"
PRE_ORDERS=$(curl -sf "$PRIMARY/orders?status=all" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)))')
docker kill -s KILL "$(docker ps -qf "name=${COMPOSE_PROJECT}-order-matcher-1")" >/dev/null
T0=$(date +%s)
wait_for 60 "standby to promote" curl -sf --max-time 2 "$STANDBY/admin/ready" \
  && ok "standby promoted (took $(( $(date +%s) - T0 ))s from kill to ready)" \
  || bad "standby never promoted"
[[ "$(role_of "$STANDBY")" == "promoted" ]] && ok "standby role=promoted" || bad "standby role: $(role_of "$STANDBY")"
[[ "$(curl -sf "$STANDBY/admin/role" | jqget journalWriting)" == "True" ]] \
  && ok "promoted leader is journaling (its writes replicate + survive restart)" \
  || bad "promoted leader NOT journaling — post-failover acks would not be durable"

step "3. Continuity through the VIP: old state intact, new writes accepted"
wait_for 30 "VIP to route reads to the promoted node" bash -c "curl -sf '$VIP/admin/ready'" || bad "VIP never routed to promoted node"
POST_ORDERS=$(curl -sf "$VIP/orders?status=all" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)))')
[[ "$POST_ORDERS" -ge "$PRE_ORDERS" ]] && ok "order count survived failover ($PRE_ORDERS -> $POST_ORDERS)" || bad "orders lost: $PRE_ORDERS -> $POST_ORDERS"
curl -sf "$VIP/orders/$OID" | grep -q '"quantity":123' && ok "pre-failover order $OID served via VIP" || bad "$OID lost after failover"
# The write backend rises a beat after reads (health-check rise 2 @ 1s): retry the POST briefly.
CREATED2=""
T1=$(date +%s)
for _ in $(seq 1 30); do
  CREATED2=$(curl -sf -X POST -H 'Content-Type: application/json' \
    -d '{"accountId":52355,"security":"MSFT","side":"Sell","quantity":77,"limitPrice":410.0}' "$VIP/orders" || true)
  [[ -n "$CREATED2" ]] && break
  sleep 1
done
if OID2=$(echo "$CREATED2" | jqget orderId 2>/dev/null); then
  ok "new order $OID2 accepted via VIP ($(( $(date +%s) - T1 ))s after reads resumed)"
  [[ "$OID2" != "$OID" ]] && ok "no order-id collision after failover" || bad "orderRef collision: $OID2"
else
  bad "post-failover create failed: ${CREATED2:-<no response>}"
  OID2="$OID"
fi

step "4. Old primary restarts and REJOINS AS FOLLOWER (lock fencing)"
docker start "$(docker ps -aqf "name=${COMPOSE_PROJECT}-order-matcher-1")" >/dev/null
wait_for 120 "old primary to answer /admin/role" bash -c "curl -sf '$PRIMARY/admin/role'" || bad "old primary never came back"
sleep 2
[[ "$(role_of "$PRIMARY")" == "standby" && "$(live_of "$PRIMARY")" == "False" ]] \
  && ok "old primary demoted itself to follower (lock held by promoted node)" \
  || bad "old primary role after restart: $(curl -s "$PRIMARY/admin/role")"
wait_for 20 "old primary (now follower) to apply $OID2" bash -c "curl -sf '$PRIMARY/orders/$OID2' | grep -q '\"quantity\":77'" \
  && ok "rejoined follower caught up ($OID2 visible)" \
  || bad "rejoined follower missing $OID2"
[[ "$(role_of "$STANDBY")" == "promoted" ]] && ok "promoted node still the leader" || bad "leadership flapped: $(role_of "$STANDBY")"

echo
echo "==================================================="
echo " verify-009b-failover: $pass passed, $fail failed"
echo "==================================================="
exit $(( fail > 0 ? 1 : 0 ))
