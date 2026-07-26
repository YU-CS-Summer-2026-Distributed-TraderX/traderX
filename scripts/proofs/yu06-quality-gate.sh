#!/usr/bin/env bash
# yu06-quality-gate.sh — proves the EOD publication quality gate is fail-safe (FR-EOD10/11/12/13/23):
# a MISSING closing price flags the session, publication is BLOCKED (HTTP 409, status stays DRAFT)
# until an operator override — with a reason — resolves it as a NEW version, which then publishes.
# The flagged version itself is immutable: it survives the override and publish untouched.
#
# Induction lever: EOD_UNIVERSE is set to include a ticker (QLTY) that never receives a price tick,
# so the close flags exactly it as MISSING. Recovered from the YU06 demo-prep scripts and hardened
# into a proof: every claim now hard-fails, including the one the demo never tested — that a
# publish attempt WHILE FLAGGED is actually refused.
#
# Self-contained via kubectl (in-cluster curl through the edge-proxy pod; JWT minted from the
# dev-token endpoint). Restarts trade-processor to inject the universe; resets it on exit.
# kind-runnable: pure correctness, no timing claim. Usage: ./yu06-quality-gate.sh
set -uo pipefail

CTX="${CTX:-kind-traderx-state-014}"
NS="${NS:-traderx}"
DATE="$(date +%F)"
UNIVERSE="AAPL,MSFT,AMZN,GOOGL,META,NVDA,TSLA,IBM,BAC,C,JPM,GS,MS,UBS,DB,COF,DFS,FNMA,FIS,FNF,QLTY"

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }
kx() { kubectl --context "${CTX}" -n "${NS}" "$@"; }
db() { kx exec deploy/database -- mariadb -utraderx -ptraderx traderx -N -e "$1" 2>/dev/null; }
# In-cluster curl: mint an admin JWT, then run the request — one exec, no port-forward to die.
TOK='T=$(curl -s -m8 -X POST http://trade-processor:18091/auth/dev-token -H "X-Auth-Master-Secret: dev-token-master-secret" -H "Content-Type: application/json" -d "{\"subject\":\"proof\",\"accounts\":[],\"admin\":true,\"ttlSeconds\":600}")'
api() { kx exec deploy/edge-proxy -- sh -c "${TOK}; $1" 2>/dev/null; }
# The exec channel through edge-proxy drops intermittently on this long-lived rig (curl reports
# 000 without the request ever leaving). Retry until a real HTTP code comes back — the endpoints
# hit this way are idempotent (publish is version-gated, override re-resolves the same fact).
api_code() { # api_code <curl-args-after-api> -> http code, retried past 000
  local code i
  for i in 1 2 3 4 5; do
    code="$(api "$1")"
    [[ -n "${code}" && "${code}" != "000" ]] && { echo "${code}"; return 0; }
    sleep 4
  done
  echo "${code:-000}"
}

# The induction lever is a live-config change; put it back whatever happens, or every later EOD
# close on this rig silently runs against the proof's universe.
cleanup() {
  kx set env deploy/trade-processor EOD_UNIVERSE- >/dev/null 2>&1
  kx rollout status deploy/trade-processor --timeout=180s >/dev/null 2>&1 || true
}
trap cleanup EXIT

session() { # session <version> -> "<status> <flagged_count>"
  db "SELECT status, flagged_count FROM eod_price_session WHERE session_date='${DATE}' AND version=$1;"
}
maxver() { db "SELECT COALESCE(MAX(version),0) FROM eod_price_session WHERE session_date='${DATE}';"; }

# ---------------------------------------------------------------------------------------------
step "0. preflight: inject a universe whose ticker QLTY can never be priced"
kx rollout status deploy/trade-processor --timeout=240s >/dev/null 2>&1 \
  || fail "trade-processor is not READY (a previous run's reset may still be rolling)"
kx set env deploy/trade-processor EOD_UNIVERSE="${UNIVERSE}" >/dev/null || fail "could not set EOD_UNIVERSE"
kx rollout status deploy/trade-processor --timeout=180s >/dev/null 2>&1 \
  || fail "trade-processor never came back after the universe change"
kx wait --for=condition=ready pod -l app=trade-processor --timeout=120s >/dev/null 2>&1
sleep 4
echo "[ok] universe injected (${UNIVERSE##*,} is priceless by construction)"

step "1. close the session until ONLY QLTY is flagged (restart wiped the price history)"
# Each close mints a new version; ticks from the live price-publisher refill the history, so poll
# until the flag set collapses to the one genuinely priceless ticker. Never warming up IS a fail.
# Only trust a session THIS run created: a previous proof leaves today's row in exactly the
# target shape, and reading it before our first close lands would pass on stale evidence.
V_START="$(db "SELECT COALESCE(MAX(version),0) FROM eod_price_session WHERE session_date='${DATE}';")"
FLAGGED=""
for i in $(seq 1 40); do
  api "curl -s -m45 -o /dev/null -X POST http://trade-processor:18091/eod/session/close -H \"Authorization: Bearer \$T\"" >/dev/null
  sleep 3
  V="$(maxver)"
  FLAGGED="$(db "SELECT flagged_count FROM eod_price_session WHERE session_date='${DATE}' AND version=${V};")"
  printf "   close -> v%s flagged=%s\n" "${V}" "${FLAGGED}"
  [[ "${FLAGGED:-99}" == "1" && "${V:-0}" -gt "${V_START}" ]] && break
  sleep 8
done
[[ "${FLAGGED:-99}" == "1" ]] || fail "flag set never collapsed to just QLTY (still ${FLAGGED:-unknown})"
read -r STATUS FLAGGED <<<"$(session "${V}")"
QROW="$(db "SELECT security, quality FROM eod_price_snapshot WHERE session_date='${DATE}' AND version=${V} AND quality<>'OK';")"
echo "   v${V}: status=${STATUS} flagged=${FLAGGED} — flagged row: [${QROW}]"
[[ "${STATUS}" == "DRAFT" ]] || fail "a flagged close must land DRAFT, got ${STATUS}"
[[ "${QROW}" == QLTY* ]] || fail "expected QLTY to be the flagged instrument, got: ${QROW}"

step "2. publication of the flagged session is REFUSED (the gate itself)"
PCODE="$(api_code "curl -s -m45 -o /dev/null -w '%{http_code}' -X POST http://trade-processor:18091/eod/prices/${DATE}/publish -H \"Authorization: Bearer \$T\"")"
echo "   POST /eod/prices/${DATE}/publish while flagged -> HTTP ${PCODE}"
[[ "${PCODE}" == "409" ]] || fail "expected 409 BLOCKED for a flagged session, got ${PCODE}"
read -r STATUS2 _ <<<"$(session "${V}")"
[[ "${STATUS2}" == "DRAFT" ]] || fail "the refused publish changed the session status to ${STATUS2}"
echo "   ✔ blocked: still DRAFT, nothing published"

step "3. an operator override (with a reason) resolves QLTY as a NEW version"
OCODE="$(api_code "curl -s -m45 -o /dev/null -w '%{http_code}' -X POST http://trade-processor:18091/eod/prices/${DATE}/override -H \"Authorization: Bearer \$T\" -H 'Content-Type: application/json' -d '{\"security\":\"QLTY\",\"price\":100.00,\"reason\":\"manual close (proof)\"}'")"
[[ "${OCODE}" == "200" ]] || fail "override was not accepted: HTTP ${OCODE}"
NV="$(maxver)"
[[ "${NV}" -gt "${V}" ]] || fail "the override did not mint a new version (still v${NV})"
QFIX="$(db "SELECT quality, closing_price FROM eod_price_snapshot WHERE session_date='${DATE}' AND version=${NV} AND security='QLTY';")"
echo "   v${V} -> v${NV}; QLTY now: [${QFIX}]"

step "4. the resolved version publishes"
# The HTTP code is informational only: publish fans out to the P&L consumer and can outlive the
# exec tunnel (curl reports 000 while the server finishes fine). The claim is asserted where it
# is decided — the session row.
PCODE2="$(api "curl -s -m45 -o /dev/null -w '%{http_code}' -X POST http://trade-processor:18091/eod/prices/${DATE}/publish -H \"Authorization: Bearer \$T\"")"
STATUS3=""
for i in $(seq 1 30); do
  read -r STATUS3 FLAGGED3 <<<"$(session "${NV}")"
  [[ "${STATUS3}" == "PUBLISHED" ]] && break
  sleep 2
done
echo "   POST /publish -> HTTP ${PCODE2}; v${NV}: status=${STATUS3} flagged=${FLAGGED3}"
[[ "${STATUS3}" == "PUBLISHED" ]] || fail "resolved session did not publish (status=${STATUS3})"

step "5. the flagged version is immutable — it survived the override and publish untouched"
read -r STATUS4 FLAGGED4 <<<"$(session "${V}")"
echo "   v${V}: status=${STATUS4} flagged=${FLAGGED4}"
[[ "${STATUS4}" == "DRAFT" && "${FLAGGED4}" == "1" ]] \
  || fail "the flagged version was mutated (status=${STATUS4} flagged=${FLAGGED4}) — versions must be immutable"

echo
echo "[PASS] EOD quality gate: MISSING price flags the session, publish is refused with 409 while"
echo "       flagged, an operator override resolves it as a new version, that version publishes,"
echo "       and the flagged version survives immutably."
