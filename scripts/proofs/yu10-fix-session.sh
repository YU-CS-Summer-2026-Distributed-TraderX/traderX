#!/usr/bin/env bash
# yu10-fix-session.sh — live-on-kind proof for YU10 FIX ingress (SC-FIX01, SC-FIX06 shape, and the
# FIX/REST equivalence claim). Deep session correctness (cancel/status/duplicate/resend) is proven
# in-JVM by FixSessionIntegrationTest; this script proves it works end-to-end on the deployed
# cluster: a real FIX session admits orders through the same ring/journal/risk/DB path REST uses.
#
# Prereqs: the state is up on kind (order-matcher Ready), node available.
# Reaches the FIX acceptor via `kubectl port-forward` (the FIX port is cluster-internal by design).
set -uo pipefail

VERBOSE=0
case "${1:-}" in -v|--verbose) VERBOSE=1; shift ;; esac
# STDERR: db_orders() is captured with $(...) for BEFORE/AFTER, so a verbose line on stdout would
# be parsed as a row count.
vlog() { [ "${VERBOSE}" = 1 ] && printf '%s\n' "$@" >&2 || true; }

NS="${NS:-traderx}"
EDGE="${EDGE:-http://localhost:8080}"
FIX_LOCAL_PORT="${FIX_LOCAL_PORT:-18130}"
# fix-load.mjs defaults to JPM at a hardcoded 190. On this rig JPM's price reference drifts into
# rejecting every order PRICE_COLLAR whatever the limit, so the sender completed nothing and the
# proof blamed FIX ingress. IBM at 200 is what seed-proof-fixtures.sh crosses and books reliably.
FIX_TICKERS="${FIX_TICKERS:-IBM}"
FIX_PX="${FIX_PX:-200}"
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
vlog "   config: account=${ACCOUNT} compId=${COMP_ID} ticker=${FIX_TICKERS} px=${FIX_PX} secs=${SECS}" \
     "   fix acceptor: svc/order-matcher :18130 -> localhost:${FIX_LOCAL_PORT}" \
     "   projection:   deploy/${DB_DEPLOY:-eod-price-db} (container ${DB_CONTAINER:-mariadb})"
BEFORE="$(db_orders)"; BEFORE="${BEFORE:-0}"
step "orderbook rows before" "${BEFORE}"

# 3. port-forward the FIX acceptor and run a short completed-lifecycle burst
#
# --context, for the same reason db_orders() above carries one. This was the last kubectl call in
# the repo without it, so the tunnel went wherever the operator's ambient context happened to
# point -- and this project keeps TWO rigs in kubeconfig. Observed 2026-08-12: current-context was
# the GKE bench cluster, which has no svc/order-matcher (it is order-matcher-gw there), the
# forward died on arrival, and the proof reported "0 completed" and "no projection growth" against
# a kind rig that was fine. A proof must not be able to report on the wrong cluster.
kubectl --context "${CTX:-kind-traderx-yu12-cluster}" -n "${NS}" \
  port-forward svc/order-matcher "${FIX_LOCAL_PORT}:18130" >/dev/null 2>&1 &
PF=$!
trap 'kill "${PF}" 2>/dev/null' EXIT

# Refuse on a dead tunnel instead of blaming FIX. Without this gate the sender's ECONNREFUSED
# becomes completed=0, which prints as "✘ FIX completed lifecycles" -- a verdict about the
# system, produced by a script that never reached it.
for _ in $(seq 1 20); do
  (exec 3<>"/dev/tcp/127.0.0.1/${FIX_LOCAL_PORT}") 2>/dev/null && { exec 3<&- 3>&-; FIX_UP=1; break; }
  sleep 1
done
if [ "${FIX_UP:-0}" != 1 ]; then
  echo "   ✘ the FIX acceptor tunnel never came up on localhost:${FIX_LOCAL_PORT}"
  echo "     context=${CTX:-kind-traderx-yu12-cluster} ns=${NS} svc/order-matcher:18130"
  echo "     This says nothing about FIX ingress — the proof could not reach it. Check that the"
  echo "     rig named by CTX is the one that is up."
  exit 1
fi

vlog "   running: SIDES=alternate QTY=1 PX=${FIX_PX:-200} TICKERS=${FIX_TICKERS:-IBM} ACCOUNT=${ACCOUNT} fix-load.mjs --secs ${SECS}"
OUT="$(FIX_JWT="${FIX_JWT}" FIX_COMP_ID="${COMP_ID}" FIX_PORT="${FIX_LOCAL_PORT}" \
  SIDES=alternate QTY=1 PX="${FIX_PX:-200}" TICKERS="${FIX_TICKERS:-IBM}" ACCOUNT="${ACCOUNT}" node "${here}/../bench/load/fix-load.mjs" --secs "${SECS}" 2>&1)"
# The FULL sender transcript, not just its last line. When rejected>0 the reason is in here and
# nowhere else -- a run that rejects every order still prints a tidy summary line, and reading only
# that line is how "1410 rejected" gets mistaken for FIX ingress being broken.
vlog "   --- fix-load.mjs transcript ---" "$(printf '%s' "${OUT}" | sed 's/^/      /')" "   --- end transcript ---"
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
