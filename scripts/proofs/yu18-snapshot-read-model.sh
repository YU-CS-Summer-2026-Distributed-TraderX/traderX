#!/usr/bin/env bash
# yu18-snapshot-read-model.sh — production's read model (ADR-097: snapshot mode on per-partition streams), at the
# effect end. YU18 FR-DRS-32, FR-DRS-20, FR-DRS-30.
#
#   1. FR-DRS-32: each of the P partitions has its own stream TRADERX_FEED_P<p> capturing /trades.<p> and /orders.<p>,
#      all with the same byte limit; a cross between two accounts of partition p = account mod P adds exactly two
#      messages to /trades.<p> of that partition's stream and none to any other partition's
#   2. FR-DRS-20: /v2/projection-feed/settlement covers exactly the booked sides: its total and per-partition sides
#      equal the committed accounting's, and today's settlesOn is the trade date + tPlusDays business days
#   3. FR-DRS-30: an order resting across a snapshot is in order_view_snapshot with the partition's order-view
#      cursor at or past its message; trade-processor is stopped and started; the restarted read model lists it open,
#      from the snapshot (its message is behind the cursor, so replay cannot bring it back). Cancelled, its row is
#      deleted by a later snapshot.
#
# Negative control: NEGATIVE=1 deletes the order's order_view_snapshot row while trade-processor is down. Step 3 must
# then FAIL (the restarted view does not list it); a pass means the order came back from somewhere other than the
# snapshot, and the step proves nothing about it.
# Restarts trade-processor (kind only). Usage: CTX=<kind ctx> NS=<ns> [ACCT_A=22214 ACCT_B=42422] [NEGATIVE=1] bash ...
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
[[ "${CTX}" == kind-* ]] || fail "kind only: step 3 stops trade-processor"
ACCT_A="${ACCT_A:-22214}"; ACCT_B="${ACCT_B:-42422}"
T="$(mint_ticker)"; PX=50
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
pf TP "${TP_TARGET:-deploy/trade-processor}" 18091
pf NATS deploy/nats 4222 tcp
ADMIN="$(mint true '[]')"; [[ -n "${ADMIN}" ]] || fail "could not mint an admin bearer"
J=(-H 'Content-Type: application/json' -H "Authorization: Bearer ${ADMIN}")
JS() { python3 "${here}/lib-jetstream.py" "$1" "127.0.0.1:${NATS}" "${@:2}"; }
tpenv() { "${K[@]}" get deploy/trade-processor -o jsonpath="{.spec.template.spec.containers[0].env[?(@.name==\"$1\")].value}"; }
place() { local c; c="$(code -X POST "${GW}/orders" "${J[@]}" -d "{\"accountId\":$1,\"ticker\":\"${T}\",\"side\":\"$2\",\"quantity\":1,\"limitPrice\":$3}")"
  [[ "${c}" == 200 && "$(body)" != *REJECTED* ]] || fail "$2 $1 @$3 not accepted: ${c} $(body)"; body | jget 'd.get("orderRef")'; }
open_ids() { curl -s -m 10 -H "Authorization: Bearer ${ADMIN}" "${TP}/accounts/$1/orders" | jget "' '.join(x['id'] for x in d if x['security']=='${T}')"; }

step "0. preflight: production's read-model shape"
P="$(tpenv PROJECTION_FEED_PARTITIONS)"; MODE="$(tpenv TRADE_PROJECTION_MODE)"; LAYOUT="$(tpenv PROJECTION_FEED_STREAM_LAYOUT)"
SNAPMS="$(tpenv ORDER_VIEW_SNAPSHOT_MS)"
[[ "${MODE}" == snapshot && "${LAYOUT}" == partition && "${P}" -gt 1 && "${SNAPMS}" -gt 0 ]] \
  || fail "trade-processor is ${MODE:-rows}/${LAYOUT:-one}/P=${P:-1}/snapshot-ms=${SNAPMS:-0}; this proof needs production's snapshot, partition, P>1, snapshot-ms>0"
PA=$(( ACCT_A % P )); (( ACCT_B % P == PA )) || fail "ACCT_A and ACCT_B must share a partition (mod ${P})"
c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-snapshot-read-model' \
  -d "{\"accountId\":${ACCT_A},\"tickers\":\"${T}\",\"price\":${PX}}")"
[[ "${c}" == 200 ]] || fail "operator /seed ${T}: ${c} $(body)"
ok "snapshot read model, ${P} partition streams, order-view snapshot every ${SNAPMS} ms; accounts on partition ${PA}; seeded ${T}"

step "1. FR-DRS-32: one stream per partition"
lim=""; declare -a before
for ((p=0; p<P; p++)); do
  i="$(JS info "TRADERX_FEED_P${p}")"
  subj="$(jget 'sorted(d["config"]["subjects"])' <<<"${i}")"
  [[ "${subj}" == "[\"/orders.${p}\", \"/trades.${p}\"]" ]] || fail "TRADERX_FEED_P${p} captures ${subj:-nothing}, want /orders.${p} and /trades.${p} only"
  m="$(jget 'd["config"]["max_bytes"]' <<<"${i}")"; [[ -z "${lim}" || "${m}" == "${lim}" ]] || fail "partition streams' byte limits differ (${lim} vs ${m})"; lim="${m}"
  before[p]="$(JS count "TRADERX_FEED_P${p}" "/trades.${p}")"
done
S1="$(place "${ACCT_B}" Sell "${PX}")"; B1="$(place "${ACCT_A}" Buy "${PX}")"
for i in $(seq 1 20); do [[ "$(JS count "TRADERX_FEED_P${PA}" "/trades.${PA}")" -ge $(( before[PA] + 2 )) ]] && break; sleep 0.5; done
for ((p=0; p<P; p++)); do
  n="$(JS count "TRADERX_FEED_P${p}" "/trades.${p}")"; want=$(( before[p] + (p == PA ? 2 : 0) ))
  [[ "${n}" == "${want}" ]] || fail "TRADERX_FEED_P${p} /trades.${p}: ${before[p]} -> ${n}, want ${want} (the cross's two sides go to partition ${PA} only)"
done
ok "TRADERX_FEED_P0..$((P - 1)), each /orders.<p> + /trades.<p>, max_bytes ${lim}; the cross's 2 sides on P${PA} only"

step "2. FR-DRS-20: settlement covers exactly the committed sides"
for i in $(seq 1 30); do
  A="$(curl -s -m 10 "${TP}/v2/projection-feed/accounting")"; ST="$(curl -s -m 10 "${TP}/v2/projection-feed/settlement")"
  v="$(python3 -c '
import json, sys, datetime as dt
a, s = json.loads(sys.argv[1]), json.loads(sys.argv[2])
acc = {p: t["sides"] for p, t in a["trades"].items() if t["sides"]}
st = {p: x["sides"] for p, x in s["partitions"].items()}
tot, settled, unsettled = s["sides"], s["settled"], s["unsettled"]
bad = []
if st != acc: bad.append(f"per-partition sides {st} != accounting {acc}")
if tot != sum(acc.values()) or settled + unsettled != tot: bad.append(f"totals {tot}/{settled}/{unsettled} vs accounting {sum(acc.values())}")
for p, x in s["partitions"].items():
    for day, d in x["days"].items():
        t, n = dt.date.fromisoformat(day), s["tPlusDays"]
        while n:
            t += dt.timedelta(days=1); n -= t.weekday() < 5
        got = d["settlesOn"]
        if not got.startswith(t.isoformat()): bad.append(f"P{p} {day} settlesOn {got}, want {t}")
print("; ".join(bad) or "ok")' "${A}" "${ST}")"
  [[ "${v}" == ok ]] && break; sleep 1
done
[[ "${v}" == ok ]] || fail "settlement disagrees with the committed accounting (FR-DRS-20): ${v}"
ok "settlement sides $(jget 'd["sides"]' <<<"${ST}") = accounting, per partition; settlesOn = trade date + $(jget 'd["tPlusDays"]' <<<"${ST}") business days"

step "3. FR-DRS-30: an open order survives a restart through the committed snapshot"
L0="$(JS last "TRADERX_FEED_P${PA}")"   # R's order message is after this
R="$(place "${ACCT_A}" Buy $((PX - 1)))"; C="$(place "${ACCT_A}" Buy $((PX - 2)))"
row() { sql "SELECT orderid FROM order_view_snapshot WHERE orderid LIKE '%-$1' AND accountid=${ACCT_A}"; }
for i in $(seq 1 30); do [[ -n "$(row "${R}")" && -n "$(row "${C}")" ]] && break; sleep 1; done
RID="$(row "${R}")"; [[ -n "${RID}" ]] || fail "order ${R} rested ${i}s and is not in order_view_snapshot (snapshot every ${SNAPMS} ms)"
cur="$(sql "SELECT MAX(stream_seq) FROM projection_cursor WHERE projection_scope LIKE 'order-view:%' AND partition_id=${PA}")"
(( cur > L0 )) || fail "order-view cursor of P${PA} is ${cur}, not past ${L0}: R's message is not behind it, so a restart could replay R and the step would not test the snapshot"
code -X POST "${GW}/cancel" "${J[@]}" -d "{\"orderRef\":${C}}" >/dev/null
for i in $(seq 1 30); do [[ -z "$(row "${C}")" ]] && break; sleep 1; done
[[ -z "$(row "${C}")" ]] || fail "cancelled order ${C} is still in order_view_snapshot after ${i}s: a snapshot keeps only open orders"
ok "${RID} in order_view_snapshot (P${PA} order-view cursor ${cur} > ${L0}, past R's message); cancelled ${C}'s row deleted by a later snapshot"
"${K[@]}" scale deploy/trade-processor --replicas=0 >/dev/null; "${K[@]}" wait --for=delete pod -l app=trade-processor --timeout=120s >/dev/null 2>&1
if [[ "${NEGATIVE:-0}" == 1 ]]; then sql "DELETE FROM order_view_snapshot WHERE orderid='${RID}'"; echo "    NEGATIVE: deleted ${RID}'s snapshot row while trade-processor was down"; fi
"${K[@]}" scale deploy/trade-processor --replicas=1 >/dev/null
"${K[@]}" rollout status deploy/trade-processor --timeout=300s >/dev/null || fail "trade-processor did not come back"
pf TP "${TP_TARGET:-deploy/trade-processor}" 18091
for i in $(seq 1 60); do ids="$(open_ids "${ACCT_A}")"; [[ " ${ids} " == *" ${RID} "* ]] && break; sleep 2; done
[[ " ${ids} " == *" ${RID} "* ]] || fail "after the restart the read model's open orders on ${T} are '${ids}', not ${RID}: the committed snapshot was not loaded (FR-DRS-30)"
ok "restarted read model lists ${RID} open"
code -X POST "${GW}/cancel" "${J[@]}" -d "{\"orderRef\":${R}}" >/dev/null

echo; echo "YU18-SNAPSHOT-READ-MODEL PASS ticker=${T}"
