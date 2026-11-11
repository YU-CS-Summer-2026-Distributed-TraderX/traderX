"""aeronmd-sidecar.py (FR-GMR-14): the C driver mirrors the member's Java driver, and the pod stays Guaranteed."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('sidecar', Path(__file__).with_name('aeronmd-sidecar.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

STS = {'kind': 'StatefulSet', 'metadata': {'name': 'order-matcher-cluster'}, 'spec': {'template': {'spec': {
    'containers': [{'name': 'cluster-node', 'image': 'member',
                    'env': [{'name': 'CLUSTER_AERON_DIR', 'value': '/dev/shm/aeron-cluster'},
                            {'name': 'CLUSTER_IDLE_STRATEGY', 'value': 'lowpark'},
                            {'name': 'CLUSTER_IDLE_STRATEGY_SENDER', 'value': 'busyspin'},
                            {'name': 'JAVA_TOOL_OPTIONS', 'value': '-Xmx12g -Daeron.socket.so_rcvbuf=4194304 '
                             '-Daeron.driver.sender.cycle.threshold=1ms -Daeron.cluster.cycle.threshold=1ms'}],
                    'resources': {'requests': {'cpu': '15', 'memory': '40Gi'}, 'limits': {'cpu': '15', 'memory': '40Gi'}},
                    'volumeMounts': [{'name': 'shm', 'mountPath': '/dev/shm'}]}],
    'volumes': [{'name': 'shm', 'emptyDir': {'medium': 'Memory', 'sizeLimit': '1Gi'}}]}}}}


class Sidecar(unittest.TestCase):
    def test_mirrors_the_java_driver_and_keeps_the_pod_guaranteed(self):
        sts = m.patch(copy.deepcopy(STS), 'img', 3, {})
        pod = sts['spec']['template']['spec']
        member, side = pod['containers'][0], pod['initContainers'][-1]
        env = {e['name']: e['value'] for e in side['env']}
        self.assertEqual(side['restartPolicy'], 'Always')
        self.assertEqual(env['AERON_DIR'], '/dev/shm/aeron-cluster')
        self.assertEqual(env['AERON_DIR_DELETE_ON_START'], 'true')
        self.assertEqual(env['AERON_TERM_BUFFER_SPARSE_FILE'], 'true')
        self.assertEqual(env['AERON_THREADING_MODE'], 'DEDICATED')
        self.assertEqual(env['AERON_SOCKET_SO_RCVBUF'], '4194304')
        self.assertEqual(env['AERON_DRIVER_SENDER_CYCLE_THRESHOLD'], '1ms')
        self.assertNotIn('AERON_CLUSTER_CYCLE_THRESHOLD', env)
        self.assertEqual(env['AERON_SENDER_IDLE_STRATEGY'], 'noop')            # per-agent wins
        self.assertEqual(env['AERON_RECEIVER_IDLE_STRATEGY'], 'backoff')       # member-wide lowpark
        self.assertEqual(env['AERON_RECEIVER_IDLE_STRATEGY_INIT_ARGS'], '10-5-1000-1000')
        self.assertEqual(side['volumeMounts'], [{'name': 'shm', 'mountPath': '/dev/shm'}])
        self.assertEqual(side['resources']['requests'], side['resources']['limits'])
        self.assertEqual(side['resources']['limits'], {'cpu': '3', 'memory': '1280Mi'})
        self.assertEqual(member['resources']['limits']['cpu'], '12')
        self.assertEqual(member['resources']['requests']['cpu'], '12')
        self.assertIn({'name': 'CLUSTER_MEDIA_DRIVER', 'value': 'external'}, member['env'])

    def test_unset_idle_leaves_the_c_default_and_override_wins(self):
        sts = copy.deepcopy(STS)
        sts['spec']['template']['spec']['containers'][0]['env'] = [{'name': 'CLUSTER_AERON_DIR', 'value': '/dev/shm/a'}]
        env = {e['name']: e['value'] for e in
               m.patch(sts, 'img', 3, {'AERON_THREADING_MODE': 'SHARED'})['spec']['template']['spec']['initContainers'][0]['env']}
        self.assertFalse(any(k.endswith('_IDLE_STRATEGY') for k in env))
        self.assertEqual(env['AERON_THREADING_MODE'], 'SHARED')

    def test_refuses_what_it_cannot_mirror(self):
        bad = copy.deepcopy(STS)
        bad['spec']['template']['spec']['containers'][0]['resources']['limits']['cpu'] = '3'
        bad['spec']['template']['spec']['containers'][0]['resources']['requests']['cpu'] = '3'
        self.assertRaises(SystemExit, m.patch, bad, 'img', 3, {})
        off = copy.deepcopy(STS)
        off['spec']['template']['spec']['containers'][0]['env'][0]['value'] = '/tmp/aeron'
        self.assertRaises(SystemExit, m.patch, off, 'img', 3, {})


if __name__ == '__main__':
    unittest.main()
