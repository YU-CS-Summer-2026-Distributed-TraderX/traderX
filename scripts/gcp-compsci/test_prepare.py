"""Offline render controls: wrong project and public exposure must be rejected."""
import copy
import json
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
        # Members claim a retained volume of this class; a render without it leaves them Pending.
        engine=next(d for d in self.docs if d['kind']=='StatefulSet')
        claimed={t['spec']['storageClassName'] for t in engine['spec']['volumeClaimTemplates']}
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
        self.assertEqual(env[names.index('FIX_SESSION_AUTH')]['value'],
                         'CLIENT1:$(FIX_SESSION_PASSWORD):*,EXTQFJ:$(FIX_EXT_PASSWORD):22214|42422')
        self.assertLess(names.index('FIX_EXT_PASSWORD'),names.index('FIX_SESSION_AUTH'))
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

    def test_external_fix_compid_is_tls_only_from_the_secret(self):
        # ADR-095: EXTQFJ on its own TLS socket, its keystore and password from Secret fix-tls; no public Service here.
        gw=next(d for d in self.docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d))
        pod=module.podspec(gw); c=pod['containers'][0]; env={e['name']:e for e in c['env']}
        self.assertEqual((env['FIX_TLS_PORT']['value'],env['FIX_TLS_COMPIDS']['value']),('18143','EXTQFJ'))
        self.assertNotIn('EXTQFJ',env['FIX_SESSION_COMPIDS']['value'].split(','))
        for var,key in (('FIX_EXT_PASSWORD','external-password'),('FIX_SSL_KEYSTORE_PASSWORD','keystore-password')):
            self.assertEqual(env[var]['valueFrom']['secretKeyRef'],{'name':'fix-tls','key':key})
        self.assertIn({'name':'fix-tls','secret':{'secretName':'fix-tls','items':[{'key':'keystore.p12','path':'keystore.p12'}]}},
                      pod['volumes'])
        self.assertIn({'name':'fix-tls','containerPort':18143},c['ports'])
        plan=__import__('json').loads((self.output/'plan.json').read_text())
        self.assertEqual(plan['requiredSecretKeys']['fix-tls'],['external-password','keystore-password'])
        for mutate in ('compid-on-plaintext','literal-password','no-accounts','keystore-not-secret'):
            docs=copy.deepcopy(self.docs)
            gw=next(d for d in docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d))
            pod=module.podspec(gw); env={e['name']:e for e in pod['containers'][0]['env']}
            if mutate=='compid-on-plaintext': env['FIX_SESSION_COMPIDS']['value']+=',EXTQFJ'
            if mutate=='literal-password': env['FIX_SESSION_AUTH']['value']=env['FIX_SESSION_AUTH']['value'].replace('$(FIX_EXT_PASSWORD)','guess')
            if mutate=='no-accounts': env['FIX_SESSION_AUTH']['value']=env['FIX_SESSION_AUTH']['value'].replace(':22214|42422',':*')
            if mutate=='keystore-not-secret': pod['volumes']=[v for v in pod['volumes'] if v['name']!='fix-tls']
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

    def test_gateway_reports_fix_fills_from_the_stream(self):
        gw=next(d for d in self.docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d))
        self.assertIn({'name':'FIX_FILL_FEED','value':'jetstream'},module.podspec(gw)['containers'][0]['env'])
        for value in ('0','core','auto'):
            docs=copy.deepcopy(self.docs)
            gw=next(d for d in docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d))
            next(e for e in module.podspec(gw)['containers'][0]['env'] if e['name']=='FIX_FILL_FEED')['value']=value
            with self.assertRaises(AssertionError): module.validate(docs)

    def test_fix_sessions_persist_and_status_has_a_read_model(self):
        # ADR-086: FR-FIX11 resume on any replica, FR-FIX04 status from the read model; either one dropped is refused.
        gw=next(d for d in self.docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d))
        env=module.podspec(gw)['containers'][0]['env']
        self.assertIn({'name':'FIX_SESSION_STORE','value':'jetstream'},env)
        self.assertIn({'name':'ORDER_READMODEL_URL','value':'http://trade-processor:18091'},env)
        self.assertIn({'name':'FIX_SESSION_RESET_TIME','value':'17:00:00'},env)
        self.assertIn({'name':'FIX_SESSION_TIME_ZONE','value':'America/New_York'},env)
        for name in ('FIX_SESSION_STORE','ORDER_READMODEL_URL','FIX_SESSION_RESET_TIME'):
            docs=copy.deepcopy(self.docs)
            gw=next(d for d in docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d))
            env=module.podspec(gw)['containers'][0]['env']; env[:]=[e for e in env if e['name']!=name]
            with self.assertRaises(AssertionError, msg=name): module.validate(docs)

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

    def test_member_log_outlives_its_pod(self):
        # FR-GMR-01 (ADR-087): a retained claim per member, or one scale-down destroys a majority of the logs.
        engine=next(d for d in self.docs if d['kind']=='StatefulSet' and d['metadata']['name']=='order-matcher-cluster')
        self.assertEqual([t['metadata']['name'] for t in engine['spec']['volumeClaimTemplates']],['data'])
        self.assertEqual(engine['spec']['persistentVolumeClaimRetentionPolicy'],{'whenScaled':'Retain','whenDeleted':'Retain'})
        # the shape this replaced: /data as a generic ephemeral volume, deleted with the pod
        docs=copy.deepcopy(self.docs)
        engine=next(d for d in docs if d['kind']=='StatefulSet' and d['metadata']['name']=='order-matcher-cluster')
        template=engine['spec'].pop('volumeClaimTemplates')[0]
        module.podspec(engine)['volumes'].append({'name':'data','ephemeral':{'volumeClaimTemplate':{'spec':template['spec']}}})
        with self.assertRaises(AssertionError): module.validate(docs)
        for policy in ({'whenScaled':'Delete','whenDeleted':'Retain'},{'whenScaled':'Retain','whenDeleted':'Delete'},None):
            docs=copy.deepcopy(self.docs)
            engine=next(d for d in docs if d['kind']=='StatefulSet' and d['metadata']['name']=='order-matcher-cluster')
            if policy: engine['spec']['persistentVolumeClaimRetentionPolicy']=policy
            else: engine['spec'].pop('persistentVolumeClaimRetentionPolicy')
            with self.assertRaises(AssertionError, msg=str(policy)): module.validate(docs)

    def workload(self, docs, name):
        return next(d for d in docs if d['kind'] in ('Deployment','StatefulSet') and d['metadata']['name']==name)

    def test_rig_tiers_are_on_their_own_pools(self):
        # FR-PRG-05 (ADR-096): members, gateways and NATS each on their own pool and taint.
        for name,pool,taint in (('order-matcher-cluster','engine-pool','traderx-engine'),
                                ('cluster-gateway','gateway-pool','traderx-gateway'),('nats','nats-pool','traderx-nats')):
            pod=module.podspec(self.workload(self.docs,name))
            self.assertEqual(pod['nodeSelector'],{'cloud.google.com/gke-nodepool':pool},name)
            self.assertIn({'key':taint,'operator':'Equal','value':'true','effect':'NoSchedule'},pod['tolerations'],name)
        self.assertEqual(module.podspec(self.workload(self.docs,'trade-processor'))['nodeSelector'],
                         {'cloud.google.com/gke-nodepool':'default-pool'})
        docs=copy.deepcopy(self.docs)
        module.podspec(self.workload(docs,'cluster-gateway'))['nodeSelector']={'cloud.google.com/gke-nodepool':'default-pool'}
        with self.assertRaises(AssertionError): module.validate(docs)

    def test_gateways_are_one_per_node_counted_by_the_rig_and_drain(self):
        # FR-PRG-06/07: no replica count in the pack, required hostname anti-affinity, a guaranteed whole-CPU pod,
        # and a drain delay longer than the internal LB's deregistration, inside the grace period.
        gw=self.workload(self.docs,'cluster-gateway'); pod=module.podspec(gw); c=pod['containers'][0]
        self.assertNotIn('replicas',gw['spec'])
        self.assertEqual(c['resources'],{'requests':{'cpu':'4','memory':'2Gi'},'limits':{'cpu':'4','memory':'2Gi'}})
        self.assertEqual({e['name']:e.get('value') for e in c['env']}['GATEWAY_DRAIN_DELAY_MS'],'30000')
        self.assertEqual(pod['terminationGracePeriodSeconds'],60)
        module.validate(self.docs)
        def broken(change):
            docs=copy.deepcopy(self.docs); change(self.workload(docs,'cluster-gateway')); return docs
        for change in (lambda d: d['spec'].__setitem__('replicas',1),
                       lambda d: module.podspec(d).pop('affinity'),
                       lambda d: module.podspec(d)['containers'][0]['resources']['requests'].__setitem__('cpu','250m'),
                       lambda d: module.podspec(d).__setitem__('terminationGracePeriodSeconds',30),
                       lambda d: next(e for e in module.podspec(d)['containers'][0]['env']
                                      if e['name']=='GATEWAY_DRAIN_DELAY_MS').__setitem__('value','0')):
            with self.assertRaises(AssertionError): module.validate(broken(change))

    def test_members_run_generational_zgc_with_the_book_cap_pinned(self):
        # FR-PRG-05: generational ZGC at production's 12 GB, 7 CPU / 16Gi, MAX_ACTIVE_BOOKS pinned at 1,024 (ADR-079 §6:
        # unset, the cap follows the heap, and the bench's 6 GB would cut it to ~436); the module flags are kept.
        c=module.podspec(self.workload(self.docs,'order-matcher-cluster'))['containers'][0]
        env={e['name']:e.get('value') for e in c['env']}
        jvm=env['JAVA_TOOL_OPTIONS'].split()
        for flag in ('-XX:+UseZGC','-XX:+ZGenerational','-Xms12g','-Xmx12g','-XX:+AlwaysPreTouch',
                     '-Daeron.socket.so_rcvbuf=4194304','--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED'):
            self.assertIn(flag,jvm)
        self.assertFalse([t for t in jvm if t.startswith('-XX:MaxGCPauseMillis')])
        self.assertEqual(env['MAX_ACTIVE_BOOKS'],'1024')
        self.assertEqual(c['resources'],{'requests':{'cpu':'7','memory':'16Gi'},'limits':{'cpu':'7','memory':'16Gi'}})
        def broken(change):
            docs=copy.deepcopy(self.docs); change(module.podspec(self.workload(docs,'order-matcher-cluster'))['containers'][0]); return docs
        def jvm_is(value):
            return lambda c: next(e for e in c['env'] if e['name']=='JAVA_TOOL_OPTIONS').__setitem__('value',value)
        for change in (jvm_is('-Xms12g -Xmx12g -XX:MaxGCPauseMillis=50'),                       # the G1 shape it replaced
                       jvm_is('-XX:+UseZGC -XX:+ZGenerational -Xms6g -Xmx6g -XX:SoftMaxHeapSize=3g'),   # the bench heap: 1,024 books do not fit
                       lambda c: c.__setitem__('env',[e for e in c['env'] if e['name']!='MAX_ACTIVE_BOOKS'])):
            with self.assertRaises(AssertionError): module.validate(broken(change))

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


class FixTestClients(unittest.TestCase):
    """FR-PRG-11: the rehearsal's load CompIDs, opt-in, each bound to its own test account, passwords from a Secret."""
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory()
        module.prepare(Path(cls.temp.name)/'candidate','test-candidate','10.93.0.10',fix_test_clients=True)
        cls.docs=list(yaml.safe_load_all((Path(cls.temp.name)/'candidate/candidate.yaml').read_text()))
        cls.plan=json.loads((Path(cls.temp.name)/'candidate/plan.json').read_text())

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def gateway(self,docs):
        c=module.podspec(next(d for d in docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d)))['containers'][0]
        return c['env'],{e['name']:e for e in c['env']}

    def test_each_client_trades_only_its_account_with_a_secret_password(self):
        env,by=self.gateway(self.docs); names=[e['name'] for e in env]
        self.assertEqual(by['FIX_SESSION_COMPIDS']['value'],'CLIENT1,'+','.join(f'CLIENT{n}' for n in range(2,9)))
        for n in range(2,9):
            self.assertIn(f',CLIENT{n}:$(FIX_TEST_PASSWORD_{n}):{9900000+n}',by['FIX_SESSION_AUTH']['value'])
            self.assertEqual(by[f'FIX_TEST_PASSWORD_{n}']['valueFrom']['secretKeyRef'],{'name':'fix-test-clients','key':f'client{n}'})
            self.assertLess(names.index(f'FIX_TEST_PASSWORD_{n}'),names.index('FIX_SESSION_AUTH'))
        self.assertEqual(self.plan['requiredSecretKeys']['fix-test-clients'],[f'client{n}' for n in range(2,9)])
        self.assertEqual(self.plan['fixTestClients']['CLIENT8'],'9900008')

    def test_the_default_render_has_no_test_clients(self):
        with tempfile.TemporaryDirectory() as d:
            module.prepare(Path(d)/'c','test-candidate','10.93.0.10')
            docs=list(yaml.safe_load_all((Path(d)/'c/candidate.yaml').read_text()))
        _,by=self.gateway(docs)
        self.assertEqual(by['FIX_SESSION_COMPIDS']['value'],'CLIENT1')
        self.assertNotIn('CLIENT2',by['FIX_SESSION_AUTH']['value'])

    def test_an_unbound_or_literal_client_is_refused(self):
        for mutate,message in (('every-account','CLIENT3 is not bound'),('literal','CLIENT4 without a Secret'),
                               ('after-auth','CLIENT5 without a Secret'),('not-secret','CLIENT6 without a Secret')):
            docs=copy.deepcopy(self.docs); env,by=self.gateway(docs); auth=by['FIX_SESSION_AUTH']
            if mutate=='every-account': auth['value']=auth['value'].replace(':9900003',':*')
            if mutate=='literal': auth['value']=auth['value'].replace('$(FIX_TEST_PASSWORD_4)','guess')
            if mutate=='after-auth': env.remove(by['FIX_TEST_PASSWORD_5']); env.append(by['FIX_TEST_PASSWORD_5'])
            if mutate=='not-secret': by['FIX_TEST_PASSWORD_6'].pop('valueFrom'); by['FIX_TEST_PASSWORD_6']['value']='x'
            with self.assertRaisesRegex(AssertionError,message,msg=mutate): module.validate(docs)

    def test_the_provisioner_keeps_passwords_in_a_private_file(self):
        with tempfile.TemporaryDirectory() as d:
            os.chmod(d,0o700)
            r=subprocess.run([__import__('sys').executable,str(Path(__file__).with_name('provision-fix-test-clients.py')),
                              '--private-dir',d],capture_output=True,text=True,check=True)
            path=Path(d)/'fix-test-clients.json'; clients=json.loads(path.read_text())
            self.assertEqual(path.stat().st_mode&0o777,0o600)
            self.assertEqual({c:v['account'] for c,v in clients.items()},module.FIX_TEST_CLIENTS)
            self.assertIn('No cluster mutation',r.stdout)
            for v in clients.values(): self.assertNotIn(v['password'],r.stdout+r.stderr)


class Population(unittest.TestCase):
    """ADR-096 §6: a declared exception renders onto its workload only, and validate() refuses it anywhere else."""
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory()
        declared=Path(cls.temp.name)/'population.json'
        declared.write_text(json.dumps({'schema':'traderx-compsci-population-v1','imageSourceCommit':'1'*40,
            'workloads':{'order-matcher-cluster':{'image':'cluster-node-member','component':'order-matcher',
                                                   'sourceCommit':'2'*40}}}))
        cls.declared=module.population.load(declared)
        module.prepare(Path(cls.temp.name)/'candidate','test-candidate','10.93.0.10',population_path=declared)
        cls.docs=list(yaml.safe_load_all((Path(cls.temp.name)/'candidate/candidate.yaml').read_text()))

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def images(self,docs,name):
        pod=module.podspec(next(d for d in docs if d['metadata']['name']==name and module.podspec(d)))
        return {c['image'] for c in pod.get('containers',[])+pod.get('initContainers',[]) if c['image'].startswith(module.REGISTRY)}

    def test_members_run_the_member_image_and_the_gateway_the_tip_image(self):
        self.assertEqual(self.images(self.docs,'order-matcher-cluster'),{module.REGISTRY+'cluster-node-member:test-candidate'})
        self.assertEqual(self.images(self.docs,'cluster-gateway'),{module.REGISTRY+'cluster-node:test-candidate'})
        private={c['image'] for d in self.docs for pod in [module.podspec(d)] if pod
                 for c in pod.get('containers',[])+pod.get('initContainers',[]) if c['image'].startswith(module.REGISTRY)}
        self.assertEqual(len(private),13)

    def test_the_member_image_on_another_workload_is_refused(self):
        docs=copy.deepcopy(self.docs)
        pod=module.podspec(next(d for d in docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d)))
        pod['containers'][0]['image']=module.REGISTRY+'cluster-node-member:test-candidate'
        with self.assertRaisesRegex(AssertionError,'cluster-gateway: image differs from the declared population'):
            module.validate(docs,self.declared)

    def test_members_on_the_tip_image_are_refused(self):
        docs=copy.deepcopy(self.docs)
        pod=module.podspec(next(d for d in docs if d['metadata']['name']=='order-matcher-cluster' and module.podspec(d)))
        for c in pod.get('containers',[])+pod.get('initContainers',[]):
            if c['image'].startswith(module.REGISTRY): c['image']=module.REGISTRY+'cluster-node:test-candidate'
        with self.assertRaisesRegex(AssertionError,'order-matcher-cluster: image differs from the declared population'):
            module.validate(docs,self.declared)


if __name__=='__main__': unittest.main()
