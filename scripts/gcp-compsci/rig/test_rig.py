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
pool_ready(){ true; }; sql_ready(){ true; }; ready_replicas(){ true; }; no_pods(){ true; }; ilb_has_ip(){ true; }; hpa_exists(){ false; }
pool_autoscaling(){ false; }; row_status_is(){ true; }; no_rig_instances(){ true; }; vm_status_is(){ true; }
snapshots_past(){ true; }; admin_token(){ echo tok; }
member(){ local v="M$1"; echo "${!v:-$M}"; }
read_model(){ [ -n "${RM_FAIL:-}" ] && return 1; if [ -f "$EVIDENCE/.rm" ]; then echo "$RM_AFTER"; else touch "$EVIDENCE/.rm"; echo "$RM"; fi; }
kubectl(){
  call "kubectl $*"
  case "$*" in
    *RECON_FULL_HISTORY_MAX*) echo "${CAP:-200000}";;
    *reindex*) [ -n "${REINDEX_FAIL:-}" ] && return 8; echo "$REINDEX";;
    *shadow-state*) echo "${SHADOW:-}";;
    *"health?digest=1"*) echo "$DIGEST";;
    *"from orderbook where status"*) echo "$OPEN_ROWS";;
    *"from trades where right(id,2)"*) printf '%s\n' "$TRADE_ROWS";;
    *"get configmap rig-shutdown-baseline"*) echo "${BASELINE:-}";;
    *"get configmap rig-unprojected"*) echo "${UNPROJECTED:-}";;
    *"get sts order-matcher-cluster -o jsonpath={.spec.template.spec.containers[0].env[*].name}"*) echo "${MEMBER_ENV:-JAVA_TOOL_OPTIONS TRADE_BRIDGE_NATS_URL}";;
    *"get pvc data-order-matcher-cluster-"*"requests.storage"*) echo "${PVC_SIZE:-32Gi}";;
    *"get storageclass order-matcher-archive"*) echo "${SC_EXPAND:-}";;
    *"create configmap"*) echo 'kind: ConfigMap';;
    *"apply -f -"*) cat >/dev/null;;
    *"get pods -l app=cluster-gateway -o jsonpath"*) for i in $(seq 1 "$GATEWAYS"); do echo "node-$i"; done;;
    *"web-front-end-console"*) [ -n "${SMOKE_FAIL:-}" ] && { echo "FAIL refused: 58=Invalid Logon message"; return 1; }; printf 'NEW 7 MSFT @ 1\nCANCELED 7\nOK\n';;
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
        'DIGEST': '{"bookHash":"11","positionHash":"22","openOrders":2,"positions":3,"digestTrades":5}', 'OPEN_ROWS': '2', 'TRADE_ROWS': '5\t5\t5\t0',
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

    def test_an_incomplete_replay_is_refused(self):
        self.refused('G2', SHADOW=GOOD['SHADOW'].replace('"complete":true', '"complete":false'))

    def test_restored_open_orders_that_the_read_model_lacks_are_refused(self):
        self.refused('G3', OPEN_ROWS='1')

    def test_the_read_model_must_hold_exactly_the_members_trade_sides(self):
        # Production 2026-10-29: trades 18 on the members = rows 1-B/S..18-B/S in SQL, beside 13 seed rows.
        for rows in ('4\t4\t4\t0', '5\t4\t5\t0', '5\t5\t6\t0', '6\t6\t6\t0'):   # missing, duplicate number, gap, extra
            out = self.refused('G3', TRADE_ROWS=rows)
            self.assertIn('the members booked 5 trade sides', out)
        rc, out, _ = run('rig_up')
        self.assertIn('G3 ok: the read model holds trade sides 1..5, each once', out)

    def test_without_bridges_the_smoke_ends_at_the_fix_answers(self):
        rc, out, calls = run('rig_up', MEMBER_ENV='JAVA_TOOL_OPTIONS CLUSTER_EGRESS_COALESCE')
        self.assertEqual(rc, 0, out)
        self.assertIn('members run without bridges (FR-PRG-14)', out)
        rc, out, _ = run('rig_up')   # with bridges the read-model row is still required
        self.assertIn('CANCELED (the read model, FR-PRG-04)', out)

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

    def test_a_recorded_unprojected_window_is_excluded_exactly(self):
        # C1 (full-throughput run with the bridges off): trade sides 3-4 never reach SQL; the gate excludes exactly them.
        rc, out, _ = run('rig_up', UNPROJECTED='3-4', TRADE_ROWS='3\t3\t5\t0')
        self.assertEqual(rc, 0, out)
        self.assertIn('G3 ok: the read model holds trade sides 1..5 except 3-4, each once', out)
        for rows in ('5\t5\t5\t2', '3\t3\t5\t1', '2\t2\t5\t0'):   # window not excluded, a row inside it, one missing
            self.assertIn('unprojected: 3-4', self.refused('G3', UNPROJECTED='3-4', TRADE_ROWS=rows))

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
                                           r'resize .*gateway-pool --num-nodes 0', r'resize .*default-pool --num-nodes 0',
                                           r'instances stop traderx-client-1')]
        self.assertNotIn(None, order, out)
        self.assertEqual(order, sorted(order), out)
        self.assertIn('JAVA_TOOL_OPTIONS=-Xmx128m', calls[order[3]], 'ClusterTool must not inherit the 6g pre-touched heap')

    def test_a_member_that_did_not_exit_143_still_ends_at_zero_but_fails(self):
        rc, out, calls = run('rig_down', EXITS='143 137 143')
        self.assertEqual(rc, 3, out)
        self.assertIn('member 1 did not exit 143', out)
        self.assertIsNotNone(first(calls, r'resize .*default-pool --num-nodes 0'), 'pools left billing')


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

    def test_more_than_three_is_refused(self):
        rc, out, _ = run('true', CLIENTS='4')
        self.assertEqual(rc, 2, out); self.assertIn('CLIENTS is 1..3', out)


class ReadModel(unittest.TestCase):
    """FR-PRG-19: the real read_model, with sql stubbed: a failed query fails it; it never hashes an error as a table."""
    def rm(self, sql_body):
        script = f'set -uo pipefail; RIG_LIB=1 . "{RIG}"; sql(){{ {sql_body}; }}; if out=$(read_model); then echo rc=0; else echo rc=1; fi; echo "$out"'
        return subprocess.run(['bash', '-c', script], capture_output=True, text=True, timeout=30).stdout

    def test_a_failed_query_fails_the_read(self):
        self.assertIn('rc=1', self.rm('case "$1" in *positions*) return 1;; *) echo row;; esac'))

    def test_the_hashes_are_of_the_exact_rows(self):
        out = self.rm('echo "row-$1"')
        import hashlib
        want = hashlib.md5(b'row-select id,accountid,security,side,quantity,price,state from trades order by 1\n').hexdigest()[:16]
        self.assertIn('rc=0', out); self.assertIn(f'trades={want}', out)


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

    def test_arguments_are_refused(self):
        for env in ({'GATEWAYS': '0'}, {'GATEWAYS': '19'}, {'GATEWAYS': 'x'}, {'AUTOSCALE': 'maybe'},
                    {'GATEWAYS': '18', 'AUTOSCALE': 'on'}):
            p = subprocess.run(['bash', str(RIG), 'up', '--dry-run'], env=dict(os.environ, **env), capture_output=True, text=True)
            self.assertEqual(p.returncode, 2, env)


if __name__ == '__main__':
    unittest.main()
