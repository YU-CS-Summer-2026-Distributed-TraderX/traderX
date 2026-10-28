#!/usr/bin/env bash
# yu18-jetstream-feed.sh — ADR-080's opt-in read side, end to end, with a crash in the middle:
#
#   FR-DRS-05  PROJECTION_FEED_MODE=jetstream: the file-backed stream TRADERX_FEED exists with the
#              /trades and /orders subjects and a durable consumer; /v2/projection-feed says jetstream.
#   SC-DRS-01  N crossing pairs, then trade-processor is SIGKILLed (force delete, no grace) while the
#   SC-DRS-02  feed is still draining or before its first snapshot. After the restart every trade side
#              the ENGINE booked is in SQL positions: A = +N and B = -N exactly (the engine's own
#              trade counter is the independent count).
#   FR-DRS-08  TRADE_PROJECTION_MODE=snapshot writes projection_cursor rows whose stream_seq advances.
#   FR-DRS-07  ORDER_FEED_PERSIST=false: a resting order is served by GET /accounts/{id}/orders while
#              SQL `orderbook` has NO row for it (the negative control: the answer is the view, not
#              SQL); it survives a trade-processor restart (rebuilt from the stream) and a cancel
#              moves it to CANCELED.
#
# The three env vars are set on deploy/trade-processor for the run and REMOVED on every exit path
# (the defaults, NFR-DRS-01). Rolls trade-processor three times; kills it once.
#
# Usage: CTX=<ctx> NS=<ns> [N=3000 ACCT_A=22214 ACCT_B=42422] bash scripts/proofs/yu18-jetstream-feed.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
ACCT_A="${ACCT_A:-22214}"; ACCT_B="${ACCT_B:-42422}"; N="${N:-3000}"
T="$(mint_ticker)"; T2="$(mint_ticker)"; PX=10
TPDEP="${TP_TARGET:-deploy/trade-processor}"
ENVS=(PROJECTION_FEED_MODE TRADE_PROJECTION_MODE ORDER_FEED_PERSIST)
# Restore what the tier had, not "unset": since FR-DRS-25 the production render itself sets
# PROJECTION_FEED_MODE=jetstream, and removing it would leave trade-processor on the core feed.
ORIG=()
for e in "${ENVS[@]}"; do
  v="$("${K[@]}" get "${TPDEP}" -o "jsonpath={.spec.template.spec.containers[0].env[?(@.name==\"${e}\")].value}" 2>/dev/null)"
  if [[ -n "${v}" ]]; then ORIG+=("${e}=${v}"); else ORIG+=("${e}-"); fi
done
restore() {
  "${K[@]}" set env "${TPDEP}" "${ORIG[@]}" >/dev/null 2>&1
  "${K[@]}" rollout status "${TPDEP}" --timeout=300s >/dev/null 2>&1
}
trap 'restore; _lib_yu18_cleanup' EXIT
reforward() { _lib_yu18_cleanup; PF_PIDS=(); TP=""; pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"; pf TP "${TPDEP}" 18091; }
tp_ready() { "${K[@]}" rollout status "${TPDEP}" --timeout=300s >/dev/null || fail "trade-processor did not become ready"
  local i; for i in $(seq 1 60); do "${K[@]}" get pods -l app=trade-processor --no-headers 2>/dev/null | grep -qvE '1/1 +Running' || break; sleep 2; done; }
feed() { curl -s -m 10 "${TP}/v2/projection-feed"; }
trades_leader() { local m r; for ((m=0; m<MEMBERS; m++)); do r="$(mget "${m}" /health)"; [[ "$(jget 'd.get("role")' <<<"${r}")" == LEADER ]] && { jget 'd.get("trades")' <<<"${r}"; return; }; done; }
pos() { sql "SELECT COALESCE(SUM(quantity),0) FROM positions WHERE security='$1' AND accountid=$2"; }
batch() { # batch <acct> <side> <n> -> accepted count
  python3 -c "import sys,json; print(json.dumps([{'accountId':$1,'ticker':'${T}','side':'$2','quantity':1,'limitPrice':${PX}}]*$3))" > "${BODY_FILE}.req"
  curl -s -m 120 -X POST "${GW}/orders/batch" -H 'Content-Type: application/json' -H "Authorization: Bearer ${ADMIN}" --data-binary @"${BODY_FILE}.req" | jget 'd.get("accepted")'
}
orders_view() { curl -s -m 10 "${TP}/accounts/$1/orders${2:+?status=all}" -H "Authorization: Bearer ${ADMIN}"; }
# OrderRow ids are epoch-qualified ("<epoch>-<orderRef>").
status_of() { jget "next((o.get('status') for o in d if str(o.get('id','')).split('-')[-1]=='$1'),'')"; }

step "0. switch trade-processor to jetstream + snapshot + in-memory order view"
"${K[@]}" set env "${TPDEP}" PROJECTION_FEED_MODE=jetstream TRADE_PROJECTION_MODE=snapshot ORDER_FEED_PERSIST=false >/dev/null || fail "set env failed"
tp_ready; reforward
ADMIN="$(mint true '[]')"; [[ -n "${ADMIN}" ]] || fail "could not mint an admin bearer"
F="$(feed)"
[[ "$(jget 'd["trades"]["mode"]' <<<"${F}")" == jetstream && "$(jget 'd["trades"].get("projection")' <<<"${F}")" == snapshot \
   && "$(jget 'd["orders"].get("persist")' <<<"${F}")" == False ]] || fail "projection-feed does not report jetstream/snapshot/persist=false: ${F}"
ok "projection-feed: ${F}"

step "FR-DRS-05. the capture stream"
pf NATSMON "${NATS_TARGET:-deploy/nats}" 8222   # the monitor port is on the pod, not the Service
JS="$(curl -s -m 10 "${NATSMON}/jsz?streams=1&consumers=1&config=1")"
STREAM="${PROJECTION_FEED_STREAM:-TRADERX_FEED}"
jget "[s for a in d.get('account_details',[]) for s in a.get('stream_detail',[]) if s['name']=='${STREAM}'][0]" <<<"${JS}" > "${BODY_FILE}.s"
[[ -s "${BODY_FILE}.s" && "$(cat "${BODY_FILE}.s")" != "" ]] || fail "stream ${STREAM} not found in /jsz"
subj="$(jget "','.join(d['config']['subjects'])" < "${BODY_FILE}.s")"; stor="$(jget "d['config']['storage']" < "${BODY_FILE}.s")"
ncons="$(jget "len(d.get('consumer_detail',[]))" < "${BODY_FILE}.s")"
[[ ",${subj}," == *",/trades,"* && ",${subj}," == *",/orders,"* ]] || fail "${STREAM} subjects ${subj} lack /trades or /orders"
[[ "${stor}" == file ]] || fail "${STREAM} storage is ${stor}, not file"
(( ncons >= 1 )) || fail "${STREAM} has no durable consumer"
ok "${STREAM}: subjects ${subj}; storage ${stor}; ${ncons} consumer(s)"

step "SC-DRS-01/02. ${N} crossing pairs, then SIGKILL trade-processor mid-drain"
for t in "${T}" "${T2}"; do
  c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-jetstream-feed' \
    -d "{\"accountId\":${ACCT_A},\"tickers\":\"${t}\",\"price\":${PX}}")"
  [[ "${c}" == 200 ]] || fail "operator /seed ${t}: ${c} $(body)"
done
L0="$(trades_leader)"; C0="$(sql "SELECT COALESCE(MAX(stream_seq),-1) FROM projection_cursor")"
s="$(batch "${ACCT_B}" Sell "${N}")"; [[ "${s}" == "${N}" ]] || fail "sell batch accepted ${s} of ${N}"
s="$(batch "${ACCT_A}" Buy "${N}")"; [[ "${s}" == "${N}" ]] || fail "buy batch accepted ${s} of ${N}"
before="$(feed)"; POD="$("${K[@]}" get pods -l app=trade-processor -o jsonpath='{.items[0].metadata.name}')"
"${K[@]}" delete pod "${POD}" --grace-period=0 --force >/dev/null 2>&1
echo "    killed ${POD}; feed at the kill: $(jget 'd["trades"]' <<<"${before}")"
for i in $(seq 1 60); do L1="$(trades_leader)"; (( L1 - L0 >= 2 * N )) && break; sleep 1; done
(( L1 - L0 == 2 * N )) || fail "engine booked $(( L1 - L0 )) trade sides, want $((2 * N)) (another writer, or the cross did not happen)"
tp_ready; reforward
for i in $(seq 1 90); do [[ "$(pos "${T}" "${ACCT_A}")" == "${N}" && "$(pos "${T}" "${ACCT_B}")" == "-${N}" ]] && break; sleep 2; done
pa="$(pos "${T}" "${ACCT_A}")"; pb="$(pos "${T}" "${ACCT_B}")"
[[ "${pa}" == "${N}" && "${pb}" == "-${N}" ]] || fail "after kill -9 + replay: positions ${ACCT_A}=${pa} ${ACCT_B}=${pb}, want +${N}/-${N} (engine booked $((2 * N)) sides)"
ok "engine $((2 * N)) sides; SQL positions ${ACCT_A}=+${pa} ${ACCT_B}=${pb} after the SIGKILL and replay"

step "FR-DRS-08. the snapshot cursor"
C1="$(sql "SELECT COALESCE(MAX(stream_seq),-1) FROM projection_cursor")"
rows="$(sql "SELECT projection_scope, partition_id, stream_seq, consensus_seq, snapshot_at FROM projection_cursor")"
(( C1 > C0 )) || fail "projection_cursor.stream_seq did not advance (${C0} -> ${C1}): ${rows}"
ok "stream_seq ${C0} -> ${C1}; rows: ${rows//$'\n'/; }"
F="$(feed)"; for k in dropped missingTradeSides; do [[ "$(jget "d['trades']['${k}']" <<<"${F}")" == 0 ]] || fail "trades.${k} non-zero after replay: ${F}"; done
ok "dropped 0, missingTradeSides 0 after the replay"

step "FR-DRS-07. a resting order lives in the view, not in SQL, and survives a restart"
c="$(code -X POST "${GW}/orders" -H 'Content-Type: application/json' -H "Authorization: Bearer ${ADMIN}" \
  -d "{\"accountId\":${ACCT_A},\"ticker\":\"${T2}\",\"side\":\"Buy\",\"quantity\":3,\"limitPrice\":$((PX - 1))}")"
[[ "${c}" == 200 && "$(body)" != *'"kind":2'* ]] || fail "resting order refused: ${c} $(body)"
REF="$(body | jget 'd.get("orderRef")')"
for i in $(seq 1 30); do st="$(orders_view "${ACCT_A}" | status_of "${REF}")"; [[ -n "${st}" ]] && break; sleep 1; done
[[ "${st}" == NEW ]] || fail "order ${REF} not NEW in GET /accounts/${ACCT_A}/orders (got '${st}'): $(orders_view "${ACCT_A}" | head -c 300)"
n="$(sql "SELECT COUNT(*) FROM orderbook WHERE security='${T2}'")"
[[ "${n}" == 0 ]] || fail "ORDER_FEED_PERSIST=false but orderbook holds ${n} row(s) for ${T2}"
ok "ref ${REF} NEW in the view; orderbook has 0 rows for ${T2}"
"${K[@]}" rollout restart "${TPDEP}" >/dev/null; tp_ready; reforward
for i in $(seq 1 30); do st="$(orders_view "${ACCT_A}" | status_of "${REF}")"; [[ -n "${st}" ]] && break; sleep 1; done
[[ "${st}" == NEW ]] || fail "after a restart order ${REF} is '${st}' in the view (want NEW, rebuilt from ORDER_VIEW_REPLAY_SECONDS of stream)"
ok "ref ${REF} still NEW after the restart"
c="$(code -X POST "${GW}/cancel" -H 'Content-Type: application/json' -H "Authorization: Bearer ${ADMIN}" -d "{\"orderRef\":${REF}}")"
[[ "${c}" == 200 ]] || fail "cancel ${REF}: ${c} $(body)"
for i in $(seq 1 30); do st="$(orders_view "${ACCT_A}" all | status_of "${REF}")"; [[ "${st}" == CANCELED ]] && break; sleep 1; done
[[ "${st}" == CANCELED ]] || fail "cancelled ${REF} reads '${st}' in ?status=all"
[[ -z "$(orders_view "${ACCT_A}" | status_of "${REF}")" ]] || fail "cancelled ${REF} still in the open set"
ok "cancel -> CANCELED in ?status=all and gone from the open set"

echo; echo "YU18-JETSTREAM-FEED PASS N=${N} ticker=${T} order=${REF} (trade-processor restored to its own env on exit)"
