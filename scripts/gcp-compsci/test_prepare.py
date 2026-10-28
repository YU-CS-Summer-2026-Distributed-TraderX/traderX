"""Offline render controls: wrong project and public exposure must be rejected."""
import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest
import os
import subprocess
import yaml

spec=importlib.util.spec_from_file_location('prepare',Path(__file__).with_name('prepare.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class Rendering(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory()
        cls.output=Path(cls.temp.name)/'candidate'
        module.prepare(cls.output,'test-candidate','10.93.0.10')
        cls.docs=list(yaml.safe_load_all((cls.output/'candidate.yaml').read_text()))

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def test_nonempty_complete_candidate(self):
        module.validate(self.docs)
        self.assertGreater(len(self.docs),30)
        engine=next(d for d in self.docs if d['kind']=='StatefulSet')
        self.assertEqual(engine['spec']['replicas'],3)
        self.assertEqual(engine['spec']['template']['spec']['nodeSelector'],
                         {'cloud.google.com/gke-nodepool':'engine-pool'})

    def test_member_archive_storageclass_is_rendered(self):
        # Members claim a generic ephemeral volume of this class; a render without it leaves them Pending.
        engine=next(d for d in self.docs if d['kind']=='StatefulSet')
        claimed={v['ephemeral']['volumeClaimTemplate']['spec']['storageClassName']
                 for v in engine['spec']['template']['spec'].get('volumes',[]) if 'ephemeral' in v}
        self.assertEqual(claimed,{'order-matcher-archive'})
        sc=[d for d in self.docs if d['kind']=='StorageClass']
        self.assertEqual([d['metadata']['name'] for d in sc],['order-matcher-archive'])
        self.assertNotIn('namespace',sc[0]['metadata'])
        self.assertEqual(sc[0]['provisioner'],'pd.csi.storage.gke.io')

    def test_admission_expects_the_rendered_population(self):
        # A render that adds a workload (promtail did) must move admit.py's counts with it, or admission
        # refuses the candidate the procedure of record just rendered.
        spec=importlib.util.spec_from_file_location('population_admit',Path(__file__).with_name('admit.py'))
        admit=importlib.util.module_from_spec(spec);spec.loader.exec_module(admit)
        slots=[c['image'] for d in self.docs for pod in [module.podspec(d)] if pod
               for c in pod.get('containers',[])+pod.get('initContainers',[])]
        private={i for i in slots if i.startswith(module.REGISTRY)}
        self.assertEqual((len(private),len(set(slots))-len(private),len(slots)),
                         (admit.PRIVATE_IMAGES,admit.UPSTREAM_IMAGES,admit.SLOTS))

    def test_promtail_ships_this_namespace_from_every_node(self):
        ds=next(d for d in self.docs if d['kind']=='DaemonSet' and d['metadata']['name']=='promtail')
        pod=ds['spec']['template']['spec']
        self.assertNotIn('nodeSelector',pod)
        self.assertIn('traderx-engine',[t['key'] for t in pod['tolerations']])
        for kind in ('ClusterRole','ClusterRoleBinding'):
            d=next(d for d in self.docs if d['kind']==kind)
            self.assertNotIn('namespace',d['metadata'])
            self.assertEqual(d['metadata']['name'],f'promtail-{module.NAMESPACE}')
        binding=next(d for d in self.docs if d['kind']=='ClusterRoleBinding')
        self.assertEqual(binding['subjects'][0]['namespace'],module.NAMESPACE)
        config=next(d for d in self.docs if d['metadata']['name']=='observability-promtail-config')
        self.assertIn(f'/var/log/pods/{module.NAMESPACE}_*',config['data']['config.yaml'])

    def test_wrong_project_image(self):
        docs=copy.deepcopy(self.docs)
        pod=next(module.podspec(d) for d in docs if module.podspec(d))
        pod['containers'][0]['image']='us-east1-docker.pkg.dev/other-project/repo/cluster-node:x'
        with self.assertRaises(AssertionError): module.validate(docs)

    def test_old_bucket(self):
        docs=copy.deepcopy(self.docs)
        docs[0]['metadata']['annotations']={'test':'gs://traderx-505400-risk-extracts'}
        with self.assertRaises(AssertionError): module.validate(docs)

    def test_public_service(self):
        docs=copy.deepcopy(self.docs)
        next(d for d in docs if d['kind']=='Service')['spec']['type']='LoadBalancer'
        with self.assertRaises(AssertionError): module.validate(docs)

    def test_unmapped_local_image(self):
        docs=copy.deepcopy(self.docs)
        api=next(d for d in docs if d['kind']=='Deployment' and d['metadata']['name']=='api-explorer')
        module.podspec(api)['containers'][0]['image']='traderx-api-explorer:local'
        with self.assertRaises(AssertionError): module.validate(docs)

    def test_empty_population(self):
        with self.assertRaises(AssertionError): module.validate([])

    def test_missing_console(self):
        docs=[d for d in self.docs if not (d['kind']=='Deployment' and d['metadata']['name']=='web-front-end-console')]
        with self.assertRaises(AssertionError): module.validate(docs)

    def test_missing_proxy_dependency(self):
        docs=[d for d in self.docs if not (d['kind']=='Service' and d['metadata']['name']=='grafana')]
        with self.assertRaises(AssertionError): module.validate(docs)

    def test_no_secrets_or_old_restore(self):
        self.assertFalse(any(d['kind']=='Secret' for d in self.docs))
        engine=next(d for d in self.docs if d['kind']=='StatefulSet')
        self.assertEqual(engine['spec']['template']['spec']['initContainers'],[])

    def test_database_bootstrap_and_clients_use_same_secret(self):
        seen = set()
        for doc in self.docs:
            pod = module.podspec(doc)
            if not pod: continue
            for c in pod.get('containers',[]) + pod.get('initContainers',[]):
                for entry in c.get('env',[]):
                    if entry['name'] in ('MARIADB_PASSWORD','DATABASE_DBPASS','RISK_EXTRACT_JDBC_PASSWORD'):
                        self.assertEqual(entry['valueFrom']['secretKeyRef'],
                            {'name':'mariadb-credentials','key':'password'})
                        self.assertNotIn('value',entry); seen.add(doc['metadata']['name'])
        self.assertTrue({'eod-price-db','risk-extract','reference-data','account-service','trade-processor','position-service'} <= seen)

    def test_seeded_grafana_password_removed(self):
        grafana=next(d for d in self.docs if d['metadata']['name']=='grafana' and module.podspec(d))
        entry=next(e for e in module.podspec(grafana)['containers'][0]['env'] if e['name']=='GF_SECURITY_ADMIN_PASSWORD')
        self.assertEqual(entry['valueFrom']['secretKeyRef'],{'name':'grafana-credentials','key':'admin-password'})
        self.assertNotIn('value',entry)

    def test_inline_database_password_is_refused(self):
        docs=copy.deepcopy(self.docs)
        db=next(d for d in docs if d['metadata']['name']=='eod-price-db' and module.podspec(d))
        module.podspec(db)['containers'][0]['env'].append({'name':'MARIADB_PASSWORD','value':'public-default'})
        with self.assertRaises(AssertionError):module.validate(docs)

    def test_fix_acceptor_authenticates_from_the_secret(self):
        # ADR-082: the gateway's FIX sessions need CLIENT1's password, built from auth-secrets, and nothing
        # carries the development opt-out; the console's FIX ticket presents the same key.
        gw=next(d for d in self.docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d))
        env=module.podspec(gw)['containers'][0]['env']; names=[e['name'] for e in env]
        self.assertEqual(env[names.index('FIX_SESSION_PASSWORD')]['valueFrom']['secretKeyRef'],
                         {'name':'auth-secrets','key':'fix-session-password'})
        self.assertEqual(env[names.index('FIX_SESSION_AUTH')]['value'],'CLIENT1:$(FIX_SESSION_PASSWORD):*')
        self.assertLess(names.index('FIX_SESSION_PASSWORD'),names.index('FIX_SESSION_AUTH'))
        console=next(d for d in self.docs if d['metadata']['name']=='web-front-end-console' and module.podspec(d))
        self.assertIn({'name':'FIX_SESSION_PASSWORD','valueFrom':{'secretKeyRef':{'name':'auth-secrets','key':'fix-session-password'}}},
                      module.podspec(console)['containers'][0]['env'])
        plan=__import__('json').loads((self.output/'plan.json').read_text())
        self.assertIn('fix-session-password',plan['requiredSecretKeys']['auth-secrets'])

    def test_unauthenticated_fix_acceptor_is_refused(self):
        for mutate in ('drop-auth','literal-auth','dev-opt-out'):
            docs=copy.deepcopy(self.docs)
            gw=next(d for d in docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d))
            env=module.podspec(gw)['containers'][0]['env']
            if mutate=='drop-auth': env[:]=[e for e in env if e['name']!='FIX_SESSION_AUTH']
            if mutate=='literal-auth': next(e for e in env if e['name']=='FIX_SESSION_AUTH')['value']='CLIENT1:guessable:*'
            if mutate=='dev-opt-out': env.append({'name':'ALLOW_DEV_SECRETS','value':'true'})
            with self.assertRaises(AssertionError,msg=mutate): module.validate(docs)

    def test_edge_redirects_stay_relative(self):
        # Behind the HTTPS load balancer the edge proxy sees http on :8080; an absolute redirect would send
        # /grafana to http://<host>:8080/grafana/ (reproduced 2026-10-27 with this line removed).
        edge=next(d for d in self.docs if d['kind']=='ConfigMap' and d['metadata']['name']=='edge-proxy-config')
        self.assertIn('absolute_redirect off;',edge['data']['default.conf'])

    def test_gateway_subscribes_to_the_control_feed(self):
        gw=next(d for d in self.docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d))
        self.assertIn({'name':'CONTROL_FEED_SUBSCRIBER','value':'1'},module.podspec(gw)['containers'][0]['env'])
        docs=copy.deepcopy(self.docs)
        gw=next(d for d in docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d))
        env=module.podspec(gw)['containers'][0]['env']; env[:]=[e for e in env if e['name']!='CONTROL_FEED_SUBSCRIBER']
        with self.assertRaises(AssertionError): module.validate(docs)

    def test_gateway_reports_fix_fills(self):
        docs=copy.deepcopy(self.docs)
        gw=next(d for d in docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d))
        module.podspec(gw)['containers'][0]['env'].append({'name':'FIX_FILL_FEED','value':'0'})
        with self.assertRaises(AssertionError): module.validate(docs)

    def member_script(self, docs=None):
        engine=next(d for d in (docs or self.docs) if d['kind']=='StatefulSet' and d['metadata']['name']=='order-matcher-cluster')
        return module.podspec(engine)['containers'][0]['command']

    def test_member_shell_forwards_sigterm(self):
        # FR-GMR-04, run for real: the rendered wrapper with a stand-in JVM that exits 143 on TERM, as the JVM does.
        import signal, time
        command=self.member_script()
        self.assertEqual(command[:2],['/bin/sh','-c'])
        stub="sh -c 'trap \"exit 143\" TERM; while :; do sleep 0.1; done'"
        script=command[2].replace('java -cp /opt/app/classes:/opt/app/lib/* finos.traderx.ordermatcher.cluster.ClusterNodeMain',stub)
        self.assertNotEqual(script,command[2],'the JVM launch line moved; update the stand-in')
        def run(action):
            p=subprocess.Popen(['/bin/sh','-c',script]); time.sleep(0.5)
            child=subprocess.run(['pgrep','-P',str(p.pid)],capture_output=True,text=True).stdout.split()
            action(p,child); t=time.time(); rc=p.wait(timeout=5); return rc,time.time()-t
        rc,took=run(lambda p,child:p.send_signal(signal.SIGTERM))
        self.assertEqual(rc,143); self.assertLess(took,3)
        rc,_=run(lambda p,child:os.kill(int(child[0]),signal.SIGKILL))   # the crash harness's kill of the JVM
        self.assertEqual(rc,137)

    def test_member_shell_without_forwarding_is_refused(self):
        docs=copy.deepcopy(self.docs)
        engine=next(d for d in docs if d['kind']=='StatefulSet' and d['metadata']['name']=='order-matcher-cluster')
        module.podspec(engine)['containers'][0]['command']=['/bin/sh','-c',
            'java -cp /opt/app/classes:/opt/app/lib/* finos.traderx.ordermatcher.cluster.ClusterNodeMain & wait $!']
        with self.assertRaises(AssertionError): module.validate(docs)

    def test_projection_pulls_the_durable_feed(self):
        tp=next(d for d in self.docs if d['metadata']['name']=='trade-processor' and module.podspec(d))
        env={e['name']:e.get('value') for e in module.podspec(tp)['containers'][0]['env']}
        self.assertEqual(env['PROJECTION_FEED_MODE'],'jetstream')
        self.assertNotIn('TRADE_PROJECTION_MODE',env)   # row projection: SQL trades stays complete
        docs=copy.deepcopy(self.docs)
        tp=next(d for d in docs if d['metadata']['name']=='trade-processor' and module.podspec(d))
        next(e for e in module.podspec(tp)['containers'][0]['env'] if e['name']=='PROJECTION_FEED_MODE')['value']='core'
        with self.assertRaises(AssertionError): module.validate(docs)
        docs=copy.deepcopy(self.docs)
        nats=next(d for d in docs if d['kind']=='Deployment' and d['metadata']['name']=='nats')
        next(v for v in module.podspec(nats)['volumes'] if v['name']=='data').pop('persistentVolumeClaim')
        with self.assertRaises(AssertionError): module.validate(docs)

    def test_all_workloads_have_complete_scheduler_requests(self):
        for doc in self.docs:
            pod=module.podspec(doc)
            if not pod: continue
            for c in pod['containers']:
                self.assertTrue({'cpu','memory'} <= set(c['resources']['requests']),doc['metadata']['name'])

    def test_angular_uses_the_packaged_start_script(self):
        doc=next(d for d in self.docs if d['metadata']['name']=='web-front-end-angular' and module.podspec(d))
        self.assertEqual(module.podspec(doc)['containers'][0]['args'],['npm','run','start'])

    def test_half_specified_lock_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(AssertionError):
                module.prepare(Path(temp)/'candidate','test','10.93.0.10',Path('missing'),None)

    def test_public_platform_and_private_registry_binding_refused(self):
        docs=copy.deepcopy(self.docs)
        # Minimal public/foreign controls are independent of the real lock files.
        pod=next(module.podspec(d) for d in docs if module.podspec(d))
        pod['containers'][0]['image']='nginx:test'
        pub={'nginx:test':{'reference':'nginx@sha256:'+'a'*64,'manifest':{'manifests':[
            {'digest':'sha256:'+'a'*64,'platform':{'architecture':'arm64','os':'linux'}}]}}}
        with self.assertRaises(AssertionError):module.pin_images(docs,{'images':{}},pub)


class UiMetadata(unittest.TestCase):
    def render(self, folder, epoch):
        target=folder/'web-front-end/angular/main/assets'
        target.mkdir(parents=True,exist_ok=True)
        env=dict(os.environ,SOURCE_DATE_EPOCH=epoch)
        result=subprocess.run(['bash',str(module.ROOT/'pipeline/install-generated-ui-state-metadata.sh'),
                               'YU18-risk-integration',str(folder),str(folder/'components')],
                              env=env,capture_output=True,text=True)
        return result,target/'state-ui.json'

    def test_fixed_clock_reproduces_actual_producer(self):
        with tempfile.TemporaryDirectory() as temp:
            a,pa=self.render(Path(temp)/'a','1791690000')
            b,pb=self.render(Path(temp)/'b','1791690000')
            self.assertEqual(a.returncode,0,a.stderr);self.assertEqual(b.returncode,0,b.stderr)
            self.assertEqual(pa.read_bytes(),pb.read_bytes())
            import json
            value=json.loads(pa.read_text())
            self.assertTrue(value['sourceBranchUrl'].startswith('https://github.com/'))

    def test_different_clock_changes_produced_content(self):
        with tempfile.TemporaryDirectory() as temp:
            a,pa=self.render(Path(temp)/'a','1791690000')
            b,pb=self.render(Path(temp)/'b','1791690001')
            self.assertEqual(a.returncode,0,a.stderr);self.assertEqual(b.returncode,0,b.stderr)
            self.assertNotEqual(pa.read_bytes(),pb.read_bytes())

    def test_malformed_clock_refuses_before_write(self):
        with tempfile.TemporaryDirectory() as temp:
            result,path=self.render(Path(temp),'not-a-time')
            self.assertNotEqual(result.returncode,0)
            self.assertFalse(path.exists())


class ExplorerMetadata(unittest.TestCase):
    def render(self, folder, epoch):
        (folder/'trade-service').mkdir(parents=True)
        result=subprocess.run(['bash',str(module.ROOT/'pipeline/install-generated-api-explorer.sh'),
                               'YU18-risk-integration',str(folder),str(folder/'components')],
                              env=dict(os.environ,SOURCE_DATE_EPOCH=epoch,npm_config_offline='true'),
                              capture_output=True,text=True)
        return result,folder/'api-explorer/catalog.json'

    def test_fixed_clock_reproduces_explorer(self):
        with tempfile.TemporaryDirectory() as temp:
            a,pa=self.render(Path(temp)/'a','1791690000')
            b,pb=self.render(Path(temp)/'b','1791690000')
            self.assertEqual(a.returncode,0,a.stderr);self.assertEqual(b.returncode,0,b.stderr)
            self.assertEqual(pa.read_bytes(),pb.read_bytes())
            import json
            self.assertTrue(json.loads(pa.read_text())['services'])

    def test_changed_clock_changes_explorer(self):
        with tempfile.TemporaryDirectory() as temp:
            a,pa=self.render(Path(temp)/'a','1791690000')
            b,pb=self.render(Path(temp)/'b','1791690001')
            self.assertEqual(a.returncode,0,a.stderr);self.assertEqual(b.returncode,0,b.stderr)
            self.assertNotEqual(pa.read_bytes(),pb.read_bytes())

    def test_invalid_clock_refuses_catalog(self):
        with tempfile.TemporaryDirectory() as temp:
            result,path=self.render(Path(temp),'invalid')
            self.assertNotEqual(result.returncode,0)
            self.assertFalse(path.exists())


if __name__=='__main__': unittest.main()
