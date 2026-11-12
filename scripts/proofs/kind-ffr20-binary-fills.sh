#!/usr/bin/env bash
# kind-ffr20-binary-fills.sh — FR-FFR-20 / FR-FFR-21 on kind: an order whose fills no FIX session reports is not counted
# lost by its gateway's FIX reporter. Black-box, readings before and after; the verdict is the caller's (exit 1 = a
# reading contradicted MODE's expectation).
#
#   MODE=sequenced    binary-load.mjs, sequenced (ADR-090, its default): not routed (FR-FFR-20), not lost
#   MODE=unsequenced  binary-load.mjs --unsequenced, the negative control: routed, and lost once aged
#   MODE=rest         one REST cross per gateway (FR-FFR-21): still routed, passed over by the gateway, not lost
#
#   0. business day; accounts 9900010-17 (binary-load's) admitted and in SQL accounts (kind-binary-load-needs-sql-accounts),
#      22214 / 42422 (the REST crosses') admitted; fresh tickers seeded @ PX
#   1. reading "before": per gateway traderx_fix_fill_reports_total{sent,gap,lost,evicted}; trade-processor's
#      orders.fills (routed/messages/acked/failed/gateways, keyed by the name the gateway connects with: <pod> or, with
#      FR-DRS-55's claim, <pod>_s<k>) and order view; each fills stream's messages; the members' trades
#   2. the load, through every gateway pod (binary by pod IP, REST through a port-forward to the pod); "after-load"
#   3. AGE_WAIT (> FR-FFR-15's 30 s) later, the trigger: an untracked fill on each gateway's routed feed runs expireEarly,
#      the only place an aged early entry is dropped and counted lost. TRIGGER=rest (a REST cross; only before FR-FFR-21,
#      which passes REST updates over without running expireEarly) or TRIGGER=unsequenced (a small --unsequenced binary
#      run, the default since FR-FFR-21; MODE=rest always uses it). "after-trigger"
#   4. TRIGGER=unsequenced: AGE_WAIT later a second such run, "after-confirm": the first run's fills, now aged, must be
#      counted lost exactly (one per order), which shows step 3's trigger did turn the buffer
#   Expect: sequenced: the load routes +0 to each gateway, lost +0 through the trigger; unsequenced: the load is routed
#   and lost rises through the trigger; rest: the load is routed, lost +0 through the trigger. Always: the trigger is
#   routed to each gateway, and (step 4) lost after-confirm - after-trigger = the first trigger's orders on that gateway.
#
#   CTX=kind-<c> NS=traderx MODE=sequenced|unsequenced|rest EVIDENCE=<dir> [TRIGGER=unsequenced|rest SESSIONS=2 RATE=20
#     SECS=20 TRIGGER_SECS=10 TRIGGER_RATE=5 PX=50 AGE_WAIT=35 CARRY_FROM=<an earlier run's MODE dir, same gateways>] \
#     bash scripts/proofs/kind-ffr20-binary-fills.sh
here="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "${here}/../.." && pwd)"
. "${here}/lib-yu18.sh"
disposable_rig || fail "disposable rig only (kind): this runs one-off load pods"
MODE="${MODE:?MODE=sequenced|unsequenced|rest}"; EV="${EVIDENCE:?EVIDENCE=<dir>}/${MODE}"; mkdir -p "${EV}"
TRIGGER="${TRIGGER:-unsequenced}"; [[ "${MODE}" == rest ]] && TRIGGER=unsequenced
SESSIONS="${SESSIONS:-2}"; RATE="${RATE:-20}"; SECS="${SECS:-20}"; PX="${PX:-50}"; AGE_WAIT="${AGE_WAIT:-35}"
TRIGGER_SECS="${TRIGGER_SECS:-10}"; TRIGGER_RATE="${TRIGGER_RATE:-5}"
NODE_IMAGE="${NODE_IMAGE:-node:20-alpine}"
[[ "${MODE}" =~ ^(sequenced|unsequenced|rest)$ && "${TRIGGER}" =~ ^(unsequenced|rest)$ ]] || fail "MODE=${MODE} TRIGGER=${TRIGGER}"
et() { TZ=America/New_York date '+%-I:%M:%S %p ET'; }
GWS=($("${K[@]}" get pods -l app=cluster-gateway --field-selector=status.phase=Running -o name))
(( ${#GWS[@]} >= 1 )) || fail "no running cluster-gateway pod"
FS="$("${K[@]}" get deploy trade-processor -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="FILLS_STREAMS")].value}')"
STREAMS=(TRADERX_FILLS); (( ${FS:-1} > 1 )) && { STREAMS=(); for ((k = 0; k < FS; k++)); do STREAMS+=("TRADERX_FILLS_${k}"); done; }
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
ok "load ticker ${T}, trigger ticker ${TT} @ ${PX}; gateways ${GWS[*]#pod/}; fills streams ${STREAMS[*]}"

# reading <label>: one JSON file of every counter this proof judges by
reading() {
  local p s raw="${EV}/$1.raw"; : > "${raw}"
  for p in "${GWS[@]}"; do
    "${K[@]}" exec "${p}" -- sh -c 'wget -qO- localhost:18110/metrics 2>/dev/null || curl -s localhost:18110/metrics' \
      | grep -E '^traderx_fix_(fill_reports_total|tracked_evicted_total|end_reports_total|fill_feed_subscribed)' | sed "s|^|${p#pod/} |" >> "${raw}"
  done
  for i in $(seq 0 $((MEMBERS - 1))); do echo "member-${i} $(mget "${i}" /health | jget 'd.get("trades")')" >> "${raw}"; done
  curl -s -m 10 "${TP}/v2/projection-feed" > "${raw}.feed"
  echo "fills $(jget 'json.dumps(d.get("orders",{}).get("fills"))' < "${raw}.feed")" >> "${raw}"
  echo "view $(jget 'json.dumps({k: d["orders"].get(k) for k in ("applied", "viewSize", "dropped", "rejected")})' < "${raw}.feed")" >> "${raw}"
  for s in "${STREAMS[@]}"; do
    echo "stream ${s} $(python3 "${here}/lib-jetstream.py" info "127.0.0.1:${NATSP}" "${s}" | jget 'd.get("state",{}).get("messages")')" >> "${raw}"
  done
  python3 - "${raw}" "$1" "$(et)" > "${EV}/$1.json" <<'PY'
import json, re, sys
raw, label, at = sys.argv[1:4]
out = {"label": label, "at": at, "gateways": {}, "trades": [], "fills": None, "fillsStreamMessages": {}}
for line in open(raw):
    k, rest = line.rstrip("\n").split(" ", 1)
    if k.startswith("member-"): out["trades"].append(int(rest) if rest.strip() else None)
    elif k == "fills": out["fills"] = json.loads(rest) if rest.strip() not in ("", "null") else None
    elif k == "view": out["orderView"] = json.loads(rest) if rest.strip() else None
    elif k == "stream":
        s, n = (rest.split(" ", 1) + [""])[:2]
        out["fillsStreamMessages"][s] = int(n) if n.strip() else None
    else:
        m = re.match(r'(\S+?)(?:\{outcome="(\w+)"\})? (\S+)$', rest)
        name = m.group(1) + ("." + m.group(2) if m.group(2) else "")
        out["gateways"].setdefault(k, {})[name] = int(float(m.group(3)))
print(json.dumps(out, indent=1))
PY
  python3 - "${EV}/$1.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); f = d["fills"] or {}
to = lambda g: sum(v for n, v in f.get("gateways", {}).items() if n == g or n.startswith(g + "_s"))
gw = "; ".join(f'{g}: lost={v.get("traderx_fix_fill_reports_total.lost")} sent={v.get("traderx_fix_fill_reports_total.sent")} '
               f'gap={v.get("traderx_fix_fill_reports_total.gap")} routedTo={to(g)}' for g, v in d["gateways"].items())
print(f'[{d["label"]} {d["at"]}] trades={d["trades"]} router routed={f.get("routed")} messages={f.get("messages")} '
      f'acked={f.get("acked")} failed={f.get("failed")} streams={d["fillsStreamMessages"]} orderView={d.get("orderView")} | {gw}')
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
# binary <tag> <ticker> <sessions> <rate/s> <secs> [--unsequenced]: binary-load.mjs through every gateway pod by IP, one
# Job each (pod index = the gateway's, so sequenced streams never interleave); RESULT lines to <tag>-<i>.log
"${K[@]}" create configmap binary-load --from-file=binary-load.mjs="${ROOT}/scripts/gcp-compsci/rig/binary-load.mjs" --dry-run=client -o yaml | "${K[@]}" apply -f - >/dev/null
binary() {
  local tag="$1" t="$2" ses="$3" rate="$4" secs="$5" flag="${6:-}" i=0 p ip j jobs=()
  for p in "${GWS[@]}"; do
    ip="$("${K[@]}" get "${p}" -o jsonpath='{.status.podIP}')"; j="ffr20-${tag}-${i}"; jobs+=("${j}")
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
        command: [node, /load/binary-load.mjs, --resolve, "http://order-matcher:18110", --ticker, "${t}", --px, "${PX}",
                  --host, "${ip}", --pod-index, "${i}", --sessions, "${ses}", --rate, "${rate}", --secs, "${secs}",
                  --phase-secs, "10", --pause-ms, "3000", --out, /tmp/results.json ${flag:+, "${flag}"}]
        volumeMounts: [{name: load, mountPath: /load}]
      volumes: [{name: load, configMap: {name: binary-load}}]
YAML
    echo "${j}: ${p#pod/} at ${ip} ${flag:-sequenced}"; i=$((i + 1))
  done
  for j in "${jobs[@]}"; do
    for _ in $(seq 1 120); do [[ -n "$("${K[@]}" get job "${j}" -o jsonpath='{.status.succeeded}{.status.failed}')" ]] && break; sleep 3; done
    "${K[@]}" logs "job/${j}" > "${EV}/${j}.log" 2>&1
    grep '^RESULT' "${EV}/${j}.log" | cut -c1-400
    [[ "$("${K[@]}" get job "${j}" -o jsonpath='{.status.succeeded}')" == 1 ]] || fail "${j} did not complete clean: $(tail -3 "${EV}/${j}.log")"
  done
}
# rest <tag>: one REST cross per gateway pod on the trigger ticker (a sell rests, a buy takes it), through that pod
BS="$(mint false '[22214]')"; BB="$(mint false '[42422]')"; [[ -n "${BS}" && -n "${BB}" ]] || fail "no bearer for the REST accounts"
rest() {
  local p o
  for p in "${GWS[@]}"; do
    pf GWP "${p}" 18110
    for o in "22214 Sell ${BS}" "42422 Buy ${BB}"; do
      set -- ${o}
      c="$(code -X POST "${GWP}/orders" -H "Authorization: Bearer $3" -H 'Content-Type: application/json' \
           -d "{\"accountId\":$1,\"ticker\":\"${TT}\",\"side\":\"$2\",\"quantity\":1,\"limitPrice\":${PX}}")"
      echo "rest ${p#pod/} $2 $1: ${c} $(body | head -c 200)" | tee -a "${EV}/rest.txt"
      [[ "${c}" == 2* ]] || fail "REST order refused"
    done
  done
}

step "1. before"
settle before

step "2. the load (${MODE}) through each gateway ($(et))"
case "${MODE}" in
  sequenced) binary load "${T}" "${SESSIONS}" "${RATE}" "${SECS}";;
  unsequenced) binary load "${T}" "${SESSIONS}" "${RATE}" "${SECS}" --unsequenced;;
  rest) rest;;
esac
LOAD_END=$(date +%s); echo "load ended $(et)"
settle after-load

step "3. ${AGE_WAIT}s past the load, then the trigger (${TRIGGER}) on each gateway"
sleep "${AGE_WAIT}"
echo "trigger at $(et), $(( $(date +%s) - LOAD_END ))s after the load ended" | tee -a "${EV}/trigger.txt"
if [[ "${TRIGGER}" == rest ]]; then rest; else binary trigger "${TT}" 1 "${TRIGGER_RATE}" "${TRIGGER_SECS}" --unsequenced; fi
sleep 5
settle after-trigger
if [[ "${TRIGGER}" == unsequenced ]]; then
  step "4. ${AGE_WAIT}s later, a second unsequenced run: the first trigger's fills, aged, are counted lost (it turned the buffer)"
  sleep "${AGE_WAIT}"
  binary confirm "${TT}" 1 "${TRIGGER_RATE}" "${TRIGGER_SECS}" --unsequenced
  sleep 5
  settle after-confirm
fi

step "verdict (${MODE}, trigger ${TRIGGER})"
python3 - "${EV}" "${MODE}" "${TRIGGER}" "${GWS[@]#pod/}" <<'PY' | tee "${EV}/verdict.txt"
import json, os, sys
ev, mode, trig, gws = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
labels = ["before", "after-load", "after-trigger"] + (["after-confirm"] if os.path.exists(f"{ev}/after-confirm.json") else [])
r = {k: json.load(open(f"{ev}/{k}.json")) for k in labels}
def results(tag): # gateway index -> RESULT
    out = {}
    for i in range(len(gws)):
        f = f"{ev}/ffr20-{tag}-{i}.log"
        if os.path.exists(f):
            out[i] = next(json.loads(l[7:]) for l in open(f) if l.startswith("RESULT "))
    return out
routed = lambda k, g: sum(v for n, v in ((r[k]["fills"] or {}).get("gateways", {})).items() if n == g or n.startswith(g + "_s"))
lost = lambda k, g: r[k]["gateways"][g].get("traderx_fix_fill_reports_total.lost", 0)
dtrades = r["after-load"]["trades"][0] - r["before"]["trades"][0]
load, trig1 = results("load"), results("trigger")
bad = []
# CARRY_FROM=<an earlier run's MODE dir> on the same gateways: its confirm run's fills were still in the early buffers
# and age out through THIS run's trigger (one lost each), so they are subtracted from this run's lost
carry, cf = {}, os.environ.get("CARRY_FROM", "")
if cf:
    if sorted(json.load(open(f"{cf}/after-confirm.json"))["gateways"]) != sorted(gws): bad.append(f"CARRY_FROM {cf}: other gateways")
    for i in range(len(gws)):
        carry[i] = next(json.loads(l[7:]) for l in open(f"{cf}/ffr20-confirm-{i}.log") if l.startswith("RESULT "))["accepted"]
    print(f"carried in from {cf}: {carry} fills per gateway, aged in the early buffers before this run")
if load: print(f"load: sent {sum(x['sent'] for x in load.values())}, accepted {sum(x['accepted'] for x in load.values())}, "
               f"sequenced {[x['sequenced'] for x in load.values()]}")
print(f"members' trades {r['before']['trades'][0]} -> {r['after-load']['trades'][0]} (+{dtrades} with the load)")
if dtrades <= 0: bad.append("the load booked no trades")
for i, g in enumerate(gws):
    dr = routed("after-load", g) - routed("before", g); dl = lost("after-trigger", g) - lost("before", g) - carry.get(i, 0)
    dt = routed("after-trigger", g) - routed("after-load", g)
    names = sorted(n for n in (r[labels[-1]]["fills"] or {}).get("gateways", {}) if n == g or n.startswith(g + "_s"))
    print(f"{g} (router keys {names}): routed {routed('before', g)} -> {routed('after-load', g)} (+{dr} with the load) -> "
          f"{routed('after-trigger', g)} (+{dt} the trigger); lost {lost('before', g)} -> {lost('after-load', g)} -> "
          f"{lost('after-trigger', g)} (+{dl} through the trigger{f', after the {carry[i]} carried in' if i in carry else ''})")
    if dt <= 0: bad.append(f"{g}: the trigger was not routed to it")
    if mode == "sequenced" and (dr != 0 or dl != 0): bad.append(f"{g}: sequenced load routed +{dr}, lost +{dl}")
    if mode == "unsequenced" and (dr <= 0 or dl <= 0): bad.append(f"{g}: unsequenced load routed +{dr}, lost +{dl}")
    if mode == "rest" and (dr <= 0 or dl != 0): bad.append(f"{g}: REST load routed +{dr}, lost +{dl}")
    if "after-confirm" in r:
        dc = lost("after-confirm", g) - lost("after-trigger", g); want = trig1[i]["accepted"]
        print(f"   confirm: lost {lost('after-trigger', g)} -> {lost('after-confirm', g)} (+{dc}), the first trigger's orders here {want}")
        if dc != want: bad.append(f"{g}: confirm lost +{dc}, want +{want} (the trigger's orders)")
print("FAIL " + "; ".join(bad) if bad else f"OK ({mode}, trigger {trig})")
PY
grep -q '^OK' "${EV}/verdict.txt" || exit 1
