#!/usr/bin/env bash
# yu13-clordid-suppression.sh — proves a resent client order id books ONCE, asserted in SQL.
#
# The gap: ClusterGatewayMain set the InputEvent's clientOrderKey slot to a hardcoded 0 with the
# comment "FIX ClOrdID dedup is deferred", so a counterparty that reconnected and resent booked a
# SECOND live order.
#
# The fix is NOT a port of YU10's ClOrdIdLedger. That ledger is gateway-local, file-backed,
# unbounded, and lives on the Spring acceptor that does not run. The engine already carries the
# authoritative mechanism — BlpRiskState keeps a bounded, LRU-evicted, SNAPSHOTTED
# clientOrderKey -> (decision, orderRef) table inside the replicated state machine, and
# MatchingEngine.onNewOrder answers a repeat key by re-emitting the ORIGINAL order. All that was
# missing was a gateway that supplies a key.
#
# Falsifiable by construction: step 4 replays identical economics under a DIFFERENT key and shows
# the rows DO appear. Without it, "no new rows" could equally mean the cross simply stopped
# working, and the proof would assert nothing.
#
# Usage:
#   ./yu13-clordid-suppression.sh        (needs: kubectl --context "${CTX:-kind-traderx-yu12-cluster}" \
#                                                  -n traderx port-forward svc/order-matcher 18110:18110)
#   ./yu13-clordid-suppression.sh -v     verbose: the request bodies, the clientOrderId on each
#                                        call, every SQL query and the row count after each step
#
# -v earns its place on THIS proof specifically: the whole claim turns on two requests being
# identical except for one field. The terse output shows the orderRefs but not the keys, so you are
# trusting the labels when the keys are the entire subject of the test.
set -euo pipefail

VERBOSE=0
case "${1:-}" in -v|--verbose) VERBOSE=1; shift ;; esac
# STDERR: order() and rows() are both captured with $(...), so a verbose line on stdout would be
# parsed as an HTTP code or a row count.
vlog() { [ "${VERBOSE}" = 1 ] && printf '%s\n' "$@" >&2 || true; }

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
SELLER="${SELLER:-42422}"
BUYER="${BUYER:-22214}"
# A unique ticker per run, so the SQL row counts below can never pick up another run's trades.
TICKER="${TICKER:-DUP$(date +%H%M%S)}"
PRICE="${PRICE:-100.00}"
QTY=5

# Real account ids from the `accounts` table. `trades.accountid` carries a FOREIGN KEY to it, so a
# synthetic account books in the cluster and is then silently dropped by trade-processor with an
# SQLIntegrityConstraintViolationException — the exact silent-read-model-drop shape that made
# asserting in SQL a standing rule. Verify with: SELECT id FROM accounts;
fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }
# The trade bridge projects into the `database` deploy on the current rigs (eod-price-db carries
# the same schema but only EOD pricing data). Override SQL_DB for a rig wired differently.
# Deployment name and CONTAINER name are not the same thing on every rig: the cluster rig runs
# deploy/eod-price-db whose container is plainly "mariadb", while the state-014 rig ran
# deploy/database with a container of the same name. Assuming they match made `sql` fail with
# "container ... is not valid for pod", which rows() then returned as empty -- and an empty rows()
# is reported by the preflight as "already has trade rows", i.e. the single most misleading
# possible message for a container-name mismatch.
SQL_DB="${SQL_DB:-eod-price-db}"
SQL_CONTAINER="${SQL_CONTAINER:-mariadb}"
sql() { vlog "      SQL: $1"; ${K} exec deploy/${SQL_DB} -c ${SQL_CONTAINER} -- mariadb -utraderx -ptraderx traderx -sN -e "$1" 2>&1 \
          | { grep -v "Using a password on the command line" || true; }; }

rows() { sql "SELECT COUNT(*) FROM trades WHERE security='${TICKER}';"; }

order() { # order <account> <side> <clientOrderId> -> "<http> <body>"
  local out body
  body="{\"accountId\":$1,\"ticker\":\"${TICKER}\",\"side\":\"$2\",\"quantity\":${QTY},\"limitPrice\":${PRICE},\"clientOrderId\":\"$3\"}"
  # The clientOrderId is called out on its own line because it is the ONLY field that differs
  # between the suppressed resend and the falsification order — everything else is identical by
  # design, and that is exactly what the proof asserts.
  vlog "      POST ${MATCHER_URL}/orders" "        ${body}" "        clientOrderId: $3"
  out="$(curl -s --max-time 30 -o /tmp/yu13-dup-body -w '%{http_code}' \
    -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' -d "${body}")"
  vlog "      <- ${out} $(cat /tmp/yu13-dup-body)"
  echo "${out} $(cat /tmp/yu13-dup-body)"
}

ref_of() { sed -n 's/.*"orderRef":\([0-9]*\).*/\1/p' <<<"$1"; }

step "0. preflight"
# rc, not a remedy. What stands in front of the gateway differs per rig -- a forward on kind, a
# LoadBalancer with a public IP on GKE -- so a remedy written here is wrong for half its readers.
# Report what was observed and name the role; curl -f makes 22 mean "it answered, with an error".
curl -sf --max-time 10 "${MATCHER_URL}/ready" >/dev/null \
  || fail "the gateway is not reachable at ${MATCHER_URL} (curl rc=$?; 7=nothing listening,
  28=timed out, 22=it answered but /ready was not 2xx)"
${K} get deploy trade-processor >/dev/null 2>&1 || fail "trade-processor is not deployed"
[[ "$(${K} get deploy trade-processor -o jsonpath='{.status.readyReplicas}')" == "1" ]] \
  || fail "trade-processor is not READY — no fill can reach SQL, so this proof cannot run"
for acct in "${SELLER}" "${BUYER}"; do
  curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${acct},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}" >/dev/null \
    || fail "seed failed for ${acct}"
done
[[ "$(rows)" == "0" ]] || fail "${TICKER} already has trade rows; pick a fresh ticker"
echo "[ok] gateway ready, trade-processor ready, ${TICKER} seeded for ${SELLER}/${BUYER}, 0 rows"

step "1. rest a sell so the buy side has something to cross"
SELL="$(order "${SELLER}" Sell "sell-${TICKER}")"
echo "  sell -> ${SELL}"
[[ "${SELL}" == 200* ]] || fail "resting sell did not rest: ${SELL}"

step "2. buy with clientOrderId=buy-1 — crosses, and both sides persist"
BUY1="$(order "${BUYER}" Buy "buy-1-${TICKER}")"
echo "  buy  -> ${BUY1}"
[[ "${BUY1}" == 200* ]] || fail "crossing buy rejected: ${BUY1}"
REF1="$(ref_of "${BUY1}")"
sleep 6
AFTER_FIRST="$(rows)"
[[ "${AFTER_FIRST}" == "2" ]] \
  || fail "expected 2 trade rows (both sides of one match) for ${TICKER}, got ${AFTER_FIRST}"
echo "[ok] orderRef ${REF1} booked; ${AFTER_FIRST} trade rows in MariaDB"

step "3. RESEND the identical clientOrderId — must not book a second order"
# Rest another sell first, so if suppression fails there is genuinely something to cross with.
# Without this, "no new rows" would be explained by an empty book rather than by suppression.
SELL2="$(order "${SELLER}" Sell "sell2-${TICKER}")"
[[ "${SELL2}" == 200* ]] || fail "second resting sell did not rest: ${SELL2}"
echo "  a second sell is resting, so a duplicate WOULD find a counterparty"

BUY_DUP="$(order "${BUYER}" Buy "buy-1-${TICKER}")"
echo "  resend -> ${BUY_DUP}"
REF_DUP="$(ref_of "${BUY_DUP}")"
[[ "${REF_DUP}" == "${REF1}" ]] \
  || fail "resend returned a NEW orderRef ${REF_DUP} (original was ${REF1}) — it double-booked"
sleep 6
AFTER_DUP="$(rows)"
[[ "${AFTER_DUP}" == "${AFTER_FIRST}" ]] \
  || fail "resend added rows: ${AFTER_FIRST} -> ${AFTER_DUP}; the duplicate booked a second time"
echo "[ok] the resend was answered with the ORIGINAL orderRef ${REF1} and added no rows"
echo "[ok] still exactly ${AFTER_DUP} trade rows — one booking, not two"

step "4. falsification — identical economics under a DIFFERENT key MUST book"
# If this does not add rows, step 3 proved nothing: it would mean the cross had stopped working
# for some unrelated reason and "no new rows" was never evidence of suppression.
BUY2="$(order "${BUYER}" Buy "buy-2-${TICKER}")"
echo "  new key -> ${BUY2}"
REF2="$(ref_of "${BUY2}")"
[[ "${REF2}" != "${REF1}" ]] || fail "a different key returned the original orderRef — keys are colliding"
sleep 6
AFTER_NEW="$(rows)"
[[ "${AFTER_NEW}" == "4" ]] \
  || fail "a fresh key should have booked 2 more rows (total 4), got ${AFTER_NEW} — \
step 3's 'no new rows' therefore proves nothing"
echo "[ok] fresh key booked orderRef ${REF2} and added 2 rows (total ${AFTER_NEW})"
echo "[ok] so step 3's suppression was the KEY, not an inert book"

step "5. the SQL rows"
${K} exec deploy/${SQL_DB} -c ${SQL_CONTAINER} -- mariadb -utraderx -ptraderx traderx -e "
  SELECT id, accountid, security, side, quantity, price FROM trades WHERE security='${TICKER}' ORDER BY id;
" 2>/dev/null | sed 's/^/  /'

echo
echo "=== PASS — a resent client order id is answered with the original booking, asserted in SQL ==="
