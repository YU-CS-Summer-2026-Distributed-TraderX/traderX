#!/usr/bin/env python3
"""Exercise actual sourced helper and caller source against offline synthetic commands."""
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
LIB = Path(os.environ.get('REPLAY_TEST_LIBRARY', str(ROOT / 'scripts/yu15/lib-replay-epoch.sh')))
EXPECTED_MS = '1743465600000'
NS = 'fixture-ns'
POD = {'kind':'Pod','metadata':{'name':'order-matcher-cluster-0','namespace':NS,'uid':'pod-uid',
       'ownerReferences':[{'kind':'StatefulSet','name':'order-matcher-cluster','uid':'sts-uid','controller':True}]},
       'spec':{'containers':[{'name':'cluster-node','env':[{'name':'CLUSTER_BASE_DIR','value':'/data'}],
       'volumeMounts':[{'name':'data','mountPath':'/data'}]}],
       'volumes':[{'name':'data','persistentVolumeClaim':{'claimName':'data-order-matcher-cluster-0'}}]}}
PVC = {'kind':'PersistentVolumeClaim','metadata':{'name':'data-order-matcher-cluster-0','namespace':NS,
       'uid':'claim-uid','creationTimestamp':'2025-04-01T00:00:00Z'},
       'status':{'phase':'Bound'},'spec':{'volumeName':'fixture-pv'}}
PV = {'kind':'PersistentVolume','metadata':{'name':'fixture-pv','uid':'pv-uid'},'status':{'phase':'Bound'},
      'spec':{'claimRef':{'name':'data-order-matcher-cluster-0','namespace':NS,'uid':'claim-uid'}}}
DEPLOY = {'kind':'Deployment','metadata':{'name':'price-publisher','namespace':NS,'uid':'producer-uid'}}
CALLER_ROOT = Path(os.environ.get('REPLAY_TEST_CALLER_ROOT', str(ROOT)))
PREFIX = f'K=(kubectl --context fixture-context -n {NS})'


def fixture(pod=None, pvc=None, pv=None, deployment=None):
    return {'rules': [
        {'contains':['get','pod','order-matcher-cluster-0','json'],'output':POD if pod is None else pod},
        {'contains':['get','pvc','data-order-matcher-cluster-0','json'],'output':PVC if pvc is None else pvc},
        {'contains':['get','pv','fixture-pv','json'],'output':PV if pv is None else pv},
        {'contains':['get','deploy','price-publisher'],'output':DEPLOY if deployment is None else deployment},
        {'contains':['create','configmap','replay-epoch'],'output':'auto-configmap'},
        {'contains':['apply','-f','-'],'output':'configmap/replay-epoch configured'},
        {'contains':['rollout','restart','deployment/price-publisher'],'output':'restarted'},
        {'contains':['rollout','status','deployment/price-publisher'],'output':'complete'},
    ]}


def function_source(path, name):
    # Functions in these actual Bash callers close with a column-zero brace. Preserve their bytes.
    text = path.read_text()
    start = text.index(name + '() {')
    return text[start:text.index('\n}', start)+2]


class AnchorTests(unittest.TestCase):
    def run_shell(self, data=None, prefix=PREFIX, script='stamp_replay_epoch; rc=$?; echo "RESULT:$rc:$REPLAY_ANCHOR_STATUS:$REPLAY_ANCHOR_PRODUCER"; exit "$rc"', env=None):
        with tempfile.TemporaryDirectory() as directory:
            d = Path(directory)
            for command in ('kubectl','kind','docker','gcloud'):
                (d/command).symlink_to(ROOT/'scripts/tests/replay-anchor/fake-command.py')
            (d/'fixture.json').write_text(json.dumps(data or fixture()))
            environ = {**os.environ, 'PATH': str(d)+':'+os.environ['PATH'], 'FAKE_FIXTURE':str(d/'fixture.json'),
                       'FAKE_TRACE':str(d/'trace'), 'REPLAY_ANCHOR_MODE':'required', **(env or {})}
            result = subprocess.run(['bash','-c',f'source "{LIB}"; {prefix}; {script}'],env=environ,
                                    capture_output=True,text=True,timeout=15)
            trace = [json.loads(line) for line in (d/'trace').read_text().splitlines()] if (d/'trace').exists() else []
            return result, trace

    def assert_refused(self, result, trace, no_query=False):
        self.assertNotEqual(result.returncode, 0, result.stdout+result.stderr)
        self.assertNotIn('anchor stored:', result.stdout)
        self.assertFalse(any(c.get('command') == 'gcloud' for c in trace))
        self.assertFalse(any(any(v in c.get('args',[]) for v in ('create','apply','restart')) for c in trace), trace)
        if no_query:
            self.assertEqual(trace, [])

    def test_valid_array_exact_anchor_target_and_idempotence(self):
        r, calls = self.run_shell(script='stamp_replay_epoch && stamp_replay_epoch; rc=$?; echo "RESULT:$rc:$REPLAY_ANCHOR_STATUS:$REPLAY_ANCHOR_PRODUCER"; exit "$rc"')
        self.assertEqual(r.returncode,0,r.stderr)
        self.assertIn('RESULT:0:stored:rollout-complete',r.stdout)
        self.assertEqual(r.stdout.count('epochStartMs='+EXPECTED_MS),2)
        creates = [c for c in calls if 'create' in c.get('args',[])]
        self.assertEqual(len(creates),2)
        for c in creates:
            self.assertIn('--from-literal=epochStartMs='+EXPECTED_MS,c['args'])
        for c in calls:
            if 'stdin' in c:
                self.assertEqual(json.loads(c['stdin'])['data']['epochStartMs'],EXPECTED_MS)
        for c in calls:
            if 'command' in c:
                self.assertEqual(c['args'][:4],['--context','fixture-context','-n',NS])
        self.assertEqual([c['stdin'] for c in calls if 'stdin' in c], [c['stdin'] for c in calls if 'stdin' in c][:1]*2)

    def test_legacy_string_and_equals_targets(self):
        for prefix in (f'K="kubectl --context fixture-context -n {NS}"',f'K=(kubectl --namespace={NS} --context=fixture-context)', f'declare -ar K=(kubectl --context fixture-context -n {NS})', f'declare -r K="kubectl --context fixture-context -n {NS}"'):
            with self.subTest(prefix=prefix):
                r,_=self.run_shell(prefix=prefix)
                self.assertEqual(r.returncode,0,r.stderr)

    def test_invalid_prefixes_refuse_before_queries(self):
        for prefix in ('unset K','K=','K="kubectl"',f'K="kubectl -n {NS}"','K="kubectl --context fixture-context"',
                       f'K="kubectl --context fixture-context --context ambient -n {NS}"',
                       f'K="kubectl --context fixture-context -n {NS} --kubeconfig other"',
                       f'K=(kubectl --context fixture-context -n {NS} get)',
                       f'K=(kubectl --context "" -n {NS})',f'K=(kubectl --context fixture-context -n "{NS} bad")',
                       f'K="kubectl --context \'fixture-context\' -n {NS}"',
                       f'K=(kubectl --context fixture-context -n {NS} --namespace={NS})',
                       f'K=(echo --context fixture-context -n {NS})',
                       'unset K; declare -A K=([0]=kubectl [1]=--context [2]=fixture-context [3]=-n [4]=fixture-ns) || exit 1'):
            with self.subTest(prefix=prefix):
                r,t=self.run_shell(prefix=prefix)
                self.assert_refused(r,t,True)

    def test_ambient_target_is_ignored(self):
        r,t=self.run_shell(env={'CTX':'ambient-other','NS':'ambient-other','KUBECONFIG':'/not/a/real/config'})
        self.assertEqual(r.returncode,0,r.stderr)
        self.assertEqual(t[0]['args'][:4],['--context','fixture-context','-n',NS])

    def test_explicit_wrong_context_read_failure(self):
        f=fixture();f['rules'][0]['rc']=1
        r,t=self.run_shell(f,prefix=f'K=(kubectl --context wrong-context -n {NS})')
        self.assert_refused(r,t)
        self.assertEqual(t[0]['args'][1],'wrong-context')

    def test_wrong_namespace_evidence(self):
        r,t=self.run_shell(prefix='K=(kubectl --context fixture-context -n wrong-namespace)')
        self.assert_refused(r,t)

    def test_missing_or_failed_reads(self):
        for index in range(4):
            for output, rc in (('',0),('',1),('not-json',0),('{}',0)):
                # Confirmed absent Deployment is separately supported.
                if index==3 and output=='' and rc==0: continue
                with self.subTest(index=index,output=output,rc=rc):
                    f=fixture();f['rules'][index].update(output=output,rc=rc)
                    r,t=self.run_shell(f);self.assert_refused(r,t)

    def test_emptydir_and_unused_claim(self):
        for backing in ({'name':'data','emptyDir':{}},
                        {'name':'data','persistentVolumeClaim':{'claimName':'unrelated-stale-pvc'}},
                        {'name':'data','emptyDir':{},'persistentVolumeClaim':{'claimName':'data-order-matcher-cluster-0'}}):
            with self.subTest(backing=backing):
                p=copy.deepcopy(POD);p['spec']['volumes']=[backing]
                r,t=self.run_shell(fixture(pod=p),prefix=f'K=(kubectl --context gke-fixture-emptydir -n {NS})');self.assert_refused(r,t)
                self.assertEqual(t[0]['args'][1],'gke-fixture-emptydir')
                self.assertEqual(len(t),1,'must not consult stale PVC on emptyDir tier')

    def test_invalid_storage_evidence(self):
        changes = [
            ('pod', lambda p:p['metadata'].update(deletionTimestamp='2025-04-02T00:00:00Z')),
            ('pod', lambda p:p['metadata'].update(ownerReferences=[])),
            ('pod', lambda p:p['spec']['containers'][0]['env'][0].update(value='/tmp/data')),
            ('pod', lambda p:p['spec']['containers'][0]['volumeMounts'][0].update(subPath='archive')),
            ('pod', lambda p:p['spec']['containers'][0]['volumeMounts'][0].update(readOnly=True)),
            ('pod', lambda p:p['spec']['containers'][0]['volumeMounts'].append({'name':'other','mountPath':'/data/log'})),
            ('pvc', lambda p:p['status'].update(phase='Pending')),
            ('pvc', lambda p:p['metadata'].update(uid='')),
            ('pvc', lambda p:p['metadata'].update(namespace='elsewhere')),
            ('pvc', lambda p:p['metadata'].update(name='other')),
            ('pvc', lambda p:p['metadata'].update(deletionTimestamp='2025-04-02T00:00:00Z')),
            ('pvc', lambda p:p['spec'].update(volumeName='')),
            ('pv', lambda p:p['status'].update(phase='Released')),
            ('pv', lambda p:p['spec']['claimRef'].update(uid='old-claim')),
            ('pv', lambda p:p['spec']['claimRef'].update(namespace='elsewhere')),
            ('pv', lambda p:p['spec']['claimRef'].update(name='unrelated')),
        ]
        for i,(kind,change) in enumerate(changes):
            with self.subTest(case=i,kind=kind):
                values={'pod':copy.deepcopy(POD),'pvc':copy.deepcopy(PVC),'pv':copy.deepcopy(PV)}
                change(values[kind]);r,t=self.run_shell(fixture(**values));self.assert_refused(r,t)

    def test_invalid_timestamps(self):
        for ts in ('','now','2025-02-30T00:00:00Z','2025-04-01T00:00:00+00:00','2025-04-01','1969-12-31T23:59:59Z','2025-04-01T00:00:00Z\n'):
            with self.subTest(ts=ts):
                p=copy.deepcopy(PVC);p['metadata']['creationTimestamp']=ts
                r,t=self.run_shell(fixture(pvc=p));self.assert_refused(r,t)

    def test_fractional_timestamp_exact_milliseconds(self):
        p=copy.deepcopy(PVC);p['metadata']['creationTimestamp']='2025-04-01T00:00:00.123456Z'
        r,t=self.run_shell(fixture(pvc=p))
        self.assertEqual(r.returncode,0,r.stderr)
        self.assertIn('--from-literal=epochStartMs=1743465600123',next(c['args'] for c in t if 'create' in c.get('args',[])))

    def test_disabled_is_explicit_and_distinct(self):
        r,t=self.run_shell(env={'REPLAY_ANCHOR_MODE':'disabled'})
        self.assertEqual(r.returncode,0,r.stderr);self.assertEqual(t,[])
        self.assertIn('RESULT:0:disabled:uninspected',r.stdout);self.assertNotIn('anchor stored:',r.stdout)
        r,t=self.run_shell(prefix='unset K',env={'REPLAY_ANCHOR_MODE':'disabled'})
        self.assert_refused(r,t,True)
        r,t=self.run_shell(env={'REPLAY_ANCHOR_MODE':'auto'})
        self.assert_refused(r,t,True)

    def test_absent_producer_distinct_from_inspection_failure(self):
        r,t=self.run_shell(fixture(deployment=''))
        self.assertEqual(r.returncode,0,r.stderr)
        self.assertIn('RESULT:0:stored:absent',r.stdout)
        self.assertFalse(any('rollout' in c.get('args',[]) for c in t))
        self.assertIn('--ignore-not-found',next(c['args'] for c in t if 'deploy' in c.get('args',[])))

    def test_dryrun_apply_restart_rollout_failures(self):
        for index in range(4,8):
            with self.subTest(index=index):
                f=fixture();f['rules'][index]['rc']=1
                # || exercises the Bash errexit suppression that used to hide inner errors.
                r,t=self.run_shell(f,script='stamp_replay_epoch || { echo "RESULT:1:$REPLAY_ANCHOR_STATUS:$REPLAY_ANCHOR_PRODUCER"; exit 1; }; echo FALSE_SUCCESS')
                self.assertNotEqual(r.returncode,0,r.stdout+r.stderr);self.assertNotIn('FALSE_SUCCESS',r.stdout)
                if index==4:
                    self.assertFalse(any('apply' in c.get('args',[]) for c in t));self.assertNotIn('anchor stored:',r.stdout)
                if index==5:
                    self.assertFalse(any('restart' in c.get('args',[]) for c in t));self.assertNotIn('anchor stored:',r.stdout)
                if index==6:
                    self.assertFalse(any('status' in c.get('args',[]) for c in t));self.assertIn('stored:restart-failed',r.stdout)
                if index==7:self.assertIn('stored:rollout-failed',r.stdout)

    def test_empty_dryrun_refuses_apply(self):
        f=fixture();f['rules'][4]['output']='  \n'
        r,t=self.run_shell(f);self.assert_refused(r,[c for c in t if 'create' not in c.get('args',[])])
        self.assertFalse(any('apply' in c.get('args',[]) for c in t))

    def test_fetch_functions_unchanged(self):
        baseline=subprocess.check_output(['git','show','a0d6da0b:scripts/yu15/lib-replay-epoch.sh'],cwd=ROOT,text=True)
        self.assertEqual(LIB.read_text().split('# Bring-up only.',1)[1],baseline.split('# Bring-up only.',1)[1])

    def test_start_kind_actual_script_propagates_failure(self):
        # Full original caller with real metadata helper and synthetic command outputs.
        f=fixture(pod='')
        f['rules'] += [
            {'command':'kind','contains':['get','clusters'],'output':'fixture-kind\n'},
            {'command':'kind','contains':['load','docker-image'],'output':''},
            {'command':'docker','contains':['image','inspect'],'output':'{}'},
            {'contains':['kustomize'],'output':'image: traderx/cluster-node:fixture\n'},
            {'contains':['get','statefulset'],'output':''},
            {'contains':['get','namespace'],'output':'traderx'},
            {'contains':['apply'],'output':'configured'},
            {'contains':['rollout','status'],'output':'complete'},
            {'contains':['get','pods'],'output':'FALSE_FINAL_PODS'},
        ]
        script=f'bash "{ROOT}/scripts/yu15/start-cluster-kind.sh"'
        r,t=self.run_shell(f,script=script,env={'CLUSTER_IMAGE':'traderx/cluster-node:fixture','KIND_CLUSTER_NAME':'fixture-kind','RIG_OFFLINE':'1'})
        self.assertNotEqual(r.returncode,0,r.stdout+r.stderr)
        self.assertIn('[epoch] unavailable:',r.stderr);self.assertNotIn('[ok] YU15 cluster up',r.stdout)
        self.assertFalse(any('pods' in c.get('args',[]) for c in t))
        # Same full caller succeeds on the valid storage branch, proving the refusal was reached.
        good=fixture()
        for rule in good['rules'][:4]:
            value=rule['output']
            if isinstance(value,dict):
                value=copy.deepcopy(value)
                if value.get('metadata',{}).get('namespace'): value['metadata']['namespace']='traderx'
                if value.get('spec',{}).get('claimRef'): value['spec']['claimRef']['namespace']='traderx'
                rule['output']=value
        good['rules'] += f['rules'][8:]
        r,t=self.run_shell(good,script=script,env={'CLUSTER_IMAGE':'traderx/cluster-node:fixture','KIND_CLUSTER_NAME':'fixture-kind','RIG_OFFLINE':'1'})
        self.assertEqual(r.returncode,0,r.stdout+r.stderr)
        self.assertIn('[ok] YU15 cluster up',r.stdout)

    def test_runner_actual_rebuild_function_propagates_failure(self):
        source=function_source(CALLER_ROOT/'scripts/yu15/run-proofs.sh','rebuild_fresh_epoch')
        # Use actual one-line fail_hard definition, never fabricate its branch.
        fail=next(line for line in (CALLER_ROOT/'scripts/yu15/run-proofs.sh').read_text().splitlines() if line.startswith('fail_hard() {'))
        f=fixture(pod='');f['rules'] += [{'contains':[v],'output':''} for v in ('scale','wait','delete','rollout')]
        script='\n'.join([source,fail,'CTX=fixture-context; NS=fixture-ns',
              'python3() { if [[ \"$1\" == */proof-cleanup.py ]]; then return 0; else command python3 \"$@\"; fi; }',
              '_k() { \"${K[@]}\" \"$@\"; }','current_image() { echo fixture; }','gateway_image() { echo fixture; }',
              'ensure_image_on_nodes() { :; }','assert_members_up() { :; }','await_cluster_writable() { :; }',
              'roll_feed_adapter() { echo FALSE_FEED; }','rebuild_fresh_epoch; echo FALSE_CONTINUED'])
        # Accepted base uses a string; reviewed RI17/RI20 caller uses an array.
        # The extracted function retains reset permission/checks; only unrelated cleanup journal
        # preparation is stubbed in this offline fixture so the anchor call is reached.
        r,t=self.run_shell(f,prefix=PREFIX if '_k ' in source else f'K="kubectl --context fixture-context -n {NS}"',script=script,env={'ALLOW_PROOF_RESET':'1'})
        self.assertNotEqual(r.returncode,0,r.stdout+r.stderr)
        self.assertIn('could not be stamped',r.stderr);self.assertNotIn('FALSE_',r.stdout)

    def test_replay_proof_actual_restore_statement_propagates_failure(self):
        text=(CALLER_ROOT/'scripts/proofs/yu17-taq-replay.sh').read_text()
        call=next(line for line in text.splitlines() if line.startswith('stamp_replay_epoch ||'))
        fail=next(line for line in text.splitlines() if line.startswith('fail() {'))
        r,t=self.run_shell(fixture(pod=''),script=fail+'\n'+call+'\necho FALSE_CONTINUED')
        self.assertNotEqual(r.returncode,0,r.stdout+r.stderr);self.assertNotIn('FALSE_',r.stdout)

    def test_replay_proof_cleanup_failure_changes_success_exit(self):
        text=(CALLER_ROOT/'scripts/proofs/yu17-taq-replay.sh').read_text()
        cleanup=function_source(CALLER_ROOT/'scripts/proofs/yu17-taq-replay.sh','cleanup')
        r,t=self.run_shell(fixture(pod=''),script=cleanup+'\nSTAMP_TOUCHED=1; trap cleanup EXIT; exit 0')
        self.assertNotEqual(r.returncode,0,r.stdout+r.stderr)
        self.assertIn('restamp failed',r.stdout+r.stderr)
        r,t=self.run_shell(fixture(pod=''),script=cleanup+'\nSTAMP_TOUCHED=1; trap cleanup EXIT; exit 7')
        self.assertEqual(r.returncode,7,r.stdout+r.stderr)

    def test_full_replay_proof_refuses_disabled_before_queries(self):
        proof=Path(os.environ.get('REPLAY_TEST_PROOF_FILE', str(ROOT/'scripts/proofs/yu17-taq-replay.sh')))
        r,t=self.run_shell(script=f'bash "{proof}"',env={'REPLAY_ANCHOR_MODE':'disabled','EXPECT':'after'})
        self.assertNotEqual(r.returncode,0,r.stdout+r.stderr)
        self.assertIn('cannot verify restoration',r.stderr)
        self.assertEqual(t,[])

    def test_cleanup_preserves_original_failure(self):
        cleanup=function_source(CALLER_ROOT/'scripts/proofs/yu17-taq-replay.sh','cleanup')
        r,t=self.run_shell(script=cleanup+'\nSTAMP_TOUCHED=0; trap cleanup EXIT; exit 7')
        self.assertEqual(r.returncode,7,r.stdout+r.stderr)


if __name__ == '__main__':
    unittest.main(verbosity=2)
