#!/usr/bin/env bash
# yu18-extract-off-projection.sh — ADR-079 §5: the EOD extract is sourced from the cluster, not from
# the SQL projection, and it triggers on eod.prices.ready, not on the P&L's eod.pnl.done.
#
#   1. a cross on a fresh ticker is booked while trade-processor is scaled to 0, so on the core feed
#      the SQL projection never sees it (step 1 CHECKS that SQL lacks it: the control that makes
#      step 4 mean something; on a jetstream tier the stream would replay it and the proof says so)
#   2. position-service is held at 0 for the rest of the run, so eod.pnl.done cannot be emitted
#   3. the session is closed (flagged marks are overridden at their own observed close, then
#      published, exactly as yu15-risk-extract does); the extract must still announce
#      RISK-EXTRACT-READY
#   4. the delivered positions file carries the unprojected cross: +5 for A and -5 for B on the
#      ticker, rows SQL does not have
#   5. FR-EB13 (when RISK_EXTRACT_READY_DIRECTORY is set on the producer): a write-once
#      <...>.ready.json receipt exists whose bytes are the announced payload plus one LF
#
# Leaves the cross unprojected on its minted ticker (core feed) and restores both Deployments.
# Usage: CTX=<ctx> NS=<ns> [ACCT_A=22214 ACCT_B=42422] bash scripts/proofs/yu18-extract-off-projection.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
ACCT_A="${ACCT_A:-22214}"; ACCT_B="${ACCT_B:-42422}"; T="$(mint_ticker)"; PX=40
TPDEP="${TP_TARGET:-deploy/trade-processor}"; PSDEP="${PS_TARGET:-deploy/position-service}"
restore() { "${K[@]}" scale "${TPDEP}" "${PSDEP}" --replicas=1 >/dev/null 2>&1; }
trap 'restore; _lib_yu18_cleanup' EXIT
XPOD="$("${K[@]}" get pod -l app=risk-extract -o jsonpath='{.items[0].metadata.name}')"; [[ -n "${XPOD}" ]] || fail "no risk-extract pod"
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
pf TP "${TPDEP}" 18091
ADMIN="$(mint true '[]')"; [[ -n "${ADMIN}" ]] || fail "could not mint an admin bearer"
J=(-H 'Content-Type: application/json' -H "Authorization: Bearer ${ADMIN}")
place() { local c; c="$(code -X POST "${GW}/orders" "${J[@]}" -d "{\"accountId\":$1,\"ticker\":\"${T}\",\"side\":\"$2\",\"quantity\":5,\"limitPrice\":$3}")"
  [[ "${c}" == 200 && "$(body)" != *'"kind":2'* ]] || fail "$2 $1 @$3: ${c} $(body)"; }
ready_count() { local l; l="$("${K[@]}" logs "${XPOD}" --tail=-1 2>/dev/null)"; grep -c 'RISK-EXTRACT-READY' <<<"${l}"; }

step "1. a cross the projection never sees"
c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-extract-off-projection' \
  -d "{\"accountId\":${ACCT_A},\"tickers\":\"${T}\",\"price\":${PX}}")"; [[ "${c}" == 200 ]] || fail "seed ${T}: ${c}"
MODE="$(curl -s -m 10 "${TP}/v2/projection-feed" | jget 'd["trades"]["mode"]')"
"${K[@]}" scale "${TPDEP}" --replicas=0 >/dev/null; "${K[@]}" wait --for=delete pod -l app=trade-processor --timeout=120s >/dev/null 2>&1
place "${ACCT_B}" Sell "${PX}"; place "${ACCT_A}" Buy "${PX}"
"${K[@]}" scale "${TPDEP}" --replicas=1 >/dev/null; "${K[@]}" rollout status "${TPDEP}" --timeout=300s >/dev/null
_lib_yu18_cleanup; PF_PIDS=(); pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"; pf TP "${TPDEP}" 18091
sleep 10
n="$(sql "SELECT COUNT(*) FROM positions WHERE security='${T}' AND quantity<>0")"
[[ "${n}" == 0 ]] || { [[ "${MODE}" == jetstream ]] && echo "    (jetstream feed replayed the cross into SQL; step 4 still checks the extract)" || fail "SQL has ${n} position row(s) for ${T} on the ${MODE} feed: the outage did not hide the cross, so this run cannot tell the sources apart"; }
ok "cross booked with trade-processor down; SQL position rows for ${T}: ${n} (feed ${MODE})"

step "2. hold position-service at 0: no eod.pnl.done this run"
"${K[@]}" scale "${PSDEP}" --replicas=0 >/dev/null; "${K[@]}" wait --for=delete pod -l app=position-service --timeout=120s >/dev/null 2>&1
[[ -z "$("${K[@]}" get pods -l app=position-service -o name 2>/dev/null)" ]] || fail "position-service still has pods"
ok "position-service scaled to 0"

step "3. close the session; the extract must still fire"
B0="$(ready_count)"
CLOSE="$(curl -s -m 60 -X POST "${TP}/eod/session/close" -H "Authorization: Bearer ${ADMIN}")"
STATUS="$(jget 'd.get("status")' <<<"${CLOSE}")"; SD="$(jget 'd.get("sessionDate")' <<<"${CLOSE}")"
[[ -n "${STATUS}" ]] || fail "/eod/session/close: ${CLOSE:0:200}"
if [[ "${STATUS}" != PUBLISHED ]]; then
  jget "'\n'.join('%s\t%s' % (p['security'], p.get('closingPrice')) for p in d['instruments'] if p.get('flagged'))" <<<"${CLOSE}" \
  | while IFS=$'\t' read -r sec px; do
      [[ -n "${sec}" && "${px}" != None ]] || continue
      curl -s -m 20 -o /dev/null -X POST "${TP}/eod/prices/${SD}/override" "${J[@]}" \
        -d "{\"security\":\"${sec}\",\"price\":${px},\"reason\":\"yu18-extract-off-projection: accepted the observed mark\"}"
    done
  CLOSE="$(curl -s -m 60 -X POST "${TP}/eod/prices/${SD}/publish" -H "Authorization: Bearer ${ADMIN}")"
  STATUS="$(jget 'd.get("status")' <<<"${CLOSE}")"
fi
[[ "${STATUS}" == PUBLISHED ]] || fail "session ${SD} did not publish: ${CLOSE:0:300}"
for i in $(seq 1 90); do [[ "$(ready_count)" -gt "${B0}" ]] && break; sleep 2; done
[[ "$(ready_count)" -gt "${B0}" ]] || { "${K[@]}" logs "${XPOD}" --tail=20 >&2; fail "no RISK-EXTRACT-READY with position-service down: the extract still waits on eod.pnl.done"; }
READY="$("${K[@]}" logs "${XPOD}" --tail=-1 | grep 'RISK-EXTRACT-READY' | tail -1 | sed 's/^.*RISK-EXTRACT-READY //')"
ok "session ${SD} published; RISK-EXTRACT-READY with position-service at 0"

step "4. the extract carries the cross SQL never saw"
URI="$(jget 'd.get("uri")' <<<"${READY}")"; [[ "${URI}" == file://* ]] || fail "this proof reads a file:// sink; ready names '${URI}'"
CSV="$("${K[@]}" exec "${XPOD}" -- cat "${URI#file://}" 2>/dev/null)"; [[ -n "${CSV}" ]] || fail "cannot read ${URI} in ${XPOD}"
got="$(python3 -c "
import sys,csv,io
rows=[l for l in sys.stdin.read().splitlines() if l and not l.startswith('#')]
r=list(csv.DictReader(io.StringIO('\n'.join(rows))))
q={row.get('accountId'):row.get('quantity') for row in r if row.get('security')=='${T}'}
print(q.get('${ACCT_A}','-'), q.get('${ACCT_B}','-'))" <<<"${CSV}")"
read -r qa qb <<<"${got}"
[[ "${qa%%.*}" == 5 && "${qb%%.*}" == -5 ]] || fail "extract rows for ${T}: ${ACCT_A}=${qa} ${ACCT_B}=${qb} (want 5 / -5); header: $(grep -v '^#' <<<"${CSV}" | head -1)"
ok "extract ${URI##*/}: ${T} ${ACCT_A}=+5 ${ACCT_B}=-5, positions SQL does not hold"

step "5. FR-EB13: the private completion receipt"
RDIR="$("${K[@]}" get pod "${XPOD}" -o json | jget "next((e.get('value') for c in d['spec']['containers'] for e in c.get('env',[]) if e['name']=='RISK_EXTRACT_READY_DIRECTORY'),'')")"
if [[ -z "${RDIR}" ]]; then echo "[skip] RISK_EXTRACT_READY_DIRECTORY is not set on the producer"
else
  SEQ="$(jget 'd.get("consensusSequence")' <<<"${READY}")"
  hit="$("${K[@]}" exec "${XPOD}" -- sh -c "grep -l '\"consensusSequence\":${SEQ}[,}]' ${RDIR}/*.ready.json 2>/dev/null | head -1")"
  [[ -n "${hit}" ]] || fail "no .ready.json in ${RDIR} names consensusSequence ${SEQ}"
  python3 - "${READY}" "$("${K[@]}" exec "${XPOD}" -- cat "${hit}" | base64)" <<'PY' || fail "receipt ${hit} is not the announced payload + LF"
import sys,base64,json
ann=sys.argv[1]; rec=base64.b64decode(sys.argv[2])
assert rec.endswith(b'\n') and not rec.endswith(b'\n\n'), 'receipt must end in exactly one LF'
assert json.loads(rec)==json.loads(ann), 'receipt JSON differs from the announcement'
PY
  ok "receipt ${hit##*/} = the announced payload (seq ${SEQ}) + LF"
fi

echo; echo "YU18-EXTRACT-OFF-PROJECTION PASS session=${SD} ticker=${T}"
