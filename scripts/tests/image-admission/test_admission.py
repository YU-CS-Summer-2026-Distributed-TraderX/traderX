"""Actual GKE and proof runner entrypoint controls, synthetic inputs only."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value


H = module(ROOT / 'scripts/yu15/image-admission.py', 'admission')
P = module(ROOT / 'scripts/yu15/stp-image-provenance.py', 'provenance')
C = module(ROOT / 'scripts/tests/proof-cleanup/test_cleanup.py', 'cleanup_tests')


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, sort_keys=True))


class DeploymentControls(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='ri20-deploy-')
        self.root = Path(self.tmp.name)
        self.fixture = self.root / 'fixture'; self.fixture.mkdir()
        self.bin = self.root / 'bin'; self.bin.mkdir()
        for tool in ('kubectl', 'docker'):
            shutil.copy2(HERE / 'fake-command.py', self.bin / tool); (self.bin / tool).chmod(0o755)
        for file in ('start-cluster-gke.sh', 'image-admission.py', 'stp-image-provenance.py'):
            target = self.root / 'scripts/yu15' / file; target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / 'scripts/yu15' / file, target)
        states = [dict(id='YU17-otc-rates', previous=[]), dict(id='YU18-risk-integration', previous=['YU17-otc-rates'])]
        write(self.root / 'catalog/state-catalog.json', dict(states=states))
        gke = self.root / 'specs/YU17-otc-rates/generation/kubernetes/cluster/gke'; gke.mkdir(parents=True)
        write(gke / 'kustomization.yaml',dict(resources=[]))
        write(self.root / 'specs/YU18-risk-integration/generation/runtime-overrides/kubernetes-runtime/manifests/base/database-init-configmap.yaml',
              dict(apiVersion='v1', kind='ConfigMap', metadata=dict(name='database-init-sql', namespace='traderx'), data={'init.sql':'synthetic schema'}))
        self.target = dict(context='gke_offline-project_us-east1-b_offline-cluster', project='offline-project', location='us-east1-b', cluster='offline-cluster', namespace='traderx')
        self.plan = dict(schema='traderx-intended-deployment-v1', target=self.target.copy(), state='YU18-risk-integration', manifestPack='YU17-otc-rates', slots={})
        self.inventory = dict(schema='traderx-offline-image-inventory-v1', artifacts=[])
        self.objects = []
        self.add_component('order-matcher', 'YU18-risk-integration', 'StatefulSet', 'order-matcher-cluster', 'node')
        self.add_component('inherited-service', 'YU17-otc-rates', 'Deployment', 'inherited-service', 'service')
        self.expected = self.root / 'expected.json'; self.artifacts = self.root / 'artifacts.json'
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'], RI20_FIXTURE=str(self.fixture))
        self.save()

    def tearDown(self):
        self.tmp.cleanup()

    def add_component(self, component, owner, kind, workload, container, group='containers'):
        source = f'specs/{owner}/generation/runtime-overrides/{component}'
        generated = f'generated/code/target-generated/{component}'
        for relative in (source, generated):
            folder = self.root / relative; folder.mkdir(parents=True, exist_ok=True)
            (folder / 'source.txt').write_text(f'synthetic {component} bytes\n')
        ref = 'offline/' + component + '@sha256:' + hashlib.sha256(component.encode()).hexdigest()
        payload = [['classes/fixture.class', hashlib.sha256(component.encode()).hexdigest()], ['lib/fixture.jar', 'b' * 64]]
        identity = dict(component=component, buildState='YU18-risk-integration', ownerState=owner, role='production', provider='reviewed-artifact-v1', platform='linux/amd64',
                        sourcePath=source, generatedPath=generated, ownerSha256=P.tree_hash(self.root / source), sourceSha256=P.tree_hash(self.root / generated),
                        payloadSha256=P.digest(P.canonical(payload)))
        provenance = dict(schema='traderx-reviewed-image-v1', **{k:v for k,v in identity.items() if k != 'provider'},
                          generationInputs=[dict(path='catalog/state-catalog.json', sha256=P.digest((self.root / 'catalog/state-catalog.json').read_bytes()))])
        identity['provenanceSha256'] = P.digest(P.canonical(provenance))
        expected = dict(reference=ref, **identity)
        inspection = dict(Id='sha256:' + hashlib.sha256(('config:' + component).encode()).hexdigest(), RepoDigests=[ref], Os='linux', Architecture='amd64',
                          Config=dict(Labels={'dev.traderx.image.provenance': json.dumps(provenance)}))
        review = dict(schema='traderx-intended-image-review-v1', verdict='verified', reviewedBy='offline-fixture-reviewer',
                      artifact=dict(reference=ref, imageId=inspection['Id'], **identity), assertions=3,
                      checks=dict(immutableReference=True, packagedPayload=True, sourceMapping=True))
        review_path = self.root / (component + '-review.json'); write(review_path, review)
        actual = dict(**expected, inspection=inspection, payloadEntries=payload, review=dict(path=review_path.name, sha256=P.digest(review_path.read_bytes())))
        self.inventory['artifacts'].append(actual)
        self.plan['slots'][f'traderx/{kind}/{workload}/{group}/{container}'] = expected
        pod = {group:[dict(name=container, image=ref)]}
        if group != 'containers': pod['containers'] = [dict(name='main', image=self.inventory['artifacts'][0]['reference'])]
        if kind == 'CronJob': spec = dict(jobTemplate=dict(spec=dict(template=dict(spec=pod))))
        elif kind == 'Pod': spec = pod
        else: spec = dict(template=dict(spec=pod))
        self.objects.append(dict(apiVersion='apps/v1', kind=kind, metadata=dict(name=workload, namespace='traderx'), spec=spec))

    def save(self):
        write(self.expected, self.plan); write(self.artifacts, self.inventory)
        write(self.fixture / 'rendered.json', dict(kind='List', apiVersion='v1', items=self.objects))
        (self.fixture / 'trace.jsonl').write_text('')

    def trace(self):
        return [json.loads(x) for x in (self.fixture / 'trace.jsonl').read_text().splitlines()]

    def call(self, accepted=True, reason='', apply=False, extra=None):
        self.save()
        args = ['bash', str(self.root / 'scripts/yu15/start-cluster-gke.sh')]
        for key,value in self.target.items(): args += ['--' + key, value]
        args += ['--state', self.plan['state'], '--manifest-pack', self.plan['manifestPack'], '--expected', str(self.expected), '--artifacts', str(self.artifacts)]
        if apply: args += ['--apply']
        args += extra or []
        p = subprocess.run(args, env=self.env, capture_output=True, text=True, timeout=15)
        self.assertEqual(p.returncode, 0 if accepted else 1, p.stdout + p.stderr)
        trace = self.trace()
        self.assertTrue(all(x[:2] == ['kubectl','kustomize'] for x in trace), trace)
        if accepted:
            self.assertIn('admitted-offline', p.stdout)
            self.assertIn('compatibility', p.stdout)
        else:
            self.assertIn(reason, p.stderr)
        return p

    def test_matching_explicit_inherited_component(self):
        self.call()

    def test_missing_expected_refuses_apply_before_mutation(self):
        self.plan['slots'].pop(next(iter(self.plan['slots'])))
        self.call(False, 'coverage differs', apply=True)

    def test_mutable_rendered_refuses(self):
        self.objects[0]['spec']['template']['spec']['containers'][0]['image'] = 'offline/order-matcher:yu18'
        self.call(False, 'mutable', apply=True)

    def test_wrong_state(self):
        self.plan['state']='YU17-otc-rates'
        self.call(False, 'component owner is outside selected state lineage')

    def test_wrong_component_owner_role_and_provider(self):
        for key, value in (('component','different-service'), ('buildState','YU17-otc-rates'), ('ownerState','YU17-otc-rates'), ('role','pre'), ('provider','unknown')):
            with self.subTest(key=key):
                old = self.inventory['artifacts'][0][key]
                self.inventory['artifacts'][0][key] = value
                self.call(False, key + ' mismatch', apply=True)
                self.inventory['artifacts'][0][key] = old

    def test_expected_pre_role_is_never_production(self):
        expected = next(iter(self.plan['slots'].values())); expected['role'] = 'pre'
        self.inventory['artifacts'][0]['role'] = 'pre'
        self.call(False, 'role must be production')

    def test_unknown_matching_producer_stays_refusal(self):
        next(iter(self.plan['slots'].values()))['provider'] = 'unknown'
        self.inventory['artifacts'][0]['provider'] = 'unknown'
        self.call(False, 'unknown provenance producer')

    def test_stp_pre_cannot_be_laundered_through_external_review(self):
        self.inventory['artifacts'][0]['inspection']['Config']['Labels'][P.LABEL] = json.dumps(dict(schema=P.SCHEMA, role='pre'))
        self.call(False, 'wrong STP image role', apply=True)

    def test_missing_malformed_and_wrong_provenance(self):
        labels = self.inventory['artifacts'][0]['inspection']['Config']['Labels']
        original = labels.copy()
        for value in (None, '{', 'null', '{}'):
            with self.subTest(value=value):
                labels.clear()
                if value is not None: labels['dev.traderx.image.provenance'] = value
                self.call(False, '', apply=True)
        labels.update(original)

    def test_content_id_without_repo_digest_refuses(self):
        self.inventory['artifacts'][0]['inspection']['RepoDigests'] = []
        self.call(False, 'absent from inspected', apply=True)

    def test_missing_inventory_entry(self):
        self.inventory['artifacts'].pop()
        self.call(False, 'no inspected artifact')

    def test_ambiguous_duplicate_inventory(self):
        self.inventory['artifacts'].append(copy.deepcopy(self.inventory['artifacts'][0]))
        self.call(False, 'ambiguous')

    def test_explicit_context_mismatch(self):
        self.target['context']='gke_other-project_us-east1-b_offline-cluster'
        self.call(False, 'context does not match', apply=True)

    def test_duplicate_options_and_root_override_refuse(self):
        self.call(False,'duplicate option',extra=['--context',self.target['context']])
        self.call(False,'unsupported option',extra=['--root=/tmp'])

    def test_remote_kustomize_resource_refuses_before_renderer(self):
        path=self.root/'specs/YU17-otc-rates/generation/kubernetes/cluster/gke/kustomization.yaml'
        write(path,dict(resources=['https://example.invalid/cloud-manifests.yaml']))
        self.call(False,'not offline admission inputs')
        self.assertFalse(self.trace())

    def test_external_kustomize_generator_refuses_before_renderer(self):
        path=self.root/'specs/YU17-otc-rates/generation/kubernetes/cluster/gke/kustomization.yaml'
        write(path,dict(generators=['external-plugin.yaml']))
        self.call(False,'does not support Helm or external')
        self.assertFalse(self.trace())

    def test_plan_target_mismatch(self):
        self.plan['target']['project'] = 'different-project'
        self.call(False, 'target differs', apply=True)

    def test_absent_explicit_context_has_no_external_calls(self):
        p = subprocess.run(['bash', str(self.root / 'scripts/yu15/start-cluster-gke.sh')],env=self.env,capture_output=True,text=True)
        self.assertEqual(1,p.returncode); self.assertFalse(self.trace())

    def test_generated_and_owner_content_changes(self):
        for key in ('sourcePath','generatedPath'):
            with self.subTest(key=key):
                path = self.root / self.inventory['artifacts'][0][key] / 'source.txt'
                old = path.read_bytes(); path.write_bytes(old + b'changed')
                self.call(False, 'content changed'); path.write_bytes(old)

    def test_platform_mismatch(self):
        self.inventory['artifacts'][0]['inspection']['Architecture'] = 'arm64'
        self.call(False, 'platform differs')

    def test_inherited_owner_shadowed_by_later_component_refuses(self):
        source=self.root/'specs/YU18-risk-integration/generation/runtime-overrides/inherited-service'
        source.mkdir(parents=True);(source/'override.txt').write_text('later override')
        self.call(False,'shadowed by a later runtime layer')

    def test_deliberately_inherited_build_admits_despite_newer_deployment_override(self):
        source=self.root/'specs/YU18-risk-integration/generation/runtime-overrides/inherited-service'
        source.mkdir(parents=True);(source/'override.txt').write_text('newer deployment override, deliberately not in intended build')
        key='traderx/Deployment/inherited-service/containers/service'
        expected=self.plan['slots'][key];actual=self.inventory['artifacts'][1]
        expected['buildState']='YU17-otc-rates';actual['buildState']=expected['buildState']
        # The independently intended ancestor generation context has its own path.
        old=self.root/expected['generatedPath']
        relative='generated/intended/YU17-otc-rates/code/target-generated/inherited-service'
        shutil.copytree(old,self.root/relative)
        expected['generatedPath']=relative;actual['generatedPath']=relative
        labels=actual['inspection']['Config']['Labels'];provenance=json.loads(labels['dev.traderx.image.provenance'])
        provenance.update(buildState=expected['buildState'],generatedPath=relative)
        labels['dev.traderx.image.provenance']=json.dumps(provenance)
        value=P.digest(P.canonical(provenance));actual['provenanceSha256']=value;expected['provenanceSha256']=value
        self.reseal_review(actual);self.call()

    def test_arbitrary_source_path_cannot_claim_component_ownership(self):
        expected=next(iter(self.plan['slots'].values()));expected['sourcePath']='specs/YU18-risk-integration/generation/runtime-overrides/kubernetes-runtime'
        self.inventory['artifacts'][0]['sourcePath']=expected['sourcePath']
        self.call(False,'source component root required')

    def use_ri18_fix(self):
        # Run RI18's accepted real builder entrypoint with its offline build tools,
        # then export its synthetic inspection/payload into RI20 review inputs.
        r=module(ROOT/'scripts/tests/stp-image-provenance/test_provenance.py','ri18_tests')
        control=r.EntrypointControls();control.setUp()
        try:
            shutil.rmtree(self.root/'generated/code/target-generated/order-matcher')
            shutil.copytree(control.om,self.root/'generated/code/target-generated/order-matcher')
            for name in ('build-stp-boundary-images.sh','stp-boundary-revert-yu18.patch','stp-boundary-revert.patch'):
                shutil.copy2(control.root/'scripts/yu15'/name,self.root/'scripts/yu15'/name)
            shutil.copytree(control.root/'pipeline',self.root/'pipeline')
            env=dict(control.env)
            p=subprocess.run(['bash',str(self.root/'scripts/yu15/build-stp-boundary-images.sh')],env=env,cwd=self.root,capture_output=True,text=True,timeout=25)
            self.assertEqual(0,p.returncode,p.stdout+p.stderr)
            built=json.loads((control.store/'images.json').read_text())['ri18:fix']
            payload=sorted([[name,P.digest(bytes.fromhex(value))] for name,value in built.pop('payload').items()])
            expected=next(iter(self.plan['slots'].values()))
            expected.update(provider='ri18-fix-v1',sourceSha256=P.tree_hash(self.root/expected['generatedPath']),payloadSha256=P.digest(P.canonical(payload)))
            provenance=json.loads(built['Config']['Labels'][P.LABEL])
            expected['provenanceSha256']=P.digest(P.canonical(provenance))
            built.update(RepoDigests=[expected['reference']],Os='linux',Architecture='amd64')
            actual=dict(**expected,inspection=built,payloadEntries=payload,review=dict(path='order-matcher-review.json',sha256=''))
            self.inventory['artifacts'][0]=actual
            self.reseal_review(actual)
        finally:control.tearDown()

    def reseal_review(self,actual):
        path=self.root/actual['review']['path']
        review=dict(schema='traderx-intended-image-review-v1',verdict='verified',reviewedBy='offline-fixture-reviewer',assertions=3,
                    checks=dict(immutableReference=True,packagedPayload=True,sourceMapping=True),
                    artifact=dict(reference=actual['reference'],imageId=actual['inspection']['Id'],**{k:actual[k] for k in H.IDENTITY}))
        write(path,review);actual['review']['sha256']=P.digest(path.read_bytes())

    def test_ri18_fix_entrypoint_is_supported_with_explicit_review(self):
        self.use_ri18_fix();self.call()

    def test_ri18_selected_composition_changes_refuse(self):
        self.use_ri18_fix()
        (self.root/'pipeline/generator.sh').write_text('changed composition')
        self.call(False,'RI18 selected source/composition/recipe/patch changed')

    def test_ri18_malformed_shape_refuses_even_when_expected_label_hash_matches(self):
        self.use_ri18_fix()
        actual=self.inventory['artifacts'][0];labels=actual['inspection']['Config']['Labels']
        provenance=json.loads(labels[P.LABEL]);del provenance['bases']
        labels[P.LABEL]=json.dumps(provenance)
        digest=P.digest(P.canonical(provenance))
        actual['provenanceSha256']=digest;next(iter(self.plan['slots'].values()))['provenanceSha256']=digest
        self.reseal_review(actual);self.call(False,'malformed RI18 provenance shape')

    def test_payload_mismatch(self):
        self.inventory['artifacts'][0]['payloadEntries'][0][1] = '0' * 64
        self.call(False, 'packaged payload differs', apply=True)

    def test_missing_review_and_hash_mismatch(self):
        old = copy.deepcopy(self.inventory['artifacts'][0]['review'])
        for value in (None, dict(path=old['path'], sha256='0'*64)):
            self.inventory['artifacts'][0]['review'] = value
            self.call(False, 'review', apply=True)

    def test_empty_assertions_and_running_only_review(self):
        actual = self.inventory['artifacts'][0]
        path = self.root / actual['review']['path']; original = json.loads(path.read_text())
        for field,value in (('assertions',0), ('checks',dict(runningPod=True))):
            report = copy.deepcopy(original); report[field] = value; write(path,report)
            actual['review']['sha256'] = P.digest(path.read_bytes())
            self.call(False, 'review', apply=True)

    def test_review_bound_to_other_immutable_artifact_refuses(self):
        actual=self.inventory['artifacts'][0];path=self.root/actual['review']['path'];report=json.loads(path.read_text())
        report['artifact']['imageId']='sha256:'+'c'*64;write(path,report);actual['review']['sha256']=P.digest(path.read_bytes())
        self.call(False,'review does not bind')

    def test_evidence_changed_during_admission_refuses_receipt(self):
        self.save()
        args=SimpleNamespace(root=self.root,expected=self.expected,artifacts=self.artifacts,
                             state=self.plan['state'],manifest_pack=self.plan['manifestPack'],**self.target)
        original=H.artifact
        def changed(*values):
            original(*values)
            self.expected.write_bytes(self.expected.read_bytes()+b'\n')
        with patch.object(H,'artifact',changed):
            with self.assertRaisesRegex(H.Refusal,'evidence changed during admission'):
                H.admit(args,(self.fixture/'rendered.json').read_text())

    def test_omitted_deployment_guard_mutant_is_detected(self):
        self.inventory['artifacts'][0]['inspection']['Config']['Labels']={}
        script=self.root/'scripts/yu15/start-cluster-gke.sh';data=script.read_text()
        line=next(x for x in data.splitlines() if x.startswith('"$PYTHON"'))
        script.write_text(data.replace(line,'''printf '{"verdict":"admitted-offline"}\\n' > "$work/admission.json"'''))
        with self.assertRaises(AssertionError):self.call(False,'missing provenance')

    def test_init_cronjob_and_pod_slots_are_covered(self):
        self.add_component('cron-service','YU17-otc-rates','CronJob','backup','backup','initContainers')
        self.plan['slots']['traderx/CronJob/backup/containers/main'] = copy.deepcopy(next(iter(self.plan['slots'].values())))
        self.add_component('pod-service','YU17-otc-rates','Pod','seed','seed')
        self.call()
        del self.plan['slots']['traderx/CronJob/backup/initContainers/backup']
        self.call(False,'coverage differs')

    def test_unknown_image_bearing_kind_refuses(self):
        self.objects[0]['kind'] = 'CustomWorkload'
        self.call(False,'unsupported rendered image-bearing kind')

    def test_empty_and_duplicate_population(self):
        old = copy.deepcopy(self.objects)
        for value in ([], [old[0],old[0]]):
            self.objects = value; self.call(False,'')


class NodeRunnerControls(unittest.TestCase):
    # Reuse only the accepted synthetic fixture/lifecycle driver, not its test
    # methods (which are run separately and should not duplicate under discovery).
    tearDown = C.CleanupAcceptance.tearDown
    write = C.CleanupAcceptance.write
    state = C.CleanupAcceptance.state
    run_runner = C.CleanupAcceptance.run_runner

    def setUp(self):
        C.CleanupAcceptance.setUp(self)
        shutil.copy2(ROOT / 'scripts/yu15/image-admission.py', self.tree / 'scripts/yu15/image-admission.py')
        self.fixture = self.base / 'ri20-fixture'; self.fixture.mkdir()
        self.legacy = self.base / 'legacy'; shutil.copytree(self.bin, self.legacy)
        for tool in ('kubectl','docker'):
            shutil.copy2(HERE/'fake-command.py',self.bin/tool); (self.bin/tool).chmod(0o755)
        self.env.update(RI20_FIXTURE=str(self.fixture), RI20_LEGACY_BIN=str(self.legacy))
        self.nodes = ['offline-control-plane','offline-worker','offline-worker2']
        write(self.fixture/'nodes.json',dict(items=[dict(metadata=dict(name=n)) for n in self.nodes]))
        self.refs = [C.BASE_IMAGE,C.PRE_IMAGE,C.FIX_IMAGE]
        self.inv = dict(images=[dict(id='sha256:'+hashlib.sha256(ref.encode()).hexdigest(),repoTags=[],repoDigests=[ref]) for ref in self.refs])
        for name in self.nodes: write(self.fixture/(name+'.json'),self.inv)
        (self.fixture/'trace.jsonl').write_text('')
        (self.tree/'scripts/proofs/yu05-settlement.sh').write_text('#!/bin/bash\ntouch "$FAKE_RIG/baseline-proof-entered"\nexit 0\n')

    def trace(self):
        return [json.loads(x) for x in (self.fixture/'trace.jsonl').read_text().splitlines()]

    def baseline(self, accepted=True, reason='', check=False):
        p = self.run_runner(selection=['--check-images'] if check else ['yu05-settlement'])
        self.assertEqual(0 if accepted else 1,p.returncode,p.stdout+p.stderr)
        if not accepted:
            self.assertIn(reason,p.stderr)
            self.assertFalse(self.journal.exists(), 'refusal must precede cleanup supervision/writes')
            self.assertFalse((self.rig/'calls.jsonl').exists())
            self.assertFalse((self.rig/'baseline-proof-entered').exists())
            self.assertTrue(all(x[0]=='docker' and x[1]=='exec' or x[0]=='kubectl' and 'nodes' in x for x in self.trace()),self.trace())
        return p

    def test_node_existing_baseline_without_rebuild(self):
        self.baseline()
        self.assertTrue((self.rig/'baseline-proof-entered').exists())
        calls=[json.loads(x) for x in (self.rig/'calls.jsonl').read_text().splitlines()]
        self.assertFalse(any(x[0]=='delete' for x in calls),calls)
        self.assertFalse(any(x[:2]==['kind','load'] for x in self.trace()))
        checked={x[2] for x in self.trace() if x[:2]==['docker','exec']}
        self.assertEqual(set(self.nodes),checked)

    def test_node_missing_ref_on_one_node_refuses_before_writes(self):
        write(self.fixture/(self.nodes[-1]+'.json'),dict(images=[]))
        self.baseline(False,'does not resolve on nodes: '+self.nodes[-1])

    def test_node_tagless_content_and_running_pods_insufficient(self):
        value=copy.deepcopy(self.inv); value['images'][0]['repoDigests']=[]
        value['images'][0]['repoTags']=[]
        write(self.fixture/(self.nodes[1]+'.json'),value)
        self.baseline(False,'does not resolve')

    def test_node_content_alias_is_not_selected_repo_reference(self):
        value=copy.deepcopy(self.inv)
        value['images'][0]['repoDigests']=['different/repo@'+C.BASE_IMAGE.split('@')[1]]
        write(self.fixture/(self.nodes[0]+'.json'),value)
        self.baseline(False,'does not resolve')

    def test_node_mutable_tag_reference_resolution(self):
        self.env['CLUSTER_IMAGE']='traderx/cluster-node:yu18'
        value=dict(images=[dict(id='sha256:'+'1'*64,repoTags=['docker.io/traderx/cluster-node:yu18'],repoDigests=[])])
        for name in self.nodes:write(self.fixture/(name+'.json'),value)
        self.baseline(check=True)

    def test_node_wrong_tag_same_content_refuses(self):
        self.env['CLUSTER_IMAGE']='traderx/cluster-node:yu18'
        value=dict(images=[dict(id='sha256:'+'1'*64,repoTags=['docker.io/traderx/cluster-node:yu17'],repoDigests=[])])
        for name in self.nodes:write(self.fixture/(name+'.json'),value)
        self.baseline(False,'does not resolve')

    def test_node_empty_malformed_duplicate_inventory(self):
        original=(self.fixture/'nodes.json').read_bytes()
        for value in (dict(items=[]),[],dict(items=[dict(metadata=dict(name=self.nodes[0]))]*2)):
            write(self.fixture/'nodes.json',value);self.baseline(False,'')
        (self.fixture/'nodes.json').write_bytes(original)

    def test_node_ambiguous_image_ref(self):
        value=copy.deepcopy(self.inv)
        other=copy.deepcopy(value['images'][0]);other['id']='sha256:'+'f'*64;value['images'].append(other)
        write(self.fixture/(self.nodes[0]+'.json'),value)
        self.baseline(False,'ambiguous image reference')

    def test_node_malformed_cri_json(self):
        (self.fixture/(self.nodes[0]+'.json')).write_text('{')
        self.baseline(False,'')

    def test_node_non_kind_adapter_refuses(self):
        self.env['CTX']='gke_offline-project_us-east1-b_offline-cluster'
        self.baseline(False,'kind contexts only')

    def test_node_missing_id_shape_refuses(self):
        value=copy.deepcopy(self.inv);value['images'][0]['id']='content-present'
        write(self.fixture/(self.nodes[0]+'.json'),value)
        self.baseline(False,'malformed CRI image record')

    def test_node_guard_omission_mutant_is_detected(self):
        write(self.fixture/(self.nodes[-1]+'.json'),dict(images=[]))
        script=self.tree/'scripts/yu15/run-proofs.sh';data=script.read_text()
        line=next(x for x in data.splitlines() if x.startswith('python3 ') and 'image-admission.py' in x)
        script.write_text(data.replace(line,'# guard deliberately removed by RI20 negative control'))
        with self.assertRaises(AssertionError):self.baseline(False,'does not resolve')


if __name__ == '__main__':
    unittest.main(verbosity=2)
