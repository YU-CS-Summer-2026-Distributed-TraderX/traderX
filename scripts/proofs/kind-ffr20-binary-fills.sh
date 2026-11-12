#!/usr/bin/env bash
# kind-ffr20-binary-fills.sh — FR-FFR-20 on kind: a sequenced binary order (ADR-090, binary-load's default) that fills
# is not routed to its gateway's fills subject and is never counted lost there; an unsequenced one (--unsequenced, the
# negative control) still is. Black-box, readings before and after; the verdict is the caller's (exit 1 = a reading
# contradicted MODE's expectation).
#
#   0. business day; accounts 9900010-17 (binary-load's) admitted and in SQL accounts (kind-binary-load-needs-sql-accounts),
#      22214 / 42422 (the trigger's) admitted; fresh tickers seeded @ PX for the load and the trigger
#   1. reading "before": per gateway traderx_fix_fill_reports_total{sent,gap,lost,evicted}; trade-processor's
#      orders.fills (routed/messages/acked/failed/gateways); TRADERX_FILLS messages; the members' trades
#   2. binary-load.mjs (MODE=sequenced: default frames; unsequenced: --unsequenced), one Job per gateway pod, dialled by
#      pod IP so both gateways carry binary sessions; reading "after-load" once the router and the members stop moving
#   3. wait AGE_WAIT (> FR-FFR-15's 30 s) past the load's end, then the trigger: one REST cross per gateway through that
#      pod (a sell rests, a buy takes it), whose untracked fills enter the gateway's early buffer and run expireEarly,
#      the only place an aged entry is dropped and counted lost; reading "after-trigger"
#   Expect, sequenced: the router's count for each gateway does not move with the load; lost does not move through
#   the trigger; the members' trades rise by the load. Unsequenced: the router's count rises with the load, and lost
#   rises through the trigger.
#
#   CTX=kind-<c> NS=traderx MODE=sequenced|unsequenced EVIDENCE=<dir> [SESSIONS=2 RATE=20 SECS=20 PX=50 AGE_WAIT=35] \
#     bash scripts/proofs/kind-ffr20-binary-fills.sh
here="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "${here}/../.." && pwd)"
. "${here}/lib-yu18.sh"
disposable_rig || fail "disposable rig only (kind): this runs one-off load pods"
MODE="${MODE:?MODE=sequenced|unsequenced}"; EV="${EVIDENCE:?EVIDENCE=<dir>}/${MODE}"; mkdir -p "${EV}"
SESSIONS="${SESSIONS:-2}"; RATE="${RATE:-20}"; SECS="${SECS:-20}"; PX="${PX:-50}"; AGE_WAIT="${AGE_WAIT:-35}"
NODE_IMAGE="${NODE_IMAGE:-node:20-alpine}"
case "${MODE}" in sequenced) LOADFLAG=();; unsequenced) LOADFLAG=(--unsequenced);; *) fail "MODE=${MODE}";; esac
et() { TZ=America/New_York date '+%-I:%M:%S %p ET'; }
GWS=($("${K[@]}" get pods -l app=cluster-gateway --field-selector=status.phase=Running -o name))
(( ${#GWS[@]} >= 1 )) || fail "no running cluster-gateway pod"
pf TP "${TP_TARGET:-svc/trade-processor}" 18091
pf GW svc/order-matcher 18110
pf NATSP "${NATS_TARGET:-svc/nats}" 4222 tcp
OPH=(-H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: kind-ffr20')

step "0. business day, accounts, tickers ($(et))"
c="$(code -X POST "${GW}/session/business-day" "${OPH[@]}" -d "{\"businessDate\":\"$(TZ=America/New_York date +%F)\"}")"
[[ "${c}" == 200 ]] || fail "business day: ${c} $(body)"
for a in 9900010 9900011 9900012 9900013 9900014 9900015 9900016 9900017 22214 42422; do
  c="$(code -X POST "${GW}/risk/control/account" "${OPH[@]}" -d "{\"accountId\":${a},\"enabled\":true}")"
  [[ "${c}" == 200 ]] || fail "admit ${a}: ${c} $(body)"
done
sql "INSERT IGNORE INTO accounts(id,displayname) VALUES (9900010,'binary-load'),(9900011,'binary-load'),(9900012,'binary-load'),(9900013,'binary-load'),(9900014,'binary-load'),(9900015,'binary-load'),(9900016,'binary-load'),(9900017,'binary-load'); SELECT count(*) FROM accounts WHERE id BETWEEN 9900010 AND 9900017" \
  | tee "${EV}/sql-accounts.txt" | grep -qx 8 || fail "the load accounts are not in SQL accounts: $(cat "${EV}/sql-accounts.txt")"
T="$(mint_ticker)"; TT="$(mint_ticker)"
for t in "${T}" "${TT}"; do
  c="$(code -X POST "${GW}/seed" "${OPH[@]}" -d "{\"accountId\":22214,\"tickers\":\"${t}\",\"price\":${PX}}")"
  [[ "${c}" == 200 ]] || fail "seed ${t}: ${c} $(body)"
done
ok "load ticker ${T}, trigger ticker ${TT} @ ${PX}; gateways ${GWS[*]#pod/}"

# reading <label>: one JSON file of every counter this proof judges by
reading() {
  local p raw="${EV}/$1.raw"; : > "${raw}"
  for p in "${GWS[@]}"; do
    "${K[@]}" exec "${p}" -- sh -c 'wget -qO- localhost:18110/metrics 2>/dev/null || curl -s localhost:18110/metrics' \
      | grep -E '^traderx_fix_(fill_reports_total|tracked_evicted_total|end_reports_total|fill_feed_subscribed)' | sed "s|^|${p#pod/} |" >> "${raw}"
  done
  for i in $(seq 0 $((MEMBERS - 1))); do echo "member-${i} $(mget "${i}" /health | jget 'd.get("trades")')" >> "${raw}"; done
  curl -s -m 10 "${TP}/v2/projection-feed" > "${raw}.feed"
  echo "fills $(jget 'json.dumps(d.get("orders",{}).get("fills"))' < "${raw}.feed")" >> "${raw}"
  echo "view $(jget 'json.dumps({k: d["orders"].get(k) for k in ("applied", "viewSize", "dropped", "rejected")})' < "${raw}.feed")" >> "${raw}"
  echo "stream $(python3 "${here}/lib-jetstream.py" info "127.0.0.1:${NATSP}" TRADERX_FILLS | jget 'd.get("state",{}).get("messages")')" >> "${raw}"
  python3 - "${raw}" "$1" "$(et)" > "${EV}/$1.json" <<'PY'
import json, re, sys
raw, label, at = sys.argv[1:4]
out = {"label": label, "at": at, "gateways": {}, "trades": [], "fills": None, "fillsStreamMessages": None}
for line in open(raw):
    k, rest = line.rstrip("\n").split(" ", 1)
    if k.startswith("member-"): out["trades"].append(int(rest) if rest.strip() else None)
    elif k == "fills": out["fills"] = json.loads(rest) if rest.strip() not in ("", "null") else None
    elif k == "view": out["orderView"] = json.loads(rest) if rest.strip() else None
    elif k == "stream": out["fillsStreamMessages"] = int(rest) if rest.strip() else None
    else:
        m = re.match(r'(\S+?)(?:\{outcome="(\w+)"\})? (\S+)$', rest)
        name = m.group(1) + ("." + m.group(2) if m.group(2) else "")
        out["gateways"].setdefault(k, {})[name] = int(float(m.group(3)))
print(json.dumps(out, indent=1))
PY
  python3 - "${EV}/$1.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); f = d["fills"] or {}
gw = "; ".join(f'{g}: lost={v.get("traderx_fix_fill_reports_total.lost")} sent={v.get("traderx_fix_fill_reports_total.sent")} '
               f'gap={v.get("traderx_fix_fill_reports_total.gap")} routedTo={f.get("gateways", {}).get(g, 0)}' for g, v in d["gateways"].items())
print(f'[{d["label"]} {d["at"]}] trades={d["trades"]} router routed={f.get("routed")} messages={f.get("messages")} '
      f'acked={f.get("acked")} failed={f.get("failed")} TRADERX_FILLS={d["fillsStreamMessages"]} orderView={d.get("orderView")} | {gw}')
PY
}
# settle <label>: re-read until the router's count and the members' trades hold still for 5 s (at most 90 s)
settle() {
  local prev="" cur i
  for i in $(seq 1 18); do
    reading "$1" > "${EV}/$1.line"
    cur="$(python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));print((d["fills"] or {}).get("routed"),d["trades"])' "${EV}/$1.json")"
    [[ "${cur}" == "${prev}" ]] && break; prev="${cur}"; sleep 5
  done
  cat "${EV}/$1.line"
}

step "1. before"
settle before

step "2. binary-load (${MODE}) through each gateway: ${SESSIONS} sessions x ${RATE}/s for ${SECS}s ($(et))"
"${K[@]}" create configmap binary-load --from-file=binary-load.mjs="${ROOT}/scripts/gcp-compsci/rig/binary-load.mjs" --dry-run=client -o yaml | "${K[@]}" apply -f - >/dev/null
i=0; JOBS=()
for p in "${GWS[@]}"; do
  ip="$("${K[@]}" get "${p}" -o jsonpath='{.status.podIP}')"; j="ffr20-load-${MODE}-${i}"; JOBS+=("${j}")
  "${K[@]}" delete job "${j}" --ignore-not-found --wait=true >/dev/null
  "${K[@]}" apply -f - >/dev/null <<YAML
apiVersion: batch/v1
kind: Job
metadata: {name: ${j}}
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
      - name: load
        image: ${NODE_IMAGE}
        imagePullPolicy: IfNotPresent
        command: [node, /load/binary-load.mjs, --resolve, "http://order-matcher:18110", --ticker, "${T}", --px, "${PX}",
                  --host, "${ip}", --pod-index, "${i}", --sessions, "${SESSIONS}", --rate, "${RATE}", --secs, "${SECS}",
                  --phase-secs, "10", --pause-ms, "3000", --out, /tmp/results.json ${LOADFLAG[@]+, "${LOADFLAG[*]}"}]
        volumeMounts: [{name: load, mountPath: /load}]
      volumes: [{name: load, configMap: {name: binary-load}}]
YAML
  echo "${j}: ${p#pod/} at ${ip}"; i=$((i + 1))
done
for j in "${JOBS[@]}"; do
  for _ in $(seq 1 120); do [[ -n "$("${K[@]}" get job "${j}" -o jsonpath='{.status.succeeded}{.status.failed}')" ]] && break; sleep 3; done
  "${K[@]}" logs "job/${j}" > "${EV}/${j}.log" 2>&1
  grep '^RESULT' "${EV}/${j}.log" | cut -c1-600
  [[ "$("${K[@]}" get job "${j}" -o jsonpath='{.status.succeeded}')" == 1 ]] || fail "${j} did not complete clean: $(tail -3 "${EV}/${j}.log")"
done
LOAD_END=$(date +%s); echo "load ended $(et)"
settle after-load

step "3. ${AGE_WAIT}s past the load, then one REST cross per gateway (the trigger for expireEarly)"
sleep "${AGE_WAIT}"
BS="$(mint false '[22214]')"; BB="$(mint false '[42422]')"; [[ -n "${BS}" && -n "${BB}" ]] || fail "no bearer for the trigger accounts"
for p in "${GWS[@]}"; do
  pf GWP "${p}" 18110
  for o in "22214 Sell ${BS}" "42422 Buy ${BB}"; do
    set -- ${o}
    c="$(code -X POST "${GWP}/orders" -H "Authorization: Bearer $3" -H 'Content-Type: application/json' \
         -d "{\"accountId\":$1,\"ticker\":\"${TT}\",\"side\":\"$2\",\"quantity\":1,\"limitPrice\":${PX}}")"
    echo "trigger ${p#pod/} $2 $1: ${c} $(body | head -c 200)" | tee -a "${EV}/trigger.txt"
    [[ "${c}" == 2* ]] || fail "trigger order refused"
  done
done
echo "trigger sent $(et), $(( $(date +%s) - LOAD_END ))s after the load ended" | tee -a "${EV}/trigger.txt"
sleep 5
settle after-trigger

step "verdict (${MODE})"
python3 - "${EV}" "${MODE}" <<'PY' | tee "${EV}/verdict.txt"
import json, sys
ev, mode = sys.argv[1:3]
r = {k: json.load(open(f"{ev}/{k}.json")) for k in ("before", "after-load", "after-trigger")}
loads = [json.loads(l[7:]) for j in sorted(__import__("glob").glob(f"{ev}/ffr20-load-{mode}-*.log")) for l in open(j) if l.startswith("RESULT ")]
accepted = sum(x["accepted"] for x in loads)
gws = sorted(r["before"]["gateways"])
routed = lambda k, g: (r[k]["fills"] or {}).get("gateways", {}).get(g, 0)
lost = lambda k, g: r[k]["gateways"][g].get("traderx_fix_fill_reports_total.lost", 0)
dtrades = r["after-load"]["trades"][0] - r["before"]["trades"][0]
bad = []
print(f"load: {len(loads)} pods, sent {sum(x['sent'] for x in loads)}, accepted {accepted}, sequenced {[x['sequenced'] for x in loads]}; "
      f"members' trades {r['before']['trades'][0]} -> {r['after-load']['trades'][0]} (+{dtrades})")
if dtrades <= 0: bad.append("the load booked no trades")
for g in gws:
    dr = routed("after-load", g) - routed("before", g); dl = lost("after-trigger", g) - lost("before", g)
    dt = routed("after-trigger", g) - routed("after-load", g)
    print(f"{g}: routed {routed('before', g)} -> {routed('after-load', g)} (+{dr} with the load) -> {routed('after-trigger', g)} (+{dt} the trigger); "
          f"lost {lost('before', g)} -> {lost('after-load', g)} -> {lost('after-trigger', g)} (+{dl} through the trigger)")
    if dt <= 0: bad.append(f"{g}: the trigger was not routed to it")
    if mode == "sequenced" and (dr != 0 or dl != 0): bad.append(f"{g}: sequenced load routed +{dr}, lost +{dl}")
    if mode == "unsequenced" and (dr <= 0 or dl <= 0): bad.append(f"{g}: unsequenced load routed +{dr}, lost +{dl}")
print("FAIL " + "; ".join(bad) if bad else f"OK ({mode})")
PY
grep -q '^OK' "${EV}/verdict.txt" || exit 1
