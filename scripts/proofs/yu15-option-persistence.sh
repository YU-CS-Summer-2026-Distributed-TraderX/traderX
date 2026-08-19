#!/usr/bin/env bash
# yu15-option-persistence.sh — proves listed options can reach the SQL read model, and that the
# shipped migration fixes a database created by an older state.
#
# The bug: YU14 made options tradeable, but every instrument-identifier column was VARCHAR(15)/(16)
# while an unpadded OCC symbol is 19 characters. MariaDB's strict mode rejected the insert, so
# every option fill the ADR-048 trade bridge published was dropped by trade-processor — the SQL
# blotter, the positions read model, and the whole YU06 EOD price/P&L chain silently excluded
# every option.
#
# This runs the real chain: cluster books the fill, the leader-side bridge publishes it to NATS
# /trades, trade-processor persists Trade + Position. It deliberately narrows the columns back
# first, so the failure is demonstrated rather than asserted, and then applies the state's own
# 900-migrations.sql — the path a populated PVC actually takes on upgrade, where
# CREATE TABLE IF NOT EXISTS is a no-op and only an explicit MODIFY can widen anything.
#
# Usage: ./yu15-option-persistence.sh     (assumes scripts/yu15/start-cluster-kind.sh has run)
set -euo pipefail

VERBOSE=0
case "${1:-}" in -v|--verbose) VERBOSE=1; shift ;; esac
# STDERR: sql() is captured with $(...) at four sites (row counts, the stored symbol, the position
# count), so a verbose line on stdout would be parsed as a count.
vlog() { [ "${VERBOSE}" = 1 ] && printf '%s\n' "$@" >&2 || true; }

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"

# Two distinct contracts so the before/after crosses can never be confused for each other.
BEFORE_SYM="AAPL260918C00260000"
AFTER_SYM="AAPL261218C00260000"
PREMIUM="2.40"
SELLER=42422
BUYER=22214

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }
# Errors are surfaced, not swallowed: a silently failing ALTER would make the proof look like it
# passed a step it never ran.
# Errors are surfaced, not swallowed: a silently failing ALTER would make the proof look like it
# passed a step it never ran. The `|| true` is on the grep, which exits 1 when a statement
# produces no output at all — which is the normal case for DELETE and ALTER.
sql()  { vlog "      SQL: $(printf '%s' "$1" | tr '\n' ' ' | tr -s ' ')"; ${K} exec deploy/eod-price-db -- mariadb -utraderx -ptraderx traderx -sN -e "$1" 2>&1 \
           | { grep -v "Using a password on the command line" || true; }; }

widths() {
  sql "SELECT CONCAT(table_name,'.',column_name,'=',character_maximum_length)
       FROM information_schema.columns
       WHERE table_schema='traderx' AND column_name IN ('security','ticker')
       ORDER BY table_name;"
}

cross() { # cross <ticker> <price> — seed then trade both sides; returns after the bridge settles
  # The symbol length is the whole subject: 19 chars against a VARCHAR(15) is the bug this proof
  # demonstrates, so print it rather than leaving the reader to count characters.
  vlog "      cross ${1} @ ${2}   (symbol length ${#1})"
  vlog "      POST ${MATCHER_URL}/seed  {\"accountId\":${SELLER},\"tickers\":\"${1}\",\"price\":${2}}"
  curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${SELLER},\"tickers\":\"${1}\",\"price\":${2}}" >/dev/null \
    || fail "seed failed for ${1}"
  for side_account in "Sell:${SELLER}" "Buy:${BUYER}"; do
    local side="${side_account%%:*}" acct="${side_account##*:}" code
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 -X POST "${MATCHER_URL}/orders" \
      -H 'Content-Type: application/json' \
      -d "{\"accountId\":${acct},\"ticker\":\"${1}\",\"side\":\"${side}\",\"quantity\":5,\"limitPrice\":${2}}")"
    vlog "      POST /orders ${side} acct=${acct} qty=5 px=${2} -> HTTP ${code}"
    [[ "${code}" == 200 ]] || fail "${side} order on ${1} returned HTTP ${code}"
  done
  sleep 6
}

step "0. preflight"
curl -sf --max-time 10 "${MATCHER_URL}/ready" >/dev/null \
  || fail "gateway not reachable at ${MATCHER_URL} (port-forward svc/order-matcher 18110:18110?)"
${K} get deploy trade-processor >/dev/null 2>&1 || fail "trade-processor is not deployed"
[[ -n "$(${K} get statefulset order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="TRADE_BRIDGE_NATS_URL")].value}')" ]] \
  || fail "TRADE_BRIDGE_NATS_URL is not set on the cluster members — options can never reach SQL"
echo "[ok] gateway ready, trade-processor up, trade bridge enabled"

step "1. narrow the columns back to an older state's widths"
# A database created before this state never held an option row — the columns could not store one.
# Now that the feed quotes the chain and the bridge persists option fills, real 19-character rows
# exist, and MariaDB (correctly) refuses to shrink a column under them. Clear them first so the
# starting state is a faithful older-schema database rather than an impossible hybrid.
# Capture the universe rows this regression is about to delete, so step 3b can put them back.
# Real catalog members live above 16 characters now (UST bills and STRIPS at 17-18, two
# corporates at 17); deleting them without restoring is what silently ate three manual catalog
# repairs in one day and failed yu16-bond-position LATER IN THE SAME SUITE, two proofs away from
# the cause. Two columns rather than CONCAT(ticker, 0x09, name): mariadb batch mode escapes a
# tab INSIDE a value as backslash-t, so only the real inter-column separator is a genuine tab.
DOOMED="$(sql "SELECT ticker, company_name FROM stocks WHERE CHAR_LENGTH(ticker) > 16;")"
sql "DELETE FROM eod_position_pnl WHERE CHAR_LENGTH(security) > 16;
     DELETE FROM eod_price_snapshot WHERE CHAR_LENGTH(security) > 16;
     DELETE FROM trades WHERE CHAR_LENGTH(security) > 15;
     DELETE FROM positions WHERE CHAR_LENGTH(security) > 15;
     DELETE FROM orderbook WHERE CHAR_LENGTH(security) > 16;
     DELETE FROM stocks WHERE CHAR_LENGTH(ticker) > 16;
     DELETE FROM stocks_control_outbox WHERE CHAR_LENGTH(ticker) > 16;"

# Exactly the definitions YU06 and earlier ship. This is what any database created before this
# state looks like.
sql "ALTER TABLE positions MODIFY COLUMN security VARCHAR(15);
     ALTER TABLE trades MODIFY COLUMN security VARCHAR(15);
     ALTER TABLE orderbook MODIFY COLUMN security VARCHAR(16) NOT NULL;
     ALTER TABLE eod_price_snapshot MODIFY COLUMN security VARCHAR(16) NOT NULL;
     ALTER TABLE eod_position_pnl MODIFY COLUMN security VARCHAR(16) NOT NULL;
     ALTER TABLE stocks MODIFY COLUMN ticker VARCHAR(16) NOT NULL;
     ALTER TABLE stocks_control_outbox MODIFY COLUMN ticker VARCHAR(16) NOT NULL;"
widths | sed 's/^/  /'
echo "[ok] database now matches the pre-YU15 schema (an OCC symbol is ${#BEFORE_SYM} characters)"

step "2. book an option cross — it must NOT reach the database"
BEFORE_ERRORS="$(${K} logs deploy/trade-processor --tail=-1 | grep -ci 'data too long' || true)"
cross "${BEFORE_SYM}" "${PREMIUM}"

ROWS="$(sql "SELECT COUNT(*) FROM trades WHERE security='${BEFORE_SYM}';")"
[[ "${ROWS}" == "0" ]] || fail "expected the narrow schema to reject the option, but ${ROWS} row(s) landed"
AFTER_ERRORS="$(${K} logs deploy/trade-processor --tail=-1 | grep -ci 'data too long' || true)"
[[ "${AFTER_ERRORS}" -gt "${BEFORE_ERRORS}" ]] \
  || fail "no rows AND no 'Data too long' error — the fill never reached trade-processor at all"
echo "[ok] 0 rows for ${BEFORE_SYM}; trade-processor logged the rejection:"
${K} logs deploy/trade-processor --tail=-1 | grep -i 'data too long' | tail -1 | cut -c1-160 | sed 's/^/  /'

# The fill IS in the cluster — only the read model lost it. That asymmetry is the whole point.
CLUSTER_TRADES="$(curl -sf --max-time 10 "${MATCHER_URL}/metrics" | awk '/event="fill"/ {print $2; exit}')"
echo "[ok] the cluster booked it regardless (gateway fill counter: ${CLUSTER_TRADES}) — only SQL lost it"

step "3. apply the state's own 900-migrations.sql to the populated volume"
# The same file, applied the same way the database Deployment's schema-migrate initContainer
# applies it. CREATE TABLE IF NOT EXISTS no-ops against tables that already exist; only the
# explicit MODIFY statements widen anything.
# Read it from the applied ConfigMap, which is what the initContainer mounts — so this tests the
# artifact that actually ships, not a local copy of it.
${K} get configmap database-init-sql -o "jsonpath={.data['900-migrations\\.sql']}" \
  > /tmp/yu15-900-migrations.sql
[[ -s /tmp/yu15-900-migrations.sql ]] || fail "could not read 900-migrations.sql from the ConfigMap"
grep -c "MODIFY COLUMN security\|MODIFY COLUMN ticker" /tmp/yu15-900-migrations.sql | sed 's/^/  MODIFY statements in the migration: /'
${K} exec -i deploy/eod-price-db -- mariadb -utraderx -ptraderx traderx < /tmp/yu15-900-migrations.sql
widths | sed 's/^/  /'
NARROW="$(widths | grep -cv '=32$' || true)"
[[ "${NARROW}" == "0" ]] || fail "${NARROW} column(s) still narrow after the migration"
echo "[ok] every instrument-identifier column widened in place, on a populated volume"

step "3b. restore the universe rows the step-1 regression deleted — through the outbox path"
# POST /stocks, never raw SQL: reference-data writes the stocks row and its control-outbox row in
# one transaction, and a raw insert would diverge the durable control feed
# (issues/open/catalog-additions-never-reach-a-deployed-environment.md). In-cluster via the
# service DNS so this step does not depend on whichever host port-forwards happen to be alive.
# The restore must run AFTER the widen — a 17-character ticker cannot enter a VARCHAR(16) column.
RESTORED=0
if [[ -n "${DOOMED}" ]]; then
  while IFS=$'\t' read -r T C; do
    [[ -n "${T}" ]] || continue
    CODE="$(${K} exec deploy/reference-data -- node -e "
      fetch('http://reference-data:18085/stocks', {method:'POST',
        headers:{'Content-Type':'application/json'},
        body: JSON.stringify({ticker: process.argv[1], companyName: process.argv[2]})})
      .then(r => { console.log(r.status); process.exit(0); })
      .catch(() => { console.log('ERR'); process.exit(1); })" "${T}" "${C}" 2>/dev/null)"
    [[ "${CODE}" == "201" ]] || fail "restore of ${T} through POST /stocks failed (got '${CODE}')"
    RESTORED=$((RESTORED+1))
  done <<< "${DOOMED}"
fi
LEFT="$(sql "SELECT COUNT(*) FROM stocks WHERE CHAR_LENGTH(ticker) > 16;")"
[[ "${LEFT}" == "${RESTORED}" ]] || fail "restored ${RESTORED} long-ticker rows but the table holds ${LEFT}"

# Do not exit while our own writes are still in flight: each POST above queued a control-outbox
# row that the gateway later applies INTO CONSENSUS, and that tail moves the cluster's applied
# sequence after this proof exits — yu16-bond-position step 1 asserts that very sequence is
# still, so an unflushed tail fails the NEXT proof in the suite, two minutes from its cause.
# Quiesce oracle: the gateway logs "CONTROL-FEED applied ... version=N" per event; wait until it
# has applied our highest outbox version.
if [[ "${RESTORED}" -gt 0 ]]; then
  MAXV="$(sql "SELECT MAX(version) FROM stocks_control_outbox;")"
  QUIESCED=0
  for _ in $(seq 1 60); do
    # grep WITHOUT -q: under this script's pipefail, -q's early exit SIGPIPEs kubectl and the
    # whole pipeline reads as a miss even when the line is present. Full-read grep drains the stream.
    if ${K} logs deploy/cluster-gateway --since=10m 2>/dev/null \
         | grep "CONTROL-FEED applied .*version=${MAXV}\b" >/dev/null; then QUIESCED=1; break; fi
    sleep 2
  done
  [[ "${QUIESCED}" == 1 ]] || fail "control feed never applied outbox version ${MAXV} to the cluster within 120s"
fi
echo "[ok] ${RESTORED} long-ticker universe rows restored (one outbox row each, applied through consensus; 0 restored is legal on a rig that never held them)"

step "4. book another option cross — it must land intact"
cross "${AFTER_SYM}" "${PREMIUM}"

TRADE_ROWS="$(sql "SELECT COUNT(*) FROM trades WHERE security='${AFTER_SYM}';")"
[[ "${TRADE_ROWS}" == "2" ]] || fail "expected 2 trade rows (both sides) for ${AFTER_SYM}, got ${TRADE_ROWS}"
STORED="$(sql "SELECT DISTINCT security FROM trades WHERE security LIKE 'AAPL2612%';")"
[[ "${STORED}" == "${AFTER_SYM}" ]] || fail "symbol stored as '${STORED}', expected '${AFTER_SYM}' — truncated"
POS="$(sql "SELECT COUNT(*) FROM positions WHERE security='${AFTER_SYM}';")"
[[ "${POS}" == "2" ]] || fail "expected 2 position rows for ${AFTER_SYM}, got ${POS}"

echo "[ok] both sides persisted, symbol intact at ${#AFTER_SYM} characters:"
${K} exec deploy/eod-price-db -- mariadb -utraderx -ptraderx traderx -e "
  SELECT id, accountid, security, side, quantity, price FROM trades WHERE security='${AFTER_SYM}';
  SELECT accountid, security, quantity, averagecostbasis FROM positions WHERE security='${AFTER_SYM}';
" 2>/dev/null | sed 's/^/  /'

echo
echo "=== PASS — listed options reach the SQL read model, and the migration fixes an existing database ==="
