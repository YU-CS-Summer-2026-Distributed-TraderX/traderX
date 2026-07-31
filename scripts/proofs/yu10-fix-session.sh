#!/usr/bin/env bash
# yu10-fix-session.sh — live-on-kind proof for YU10 FIX ingress (SC-FIX01, SC-FIX06 shape, and the
# FIX/REST equivalence claim). Deep session correctness (cancel/status/duplicate/resend) is proven
# in-JVM by FixSessionIntegrationTest; this script proves it works end-to-end on the deployed
# cluster: a real FIX session admits orders through the same ring/journal/risk/DB path REST uses.
#
# Prereqs: the state is up on kind (order-matcher Ready), node available.
# Reaches the FIX acceptor via `kubectl port-forward` (the FIX port is cluster-internal by design).
set -uo pipefail

NS="${NS:-traderx}"
EDGE="${EDGE:-http://localhost:8080}"
FIX_LOCAL_PORT="${FIX_LOCAL_PORT:-18130}"
ACCOUNT="${ACCOUNT:-11413}"          # BENCH01 -> 11413 in the kind manifest FIX_SESSION_ACCOUNTS
# Read the CompID the deployed acceptor is actually configured for rather than assuming BENCH01.
# A logon from an unconfigured CompID is refused by quickfix, the sender completes no lifecycles,
# and the proof reports "0 completed" -- which reads as FIX ingress being broken when the ingress
# is fine and the two ends simply disagree about who is allowed to connect. The cluster gateway
# ships FIX_SESSION_COMPIDS=CLIENT1; the state-014 rig used BENCH01.
COMP_ID="${FIX_COMP_ID:-$(kubectl --context "${CTX:-kind-traderx-yu12-cluster}" -n "${NS:-traderx}" get deploy cluster-gateway -o "jsonpath={.spec.template.spec.containers[0].env[?(@.name=='FIX_SESSION_COMPIDS')].value}" 2>/dev/null | cut -d, -f1)}"
COMP_ID="${COMP_ID:-CLIENT1}"
SECS="${SECS:-10}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

pass=0; fail=0
step() { printf "   %-42s %s\n" "$1" "$2"; }
ok()   { pass=$((pass+1)); step "$1" "✔ $2"; }
bad()  { fail=$((fail+1)); step "$1" "✘ $2"; }

echo "YU10 FIX ingress — live session proof (account ${ACCOUNT}, CompID ${COMP_ID})"

# 1. mint a JWT for the session — the dev-token endpoint lives on trade-processor and requires the
#    master secret header (same infra the YU05 auth demos use); scoped to this account.
# The cluster rig has no edge-proxy, so trade-processor is addressed directly (port-forward
# 18091). On the state-014 rig it sits behind the edge-proxy: TRADE_PROCESSOR_URL=$EDGE/trade-processor.
TP="${TRADE_PROCESSOR_URL:-http://localhost:18091}"
# Read from the cluster: the two rigs hold different values in the auth-secrets Secret, and a
# wrong one fails as an opaque "no token" with nothing naming the secret as the cause.
MASTER="${AUTH_MASTER_SECRET:-$(kubectl --context "${CTX:-kind-traderx-yu12-cluster}" -n "${NS:-traderx}" get secret auth-secrets -o "jsonpath={.data.dev-token-master-secret}" 2>/dev/null | base64 -d 2>/dev/null)}"
MASTER="${MASTER:-dev-token-master-secret}"
FIX_JWT="$(curl -s -m8 -X POST "${TP}/auth/dev-token" \
  -H "X-Auth-Master-Secret: ${MASTER}" -H "Content-Type: application/json" \
  -d "{\"subject\":\"fix-${COMP_ID}\",\"accounts\":[${ACCOUNT}],\"admin\":false,\"ttlSeconds\":600}" \
  | python3 -c "import sys,json
s=sys.stdin.read().strip()
try: print(json.loads(s).get('token') or json.loads(s).get('accessToken') or s)
except Exception: print(s)")"
if [ -n "${FIX_JWT}" ] && [ "${FIX_JWT}" != "null" ] && ! echo "${FIX_JWT}" | grep -q '404\|error'; then
  ok "mint session JWT" "dev-token issued (account ${ACCOUNT})"
else bad "mint session JWT" "no token from ${TP}/auth/dev-token"; exit 1; fi

# 2. DB projection baseline for this account (proves FIX orders reach the same read model)
db_orders() {
  # Was: kubectl exec (no --context) into deploy/database as root. On the cluster rig the
  # deployment is eod-price-db, its container is "mariadb", and there is no MARIADB_ROOT_PASSWORD
  # in scope -- so this returned empty every time and BEFORE/AFTER were both 0, which the script
  # then reported as "no projection growth" no matter what FIX actually did.
  kubectl --context "${CTX:-kind-traderx-yu12-cluster}" -n "${NS}" \
    exec "deploy/${DB_DEPLOY:-eod-price-db}" -c "${DB_CONTAINER:-mariadb}" -- \
    mariadb -utraderx -ptraderx traderx -N -B \
    -e "SELECT COUNT(*) FROM orderbook WHERE accountid=${ACCOUNT}" 2>/dev/null | tail -1
}
BEFORE="$(db_orders)"; BEFORE="${BEFORE:-0}"
step "orderbook rows before" "${BEFORE}"

# 3. port-forward the FIX acceptor and run a short completed-lifecycle burst
kubectl port-forward -n "${NS}" svc/order-matcher "${FIX_LOCAL_PORT}:18130" >/dev/null 2>&1 &
PF=$!
trap 'kill "${PF}" 2>/dev/null' EXIT
sleep 3

OUT="$(FIX_JWT="${FIX_JWT}" FIX_COMP_ID="${COMP_ID}" FIX_PORT="${FIX_LOCAL_PORT}" \
  SIDES=alternate QTY=1 PX=190 node "${here}/../bench/load/fix-load.mjs" --secs "${SECS}" 2>&1)"
echo "${OUT}" | tail -1
COMPLETED="$(echo "${OUT}" | sed -n 's/.*completed=\([0-9]*\).*/\1/p' | tail -1)"
COMPLETED="${COMPLETED:-0}"
if [ "${COMPLETED}" -gt 0 ]; then ok "FIX completed lifecycles" "${COMPLETED} order->ExecutionReport round-trips"
else bad "FIX completed lifecycles" "0 completed (see output above)"; fi

# 4. the FIX/REST equivalence: those orders landed in the same DB projection
sleep 2
AFTER="$(db_orders)"; AFTER="${AFTER:-0}"
step "orderbook rows after" "${AFTER}"
if [ "${AFTER}" -gt "${BEFORE}" ]; then ok "FIX orders in the read model" "projection grew by $((AFTER-BEFORE))"
else bad "FIX orders in the read model" "no projection growth"; fi

echo
echo "   result: ${pass} passed, ${fail} failed"
[ "${fail}" -eq 0 ]
