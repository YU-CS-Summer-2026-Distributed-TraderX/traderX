#!/usr/bin/env bash
# yu06-consumer-halt.sh — proves the EOD P&L consumer is fail-safe (FR-EOD32): an account holding
# a security with NO published close is HELD BACK ENTIRELY — zero P&L rows, never a partial mark
# or a guessed price — while every other account is marked normally.
#
# Induction lever: EOD_UNIVERSE excludes NVDA, which account 10031 verifiably holds. The session
# then publishes CLEAN (every listed instrument OK — nothing is flagged, so the gate lets it out),
# and the failure is pushed to the consumer: it cannot mark 10031's NVDA leg, and must halt that
# account rather than write the legs it can price.
#
# Falsifiability guards (why zero rows is evidence and not an accident):
#   * the NVDA holding is asserted PRESENT in the positions table first — otherwise "0 P&L rows
#     for 10031" would also pass on a rig where 10031 simply holds nothing;
#   * other accounts are asserted MARKED in the same version — otherwise "0 rows" would also pass
#     with the consumer dead.
#
# Recovered from the YU06 demo-prep scripts and hardened into a proof (every claim hard-fails).
# Self-contained via kubectl; resets EOD_UNIVERSE on exit.
# kind-runnable: pure correctness, no timing claim. Usage: ./yu06-consumer-halt.sh
set -uo pipefail

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
# Rig-dependent addresses. Defaults are the YU15 cluster rig; for the state-014 rig set
# DB_DEPLOY=database EXEC_POD=edge-proxy.
DB_DEPLOY="${DB_DEPLOY:-eod-price-db}"
EXEC_POD="${EXEC_POD:-trade-processor}"
# Read from the cluster rather than hardcoded: the two rigs hold different values, and a wrong
# one fails as an opaque 401 from /auth/dev-token with nothing naming the secret as the cause.
MASTER="${AUTH_MASTER_SECRET:-$(kubectl --context "${CTX}" -n "${NS}" get secret auth-secrets -o "jsonpath={.data.dev-token-master-secret}" 2>/dev/null | base64 -d 2>/dev/null)}"
MASTER="${MASTER:-dev-token-master-secret}"
DATE="$(date +%F)"
HELD_ACCT="${HELD_ACCT:-10031}"
HELD_SEC="${HELD_SEC:-NVDA}"
UNIVERSE="AAPL,MSFT,AMZN,GOOGL,META,TSLA,IBM,BAC,C,JPM,GS,MS,UBS,DB,COF,DFS,FNMA,FIS,FNF"  # no NVDA

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }
kx() { kubectl --context "${CTX}" -n "${NS}" "$@"; }
db() { kx exec "deploy/${DB_DEPLOY}" -- mariadb -utraderx -ptraderx traderx -N -e "$1" 2>/dev/null; }
TOK='T=$(curl -s -m8 -X POST http://trade-processor:18091/auth/dev-token -H "X-Auth-Master-Secret: '"${MASTER}"'" -H "Content-Type: application/json" -d "{\"subject\":\"proof\",\"accounts\":[],\"admin\":true,\"ttlSeconds\":600}")'
api() { kx exec "deploy/${EXEC_POD}" -- sh -c "${TOK}; $1" 2>/dev/null; }

cleanup() {
  kx set env deploy/trade-processor EOD_UNIVERSE- >/dev/null 2>&1
  kx rollout status deploy/trade-processor --timeout=180s >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ---------------------------------------------------------------------------------------------
step "0. preflight: ${HELD_ACCT} really holds ${HELD_SEC}, and other accounts hold marked stock"
QTY="$(db "SELECT quantity FROM positions WHERE accountid=${HELD_ACCT} AND security='${HELD_SEC}';")"
[[ -n "${QTY}" && "${QTY}" != "0" ]] \
  || fail "${HELD_ACCT} holds no ${HELD_SEC} — zero P&L rows would prove nothing on this rig"
OTHERS_HOLDING="$(db "SELECT COUNT(DISTINCT accountid) FROM positions WHERE accountid<>${HELD_ACCT};")"
[[ "${OTHERS_HOLDING:-0}" -ge 1 ]] || fail "no other account holds anything — no control group"
kx rollout status deploy/trade-processor --timeout=240s >/dev/null 2>&1 \
  || fail "trade-processor is not READY (a previous run's reset may still be rolling)"
echo "[ok] ${HELD_ACCT} holds ${QTY} ${HELD_SEC}; ${OTHERS_HOLDING} other holding accounts as control"

step "1. inject a universe WITHOUT ${HELD_SEC}; close until the session is clean and PUBLISHED"
kx set env deploy/trade-processor EOD_UNIVERSE="${UNIVERSE}" >/dev/null || fail "could not set EOD_UNIVERSE"
kx rollout status deploy/trade-processor --timeout=180s >/dev/null 2>&1 \
  || fail "trade-processor never came back after the universe change"
kx wait --for=condition=ready pod -l app=trade-processor --timeout=120s >/dev/null 2>&1
sleep 4
# The restart wiped the in-memory price history; close on a poll until flagged=0 so the session
# AUTO-publishes — then the halt below is purely about the excluded ${HELD_SEC} leg, not a flag.
# Only trust a session THIS run created: a previous proof leaves today's row in exactly the
# target shape, and reading it before our first close lands would pass on stale evidence.
V_START="$(db "SELECT COALESCE(MAX(version),0) FROM eod_price_session WHERE session_date='${DATE}';")"
FLAGGED=""
for i in $(seq 1 40); do
  api "curl -s -m45 -o /dev/null -X POST http://trade-processor:18091/eod/session/close -H \"Authorization: Bearer \$T\"" >/dev/null
  sleep 3
  V="$(db "SELECT COALESCE(MAX(version),0) FROM eod_price_session WHERE session_date='${DATE}';")"
  FLAGGED="$(db "SELECT flagged_count FROM eod_price_session WHERE session_date='${DATE}' AND version=${V};")"
  printf "   close -> v%s flagged=%s\n" "${V}" "${FLAGGED}"
  [[ "${FLAGGED:-99}" == "0" && "${V:-0}" -gt "${V_START}" ]] && break
  sleep 8
done
[[ "${FLAGGED:-99}" == "0" ]] || fail "the session never closed clean (flagged=${FLAGGED:-unknown})"
sleep 4
read -r STATUS INST <<<"$(db "SELECT status, instrument_count FROM eod_price_session WHERE session_date='${DATE}' AND version=${V};")"
echo "   v${V}: status=${STATUS}, ${INST} instruments (universe excludes ${HELD_SEC})"
[[ "${STATUS}" == "PUBLISHED" ]] || fail "a clean session must publish, got ${STATUS}"
NVDA_IN_CUT="$(db "SELECT COUNT(*) FROM eod_price_snapshot WHERE session_date='${DATE}' AND version=${V} AND security='${HELD_SEC}';")"
[[ "${NVDA_IN_CUT}" == "0" ]] || fail "${HELD_SEC} is in the published cut — the induction lever failed"

step "2. the consumer marks every account EXCEPT ${HELD_ACCT}, which is halted whole"
# The projection is async: poll the effect end (P&L rows for the control group) with a bound.
OTHERS=""
for i in $(seq 1 30); do
  OTHERS="$(db "SELECT COUNT(DISTINCT account_id) FROM eod_position_pnl WHERE session_date='${DATE}' AND version=${V} AND account_id<>${HELD_ACCT};")"
  [[ "${OTHERS:-0}" -ge 1 ]] && break
  sleep 2
done
[[ "${OTHERS:-0}" -ge 1 ]] \
  || fail "no account got P&L rows for v${V} — the consumer is dead, so the halt proves nothing"
HELD_ROWS="$(db "SELECT COUNT(*) FROM eod_position_pnl WHERE session_date='${DATE}' AND version=${V} AND account_id=${HELD_ACCT};")"
printf "   %-38s %s accounts marked ✔\n" "control group (v${V})" "${OTHERS}"
printf "   %-38s %s rows\n" "held account ${HELD_ACCT} (holds ${HELD_SEC})" "${HELD_ROWS}"
[[ "${HELD_ROWS}" == "0" ]] \
  || fail "${HELD_ACCT} was PARTIALLY MARKED (${HELD_ROWS} rows) despite an unpriceable ${HELD_SEC} leg — the consumer guessed instead of halting"
HALT_LOG="$(kx logs deploy/position-service --tail=200 2>/dev/null \
  | { grep -iE "halt" | grep "version=${V}" || true; } | tail -2)"
[[ -n "${HALT_LOG}" ]] && echo "   consumer said: $(echo "${HALT_LOG}" | sed 's/^.*: //' | head -1)"

echo
echo "[PASS] EOD consumer fail-safe: with ${HELD_SEC} excluded from the published universe,"
echo "       account ${HELD_ACCT} (which provably holds it) got ZERO P&L rows — halted whole,"
echo "       not partially marked — while ${OTHERS} other accounts were marked normally."
