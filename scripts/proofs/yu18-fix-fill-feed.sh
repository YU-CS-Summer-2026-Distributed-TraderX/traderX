#!/usr/bin/env bash
# yu18-fix-fill-feed.sh — FIX fill reports off the durable feed (YU18 fix-fill-reports, ADR-084) and the
# configuration the session-persistence pack pins (fix-session-persistence, ADR-086), black-box on a live tier.
#
#   0. configuration, from the rig: every FIX gateway runs FIX_FILL_FEED=jetstream (FR-FFR-09) and pins
#      FIX_SESSION_RESET_TIME / FIX_SESSION_TIME_ZONE, and logs the schedule it runs (FR-GFS-08); the store
#      buckets are bounded as specified: FIX_SESSIONS max age 7 d, 1 GiB, discard old (FR-GFS-15); FIX_SESSION_LEASE max
#      age 3 s (FR-GFS-16)
#   1-4. yu18-fix-fill-feed.mjs: a stranger's cross sends nothing (FR-FFR-04); the gateway's /orders consumers
#      deleted mid-cross, the fill still reported exactly once (FR-FFR-07); partial then full fill with the
#      spec's CumQty/LeavesQty/LastQty/AvgPx (FR-FFR-02/03); distinct ExecIDs (FR-GFS-07)
#   5. effect end: the order read model holds the FIX Sell FILLED and the history store two trades on the ticker; the gateways' metrics count
#      exactly the two reports sent and no gap (FR-FFR-08)
#   6. FR-GFS-12 on REST: an unknown ticker answers its reject code, not 504, and moves no gateway's no-ack
#      streak (control: the same order on a known ticker is accepted)
#
# DESTRUCTIVE (deletes the gateways' feed consumers on TRADERX_FEED), disposable rigs.
# Usage: CTX=<ctx> NS=<ns> DESTRUCTIVE=1 [ACCT_F=22214 ACCT_R=42422 ACCT_X=10031 ACCT_Y=11413] bash scripts/proofs/yu18-fix-fill-feed.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
[[ "${DESTRUCTIVE:-0}" == 1 ]] || fail "DESTRUCTIVE=1 required: this deletes the gateways' TRADERX_FEED consumers"
ACCT_F="${ACCT_F:-22214}"; ACCT_R="${ACCT_R:-42422}"; ACCT_X="${ACCT_X:-10031}"; ACCT_Y="${ACCT_Y:-11413}"
T="$(mint_ticker)"; T2="$(mint_ticker)"; PX=50
GWS=($("${K[@]}" get pods -l app=cluster-gateway --field-selector=status.phase=Running -o name))
(( ${#GWS[@]} >= 1 )) || fail "no running cluster-gateway pod"
genv() { "${K[@]}" get deploy cluster-gateway -o jsonpath="{.spec.template.spec.containers[0].env[?(@.name==\"$1\")].value}"; }
gmetric() { # gmetric <outcome> -> sum over every gateway pod of traderx_fix_fill_reports_total{outcome=...}
  local p s=0 v
  for p in "${GWS[@]}"; do
    v="$("${K[@]}" exec "${p}" -- sh -c 'wget -qO- localhost:18110/metrics 2>/dev/null || curl -s localhost:18110/metrics' \
         | awk -v o="outcome=\"$1\"" 'index($0,"traderx_fix_fill_reports_total") && index($0,o) {print $NF}' | head -1)"
    s=$(python3 -c "print(int(float('${v:-0}')) + ${s})")
  done; echo "${s}"
}
streaks() { local p o=""; for p in "${GWS[@]}"; do o+="$("${K[@]}" exec "${p}" -- sh -c 'wget -qO- localhost:18110/live 2>/dev/null || curl -s localhost:18110/live' | jget 'd.get("noAckStreak")') "; done; echo "${o% }"; }

step "0. configuration the specs pin"
MODE="$(genv FIX_FILL_FEED)"
[[ "${MODE}" == jetstream || "${MODE}" == routed ]] || fail "FR-FFR-09: gateway FIX_FILL_FEED='${MODE}', want jetstream or routed"
if [[ "${MODE}" == routed ]]; then   # FR-FFR-14: every gateway names itself from its pod and reads only /fills.<pod>
  for p in "${GWS[@]}"; do
    "${K[@]}" logs "${p}" 2>/dev/null | grep -q "FIX fill feed: routed, JetStream ordered consumer on /fills.${p#pod/} of" \
      || fail "FR-FFR-14: ${p#pod/} did not subscribe /fills.${p#pod/} (routed): $("${K[@]}" logs "${p}" 2>/dev/null | grep -m1 'FIX fill feed' || echo 'no fill feed line')"
    "${K[@]}" logs "${p}" 2>/dev/null | grep -q 'FIX fill feed: JetStream ordered consumers' && fail "FR-FFR-14: ${p#pod/} also reads the capture stream"
  done
  ok "FR-FFR-14: ${#GWS[@]} gateway(s) read only their own routed subject"
fi
RT="$(genv FIX_SESSION_RESET_TIME)"; TZN="$(genv FIX_SESSION_TIME_ZONE)"
[[ -n "${RT}" && -n "${TZN}" ]] || fail "FR-GFS-08: the manifest does not pin FIX_SESSION_RESET_TIME ('${RT}') and FIX_SESSION_TIME_ZONE ('${TZN}')"
logs="$("${K[@]}" logs "${GWS[0]}" 2>/dev/null)"
# The schedule is logged as the UTC instant (QuickFIX/J: "daily, 21:00:00-UTC ... (daily, 17:00:00-EDT ...)"), so
# derive today's UTC equivalent of the pinned local time and match that, not a wording.
UTC_AT="$(python3 -c "import datetime as d,zoneinfo as z;t=d.datetime.combine(d.date.today(),d.time.fromisoformat('${RT}'),z.ZoneInfo('${TZN}'));print(t.astimezone(d.timezone.utc).strftime('%H:%M:%S'))")"
grep -q "daily, ${UTC_AT}-UTC" <<<"${logs}" || fail "FR-GFS-08: ${GWS[0]#pod/} does not run the FIX day to ${RT} ${TZN} (= ${UTC_AT} UTC today): $(grep -i 'schedule is' <<<"${logs}" | head -1 | cut -c1-200)"
ok "FR-FFR-09 jetstream; FR-GFS-08 pinned ${RT} ${TZN}, the gateway's schedule ends the day at ${UTC_AT} UTC"
pf NATSP "${NATS_TARGET:-svc/nats}" 4222 tcp
kv() { python3 "${here}/lib-jetstream.py" info "127.0.0.1:${NATSP}" "KV_$1" | jget '" ".join(str(d.get("config",{}).get(k)) for k in ("max_age","max_bytes","discard"))'; }
read -r age bytes disc <<<"$(kv FIX_SESSIONS)"
[[ "${age}" == $((7*86400*1000000000)) && "${bytes}" == $((1<<30)) && "${disc}" == old ]] || fail "FR-GFS-10: KV_FIX_SESSIONS max_age=${age} max_bytes=${bytes} discard=${disc}, want 7 d, 1 GiB, old (amended 2026-10-28, FR-GFS-15)"
read -r lage _ _ <<<"$(kv FIX_SESSION_LEASE)"
[[ "${lage}" == $((3*1000000000)) ]] || fail "FR-GFS-03/16: KV_FIX_SESSION_LEASE max_age=${lage}, want 3 s (renewed every 500 ms)"
ok "FR-GFS-10: FIX_SESSIONS 7 d / 1 GiB / discard old; FIX_SESSION_LEASE 3 s (FR-GFS-16)"

step "seed ${T} and ${T2} @${PX}"
# FR-FFR-14: in routed mode the cross is made ACROSS gateways when there are two: the FIX session on the first pod
# rests, the REST aggressor comes through the second, so the resting side's fill must be routed to the first pod.
if [[ "${MODE}" == routed && ${#GWS[@]} -ge 2 ]]; then
  pf GW "${GWS[1]}" "${GATEWAY_PORT:-18110}"; pf FIXP "${GWS[0]}" 18130 tcp
  ok "cross across gateways: FIX on ${GWS[0]#pod/}, REST on ${GWS[1]#pod/}"
else
  [[ "${MODE}" != routed ]] || echo "note: one gateway; the routed cross stays on it (bring the rig up with --gateways 2 for the cross-gateway case)"
  pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
  pf FIXP "${FIX_TARGET:-svc/order-matcher-gw-fix}" 18130 tcp
fi
PW="$(secret auth-secrets fix-session-password)"; [[ -n "${PW}" ]] || fail "auth-secrets has no fix-session-password"
for t in "${T}" "${T2}"; do
  c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-fix-fill-feed' -d "{\"accountId\":${ACCT_F},\"tickers\":\"${t}\",\"price\":${PX}}")"
  [[ "${c}" == 200 ]] || fail "operator /seed ${t}: ${c} $(body)"
done
SENT0="$(gmetric sent)"; GAP0="$(gmetric gap)"
ok "seeded; fill reports sent=${SENT0} gap=${GAP0} across ${#GWS[@]} gateway(s)"

step "1-4. the FIX session"
FIX_HOST=127.0.0.1 FIX_PORT="${FIXP}" FIX_PASSWORD="${PW}" TICKER="${T}" TICKER2="${T2}" PX="${PX}" GW="${GW}" \
  ACCT_F="${ACCT_F}" ACCT_R="${ACCT_R}" ACCT_X="${ACCT_X}" ACCT_Y="${ACCT_Y}" \
  BEARER_R="$(mint false "[${ACCT_R}]")" BEARER_X="$(mint false "[${ACCT_X}]")" BEARER_Y="$(mint false "[${ACCT_Y}]")" \
  NATS="127.0.0.1:${NATSP}" JS="${here}/lib-jetstream.py" FILL_MODE="${MODE}" FILL_GW="${GWS[0]#pod/}" node "${here}/yu18-fix-fill-feed.mjs"; rc=$?
(( rc == 0 )) || fail "the FIX session failed (above)"

step "5. effect end"
# The order's status is the order read model's (ADR-097: production's shape persists no order row in SQL,
# ORDER_FEED_PERSIST=false); the trades are the history store's (ADR-102).
pf TP "${TP_TARGET:-svc/trade-processor}" 18091
ostat() { curl -s -m 10 "${TP}/accounts/${ACCT_F}/orders?status=all" | jget "next((o.get('status') for o in d if o.get('security') == '${T}'), '')"; }
for i in $(seq 1 30); do [[ "$(ostat)" == FILLED ]] && break; sleep 1; done
st="$(ostat)"; nt="$(hist "SELECT count() FROM trades WHERE security='${T}'")"
[[ "${st}" == FILLED && "${nt}" == 4 ]] || fail "effect end: FIX Sell '${st}' in the order read model, ${nt} trade rows on ${T} (want FILLED, 4 = two crosses x two sides)"
SENT1="$(gmetric sent)"; GAP1="$(gmetric gap)"
[[ $(( SENT1 - SENT0 )) == 2 && "${GAP1}" == "${GAP0}" ]] || fail "FR-FFR-08: reports sent ${SENT0} -> ${SENT1} (want +2), gap ${GAP0} -> ${GAP1} (want +0)"
ok "FIX Sell FILLED, 4 trade rows; metrics sent +2, gap +0"
if [[ "${MODE}" == routed ]]; then   # FR-DRS-48: the read model routed the cross's events to the gateways by name
  fills="$(curl -s -m 10 "${TP}/v2/projection-feed" | jget 'json.dumps(d.get("orders",{}).get("fills"))')"
  [[ "${fills}" != null && -n "${fills}" ]] || fail "FR-DRS-48: trade-processor reports no fills router: ${fills}"
  routed="$(jget 'd.get("routed",0)' <<<"${fills}")"; acked="$(jget 'd.get("acked",0)' <<<"${fills}")"; failed="$(jget 'd.get("failed",0)' <<<"${fills}")"
  (( routed >= 2 && failed == 0 )) || fail "FR-DRS-48: fills routed=${routed} acked=${acked} failed=${failed}: ${fills}"
  grep -q "\"${GWS[0]#pod/}\"" <<<"${fills}" || fail "FR-DRS-48: nothing routed to ${GWS[0]#pod/}: ${fills}"
  ok "FR-DRS-48: fills routed=${routed} acked=${acked} failed=0 to $(jget 'list(d.get("gateways",{}).keys())' <<<"${fills}")"
fi

step "6. FR-GFS-12 on REST: an unknown ticker is refused by code and moves no no-ack streak"
S0="$(streaks)"; B="$(mint false "[${ACCT_F}]")"
for i in 1 2 3 4 5; do
  c="$(code -X POST "${GW}/orders" -H "Authorization: Bearer ${B}" -H 'Content-Type: application/json' -d "{\"accountId\":${ACCT_F},\"ticker\":\"ZQ${RANDOM}${i}\",\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":${PX}}")"
  [[ "${c}" != 504 && "$(body)" == *UNKNOWN_SECURITY* ]] || fail "FR-GFS-12: an unknown ticker answered ${c} $(body | head -c 160) (want its reject code, not 504)"
done
S1="$(streaks)"; [[ "${S1}" == "${S0}" ]] || fail "FR-GFS-12: five unknown-ticker orders moved the no-ack streak ${S0} -> ${S1}"
c="$(code -X POST "${GW}/orders" -H "Authorization: Bearer ${B}" -H 'Content-Type: application/json' -d "{\"accountId\":${ACCT_F},\"ticker\":\"${T2}\",\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":$((PX - 1))}")"
[[ "$(body)" == *'"kind":1'* ]] || fail "control: a known ticker was not accepted: ${c} $(body | head -c 160)"
ok "5 x UNKNOWN_SECURITY (${c} for the control), noAckStreak ${S0} unchanged; control accepted"
echo "[PASS] fix fill feed: config pinned, stranger silent, consumers deleted and the fill reported once, metrics exact"
