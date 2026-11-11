"""Offline render controls: wrong project and public exposure must be rejected."""
import copy
import json
import importlib.util
from pathlib import Path
import tempfile
import unittest
import os
import subprocess
import re
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

    def test_every_render_names_the_snapshot_read_model(self):
        # Production 2026-11-07: this render left TRADE_PROJECTION_MODE unset, so 889e47be moved production from rows to
        # snapshot by flipping trade-processor's default. The mode is named in the render, and its absence refused.
        tp=next(d for d in self.docs if d['kind']=='Deployment' and d['metadata']['name']=='trade-processor')
        self.assertIn({'name':'TRADE_PROJECTION_MODE','value':'snapshot'},module.podspec(tp)['containers'][0]['env'])
        docs=copy.deepcopy(self.docs)
        c=module.podspec(next(d for d in docs if d['kind']=='Deployment' and d['metadata']['name']=='trade-processor'))['containers'][0]
        c['env']=[e for e in c['env'] if e['name']!='TRADE_PROJECTION_MODE']
        with self.assertRaisesRegex(AssertionError,'TRADE_PROJECTION_MODE=snapshot'): module.validate(docs)

    def test_the_default_render_is_the_snapshot_read_model_at_one_partition(self):
        # As production e1107 ran it (2026-11-07): without ORDER_VIEW_SNAPSHOT_MS the restore gate's G3 refused
        # ("no order view snapshot") at 7:13 pm ET.
        tp=next(d for d in self.docs if d['kind']=='Deployment' and d['metadata']['name']=='trade-processor')
        env={e['name']:e.get('value') for e in module.podspec(tp)['containers'][0]['env']}
        self.assertEqual({k:env.get(k) for k in ('TRADE_PROJECTION_MODE','ORDER_FEED_PERSIST','ORDER_VIEW_SNAPSHOT_MS',
                                                 'PROJECTION_FEED_PARTITIONS','PROJECTION_FEED_STREAM_LAYOUT')},
                         {'TRADE_PROJECTION_MODE':'snapshot','ORDER_FEED_PERSIST':'false','ORDER_VIEW_SNAPSHOT_MS':'5000',
                          'PROJECTION_FEED_PARTITIONS':None,'PROJECTION_FEED_STREAM_LAYOUT':None})
        # FR-PRG-29: Ready means listening on G3's read (rollout status returned before it, production 2026-11-07)
        self.assertEqual(module.podspec(tp)['containers'][0]['readinessProbe']['httpGet'],
                         {'path':'/v2/projection-feed/accounting','port':18091})
        for value in (None,'0'):
            docs=copy.deepcopy(self.docs)
            c=module.podspec(next(d for d in docs if d['kind']=='Deployment' and d['metadata']['name']=='trade-processor'))['containers'][0]
            c['env']=[e for e in c['env'] if e['name']!='ORDER_VIEW_SNAPSHOT_MS']+([{'name':'ORDER_VIEW_SNAPSHOT_MS','value':value}] if value else [])
            with self.assertRaisesRegex(AssertionError,'no order view snapshot'): module.validate(docs)

    def test_fix_sessions_are_spread_over_the_nats_servers(self):
        # FR-GFS-21: one FIX_SESSIONS stream capped the 2026-11-10 acceptance run's FIX near 85K orders/s.
        gw=next(d for d in self.docs if d['kind']=='Deployment' and d['metadata']['name']=='cluster-gateway')
        env={e['name']:e.get('value') for e in module.podspec(gw)['containers'][0]['env']}
        n=module.nats_servers(self.docs)
        self.assertEqual((env.get('FIX_SESSION_BUCKETS'),env.get('FIX_SESSION_PLACEMENT')),
                         (str(4*n),','.join(f'nats-{i}' for i in range(n))))
        self.assertGreater(n,1)

    def test_member_archive_storageclass_is_rendered(self):
        # Members claim a retained volume of this class; a render without it leaves them Pending.
        engine=next(d for d in self.docs if d['kind']=='StatefulSet')
        claimed={t['spec']['storageClassName'] for t in engine['spec']['volumeClaimTemplates']}
        self.assertEqual(claimed,{'order-matcher-archive'})
        sc=[d for d in self.docs if d['kind']=='StorageClass']
        self.assertEqual([d['metadata']['name'] for d in sc],['order-matcher-archive','nats-store','clickhouse-hot'])   # + FR-PRG-41's, FR-THT-02's
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
                         'CLIENT1:$(FIX_SESSION_PASSWORD):*,EXTQFJ:$(FIX_EXT_PASSWORD):*')
        self.assertLess(names.index('FIX_EXT_PASSWORD'),names.index('FIX_SESSION_AUTH'))
        self.assertLess(names.index('FIX_SESSION_PASSWORD'),names.index('FIX_SESSION_AUTH'))
        console=next(d for d in self.docs if d['metadata']['name']=='web-front-end-console' and module.podspec(d))
        self.assertIn({'name':'FIX_SESSION_PASSWORD','valueFrom':{'secretKeyRef':{'name':'auth-secrets','key':'fix-session-password'}}},
                      module.podspec(console)['containers'][0]['env'])
        plan=__import__('json').loads((self.output/'plan.json').read_text())
        self.assertIn('fix-session-password',plan['requiredSecretKeys']['auth-secrets'])

    def test_the_read_model_database_claim_is_50gi(self):
        """FR-PRG-37: 10Gi filled on 2026-11-04 (InnoDB error 28)."""
        claim=[d for d in self.docs if d['kind']=='PersistentVolumeClaim' and d['metadata']['name']=='eod-price-db-data']
        self.assertEqual([c['spec']['resources']['requests']['storage'] for c in claim],['50Gi'])
        # 2026-11-08: the size constant once reassigned the claim's NAME, so the snapshot render's claim was '50Gi',
        # which the API server refuses (RFC 1123). The partitioned render names it eod-price-db-fast.
        with tempfile.TemporaryDirectory() as d:
            module.prepare(Path(d)/'p','test-candidate','10.93.0.10',read_model='snapshot',partitions=8)
            docs=list(yaml.safe_load_all((Path(d)/'p/candidate.yaml').read_text()))
        db=next(x for x in docs if x['kind']=='Deployment' and x['metadata']['name']=='eod-price-db')
        names={v['persistentVolumeClaim']['claimName'] for v in module.podspec(db)['volumes'] if 'persistentVolumeClaim' in v}
        self.assertEqual(names,{'eod-price-db-fast'})
        self.assertIn('eod-price-db-fast',{x['metadata']['name'] for x in docs if x['kind']=='PersistentVolumeClaim'})

    def test_console_reads_the_kdb_vm(self):
        """FR-DA12: the Kdb tab's q service is the traderx-kdb VM, by name, exactly once."""
        console=next(d for d in self.docs if d['metadata']['name']=='web-front-end-console' and module.podspec(d))
        urls=[e['value'] for e in module.podspec(console)['containers'][0]['env'] if e['name']=='KDB_Q_URL']
        self.assertEqual(urls,['http://traderx-kdb.us-east1-b.c.project-compsci-484414.internal:5050'])

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
            if mutate=='no-accounts': env['FIX_SESSION_AUTH']['value']=env['FIX_SESSION_AUTH']['value'].replace('$(FIX_EXT_PASSWORD):*','$(FIX_EXT_PASSWORD):')
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
        self.assertIn({'name':'FIX_FILL_FEED','value':'routed'},module.podspec(gw)['containers'][0]['env'])   # FR-FFR-14: its own routed subject, still durable
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
        self.assertIn({'name':'FIX_EXACTLY_ONCE','value':'engine'},env)   # FR-PRG-46, ADR-104 (yaakov 2026-11-10)
        self.assertIn({'name':'ORDER_READMODEL_URL','value':'http://trade-processor:18091'},env)
        self.assertIn({'name':'FIX_SESSION_RESET_TIME','value':'17:00:00'},env)
        self.assertIn({'name':'FIX_SESSION_TIME_ZONE','value':'America/New_York'},env)
        for name in ('FIX_SESSION_STORE','FIX_EXACTLY_ONCE','ORDER_READMODEL_URL','FIX_SESSION_RESET_TIME'):
            docs=copy.deepcopy(self.docs)
            gw=next(d for d in docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d))
            env=module.podspec(gw)['containers'][0]['env']; env[:]=[e for e in env if e['name']!=name]
            with self.assertRaises(AssertionError, msg=name): module.validate(docs)
        # FR-XOD-24: the store-before-submit path (FR-XOD-12) is no longer production's, and the members' ring must
        # hold what a session can have outstanding at the gateways' write bound.
        for value in ('jetstream','1'):
            docs=copy.deepcopy(self.docs)
            gw=next(d for d in docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d))
            next(e for e in module.podspec(gw)['containers'][0]['env'] if e['name']=='FIX_EXACTLY_ONCE')['value']=value
            with self.assertRaises(AssertionError, msg=value): module.validate(docs)
        engine=next(d for d in self.docs if d['kind']=='StatefulSet' and d['metadata']['name']=='order-matcher-cluster')
        self.assertIn({'name':'EXACTLY_ONCE_SESSION_RING','value':'2048'},module.podspec(engine)['containers'][0]['env'])
        docs=copy.deepcopy(self.docs)
        gw=next(d for d in docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d))
        module.podspec(gw)['containers'][0]['env'].append({'name':'FIX_MAX_QUEUED_WRITES','value':'4096'})
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
        # ADR-103: c4d-standard-16, 15 CPU / 40Gi (the 464K hour's members; 16Gi lived in page-cache reclaim and flapped).
        self.assertEqual(c['resources'],{'requests':{'cpu':'15','memory':'40Gi'},'limits':{'cpu':'15','memory':'40Gi'}})
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
        self.assertEqual(env['TRADE_PROJECTION_MODE'],'snapshot')   # named, never trade-processor's default (FR-THT-01)
        docs=copy.deepcopy(self.docs)
        tp=next(d for d in docs if d['metadata']['name']=='trade-processor' and module.podspec(d))
        next(e for e in module.podspec(tp)['containers'][0]['env'] if e['name']=='PROJECTION_FEED_MODE')['value']='core'
        with self.assertRaises(AssertionError): module.validate(docs)
        docs=copy.deepcopy(self.docs)
        nats=next(d for d in docs if d['kind']=='StatefulSet' and d['metadata']['name']=='nats')
        nats['spec']['volumeClaimTemplates']=[]
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


class NatsMemory(unittest.TestCase):
    """FR-PRG-28: the production (rows) render's NATS pod, Go heap and NATS limits agree, and drift is refused at render.
    2026-10-30: a 2Gi pod against NATS's own 23.5 GB memory store was OOM-killed 7 times under the bridges' publish."""
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory()
        module.prepare(Path(cls.temp.name)/'candidate','test-candidate','10.93.0.10')
        cls.docs=list(yaml.safe_load_all((Path(cls.temp.name)/'candidate/candidate.yaml').read_text()))

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def nats(self,docs):
        return module.podspec(next(d for d in docs if d['kind']=='StatefulSet' and d['metadata']['name']=='nats'))['containers'][0]

    def conf(self,docs):
        return next(d for d in docs if d['kind']=='ConfigMap' and d['metadata']['name']=='nats-server-config')['data']

    def test_the_limit_holds_what_nats_is_configured_to_hold(self):
        c=self.nats(self.docs)
        limit=module.size_bytes(c['resources']['limits']['memory'])
        gomem=module.size_bytes(next(e['value'] for e in c['env'] if e['name']=='GOMEMLIMIT'))
        conf=self.conf(self.docs)['nats.conf']
        store=module.size_bytes(re.search(r'max_memory_store: (\S+)',conf).group(1))
        queue=module.size_bytes(re.search(r'max_buffered_size: (\S+)',conf).group(1))
        self.assertEqual(c['resources']['requests']['memory'],c['resources']['limits']['memory'])
        self.assertLessEqual(limit,module.NATS_NODE_BUDGET)
        self.assertLessEqual(gomem*100,limit*85)
        self.assertLessEqual((store+queue)*2,gomem)
        self.assertGreater(limit,2*2**30)   # the size that was OOM-killed

    def test_drift_is_refused(self):
        def broken(change):
            docs=copy.deepcopy(self.docs); change(docs); return docs
        def limit(v):
            def change(docs): r=self.nats(docs)['resources']; r['limits']['memory']=r['requests']['memory']=v
            return change
        def gomem(v):
            def change(docs):
                c=self.nats(docs); c['env']=[e for e in c['env'] if e['name']!='GOMEMLIMIT']+([{'name':'GOMEMLIMIT','value':v}] if v else [])
            return change
        def conf(old,new):
            def change(docs): d=self.conf(docs); d['nats.conf']=re.sub(old,new,d['nats.conf'])
            return change
        def request(docs): self.nats(docs)['resources']['requests']['memory']='512Mi'
        for change,message in ((limit('2Gi'),'GOMEMLIMIT'),                                  # 2026-10-30's pod
                               (limit('60Gi'),'fit nats-pool'),                                 # past a c4d-standard-16's allocatable
                               (request,'equal the request'),
                               (gomem(None),'GOMEMLIMIT'),(gomem('45GiB'),'85%'),                     # 45 of 48Gi leaves no page cache
                               (conf(r'\s*max_memory_store: \S+',''),'explicit'),          # NATS then takes 75% of the host
                               (conf(r'max_buffered_size: \S+','max_buffered_size: 12GB'),'ingest queue')):
            with self.assertRaisesRegex(AssertionError,message,msg=message): module.validate(broken(change))


class NatsCluster(unittest.TestCase):
    """FR-PRG-41 (ADR-103): the capture stream's servers are a three-server NATS cluster, one per node, each on its own
    retained Hyperdisk claim, named and tagged by pod; the partitioned render places the streams over every server by
    tag (FR-DRS-44) and sizes each server's store to the partitions it holds. One server was the wall (FR-DRS-34) and one
    volume saturated at 400K orders/s (RI34 §1b)."""
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory()
        module.prepare(Path(cls.temp.name)/'rows','test-candidate','10.93.0.10')
        cls.rows=list(yaml.safe_load_all((Path(cls.temp.name)/'rows/candidate.yaml').read_text()))
        module.prepare(Path(cls.temp.name)/'snap','test-candidate','10.93.0.10',read_model='snapshot')
        cls.snap=list(yaml.safe_load_all((Path(cls.temp.name)/'snap/candidate.yaml').read_text()))

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def sts(self,docs): return next(d for d in docs if d['kind']=='StatefulSet' and d['metadata']['name']=='nats')
    def conf(self,docs): return next(d for d in docs if d['kind']=='ConfigMap' and d['metadata']['name']=='nats-server-config')['data']
    def tp_env(self,docs):
        tp=module.read_model_workload(docs)
        return {e['name']:e.get('value') for e in module.podspec(tp)['containers'][0]['env']}

    def test_three_servers_one_per_node_each_on_its_own_hyperdisk(self):
        sts=self.sts(self.rows); pod=module.podspec(sts); conf=self.conf(self.rows)['nats.conf']
        self.assertEqual(sts['spec']['replicas'],3)
        self.assertEqual(sts['spec']['serviceName'],'nats-headless')
        self.assertEqual(sorted(re.findall(r'nats://(nats-\d)\.nats-headless\.traderx\.svc',conf)),['nats-0','nats-1','nats-2'])
        self.assertRegex(conf,r'(?m)^\s*server_name: POD_NAME$'); self.assertRegex(conf,r'(?m)^\s*server_tags: \["POD_NAME"\]$')
        self.assertIn('POD_NAME',' '.join(pod['containers'][0]['args']))
        self.assertIn({'name':'POD_NAME','valueFrom':{'fieldRef':{'fieldPath':'metadata.name'}}},pod['containers'][0]['env'])
        self.assertEqual(pod['affinity']['podAntiAffinity']['requiredDuringSchedulingIgnoredDuringExecution'][0]['topologyKey'],'kubernetes.io/hostname')
        self.assertEqual(sts['spec']['persistentVolumeClaimRetentionPolicy'],{'whenDeleted':'Retain','whenScaled':'Retain'})
        claim=next(t for t in sts['spec']['volumeClaimTemplates'] if t['metadata']['name']=='data')
        sc=next(d for d in self.rows if d['kind']=='StorageClass' and d['metadata']['name']==claim['spec']['storageClassName'])
        self.assertEqual(sc['parameters']['type'],'hyperdisk-balanced'); self.assertNotIn('namespace',sc['metadata'])
        self.assertEqual(pod['nodeSelector'],{'cloud.google.com/gke-nodepool':'nats-pool'})
        self.assertRegex(conf,r'(?m)^\s*max_pending: 256MB$')
        self.assertNotIn('PROJECTION_FEED_STREAM_PLACEMENT',self.tp_env(self.rows))   # one stream: the server places it

    def test_the_partitioned_capture_is_placed_over_every_server_and_each_store_holds_its_share(self):
        env=self.tp_env(self.snap)
        self.assertEqual(env['PROJECTION_FEED_STREAM_PLACEMENT'],'nats-0,nats-1,nats-2')
        retention=int(env['PROJECTION_FEED_STREAM_MAX_BYTES'])
        self.assertEqual(retention,module.stream_retention_bytes(450_000,30))
        store=int(re.search(r'max_file_store: (\d+)G',self.conf(self.snap)['nats.conf']).group(1))
        fills=int(env['FILLS_STREAM_MAX_BYTES'])
        self.assertEqual(store,module.store_per_server_gb(retention,8,3,fills))
        self.assertGreater(store*10**9,retention*3//8+fills)        # three of eight partitions and the fills stream on the fullest server
        self.assertLess(store*10**9,retention)                      # not sized for the whole feed on one server
        claim=next(t for t in self.sts(self.snap)['spec']['volumeClaimTemplates'] if t['metadata']['name']=='data')
        self.assertGreater(int(claim['spec']['resources']['requests']['storage'][:-2])*2**30,store*10**9)

    def test_drift_is_refused(self):
        def broken(docs,change):
            docs=copy.deepcopy(docs); change(docs); return docs
        def replicas(docs): self.sts(docs)['spec']['replicas']=1
        def route(docs): d=self.conf(docs); d['nats.conf']=d['nats.conf'].replace('nats://nats-2.nats-headless','nats://nats-9.nats-headless')
        def name(docs): d=self.conf(docs); d['nats.conf']=d['nats.conf'].replace('server_name: POD_NAME','server_name: nats')
        def spread(docs): module.podspec(self.sts(docs)).pop('affinity')
        def claim(docs): self.sts(docs)['spec']['persistentVolumeClaimRetentionPolicy']['whenScaled']='Delete'
        def disk(docs):
            next(t for t in self.sts(docs)['spec']['volumeClaimTemplates'])['spec']['storageClassName']='standard-rwo'
        def placement(docs):
            tp=module.read_model_workload(docs)
            next(e for e in module.podspec(tp)['containers'][0]['env'] if e['name']=='PROJECTION_FEED_STREAM_PLACEMENT')['value']='nats-0,nats-1'
        for docs,change,message in ((self.rows,replicas,'fewer than three'),(self.rows,route,'routes must name every server'),
                                    (self.rows,name,'named and tagged'),(self.rows,spread,'one per node'),
                                    (self.rows,claim,'retained per-server claim'),(self.rows,disk,'Hyperdisk'),
                                    (self.snap,placement,'placed over every server')):
            with self.assertRaisesRegex(AssertionError,message,msg=message): module.validate(broken(docs,change))


class RoutedFills(unittest.TestCase):
    """FR-FFR-14 / FR-DRS-48: the gateway names itself (GATEWAY_ID from its pod) and reads only /fills.<id>; validation
    refuses a routed feed without the name or without a jetstream read model to route from."""
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory()
        module.prepare(Path(cls.temp.name)/'candidate','test-candidate','10.93.0.10')
        cls.docs=list(yaml.safe_load_all((Path(cls.temp.name)/'candidate/candidate.yaml').read_text()))

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def gw(self,docs): return module.podspec(next(d for d in docs if d['kind']=='Deployment' and d['metadata']['name']=='cluster-gateway'))['containers'][0]

    def test_the_gateway_names_itself_and_reads_its_own_fills(self):
        env={e['name']:e for e in self.gw(self.docs)['env']}
        self.assertEqual(env['FIX_FILL_FEED']['value'],'routed')
        self.assertEqual(env['GATEWAY_ID']['valueFrom'],{'fieldRef':{'fieldPath':'metadata.name'}})

    def test_a_routed_feed_without_a_name_or_a_router_is_refused(self):
        docs=copy.deepcopy(self.docs); c=self.gw(docs); c['env']=[e for e in c['env'] if e['name']!='GATEWAY_ID']
        with self.assertRaisesRegex(AssertionError,'GATEWAY_ID'): module.validate(docs)
        docs=copy.deepcopy(self.docs); tp=module.read_model_workload(docs)
        module.set_env(module.podspec(tp)['containers'][0],{'FILLS_ROUTE':'0'})
        with self.assertRaisesRegex(AssertionError,'fills router'): module.validate(docs)


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

    def test_a_throughput_run_scales_the_clients_over_the_same_accounts(self):
        clients=module.load_clients(200)
        self.assertEqual(len(clients),200)
        self.assertEqual({k:clients[k] for k in module.FIX_TEST_CLIENTS},module.FIX_TEST_CLIENTS)   # CLIENT2..8 unchanged
        self.assertEqual(set(clients.values()),set(module.FIX_TEST_ACCOUNTS))
        self.assertEqual(clients['CLIENT9'],'9900002')

    def test_the_provisioner_grows_without_rotating(self):
        with tempfile.TemporaryDirectory() as d:
            os.chmod(d,0o700)
            prov=[__import__('sys').executable,str(Path(__file__).with_name('provision-fix-test-clients.py')),'--private-dir',d]
            subprocess.run(prov,capture_output=True,check=True)
            first=json.loads((Path(d)/'fix-test-clients.json').read_text())
            r=subprocess.run(prov+['--count','20'],capture_output=True,text=True,check=True)
            grown=json.loads((Path(d)/'fix-test-clients.json').read_text())
            self.assertEqual(len(grown),20)
            self.assertEqual({c:grown[c] for c in first},first)   # existing passwords kept
            self.assertEqual((Path(d)/'fix-test-clients.json').stat().st_mode&0o777,0o600)
            for v in grown.values(): self.assertNotIn(v['password'],r.stdout+r.stderr)
            r=subprocess.run(prov+['--count','5'],capture_output=True,text=True)   # shrinking would drop CompIDs
            self.assertNotEqual(r.returncode,0)

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
        self.assertEqual(len(private),14)   # history-feed since FR-THT-02

    def test_older_members_get_a_gateway_that_refuses_sequenced_binary(self):
        # ADR-096 §6: a sequenced binary NEW (ADR-090) must never reach members built before it.
        pod=module.podspec(next(d for d in self.docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d)))
        self.assertIn({'name':'BINARY_SEQUENCED','value':'refuse'},pod['containers'][0]['env'])
        docs=copy.deepcopy(self.docs)
        pod=module.podspec(next(d for d in docs if d['metadata']['name']=='cluster-gateway' and module.podspec(d)))
        pod['containers'][0]['env']=[e for e in pod['containers'][0]['env'] if e['name']!='BINARY_SEQUENCED']
        with self.assertRaisesRegex(AssertionError,'BINARY_SEQUENCED=refuse'):
            module.validate(docs,self.declared)

    def test_a_throughput_run_can_render_the_members_without_bridges(self):
        def bridge(docs):
            sts=next(x for x in docs if x['kind']=='StatefulSet')
            return [e for e in module.podspec(sts)['containers'][0]['env'] if e['name']=='TRADE_BRIDGE_NATS_URL']
        self.assertTrue(bridge(self.docs))   # the default render keeps the read model fed
        with tempfile.TemporaryDirectory() as d:
            module.prepare(Path(d)/'c','test-candidate','10.93.0.10',no_bridges=True)
            docs=list(yaml.safe_load_all((Path(d)/'c/candidate.yaml').read_text()))
        self.assertEqual(bridge(docs),[])

    def test_throughput_members_get_the_bench_settings(self):
        def env(docs):
            sts=next(x for x in docs if x['kind']=='StatefulSet')
            return {e['name']:e.get('value') for e in module.podspec(sts)['containers'][0]['env']}
        self.assertNotIn('CLUSTER_EGRESS_COALESCE',env(self.docs))   # off unless asked
        with tempfile.TemporaryDirectory() as d:
            module.prepare(Path(d)/'c','test-candidate','10.93.0.10',throughput=True)
            docs=list(yaml.safe_load_all((Path(d)/'c/candidate.yaml').read_text()))
        e=env(docs)
        self.assertEqual({k:e[k] for k in module.THROUGHPUT_MEMBER_ENV},module.THROUGHPUT_MEMBER_ENV)
        self.assertIn('-Xmx12g',e['JAVA_TOOL_OPTIONS'])   # the heap and the pinned book cap stay
        self.assertEqual(e['MAX_ACTIVE_BOOKS'],'1024')

    def test_the_binary_entry_is_in_cluster_only(self):
        with tempfile.TemporaryDirectory() as d:
            declared=Path(d)/'population.json'
            declared.write_text(json.dumps({'schema':'traderx-compsci-population-v1','imageSourceCommit':'1'*40,'workloads':{}}))
            module.prepare(Path(d)/'c','test-candidate','10.93.0.10',population_path=declared,binary_port=18120)
            docs=list(yaml.safe_load_all((Path(d)/'c/candidate.yaml').read_text()))
            decl=module.population.load(declared)
        c=module.podspec(next(x for x in docs if x['metadata']['name']=='cluster-gateway' and module.podspec(x)))['containers'][0]
        self.assertIn({'name':'BINARY_ACCEPTOR_PORT','value':'18120'},c['env'])
        self.assertIn({'name':'binary','containerPort':18120},c['ports'])
        self.assertNotIn('BINARY_SEQUENCED',[e['name'] for e in c['env']])   # same-commit members read sequencing
        svc=next(x for x in docs if x['kind']=='Service' and x['metadata']['name']=='order-matcher-gw-binary')
        self.assertEqual(svc['spec']['type'],'ClusterIP')
        svc['spec']['type']='LoadBalancer'
        with self.assertRaisesRegex(AssertionError,'binary port exposed outside the cluster'):
            module.validate(docs,decl)

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


class SnapshotReadModel(unittest.TestCase):
    """ADR-097 (FR-PRG-20): the production profile for the read model with persistence ON at high rates."""
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory()
        module.prepare(Path(cls.temp.name)/'candidate','test-candidate','10.93.0.10',read_model='snapshot',partitions=6)
        cls.docs=list(yaml.safe_load_all((Path(cls.temp.name)/'candidate/candidate.yaml').read_text()))
        module.prepare(Path(cls.temp.name)/'one','test-candidate','10.93.0.10',read_model='snapshot',partitions=6,read_model_replicas=1)
        cls.one=list(yaml.safe_load_all((Path(cls.temp.name)/'one/candidate.yaml').read_text()))

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def doc(self,docs,kind,name):
        return next(d for d in docs if d['kind']==kind and d['metadata']['name']==name)

    def env(self,docs,kind,name):
        return {e['name']:e.get('value') for e in module.podspec(self.doc(docs,kind,name))['containers'][0]['env']}

    def test_every_part_is_on(self):
        tp=self.env(self.docs,'StatefulSet','trade-processor')
        self.assertEqual(self.env(self.docs,'StatefulSet','order-matcher-cluster')['BRIDGE_PARTITIONS'],'6')
        self.assertEqual({k:tp[k] for k in ('PROJECTION_FEED_MODE','PROJECTION_FEED_PARTITIONS','PROJECTION_FEED_STREAM_LAYOUT',
                                            'TRADE_PROJECTION_MODE','ORDER_FEED_PERSIST','ORDER_VIEW_SNAPSHOT_MS')},
                         {'PROJECTION_FEED_MODE':'jetstream','PROJECTION_FEED_PARTITIONS':'6','PROJECTION_FEED_STREAM_LAYOUT':'partition',
                          'TRADE_PROJECTION_MODE':'snapshot','ORDER_FEED_PERSIST':'false','ORDER_VIEW_SNAPSHOT_MS':'5000'})
        self.assertEqual(module.podspec(self.doc(self.docs,'StatefulSet','trade-processor'))['containers'][0]['readinessProbe']['httpGet']['path'],
                         '/v2/projection-feed/accounting')
        self.assertEqual(int(tp['PROJECTION_FEED_STREAM_MAX_BYTES']),module.stream_retention_bytes())
        self.assertIn('-XX:+ZGenerational',tp['JAVA_TOOL_OPTIONS'].split())
        nats=module.podspec(self.doc(self.docs,'StatefulSet','nats'))['containers'][0]
        self.assertEqual(nats['resources']['limits']['memory'],'48Gi')   # the base sizing, every profile (FR-PRG-28)
        self.assertIn({'name':'GOMEMLIMIT','value':'20GiB'},nats['env'])
        for kind,name,pool in (('StatefulSet','trade-processor','readmodel-pool'),('Deployment','eod-price-db','db-pool')):
            self.assertEqual(module.podspec(self.doc(self.docs,kind,name))['nodeSelector'],{'cloud.google.com/gke-nodepool':pool})

    def test_the_database_moves_by_restoring_its_old_disk(self):
        db=module.podspec(self.doc(self.docs,'Deployment','eod-price-db'))
        claim=next(v['persistentVolumeClaim']['claimName'] for v in db['volumes'] if 'persistentVolumeClaim' in v)
        pvc=self.doc(self.docs,'PersistentVolumeClaim',claim)
        self.assertEqual(pvc['spec']['storageClassName'],'eod-price-db-fast')
        snap=self.doc(self.docs,'VolumeSnapshot',pvc['spec']['dataSource']['name'])
        self.assertEqual(snap['spec']['source'],{'persistentVolumeClaimName':'eod-price-db-data'})
        self.assertEqual(self.doc(self.docs,'StorageClass','eod-price-db-fast')['parameters']['type'],'hyperdisk-balanced')
        docs=copy.deepcopy(self.docs)
        self.doc(docs,'PersistentVolumeClaim',claim)['spec'].pop('dataSource')
        with self.assertRaisesRegex(AssertionError,'would start empty'): module.validate(docs)

    def test_parts_that_do_not_fit_together_are_refused(self):
        def broken(change):
            docs=copy.deepcopy(self.docs); change(docs); return docs
        def tp_env(name,value):
            def change(docs):
                c=module.podspec(self.doc(docs,'StatefulSet','trade-processor'))['containers'][0]
                next(e for e in c['env'] if e['name']==name)['value']=value
            return change
        def nats_store(docs):
            cm=self.doc(docs,'ConfigMap','nats-server-config')
            cm['data']['nats.conf']=cm['data']['nats.conf'].replace('max_file_store:','max_file_store: 1G #')
        for change,message in ((tp_env('PROJECTION_FEED_PARTITIONS','4'),'never be read'),
                               (tp_env('ORDER_VIEW_SNAPSHOT_MS','0'),'replay window'),
                               (tp_env('PROJECTION_FEED_MODE','core'),'FR-'),
                               (tp_env('PROJECTION_FEED_STREAM_LAYOUT','one'),'one capture stream'),
                               (nats_store,'max_file_store')):
            with self.assertRaisesRegex(AssertionError,message): module.validate(broken(change))

    def test_one_read_model_replica_is_a_recreate_deployment(self):
        # FR-DRS-45: a second pod is refused its partitions, so a RollingUpdate surge pod is never Ready.
        self.assertEqual(self.doc(self.one,'Deployment','trade-processor')['spec']['strategy'],{'type':'Recreate'})
        module.validate(self.one)
        docs=copy.deepcopy(self.one); self.doc(docs,'Deployment','trade-processor')['spec']['strategy']={'type':'RollingUpdate'}
        with self.assertRaisesRegex(AssertionError,'one Recreate replica'): module.validate(docs)

    def test_the_read_model_is_four_replicas_each_owning_a_block_of_the_partitions(self):
        # FR-DRS-45/46 (RI36, ADR-103 §3): the snapshot profile's default, the order view's reads routed between replicas.
        docs=self.docs
        self.assertEqual([d for d in docs if d['kind']=='Deployment' and d['metadata']['name']=='trade-processor'],[])
        sts=self.doc(docs,'StatefulSet','trade-processor')
        self.assertEqual((sts['spec']['replicas'],sts['spec']['podManagementPolicy'],sts['spec']['serviceName']),
                         (4,'Parallel','trade-processor-replicas'))
        self.assertNotIn('strategy',sts['spec'])
        env={e['name']:e for e in module.podspec(sts)['containers'][0]['env']}
        self.assertEqual(env['PROJECTION_FEED_PARTITIONS_OWNED']['value'],'replica:$(TP_REPLICA_INDEX)/4')
        self.assertEqual(env['TP_REPLICA_INDEX']['valueFrom']['fieldRef']['fieldPath'],"metadata.labels['apps.kubernetes.io/pod-index']")
        self.assertEqual(env['PROJECTION_REPLICA_ID']['valueFrom']['fieldRef']['fieldPath'],'metadata.name')
        self.assertEqual(env['PROJECTION_REPLICA_URL']['value'],'http://{replica}.trade-processor-replicas:18091')
        names=[e['name'] for e in module.podspec(sts)['containers'][0]['env']]   # $(TP_REPLICA_INDEX) expands only from an earlier entry
        self.assertLess(names.index('TP_REPLICA_INDEX'),names.index('PROJECTION_FEED_PARTITIONS_OWNED'))
        svc=self.doc(docs,'Service','trade-processor-replicas')
        self.assertEqual((svc['spec']['clusterIP'],svc['spec']['selector'],svc['metadata']['namespace']),
                         ('None',{'app':'trade-processor'},sts['metadata']['namespace']))
        self.assertEqual(self.doc(docs,'Service','trade-processor')['spec']['selector'],{'app':'trade-processor'})   # the clients' address
        for name,value,why in (('PROJECTION_FEED_PARTITIONS_OWNED','0-5','replica:<pod index>/4'),
                               ('PROJECTION_REPLICA_URL','http://trade-processor:18091','reach each other')):
            broken=copy.deepcopy(docs)
            next(e for e in module.podspec(self.doc(broken,'StatefulSet','trade-processor'))['containers'][0]['env'] if e['name']==name)['value']=value
            with self.assertRaisesRegex(AssertionError,why): module.validate(broken)
        with self.assertRaisesRegex(AssertionError,'would own none'): module.shard_read_model(copy.deepcopy(self.one),7)

class ArchivePurge(unittest.TestCase):
    """FR-PRG-40: --archive-purge puts ArchivePurgeMain beside each member, on the member image and its /data and /dev/shm,
    held by the restore gate's file, history discarded by name; the default render has none."""
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory()
        for name,purge in (('plain',False),('purge',True)):
            module.prepare(Path(cls.temp.name)/name,'test-candidate','10.93.0.10',purge=purge)
        cls.plain,cls.docs=(list(yaml.safe_load_all((Path(cls.temp.name)/n/'candidate.yaml').read_text())) for n in ('plain','purge'))

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def sts(self,docs): return next(d for d in docs if d['kind']=='StatefulSet' and d['metadata']['name']=='order-matcher-cluster')

    def test_the_agent_runs_beside_each_member(self):
        self.assertEqual([c['name'] for c in module.podspec(self.sts(self.plain))['containers']],['cluster-node'])
        sts=self.sts(self.docs); member,agent=module.podspec(sts)['containers']
        self.assertEqual(agent['name'],'archive-purge'); self.assertEqual(agent['image'],member['image'])
        self.assertEqual(agent['command'][-3:],['finos.traderx.ordermatcher.cluster.ArchivePurgeMain','/data/cluster','/dev/shm/aeron-cluster'])
        env={e['name']:e.get('value') for e in agent['env']}
        self.assertEqual((env['ARCHIVE_PURGE_DISCARD_HISTORY'],env['ARCHIVE_PURGE_HOLD_FILE']),('true','/data/archive-purge.hold'))
        self.assertEqual(env['CLUSTER_HOSTNAMES'],{e['name']:e.get('value') for e in member['env']}['CLUSTER_HOSTNAMES'])
        self.assertIn('--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED',env['JAVA_TOOL_OPTIONS'])
        self.assertEqual({m['name']:m['mountPath'] for m in agent['volumeMounts']},{'data':'/data','shm':'/dev/shm'})
        self.assertEqual(agent['resources']['requests'],agent['resources']['limits'])
        self.assertEqual(sts['spec']['template']['metadata']['annotations']['kubectl.kubernetes.io/default-container'],'cluster-node')

    def test_an_agent_the_gate_cannot_hold_or_that_loses_history_unnamed_is_refused(self):
        def agent(docs): return module.podspec(self.sts(docs))['containers'][1]
        def unheld(docs): agent(docs)['env']=[e for e in agent(docs)['env'] if e['name']!='ARCHIVE_PURGE_HOLD_FILE']
        def unnamed(docs): agent(docs)['env']=[e for e in agent(docs)['env'] if e['name']!='ARCHIVE_PURGE_DISCARD_HISTORY']
        def both(docs): agent(docs)['env'].append({'name':'ARCHIVE_PURGE_OFFLOAD_URI','value':'gs://b/p'})
        # another tag of the member's own repository, so only FR-PRG-40 (not the population, ADR-096 §6) can refuse it
        def other_image(docs): agent(docs)['image']=agent(docs)['image'].rsplit(':',1)[0]+':another-build'
        def burstable(docs): agent(docs)['resources']['limits']['cpu']='1'
        module.validate(copy.deepcopy(self.docs))   # control
        for change in (unheld,unnamed,both,other_image,burstable):
            docs=copy.deepcopy(self.docs); change(docs)
            with self.assertRaisesRegex(AssertionError,'FR-PRG-40',msg=change.__name__):
                module.validate(docs)


class HistoryStore(unittest.TestCase):
    """FR-THT-02 (ADR-102): ClickHouse and history-feed in the production render, on their own pool, sized and retained."""
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory()
        module.prepare(Path(cls.temp.name)/'candidate','test-candidate','10.93.0.10',read_model='snapshot',partitions=6)
        cls.docs=list(yaml.safe_load_all((Path(cls.temp.name)/'candidate/candidate.yaml').read_text()))
        module.prepare(Path(cls.temp.name)/'tiered','test-candidate','10.93.0.10',read_model='snapshot',partitions=6,
                       history_hot_days=2)
        cls.tiered=list(yaml.safe_load_all((Path(cls.temp.name)/'tiered/candidate.yaml').read_text()))
        cls.plan=json.loads((Path(cls.temp.name)/'tiered/plan.json').read_text())

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def doc(self,docs,kind,name):
        return next(d for d in docs if d['kind']==kind and d['metadata']['name']==name)

    def broken(self,change,docs=None):
        docs=copy.deepcopy(docs or self.docs); change(docs); return docs

    # FR-THT-10: the partitioned render is the full-rate one (~1M feed messages/s); one partition keeps the e1108b sizes.
    def test_the_store_and_its_writer_are_sized_by_the_partitions(self):
        def size(docs):
            cpu=lambda k,n: module.podspec(self.doc(docs,k,n))['containers'][0]['resources']['limits']['cpu']
            claim=self.doc(docs,'StatefulSet','clickhouse')['spec']['volumeClaimTemplates'][0]['spec']['resources']['requests']['storage']
            return cpu('StatefulSet','clickhouse'),cpu('Deployment','history-feed'),claim
        self.assertEqual(size(self.docs),('6','8','500Gi'))
        with tempfile.TemporaryDirectory() as d:
            module.prepare(Path(d)/'one','test-candidate','10.93.0.10',read_model='snapshot',partitions=1)
            self.assertEqual(size(list(yaml.safe_load_all((Path(d)/'one/candidate.yaml').read_text()))),('4','2','200Gi'))

    def test_the_store_and_its_writer_are_rendered_on_their_own_pool(self):
        store,writer=self.doc(self.docs,'StatefulSet','clickhouse'),self.doc(self.docs,'Deployment','history-feed')
        for d in (store,writer):
            pod=module.podspec(d)
            self.assertEqual(pod['nodeSelector'],{'cloud.google.com/gke-nodepool':'history-pool'})
            self.assertIn({'key':'traderx-history','operator':'Equal','value':'true','effect':'NoSchedule'},pod['tolerations'])
            res=pod['containers'][0]['resources']; self.assertEqual(res['requests'],res['limits'])
        claim=store['spec']['volumeClaimTemplates'][0]['spec']
        self.assertEqual((claim['storageClassName'],claim['resources']['requests']['storage']),('clickhouse-hot','500Gi'))
        sc=self.doc(self.docs,'StorageClass','clickhouse-hot')
        self.assertEqual((sc['parameters']['type'],sc['reclaimPolicy']),('hyperdisk-balanced','Retain'))
        env={e['name']:e.get('value') for e in module.podspec(writer)['containers'][0]['env']}
        self.assertEqual((env['PROJECTION_FEED_PARTITIONS'],env['PROJECTION_FEED_STREAM_LAYOUT'],env['HISTORY_HOT_DAYS']),('6','partition','0'))
        self.assertEqual(module.podspec(writer)['containers'][0]['image'],module.REGISTRY+'history-feed:test-candidate')
        ingress=self.doc(self.docs,'NetworkPolicy','clickhouse-clients')['spec']['ingress'][0]['from']
        self.assertEqual({f['podSelector']['matchLabels']['app'] for f in ingress},{'history-feed','trade-processor','position-service'})

    def test_an_unsized_or_unclaimed_or_absent_store_is_refused(self):
        def pod(docs,kind,name): return module.podspec(self.doc(docs,kind,name))['containers'][0]
        def unsized(docs): pod(docs,'StatefulSet','clickhouse').pop('resources')
        def burstable(docs): pod(docs,'Deployment','history-feed')['resources']['limits']['memory']='16Gi'
        def unclaimed(docs): self.doc(docs,'StatefulSet','clickhouse')['spec']['volumeClaimTemplates'][0]['spec'].pop('storageClassName')
        def absent(docs): docs.remove(self.doc(docs,'StatefulSet','clickhouse'))
        def surging(docs): self.doc(docs,'Deployment','history-feed')['spec']['strategy']={'type':'RollingUpdate'}
        def elsewhere(docs): module.set_env(pod(docs,'Deployment','history-feed'),{'PROJECTION_FEED_PARTITIONS':'4'})
        # ADR-102 cutover: the blotter reads the store, with trade-processor's settlement rule
        def blotter_shut_out(docs): self.doc(docs,'NetworkPolicy','clickhouse-clients')['spec']['ingress'][0]['from'].pop()
        def other_settlement(docs): module.set_env(pod(docs,'Deployment','position-service'),{'SETTLEMENT_T_PLUS_DAYS':'3'})
        module.validate(self.docs)
        for change,message in ((unsized,'clickhouse is not sized'),(burstable,'history-feed is not sized'),
                               (unclaimed,'no sized claim'),(absent,'not rendered'),(surging,'one Recreate replica'),
                               (elsewhere,"trade-processor's streams"),(blotter_shut_out,"does not admit \\['position-service'\\]"),
                               (other_settlement,"SETTLEMENT_T_PLUS_DAYS differs")):
            with self.assertRaisesRegex(AssertionError,message): module.validate(self.broken(change))

    def test_the_store_runs_only_the_locked_amd64_digest(self):
        published={'images':{}}
        for d in self.docs:
            for c in (module.podspec(d) or {}).get('containers',[])+(module.podspec(d) or {}).get('initContainers',[]):
                if c['image'].startswith(module.REGISTRY):
                    name=c['image'][len(module.REGISTRY):].split(':')[0]; ref=module.REGISTRY+name+'@sha256:'+'b'*64
                    published['images'][name]={'reference':ref,'registryMetadata':{'image_summary':{'fully_qualified_digest':ref}}}
        lock=json.loads(Path(__file__).with_name('public-image-lock.json').read_text())
        docs=copy.deepcopy(self.docs); module.pin_images(docs,published,lock)
        self.assertEqual(module.podspec(self.doc(docs,'StatefulSet','clickhouse'))['containers'][0]['image'],
                         'clickhouse/clickhouse-server@sha256:4ff0d6c199a9ebb906af75ae406496e8a96edd0187afdc16d51c0fb77ffca588')
        lock.pop('clickhouse/clickhouse-server:25.8')
        with self.assertRaisesRegex(AssertionError,'clickhouse/clickhouse-server:25.8 has no locked digest'):
            module.pin_images(copy.deepcopy(self.docs),published,lock)

    def test_hot_days_put_the_cold_volume_on_gcs(self):
        c=module.podspec(self.doc(self.tiered,'StatefulSet','clickhouse'))['containers'][0]
        env={e['name']:e for e in c['env']}
        self.assertEqual(env['HISTORY_COLD_ENDPOINT']['value'],
                         f'https://storage.googleapis.com/{module.PROJECT}-traderx-history/{module.NAMESPACE}/')
        self.assertEqual(env['HISTORY_COLD_SECRET']['valueFrom']['secretKeyRef'],{'name':'history-cold-hmac','key':'secret'})
        self.assertIn({'name':'tiered','mountPath':module.HISTORY_COLD_TIERED,'subPath':'tiered.xml'},c['volumeMounts'])
        self.assertIn('<tiered>',self.doc(self.tiered,'ConfigMap','clickhouse-tiered')['data']['tiered.xml'])
        self.assertEqual(self.plan['requiredSecretKeys']['history-cold-hmac'],['access-id','secret'])
        def untiered(docs):
            m=module.podspec(self.doc(docs,'StatefulSet','clickhouse'))['containers'][0]['volumeMounts']
            m[:]=[x for x in m if x['name']!='tiered']
        with self.assertRaisesRegex(AssertionError,'without the tiered policy'): module.validate(self.broken(untiered,self.tiered))


class AcceptanceShape(unittest.TestCase):
    """The 2026-11-10 acceptance run's render gaps (ADR-103 amendment): the member archive claim holds hours at rate, not
    the pack's 32Gi (FR-PRG-42); TRADERX_FILLS holds a gateway's drain-and-restart window at rate, not the code's 2 GiB
    (FR-DRS-48); every load test account has a counterparty, or the EOD extract fails closed after a load (FR-PRG-43)."""
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory()
        module.prepare(Path(cls.temp.name)/'rows','test-candidate','10.93.0.10')
        cls.rows=list(yaml.safe_load_all((Path(cls.temp.name)/'rows/candidate.yaml').read_text()))
        module.prepare(Path(cls.temp.name)/'snap','test-candidate','10.93.0.10',read_model='snapshot')
        cls.snap=list(yaml.safe_load_all((Path(cls.temp.name)/'snap/candidate.yaml').read_text()))

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def claim(self,docs):
        sts=next(d for d in docs if d['kind']=='StatefulSet' and d['metadata']['name']=='order-matcher-cluster')
        return next(t for t in sts['spec']['volumeClaimTemplates'] if t['metadata']['name']=='data')['spec']['resources']['requests']

    def test_the_member_archive_holds_three_hours_at_rate(self):
        self.assertEqual(module.member_archive_gi(),600)                      # 450K × 125 B × 3 h = 566 GiB, up to 50Gi
        self.assertGreaterEqual(600*2**30,450_000*125*3*3600)
        for docs in (self.rows,self.snap): self.assertEqual(self.claim(docs)['storage'],'600Gi')
        docs=copy.deepcopy(self.rows); self.claim(docs)['storage']='32Gi'
        with self.assertRaisesRegex(AssertionError,'FR-PRG-42'): module.validate(docs)

    def test_the_fills_stream_holds_a_gateway_restart_at_rate(self):
        tp=module.read_model_workload(self.snap)
        env={e['name']:e.get('value') for e in module.podspec(tp)['containers'][0]['env']}
        self.assertEqual(int(env['FILLS_STREAM_MAX_BYTES']),module.fills_retention_bytes(450_000,3))
        self.assertEqual(env['FILLS_STREAM_MAX_AGE_MINUTES'],'3')
        self.assertGreater(int(env['FILLS_STREAM_MAX_BYTES']),450_000*882*(30+60+60))   # drain delay, grace, start
        tp=module.read_model_workload(self.rows)
        self.assertNotIn('FILLS_STREAM_MAX_BYTES',{e['name'] for e in module.podspec(tp)['containers'][0]['env']})   # the rows render keeps the code's default

    def test_the_gateway_count_is_fixed_not_autoscaled(self):
        # FR-PRG-09, ADR-103 amendment option A (yaakov 2026-11-10): no HPA in the candidate, and one is refused.
        self.assertEqual([d for d in self.rows if d['kind']=='HorizontalPodAutoscaler'],[])
        docs=copy.deepcopy(self.rows)
        docs.append(yaml.safe_load((Path(module.__file__).parent/'rig/gateway-hpa.yaml').read_text().replace('MIN_GATEWAYS','2').replace('CPU_TARGET','60')))
        with self.assertRaisesRegex(AssertionError,'FR-PRG-09'): module.validate(docs)

    def test_every_load_account_has_a_counterparty(self):
        self.assertEqual(module.unmapped_test_accounts(),[])
        self.assertEqual(len(module.FIX_TEST_ACCOUNTS+module.BINARY_TEST_ACCOUNTS),15)
        rows=[l for l in module.COUNTERPARTIES.read_text().splitlines() if not l.startswith('9900008')]
        csv=Path(self.temp.name)/'short.csv'; csv.write_text('\n'.join(rows)+'\n')
        self.assertEqual(module.unmapped_test_accounts(csv),['9900008'])

if __name__=='__main__': unittest.main()
