import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import bundle
from coordinator import Coordinator, MockAdapter
from job_status import snapshot
from w0_result import W0FileAdapter

FIX=Path(__file__).parent/'fixtures'

class JobStatusTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.state=self.root/'state'
        self.inbox=self.root/'inbox';self.inbox.mkdir()
        shutil.copytree(FIX/'shared/bill/v2',self.inbox/'bill')

    def test_missing_does_not_create_state(self):
        with self.assertRaises(ValueError):snapshot(self.state)
        self.assertFalse(self.state.exists())

    def test_empty_and_running_with_writer_lock(self):
        with Coordinator(self.state) as ctl:
            self.assertEqual(snapshot(self.state)['jobs'],[])
            ctl.discover(self.inbox)
            self.assertEqual(snapshot(self.state)['jobs'][0]['status'],'QUEUED')
            with ctl.db:
                ctl.db.execute("UPDATE jobs SET status='RUNNING'")
            before=(self.state/'jobs.sqlite3').read_bytes()
            view=snapshot(self.state)
            self.assertEqual(view['jobs'][0]['status'],'RUNNING')
            self.assertEqual((self.state/'jobs.sqlite3').read_bytes(),before)
            self.assertEqual(view['workerConnectivity'],'NOT_PROBED')

    def test_mock_and_corruption(self):
        with Coordinator(self.state) as ctl:
            ctl.discover(self.inbox); row=ctl.run()['jobs'][0]
        view=snapshot(self.state);job=view['jobs'][0]
        self.assertTrue(job['selectedMockResult']);self.assertFalse(job['pricingAvailable'])
        self.assertEqual(job['coverage'],{'submitted':2,'priced':0})
        self.assertNotIn('result_path',job)
        result=self.state/row['result_path']/'results.json'
        result.write_bytes(result.read_bytes()+b' ')
        job=snapshot(self.state)['jobs'][0]
        self.assertEqual(job['resultIntegrity'],'INVALID');self.assertIsNone(job['coverage'])
        self.assertFalse(job['selectedMockResult'])

    def test_failed(self):
        class Fail(MockAdapter):
            def execute(self,*args): raise ValueError('worker unavailable')
        with Coordinator(self.state,Fail()) as ctl:
            ctl.discover(self.inbox);ctl.run()
        job=snapshot(self.state)['jobs'][0]
        self.assertEqual(job['status'],'FAILED');self.assertIn('worker unavailable',job['error'])

    def test_w0_pending_then_validated(self):
        incoming=self.root/'incoming';incoming.mkdir()
        with Coordinator(self.state,W0FileAdapter(incoming)) as ctl:
            ctl.discover(self.inbox);ctl.run()
            job=snapshot(self.state)['jobs'][0]
            self.assertEqual(job['status'],'RUNNING');self.assertIsNone(job['coverage'])
            shutil.copyfile(FIX/'w0-results/bill.json',incoming/(job['bundle_id']+'.json'))
            ctl.run()
        job=snapshot(self.state)['jobs'][0]
        self.assertEqual(job['status'],'W0_VALIDATED');self.assertTrue(job['selectedW0Result'])
        self.assertFalse(job['selectedMockResult']);self.assertEqual(job['coverage']['itemCount'],2)
        self.assertFalse(job['portfolioRiskAvailable'])
