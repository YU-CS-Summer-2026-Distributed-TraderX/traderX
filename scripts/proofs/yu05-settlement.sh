#!/usr/bin/env bash
# YU05 — PROOF: real settlement lifecycle. A booked trade starts Processing with a T+N settlement
# date (default T+1 business day) and advances to Settled by EITHER a scheduled sweep OR a manual
# admin force (FR-PTC02/06). No more "settled the instant it books".
#
# This proves all three claims. It used to prove one:
#   1. a booked trade does NOT settle instantly, and carries a FUTURE settlement date;
#   2. the SCHEDULED SWEEP settles a trade once its date has passed, with nobody calling anything;
#   3. an ADMIN FORCE settles a trade whose date has NOT passed.
#
# (2) was previously untested — the script forced every trade and never exercised
# SettlementService's @Scheduled sweep at all, so a dead scheduler would have passed. The sweep is
# driven here by backdating one trade's settlementdate and then waiting WITHOUT touching the force
# endpoint; if it advances, only the scheduler can have advanced it.
#
# The old verdict also printed a mismatch and exited 0 — `case "$after" in Settled*) ... esac` with
# no exit — so a broken settlement path reported itself and still passed the suite.
#
# Settlement state lives only in the trade-processor MariaDB projection (no HTTP read endpoint —
# FR-PTC07), so state is read from the DB via kubectl; the force is an HTTP POST.
#
# Prereq: source terminals in yu05-common.sh (trade-processor port-forward) + kube context reachable.
# Usage:
#   bash yu05-settlement.sh        # all three parts
#   bash yu05-settlement.sh -v     # verbose: show the SQL, the HTTP calls and each poll
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/yu05-common.sh"

VERBOSE=0
[[ "${1:-}" == "-v" || "${1:-}" == "--verbose" ]] && VERBOSE=1
vlog(){ (( VERBOSE )) && printf '%s\n' "$@" >&2 || true; }

CHECKS=0
FAILED=0
check(){ # check <label> <expected> <actual>
  CHECKS=$((CHECKS + 1))
  if [[ "$3" == "$2" ]]; then
    printf "   %-34s %s ✔\n" "$1" "$3"
  else
    printf "   %-34s %s ✘  expected %s\n" "$1" "${3:-<empty>}" "$2"
    FAILED=$((FAILED + 1))
  fi
}

ADMIN=$(mint true '[]')
[[ -z "${ADMIN}" ]] && { echo "   ✘ could not mint an admin token — is trade-processor forwarded on 18091?"; exit 1; }
vlog "   endpoints: OM=${OM}  TP=${TP}  db=deploy/${DB_DEPLOY}"

order(){ curl -s -m8 -o /dev/null -w "%{http_code}" "$OM/orders" -H "Content-Type: application/json" -d "$1"; }
stateof(){ dbq "SELECT state FROM trades WHERE id='$1';"; }
dateof(){ dbq "SELECT IFNULL(settlementdate,'') FROM trades WHERE id='$1';"; }

# book a crossing pair and return the newest Processing trade id. Polls rather than sleeping a flat
# 2s: the projection is asynchronous, and a fixed sleep is either wasteful or wrong depending on the
# box. Empty return is handled by the caller.
book_processing(){
  local b s i tid
  b=$(order '{"accountId":22214,"security":"IBM","side":"Buy","quantity":7,"limitPrice":200}')
  s=$(order '{"accountId":52355,"security":"IBM","side":"Sell","quantity":7,"limitPrice":190}')
  vlog "      booked crossing IBM pair: buy=${b} sell=${s}"
  for i in $(seq 1 20); do
    tid=$(dbq "SELECT id FROM trades WHERE state='Processing' ORDER BY created DESC LIMIT 1;")
    [[ -n "${tid}" ]] && { printf '%s' "${tid}"; return 0; }
    sleep 0.5
  done
  return 1
}

echo "── 1. A BOOKED TRADE DOES NOT SETTLE INSTANTLY (FR-PTC02) ──"
TID=$(book_processing) || { echo "   ✘ no Processing trade appeared in 10s — is trade-processor consuming?"; exit 1; }
printf "   %-34s %s\n" "newest Processing trade" "${TID}"
check "state on booking" "Processing" "$(stateof "$TID")"

SD=$(dateof "$TID")
vlog "      settlementdate=${SD}  today=$(dbq 'SELECT CURDATE();')"
# T+N means the date is strictly in the future. A trade booked with today's date would settle on the
# very next sweep, which is the "settled the instant it books" behaviour this state removed.
FUTURE=$(dbq "SELECT IF('${SD}' > CURDATE(), 'future', 'not-future');")
check "settlement date is T+N (future)" "future" "${FUTURE}"

echo
echo "── 2. THE SCHEDULED SWEEP SETTLES A DUE TRADE (nobody calls anything) ──"
# Backdate this trade so it is due, then do NOT touch the force endpoint. SettlementService runs on
# @Scheduled(fixedDelayString="${settlement.sweep.interval-ms:5000}"), so if the state advances from
# here the scheduler is the only thing that can have advanced it.
vlog "      UPDATE trades SET settlementdate=DATE_SUB(CURDATE(), INTERVAL 1 DAY) WHERE id='${TID}'"
dbq "UPDATE trades SET settlementdate=DATE_SUB(CURDATE(), INTERVAL 1 DAY) WHERE id='$TID';" >/dev/null
check "backdated to yesterday" "yesterday" "$(dbq "SELECT IF(settlementdate < CURDATE(), 'yesterday', 'not-backdated') FROM trades WHERE id='$TID';")"

swept=""
for i in $(seq 1 30); do
  swept=$(stateof "$TID")
  vlog "      poll ${i}: state=${swept}"
  [[ "${swept}" == "Settled" ]] && { printf "   %-34s after ~%ss\n" "sweep advanced it" "$((i))"; break; }
  sleep 1
done
check "settled by scheduled sweep" "Settled" "${swept}"

echo
echo "── 3. AN ADMIN FORCE SETTLES AN UN-DUE TRADE (FR-PTC06) ──"
TID2=$(book_processing) || { echo "   ✘ no second Processing trade appeared in 10s"; exit 1; }
printf "   %-34s %s\n" "second Processing trade" "${TID2}"
check "state before force" "Processing" "$(stateof "$TID2")"

vlog "      POST ${TP}/trades/${TID2}/settlement/force"
code=$(curl -s -m8 -o /dev/null -w "%{http_code}" -X POST "$TP/trades/$TID2/settlement/force" -H "Authorization: Bearer $ADMIN")
vlog "      ← HTTP ${code}"
check "force settle (admin)" "200" "${code}"

forced=""
for i in $(seq 1 10); do
  forced=$(stateof "$TID2")
  vlog "      poll ${i}: state=${forced}"
  [[ "${forced}" == "Settled" ]] && break
  sleep 1
done
check "settled by admin force" "Settled" "${forced}"

echo
if (( CHECKS == 0 )); then
  echo "[fail] no checks ran — nothing was asserted, so this is not a pass"
  exit 1
fi
if (( FAILED > 0 )); then
  echo "[FAIL] ${FAILED} of ${CHECKS} checks did not match"
  exit 1
fi
echo "[ok] ${CHECKS}/${CHECKS} checks matched — T+N held, the sweep settled a due trade, the force settled an un-due one"
