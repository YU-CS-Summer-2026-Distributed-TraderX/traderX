#!/usr/bin/env bash
# yu18-store-failure-barrier.sh — YU18 FR-DRS-35: a database failure is a barrier on every JetStream feed. Nothing
# after it is applied, and no cursor, snapshot or ack passes it, until it is applied; then everything lands once.
#
#   1. seed a fresh ticker; the read model's database is made READ ONLY (SET GLOBAL read_only=1): every write the
#      feeds attempt fails as a store failure (an SQLException that is not a constraint or data violation)
#   2. N crosses while the store is down: every member books 2N sides; SQL holds none of them
#   3. read_only=0: within the deadline the positions are +N/-N, and the committed per-partition accounting
#      (GET /v2/projection-feed/accounting, FR-DRS-29) is exactly the members' booked set: sides summed over the
#      partitions equal the members' trade count n, and the tradeSeq sums equal the closed form over 1..n
#      (n(n+1)/2 and n(n+1)(2n+1)/6, mod 2^64) with no duplicates; /v2/projection-feed reports dropped=0. In rows mode
#      the SQL trade rows must also number 2N.
# Needs a rig whose only writer is this proof (the closed form is over every side the epoch booked).
# The store is restored on every exit path.
#
# Negative control: NEGATIVE=1 removes one buy side from ACCT_A's position after recovery (in rows mode also its
# trade row), the loss a feed that acked past a failed write leaves. Step 3 must then FAIL; a pass means it cannot
# see one missing side.
# Usage: CTX=<ctx> NS=<ns> [ACCT_A=22214 ACCT_B=42422] [CROSSES=10] [NEGATIVE=1] bash scripts/proofs/yu18-store-failure-barrier.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
ACCT_A="${ACCT_A:-22214}"; ACCT_B="${ACCT_B:-42422}"; N="${CROSSES:-10}"
T="$(mint_ticker)"; PX=50
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
pf TP "${TP_TARGET:-deploy/trade-processor}" 18091
ADMIN="$(mint true '[]')"; [[ -n "${ADMIN}" ]] || fail "could not mint an admin bearer"
J=(-H 'Content-Type: application/json' -H "Authorization: Bearer ${ADMIN}")
rootsql() { "${K[@]}" exec "deploy/${DB_DEPLOY}" -c "${DB_CONTAINER}" -- mariadb -uroot -p"$(secret mariadb-credentials root-password)" -sN -e "$1" 2>&1; }
restore() { rootsql "SET GLOBAL read_only=0" >/dev/null; }
trap 'restore; _lib_yu18_cleanup' EXIT
trades_all() { local m s=""; for ((m=0; m<MEMBERS; m++)); do s+="$(mget "${m}" /health | jget 'd.get("trades")') "; done; echo "${s% }"; }
place() { local c; c="$(code -X POST "${GW}/orders" "${J[@]}" -d "{\"accountId\":$1,\"ticker\":\"${T}\",\"side\":\"$2\",\"quantity\":1,\"limitPrice\":${PX}}")"
  [[ "${c}" == 200 && "$(body)" != *REJECTED* ]] || fail "$2 $1 not accepted: ${c} $(body)"; }
sides() { sql "SELECT COUNT(*) FROM trades WHERE security='${T}'"; }
pos() { sql "SELECT COALESCE(SUM(quantity),0) FROM positions WHERE security='${T}' AND accountid=$1"; }
signed_sum() { sql "SELECT COALESCE(SUM(CASE WHEN side='Buy' THEN quantity ELSE -quantity END),0) FROM trades WHERE security='${T}' AND accountid=$1"; }
feed() { curl -s -m 10 "${TP}/v2/projection-feed"; }

step "0. seed ${T} @${PX}"
c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-store-failure-barrier' \
  -d "{\"accountId\":${ACCT_A},\"tickers\":\"${T}\",\"price\":${PX}}")"
[[ "${c}" == 200 ]] || fail "operator /seed ${T}: ${c} $(body)"
F0="$(feed)"; SF0="$(jget 'd["trades"].get("snapshotFailures", 0)' <<<"${F0}")"
T0="$(trades_all)"; ok "seeded; members' trades ${T0}"

step "1. the read model's store goes down (read_only=1)"
r="$(rootsql "SET GLOBAL read_only=1; SELECT @@global.read_only")"; [[ "${r}" == 1 ]] || fail "could not make the database read only: ${r}"
w="$(sql "UPDATE positions SET quantity=quantity WHERE 1=0" ; "${K[@]}" exec "deploy/${DB_DEPLOY}" -c "${DB_CONTAINER}" -- \
  mariadb -u"$(secret mariadb-credentials username)" -p"$(secret mariadb-credentials password)" traderx -e "CREATE TABLE pa_probe_ro (x INT)" 2>&1)"
[[ "${w}" == *read-only* || "${w}" == *READ-ONLY* || "${w}" == *1290* ]] || { rootsql "DROP TABLE IF EXISTS traderx.pa_probe_ro" >/dev/null; fail "the application user can still write (${w:0:160}): the outage would not be one"; }
ok "the application user's writes fail: $(grep -o "ERROR 1290[^:]*" <<<"${w}")"

step "2. ${N} crosses while the store is down"
for ((i=0; i<N; i++)); do place "${ACCT_B}" Sell; place "${ACCT_A}" Buy; done
for i in $(seq 1 30); do T1="$(trades_all)"; read -r -a a <<<"${T0}"; read -r -a b <<<"${T1}"; [[ $(( b[0] - a[0] )) -ge $((2 * N)) ]] && break; sleep 1; done
for ((m=0; m<MEMBERS; m++)); do [[ $(( b[m] - a[m] )) == $((2 * N)) ]] || fail "member ${m} trades ${a[m]} -> ${b[m]}, want +$((2 * N)) (another writer makes this inconclusive)"; done
sleep 5
[[ "$(pos "${ACCT_A}")" == 0 && "$(sides)" == 0 ]] || fail "SQL moved while the store is read only (position $(pos "${ACCT_A}"), rows $(sides)): the outage did not stop writes"
ok "members booked $((2 * N)) sides; SQL position and rows still 0 while down"

step "3. the store returns: every side lands exactly once"
restore
for i in $(seq 1 120); do [[ "$(pos "${ACCT_A}")" == "${N}" && "$(pos "${ACCT_B}")" == "-${N}" ]] && break; sleep 1; done
MODE="$(feed | jget 'd["trades"].get("projection")')"
if [[ "${NEGATIVE:-0}" == 1 ]]; then
  sql "UPDATE positions SET quantity=quantity-1 WHERE security='${T}' AND accountid=${ACCT_A}"
  [[ "${MODE}" == snapshot ]] || sql "DELETE FROM trades WHERE security='${T}' AND accountid=${ACCT_A} ORDER BY id DESC LIMIT 1"
  echo "    NEGATIVE: one of ${ACCT_A}'s buy sides removed (a side lost past the barrier)"
fi
[[ "$(pos "${ACCT_A}")" == "${N}" && "$(pos "${ACCT_B}")" == "-${N}" ]] || fail "positions ${ACCT_A}=$(pos "${ACCT_A}") ${ACCT_B}=$(pos "${ACCT_B}"), want +${N}/-${N}: a side was lost or doubled across the store failure (FR-DRS-35)"
if [[ "${MODE}" == snapshot ]]; then
  n="$(mget 0 /health | jget 'd.get("trades")')"
  for i in $(seq 1 60); do ACC="$(curl -s -m 10 "${TP}/v2/projection-feed/accounting")"
    got="$(jget "sum(p['sides'] for p in d['trades'].values())" <<<"${ACC}")"; [[ "${got}" == "${n}" ]] && break; sleep 1; done
  v="$(jget "(lambda t, n: [sum(p['sides'] for p in t), sum(int(p['sumSeq']) for p in t) % 2**64, sum(int(p['sumSq']) for p in t) % 2**64,
       sum(p['duplicates'] for p in t), max(p['maxSeq'] for p in t)] == [n, n*(n+1)//2 % 2**64, n*(n+1)*(2*n+1)//6 % 2**64, 0, n])(list(d['trades'].values()), ${n})" <<<"${ACC}")"
  [[ "${v}" == True ]] || fail "accounting is not the members' booked set 1..${n} (FR-DRS-29/35): ${ACC:0:600}"
  SF1="$(feed | jget 'd["trades"].get("snapshotFailures", 0)')"
  (( SF1 > SF0 )) || fail "trades.snapshotFailures ${SF0} -> ${SF1}: the outage never failed a trade-store write, so it tested no barrier"
  ok "the trade store's writes failed during the outage (snapshotFailures ${SF0} -> ${SF1})"
  ok "snapshot accounting: ${n} sides, tradeSeq sums equal the closed form over 1..${n}, 0 duplicates"
else
  k="$(sides)"; [[ "${k}" == $((2 * N)) ]] || fail "SQL holds ${k} sides on ${T}, want $((2 * N)) (FR-DRS-35)"
  for a in "${ACCT_A}" "${ACCT_B}"; do [[ "$(pos "${a}")" == "$(signed_sum "${a}")" ]] || fail "account ${a}: position != signed sum of its rows"; done
  ok "rows mode: $((2 * N)) SQL rows, positions equal their signed sums"
fi
v="$(feed | jget 'd["trades"].get("dropped")')"; [[ "${v}" == 0 ]] || fail "projection-feed trades.dropped=${v}"
ok "positions +${N}/-${N}; dropped 0"

echo; echo "YU18-STORE-FAILURE-BARRIER PASS ticker=${T}"
