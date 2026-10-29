"""ADR-096 rig lifecycle, offline: the real rig.sh functions with kubectl and gcloud stubbed as bash functions that
record every call. Checks the order of operations and that every gate refusal leaves the gateways at 0."""
import os
from pathlib import Path
import re
import subprocess
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
pool_ready(){ true; }; ready_replicas(){ true; }; no_pods(){ true; }; ilb_has_ip(){ true; }; hpa_exists(){ false; }
pool_autoscaling(){ false; }; row_status_is(){ true; }; no_rig_instances(){ true; }; vm_status_is(){ true; }
snapshots_past(){ true; }; admin_token(){ echo tok; }
member(){ local v="M$1"; echo "${!v:-$M}"; }
read_model(){ if [ -f "$EVIDENCE/.rm" ]; then echo "$RM_AFTER"; else touch "$EVIDENCE/.rm"; echo "$RM"; fi; }
kubectl(){
  call "kubectl $*"
  case "$*" in
    *reindex*) echo "$REINDEX";;
    *shadow-state*) echo "${SHADOW:-}";;
    *"health?digest=1"*) echo "$DIGEST";;
    *"from orderbook where status"*) echo "$OPEN_ROWS";;
    *"from trades where right(id,2)"*) printf '%s\n' "$TRADE_ROWS";;
    *"get configmap rig-shutdown-baseline"*) echo "${BASELINE:-}";;
    *"create configmap"*) echo 'kind: ConfigMap';;
    *"apply -f -"*) cat >/dev/null;;
    *"get pods -l app=cluster-gateway -o jsonpath"*) for i in $(seq 1 "$GATEWAYS"); do echo "node-$i"; done;;
    *"web-front-end-console"*) printf 'NEW 7 MSFT @ 1\nCANCELED 7\nOK\n';;
    *"get sts order-matcher-cluster -o jsonpath={.status.replicas}"*) echo "${STS_REPLICAS:-0}";;
    *"get sts order-matcher-cluster -o jsonpath={.spec"*) echo member-image;;
    *"get sts order-matcher-cluster -o jsonpath"*) echo 3;;
    *"get pod rig-errlog-"*phase*) echo "$ERRLOG_PHASE";;
    *"logs rig-errlog-"*) echo "$ERRLOG";;
    *"localhost:8080/health"*) a="$*"; [[ "$a" == *"order-matcher-cluster-$LEADER "* ]] && echo '{"role":"LEADER"}' || echo '{"role":"FOLLOWER"}';;
    *"get pods -l app=order-matcher-cluster -w"*)
      i=0; for code in $EXITS; do
        printf '{"object":{"metadata":{"name":"order-matcher-cluster-%s"},"status":{"containerStatuses":[{"name":"cluster-node","state":{"terminated":{"exitCode":%s}}}]}}}\n' $i $code
        i=$((i+1)); done;;
  esac
}
gcloud(){ call "gcloud $*"; case "$*" in *networkIP*) echo 10.0.0.5;; esac; }
'''

GOOD = {'GATEWAYS': '3', 'AUTOSCALE': 'off', 'NS': 'traderx', 'M': '100 100 5 11 22 7', 'RM': 'trades=a', 'RM_AFTER': 'trades=a',
        'REINDEX': '{"replayedAppliedSeq":100,"indexedTrades":5}',
        'SHADOW': '{"applied":100,"complete":true,"bookHash":"11","positionHash":"22","openOrders":2,"positions":3,"digestTrades":5}',
        'DIGEST': '{"bookHash":"11","positionHash":"22","openOrders":2,"positions":3,"digestTrades":5}', 'OPEN_ROWS': '2', 'TRADE_ROWS': '5\t5\t5',
        'LEADER': '1', 'EXITS': '143 143 143', 'ERRLOG_PHASE': 'Succeeded', 'ERRLOG': 'No errors'}


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
                                           r'scale statefulset/order-matcher-cluster --replicas=3', r'create configmap rig-gate',
                                           SCALE_UP, r'networkInterfaces\[0\]\.networkIP', r'web-front-end-console')]
        self.assertNotIn(None, order, out)
        self.assertEqual(order, sorted(order), out)
        self.assertIn('G4: no shutdown baseline', out)

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

    def test_a_restored_state_that_differs_from_the_replay_is_refused(self):
        good = GOOD['SHADOW']
        for field, value in (('"bookHash":"11"', '"bookHash":"12"'), ('"positions":3', '"positions":4'),
                             ('"openOrders":2', '"openOrders":1')):
            self.refused('G2', SHADOW=good.replace(field, value))

    def test_an_incomplete_replay_is_refused(self):
        self.refused('G2', SHADOW=GOOD['SHADOW'].replace('"complete":true', '"complete":false'))

    def test_restored_open_orders_that_the_read_model_lacks_are_refused(self):
        self.refused('G3', OPEN_ROWS='1')

    def test_the_read_model_must_hold_exactly_the_members_trade_sides(self):
        # Production 2026-10-29: trades 18 on the members = rows 1-B/S..18-B/S in SQL, beside 13 seed rows.
        for rows in ('4\t4\t4', '5\t4\t5', '5\t5\t6', '6\t6\t6'):   # missing, duplicate number, gap, extra
            out = self.refused('G3', TRADE_ROWS=rows)
            self.assertIn('the members booked 5 trade sides', out)
        rc, out, _ = run('rig_up')
        self.assertIn('G3 ok: the read model holds trade sides 1..5, each once', out)

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

    def test_a_read_model_that_moved_is_refused(self):
        self.refused('G3', RM_AFTER='trades=b')

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


class Down(unittest.TestCase):
    def test_gateways_then_barrier_then_members_then_pools(self):
        rc, out, calls = run('rig_down')
        self.assertEqual(rc, 0, out)
        order = [first(calls, p) for p in (r'scale deploy/cluster-gateway --replicas=0', r'delete svc order-matcher-gw-ilb',
                                           r'order-matcher-cluster-1 .*ClusterTool /data/cluster snapshot',
                                           r'create configmap rig-shutdown-baseline',
                                           r'scale statefulset/order-matcher-cluster --replicas=0',
                                           r'resize .*gateway-pool --num-nodes 0', r'resize .*default-pool --num-nodes 0',
                                           r'instances stop traderx-client-1')]
        self.assertNotIn(None, order, out)
        self.assertEqual(order, sorted(order), out)
        self.assertIn('JAVA_TOOL_OPTIONS=-Xmx128m', calls[order[2]], 'ClusterTool must not inherit the 6g pre-touched heap')

    def test_a_member_that_did_not_exit_143_still_ends_at_zero_but_fails(self):
        rc, out, calls = run('rig_down', EXITS='143 137 143')
        self.assertEqual(rc, 3, out)
        self.assertIn('member 1 did not exit 143', out)
        self.assertIsNotNone(first(calls, r'resize .*default-pool --num-nodes 0'), 'pools left billing')


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

    def test_arguments_are_refused(self):
        for env in ({'GATEWAYS': '0'}, {'GATEWAYS': '19'}, {'GATEWAYS': 'x'}, {'AUTOSCALE': 'maybe'},
                    {'GATEWAYS': '18', 'AUTOSCALE': 'on'}):
            p = subprocess.run(['bash', str(RIG), 'up', '--dry-run'], env=dict(os.environ, **env), capture_output=True, text=True)
            self.assertEqual(p.returncode, 2, env)


if __name__ == '__main__':
    unittest.main()
