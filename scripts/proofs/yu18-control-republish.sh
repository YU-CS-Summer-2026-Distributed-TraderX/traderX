#!/usr/bin/env bash
# yu18-control-republish.sh — the control stream refills itself from the outbox (YU18 control-feed-republish,
# FR-CFR-01/02/04, ADR-085) on the live tier. reference-data audits its `stocks_control_outbox` against
# `TRADERX_CONTROL_SECURITY` on start and every SECURITY_OUTBOX_AUDIT_INTERVAL_MS (default 30 s).
#
#   1. FR-CFR-04 control: the stream holds every published row (messages = published rows, last version =
#      the highest published version) and, over two audit intervals, nothing is republished
#   2. FR-CFR-02 "fewer messages": the stream is PURGED; within two intervals it again holds every published
#      row, in version order, and every outbox row is published
#   3. FR-CFR-02 "missing": the stream is DELETED; within two intervals it exists again, complete
#   4. effect end: the gateway, restarted so it replays the stream from its start (FR-GMR-07), accepts an
#      order on a ticker from the universe, and refuses an unregistered one UNKNOWN_SECURITY (control)
#
# DESTRUCTIVE (empties a stream every gateway replays), disposable rigs.
# Usage: CTX=<ctx> NS=<ns> DESTRUCTIVE=1 [ACCT=22214 TICKER=IBM] bash scripts/proofs/yu18-control-republish.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
[[ "${DESTRUCTIVE:-0}" == 1 ]] || fail "DESTRUCTIVE=1 required: this empties and deletes TRADERX_CONTROL_SECURITY"
ACCT="${ACCT:-22214}"; TICKER="${TICKER:-IBM}"; ST=TRADERX_CONTROL_SECURITY
IV_MS="$("${K[@]}" get deploy reference-data -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="SECURITY_OUTBOX_AUDIT_INTERVAL_MS")].value}')"
IV=$(( ${IV_MS:-30000} / 1000 )); WAIT=$(( IV * 2 + 30 ))   # two audits plus the poller's republish
pf NATSP "${NATS_TARGET:-svc/nats}" 4222 tcp
js() { python3 "${here}/lib-jetstream.py" "$1" "127.0.0.1:${NATSP}" "${ST}" "${@:2}"; }
state() { # -> "<messages> <last version>" or "missing"
  local info msgs ls last
  info="$(js info)"; msgs="$(jget 'd.get("state",{}).get("messages","") if "error" not in d else ""' <<<"${info}")"
  [[ -n "${msgs}" ]] || { echo missing; return; }
  ls="$(jget 'd["state"]["last_seq"]' <<<"${info}")"
  # read from one before the last: a consumer started exactly at last_seq reports nothing pending
  (( msgs > 0 )) && last="$(js read traderx.control.security.deltas $(( ls > 1 ? ls - 1 : 1 )) | grep '"seq"' | tail -1 | jget 'd.get("data",{}).get("version","")')"
  echo "${msgs} ${last:-none}"
}
outbox() { sql "SELECT SUM(published=1), IFNULL(MAX(CASE WHEN published=1 THEN version END),0), SUM(published=0) FROM stocks_control_outbox"; }
# FR-CFR-02's own test: complete = at least as many messages as published rows, the last at the highest
# published version, and no row left unpublished. (Versions have gaps: rows can outnumber nothing else.)
complete() { local s m l; s="$(state)"; read -r P V U <<<"$(outbox)"; read -r m l <<<"${s}"; [[ "${s}" != missing && "${m}" -ge "${P}" && "${l}" == "${V}" && "${U:-0}" == 0 ]]; }

step "1. FR-CFR-04: a complete stream is left alone (audit interval ${IV}s)"
read -r P V U <<<"$(outbox)"
complete || fail "precondition: stream $(state) vs outbox rows=${P} max version=${V} unpublished=${U}"
S0="$(js info | jget 'd["state"]["last_seq"]')"
sleep $(( IV * 2 + 5 ))
S1="$(js info | jget 'd["state"]["last_seq"]')"
[[ "${S1}" == "${S0}" ]] && complete || fail "FR-CFR-04: a complete stream changed over two audits (last_seq ${S0} -> ${S1}, $(state))"
ok "complete (${P} messages, last version ${V}); last_seq ${S0} unchanged over two audits"

for arm in purge delete; do
  step "$([[ ${arm} == purge ]] && echo 2 || echo 3). FR-CFR-02: the stream is ${arm}d"
  js "${arm}" >/dev/null
  [[ "$(state)" == "$([[ ${arm} == purge ]] && echo '0 none' || echo missing)" ]] || fail "the ${arm} did not take: $(state)"
  t0=$(date +%s); until complete; do (( $(date +%s) - t0 < WAIT )) || fail "FR-CFR-02: ${WAIT}s after the ${arm} the stream is $(state); outbox $(outbox)"; sleep 3; done
  vs="$(js read traderx.control.security.deltas 1 | jget 'd.get("data",{}).get("version","")' 2>/dev/null | grep -v '^$')"
  sort -n -c <<<"${vs}" 2>/dev/null || fail "FR-CFR-02: republished versions are not in version order"
  [[ "$(state | cut -d' ' -f1)" == "${P}" ]] || fail "FR-CFR-02: after the ${arm} the stream holds $(state | cut -d' ' -f1) messages, want the ${P} published rows"
  ok "refilled in $(( $(date +%s) - t0 ))s: $(state | cut -d' ' -f1) messages, last version $(state | cut -d' ' -f2), in version order, no row unpublished"
done

step "4. effect end: a restarted gateway replays the refilled stream"
"${K[@]}" rollout restart deploy/cluster-gateway >/dev/null && "${K[@]}" rollout status deploy/cluster-gateway --timeout=300s >/dev/null || fail "gateway roll"
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
B="$(mint false "[${ACCT}]")"
for i in $(seq 1 30); do [[ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' "${GW}/ready")" == 200 ]] && break; sleep 2; done
c="$(code -X POST "${GW}/orders" -H "Authorization: Bearer ${B}" -H 'Content-Type: application/json' -d "{\"accountId\":${ACCT},\"ticker\":\"${TICKER}\",\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":1}")"
[[ "$(body)" != *UNKNOWN_SECURITY* && "${c}" != 000 ]] || fail "after the republish + gateway restart ${TICKER} is unknown: ${c} $(body | head -c 160)"
ok "${TICKER} known to the restarted gateway (${c} $(body | head -c 80))"
c="$(code -X POST "${GW}/orders" -H "Authorization: Bearer ${B}" -H 'Content-Type: application/json' -d "{\"accountId\":${ACCT},\"ticker\":\"ZQ$(date +%s)\",\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":1}")"
[[ "$(body)" == *UNKNOWN_SECURITY* ]] || fail "control: an unregistered ticker was not refused UNKNOWN_SECURITY: ${c} $(body | head -c 160)"
ok "control: an unregistered ticker refused UNKNOWN_SECURITY"
echo "[PASS] control stream republish: complete stream untouched; purged and deleted streams refilled from the outbox"
