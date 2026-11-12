#!/usr/bin/env bash
# kind-fix-restatement-failover.sh — acceptance run 7's gateway OutOfMemoryError after a leader kill, reproduced on kind.
#
# GKE 2026-11-12 (run 7, 3526dcdf): the leader's JVM SIGKILLed at ~450K orders/s; all 18 gateways (-Xmx768m, ZGC) out of
# Java heap 9-13 s after the leader change. The gateway resubmits its stranded FIX orders under their keys; the new leader
# answers each already-booked one REJECTED/DUPLICATE + ref; FixGatewayAcceptor.sendRestatement then reads the account's
# WHOLE order list (GET {ORDER_READMODEL_URL}/accounts/{id}/orders?status=all, a String parsed into a Jackson tree) to find
# one ref. onOrderStatusRequest (35=H) falls back to the same listing.
#
# Three modes, one rig, black-box readings; the verdict is the caller's.
#   cluster          create kind cluster $CLUSTER (refuses if ANY kind cluster exists): 1 control plane + 3 workers,
#                    host 127.0.0.1:$FIX_NODEPORT mapped to the FIX NodePort, so the load runs on the Mac, not in a pod
#   rig   TAG=<t>    load traderx/{cluster-node,trade-processor}:<t> (+ nats, mariadb) into the nodes; recreate namespace
#                    $NS from kind-yu18-rig.py --throughput plus production's gateway JVM (gke/gateway.yaml: ZGC,
#                    -Xms768m -Xmx768m, pre-touched), FIX_EXACTLY_ONCE=engine (prepare.exactly_once), $SESSIONS load CompIDs
#                    on the production FIX accounts, a Tomcat access log on trade-processor (stdout), the FIX NodePort.
#                    A fresh epoch each time: the after arm changes only TAG.
#   run   EVIDENCE=<dir>  the arm: business day, accounts, fresh ticker; fix-rehearsal-load.mjs ($SESSIONS x $RATE/s) for
#                    $BUILD_SECS to grow the accounts' ?status=all lists; the lists measured (rows, bytes); the leader's JVM
#                    killed by host pid (crictl) with the load in flight; $POST_SECS more of load; the driver drains;
#                    readings collected and judged.
#
# Verdict branches (written before the first run; printed into the evidence as branches.txt):
#   VALID requires ALL: a new leader within 30 s of the kill; the load in flight at the kill (driver sent rose in the
#     20 s before it, members' trades rose during the build-up); the access log counts the script's own probe of the
#     seven accounts (else the request counter is blind); every gateway container's log was captured and says -Xmx768m;
#     no OutOfMemoryError before the kill.
#   REPRODUCED      VALID and >= 1 gateway logs java.lang.OutOfMemoryError after the kill. Mechanism SUPPORTED if the
#                   read model served ?status=all to the gateway after the kill and the stranded count is > 0; NOT
#                   INDICATED otherwise (an OOM from another path: say so, indict "restatement -> full listing").
#   NOT REPRODUCED  VALID and no OutOfMemoryError after the kill. Reported with the measured list sizes, stranded count,
#                   restatements and ?status=all count: at kind rates the stranded/duplicate count or the list size is
#                   too small. Not rounded toward "reproduced" by a heap that merely grew; the heap is never shrunk below
#                   768m and the load is never raised past what kind carries to force it.
#   INCONCLUSIVE    any VALID condition fails: named in the verdict, no claim either way.
#   A second arm with pre-grown lists is allowed only if this one does not bite, and only clearly labelled (ARM=...):
#   it answers "does the listing OOM at a larger list", not "did run 7's lists OOM".
#
#   CLUSTER=traderx-r7k bash scripts/proofs/kind-fix-restatement-failover.sh cluster
#   TAG=r7k-3526dcdf bash scripts/proofs/kind-fix-restatement-failover.sh rig
#   EVIDENCE=<dir> [ARM=before SESSIONS=28 RATE=40 BUILD_SECS=240 POST_SECS=180 PX=50] bash scripts/proofs/kind-fix-restatement-failover.sh run
#
# Exit: 0 = a VALID verdict (REPRODUCED or NOT REPRODUCED; EXPECT=oom|no-oom makes the other one exit 1); 2 = INCONCLUSIVE.
set -uo pipefail
MODE="${1:?usage: cluster | rig | run}"
here="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "${here}/../.." && pwd)"
CLUSTER="${CLUSTER:-traderx-r7k}"; export CTX="${CTX:-kind-${CLUSTER}}" NS="${NS:-traderx}"
[[ "${CTX}" == kind-* ]] || { echo "[FAIL] kind only: this kills a member's JVM on its node" >&2; exit 1; }
FIX_NODEPORT="${FIX_NODEPORT:-30130}"
SESSIONS="${SESSIONS:-28}"; RATE="${RATE:-40}"; BUILD_SECS="${BUILD_SECS:-240}"; POST_SECS="${POST_SECS:-180}"; PX="${PX:-50}"
ACCOUNTS=(9900002 9900003 9900004 9900005 9900006 9900007 9900008)   # prepare.FIX_TEST_ACCOUNTS, round-robin as load_clients
GATEWAY_JVM='-Dsun.net.httpserver.nodelay=true -XX:+UseZGC -XX:+ZGenerational -Xms768m -Xmx768m -XX:+AlwaysPreTouch --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED'
et() { TZ=America/New_York date "+%-I:%M:%S %p ET"; }
now_ms() { python3 -c 'import time;print(int(time.time()*1000))'; }
py() { uv run --offline --no-project --with pyyaml python3 "$@"; }   # host python has no yaml
free_gb() { df -g /System/Volumes/Data | awk 'NR==2{print $4}'; }
disk_guard() { local f; f="$(free_gb)"; [[ "${f}" =~ ^[0-9]+$ ]] || { echo "[FAIL] cannot read free disk" >&2; exit 1; }
  (( f >= 20 )) || { echo "[FAIL] ${f} GB free on the Mac: under the 20 GB floor; stop" >&2; exit 1; }; echo "disk ${f} GB free"; }

if [[ "${MODE}" == cluster ]]; then
  n="$(kind get clusters 2>/dev/null | grep -c .)"
  (( n == 0 )) || { echo "[FAIL] a kind cluster already exists ($(kind get clusters | tr '\n' ' ')): one per Mac" >&2; exit 1; }
  disk_guard
  img="${KIND_NODE_IMAGE:-$(docker images --no-trunc --format '{{.Repository}}@{{.ID}}' kindest/node | head -1)}"
  [[ -n "${img}" ]] || { echo "[FAIL] no local kindest/node image" >&2; exit 1; }
  img="kindest/node@$(docker image inspect "${img#kindest/node@}" --format '{{index .RepoDigests 0}}' | sed 's/.*@//')"
  kind create cluster --name "${CLUSTER}" --image "${img}" --wait 180s --config - <<YAML
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraPortMappings:
      - {containerPort: ${FIX_NODEPORT}, hostPort: ${FIX_NODEPORT}, listenAddress: 127.0.0.1, protocol: TCP}
  - role: worker
  - role: worker
  - role: worker
YAML
  exit $?
fi

. "${here}/lib-yu18.sh"

if [[ "${MODE}" == rig ]]; then
  : "${TAG:?TAG=<image tag> (traderx/cluster-node:<tag>, traderx/trade-processor:<tag>)}"
  disk_guard
  imgs=("traderx/cluster-node:${TAG}" "traderx/trade-processor:${TAG}" nats:2.14-alpine mariadb:11.4)
  tmp="$(mktemp -d)"; trap 'rm -rf "${tmp}"; _lib_yu18_cleanup' EXIT
  for i in "${imgs[@]}"; do
    docker image inspect "${i}" >/dev/null 2>&1 || fail "${i} is not in the local daemon (build it; nothing is pulled)"
    docker save --platform linux/arm64 -o "${tmp}/i.tar" "${i}" || fail "docker save ${i}"
    kind load image-archive "${tmp}/i.tar" --name "${CLUSTER}" >/dev/null || fail "kind load ${i}"
    rm -f "${tmp}/i.tar"
    for n in $(kind get nodes --name "${CLUSTER}"); do
      docker exec "${n}" crictl images 2>/dev/null | awk -v r="${i%:*}" -v t="${i##*:}" '$1 ~ r"$" && $2 == t {f=1} END {exit !f}' \
        || fail "${i} is not in ${n}'s CRI image store after the load"
    done
    echo "[ok] ${i} on every node"
  done
  # the rig: kind-yu18-rig.py's production read-model shape, then what this proof needs on top
  py "${here}/kind-yu18-rig.py" --tag "${TAG}" --throughput > "${tmp}/base.yaml" || fail "render"
  SESSIONS="${SESSIONS}" GATEWAY_JVM="${GATEWAY_JVM}" ACCOUNTS="${ACCOUNTS[*]}" FIX_NODEPORT="${FIX_NODEPORT}" \
    py - "${tmp}/base.yaml" > "${tmp}/rig.yaml" <<'PY' || fail "patch"
import os, sys, yaml
docs = [d for d in yaml.safe_load_all(open(sys.argv[1])) if d]
accts = os.environ['ACCOUNTS'].split()
comp = [f'LOAD{n}' for n in range(1, int(os.environ['SESSIONS']) + 1)]
def env(c, k, v): c['env'] = [e for e in c['env'] if e['name'] != k] + [{'name': k, 'value': v}]
for d in docs:
    pod = d.get('spec', {}).get('template', {}).get('spec')
    if d['kind'] == 'Deployment' and d['metadata']['name'] == 'cluster-gateway':
        c = pod['containers'][0]
        env(c, 'JAVA_TOOL_OPTIONS', os.environ['GATEWAY_JVM'])                      # gke/gateway.yaml, as production
        env(c, 'FIX_EXACTLY_ONCE', 'engine')                                         # prepare.exactly_once
        e = {x['name']: x for x in c['env']}
        e['FIX_SESSION_COMPIDS']['value'] += ',' + ','.join(comp)
        auth = e['FIX_SESSION_AUTH']; c['env'].remove(auth); c['env'].append(auth)  # $(VAR) expands only earlier entries
        auth['value'] += ''.join(f',{x}:$(FIX_SESSION_PASSWORD):{accts[i % len(accts)]}' for i, x in enumerate(comp))
    if d['kind'] == 'Deployment' and d['metadata']['name'] == 'trade-processor':
        c = pod['containers'][0]   # the read model's request counter: Tomcat's access log on stdout (query string, bytes, ms)
        for k, v in {'SERVER_TOMCAT_ACCESSLOG_ENABLED': 'true', 'SERVER_TOMCAT_ACCESSLOG_DIRECTORY': '/dev',
                     'SERVER_TOMCAT_ACCESSLOG_PREFIX': 'stdout', 'SERVER_TOMCAT_ACCESSLOG_SUFFIX': '',
                     'SERVER_TOMCAT_ACCESSLOG_FILE_DATE_FORMAT': '', 'SERVER_TOMCAT_ACCESSLOG_ROTATE': 'false',
                     'SERVER_TOMCAT_ACCESSLOG_BUFFERED': 'false',
                     'SERVER_TOMCAT_ACCESSLOG_PATTERN': 'ACCESS %a "%r" %s %b %D'}.items():
            env(c, k, v)
docs.append({'apiVersion': 'v1', 'kind': 'Service', 'metadata': {'name': 'order-matcher-gw-fix-nodeport', 'namespace': 'traderx'},
             'spec': {'type': 'NodePort', 'selector': {'app': 'cluster-gateway'},
                      'ports': [{'name': 'fix', 'port': 18130, 'targetPort': 18130, 'nodePort': int(os.environ['FIX_NODEPORT'])}]}})
yaml.safe_dump_all(docs, sys.stdout, sort_keys=False)
PY
  [[ "${NS}" == traderx ]] || fail "the render is namespace traderx"
  "${K[@]}" get ns "${NS}" >/dev/null 2>&1 && { kubectl --context "${CTX}" delete ns "${NS}" --wait=true || fail "delete ns"; }
  kubectl --context "${CTX}" create ns "${NS}" >/dev/null || fail "create ns"
  "${K[@]}" apply -f "${tmp}/rig.yaml" >/dev/null || fail "apply"
  cp "${tmp}/rig.yaml" "${RIG_RENDER_OUT:-/dev/null}" 2>/dev/null || true
  for w in sts/order-matcher-cluster deploy/nats deploy/eod-price-db deploy/trade-processor deploy/cluster-gateway; do
    "${K[@]}" rollout status "${w}" --timeout=600s || fail "${w} not ready"
  done
  for i in $(seq 1 90); do r="$(mget 0 /health | jget 'd.get("applied")')"; [[ "${r}" =~ ^[0-9]+$ && "${r}" -ge 0 ]] && break; sleep 4; done
  [[ "${r}" =~ ^[0-9]+$ ]] || fail "member 0 never applied (applied='${r}')"
  ok "rig up at tag ${TAG}: $("${K[@]}" get pods --no-headers | awk '{print $1, $2, $3}' | tr '\n' ';')"
  exit 0
fi

[[ "${MODE}" == run ]] || fail "mode ${MODE}: cluster | rig | run"
EV="${EVIDENCE:?EVIDENCE=<dir>}"; mkdir -p "${EV}"; ARM="${ARM:-before}"
(( $(kind get clusters | grep -c .) == 1 )) || fail "exactly one kind cluster expected: $(kind get clusters | tr '\n' ' ')"
disk_guard | tee "${EV}/disk-start.txt"
sed -n '/^# Verdict branches/,/^#   CLUSTER=/p' "$0" | sed 's/^# \{0,1\}//' > "${EV}/branches.txt"
cat > "${EV}/parameters.txt" <<P
arm=${ARM} ctx=${CTX} ns=${NS} sessions=${SESSIONS} rate_per_session=${RATE} build_secs=${BUILD_SECS} post_secs=${POST_SECS} px=${PX}
accounts=${ACCOUNTS[*]} driver=scripts/gcp-compsci/rig/fix-rehearsal-load.mjs max_in_flight=1024 drain_secs=90
written $(et) ($(date -u +%FT%TZ)), before the load
P
cat "${EV}/parameters.txt"
DRV_PID=""; FOLLOW_PIDS=(); WORK="$(mktemp -d)"
cleanup() { local p; [[ -n "${DRV_PID}" ]] && kill "${DRV_PID}" 2>/dev/null; touch "${WORK}/stop"
  for p in "${FOLLOW_PIDS[@]:-}"; do [[ -n "${p}" ]] && kill "${p}" 2>/dev/null; done; rm -rf "${WORK}"; _lib_yu18_cleanup; }
trap cleanup EXIT
# The operator token goes in on stdin (curl -H @-), never on argv.
op() { operator_token | awk '{print "X-Risk-Control-Token: " $0; print "X-Risk-Operator: kind-fix-restatement-failover"; print "Content-Type: application/json"}' \
  | "${K[@]}" exec -i deploy/trade-processor -- curl -s -m 30 -o /dev/null -w '%{http_code}' -H @- -X POST "http://order-matcher:18110$1" -d "$2"; }
role() { mget "$1" /health | jget 'd.get("role")'; }
leader() { local i; for i in 0 1 2; do [[ "$(role ${i} 2>/dev/null)" == LEADER ]] && { echo "${i}"; return; }; done; echo none; }
trades() { mget "$1" /health | jget 'd.get("trades")'; }
kill_member() { local pod="order-matcher-cluster-$1" node cid pid
  node="$("${K[@]}" get pod "${pod}" -o jsonpath='{.spec.nodeName}')"
  cid="$("${K[@]}" get pod "${pod}" -o jsonpath='{.status.containerStatuses[?(@.name=="cluster-node")].containerID}')"; cid="${cid#containerd://}"
  pid="$(docker exec "${node}" crictl inspect -o go-template --template '{{.info.pid}}' "${cid}")"
  [[ "${pid}" =~ ^[0-9]+$ && "${pid}" -gt 1 ]] || fail "no host pid for ${pod} (${pid})"
  echo "${pod} on ${node}: container ${cid:0:12}, host pid ${pid}"
  docker exec "${node}" kill -9 "${pid}"; }
# per-pod log followers (kubelet timestamps): a restarted container is followed from its first line; duplicates sorted out
follow() { local pod="$1" c="$2"
  ( while [[ ! -e "${WORK}/stop" ]]; do "${K[@]}" logs -f --timestamps "${pod}" -c "${c}" 2>/dev/null; sleep 1; done ) >> "${EV}/logs/${pod}.${c}.raw" &
  FOLLOW_PIDS+=($!); }
lists() { # lists <label>: every load account's ?status=all list, rows and bytes, read inside the read model's pod
  local a b
  for a in "${ACCOUNTS[@]}"; do
    b="$("${K[@]}" exec deploy/trade-processor -- curl -s -m 30 "http://localhost:18091/accounts/${a}/orders?status=all" | tee "${WORK}/list-${a}.json" | wc -c | tr -d ' ')"
    echo "${a} rows=$(python3 -c 'import json,sys;print(len(json.load(open(sys.argv[1]))))' "${WORK}/list-${a}.json" 2>/dev/null || echo '?') bytes=${b} open=$(python3 -c 'import json,sys;print(sum(1 for o in json.load(open(sys.argv[1])) if o.get("status") in ("NEW","PARTIALLY_FILLED","QUEUED","PENDING_TRIGGER","SUSPENDED")))' "${WORK}/list-${a}.json" 2>/dev/null || echo '?')"
  done | sed "s/^/$1 /" | tee -a "${EV}/lists.txt"; }

step "0. the rig ($(et))"
mkdir -p "${EV}/logs"
"${K[@]}" get pods -o wide > "${EV}/pods-start.txt"
"${K[@]}" get pods -o jsonpath='{range .items[*]}{.metadata.name}{" "}{range .status.containerStatuses[*]}{.name}{"="}{.imageID}{" restarts="}{.restartCount}{" "}{end}{"\n"}{end}' > "${EV}/images-start.txt"
cat "${EV}/images-start.txt"
GWS=($("${K[@]}" get pods -l app=cluster-gateway -o jsonpath='{.items[*].metadata.name}')); (( ${#GWS[@]} >= 1 )) || fail "no gateway pod"
TPP="$("${K[@]}" get pods -l app=trade-processor -o jsonpath='{.items[0].metadata.name}')"
for g in "${GWS[@]}"; do follow "${g}" gateway; done
follow "${TPP}" trade-processor
for i in 0 1 2; do follow "order-matcher-cluster-${i}" cluster-node; done
L0="$(leader)"; [[ "${L0}" != none ]] || fail "no leader"
ok "gateways ${GWS[*]}; read model ${TPP}; leader member ${L0}"

step "1. admission: business day, accounts ${ACCOUNTS[*]} (control and SQL), a fresh ticker @ ${PX}"
[[ "$(op /session/business-day "{\"businessDate\":\"$(TZ=America/New_York date +%F)\"}")" == 200 ]] || fail "business day"
for a in "${ACCOUNTS[@]}"; do [[ "$(op /risk/control/account "{\"accountId\":${a},\"enabled\":true}")" == 200 ]] || fail "admit ${a}"; done
vals="$(printf "(%s,'fix-load')," "${ACCOUNTS[@]}")"; inlist="$(IFS=,; echo "${ACCOUNTS[*]}")"
sql "INSERT IGNORE INTO accounts(id,displayname) VALUES ${vals%,}; SELECT count(*) FROM accounts WHERE id IN (${inlist})" | tee "${EV}/sql-accounts.txt" \
  | grep -qx "${#ACCOUNTS[@]}" || fail "load accounts not in SQL accounts: $(cat "${EV}/sql-accounts.txt")"
T="$(python3 -c 'import random;print("FX%06d" % random.randrange(10**6))')"
[[ "$(op /seed "{\"accountId\":${ACCOUNTS[0]},\"tickers\":\"${T}\",\"price\":${PX}}")" == 200 ]] || fail "seed ${T}"
ok "ticker ${T}"

PW="$(secret auth-secrets fix-session-password)"; [[ -n "${PW}" ]] || fail "no fix-session-password"
CREDS="${WORK}/creds.json"; ( umask 077; PW="${PW}" SESSIONS="${SESSIONS}" ACCTS="${ACCOUNTS[*]}" python3 -c '
import json, os
a = os.environ["ACCTS"].split()
print(json.dumps({f"LOAD{n}": {"account": a[(n - 1) % len(a)], "password": os.environ["PW"]} for n in range(1, int(os.environ["SESSIONS"]) + 1)}))' > "${CREDS}" )
unset PW

step "2. the load: ${SESSIONS} sessions x ${RATE}/s for $((BUILD_SECS + POST_SECS)) s through 127.0.0.1:${FIX_NODEPORT} ($(et))"
t_start=$(now_ms); tr0="$(trades 0)"
node "${ROOT}/scripts/gcp-compsci/rig/fix-rehearsal-load.mjs" --creds "${CREDS}" --px "${PX}" --ticker "${T}" --rate "${RATE}" \
  --secs "$((BUILD_SECS + POST_SECS))" --host 127.0.0.1 --port "${FIX_NODEPORT}" --max-in-flight 1024 --drain-secs 90 \
  --out "${EV}/fix-result.json" > "${EV}/fix-load.log" 2>&1 &
DRV_PID=$!
echo "driver pid ${DRV_PID} started ${t_start} ($(et)); members' trades ${tr0}" | tee "${EV}/timeline.txt"
sleep "${BUILD_SECS}"
kill -0 "${DRV_PID}" 2>/dev/null || { tail -5 "${EV}/fix-load.log" >&2; fail "the driver exited during the build-up"; }
tr1="$(trades 0)"; [[ "${tr1}" =~ ^[0-9]+$ && "${tr0}" =~ ^[0-9]+$ && "${tr1}" -gt "${tr0}" ]] || fail "the load booked nothing (${tr0} -> ${tr1})"
echo "build-up done $(et): members' trades ${tr0} -> ${tr1}" | tee -a "${EV}/timeline.txt"
tail -2 "${EV}/fix-load.log"

step "3. the lists before the kill (the probe the access-log self-test looks for)"
t_probe=$(now_ms); lists pre-kill; t_probe_end=$(now_ms)
sleep 5

step "4. kill the leader's JVM with the load in flight ($(et))"
L0="$(leader)"; [[ "${L0}" != none ]] || fail "no leader before the kill"
for i in 0 1 2; do echo "member ${i}: role $(role ${i}) trades $(trades ${i})"; done | tee "${EV}/members-before-kill.txt"
t_kill=$(now_ms); kill_member "${L0}" | tee -a "${EV}/timeline.txt"
echo "killed member ${L0} at ${t_kill} ($(et))" | tee -a "${EV}/timeline.txt"
t_new=""; L1=none
for _ in $(seq 1 120); do
  l="$(leader)"; [[ "${l}" != none && "${l}" != "${L0}" ]] && { t_new=$(now_ms); L1="${l}"; break; }; sleep 0.25
done
echo "new leader member ${L1} first seen at ${t_new:-never} ($( [[ -n ${t_new} ]] && echo "$(( t_new - t_kill )) ms after the kill" ))" | tee -a "${EV}/timeline.txt"

step "5. the load runs on, then drains ($(et))"
wait "${DRV_PID}"; drc=$?; DRV_PID=""; t_end=$(now_ms)
echo "driver exited ${drc} at ${t_end} ($(et))" | tee -a "${EV}/timeline.txt"
tail -3 "${EV}/fix-load.log"

step "6. members agree"
for _ in $(seq 1 60); do
  a="$(trades 0)"; b="$(trades 1)"; c="$(trades 2)"
  [[ "${a}" =~ ^[0-9]+$ && "${a}" == "${b}" && "${b}" == "${c}" ]] && break; sleep 5
done
for i in 0 1 2; do echo "member ${i}: role $(role ${i}) trades $(trades ${i})"; done | tee "${EV}/members-after.txt"
lists post-run

step "7. readings ($(et))"
sleep 5; touch "${WORK}/stop"; for p in "${FOLLOW_PIDS[@]}"; do kill "${p}" 2>/dev/null; done; FOLLOW_PIDS=()
for g in $("${K[@]}" get pods -l app=cluster-gateway -o jsonpath='{.items[*].metadata.name}'); do   # pods new since the start too
  "${K[@]}" logs --timestamps "${g}" -c gateway >> "${EV}/logs/${g}.gateway.raw" 2>/dev/null
  "${K[@]}" logs --timestamps --previous "${g}" -c gateway >> "${EV}/logs/${g}.gateway.raw" 2>/dev/null
done
for f in "${EV}"/logs/*.raw; do sort -u "${f}" > "${f%.raw}.log" && rm -f "${f}"; done
"${K[@]}" get pods -o wide > "${EV}/pods-end.txt"
"${K[@]}" get pods -l app=cluster-gateway -o json > "${EV}/gateway-pods-end.json"
"${K[@]}" get pods -o jsonpath='{range .items[*]}{.metadata.name}{" "}{range .status.containerStatuses[*]}{.name}{"="}{.imageID}{" restarts="}{.restartCount}{" "}{end}{"\n"}{end}' > "${EV}/images-end.txt"
disk_guard > "${EV}/disk-end.txt"
python3 - "${EV}" "${t_start}" "${t_probe}" "${t_probe_end}" "${t_kill}" "${t_new:-0}" "${t_end}" "${L0}" "${L1}" "${#ACCOUNTS[@]}" "${EXPECT:-}" <<'PY' | tee "${EV}/verdict.txt"
import datetime, glob, json, os, re, sys
ev, t_start, t_probe, t_probe_end, t_kill, t_new, t_end, l0, l1, naccts, expect = sys.argv[1:12]
t_start, t_probe, t_probe_end, t_kill, t_new, t_end, naccts = map(int, (t_start, t_probe, t_probe_end, t_kill, t_new, t_end, naccts))
def ms(line):   # kubelet RFC3339Nano -> epoch ms
    ts = line.split(' ', 1)[0]
    try:
        d, frac = ts.rstrip('Z').split('.') if '.' in ts else (ts.rstrip('Z'), '0')
        return int(datetime.datetime.fromisoformat(d).replace(tzinfo=datetime.timezone.utc).timestamp() * 1000) + int(frac[:3].ljust(3, '0'))
    except ValueError:
        return None
from zoneinfo import ZoneInfo
def et(t): return datetime.datetime.fromtimestamp(t / 1000, ZoneInfo('America/New_York')).strftime('%-I:%M:%S.%f')[:-3] + ' ET'
out, bad, stranded, full_after = [], [], 0, 0
say = lambda s: (out.append(s), print(s))
say(f"kill {et(t_kill)}; leader member {l0} -> {l1}, first seen {t_new - t_kill if t_new else 'never'} ms after")
if not t_new or t_new - t_kill > 30000 or l1 in ('none', l0): bad.append('no new leader within 30 s of the kill')
# gateways
oom_after = 0
for f in sorted(glob.glob(f'{ev}/logs/*.gateway.log')):
    pod = os.path.basename(f).split('.')[0]; lines = open(f, errors='replace').read().splitlines()
    jvm = [l for l in lines if 'Picked up JAVA_TOOL_OPTIONS' in l]
    ooms = [ms(l) for l in lines if 'OutOfMemoryError' in l]; ooms = [t for t in ooms if t]
    before = [t for t in ooms if t < t_kill]; after = [t for t in ooms if t >= t_kill]
    strand = [ms(l) for l in lines if 'stranded by a leader change' in l]
    amb = sum('ambiguous (no committed ack)' in l for l in lines)
    rmfail = sum('read-model status query failed' in l for l in lines)
    oom_after += len(after); stranded += len(strand)
    heap = sorted(set(re.findall(r'-Xmx\S+', ' '.join(jvm))))
    say(f"{pod}: containers {len(jvm)} (heap {heap}); OutOfMemoryError lines {len(ooms)} "
        f"(before the kill {len(before)}, after {len(after)}; first after the kill "
        f"{'+%.1f s' % ((min(after) - t_kill) / 1000) if after else 'none'}); stranded {len(strand)} "
        f"(first {'+%.1f s' % ((min(strand) - t_kill) / 1000) if strand else 'none'}); ambiguous {amb}; read-model query failures {rmfail}")
    if not jvm or any('-Xmx768m' not in j for j in jvm): bad.append(f'{pod}: a container without -Xmx768m, or its log not captured')
    if before: bad.append(f'{pod}: OutOfMemoryError before the kill ({len(before)})')
pods = json.load(open(f'{ev}/gateway-pods-end.json'))['items']
for p in pods:
    for c in p['status'].get('containerStatuses', []):
        say(f"{p['metadata']['name']} restarts {c['restartCount']} last {json.dumps(c.get('lastState', {}).get('terminated', {}) and {k: c['lastState']['terminated'].get(k) for k in ('reason', 'exitCode', 'finishedAt')})}")
start_restarts = {l.split()[0]: l for l in open(f'{ev}/images-start.txt')}
# the read model's access log
acc = [l for f in glob.glob(f'{ev}/logs/*.trade-processor.log') for l in open(f, errors='replace') if ' ACCESS ' in l]
def req(l):
    m = re.search(r'ACCESS (\S+) "GET (\S+) [^"]*" (\d+) (\S+) (\d+)', l)
    return m and (ms(l), m.group(1), m.group(2), int(m.group(3)), int(m.group(4)) if m.group(4).isdigit() else 0, int(m.group(5)))
reqs = [r for r in map(req, acc) if r]
probe = [r for r in reqs if t_probe - 2000 <= r[0] <= t_probe_end + 2000 and r[2].endswith('?status=all') and r[1] in ('127.0.0.1', '0:0:0:0:0:0:0:1', '::1')]
say(f"access log: {len(acc)} lines, {len(reqs)} GETs; the script's own pre-kill probe seen {len(probe)} of {naccts}")
if len(probe) < naccts: bad.append(f'access log blind: saw {len(probe)} of the probe\'s {naccts} ?status=all requests')
mine = lambda r: not (t_probe - 2000 <= r[0] <= t_probe_end + 2000) and r[1] not in ('127.0.0.1', '0:0:0:0:0:0:0:1', '::1')
for name, lo, hi in (('build-up (start..kill)', t_start, t_kill), ('kill..end', t_kill, t_end + 60000)):
    w = [r for r in reqs if lo <= r[0] < hi and mine(r) and '/orders' in r[2]]
    full = [r for r in w if 'status=all' in r[2]]; key = [r for r in w if 'clientOrderKey=' in r[2]]
    say(f"read model, {name}: ?status=all {len(full)} ({sum(r[4] for r in full):,} bytes, largest {max([r[4] for r in full] or [0]):,}, "
        f"slowest {max([r[5] for r in full] or [0])} us (Tomcat %D), non-200 {sum(r[3] != 200 for r in full)}); ?clientOrderKey= {len(key)}; "
        f"open-only listings {sum(1 for r in w if r not in full and r not in key)}")
    if name.startswith('kill'):
        first = min([r[0] for r in full] or [0]); full_after = len(full)
        if full: say(f"   first ?status=all after the kill +{(first - t_kill) / 1000:.1f} s; per 5 s from the kill: "
                     + ' '.join(str(sum(1 for r in full if t_kill + 5000 * k <= r[0] < t_kill + 5000 * (k + 1))) for k in range(12)))
# the driver
prog = []
for l in open(f'{ev}/fix-load.log', errors='replace'):
    m = re.match(r'(\S+Z) ramp=\S+ sent=(\d+) answered=(\d+) unanswered=(\d+) rejected=(\d+) restated=(\d+) dupAnswers=(\d+) filled=(\d+)', l)
    if m:
        t = int(datetime.datetime.fromisoformat(m.group(1).replace('Z', '+00:00')).timestamp() * 1000)
        prog.append((t,) + tuple(int(x) for x in m.groups()[1:]))
def at(t, k):   # the latest progress value at or before t (k: 1 sent, 7 filled)
    v = [p[k] for p in prog if p[0] <= t]; return v[-1] if v else None
s20, s0 = at(t_kill - 20000, 1), at(t_kill, 1)
if s20 is None or s0 is None or s0 <= s20: bad.append(f'the load was not in flight at the kill (sent {s20} -> {s0} in the 20 s before)')
fb = (at(t_kill, 7) or 0) - (at(t_kill - 60000, 7) or 0); fa = (at(t_kill + 120000, 7) or 0) - (at(t_kill + 60000, 7) or 0)
res = json.load(open(f'{ev}/fix-result.json')) if os.path.exists(f'{ev}/fix-result.json') else {}
recon = sum(1 for l in open(f'{ev}/fix-load.log', errors='replace') if ' RECONNECT ' in l)
say(f"FIX load: filled {fb} in the 60 s before the kill, {fa} in kill+60..+120 s; final sent {res.get('sent')} answered {res.get('answered')} "
    f"unanswered {res.get('unanswered')} filled {res.get('filled')} restated {res.get('restated')} rejected {res.get('rejected')} "
    f"reconnects {res.get('reconnects')} (RECONNECT lines {recon}) statusRequests {res.get('statusRequests')} dupFills {res.get('duplicateFills')}")
m = [l.split() for l in open(f'{ev}/members-after.txt')]
say('members after: ' + '; '.join(' '.join(x[1:]) for x in m) + (' (agree)' if len({x[-1] for x in m}) == 1 else ' (DISAGREE)'))
if bad:
    verdict = 'INCONCLUSIVE: ' + '; '.join(bad)
elif oom_after:
    verdict = 'REPRODUCED: gateway OutOfMemoryError after the kill; mechanism ' + ('SUPPORTED (?status=all served after the kill, stranded > 0)'
        if full_after and stranded else 'NOT INDICATED (no ?status=all after the kill, or nothing stranded)')
else:
    verdict = 'NOT REPRODUCED: no gateway OutOfMemoryError after the kill'
say('VERDICT ' + verdict)
code = 2 if verdict.startswith('INCONCLUSIVE') else 0
if expect == 'oom' and not verdict.startswith('REPRODUCED'): code = code or 1
if expect == 'no-oom' and not verdict.startswith('NOT REPRODUCED'): code = code or 1
open(f'{ev}/exit-code', 'w').write(str(code))
PY
exit "$(cat "${EV}/exit-code" 2>/dev/null || echo 2)"
