#!/usr/bin/env bash
# yu18-member-runtime.sh — ADR-081 / FR-GMR-01, -03, -04, -05 on the GKE tier, read off the running
# members rather than the manifest:
#
#   01  each member's /data is a generic ephemeral claim of StorageClass order-matcher-archive,
#       32Gi, ReadWriteOnce, Bound, and is NOT on the node boot disk (the whole point of ADR-081 §1
#       is that the archive no longer shares the boot disk's ext4 journal). The comparison is with
#       /etc/hosts, which the kubelet bind-mounts from its pod directory ON the boot disk; the
#       overlay root is a different device from everything and would prove nothing.
#   03  the JVM that runs picked up -Xms12g -Xmx12g -XX:MaxGCPauseMillis=50 (its own "Picked up
#       JAVA_TOOL_OPTIONS" line, plus /proc/<pid>/cmdline), selects no collector other than G1, and
#       the container's memory request equals its limit at 16Gi.
#   04  the kubelet's SIGTERM (pod delete) ends a FOLLOWER's container with exit status 143 (128+15, a clean exit;
#       137 would be the kubelet's SIGKILL) inside terminationGracePeriodSeconds; it then rejoins and
#       reaches the leader's applied sequence. Destructive: needs DESTRUCTIVE=1, else that step SKIPs.
#   05  promtail ships member logs: Loki holds a recent line from every member pod (the members run
#       on tainted engine nodes, so a DaemonSet without the engine toleration fails this).
#
# Usage: CTX=<ctx> NS=<ns> [DESTRUCTIVE=1] bash scripts/proofs/yu18-member-runtime.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
C="${MEMBER_CONTAINER:-cluster-node}"

step "01. /data is a dedicated order-matcher-archive volume on every member"
for ((m=0; m<MEMBERS; m++)); do
  pod="order-matcher-cluster-${m}"
  vol="$("${K[@]}" get pod "${pod}" -o json | jget "[v for v in d['spec']['volumes'] if v['name']==([vm['name'] for c in d['spec']['containers'] if c['name']=='${C}' for vm in c.get('volumeMounts',[]) if vm['mountPath']=='/data'] or ['?'])[0]][0]")"
  [[ -n "${vol}" ]] || fail "${pod}: no volume mounted at /data"
  sc="$(jget "d['ephemeral']['volumeClaimTemplate']['spec'].get('storageClassName')" <<<"${vol}")"
  sz="$(jget "d['ephemeral']['volumeClaimTemplate']['spec']['resources']['requests']['storage']" <<<"${vol}")"
  am="$(jget "','.join(d['ephemeral']['volumeClaimTemplate']['spec']['accessModes'])" <<<"${vol}")"
  name="$(jget "d['name']" <<<"${vol}")"
  [[ "${sc}" == order-matcher-archive && "${sz}" == 32Gi && "${am}" == ReadWriteOnce ]] \
    || fail "${pod}: /data volume is not an ephemeral 32Gi RWO order-matcher-archive claim (sc=${sc} size=${sz} modes=${am})"
  phase="$("${K[@]}" get pvc "${pod}-${name}" -o jsonpath='{.status.phase}' 2>/dev/null)"
  [[ "${phase}" == Bound ]] || fail "${pod}: claim ${pod}-${name} is '${phase}', not Bound"
  devs="$("${K[@]}" exec -c "${C}" "${pod}" -- sh -c 'stat -c %d /data; stat -c %d /etc/hosts' 2>/dev/null | tr '\n' ' ')"
  read -r d1 d2 <<<"${devs}"
  [[ -n "${d1}" && "${d1}" != "${d2}" ]] || fail "${pod}: /data is on the boot disk, same device as /etc/hosts (${devs})"
  ok "${pod}: ${pod}-${name} Bound, ${sc} ${sz} ${am}, /data device ${d1} != boot disk (/etc/hosts) ${d2}"
done

step "03. the running JVM's heap and pause goal"
for ((m=0; m<MEMBERS; m++)); do
  pod="order-matcher-cluster-${m}"
  res="$("${K[@]}" get pod "${pod}" -o json | jget "[c['resources'] for c in d['spec']['containers'] if c['name']=='${C}'][0]")"
  rq="$(jget "d['requests']['memory']" <<<"${res}")"; lm="$(jget "d['limits']['memory']" <<<"${res}")"
  [[ "${rq}" == 16Gi && "${lm}" == 16Gi ]] || fail "${pod}: memory request/limit ${rq}/${lm}, want 16Gi/16Gi"
  picked="$("${K[@]}" logs "${pod}" -c "${C}" 2>/dev/null | grep -m1 'Picked up JAVA_TOOL_OPTIONS' || true)"
  cmd="$("${K[@]}" exec -c "${C}" "${pod}" -- sh -c 'for p in /proc/[0-9]*; do c=$(tr "\0" " " < $p/cmdline 2>/dev/null); case "$c" in *java*) echo "$c"; tr "\0" "\n" < $p/environ 2>/dev/null | grep ^JAVA_TOOL_OPTIONS=; break;; esac; done' 2>/dev/null)"
  all="${picked} ${cmd}"
  for f in -Xms12g -Xmx12g -XX:MaxGCPauseMillis=50; do
    [[ "${all}" == *" ${f}"* || "${all}" == *"=${f}"* || "${all}" == *":${f}"* || "${all}" == "${f}"* ]] || fail "${pod}: running JVM lacks ${f} (saw: ${all:0:300})"
  done
  [[ "${all}" =~ -XX:\+Use(Parallel|Serial|Z|Shenandoah|Epsilon)GC ]] && fail "${pod}: a non-G1 collector is selected: ${BASH_REMATCH[0]}"
  [[ -n "${picked}" ]] || echo "    (no 'Picked up JAVA_TOOL_OPTIONS' line in ${pod}'s retained log; read /proc instead)"
  ok "${pod}: 16Gi request = limit; JVM runs -Xms12g -Xmx12g -XX:MaxGCPauseMillis=50 on the default (G1) collector"
done

step "05. Loki holds recent lines from every member"
pf LOKI "${LOKI_TARGET:-svc/loki}" 3100
for ((m=0; m<MEMBERS; m++)); do
  pod="order-matcher-cluster-${m}"; n=0
  for i in $(seq 1 12); do
    n="$(curl -s -m 15 -G "${LOKI}/loki/api/v1/query_range" \
      --data-urlencode "query={namespace=\"${NS}\"} |= \"\"" --data-urlencode "limit=5000" \
      --data-urlencode "start=$(( ($(date +%s) - 1800) * 1000000000 ))" \
      | jget "sum(len(s['values']) for s in d['data']['result'] if any('${pod}' in str(v) for v in s['stream'].values()))")"
    [[ "${n:-0}" -gt 0 ]] && break; sleep 10
  done
  [[ "${n:-0}" -gt 0 ]] || fail "Loki has no line from ${pod} in the last 30 min (promtail not on the engine nodes, or not shipping)"
  ok "${pod}: ${n} line(s) in Loki"
done

step "04. the kubelet's SIGTERM ends a follower with exit 143 inside its grace period, and it rejoins"
if [[ "${DESTRUCTIVE:-0}" != 1 ]]; then
  echo "[skip] DESTRUCTIVE=1 not set; this step restarts one member"
else
  leader="" ; follower=""
  for ((m=0; m<MEMBERS; m++)); do
    role="$(mget "${m}" /health | jget 'd.get("role")')"
    [[ "${role}" == LEADER ]] && leader="${m}" || { [[ -z "${follower}" ]] && follower="${m}"; }
  done
  [[ -n "${leader}" && -n "${follower}" ]] || fail "could not find a leader and a follower from /health role"
  pod="order-matcher-cluster-${follower}"
  grace="$("${K[@]}" get pod "${pod}" -o jsonpath='{.spec.terminationGracePeriodSeconds}')"
  # THE KUBELET'S PATH, NOT A HAND-SENT SIGNAL: delete the pod and watch its container's terminal
  # state. The kubelet SIGTERMs the container's PID 1 and SIGKILLs at the grace deadline, so 143
  # means the member exited on SIGTERM and 137 means it had to be killed. If PID 1 is a wrapper
  # shell that neither handles nor forwards SIGTERM, the JVM never sees it and this reads 137.
  pid1="$("${K[@]}" exec -c "${C}" "${pod}" -- sh -c 'tr "\0" " " < /proc/1/cmdline; echo; grep SigCgt /proc/1/status' 2>/dev/null)"
  echo "    ${pod} PID 1: ${pid1//$'\n'/ | }"
  watch="$(mktemp)"
  "${K[@]}" get pod "${pod}" -w -o json > "${watch}" 2>/dev/null & wpid=$!
  sleep 2; t0="$(date -u +%s)"
  "${K[@]}" delete pod "${pod}" --wait=true >/dev/null
  sleep 1; kill "${wpid}" 2>/dev/null
  term="$(python3 - "${watch}" "${C}" <<'PY'
import sys,json
raw=open(sys.argv[1]).read(); dec=json.JSONDecoder(); i=0; last=None
while True:
    rest=raw[i:].lstrip()
    if not rest: break
    o,n=dec.raw_decode(rest); i=len(raw)-len(rest)+n
    for c in o['status'].get('containerStatuses',[]):
        if c['name']==sys.argv[2] and 'terminated' in c.get('state',{}): last=c['state']['terminated']
print(json.dumps(last) if last else '')
PY
)"
  rm -f "${watch}"
  [[ -n "${term}" ]] || fail "${pod}: the watch never saw the container terminate"
  ec="$(jget 'd.get("exitCode")' <<<"${term}")"
  fin="$(jget 'd.get("finishedAt")' <<<"${term}")"
  t1="$(python3 -c "import datetime;print(int(datetime.datetime.fromisoformat('${fin}'.replace('Z','+00:00')).timestamp()))")"
  echo "    follower ${follower}: exit ${ec}, finished $((t1 - t0))s after the delete (grace ${grace}s)"
  [[ "${ec}" == 143 ]] || fail "${pod} ended with exit ${ec} on the kubelet's SIGTERM (want 143; 137 = SIGKILLed at the ${grace}s grace deadline)"
  (( t1 - t0 <= grace )) || fail "${pod} took $((t1 - t0))s, beyond its ${grace}s grace period"
  for i in $(seq 1 90); do [[ "$("${K[@]}" get pod "${pod}" -o jsonpath='{.status.phase}' 2>/dev/null)" == Running ]] && break; sleep 2; done
  for i in $(seq 1 180); do
    a_f="$(member_applied "${follower}")"; a_l="$(member_applied "${leader}")"
    [[ -n "${a_f}" && -n "${a_l}" && "${a_f}" -ge "${a_l}" ]] && break; sleep 2
  done
  [[ -n "${a_f}" && "${a_f}" -ge "${a_l}" ]] || fail "${pod} did not catch up (applied ${a_f:-?} vs leader ${a_l:-?})"
  ok "exit 143 in $((t1 - t0))s <= ${grace}s; rejoined at applied ${a_f} (leader ${a_l})"
fi

echo; echo "YU18-MEMBER-RUNTIME PASS destructive=${DESTRUCTIVE:-0}"
