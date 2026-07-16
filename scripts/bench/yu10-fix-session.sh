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
COMP_ID="${FIX_COMP_ID:-BENCH01}"
SECS="${SECS:-10}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

pass=0; fail=0
step() { printf "   %-42s %s\n" "$1" "$2"; }
ok()   { pass=$((pass+1)); step "$1" "✔ $2"; }
bad()  { fail=$((fail+1)); step "$1" "✘ $2"; }

echo "YU10 FIX ingress — live session proof (account ${ACCOUNT}, CompID ${COMP_ID})"

# 1. mint a JWT for the session (same dev-token infra the REST demos use)
FIX_JWT="$(curl -s -m8 -X POST "${EDGE}/order-matcher/auth/dev-token" \
  -H "Content-Type: application/json" -d "{\"user\":\"user01\",\"accountId\":${ACCOUNT}}" | tr -d '"')"
if [ -n "${FIX_JWT}" ] && [ "${FIX_JWT}" != "null" ]; then ok "mint session JWT" "dev-token issued"
else bad "mint session JWT" "no token from ${EDGE}"; exit 1; fi

# 2. DB projection baseline for this account (proves FIX orders reach the same read model)
db_orders() {
  kubectl exec -n "${NS}" deploy/database -- sh -c \
    'mariadb -uroot -p"$MARIADB_ROOT_PASSWORD" traderx -N -B -e '"\"SELECT COUNT(*) FROM orderbook WHERE accountid=${ACCOUNT}\"" 2>/dev/null | tail -1
}
BEFORE="$(db_orders)"; BEFORE="${BEFORE:-0}"
step "orderbook rows before" "${BEFORE}"

# 3. port-forward the FIX acceptor and run a short completed-lifecycle burst
kubectl port-forward -n "${NS}" svc/order-matcher "${FIX_LOCAL_PORT}:18130" >/dev/null 2>&1 &
PF=$!
trap 'kill "${PF}" 2>/dev/null' EXIT
sleep 3

OUT="$(FIX_JWT="${FIX_JWT}" FIX_COMP_ID="${COMP_ID}" FIX_PORT="${FIX_LOCAL_PORT}" \
  SIDES=alternate QTY=1 PX=190 node "${here}/fix-load.mjs" --secs "${SECS}" 2>&1)"
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
