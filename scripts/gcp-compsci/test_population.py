"""ADR-096 §6 refusals: an image from neither declared commit, and any undeclared mix, each refused where it can arise
(the declaration itself, admit.py's plan, measure-admission.py's pulled labels and rendered candidate)."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

HERE = Path(__file__).resolve().parent
TIP, MEMBER, OTHER = '1' * 40, '2' * 40, '3' * 40
DECLARED = {'schema': 'traderx-compsci-population-v1', 'imageSourceCommit': TIP,
            'workloads': {'order-matcher-cluster': {'image': 'cluster-node-member', 'component': 'order-matcher',
                                                     'sourceCommit': MEMBER}}}


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


population = load('test_population_module', 'population.py')


def declared_modules():
    """admit.py and measure-admission.py, loaded with the 5a-shaped declaration above (TRADERX_POPULATION)."""
    temp = tempfile.TemporaryDirectory()
    path = Path(temp.name) / 'population.json'; path.write_text(json.dumps(DECLARED))
    with mock.patch.dict(os.environ, TRADERX_POPULATION=str(path)):
        admit = load('test_population_admit', 'admit.py')
        measure = load('test_population_measure', 'measure-admission.py')
    temp.cleanup()
    return admit, measure


class Declaration(unittest.TestCase):
    def refuses(self, mutate, message):
        value = json.loads(json.dumps(DECLARED)); mutate(value)
        with self.assertRaisesRegex(population.Refusal, message):
            population.parse(value)

    def test_the_declaration_parses_and_the_shipped_file_too(self):
        self.assertEqual(population.commits(population.parse(DECLARED)), {TIP, MEMBER})
        self.assertTrue(population.SHA.fullmatch(population.load()['imageSourceCommit']))

    def test_each_malformed_declaration_is_refused(self):
        entry = lambda v: v['workloads']['order-matcher-cluster']
        self.refuses(lambda v: v.update(schema='v0'), 'unsupported')
        self.refuses(lambda v: v.update(extra=1), 'unknown population field')
        self.refuses(lambda v: v.update(imageSourceCommit='00b31d33'), 'not a full SHA')
        self.refuses(lambda v: v['workloads'].update({'Bad_Name': entry(v)}), 'malformed workload name')
        self.refuses(lambda v: entry(v).update(tag='x'), 'exactly image, component and sourceCommit')
        self.refuses(lambda v: entry(v).update(sourceCommit='c562bbab'), 'not a full SHA')
        self.refuses(lambda v: entry(v).update(sourceCommit=TIP), 'no exception')
        self.refuses(lambda v: v['workloads'].update({'cluster-gateway': dict(entry(v))}), 'declared twice')
        self.refuses(lambda v: entry(v).update(image='Cluster_Node'), 'malformed')


class Images(unittest.TestCase):
    defaults = ['cluster-node', 'trade-service']

    def check(self, **built):
        population.check_images(population.parse(DECLARED), built, [n for n in built if n != 'cluster-node-member'])

    def test_the_declared_population_passes(self):
        self.check(**{'cluster-node': TIP, 'trade-service': TIP, 'cluster-node-member': MEMBER})

    def test_an_image_from_neither_declared_commit_is_refused(self):
        with self.assertRaisesRegex(population.Refusal, 'neither declared commit'):
            self.check(**{'cluster-node': TIP, 'trade-service': OTHER, 'cluster-node-member': MEMBER})
        with self.assertRaisesRegex(population.Refusal, 'neither declared commit'):
            self.check(**{'cluster-node': TIP, 'trade-service': TIP, 'cluster-node-member': OTHER})

    def test_a_default_image_from_the_member_commit_is_an_undeclared_mix(self):
        with self.assertRaisesRegex(population.Refusal, 'undeclared mix'):
            self.check(**{'cluster-node': MEMBER, 'trade-service': TIP, 'cluster-node-member': MEMBER})

    def test_the_member_image_from_the_tip_commit_is_an_undeclared_mix(self):
        with self.assertRaisesRegex(population.Refusal, 'undeclared mix'):
            self.check(**{'cluster-node': TIP, 'trade-service': TIP, 'cluster-node-member': TIP})

    def test_a_missing_exception_image_is_refused(self):
        with self.assertRaisesRegex(population.Refusal, "missing \\['cluster-node-member'\\]"):
            self.check(**{'cluster-node': TIP, 'trade-service': TIP})


class Admit(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.admit, _ = declared_modules()

    def plan(self, **changes):
        ref = lambda name, n: f'us-east1-docker.pkg.dev/project-compsci-484414/traderx-demo/{name}@sha256:{n * 64}'
        plan = {'artifacts': {ref('cluster-node', '1'): {'provider': 'compiled-context-v1', 'component': 'order-matcher',
                                                         'sourceCommit': TIP},
                              ref('trade-service', '2'): {'provider': 'compiled-context-v1', 'component': 'trade-service',
                                                          'sourceCommit': TIP},
                              ref('cluster-node-member', '3'): {'provider': 'compiled-context-v1',
                                                                'component': 'order-matcher', 'sourceCommit': MEMBER},
                              'docker.io/library/nats@sha256:' + '4' * 64: {'provider': 'locked-upstream-v1'}},
                'workloadDerivations': {MEMBER: {'path': 'evidence/derivation-222222222222.json', 'sha256': '5' * 64}}}
        for name, fields in changes.items():
            target = next(v for r, v in plan['artifacts'].items() if self.admit.image_name(r) == name) \
                if name != 'plan' else plan
            fields(target) if callable(fields) else target.update(fields)
        return plan

    def refuses(self, message, **changes):
        with self.assertRaisesRegex(self.admit.ri20.Refusal, message):
            self.admit.check_population(self.plan(**changes))

    def test_the_declared_two_commit_plan_passes(self):
        self.admit.check_population(self.plan())
        self.assertEqual(self.admit.PRIVATE_IMAGES, 13)

    def test_neither_commit(self):
        self.refuses('neither declared commit', **{'trade-service': {'sourceCommit': OTHER}})

    def test_tip_image_on_the_member_commit(self):
        self.refuses('undeclared mix', **{'cluster-node': {'sourceCommit': MEMBER}})

    def test_member_image_on_the_tip_commit(self):
        self.refuses('undeclared mix', **{'cluster-node-member': {'sourceCommit': TIP}})

    def test_member_image_missing(self):
        plan = self.plan()
        plan['artifacts'] = {r: v for r, v in plan['artifacts'].items() if 'cluster-node-member' not in r}
        with self.assertRaisesRegex(self.admit.ri20.Refusal, 'missing'):
            self.admit.check_population(plan)

    def test_two_digests_for_one_image_name(self):
        plan = self.plan()
        plan['artifacts']['us-east1-docker.pkg.dev/project-compsci-484414/traderx-demo/cluster-node@sha256:' + '9' * 64] = \
            {'provider': 'compiled-context-v1', 'component': 'order-matcher', 'sourceCommit': TIP}
        with self.assertRaisesRegex(self.admit.ri20.Refusal, 'two digests'):
            self.admit.check_population(plan)

    def test_member_image_of_another_component(self):
        self.refuses('another component', **{'cluster-node-member': {'component': 'trade-service'}})

    def test_member_image_without_its_own_derivation(self):
        self.refuses('without its own derivation', plan=lambda p: p.pop('workloadDerivations'))

    def test_validate_runs_the_population_check(self):
        # The check is reached from validate(), the entrypoint admit.py's CLI and admission-controls.py call.
        with mock.patch.object(self.admit, 'check_population', side_effect=self.admit.ri20.Refusal('reached')) as check:
            root = HERE.parents[1]
            receipt = {'schema': 'traderx-generation-derivation-v1', 'state': 'YU18-risk-integration', 'role': 'fix',
                       'ancestry': sorted(self.admit.ri20.lineage(root, 'YU18-risk-integration')),
                       'sourceInputsSha256': '0' * 64, 'contexts': {str(i): '0' * 64 for i in range(11)}}
            plan = {'schema': 'traderx-compsci-intended-deployment-v1', 'target': self.admit.TARGET,
                    'state': 'YU18-risk-integration', 'renderedSha256': self.admit.digest(b''),
                    'contexts': receipt['contexts'], 'imageSourceCommit': self.admit.REVIEWED_IMAGE_COMMIT}
            try:
                self.admit.validate(root, '', plan, {}, HERE, receipt, '0' * 64)
            except self.admit.ri20.Refusal:
                pass
            check.assert_called_once()


class Measure(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.admit, cls.measure = declared_modules()

    def pull(self, commits):
        refs = {name: f'{self.measure.REGISTRY}{name}@sha256:{str(i) * 64}' for i, name in enumerate(commits, 1)}
        def inspect(args, **kw):
            ref = args[-1]; name = ref[len(self.measure.REGISTRY):].split('@')[0]
            return json.dumps([{'Os': 'linux', 'Architecture': 'amd64', 'RepoDigests': [ref], 'Id': 'sha256:' + '0' * 64,
                                'Config': {'Labels': {'dev.traderx.source.commit': commits[name],
                                                      'dev.traderx.derivation.sha256': ('a' if commits[name] == TIP else 'b') * 64}}}])
        with mock.patch.object(self.measure, 'run'), mock.patch.object(self.measure, 'out', side_effect=inspect):
            return self.measure.pull(refs)

    def test_pulled_images_from_their_declared_commits_pass(self):
        images = self.pull({'cluster-node': TIP, 'trade-service': TIP, 'cluster-node-member': MEMBER})
        self.assertEqual(images['cluster-node-member']['sourceCommit'], MEMBER)

    def test_a_pulled_image_from_neither_commit_is_refused(self):
        with self.assertRaisesRegex(SystemExit, 'neither declared commit'):
            self.pull({'cluster-node': TIP, 'trade-service': OTHER, 'cluster-node-member': MEMBER})

    def test_a_pulled_tip_image_built_from_the_member_commit_is_refused(self):
        with self.assertRaisesRegex(SystemExit, 'undeclared mix'):
            self.pull({'cluster-node': MEMBER, 'trade-service': TIP, 'cluster-node-member': MEMBER})

    def test_a_pulled_member_image_built_from_the_tip_is_refused(self):
        with self.assertRaisesRegex(SystemExit, 'undeclared mix'):
            self.pull({'cluster-node': TIP, 'trade-service': TIP, 'cluster-node-member': TIP})

    def candidate(self, member_image, gateway_image):
        doc = lambda kind, name, image: {'kind': kind, 'metadata': {'name': name}, 'spec': {'template': {'spec': {
            'containers': [{'name': 'c', 'image': f'{self.measure.REGISTRY}{image}@sha256:{"1" * 64}'}]}}}}
        temp = tempfile.NamedTemporaryFile('w', suffix='.yaml', delete=False)
        import yaml
        yaml.safe_dump_all([doc('StatefulSet', 'order-matcher-cluster', member_image),
                            doc('Deployment', 'cluster-gateway', gateway_image)], temp); temp.close()
        self.addCleanup(os.unlink, temp.name)
        return temp.name

    def test_the_members_running_the_tip_image_are_refused(self):
        with self.assertRaisesRegex(SystemExit, 'order-matcher-cluster runs cluster-node, not its declared'):
            self.measure.population(self.candidate('cluster-node', 'cluster-node'))

    def test_another_workload_running_the_member_image_is_refused(self):
        with self.assertRaisesRegex(SystemExit, 'cluster-gateway runs cluster-node-member'):
            self.measure.population(self.candidate('cluster-node-member', 'cluster-node-member'))

    def test_the_declared_placement_reaches_the_population_count(self):
        # Placement holds, so the refusal is the (deliberately incomplete) population count, not placement.
        with self.assertRaisesRegex(SystemExit, 'candidate population differs'):
            self.measure.population(self.candidate('cluster-node-member', 'cluster-node'))


if __name__ == '__main__':
    unittest.main()
