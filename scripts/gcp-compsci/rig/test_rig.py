"""ADR-096 rig lifecycle, offline: the real rig.sh functions with kubectl and gcloud stubbed as bash functions that
record every call. Checks the order of operations and that every gate refusal leaves the gateways at 0."""
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

RIG = Path(__file__).with_name('rig.sh')

STUBS = r'''
set -euo pipefail
RIG_LIB=1 . "$RIG"
DRY=0; mkdir -p "$EVIDENCE"
call(){ echo "$*" >> "$LOG"; }
sleep(){ :; }
preflight(){ :; }; require_pools(){ :; }
pool_ready(){ true; }; sql_ready(){ true; }; ready_replicas(){ true; }; deploy_rolled_out(){ true; }; no_pods(){ true; }; ilb_has_ip(){ true; }; hpa_exists(){ false; }
pool_autoscaling(){ false; }; no_rig_instances(){ true; }; vm_status_is(){ true; }
snapshots_past(){ true; }
initialize(){ call "initialize $1"; [ "${INIT_FAIL:-}" != "$1" ]; }
admin_token(){ local n; n=$(( $(cat "$EVIDENCE/.tok" 2>/dev/null || echo 0) + 1 )); echo $n > "$EVIDENCE/.tok"; echo "tok$n"; }
# TOKEN_SINGLE_USE: a token already presented has expired (a strict replay outlasts the 600 s TTL), so the member answers 401
expired(){ local t; read -r t || t=''; [ -n "${TOKEN_SINGLE_USE:-}" ] || return 1
  grep -qx "$t" "$EVIDENCE/.spent" 2>/dev/null && return 0; echo "$t" >> "$EVIDENCE/.spent"; return 1; }
member(){ local v="M$1"; echo "${!v:-$M}"; }
# Archive listings with state (FR-PRG-39): each starts from its env (unset = epoch e2's archive only), and a delete
# removes what it names, so a retry inside one run sees what the first attempt left.
state(){ local f="$EVIDENCE/.$1"; [ -f "$f" ] || printf '%s\n' $2 > "$f"; grep . "$f" || true; }
unstate(){ local f="$EVIDENCE/.$1" n; shift; for n in "$@"; do grep -vx "$n" "$f" > "$f.t" || true; mv "$f.t" "$f"; done; }
SQL_ARC=${SQL_ARC-arc_e2_trades arc_e2_positions}; CH_ARC_TABLES=${CH_ARC_TABLES-arc_e2_trades}; SNAP_LIST=${SNAP_LIST-traderx-e2-m0 traderx-e2-m1 traderx-e2-m2}
read_model(){ [ -n "${RM_FAIL:-}" ] && return 1; if [ -f "$EVIDENCE/.rm" ]; then echo "$RM_AFTER"; else touch "$EVIDENCE/.rm"; echo "$RM"; fi; }
kubectl(){
  call "kubectl $*"
  case "$*" in
    *RECON_FULL_HISTORY_MAX*) echo "${CAP:-200000}";;
    *"get pods -l app=nats -o jsonpath"*) echo u1;;
    *"get pods -l app=nats -o json"*) [ -n "${NATS_LIST_FAIL:-}" ] && return 1; echo "${NATS_PODS:-$NATS_OK}";;
    *"get configmap rig-nats"*) echo "${NATS_UID-u1}";;
    *"get nodes -l cloud.google.com/gke-nodepool=gateway-pool -o name"*)
      [ -n "${NODES_FAIL:-}" ] && { echo "Error from server: etcdserver: request timed out" >&2; return 1; }; printf 'node/%s\n' ${GW_NODES:-n1 n2};;
    *"--field-selector spec.nodeName="*)   # GW_ON_<node>: the gateway pod on it; POD_LIST_FAIL: the node whose listing fails
      n="$*"; n=${n##*spec.nodeName=}; n=${n%% *}; [ "$n" = "${POD_LIST_FAIL:-}" ] && { echo "API unavailable" >&2; return 1; }
      v="GW_ON_$n"; [ -z "${!v:-}" ] || echo "pod/${!v}";;
    *reindex*) [ -n "${REINDEX_FAIL:-}" ] && return 8; expired && return 8; echo "$REINDEX";;
    *"orders?status=all"*) cat > "$EVIDENCE/.order-stdin"; echo "$ORDERS";;   # the order read model
    *"8222/jsz"*) [ -n "${FEED_FAIL:-}" ] && return 1   # FEED: "<stream>:<messages> ..." on NATS, unset = none
      jq -cn --arg f "${FEED:-}" '{streams: 1, account_details: [{stream_detail: [$f | splits(" ") | select(. != "")
        | split(":") | {name: .[0], state: {messages: (.[1] | tonumber)}}]}]}';;
    *"nc localhost 4222"*) echo 'INFO {"server_id":"x"}'; echo 'MSG r 1 40'; echo '{"type":"stream_delete_response","success":true}';;
    *"get sts clickhouse"*) [ -z "${HIST_ABSENT:-}" ] && echo statefulset.apps/clickhouse;;   # HIST_ABSENT: no history store rendered
    *"annotations.traderx-rig-held-replicas"*)   # HELD_<deploy>: a count an earlier (refused) run recorded on it
      n="$*"; d=${n#*get deploy }; d=${d%% *}; v="HELD_${d//-/_}"
      if [ -f "$EVIDENCE/.held-$d" ]; then cat "$EVIDENCE/.held-$d"; else echo "${!v:-}"; fi;;
    *"annotate deploy "*) n="$*"; d=${n#*annotate deploy }; d=${d%% *}; r=${n#*traderx-rig-held-replicas}
      case $r in -*) rm -f "$EVIDENCE/.held-$d"; touch "$EVIDENCE/.held-$d";; =*) r=${r#=}; echo "${r%% *}" > "$EVIDENCE/.held-$d";; esac;;
    *"get deploy history-feed -o jsonpath={.spec.replicas}"*) echo "${HF_SPEC:-1}";;
    *"get deploy history-feed"*) [ -n "${HIST_ABSENT:-}" ] || echo deployment.apps/history-feed;;
    *"get configmap -o json"*) [ -n "${HISTORY_FAIL:-}" ] && return 1   # HISTORY: "<label>=<created> ..." history records
      jq -cn --arg h "${HISTORY-e1=2026-11-01T01:00:00Z e2=2026-11-02T01:00:00Z}" '{items: [{metadata: {name: "rig-epoch"}},
        ($h | splits(" ") | select(. != "") | split("=") | {metadata: {name: "rig-epoch-\(.[0])-history", creationTimestamp: .[1]}})]}';;
    *clickhouse-client*"system.tables"*) [ -n "${CH_LIST_FAIL:-}" ] && return 1; state ch-arc "$CH_ARC_TABLES";;
    *clickhouse-client*"DROP TABLE IF EXISTS "*) [ -n "${CH_DROP_FAIL:-}" ] && return 1; n="$*"; n=${n#*IF EXISTS }; unstate ch-arc "${n%% *}";;
    *clickhouse-client*) case "$*" in   # CH_LIVE / CH_ARC: whether the live / archived history tables exist
      *"EXISTS TABLE arc_"*) echo "${CH_ARC:-0}";; *"EXISTS TABLE"*) echo "${CH_LIVE:-1}";;
      *"count() FROM arc_"*) echo 81140;; *"count() FROM"*) echo "${CH_ROWS:-0}";; esac;;
    *"history-feed:18095/health"*) echo "$HIST_HEALTH";;
    *"clickhouse:8123"*) cat >/dev/null; [ -n "${HIST_FAIL:-}" ] && return 7   # HIST: per-partition check rows;
      t=$(member 0 | cut -d' ' -f3)                                            # unset: sides 1..trades, each once
      printf '%b\n' "${HIST:-0\t$t\t$t\t$((t * (t + 1) / 2))\t$((t * (t + 1) * (2 * t + 1) / 6))\t0}";;
    *TRADE_PROJECTION_MODE*) [ -n "${TP_MODE_FAIL:-}" ] && return 1; echo "${TP_MODE:-}";;
    *projection-feed/accounting*) [ -n "${ACCT_FAIL:-}" ] && return 22   # ACCT_FAIL_N: refused (exit 7) the first N reads
      n=$(( $(cat "$EVIDENCE/.acct" 2>/dev/null || echo 0) + 1 )); echo $n > "$EVIDENCE/.acct"
      [ "$n" -gt "${ACCT_FAIL_N:-0}" ] || return 7; echo "$ACCT";;
    *"get configmap rig-snapshot-base"*) echo "${SNAP_BASE:-}";;
    *shadow-state*) expired || echo "${SHADOW:-}";;
    *"health?digest=1"*) echo "$DIGEST";;
    *"from orderbook where status"*) echo "$OPEN_ROWS";;
    *"from trades where right(id,2)"*) printf '%s\n' "$TRADE_ROWS";;
    *"get configmap rig-shutdown-baseline"*)   # BASELINE_ABSENT = NotFound; BASELINE_FAIL = any other error
      [ -n "${BASELINE_FAIL:-}" ] && { echo "Error from server (ServiceUnavailable): the server is currently unable to handle the request" >&2; return 1; }
      [ -z "${BASELINE_ABSENT:-}" ] || { echo 'Error from server (NotFound): configmaps "rig-shutdown-baseline" not found' >&2; return 1; }
      jq -cn --arg m "$BASELINE" '{data: ({members: $m} | if $m == "" then {} else . end)}';;
    *"get configmap rig-unprojected"*) echo "${UNPROJECTED:-}";;
    *"get configmap rig-epoch -o json"*)   # EPOCH="<state> <label>"; unset = NotFound; EPOCH_FAIL = any other error
      [ -n "${EPOCH_FAIL:-}" ] && { echo "Error from server (ServiceUnavailable): unavailable" >&2; return 1; }
      e=${EPOCH:-}; [ -f "$EVIDENCE/.epoch" ] && e=$(cat "$EVIDENCE/.epoch")   # what set_epoch last wrote
      [ -n "$e" ] || { echo 'Error from server (NotFound): configmaps "rig-epoch" not found' >&2; return 1; }
      jq -cn --arg s "${e% *}" --arg l "${e#* }" '{data: {state: $s, label: $l}}';;
    *"create configmap rig-epoch --from-literal=label="*)
      n="$*"; l=${n#*label=}; l=${l%% *}; st=${n#*state=}; echo "${st%% *} $l" > "$EVIDENCE/.epoch"; echo 'kind: ConfigMap';;
    *"get pvc -o name"*) printf '%s\n' ${CLAIMS:-};;
    *"get pvc data-order-matcher-cluster-"*volumeName*) n="$*"; n=${n#*cluster-}; echo "pv-${n%% *}";;
    *"get pv pv-"*volumeHandle*) n="$*"; n=${n#*pv-}; echo "projects/p/zones/us-east1-b/disks/disk-${n%% *}";;
    *"get deploy trade-processor -o jsonpath={.spec.replicas}"*) echo "${TP_SPEC:-1}";;
    *"left(table_name,4)='arc_'"*) [ -n "${SQL_LIST_FAIL:-}" ] && return 1; state sql-arc "$SQL_ARC";;
    *"from information_schema.referential_constraints where"*) echo "${ARC_REFS:-}";;
    *"DROP TABLE IF EXISTS "*) [ -n "${SQL_DROP_FAIL:-}" ] && return 1; n="$*"; n=${n#*IF EXISTS }; n=${n%%;*}; unstate sql-arc ${n//,/ }; echo 1;;
    *"count(*) from information_schema.tables"*) echo "${ARC_EXISTS:-0}";;
    *"from information_schema.tables"*) printf '%s\n' ${PRESENT:-trades positions orderbook order_view_snapshot projection_accounting projection_cursor projection_trade_days projection_recon};;
    *"select count(*) from arc_"*) echo 42;;
    *"select count(*) from "*) echo "${LIVE_ROWS:-0}";;
    *"get configmap rig-epoch-"*"-history"*disks*) echo "disk-0 disk-1 disk-2";;
    *"get sts order-matcher-cluster -o jsonpath={.spec.template.spec.containers[0].env[*].name}"*) echo "${MEMBER_ENV:-JAVA_TOOL_OPTIONS TRADE_BRIDGE_NATS_URL}";;
    *"get pvc data-order-matcher-cluster-"*"requests.storage"*) echo "${PVC_SIZE:-32Gi}";;
    *"get storageclass order-matcher-archive"*) echo "${SC_EXPAND:-}";;
    *"create configmap"*) echo 'kind: ConfigMap';;
    *"apply -f -"*) cat >/dev/null;;
    *"get pods -l app=cluster-gateway -o jsonpath"*) for i in $(seq 1 "$GATEWAYS"); do echo "node-$i"; done;;
    *"--input-type=module - "*) [ -n "${BDAY_FAIL:-}" ] && { echo 'BUSINESS-DAY 401 {"error":"operator credentials required"}'; return 1; }
      echo 'BUSINESS-DAY 200 {"outcome":"APPLIED"}';;
    *"get pods -l app=loadgen"*) printf 'pod/%s\n' ${LG_PODS:-lg-a lg-b};;
    *"localhost:8080/bbo"*) echo '{"books":[{"ticker":"MSFT","mark":412.5,"ref":400}]}';;
    *'grep -q "sent=[1-9]"'*) [ -z "${LG_SILENT:-}" ];;
    *"/proc/[0-9]*"*kill*) call "LG-KILL";;
    *"cat /proc/"*|*"/proc/[0-9]*"*) [ -f "$EVIDENCE/.lg-$(n="$*"; n=${n#*exec }; echo "${n%% *}")" ] && echo 4242 || true;;
    *"nohup node /load/binary-load.mjs"*) n="$*"; n=${n#*exec }; [ -n "${LG_DEAD:-}" ] || touch "$EVIDENCE/.lg-${n%% *}";;
    *"web-front-end-console"*) [ -n "${SMOKE_FAIL:-}" ] && { echo "FAIL refused: 58=Invalid Logon message"; return 1; }; printf 'NEW 7 MSFT @ 1\nCANCELED 7\nOK\n';;
    *"get sts order-matcher-cluster -o jsonpath={.status.replicas}"*) echo "${STS_REPLICAS:-0}";;
    *"get sts order-matcher-cluster -o jsonpath={.spec.replicas}"*) echo "${STS_SPEC:-0}";;
    *"containers[*].name}"*) [ -n "${CONTAINERS_FAIL:-}" ] && return 1; echo "cluster-node${PURGE:+ archive-purge}";;   # PURGE: rendered
    *"-- touch /data/archive-purge.hold"*|*"-- rm -f /data/archive-purge.hold"*) [ -z "${HOLD_FAIL:-}" ];;
    *tail-state*) expired || echo "${TAIL_STATE:-$TAIL_OK}";;
    *"get sts order-matcher-cluster -o jsonpath={.spec"*) echo member-image;;
    *"get sts order-matcher-cluster -o jsonpath"*) echo 3;;
    *"get pod rig-errlog-"*phase*) echo "$ERRLOG_PHASE";;
    *"logs rig-errlog-"*) [ -n "${ERRLOG_FETCH_FAIL:-}" ] && { echo "Error from server: service unavailable" >&2; return 1; }; printf '%s\n' "${ERRLOG%$'\n'}";;
    *"localhost:8080/health"*) a="$*"; [[ "$a" == *"order-matcher-cluster-$LEADER "* ]] && echo '{"role":"LEADER"}' || echo '{"role":"FOLLOWER"}';;
    *"get pods -l app=order-matcher-cluster -w"*)
      i=0; for code in $EXITS; do
        printf '{"object":{"metadata":{"name":"order-matcher-cluster-%s"},"status":{"containerStatuses":[{"name":"cluster-node","state":{"terminated":{"exitCode":%s}}}]}}}\n' $i $code
        i=$((i+1)); done;;
  esac
}
gcloud(){ call "gcloud $*"; case "$*" in *networkIP*) echo 10.0.0.5;;
  *"snapshots describe"*)   # SNAPS: ready | absent | wrong (of another disk) | broken (read error)
    n="$*"; n=${n#*-m}; n=${n%% *}
    case ${SNAPS:-ready} in
      ready) printf 'READY\thttps://compute/projects/p/zones/us-east1-b/disks/disk-%s\n' "$n";;
      wrong) printf 'READY\thttps://compute/projects/p/zones/us-east1-b/disks/other-%s\n' "$n";;
      absent) echo "ERROR: (gcloud.compute.snapshots.describe) The resource 'traderx' was not found" >&2; return 1;;
      broken) echo "ERROR: (gcloud) Reauthentication required" >&2; return 1;;
    esac;;
  *"disks describe"*) return 1;;
  *"snapshots list"*) [ -n "${SNAP_LIST_FAIL:-}" ] && return 1; state snaps "$SNAP_LIST";;
  *"snapshots delete"*) [ -n "${SNAP_DELETE_FAIL:-}" ] && return 1; n="$*"; n=${n#*snapshots delete }; n=${n%% --*}; unstate snaps $n;;
  esac; }
'''

def nats_pods(*pods):   # (uid, phase, restarts, last reason)
    import json
    return json.dumps({'items': [{'metadata': {'name': f'nats-{u}', 'uid': u}, 'status': {'phase': ph, 'containerStatuses': [
        {'name': 'nats', 'restartCount': r, 'lastState': {'terminated': {'reason': why, 'exitCode': 137,
                                                                         'finishedAt': '2026-10-30T19:05:00Z'}} if r else {}}]}}
        for u, ph, r, why in pods]})


GOOD = {'NATS_OK': nats_pods(('u1', 'Running', 0, '')), 'HIST_HEALTH': '{"ready":true,"unowned":{},"lanes":[{"pending":0},{"pending":0}]}', 'GATEWAYS': '3', 'AUTOSCALE': 'off', 'NS': 'traderx', 'M': '100 100 5 11 22 7', 'RM': 'trades=a', 'RM_AFTER': 'trades=a',
        'REINDEX': '{"replayedAppliedSeq":100,"indexedTrades":5}',
        'SHADOW': '{"applied":100,"complete":true,"bookHash":"11","positionHash":"22","openOrders":2,"positions":3,"digestTrades":5}',
        'DIGEST': '{"bookHash":"11","positionHash":"22","openOrders":2,"positions":3,"digestTrades":5}', 'OPEN_ROWS': '2', 'TRADE_ROWS': '5\t5\t5\t0',
        'LEADER': '1', 'EXITS': '143 143 143', 'ERRLOG_PHASE': 'Succeeded', 'BASELINE': '100 100 5 11 22 7',
        'ERRLOG': 'mark files in /data/cluster: consensus=1 service=1\nerrlog complete'}

# The snapshot read model's committed accounting for trades = 5: sides 1,3,5 on partition 0 and 2,4 on partition 1 (sums of
# seq and seq^2 as the trade store writes them), one open order on each, every cursor at its subject's last message.
def acct(p0=(3, 5, 9, 35, 0), p1=(2, 4, 6, 20, 0), resting=(1, 1), orders=True):
    import json
    t = {str(i): {'cursor': 50, 'sides': s, 'maxSeq': m, 'sumSeq': str(a), 'sumSq': str(b), 'duplicates': d, 'epoch': '',
                  'streamLast': 40} for i, (s, m, a, b, d) in enumerate((p0, p1))}
    o = {str(i): {'cursor': 60, 'open': r, 'resting': r, 'streamLast': 55} for i, r in enumerate(resting)}
    return json.dumps({'scope': 'legacy-unknown', 'partitions': 2, 'trades': t, **({'orders': o} if orders else {})})


GOOD['ACCT'] = acct()
# FR-PRG-40: /recon/tail-state for the members in GOOD, 40 session messages replayed after the oldest retained snapshot
TAIL = {'applied': 100, 'complete': True, 'bookHash': '11', 'positionHash': '22', 'openOrders': 2, 'positions': 3, 'digestTrades': 5,
        'replayedMessages': 40, 'snapshotLogPosition': 4096, 'inconclusive': False}
GOOD['TAIL_OK'] = __import__('json').dumps(TAIL)
GOOD['ORDERS'] = '[{"id":"om-7","status":"CANCELED"}]'   # the smoke order (NEW 7), cancelled, in the order read model   # rows mode was retired (FR-THT-01): unset TRADE_PROJECTION_MODE is the snapshot read model


def run(body, **env):
    with tempfile.TemporaryDirectory() as d:
        e = {**os.environ, **GOOD, **env, 'RIG': str(RIG), 'EVIDENCE': d + '/ev', 'LOG': d + '/calls'}
        p = subprocess.run(['bash', '-c', STUBS + body], env=e, capture_output=True, text=True, timeout=60)
        calls = Path(d, 'calls').read_text().splitlines() if Path(d, 'calls').exists() else []
        return p.returncode, p.stdout + p.stderr, calls


def first(calls, pattern):
    return next((i for i, c in enumerate(calls) if re.search(pattern, c)), None)


SCALE_UP = r'scale deploy/cluster-gateway --replicas=[1-9]'


class Up(unittest.TestCase):
    def test_gateways_start_only_after_the_gate_passes(self):
        rc, out, calls = run('rig_up')
        self.assertEqual(rc, 0, out)
        order = [first(calls, p) for p in (r'scale deploy/cluster-gateway --replicas=0', r'clusters resize .*engine-pool',
                                           r'clusters resize .*history-pool --num-nodes 1',
                                           r'scale statefulset/order-matcher-cluster --replicas=3', r'create configmap rig-gate',
                                           SCALE_UP, r'networkInterfaces\[0\]\.networkIP', r'web-front-end-console')]
        self.assertNotIn(None, order, out)
        self.assertEqual(order, sorted(order), out)
        self.assertIn('G4 ok: equals the shutdown baseline', out)
        self.assertIn('ok: sts/clickhouse Ready (the history store, FR-THT-02)', out)   # FR-THT-02: part of readiness
        self.assertIn('G3h ok: the history store holds trade sides 1..5, each once', out)

    # FR-PRG-04 (production 2026-11-07): ORDER_FEED_PERSIST=false never writes SQL orderbook, so the smoke waited for a row
    # that cannot exist and refused after a clean New/Canceled. The effect end is trade-processor's order read model.
    def test_the_smoke_reads_the_cancel_from_the_order_read_model(self):
        rc, out, calls = run('rig_up')
        self.assertEqual(rc, 0, out)
        self.assertIn('ok: order 7 CANCELED in the order read model (FR-PRG-04)', out)
        self.assertIsNone(first(calls, r'from orderbook where orderid'))
        self.assertTrue(any('orders?status=all' in c and 'tok' not in c for c in calls))   # the bearer never on argv
        for orders, why in (('[{"id":"om-7","status":"NEW"}]', 'still open'), ('[{"id":"om-17","status":"CANCELED"}]', 'another order'),
                            ('[]', 'absent')):
            rc, out, _ = run('wait_for(){ "${@:3}" >/dev/null 2>&1 || die "timed out waiting for: $2"; note "ok: $2"; }; rig_up', ORDERS=orders)
            self.assertEqual(rc, 2, why); self.assertIn('order 7 CANCELED in the order read model', out, why)

    def test_the_default_render_one_partition_memory_only_orders_passes_g3_and_the_smoke(self):
        # The default render as production e1107 ran it: one partition, ORDER_FEED_PERSIST=false with the 5 s order-view
        # snapshot. G3 reads the accounting's single partition and the snapshot's open orders; the smoke reads the view.
        import json
        one = json.dumps({'scope': 'legacy-unknown', 'partitions': 1,
                          'trades': {'0': {'cursor': 50, 'sides': 5, 'maxSeq': 5, 'sumSeq': '15', 'sumSq': '55', 'duplicates': 0,
                                           'epoch': '', 'streamLast': 40}},
                          'orders': {'0': {'cursor': 60, 'open': 2, 'resting': 2, 'streamLast': 55}}})
        rc, out, _ = run('rig_up', TP_MODE='snapshot', ACCT=one)
        self.assertEqual(rc, 0, out)
        self.assertIn('G3 ok: the read model booked sides 1..5, each once; 2 open orders', out)
        self.assertIn('ok: order 7 CANCELED in the order read model (FR-PRG-04)', out)
        no_orders = json.dumps({**json.loads(one), 'orders': None}).replace(', "orders": null', '')
        self.assertIn('no order view snapshot', self.refused('G3', TP_MODE='snapshot', ACCT=no_orders))

    def test_pools_creates_the_history_pool_tainted_for_the_store(self):
        rc, out, _ = run('DRY=1; rig_pools')
        self.assertEqual(rc, 0, out)
        self.assertRegex(out, r'would: gcloud container node-pools create history-pool .*--machine-type c4d-standard-8 '
                              r'.*--disk-type hyperdisk-balanced .*--node-taints traderx-history=true:NoSchedule')

    def refused(self, gate, **env):
        rc, out, calls = run('rig_up', **env)
        self.assertEqual(rc, 2, out)
        self.assertIn(f'REFUSED', out)
        self.assertIn(gate, out)
        self.assertIsNone(first(calls, SCALE_UP), 'a gateway started after a refused gate')
        self.assertIsNone(first(calls, 'create configmap rig-gate'))
        return out

    def test_members_that_disagree_are_refused(self):
        self.refused('G0', M2='100 100 5 11 23 7')

    def test_a_cold_restore_with_no_engine_event_since_passes_g0_and_g4(self):
        # Production 2026-10-29: restored from snapshots into an idle cluster, every member reported engineApplied -1.
        rc, out, _ = run('rig_up', M='100 -1 5 11 22 7')
        self.assertEqual(rc, 0, out)
        self.assertIn('G0 ok', out)
        rc, out, _ = run('rig_up', M='100 -1 5 11 22 7', BASELINE='100 100 5 11 22 7')   # baseline taken while live
        self.assertEqual(rc, 0, out)
        self.assertIn('G4 ok', out)

    def test_an_engine_behind_the_log_is_refused(self):
        self.refused('G0', M='100 99 5 11 22 7')
        self.refused('G0', M2='100 -1 5 11 22 7')   # one member restored idle, the others not: they disagree

    def test_a_replay_from_zero_that_differs_is_refused(self):
        self.refused('G1', REINDEX='{"replayedAppliedSeq":100,"indexedTrades":4}')

    def test_over_the_index_cap_g1_defers_to_g2_and_never_calls_the_capped_index(self):
        rc, out, calls = run('rig_up', CAP='4', REINDEX='{"error":"exceeded"}')   # trades 5 > cap 4
        self.assertEqual(rc, 0, out); self.assertIn('G1 deferred to G2', out); self.assertIn('G2 ok', out)
        self.assertIsNone(first(calls, r'reindex'))
        self.refused('G1', CAP='5', REINDEX='{"replayedAppliedSeq":100,"indexedTrades":4}')   # at the cap G1 still runs

    def test_a_deferred_g1_never_lets_g2_be_skipped(self):
        self.refused('G2', CAP='4', SHADOW='', GATE_ALLOW_NO_SHADOW='1', NS='traderx-side')

    def test_a_failed_replay_request_is_a_named_refusal(self):
        self.refused('G1', REINDEX_FAIL='1')

    def test_a_restored_state_that_differs_from_the_replay_is_refused(self):
        good = GOOD['SHADOW']
        for field, value in (('"bookHash":"11"', '"bookHash":"12"'), ('"positions":3', '"positions":4'),
                             ('"openOrders":2', '"openOrders":1')):
            self.refused('G2', SHADOW=good.replace(field, value))

    def test_each_members_replay_request_carries_a_token_minted_for_it(self):
        # FR-PRG-25: one token minted up front expired before member 2 was asked (production 2026-10-30, G2 refused)
        for gate in ({}, {'CAP': '4'}):   # G1 runs; G1 defers to G2
            rc, out, _ = run('rig_up', TOKEN_SINGLE_USE='1', **gate)
            self.assertEqual(rc, 0, out); self.assertIn('G2 ok', out)

    def test_an_incomplete_replay_is_refused(self):
        self.refused('G2', SHADOW=GOOD['SHADOW'].replace('"complete":true', '"complete":false'))

    def test_restored_open_orders_that_the_read_model_lacks_are_refused(self):
        self.refused('G3', ACCT=acct(resting=(1, 0)))

    def test_without_bridges_the_smoke_ends_at_the_fix_answers(self):
        rc, out, calls = run('rig_up', MEMBER_ENV='JAVA_TOOL_OPTIONS CLUSTER_EGRESS_COALESCE')
        self.assertEqual(rc, 0, out)
        self.assertIn('members run without bridges (FR-PRG-14)', out)
        rc, out, _ = run('rig_up')   # with bridges the read-model row is still required
        self.assertIn('CANCELED in the order read model (FR-PRG-04)', out)

    def test_a_failed_smoke_script_is_refused_with_its_output_kept(self):
        # Production 2026-10-29: the script exited 1 under set -e with no REFUSED line and no evidence.
        rc, out, _ = run('rig_up', SMOKE_FAIL='1')
        self.assertEqual(rc, 2, out)
        self.assertIn('REFUSED', out)
        self.assertIn('smoke: the FIX script failed', out)

    def test_the_smoke_script_against_a_fake_acceptor(self):
        # The real fix-smoke.mjs through Logon, New, Cancel and Logout. The acceptor refuses a bad SendingTime(52) and
        # answers the Logout, as production's gateway did on 2026-10-29.
        import http.server, socket, threading
        SOH = '\x01'
        def frame(fields):
            body = SOH.join(f'{k}={v}' for k, v in fields) + SOH
            head = f'8=FIX.4.4{SOH}9={len(body)}{SOH}'
            return (head + body + f'10={sum((head + body).encode()) % 256:03d}{SOH}').encode()
        def acceptor(srv, reject_logon):
            conn, _ = srv.accept(); buf = b''
            while True:
                data = conn.recv(4096)
                if not data: return
                buf += data
                while (e := buf.find(b'\x0110=')) >= 0 and (cut := buf.find(b'\x01', e + 1)) >= 0:
                    m, buf = buf[:cut + 1].decode(), buf[cut + 1:]
                    f = dict(kv.split('=', 1) for kv in m.split(SOH) if kv)
                    if not re.fullmatch(r'\d{8}-\d\d:\d\d:\d\d\.\d{3}', f['52']) or reject_logon:
                        conn.sendall(frame([('35', '5'), ('58', 'Invalid Logon message')])); return
                    reply = {'A': [('35', 'A')], 'D': [('35', '8'), ('11', f.get('11')), ('39', '0'), ('37', 'ord-7')],
                             'F': [('35', '8'), ('11', f.get('11')), ('39', '4'), ('37', 'ord-7')], '5': [('35', '5')]}[f['35']]
                    conn.sendall(frame(reply))
                    if f['35'] == '5': return
        class Price(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200); self.end_headers(); self.wfile.write(b'{"price": 100.0}')
            def log_message(self, *a): pass
        http_srv = http.server.HTTPServer(('127.0.0.1', 0), Price)
        threading.Thread(target=http_srv.serve_forever, daemon=True).start(); self.addCleanup(http_srv.shutdown)
        for reject, code, want in ((False, 0, ['NEW 7 MSFT @ 98.00', 'CANCELED 7', 'OK']), (True, 1, ['FAIL refused'])):
            srv = socket.create_server(('127.0.0.1', 0)); self.addCleanup(srv.close)
            threading.Thread(target=acceptor, args=(srv, reject), daemon=True).start()
            r = subprocess.run(['node', '-', '42422', 'MSFT'], stdin=RIG.with_name('fix-smoke.mjs').open(), capture_output=True,
                               text=True, timeout=30, env={**os.environ, 'FIX_SESSION_PASSWORD': 'not-printed-pw',
                               'FIX_HOST': '127.0.0.1', 'FIX_PORT': str(srv.getsockname()[1]),
                               'PRICE_URL': f'http://127.0.0.1:{http_srv.server_address[1]}'})
            self.assertEqual(r.returncode, code, r.stdout + r.stderr)
            for line in want: self.assertIn(line, r.stdout)
            self.assertNotIn('not-printed-pw', r.stdout + r.stderr)

    def test_the_smoke_sends_a_fix_sending_time(self):
        # Production 2026-10-29: 52=20261029-174539.540Z was refused as an invalid Logon.
        line = next(l for l in RIG.with_name('fix-smoke.mjs').read_text().splitlines() if l.startswith('const t = '))
        stamp = subprocess.run(['node', '-e', line + '\nconsole.log(t())'], capture_output=True, text=True, check=True).stdout.strip()
        self.assertRegex(stamp, r'^\d{8}-\d\d:\d\d:\d\d\.\d{3}$')

    def test_recording_a_window_appends_and_checks_its_form(self):
        rc, out, calls = run('rig_unprojected 10-20', UNPROJECTED='3-4')
        self.assertEqual(rc, 0, out)
        self.assertIsNotNone(first(calls, r'create configmap rig-unprojected --from-literal=ranges=3-4 10-20'))
        for bad in ('20-10', '0-5', '5', 'a-b'):
            rc, out, _ = run(f'rig_unprojected {bad}')
            self.assertEqual(rc, 2, (bad, out))

    def test_a_managed_projection_scope_is_refused_not_guessed(self):
        digest = GOOD['DIGEST'][:-1] + ',"projectionScope":"run_a"}'
        self.assertIn('managed projection scope', self.refused('G3', DIGEST=digest))

    def test_a_build_without_check_a_is_refused_on_production(self):
        self.refused('G2', SHADOW='')
        self.refused('G2', SHADOW='', GATE_ALLOW_NO_SHADOW='1')   # the opt-out never applies to traderx

    def test_the_opt_out_applies_only_to_a_side_namespace(self):
        rc, out, _ = run('rig_up', SHADOW='', GATE_ALLOW_NO_SHADOW='1', NS='traderx-side')
        self.assertEqual(rc, 0, out)
        self.assertIn('G2 SKIPPED on traderx-side', out)

    def test_an_unreadable_read_model_is_refused_not_hashed(self):
        self.refused('G3', RM_FAIL='1')

    def test_the_snapshot_read_model_is_checked_by_its_accounting(self):
        # ADR-097 (FR-PRG-20): no SQL row per side; the committed accounting must be the closed form over sides 1..5.
        rc, out, calls = run('rig_up', TP_MODE='snapshot', SNAP_BASE='0', ACCT=acct(), TRADE_ROWS='0\t0\t0\t0', OPEN_ROWS='0')
        self.assertEqual(rc, 0, out)
        self.assertIn('G3 ok: the read model booked sides 1..5, each once; 2 open orders', out)
        self.assertIsNone(first(calls, r'from trades where right'), 'the row check ran on the snapshot read model')

    def test_a_snapshot_read_model_that_lost_or_doubled_a_side_is_refused(self):
        for bad, why in ((acct(p1=(1, 2, 2, 4, 0)), 'booked 4 sides'),                 # side 4 lost
                         (acct(p1=(2, 4, 6, 20, 1)), 'duplicate'),                     # a copy refused: the feed published twice
                         (acct(p1=(2, 4, 5, 17, 0)), 'want 5'),                        # 1 twice instead of 2 (cross-partition)
                         (acct(p0=(3, 6, 10, 46, 0)), 'above the members trades'),     # a side the members never booked
                         (acct(resting=(1, 0)), 'order view holds 1 open orders, the members restored 2'),
                         (acct(orders=False), 'no order view snapshot')):
            self.assertIn(why, self.refused('G3', TP_MODE='snapshot', SNAP_BASE='0', ACCT=bad))

    def test_the_snapshot_check_starts_at_the_recorded_rows_era_base(self):
        # Sides 1..2 were projected as rows before the switch (rig-snapshot-base 2); the accounting holds 3..5.
        three_to_five = acct(p0=(2, 5, 8, 34, 0), p1=(1, 4, 4, 16, 0))
        rc, out, _ = run('rig_up', TP_MODE='snapshot', ACCT=three_to_five, SNAP_BASE='2')
        self.assertEqual(rc, 0, out)
        self.assertIn('booked sides 3..5', out)
        self.assertIn('booked 3 sides', self.refused('G3', TP_MODE='snapshot', SNAP_BASE='0', ACCT=three_to_five))   # base 0
        rc, out, _ = run('rig_up', TP_MODE='snapshot', SNAP_BASE='0', UNPROJECTED='4-4', ACCT=acct(p1=(1, 2, 2, 4, 0)),  # 4 unprojected
                         HIST='0\\t4\\t5\\t11\\t39\\t0')   # and never in the history store either
        self.assertEqual(rc, 0, out)

    # FR-PRG-22: the dispatch and the snapshot G3 fail closed. Each case has an A/B control in the gate (the guard removed,
    # the test fails).
    def test_an_unreadable_projection_mode_is_refused_never_taken_as_rows(self):
        out = self.refused('G3', TP_MODE_FAIL='1')
        self.assertIn('projection mode is unreadable or unknown', out)

    def test_an_unknown_projection_mode_is_refused(self):
        for mode in ('snapshots', 'ROWS', 'off'):
            self.assertIn('projection mode is unreadable or unknown', self.refused('G3', TP_MODE=mode))

    def test_unset_is_the_snapshot_g3_and_rows_is_refused(self):
        rc, out, calls = run('rig_up', TP_MODE='')
        self.assertEqual(rc, 0, out)
        self.assertIsNone(first(calls, r'from trades where right'), 'a row check ran')
        self.assertIn('projection mode is unreadable or unknown', self.refused('G3', TP_MODE='rows'))   # retired, FR-THT-01

    def test_a_snapshot_g3_without_a_recorded_base_takes_0(self):
        rc, out, _ = run('rig_up', TP_MODE='snapshot', ACCT=acct())   # a read model born in snapshot mode
        self.assertEqual(rc, 0, out)
        three_to_five = acct(p0=(2, 5, 8, 34, 0), p1=(1, 4, 4, 16, 0))   # base 2 would be needed: refused, not guessed
        self.assertIn('booked 3 sides', self.refused('G3', TP_MODE='snapshot', ACCT=three_to_five))

    def test_an_accounting_that_cannot_be_read_is_refused_at_g3(self):
        self.assertIn('the accounting could not be read', self.refused('G3', TP_MODE='snapshot', SNAP_BASE='0', ACCT_FAIL='1'))

    # FR-THT-06 / SC-THT-04: the history store (ClickHouse) per partition: count, max, sums of trade_seq and its square,
    # duplicates. Sides 1,3,5 on partition 0 and 2,4 on partition 1 for the members' trades = 5.
    HIST = '0\\t3\\t5\\t9\\t35\\t0\\n1\\t2\\t4\\t6\\t20\\t0'

    def test_the_history_store_is_checked_against_the_members_trades(self):
        rc, out, _ = run('rig_up', HIST=self.HIST)
        self.assertEqual(rc, 0, out)
        self.assertIn('G3h ok: the history store holds trade sides 1..5, each once', out)
        rc, out, _ = run('rig_up', HIST='0\\t3\\t5\\t9\\t35\\t0', UNPROJECTED='2-2 4-4', ACCT=acct(p1=(0, 0, 0, 0, 0)))
        self.assertEqual(rc, 0, out)                     # 2 and 4 recorded unprojected: never reached the stream

    def test_a_rig_without_the_history_store_is_refused(self):
        # FR-THT-02 renders the store; a namespace without sts/clickhouse is a missing system of record, not a skip.
        self.assertIn('no history store (sts/clickhouse)', self.refused('G3h', HIST_ABSENT='1'))

    def test_a_history_store_with_a_deleted_or_duplicated_row_is_refused(self):
        for rows, why in (('0\\t3\\t5\\t9\\t35\\t0\\n1\\t1\\t2\\t2\\t4\\t0', 'holds 4 sides'),           # side 4 deleted
                          ('0\\t4\\t5\\t14\\t60\\t1\\n1\\t2\\t4\\t6\\t20\\t0', '1 duplicated'),           # side 5 twice
                          ('0\\t3\\t5\\t9\\t35\\t0\\n1\\t2\\t2\\t4\\t8\\t1', '1 duplicated'),             # 4 deleted AND 2 doubled
                          ('0\\t3\\t6\\t10\\t46\\t0\\n1\\t2\\t4\\t6\\t20\\t0', 'above the members trades')):  # a side never booked
            self.assertIn(why, self.refused('G3h', HIST=rows))

    # FR-THT-02 (production 2026-11-07, e1107b): history-feed's lanes filtered /trades.0 and /orders.0 while the members
    # published /trades and /orders; every lane was open with nothing pending and the store held 0 of 6 sides.
    def test_a_stream_subject_no_lane_reads_is_refused(self):
        lanes = '"lanes":[{"pending":0},{"pending":0}]'
        for health, why in (('{"ready":true,"unowned":{"/orders":4416,"/trades":6},' + lanes + '}', 'no history-feed lane reads {"/orders":4416,"/trades":6}'),
                            ('{"ready":true,' + lanes + '}', 'reports no unread-subject check'),
                            ('{"ready":true,"unowned":null,"unownedError":"timeout",' + lanes + '}', 'NATS unreadable: timeout')):
            self.assertIn(why, self.refused('G3h', HIST=self.HIST, HIST_HEALTH=health))

    # FR-THT-10 (production 2026-11-08, e1108b): a 16M-row backlog at 1,500 rows/s timed out the fixed 600 s. The wait is
    # 600 s plus the backlog at 20,000 rows/s; no backlog, or an unreadable health, is the plain 600 s.
    def test_the_history_wait_scales_with_the_backlog(self):
        for health, secs in (('{"lanes":[{"pending":0},{"pending":0}]}', '600'),
                             ('{"lanes":[{"pending":3070000},{"pending":16200000}]}', '1563'),
                             ('{"lanes":[{"pending":-1},{"pending":400000}]}', '620'),
                             ('not json', '600')):
            rc, out, _ = run('history_wait_secs', HIST_HEALTH=health)
            self.assertEqual((rc, out.strip()), (0, secs), health)
        rc, out, _ = run('history_wait_secs', HIST_HEALTH='{"lanes":[{"pending":16200000}]}', HISTORY_DRAIN_FLOOR='200000')
        self.assertEqual(out.strip(), '681')

    def test_an_unreadable_history_store_is_refused(self):
        self.assertIn('the history store could not be read', self.refused('G3h', HIST=self.HIST, HIST_FAIL='1'))

    def test_a_read_model_that_moved_is_refused(self):
        self.refused('G3', RM_AFTER='trades=b')

    def test_a_baseline_that_cannot_be_read_is_refused_not_taken_as_a_first_bring_up(self):
        # FR-PRG-23 (codeX review 2026-10-30 §3): any read error became "no baseline" and the gate recorded PASS.
        out = self.refused('G4', BASELINE_FAIL='1')
        self.assertIn('rig-shutdown-baseline could not be read', out)
        self.assertNotIn('first bring-up', out)
        rc, out, _ = run('rig_up')   # control: a baseline that reads passes
        self.assertEqual(rc, 0, out)
        self.assertIn('G4 ok: equals the shutdown baseline', out)

    def test_a_baseline_that_exists_but_is_empty_or_malformed_is_refused(self):
        for bad in ('', '100 100 5', 'x 100 5 11 22 7'):
            out = self.refused('G4', BASELINE=bad)
            self.assertIn('empty or malformed', out)
        rc, out, _ = run('rig_up', BASELINE='100 -1 5 11 22 7')   # control: well-formed (a baseline taken cold)
        self.assertEqual(rc, 0, out)
        self.assertIn('G4 ok', out)

    def test_the_baseline_shape_accepts_productions_own(self):
        # The live rig-shutdown-baseline on 2026-10-30, a negative position hash and a cold engineApplied included.
        line = '695423942 -1 662922190 0 -4922934033839070311 1'
        rc, out, _ = run(f"m0='{line}'; rig_shutdown_baseline_ok '{line}' && echo SHAPE-OK")
        self.assertEqual(rc, 0, out)
        self.assertIn('SHAPE-OK', out)

    def test_a_restore_that_differs_from_the_shutdown_baseline_is_refused(self):
        self.refused('G4', BASELINE='101 101 5 11 22 7')
        rc, out, _ = run('rig_up', BASELINE='100 100 5 11 22 9')   # snapshot counter differs: not compared
        self.assertEqual(rc, 0, out)

    def test_a_member_that_once_restored_partially_is_refused_before_any_member_starts(self):
        for text in ('io.aeron.cluster.ClusterException: snapshot corrupt: record 7',
                     'snapshot format 15 is NEWER than this reader', 'unknown snapshot record type 99'):
            rc, out, calls = run('rig_up', ERRLOG=text)
            self.assertEqual(rc, 2, out)
            self.assertIn('G-pre: member 0 restored a snapshot partially', out)
            self.assertIsNone(first(calls, r'scale statefulset/order-matcher-cluster --replicas=3'), 'members started')

    def test_an_error_log_whose_fetch_fails_is_refused_and_its_pod_kept(self):
        # FR-PRG-23 (codeX review 2026-10-30 §2): the fetch's error text matched no pattern, so G-pre printed ok.
        rc, out, calls = run('rig_up', ERRLOG_FETCH_FAIL='1')
        self.assertEqual(rc, 2, out)
        self.assertIn("G-pre: member 0's error log could not be fetched", out)
        self.assertIsNone(first(calls, r'delete pod rig-errlog-0 --wait'), 'the pod was deleted before its log was read')
        self.assertIsNone(first(calls, r'scale statefulset/order-matcher-cluster --replicas=3'))
        rc, out, _ = run('rig_up')   # control: the same run with the fetch working passes
        self.assertEqual(rc, 0, out)
        self.assertIn('G-pre ok', out)

    def test_an_empty_or_truncated_error_log_is_refused(self):
        for log in ('', 'mark files in /data/cluster: consensus=1 service=1', 'errlog complete'):
            rc, out, calls = run('rig_up', ERRLOG=log)
            self.assertEqual(rc, 2, (log, out))
            self.assertIn("error log is incomplete", out)
            self.assertIsNone(first(calls, r'scale statefulset/order-matcher-cluster --replicas=3'))

    def test_an_unreadable_error_log_is_refused(self):
        rc, out, calls = run('rig_up', ERRLOG_PHASE='Failed')
        self.assertEqual(rc, 2, out)
        self.assertIn('could not be read', out)
        self.assertIsNone(first(calls, r'scale statefulset/order-matcher-cluster --replicas=3'))

    def test_the_error_logs_are_read_read_only_before_the_members_start(self):
        rc, out, calls = run('rig_up')
        self.assertEqual(rc, 0, out)
        self.assertIn('G-pre ok', out)
        self.assertLess(first(calls, r'get pod rig-errlog-2'), first(calls, r'scale statefulset/order-matcher-cluster --replicas=3'))
        rc, out, _ = run('errlog_pod 1 img')
        self.assertIn('claimName: data-order-matcher-cluster-1, readOnly: true', out)
        self.assertIn('mountPath: /data, readOnly: true', out)

    def test_the_reader_finds_a_refusal_aeron_logged_in_the_service_mark_file(self):
        # The real bytes: Aeron 1.51 writes both mark files, the refusal in the service container's error log only.
        java = Path(subprocess.run(['/usr/libexec/java_home', '-v', '21'], capture_output=True, text=True).stdout.strip(),
                    'bin/java')
        jar = next(Path.home().glob('.gradle/caches/modules-2/files-2.1/io.aeron/aeron-all/1.51.0/*/aeron-all-1.51.0.jar'), None)
        if not java.exists() or jar is None:
            self.skipTest('needs JDK 21 and aeron-all 1.51.0 in the gradle cache')
        def member(message=None):
            d = tempfile.mkdtemp(); self.addCleanup(subprocess.run, ['rm', '-rf', d])
            subprocess.run([str(java), '--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED', '-cp', str(jar), str(RIG.with_name('ErrlogProbe.java')), d] + ([message] if message else []),
                           check=True, capture_output=True)
            return d
        read = lambda d: run(f'sh -c "$ERRLOG_SCRIPT" errlog {d} "$PAST_RESTORE_ERRORS"')
        for message in ('snapshot corrupt: first record type 7, want header', 'unknown snapshot record type: 12',
                        'snapshot format 15 is NEWER than this reader'):
            rc, out, _ = read(member(message))
            self.assertEqual(rc, 0, out)
            self.assertIn('consensus=1 service=1', out)
            self.assertRegex(out, r'cluster-mark-service-0\.dat: .*' + re.escape(message.split(':')[0]))
            rc, gated, calls = run('rig_up', ERRLOG=out)
            self.assertEqual(rc, 2, gated)
            self.assertIn('G-pre: member 0 restored a snapshot partially', gated)
            self.assertIsNone(first(calls, r'scale statefulset/order-matcher-cluster --replicas=3'), 'members started')
        clean = member()   # control: the consensus module's own error, no refusal anywhere
        rc, out, _ = read(clean)
        self.assertEqual(rc, 0, out)
        self.assertNotIn('.dat: ', out)
        rc, gated, _ = run('rig_up', ERRLOG=out)
        self.assertEqual(rc, 0, gated)
        os.remove(Path(clean, 'cluster-mark-service-0.dat'))   # a missing service log is not a clean one
        rc, out, _ = read(clean)
        self.assertEqual(rc, 3, out)

    def test_the_pod_runs_the_reader_on_the_claim(self):
        import yaml
        rc, out, _ = run('errlog_pod 1 img; echo ---; printf "%s" "$ERRLOG_SCRIPT"')
        pod_yaml, script = out.split('\n---\n', 1)
        command = yaml.safe_load(pod_yaml)['spec']['containers'][0]['command']
        self.assertEqual(command[:2], ['sh', '-c'])
        self.assertEqual(command[2].rstrip('\n'), script)
        self.assertEqual(command[3:], ['errlog', '/data/cluster', 'snapshot corrupt|NEWER|unknown snapshot record type'])

    def test_the_error_logs_are_not_read_while_members_run(self):
        rc, out, _ = run('rig_up', STS_REPLICAS='3')
        self.assertEqual(rc, 2, out)
        self.assertIn('G-pre: members are running', out)

    def test_members_are_held_at_zero_before_any_pool_grows(self):
        # Pending members of a StatefulSet left at 3 would start the moment engine-pool has nodes, before G-pre.
        rc, out, calls = run('rig_up')
        self.assertEqual(rc, 0, out)
        self.assertLess(first(calls, r'scale statefulset/order-matcher-cluster --replicas=0'), first(calls, r'clusters resize'))

    def test_a_rerun_on_a_gated_rig_stops_nothing(self):
        rc, out, calls = run('members_gated(){ true; }; rig_up', GATEWAYS='5')
        self.assertEqual(rc, 0, out)
        self.assertIn('holding nothing, re-gating nothing', out)
        for stop in (r'statefulset/order-matcher-cluster --replicas=0', r'deploy/cluster-gateway --replicas=0',
                     r'replicas=3', r'rig-errlog', r'reindex', r'create configmap rig-gate'):
            self.assertIsNone(first(calls, stop), stop)
        self.assertIsNotNone(first(calls, r'scale deploy/cluster-gateway --replicas=5'))

    def test_autoscale_sets_the_minimum_to_the_gateway_count(self):
        rc, out, calls = run('rig_up', AUTOSCALE='on', GATEWAYS='4')
        self.assertEqual(rc, 0, out)
        self.assertIsNotNone(first(calls, r'node-pools update gateway-pool .*--enable-autoscaling --min-nodes 4 --max-nodes 18'))

    def test_gateways_sharing_a_node_are_refused(self):
        body = 'kc(){ if [ "$*" = "get pods -l app=cluster-gateway -o jsonpath={range .items[*]}{.spec.nodeName}{\\"\\n\\"}{end}" ]; ' \
               'then echo node-1; echo node-1; echo node-1; else kubectl --context "$CTX" -n "$NS" "$@"; fi; }; rig_up'
        rc, out, _ = run(body)
        self.assertEqual(rc, 2, out)
        self.assertIn('one per node does not hold', out)


class Purge(unittest.TestCase):
    """FR-PRG-40: with the archive purge rendered the log no longer starts at 0, so G1 and G2 read /recon/tail-state (the
    oldest retained snapshot plus a strict replay of its tail), with the purge held while they read. An empty tail is
    INCONCLUSIVE, never ok; the gate then rests on G4. Without the purge the replay from 0 runs as before."""
    def tail(self, **change):
        import json
        return json.dumps({**TAIL, **change})

    def refused(self, why, **env):
        rc, out, calls = run('rig_up', PURGE='1', **env)
        self.assertEqual(rc, 2, out); self.assertIn('REFUSED', out); self.assertIn(why, out)
        self.assertIsNone(first(calls, SCALE_UP), 'a gateway started after a refusal')
        self.assertIsNone(first(calls, 'rm -f /data/archive-purge.hold'), 'the purge was released after a refusal')
        return out, calls

    def test_a_purged_log_is_gated_by_snapshot_plus_tail_with_the_purge_held(self):
        rc, out, calls = run('rig_up', PURGE='1')
        self.assertEqual(rc, 0, out)
        self.assertIn('G1/G2 ok (snapshot+tail)', out)
        self.assertIsNone(first(calls, 'reindex')); self.assertIsNone(first(calls, 'shadow-state'))
        held = [i for i, c in enumerate(calls) if 'touch /data/archive-purge.hold' in c]
        tails = [i for i, c in enumerate(calls) if 'tail-state' in c]
        released = [i for i, c in enumerate(calls) if 'rm -f /data/archive-purge.hold' in c]
        self.assertEqual((len(held), len(tails), len(released)), (3, 3, 3), out)
        gate = first(calls, 'create configmap rig-gate')
        self.assertLess(max(held), min(tails)); self.assertLess(gate, min(released)); self.assertLess(max(released), first(calls, SCALE_UP))
        self.assertIn('--from-literal=tail=PASS', calls[gate])
        for c in ('order-matcher-cluster-0', 'order-matcher-cluster-2'):
            self.assertTrue(any(c in x and 'touch /data/archive-purge.hold' in x for x in calls), c)

    def test_an_empty_tail_is_inconclusive_never_ok_and_the_gate_rests_on_g4(self):
        empty = self.tail(replayedMessages=0, inconclusive=True)
        rc, out, calls = run('rig_up', PURGE='1', TAIL_STATE=empty)
        self.assertEqual(rc, 0, out)
        self.assertIn('G1/G2 INCONCLUSIVE (not ok)', out); self.assertNotIn('G1/G2 ok', out); self.assertIn('G4 ok', out)
        self.assertIn('--from-literal=tail=INCONCLUSIVE', calls[first(calls, 'create configmap rig-gate')])
        self.refused('G4', TAIL_STATE=empty, BASELINE_ABSENT='1')   # nothing else to stand on
        self.refused('G2', TAIL_STATE=self.tail(replayedMessages=0, inconclusive=True, bookHash='99'))   # a mismatch still refuses

    def test_a_snapshot_plus_tail_that_differs_or_cannot_answer_is_refused(self):
        for env, why in (({'TAIL_STATE': self.tail(bookHash='12')}, "G2: member 0's restored state differs from its snapshot+tail"),
                         ({'TAIL_STATE': self.tail(positions=4)}, 'G2'),
                         ({'TAIL_STATE': self.tail(applied=99)}, "G1: member 0's snapshot+tail gives applied 99"),
                         ({'TAIL_STATE': self.tail(digestTrades=4)}, 'trades 4'),
                         ({'TAIL_STATE': self.tail(complete=False)}, 'G1'),
                         ({'TAIL_STATE': '{"error":"RECOVERY_ARCHIVE_DISCONTINUITY: term=3"}'}, 'G1: member 0 has no /recon/tail-state answer'),
                         ({'TAIL_STATE': '{"bookHash":"11","positionHash":"22"}'}, 'no /recon/tail-state answer'),   # no verdict field
                         ({'HOLD_FAIL': '1'}, 'failed (the archive purge hold)'),
                         ({'CONTAINERS_FAIL': '1'}, "the members' containers cannot be read")):
            out, calls = self.refused(why, **env)
            self.assertIsNone(first(calls, 'reindex'), why)

    def test_each_tail_read_carries_a_token_minted_for_it(self):
        rc, out, calls = run('rig_up', PURGE='1', TOKEN_SINGLE_USE='1')
        self.assertEqual(rc, 0, out)

    def test_without_the_purge_rendered_the_replay_from_0_still_runs(self):   # control
        rc, out, calls = run('rig_up')
        self.assertEqual(rc, 0, out)
        self.assertIsNotNone(first(calls, 'reindex')); self.assertIsNotNone(first(calls, 'shadow-state'))
        self.assertIsNone(first(calls, 'tail-state')); self.assertIsNone(first(calls, 'archive-purge.hold'))
        self.assertNotIn('--from-literal=tail=', calls[first(calls, 'create configmap rig-gate')])

    def test_a_fresh_epoch_with_the_purge_rendered_has_nothing_to_replay(self):
        rc, out, calls = run('rig_up', PURGE='1', **FreshEpoch.EMPTY)
        self.assertEqual(rc, 0, out)
        self.assertIsNone(first(calls, 'tail-state')); self.assertIsNone(first(calls, 'archive-purge.hold'))


class FreshEpoch(unittest.TestCase):
    """FR-PRG-29 / ADR-101: a fresh epoch wipes the member disks only after READY snapshots of exactly those disks, and
    the gate accepts an empty epoch and a missing baseline only while `rig-epoch` says provisioned. Each refusal test
    fails against the pre-FR-PRG-29 rig.sh, whose G4 took a missing baseline as a first bring-up."""
    EMPTY = {'EPOCH': 'provisioned e2', 'BASELINE_ABSENT': '1', 'M': '0 -1 0 0 0 0',
             'ACCT': acct(p0=(0, 0, 0, 0, 0), p1=(0, 0, 0, 0, 0), resting=(0, 0)),
             'DIGEST': '{"bookHash":"11","positionHash":"22","openOrders":0,"positions":0,"digestTrades":0}'}

    def refused(self, gate, body='rig_up', **env):
        rc, out, calls = run(body, **env)
        self.assertEqual(rc, 2, out); self.assertIn('REFUSED', out); self.assertIn(gate, out)
        self.assertIsNone(first(calls, SCALE_UP), 'a gateway started after a refusal')
        return out, calls

    def test_a_missing_baseline_is_refused_without_a_fresh_epoch(self):
        out, _ = self.refused('G4', BASELINE_ABSENT='1')
        self.assertIn('a missing baseline is not a first bring-up', out)

    def test_an_unreadable_epoch_record_is_refused(self):
        self.refused('rig-epoch cannot be read', EPOCH_FAIL='1')

    def test_a_provisioned_empty_epoch_passes_and_is_consumed(self):
        rc, out, calls = run('rig_up', **self.EMPTY)
        self.assertEqual(rc, 0, out)
        self.assertIn('G0 ok: fresh epoch e2', out); self.assertIn('G4 ok: fresh epoch e2', out)
        self.assertNotIn('G2 ok', out)
        self.assertIsNone(first(calls, 'rig-errlog'), 'G-pre read a log on wiped claims')
        gate, consumed = first(calls, 'create configmap rig-gate'), first(calls, 'rig-epoch .*state=consumed')
        self.assertIsNotNone(consumed, out); self.assertLess(gate, consumed)

    def test_a_fresh_epoch_is_initialized_after_the_gateways_and_before_the_smoke(self):
        # FR-PRG-30: the 2026-10-31 bring-up's smoke failed UNKNOWN_ACCOUNT on the empty engine
        rc, out, calls = run('rig_up', **self.EMPTY)
        self.assertEqual(rc, 0, out)
        steps = [first(calls, p) for p in (SCALE_UP, 'initialize accounts', 'initialize reference-data',
                                           'rig-epoch .*state=initialized', 'web-front-end-console')]
        self.assertNotIn(None, steps, out); self.assertEqual(steps, sorted(steps), out)

    def test_a_failed_initialization_refuses_before_the_smoke_and_is_retried(self):
        rc, out, calls = run('rig_up', **{**self.EMPTY, 'INIT_FAIL': 'reference-data'})
        self.assertEqual(rc, 2, out); self.assertIn('initialize-reference-data failed', out)
        self.assertIsNone(first(calls, 'web-front-end-console')); self.assertIsNone(first(calls, 'state=initialized'))
        # a rerun on the gated rig, the epoch still consumed: the initialization runs again
        rc, out, calls = run('members_gated(){ true; }; rig_up', EPOCH='consumed e2')
        self.assertEqual(rc, 0, out); self.assertIsNotNone(first(calls, 'initialize accounts'), out)
        rc, out, calls = run('members_gated(){ true; }; rig_up', EPOCH='initialized e2')   # control: done once
        self.assertEqual(rc, 0, out); self.assertIsNone(first(calls, 'initialize'))
        rc, out, calls = run('rig_up')   # control: no fresh epoch, no initialization
        self.assertEqual(rc, 0, out); self.assertIsNone(first(calls, 'initialize'))

    def test_a_provisioned_epoch_whose_members_are_not_empty_is_refused(self):
        self.refused('is provisioned (empty) but the members hold', **{**self.EMPTY, 'M': '100 100 5 11 22 7'})

    def test_a_provisioned_epoch_with_a_baseline_is_refused(self):
        env = {k: v for k, v in self.EMPTY.items() if k != 'BASELINE_ABSENT'}
        self.refused('is provisioned (empty) but a shutdown baseline exists', **{**env, 'BASELINE': '0 -1 0 0 0 0'})

    def test_a_provisioned_epoch_with_a_claim_left_is_refused(self):
        self.refused('provisioned but 1 of 3 member claims exist', **{**self.EMPTY, 'CLAIMS': 'persistentvolumeclaim/data-order-matcher-cluster-1'})

    # Production 2026-11-07: an up refused after the members started left provisioned with their three (empty) claims,
    # deleted by hand twice. The retry reads their error logs and lets G0 decide; members holding anything are refused.
    CLAIMS3 = ' '.join(f'persistentvolumeclaim/data-order-matcher-cluster-{i}' for i in range(3))

    def test_a_provisioned_epoch_retried_on_its_own_empty_claims_passes(self):
        rc, out, calls = run('rig_up', **{**self.EMPTY, 'CLAIMS': self.CLAIMS3})
        self.assertEqual(rc, 0, out)
        self.assertIn('its own three claims from an earlier start', out); self.assertIn('G0 ok: fresh epoch e2', out)
        self.assertIsNotNone(first(calls, 'rig-errlog-2'), 'G-pre skipped the claims it found')
        self.assertIsNone(first(calls, r'delete pvc'))
        self.refused('provisioned (empty) but the members hold', **{**self.EMPTY, 'CLAIMS': self.CLAIMS3, 'M': '100 100 5 11 22 7'})

    def test_a_wiped_epoch_with_a_claim_left_is_refused_before_the_archive(self):
        out, calls = self.refused('is wiped but a member claim exists', **{**self.EMPTY, 'EPOCH': 'wiped e2', 'CLAIMS': self.CLAIMS3})
        self.assertIsNone(first(calls, 'RENAME TABLE')); self.assertIsNone(first(calls, 'state=provisioned'))

    def test_a_provisioned_epoch_with_open_rows_is_refused(self):
        self.refused('G3', **{**self.EMPTY, 'ACCT': acct(p0=(0, 0, 0, 0, 0), p1=(0, 0, 0, 0, 0), resting=(1, 1))})

    def test_a_wiped_epoch_archives_the_read_model_with_trade_processor_stopped(self):
        rc, out, calls = run('rig_up', **{**self.EMPTY, 'EPOCH': 'wiped e2'})
        self.assertEqual(rc, 0, out)
        steps = [first(calls, p) for p in (r'scale deploy/trade-processor --replicas=0', r'RENAME TABLE trades TO arc_e2_trades, positions TO arc_e2_positions',
                                           r'CREATE TABLE IF NOT EXISTS trades LIKE arc_e2_trades', r'key_column_usage',
                                           r'scale deploy/trade-processor --replicas=1', r'rig-epoch .*state=provisioned',
                                           r'scale statefulset/order-matcher-cluster --replicas=3')]
        self.assertNotIn(None, steps, out); self.assertEqual(steps, sorted(steps), out)
        rc, out, calls = run('rig_up', **{**self.EMPTY, 'EPOCH': 'wiped e2', 'ARC_EXISTS': '1'})   # a re-run renames nothing
        self.assertEqual(rc, 0, out); self.assertIsNone(first(calls, 'eod-price-db.*RENAME TABLE'))
        # FR-PRG-29: a volume older than a table's first-boot SQL never got it; only the tables present move
        rc, out, calls = run('rig_up', **{**self.EMPTY, 'EPOCH': 'wiped e2', 'PRESENT': 'trades positions orderbook projection_cursor'})
        self.assertEqual(rc, 0, out)
        self.assertIsNotNone(first(calls, r'RENAME TABLE trades TO arc_e2_trades, positions TO arc_e2_positions, orderbook TO arc_e2_orderbook, projection_cursor TO arc_e2_projection_cursor"'), out)
        self.assertIsNone(first(calls, 'LIKE arc_e2_projection_accounting'))

    # FR-PRG-29 (production 2026-11-07): the old epoch's capture streams aged out to 0 messages at first_seq 371203273;
    # the fresh read model's cursor 0 then met a RETENTION_GAP. An empty stream is deleted (trade-processor recreates
    # it at seq 1) while the trade store is stopped; one still holding the old epoch's messages is refused.
    def test_a_wiped_epoch_restarts_its_empty_capture_streams_at_seq_1(self):
        rc, out, calls = run('rig_up', **{**self.EMPTY, 'EPOCH': 'wiped e2', 'FEED': 'TRADERX_FEED_P0:0 TRADERX_FEED_P1:0 OTHER:5'})
        self.assertEqual(rc, 0, out)
        steps = [first(calls, p) for p in (r'scale deploy/trade-processor --replicas=0', r'select count\(\*\) from trades',
                                           r'STREAM.DELETE.TRADERX_FEED_P0', r'STREAM.DELETE.TRADERX_FEED_P1',
                                           r'scale deploy/trade-processor --replicas=1', r'rig-epoch .*state=provisioned')]
        self.assertNotIn(None, steps, out); self.assertEqual(steps, sorted(steps), out)
        self.assertIsNone(first(calls, 'STREAM.DELETE.OTHER'))

    # FR-PRG-29 (production 2026-11-07 11:01 pm ET): rollout status returned before trade-processor listened, and G3's
    # read of the accounting got connection refused. The archive waits until the accounting answers.
    def test_a_wiped_epoch_waits_for_the_restarted_trade_processor_to_answer(self):
        rc, out, calls = run('rig_up', **{**self.EMPTY, 'EPOCH': 'wiped e2', 'ACCT_FAIL_N': '3'})
        self.assertEqual(rc, 0, out)
        self.assertIn("ok: trade-processor's accounting answers", out)
        up, members = first(calls, r'scale deploy/trade-processor --replicas=1'), first(calls, r'replicas=3')
        reads = [i for i, c in enumerate(calls) if 'projection-feed/accounting' in c and up < i < members]
        self.assertGreaterEqual(len(reads), 4, out)   # three refused, then the one that answered, before any member

    # FR-THT-02 (production 2026-11-07 11:28 pm ET): a history-feed started before the reset kept its lanes on the deleted
    # stream and G3h timed out. It is held at 0 across the reset and started after trade-processor answers.
    def test_a_wiped_epoch_holds_history_feed_until_the_streams_are_recreated(self):
        rc, out, calls = run('rig_up', **{**self.EMPTY, 'EPOCH': 'wiped e2', 'FEED': 'TRADERX_FEED:0', 'ACCT_FAIL_N': '2'})
        self.assertEqual(rc, 0, out)
        answered = [i for i, c in enumerate(calls) if 'projection-feed/accounting' in c][2]
        steps = [first(calls, p) for p in (r'scale deploy/history-feed --replicas=0', r'STREAM.DELETE.TRADERX_FEED',
                                           r'scale deploy/trade-processor --replicas=1')]
        steps += [answered, first(calls, r'scale deploy/history-feed --replicas=1'), first(calls, r'replicas=3')]
        self.assertNotIn(None, steps, out); self.assertEqual(steps, sorted(steps), out)
        _, out, calls = run('rig_up', **{**self.EMPTY, 'EPOCH': 'wiped e2', 'HIST_ABSENT': '1'})   # no store (G3h refuses later)
        self.assertIsNotNone(first(calls, r'scale deploy/trade-processor --replicas=1'), out)
        self.assertIsNone(first(calls, 'scale deploy/history-feed'))

    # FR-THT-08 (production 2026-11-08, e1108): the reset deleted TRADERX_FEED while the history store still held the old
    # epoch's rows bound to its incarnation, and history-feed refused STREAM_RESET. Its tables move to arc_<label>_* while
    # it is stopped, before the stream is deleted; it recreates them on start.
    CH = 'clickhouse-client -q '
    def test_a_wiped_epoch_archives_the_history_store_while_history_feed_is_stopped(self):
        wiped = {**self.EMPTY, 'EPOCH': 'wiped e2', 'FEED': 'TRADERX_FEED:0'}
        rc, out, calls = run('rig_up', **wiped)
        self.assertEqual(rc, 0, out)
        steps = [first(calls, p) for p in (r'scale deploy/history-feed --replicas=0', self.CH + 'RENAME TABLE trades TO arc_e2_trades$',
                                           self.CH + 'RENAME TABLE orders TO arc_e2_orders$', self.CH + 'RENAME TABLE history_cursor TO arc_e2_history_cursor$',
                                           r'STREAM.DELETE.TRADERX_FEED', r'scale deploy/history-feed --replicas=1')]
        self.assertNotIn(None, steps, out); self.assertEqual(steps, sorted(steps), out)
        self.assertIn('arc_e2_trades holds 81140 sides', out)
        self.assertIsNone(first(calls, 'TRUNCATE'))
        # a retry: everything moved; the live tables are an earlier attempt's, empty, and only its cursor is emptied
        rc, out, calls = run('rig_up', **wiped, CH_ARC='1')
        self.assertEqual(rc, 0, out)
        self.assertIsNone(first(calls, self.CH + 'RENAME')); self.assertIsNotNone(first(calls, self.CH + 'TRUNCATE TABLE history_cursor$'))
        out, calls = self.refused('arc_e2_trades exists and the live trades holds 3 rows', **wiped, CH_ARC='1', CH_ROWS='3')
        self.assertIsNone(first(calls, 'STREAM.DELETE')); self.assertIsNone(first(calls, 'TRUNCATE'))
        rc, out, calls = run('rig_up', **wiped, CH_LIVE='0')   # a store never written: nothing to move
        self.assertEqual(rc, 0, out); self.assertIsNone(first(calls, self.CH + 'RENAME'))
        _, out, calls = run('rig_up', **wiped, HIST_ABSENT='1')   # control: no store rendered, none touched
        self.assertIsNone(first(calls, 'clickhouse-client'))

    # FR-PRG-29 (production 2026-11-08, e1108): a refused up had already stopped trade-processor and history-feed, and the
    # retry read their live 0 as the count to restore. Each restarts at the count recorded when the archive first stopped it.
    def test_a_retried_archive_restarts_at_the_recorded_counts_not_the_live_zero(self):
        wiped = {**self.EMPTY, 'EPOCH': 'wiped e2'}
        rc, out, calls = run('rig_up', **wiped)
        self.assertEqual(rc, 0, out)
        steps = [first(calls, p) for p in (r'annotate deploy trade-processor traderx-rig-held-replicas=1 ', r'scale deploy/trade-processor --replicas=0',
                                           r'scale deploy/trade-processor --replicas=1', r'annotate deploy trade-processor traderx-rig-held-replicas-')]
        self.assertNotIn(None, steps, out); self.assertEqual(steps, sorted(steps), out)
        rc, out, calls = run('rig_up', **wiped, TP_SPEC='0', HF_SPEC='0', HELD_trade_processor='2', HELD_history_feed='1')
        self.assertEqual(rc, 0, out)
        self.assertIsNotNone(first(calls, r'scale deploy/trade-processor --replicas=2'), out)
        self.assertIsNotNone(first(calls, r'scale deploy/history-feed --replicas=1'), out)
        self.assertIsNone(first(calls, r'held-replicas=[0-9]'), 'a retry re-recorded its count')
        for env, d in (({'TP_SPEC': '0'}, 'trade-processor'), ({'HF_SPEC': '0'}, 'history-feed')):   # at 0, nothing recorded
            out, calls = self.refused(f'{d} is at 0 replicas with none recorded', **wiped, **env)
            self.assertIsNone(first(calls, 'STREAM.DELETE'), d); self.assertIsNone(first(calls, 'RENAME TABLE'), d)

    # FR-PRG-29 (production 2026-11-08, e1108): a retry after trade-processor restarted re-ran the archive, whose emptiness
    # check refused the seq-0 cursors the running trade-processor had written. The archive records itself (archived,
    # between wiped and provisioned) and a retry from archived only restarts. One case per point a refusal can stop at.
    def test_the_archive_records_itself_and_each_retry_ordering_resumes(self):
        rc, out, calls = run('rig_up', **{**self.EMPTY, 'EPOCH': 'wiped e2', 'FEED': 'TRADERX_FEED:0'})
        self.assertEqual(rc, 0, out)
        steps = [first(calls, p) for p in (r'STREAM.DELETE.TRADERX_FEED', r'rig-epoch .*state=archived', r'scale deploy/trade-processor --replicas=1',
                                           r'scale deploy/history-feed --replicas=1', r'rig-epoch .*state=provisioned', r'replicas=3')]
        self.assertNotIn(None, steps, out); self.assertEqual(steps, sorted(steps), out)
        restart = {'TP_SPEC': '0', 'HELD_trade_processor': '1', 'HF_SPEC': '0', 'HELD_history_feed': '1'}
        # 1. stopped inside the archive (wiped; both held, the tables already moved): the archive re-runs on empty tables
        rc, out, calls = run('rig_up', **{**self.EMPTY, 'EPOCH': 'wiped e2', **restart, 'ARC_EXISTS': '1', 'CH_ARC': '1'})
        self.assertEqual(rc, 0, out)
        self.assertLess(first(calls, r'state=archived'), first(calls, r'scale deploy/trade-processor --replicas=1'))
        # 2. stopped after the archive, before the restart (archived; both still held): no archive, both restarted
        # 3. stopped after trade-processor restarted and wrote its cursors (archived; live rows): no archive, no re-check
        for n, env in ((2, restart), (3, {'LIVE_ROWS': '2', 'HELD_history_feed': '1', 'HF_SPEC': '0'})):
            rc, out, calls = run('rig_up', **{**self.EMPTY, 'EPOCH': 'archived e2', **env})
            self.assertEqual(rc, 0, f'ordering {n}: {out}')
            for never in ('RENAME TABLE', 'STREAM.DELETE', 'scale deploy/trade-processor --replicas=0', r'select count\(\*\) from projection_cursor', 'state=archived'):
                self.assertIsNone(first(calls, never), f'ordering {n}: {never}')
            self.assertIn('already archived', out)
            self.assertIsNotNone(first(calls, r'scale deploy/history-feed --replicas=1'), f'ordering {n}')
            self.assertLess(first(calls, r'scale deploy/history-feed --replicas=1'), first(calls, r'state=provisioned'))
        self.assertIsNone(first(calls, r'scale deploy/trade-processor'), 'ordering 3 rescaled a running trade-processor')
        out, _ = self.refused('trade-processor is at 0 replicas with none recorded', **{**self.EMPTY, 'EPOCH': 'archived e2', 'TP_SPEC': '0'})
        self.refused('epoch e2 is archived and not yet provisioned', 'rig_fresh_epoch e3', EPOCH='archived e2')
        out, calls = self.refused('is archived but a member claim exists', **{**self.EMPTY, 'EPOCH': 'archived e2', 'CLAIMS': self.CLAIMS3})
        self.assertIsNone(first(calls, 'state=provisioned'))

    def test_a_capture_stream_still_holding_messages_or_unreadable_is_refused(self):
        for env, why in (({'FEED': 'TRADERX_FEED_P0:0 TRADERX_FEED_P1:3'}, 'TRADERX_FEED_P1 still holds 3 messages'),
                         ({'FEED_FAIL': '1'}, 'capture streams cannot be listed')):
            out, calls = self.refused(why, **{**self.EMPTY, 'EPOCH': 'wiped e2', **env})
            self.assertIsNone(first(calls, 'STREAM.DELETE'), why)
            self.assertIsNone(first(calls, 'state=provisioned'), why)

    def test_a_table_left_with_rows_after_the_archive_is_refused(self):
        out, calls = self.refused('not empty after the archive', **{**self.EMPTY, 'EPOCH': 'wiped e2', 'LIVE_ROWS': '3'})
        self.assertIsNone(first(calls, 'state=provisioned'))

    def test_fresh_epoch_wipes_only_after_ready_snapshots_of_those_disks(self):
        for snaps, why in (('absent', 'no READY snapshot'), ('wrong', 'no READY snapshot'), ('broken', 'cannot be read')):
            out, calls = self.refused(why, 'rig_fresh_epoch e2', SNAPS=snaps)
            self.assertIsNone(first(calls, 'delete pvc'), snaps); self.assertIsNone(first(calls, 'state=wiped'), snaps)
        self.refused('must be stopped', 'rig_fresh_epoch e2', STS_SPEC='3')
        self.refused('must pass a restore gate', 'rig_fresh_epoch e2', EPOCH='provisioned e1')
        rc, out, calls = run('rig_fresh_epoch e2')   # control
        self.assertEqual(rc, 0, out)
        steps = [first(calls, p) for p in ('create configmap rig-epoch-e2-history', r'rig-epoch .*state=wiped',
                                           'delete configmap rig-shutdown-baseline', 'delete pvc data-order-matcher-cluster-0')]
        self.assertNotIn(None, steps, out); self.assertEqual(steps, sorted(steps), out)

    def test_archive_members_snapshots_each_members_own_disk(self):
        rc, out, calls = run('wait_for(){ die "not READY: $2"; }; rig_archive_members e2', SNAPS='absent')
        self.assertEqual(rc, 2, out)   # the stub never turns READY, so the wait refuses after the first create
        self.assertIsNotNone(first(calls, 'snapshots create traderx-e2-m0 --source-disk disk-0 --source-disk-zone us-east1-b'), out)
        self.refused('not of member 0', 'rig_archive_members e2', SNAPS='wrong')
        self.refused('must be stopped', 'rig_archive_members e2', STS_SPEC='3')
        rc, out, calls = run('rig_archive_members e2')   # control: READY snapshots of these disks exist
        self.assertEqual(rc, 0, out); self.assertIsNone(first(calls, 'snapshots create'))


# Three epochs archived; e1108 the newest (FR-PRG-39). Not a member snapshot: traderx-eod-price-db-20261101.
ARC_TABLES = 'trades positions orderbook'.split()
ARCHIVES = {'HISTORY': 'e1101=2026-11-01T05:00:00Z e1107=2026-11-07T05:00:00Z e1108=2026-11-08T05:00:00Z',
         'SQL_ARC': ' '.join(f'arc_{l}_{t}' for l in ('e1101', 'e1107', 'e1108') for t in ARC_TABLES),
         'CH_ARC_TABLES': 'arc_e1107_trades arc_e1107_orders arc_e1108_trades arc_e1108_history_cursor',
         'SNAP_LIST': ' '.join(f'traderx-{l}-m{i}' for l in ('e1101', 'e1107', 'e1108') for i in range(3))
                      + ' traderx-eod-price-db-20261101'}


class Retention(unittest.TestCase):
    """FR-PRG-39 (yaakov 2026-11-08, "Keep only the last one"): once epoch L's archive is complete, every older epoch's
    arc_<label>_* tables (eod-price-db and ClickHouse) and member-disk snapshots go; L's stay, live tables are never
    named, and a label whose age is not on record is refused."""
    TABLES, THREE = ARC_TABLES, ARCHIVES
    LEFT = 'echo LEFT; cat "$EVIDENCE/.sql-arc" "$EVIDENCE/.ch-arc" "$EVIDENCE/.snaps"'

    def deletes(self, calls):
        return [c for c in calls if re.search(r'DROP TABLE|snapshots delete', c)]

    def refused(self, why, body='prune_archives e1108', **env):
        rc, out, calls = run(body, **{**self.THREE, **env})
        self.assertEqual(rc, 2, out); self.assertIn('REFUSED', out); self.assertIn(why, out)
        self.assertEqual(self.deletes(calls), [], why)
        return out

    def test_every_older_epoch_goes_and_the_newest_stays(self):
        rc, out, calls = run(f'prune_archives e1108; {self.LEFT}', **self.THREE)
        self.assertEqual(rc, 0, out)
        self.assertEqual(sorted(out.split('LEFT')[1].split()), sorted(
            [f'arc_e1108_{t}' for t in self.TABLES] + ['arc_e1108_trades', 'arc_e1108_history_cursor', 'traderx-eod-price-db-20261101']
            + [f'traderx-e1108-m{i}' for i in range(3)]))
        sql = [c for c in self.deletes(calls) if 'eod-price-db' in c]
        self.assertEqual(len(sql), 1, 'one DROP for every old SQL table')
        self.assertIn('SET foreign_key_checks=0; DROP TABLE IF EXISTS arc_e1101_trades, arc_e1101_positions', sql[0])
        for c in self.deletes(calls):
            self.assertNotIn('e1108', c); self.assertNotRegex(c, r'EXISTS (trades|positions|orderbook)\b')
        self.assertIsNotNone(first(calls, r'clickhouse-client -q DROP TABLE IF EXISTS arc_e1107_orders SYNC'), out)
        self.assertIsNotNone(first(calls, r'snapshots delete traderx-e1101-m0 .*traderx-e1107-m2 --project'), out)
        self.assertIn('older epochs: e1101 e1107 (14 objects', out)
        rc, out, calls = run('prune_archives e1108; prune_archives e1108', **self.THREE)   # a rerun deletes nothing more
        self.assertEqual(rc, 0, out); self.assertIn('nothing older than e1108', out)
        self.assertEqual(len(self.deletes(calls)), 1 + 2 + 1, out)

    def test_a_label_whose_age_is_not_on_record_is_refused(self):
        three = self.THREE
        self.refused('traderx-e1109-m0 (no rig-epoch-e1109-history)', SNAP_LIST=three['SNAP_LIST'] + ' traderx-e1109-m0')
        self.refused('arc_e1109_trades (not older than e1108)', SQL_ARC=three['SQL_ARC'] + ' arc_e1109_trades',
                     HISTORY=three['HISTORY'] + ' e1109=2026-11-09T05:00:00Z')
        self.refused('arc_e1107_trades (not older than e1108)',
                     HISTORY='e1101=2026-11-01T05:00:00Z e1107=2026-11-08T05:00:00Z e1108=2026-11-08T05:00:00Z')
        self.refused('arc_Odd_trades (not arc_<label>_<table>)', SQL_ARC=three['SQL_ARC'] + ' arc_Odd_trades')
        self.refused('no rig-epoch-e1108-history', HISTORY='e1101=2026-11-01T05:00:00Z e1107=2026-11-07T05:00:00Z')
        for env, why in (({'HISTORY_FAIL': '1'}, 'ConfigMaps cannot be listed'), ({'SQL_LIST_FAIL': '1'}, "eod-price-db's archived tables"),
                         ({'CH_LIST_FAIL': '1'}, "ClickHouse's archived tables"), ({'SNAP_LIST_FAIL': '1'}, 'snapshots cannot be listed')):
            self.refused(why, **env)

    def test_nothing_older_goes_until_the_newest_archive_is_complete(self):
        self.refused("epoch e1108's read model is not archived", SQL_ARC=' '.join(f'arc_e1101_{t}' for t in self.TABLES))
        self.refused("epoch e1108's three member-disk snapshots are not all READY", SNAPS='absent')
        self.refused('are not all READY', SNAP_LIST=self.THREE['SNAP_LIST'].replace('traderx-e1108-m2 ', ''))

    def test_a_live_tables_key_onto_an_archive_is_refused(self):
        self.refused('a table outside the old archives references them: trades->arc_e1101_trades', ARC_REFS='trades->arc_e1101_trades')

    def test_a_retry_after_a_partial_delete_finishes_it(self):
        for fail, done in (('SNAP_DELETE_FAIL', ('eod-price-db', 'clickhouse')), ('CH_DROP_FAIL', ('eod-price-db',)),
                           ('SQL_DROP_FAIL', ())):
            # the first attempt stops at its failing delete; the second, against what it left, finishes
            rc, out, calls = run(f'( {fail}=1 prune_archives e1108 ) && exit 9; echo RETRY >> "$LOG"; prune_archives e1108; {self.LEFT}', **self.THREE)
            self.assertEqual(rc, 0, f'{fail}: {out}'); self.assertIn('rerun retries', out)
            self.assertFalse(re.search('e1101|e1107', out.split('LEFT')[1].split('REFUSED')[0]), f'{fail}: {out}')   # stderr follows
            second = calls[calls.index('RETRY'):]
            for store in done:   # what the first attempt deleted is not deleted again
                self.assertFalse([c for c in self.deletes(second) if store in c], f'{fail}: {store} deleted twice')
            self.assertEqual(len([c for c in self.deletes(second) if 'snapshots delete' in c]), 1, fail)

    def test_a_fresh_epoch_up_prunes_after_its_archive_and_before_the_restart(self):
        wiped = {**FreshEpoch.EMPTY, **self.THREE, 'EPOCH': 'wiped e1108', 'ARC_EXISTS': '1'}
        rc, out, calls = run('rig_up', **wiped)
        self.assertEqual(rc, 0, out)
        steps = [first(calls, p) for p in (r'rig-epoch .*state=archived', r'DROP TABLE IF EXISTS arc_e1101',
                                           r'snapshots delete traderx-e1101-m0', r'scale deploy/trade-processor --replicas=1',
                                           r'rig-epoch .*state=provisioned')]
        self.assertNotIn(None, steps, out); self.assertEqual(steps, sorted(steps), out)
        # refused (a newer snapshot set with no record): the epoch stays archived, and the retry prunes before restarting
        rc, out, calls = run('rig_up', **{**wiped, 'SNAP_LIST': self.THREE['SNAP_LIST'] + ' traderx-e1109-m0'})
        self.assertEqual(rc, 2, out); self.assertIn('(no rig-epoch-e1109-history)', out)
        self.assertIsNone(first(calls, r'trade-processor --replicas=1')); self.assertIsNone(first(calls, 'state=provisioned'))
        rc, out, calls = run('rig_up', **{**wiped, 'EPOCH': 'archived e1108', 'TP_SPEC': '0', 'HELD_trade_processor': '1'})
        self.assertEqual(rc, 0, out); self.assertLess(first(calls, 'snapshots delete'), first(calls, 'trade-processor --replicas=1'))

    def test_prune_archives_on_its_own(self):
        rc, out, calls = run('rig_prune_archives', **self.THREE, EPOCH='initialized e1108')
        self.assertEqual(rc, 0, out); self.assertEqual(len(self.deletes(calls)), 4, out)
        rc, out, calls = run('DRY=1; rig_prune_archives', **self.THREE, EPOCH='initialized e1108')   # reads, deletes nothing
        self.assertEqual(rc, 0, out); self.assertEqual(self.deletes(calls), [])
        self.assertIn('would delete: snap traderx-e1107-m2', out); self.assertIn('would delete: sql arc_e1101_trades', out)
        for env in ({'EPOCH': 'wiped e1108'}, {}):
            self.refused('no completed fresh-epoch archive', 'rig_prune_archives', **env)


class GatewayShrink(unittest.TestCase):
    """FR-PRG-24 (codeX review 2026-10-30 §8): the shrink deletes only nodes a successful listing shows empty."""
    BODY = 'GATEWAYS_LIB=1 . "$GW" 3; DRY=0; delete_empty_nodes mig-1'

    def shrink(self, **env):
        return run(self.BODY, GW=str(RIG.with_name('gateways.sh')), GW_ON_n1='still-serving', **env)

    def test_only_the_empty_node_is_deleted(self):   # control
        rc, out, calls = self.shrink()
        self.assertEqual(rc, 0, out)
        self.assertIsNotNone(first(calls, r'cordon n2$'), out)
        self.assertIsNotNone(first(calls, r'delete-instances mig-1 --instances=n2 '), out)
        self.assertIsNone(first(calls, r'cordon n1$'), 'an occupied node was cordoned')
        self.assertIsNone(first(calls, r'--instances=n1 '), 'an occupied node was deleted')

    def test_an_unreadable_occupancy_refuses_and_deletes_nothing(self):
        for env in ({'POD_LIST_FAIL': 'n1'}, {'POD_LIST_FAIL': 'n2'}, {'NODES_FAIL': '1'}):
            rc, out, calls = self.shrink(**env)
            self.assertEqual(rc, 2, (env, out))
            self.assertIn('no node is deleted', out)
            self.assertIsNone(first(calls, r'cordon '), (env, 'a node was cordoned'))
            self.assertIsNone(first(calls, r'delete-instances'), (env, 'a node was deleted'))


class MemberDisk(unittest.TestCase):
    """FR-PRG-15: member claims grow for a throughput hour, never shrink, after the StorageClass allows it."""
    def test_expansion_is_allowed_then_each_claim_grows(self):
        rc, out, calls = run('DRY=0; pv_size_is(){ true; }; rig_member_disk 250Gi')
        self.assertEqual(rc, 0, out)
        sc = first(calls, r'patch storageclass order-matcher-archive .*allowVolumeExpansion')
        claims = [first(calls, rf'patch pvc data-order-matcher-cluster-{i} .*250Gi') for i in range(3)]
        self.assertIsNotNone(sc); self.assertTrue(all(c is not None for c in claims), calls)
        self.assertLess(sc, min(claims))

    def test_an_expandable_class_is_left_alone(self):
        rc, out, calls = run('DRY=0; pv_size_is(){ true; }; rig_member_disk 250Gi', SC_EXPAND='true')
        self.assertEqual(rc, 0, out)
        self.assertIsNone(first(calls, r'patch storageclass'))

    def test_a_shrink_or_a_bad_size_is_refused_before_any_change(self):
        for args, env in (('16Gi', {}), ('250', {}), ('250Gi', {'PVC_SIZE': '300Gi'})):
            rc, out, calls = run(f'DRY=0; rig_member_disk {args}', **env)
            self.assertEqual(rc, 2, (args, out))
            self.assertIsNone(first(calls, r'patch '), (args, calls))

class Resume(unittest.TestCase):
    """FR-PRG-06: the shrink's logout -> logon report, on the real logs of production's 2 -> 1 drain (2026-10-29)."""
    def test_only_the_drains_logouts_are_reported(self):
        fixture = Path(__file__).with_name('fixtures') / 'resume-20261029'
        drained = (fixture / 'drained-cluster-gateway-qlxrz.log').read_text()
        self.assertGreater(drained.count('FIX logout:'), 4)   # the gateway's earlier logouts are in its log too
        out = subprocess.run([sys.executable, str(RIG.with_name('fix-resume.py')), str(fixture)],
                             capture_output=True, text=True, check=True).stdout
        self.assertIn('sessions 4, worst resume 595 ms', out)
        self.assertEqual(sorted(l.split(':')[0] for l in out.splitlines() if 'logout -> logon' in l),
                         ['CLIENT3', 'CLIENT4', 'CLIENT7', 'CLIENT8'])

    def test_a_drained_log_without_its_drain_is_flagged(self):
        with tempfile.TemporaryDirectory() as d:
            Path(d, 'drained-x.log').write_text('2026-10-29T17:58:00.758Z x -- FIX logout: CLIENT7\n')
            out = subprocess.run([sys.executable, str(RIG.with_name('fix-resume.py')), d], capture_output=True, text=True).stdout
        self.assertIn('no GATEWAY-DRAIN begin', out)


class Down(unittest.TestCase):
    def test_gateways_then_barrier_then_members_then_pools(self):
        rc, out, calls = run('rig_down')
        self.assertEqual(rc, 0, out)
        order = [first(calls, p) for p in (r'delete job binary-load', r'scale deploy/cluster-gateway --replicas=0',
                                           r'delete svc order-matcher-gw-ilb',
                                           r'order-matcher-cluster-1 .*ClusterTool /data/cluster snapshot',
                                           r'create configmap rig-shutdown-baseline',
                                           r'scale statefulset/order-matcher-cluster --replicas=0',
                                           r'resize .*loadgen-pool --num-nodes 0',
                                           r'resize .*gateway-pool --num-nodes 0', r'resize .*history-pool --num-nodes 0',
                                           r'resize .*default-pool --num-nodes 0',
                                           r'instances stop traderx-client-1')]
        self.assertNotIn(None, order, out)
        self.assertEqual(order, sorted(order), out)
        self.assertIn('JAVA_TOOL_OPTIONS=-Xmx128m', calls[order[3]], 'ClusterTool must not inherit the 6g pre-touched heap')

    def test_a_member_that_did_not_exit_143_still_ends_at_zero_but_fails(self):
        rc, out, calls = run('rig_down', EXITS='143 137 143')
        self.assertEqual(rc, 3, out)
        self.assertIn('member 1 did not exit 143', out)
        self.assertIsNotNone(first(calls, r'resize .*default-pool --num-nodes 0'), 'pools left billing')


class SnapshotBarrier(unittest.TestCase):
    """FR-PRG-02: the barrier waits for each member past its OWN snapshot count. 2026-11-06 (e1106): after a failover
    replaced member 2, its per-process counter read 5 against member 0's 8, and down refused forever."""
    def past(self, counts, *base):
        m = ''.join(f'M{i}="0 0 0 h p {c}"; ' for i, c in enumerate(counts))
        return subprocess.run(['bash', '-c', f'RIG_LIB=1 . "{RIG}"; member(){{ local v="M$1"; echo "${{!v}}"; }}; '
                               f'{m} snapshots_past {" ".join(map(str, base))}'], capture_output=True, text=True).returncode

    def test_a_replaced_member_with_a_lower_count_passes_once_it_advances(self):
        self.assertEqual(self.past([11, 11, 6], 10, 10, 5), 0)

    def test_any_member_that_has_not_advanced_holds_the_barrier(self):
        self.assertNotEqual(self.past([11, 11, 5], 10, 10, 5), 0)
        self.assertNotEqual(self.past([10, 11, 6], 10, 10, 5), 0)


class Nats(unittest.TestCase):
    """FR-PRG-28: a NATS restart or replacement under a run is never a clean run (2026-10-30: OOM-killed 7x, unseen)."""
    def test_up_records_the_nats_pod_and_down_finds_it_intact(self):
        rc, out, calls = run('rig_up')
        self.assertEqual(rc, 0, out)
        self.assertIsNotNone(first(calls, r'create configmap rig-nats --from-literal=uid=u1'), out)
        self.assertLess(first(calls, 'create configmap rig-nats'), first(calls, r'scale statefulset/order-matcher-cluster --replicas=3'))
        rc, out, _ = run('rig_down')
        self.assertEqual(rc, 0, out)
        self.assertNotIn('NATS did not stay up', out)

    def test_an_oom_killed_nats_fails_the_run_but_the_teardown_still_reaches_zero(self):
        rc, out, calls = run('rig_down', NATS_PODS=nats_pods(('u1', 'Running', 7, 'OOMKilled')))
        self.assertEqual(rc, 3, out)
        self.assertIn('NATS did not stay up', out)
        self.assertIn('restarted 7x; last OOMKilled', out)
        self.assertIsNotNone(first(calls, r'resize .*default-pool --num-nodes 0'), 'pools left billing')

    def test_a_replaced_or_missing_nats_fails_the_run(self):
        for pods, says in ((nats_pods(('u2', 'Running', 0, '')), 'was replaced'),
                           (nats_pods(('u1', 'Failed', 0, ''), ('u2', 'Running', 0, '')), '2 NATS pods'),
                           (nats_pods(), '0 NATS pods')):
            rc, out, _ = run('rig_down', NATS_PODS=pods)
            self.assertEqual(rc, 3, out)
            self.assertIn(says, out)
        rc, out, _ = run('rig_down', NATS_LIST_FAIL='1')
        self.assertEqual(rc, 3, out)
        self.assertIn('could not be listed', out)

    def test_up_refuses_a_nats_that_already_restarted_before_any_member_starts(self):
        rc, out, calls = run('rig_up', NATS_PODS=nats_pods(('u1', 'Running', 1, 'OOMKilled')))
        self.assertEqual(rc, 2, out)
        self.assertIn('REFUSED', out)
        self.assertIsNone(first(calls, r'scale statefulset/order-matcher-cluster --replicas=3'))
        self.assertIsNone(first(calls, SCALE_UP))

    def test_status_reports_it(self):
        rc, out, _ = run('rig_status', NATS_PODS=nats_pods(('u1', 'Running', 2, 'OOMKilled')))
        self.assertIn('NATS: nats-u1 restarted 2x', out)


class Clients(unittest.TestCase):
    """FR-PRG-17: CLIENTS=N client VMs, each its own /32 on the internal LB; down stops every one."""
    def test_each_client_vm_is_its_own_source_range(self):
        rc, out, calls = run('kc(){ if [ "$1" = apply ]; then cat >> "$LOG"; else call "kc $*"; fi; }; ilb_up', CLIENTS='3')
        self.assertEqual(rc, 0, out)
        self.assertEqual([c.strip() for c in calls if c.strip().startswith("- ") and c.strip().endswith("/32")], ['- 10.0.0.5/32'] * 3, calls)
        for v in ('traderx-client-1', 'traderx-client-2', 'traderx-client-3'):
            self.assertIsNotNone(first(calls, rf'instances describe {v} .*networkIP'), v)
        self.assertNotIn('- CLIENT_CIDR', '\n'.join(calls))

    def test_down_stops_every_client_vm(self):
        rc, out, calls = run('rig_down', CLIENTS='3')
        self.assertEqual(rc, 0, out)
        for v in ('traderx-client-1', 'traderx-client-2', 'traderx-client-3'):
            self.assertIsNotNone(first(calls, rf'instances stop {v} '), v)

    def test_kdb_vm_starts_and_stops_with_the_rig_unless_disabled(self):
        """FR-PRG-35: the KDB-X query VM rides the rig's VM lifecycle; KDB_VM= leaves it out."""
        rc, out, calls = run('rig_down')
        self.assertEqual(rc, 0, out)
        self.assertIsNotNone(first(calls, r'instances stop traderx-kdb '), calls)
        rc, out, calls = run('rig_down', KDB_VM='')
        self.assertEqual(rc, 0, out)
        self.assertIsNone(first(calls, r'traderx-kdb'), calls)

    def test_more_than_three_is_refused(self):
        rc, out, _ = run('echo "vms=$CLIENT_VMS"', CLIENTS='1')   # FR-PRG-26: one VM, not "1 2 1" (BSD seq 2 1)
        self.assertIn('vms=traderx-client-1\n', out)
        rc, out, _ = run('echo "vms=$CLIENT_VMS"', CLIENTS='3')
        self.assertIn('vms=traderx-client-1 traderx-client-2 traderx-client-3\n', out)
        rc, out, _ = run('true', CLIENTS='4')
        self.assertEqual(rc, 2, out); self.assertIn('CLIENTS is 1..3', out)


class ReadModel(unittest.TestCase):
    """FR-PRG-19: the real read_model, with sql stubbed: a failed query fails it; it never hashes an error as a table.
    FR-PRG-22: the same for the snapshot read model's accounting, and for a mode that cannot be read."""
    def rm(self, sql_body, kubectl_body='echo ""'):
        script = (f'set -uo pipefail; RIG_LIB=1 . "{RIG}"; sql(){{ {sql_body}; }}; kubectl(){{ {kubectl_body}; }}; '
                  'if out=$(read_model); then echo rc=0; else echo rc=1; fi; echo "$out"')
        return subprocess.run(['bash', '-c', script], capture_output=True, text=True, timeout=30).stdout

    def test_a_failed_accounting_fetch_fails_the_read(self):
        snapshot = 'case "$*" in *TRADE_PROJECTION_MODE*) echo snapshot;; *accounting*) {};; esac'
        self.assertIn('rc=1', self.rm('echo row', snapshot.replace('{}', 'return 22')))
        ok = self.rm("printf '7\\t1\\t2\\n'", snapshot.replace('{}', """echo '{"trades":{}}'"""))
        self.assertIn('rc=0', ok)
        self.assertIn('accounting=', ok)

    def test_a_mode_that_cannot_be_read_fails_the_read(self):
        self.assertIn('rc=1', self.rm('echo row', 'return 1'))

    def test_a_failed_query_fails_the_read(self):
        self.assertIn('rc=1', self.rm('case "$1" in *positions*) return 1;; *) echo row;; esac'))

    def test_each_table_is_one_in_db_aggregate(self):
        # FR-PRG-27: count and two md5 sums per table, computed by MariaDB; never a dump (no ORDER BY, no row stream)
        snapshot = 'case "$*" in *TRADE_PROJECTION_MODE*) echo snapshot;; *accounting*) echo \'{"trades":{}}\';; esac'
        out = self.rm('printf \'7\\t1\\t2\\n\'', snapshot)
        self.assertIn('rc=0', out)
        for t in ('trades', 'positions', 'orderbook'):
            self.assertIn(f'{t}=7:1:2', out)
        self.assertIn('rc=1', self.rm('echo row'))   # an answer that is not count:sum:sum is a failure, not a hash

class Loadgen(unittest.TestCase):
    """FR-PRG-16: loadgen-pool is sized only by `rig.sh loadgen N`, within 0..16."""
    def test_the_pool_is_resized_and_waited_for(self):
        rc, out, calls = run('rig_loadgen 12')
        self.assertEqual(rc, 0, out)
        self.assertIsNotNone(first(calls, r'resize traderx-demo --node-pool loadgen-pool --num-nodes 12'), out)

    def test_a_count_outside_0_to_16_is_refused_before_any_call(self):
        for n in ('17', '-1', 'x', ''):
            rc, out, calls = run(f'rig_loadgen "{n}"')
            self.assertEqual(rc, 2, out); self.assertIsNone(first(calls, r'resize'), n)


class BusinessDay(unittest.TestCase):
    """FR-PRG-31: a fresh epoch has no business date, so every DAY order was REJECTED NO_TRADING_DAY. Every `up` opens
    today's US Eastern date after the initialization and before the smoke. Fails against the pre-FR-PRG-31 rig.sh."""

    def test_every_up_opens_the_business_day_before_the_smoke(self):
        for env in ({}, FreshEpoch.EMPTY):
            rc, out, calls = run('rig_up', **env)
            self.assertEqual(rc, 0, out)
            steps = [first(calls, p) for p in (SCALE_UP, '--input-type=module - 20[0-9][0-9]-[01][0-9]-[0-3][0-9]',
                                               '-- node -$')]
            self.assertNotIn(None, steps, out); self.assertEqual(steps, sorted(steps), out)
        self.assertLess(first(calls, 'initialize reference-data'), first(calls, '--input-type=module'))

    def test_a_given_date_is_used_and_a_refusal_stops_before_the_smoke(self):
        rc, out, calls = run('rig_up', BUSINESS_DATE='2026-11-05')
        self.assertEqual(rc, 0, out); self.assertIsNotNone(first(calls, '--input-type=module - 2026-11-05$'))
        rc, out, calls = run('rig_up', BDAY_FAIL='1')
        self.assertEqual(rc, 2, out); self.assertIn('business day', out); self.assertIsNone(first(calls, '-- node -$'))


class LoadgenIdle(unittest.TestCase):
    """FR-PRG-32: LOADGEN=N brings the load pods up idle with the rig, and loadgen.sh starts the load on them."""
    LG = Path(__file__).with_name('loadgen.sh')

    def test_up_with_loadgen_starts_the_pool_with_the_others_and_the_pods_after_the_smoke(self):
        rc, out, calls = run('rig_up', LOADGEN='2')
        self.assertEqual(rc, 0, out)
        pool, smoke = first(calls, 'resize .*--node-pool loadgen-pool --num-nodes 2'), first(calls, '-- node -$')
        self.assertIsNotNone(pool, out); self.assertLess(pool, first(calls, SCALE_UP))
        self.assertIn('8 idle load pods Ready', out)
        self.assertLess(smoke, max(i for i, c in enumerate(calls) if 'apply -f -' in c))

    def test_without_loadgen_up_touches_no_loadgen(self):
        rc, out, calls = run('rig_up')
        self.assertEqual(rc, 0, out); self.assertIsNone(first(calls, 'loadgen'))

    def test_bad_loadgen_values_are_refused(self):
        for env in ({'LOADGEN': '17'}, {'LOADGEN': 'x'}, {'LOADGEN': '2', 'LOADGEN_PODS': '0'}):
            rc, out, _ = run('validate_args', **env)
            self.assertEqual(rc, 2, (env, out)); self.assertIn('LOADGEN', out)

    def test_down_removes_the_load_pods(self):
        rc, out, calls = run('rig_down')
        self.assertIsNotNone(first(calls, 'delete deploy loadgen'), out)

    def start(self, *args, **env):
        return run(f'LOADGEN_LIB=1 . "{self.LG}" start; members_gated(){{ true; }}; ARGS=({" ".join(args)}); start', **env)

    def test_start_splits_the_rate_over_the_idle_pods_at_the_mark(self):
        rc, out, calls = self.start('120000', '600')
        self.assertEqual(rc, 0, out)
        runs = [c for c in calls if 'nohup node /load/binary-load.mjs' in c]
        self.assertEqual(len(runs), 2, out)   # 120000 / (2 pods x 16 sessions) = 3750 per session
        for c in runs:
            self.assertIn('--ticker MSFT --px 412.50 --sessions 16 --rate 3750 --secs 600', c)
            self.assertIn('--pause-ms 3000', c)   # FR-PRG-36 default

    def test_the_phase_pause_is_configurable_and_bounded(self):
        """FR-PRG-36: 1000 ms stopped a production run after one phase (2026-11-02)."""
        rc, out, calls = self.start('120000', LOADGEN_PAUSE_MS='8000')
        self.assertEqual(rc, 0, out)
        self.assertTrue(all('--pause-ms 8000' in c for c in calls if 'nohup node /load/binary-load.mjs' in c), out)
        for bad in ('50', '90000', 'x'):
            rc, out, calls = self.start('120000', LOADGEN_PAUSE_MS=bad)
            self.assertEqual(rc, 2, out); self.assertIn('LOADGEN_PAUSE_MS', out); self.assertIsNone(first(calls, 'nohup'))

    def test_start_refuses_without_idle_pods_without_the_gate_and_when_a_pod_does_not_start(self):
        rc, out, calls = self.start('120000', LG_PODS=' ')
        self.assertEqual(rc, 2, out); self.assertIn('no idle load pod', out)
        rc, out, calls = run(f'LOADGEN_LIB=1 . "{self.LG}" start; members_gated(){{ false; }}; ARGS=(120000); start')
        self.assertEqual(rc, 2, out); self.assertIsNone(first(calls, 'nohup'))
        rc, out, calls = self.start('120000', LG_DEAD='1')
        self.assertEqual(rc, 2, out); self.assertIn('did not start', out)
        rc, out, calls = self.start('120000', LG_SILENT='1', LOADGEN_SEND_WAIT='1')   # a process that sends nothing
        self.assertEqual(rc, 2, out); self.assertIn('sent no orders', out); self.assertIsNotNone(first(calls, 'LG-KILL'), out)
        rc, out, calls = self.start('fast')
        self.assertEqual(rc, 2, out); self.assertIn('usage', out)

    def test_start_refuses_and_results_fail_when_nats_restarted(self):
        """FR-PRG-28: production 2026-10-30's load pods all read clean while NATS was OOM-killed under them."""
        oom = nats_pods(('u1', 'Running', 1, 'OOMKilled'))
        rc, out, calls = self.start('120000', NATS_PODS=oom)
        self.assertEqual(rc, 2, out); self.assertIn('OOMKilled', out); self.assertIsNone(first(calls, 'nohup'))
        results = f'LOADGEN_LIB=1 . "{self.LG}" results; results'
        rc, out, _ = run(results, NATS_PODS=oom)
        self.assertEqual(rc, 3, out); self.assertIn('FAILED: NATS', out); self.assertIn('lg-a', out)
        rc, out, _ = run(results, NATS_PODS=nats_pods(('u2', 'Running', 0, '')))   # replaced since up recorded u1
        self.assertEqual(rc, 3, out); self.assertIn('replaced', out)
        rc, out, _ = run(results)   # control: the pod up recorded, never restarted
        self.assertEqual(rc, 0, out); self.assertIn('NATS intact', out)


class HpaTarget(unittest.TestCase):
    """FR-PRG-33: the HPA's CPU target is HPA_CPU_TARGET (default 60); 2026-11-01 three gateways at ~230K/s binary sat at
    44%, so a 60% target never scaled them."""

    def hpa(self, **env):
        rc, out, _ = run('kc(){ cat > "$EVIDENCE/hpa.yaml"; }; validate_args && autoscale_on && cat "$EVIDENCE/hpa.yaml"', **env)
        return rc, out

    def test_the_target_is_rendered(self):
        rc, out = self.hpa(HPA_CPU_TARGET='30')
        self.assertEqual(rc, 0, out); self.assertIn('averageUtilization: 30', out); self.assertIn('minReplicas: 3', out)
        rc, out = self.hpa()
        self.assertEqual(rc, 0, out); self.assertIn('averageUtilization: 60', out)

    def test_a_bad_target_is_refused(self):
        for v in ('5', '100', 'x'):
            rc, out = self.hpa(HPA_CPU_TARGET=v)
            self.assertEqual(rc, 2, out); self.assertIn('HPA_CPU_TARGET', out)


class RolledOut(unittest.TestCase):
    """FR-PRG-34: support readiness is every replica updated and available, not `rollout status`, which gives up at once
    on a rollout flagged ProgressDeadlineExceeded after sitting at 0 nodes (production 2026-11-01 9:24 pm ET)."""

    def check(self, fields):
        rc, out, _ = run(f'unset -f deploy_rolled_out; RIG_LIB=1 . "$RIG"; kubectl(){{ echo "{fields}"; }}; deploy_rolled_out deploy/x && echo READY || echo WAIT')
        return out.strip().splitlines()[-1]

    def test_ready_only_when_every_replica_is_updated_and_available(self):
        self.assertEqual(self.check('3 3 1 1 1 1'), 'READY')
        for f in ('3 2 1 1 1 1', '3 3 1 0 1 1', '3 3 1 1 0 1', '3 3 1 1 1 2', '3 3 1   '):
            self.assertEqual(self.check(f), 'WAIT', f)


class InstanceCheck(unittest.TestCase):
    """FR-PRG-02: the real no_rig_instances (the suites stub it), with gcloud stubbed."""
    def check(self, gc_body):
        script = f'set -uo pipefail; RIG_LIB=1 . "{RIG}"; gc(){{ {gc_body}; }}; if no_rig_instances; then echo rc=0; else echo rc=1; fi'   # as wait_for calls it
        return subprocess.run(['bash', '-c', script], capture_output=True, text=True, timeout=30).stdout.strip()

    def test_a_failing_query_is_not_an_empty_one(self):
        # Production 2026-10-29: gcloud crashed on the alternation filter, printed nothing, and the check passed.
        self.assertEqual(self.check('echo "ERROR: gcloud crashed" >&2; return 1'), 'rc=1')

    def test_an_instance_in_any_pool_is_found(self):
        for pool in ('default-pool', 'engine-pool', 'nats-pool', 'gateway-pool', 'loadgen-pool'):
            body = f'case "$*" in *"{pool}-"*) echo gke-traderx-demo-{pool}-abc-1;; esac'
            self.assertEqual(self.check(body), 'rc=1', pool)

    def test_no_instance_in_any_pool_passes(self):
        self.assertEqual(self.check('return 0'), 'rc=0')

class Plan(unittest.TestCase):
    def test_dry_run_contacts_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            env = dict(os.environ, PATH=f'{d}:/usr/bin:/bin', GATEWAYS='2', EVIDENCE=d + '/ev')
            for tool in ('kubectl', 'gcloud'):
                Path(d, tool).write_text(f'#!/bin/sh\necho CONTACTED {tool} >&2; exit 99\n'); os.chmod(Path(d, tool), 0o755)
            for action in ('pools', 'up', 'down'):
                p = subprocess.run(['bash', str(RIG), action, '--dry-run'], env=env, capture_output=True, text=True)
                self.assertEqual(p.returncode, 0, p.stderr)
                self.assertNotIn('CONTACTED', p.stderr)

    def test_dry_run_after_a_label_writes_nothing(self):   # it once snapshotted production, flag ignored
        with tempfile.TemporaryDirectory() as d:
            env = dict(os.environ, PATH=f'{d}:/usr/bin:/bin:/opt/homebrew/bin:/usr/local/bin', EVIDENCE=d + '/ev')
            for tool in ('kubectl', 'gcloud'):   # reads answer as for stopped members on one disk
                Path(d, tool).write_text(f'#!/bin/sh\necho "{tool} $*" >> {d}/calls\ncase "$*" in *volumeName*) echo pvc-x;; '
                                         '*volumeHandle*) echo projects/p/zones/us-east1-b/disks/pvc-x;; *replicas*) echo 0;; esac\n')
                os.chmod(Path(d, tool), 0o755)
            p = subprocess.run(['bash', str(RIG), 'archive-members', 'e1', '--dry-run'], env=env, capture_output=True, text=True, timeout=60)
            self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
            self.assertIn('would: gcloud compute snapshots create traderx-e1-m2', p.stdout)
            self.assertNotIn('snapshots create', Path(d, 'calls').read_text())

    def test_arguments_are_refused(self):
        for env in ({'GATEWAYS': '0'}, {'GATEWAYS': '19'}, {'GATEWAYS': 'x'}, {'AUTOSCALE': 'maybe'},
                    {'GATEWAYS': '18', 'AUTOSCALE': 'on'}):
            p = subprocess.run(['bash', str(RIG), 'up', '--dry-run'], env=dict(os.environ, **env), capture_output=True, text=True)
            self.assertEqual(p.returncode, 2, env)


if __name__ == '__main__':
    unittest.main()
