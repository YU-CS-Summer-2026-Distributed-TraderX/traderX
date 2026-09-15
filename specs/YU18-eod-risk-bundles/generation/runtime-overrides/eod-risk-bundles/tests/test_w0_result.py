import copy
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bundle
import coordinator
import w0_result as w

FIX = Path(__file__).parent / 'fixtures'

class W0Tests(unittest.TestCase):
    def test_actual_outputs(self):
        for name, conversions in [('bill',2),('note',2),('sofr',0)]:
            with self.subTest(name=name):
                result=w.validate(FIX/'shared'/name/'v2',(FIX/'w0-results'/(name+'.json')).read_bytes())
                self.assertEqual(result,dict(pricedItems=0,accrualConversions=conversions,portfolioRiskAvailable=False,usableForRisk=False))

    def test_corrupt_outputs_rejected(self):
        cases=[(['bundleId'],'wrong'),(['clusterEpoch'],'wrong'),(['valuationTime'],'wrong'),
               (['sessionDate'],'wrong'),(['engineVersion'],'2'),(['marketInputs'],{}),
               (['items',0,'sourceIdentity','accountId'],'wrong'),(['items',0,'currency'],'EUR'),
               (['items',0,'itemId'],'unknown'),(['items',0,'calculations','npv'],{'status':'ok','value':12}),
               (['items',0,'calculations','accruedInterest','value'],-1857.1),
               (['items',1,'calculations','accruedInterest','value'],1857.1),
               (['items',0,'calculations','accruedInterest','value'],True),
               (['items',0,'calculations','accruedInterest','currency'],'EUR'),
               (['items',0,'calculations','accruedInterest','signedFaceAmount'],1),
               (['items',0,'calculations','vega','detail'],'missing market data'),
               (['coverage','allApplicableComputed'],True),(['coverage','itemCount'],True),
               (['coverage','byCalculation','npv','ok'],1),(['itemOrder','sha256'],'bad')]
        original=json.loads((FIX/'w0-results/note.json').read_bytes())
        for path,value in cases:
            with self.subTest(path=path):
                data=copy.deepcopy(original); target=data
                for key in path[:-1]: target=target[key]
                target[path[-1]]=value
                with self.assertRaises(ValueError): w.validate(FIX/'shared/note/v2',json.dumps(data).encode())
        for items in [original['items'][:1],original['items']+[original['items'][0]]]:
            data=copy.deepcopy(original);data['items']=items
            with self.assertRaises(ValueError): w.validate(FIX/'shared/note/v2',json.dumps(data).encode())

    def test_swap_missing_terms_and_schema(self):
        data=json.loads((FIX/'w0-results/sofr.json').read_bytes())
        data['items'][0]['refusal']['missingTerms']=[]
        with self.assertRaises(ValueError):w.validate(FIX/'shared/sofr/v2',json.dumps(data).encode())
        with self.assertRaises(ValueError):w.validate(FIX/'compatibility/note-structured-basis',(FIX/'w0-results/note.json').read_bytes())

    def test_invalid_json(self):
        data=(FIX/'w0-results/note.json').read_bytes()
        for corrupt in [b'{"a":1,"a":2}',data.replace(b'1857.1000000000001',b'NaN'),b'[]']:
            with self.assertRaises(ValueError):w.validate(FIX/'shared/note/v2',corrupt)

    def test_pending_completion_integrity_and_dedup(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);inbox=root/'inbox';inbox.mkdir();results=root/'incoming';results.mkdir()
            shutil.copytree(FIX/'shared/note/v2',inbox/'note')
            adapter=w.W0FileAdapter(results)
            with coordinator.Coordinator(root/'state',adapter) as ctl:
                ctl.discover(inbox)
                job=ctl.run()['jobs'][0];self.assertEqual(job['status'],'RUNNING')
                self.assertIn('LOCAL_RESULT_PENDING',job['error'])
                manifest=json.loads((inbox/'note/manifest.json').read_bytes())
                incoming=results/(manifest['bundleId']+'.json')
                shutil.copyfile(FIX/'w0-results/note.json',incoming)
                job=ctl.run()['jobs'][0]
                self.assertEqual(job['status'],'W0_VALIDATED');self.assertTrue(job['selectedW0Result'])
                self.assertFalse(job['selectedMockResult']);self.assertFalse(job['usableForRisk'])
                self.assertEqual(len(job['attempts']),1)
                self.assertEqual(len(ctl.discover(inbox)['duplicates']),1)
                self.assertEqual(ctl.run()['processed'],[])
                incoming.unlink()
                self.assertEqual(ctl.status()['jobs'][0]['resultIntegrity'],'VERIFIED')
                stored=root/'state'/job['result_path']/'results.json';stored.write_text('{}')
                job=ctl.status()['jobs'][0]
                self.assertFalse(job['selectedW0Result']);self.assertNotEqual(job['resultIntegrity'],'VERIFIED')
            with self.assertRaises(ValueError):
                with coordinator.Coordinator(root/'state'): pass

    def test_recovery_after_publication_before_database_completion(self):
        class Interrupted(w.W0FileAdapter):
            def execute(self, source, destination):
                super().execute(source,destination)
                raise KeyboardInterrupt('simulated process interruption after atomic publication')
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);inbox=root/'inbox';inbox.mkdir();incoming=root/'incoming';incoming.mkdir()
            shutil.copytree(FIX/'shared/bill/v2',inbox/'cut')
            m,_=bundle.validate(inbox/'cut')
            shutil.copyfile(FIX/'w0-results/bill.json',incoming/(m['bundleId']+'.json'))
            with coordinator.Coordinator(root/'state',Interrupted(incoming)) as ctl:
                ctl.discover(inbox)
                with self.assertRaises(KeyboardInterrupt):ctl.run()
            with coordinator.Coordinator(root/'state',w.W0FileAdapter(incoming)) as ctl:
                job=ctl.run()['jobs'][0]
                self.assertEqual(job['status'],'W0_VALIDATED')
                self.assertEqual(len(job['attempts']),1)
                self.assertTrue(job['selectedW0Result'])

    def test_receipt_tamper_and_symlink(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=FIX/'shared/bill/v2';m,_=bundle.validate(source)
            incoming=root/(m['bundleId']+'.json');shutil.copyfile(FIX/'w0-results/bill.json',incoming)
            adapter=w.W0FileAdapter(root);adapter.execute(source,root/'output')
            adapter.validate_result(source,root/'output')
            (root/'output/acceptance.json').write_text('{}')
            with self.assertRaises(ValueError):adapter.validate_result(source,root/'output')
            incoming.unlink();incoming.symlink_to((FIX/'w0-results/bill.json').resolve())
            with self.assertRaises(ValueError):adapter.execute(source,root/'other')

if __name__=='__main__':unittest.main()
