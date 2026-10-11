"""Offline render controls: wrong project and public exposure must be rejected."""
import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest
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


if __name__=='__main__': unittest.main()
