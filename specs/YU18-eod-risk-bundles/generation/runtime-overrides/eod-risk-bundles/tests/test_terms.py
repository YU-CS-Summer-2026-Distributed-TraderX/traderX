import copy
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import bundle
import instrument_terms
import verify_golden
from coordinator import Coordinator
from http_adapter import HttpAdapter
from worker_protocol import PROFILE, HTTP_PROFILE

FIXTURES=Path(__file__).parent/'fixtures'


class TermsTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name).resolve()
        self.source=FIXTURES/'shared/note/v2'
        self.terms=json.loads((self.source/'instrument-terms.json').read_bytes())

    def build(self,terms=None,origin='synthetic',epoch='synthetic-shared-examples-v1',name='out'):
        path=self.root/'terms.json';path.write_bytes(bundle.encoded(self.terms if terms is None else terms))
        return bundle.build(self.source/'positions.csv',self.source/'contracts.csv',self.root/name,
                            epoch,'2025-06-02T16:00:00-04:00',origin,path)

    def test_all_exported_shared_examples_and_v1_bytes(self):
        for name in ('bill','note','sofr'):
            source=FIXTURES/'shared'/name
            for version in ('v1','v2'):
                manifest,parsed=bundle.validate(source/version)
                self.assertEqual(manifest['inputOrigin'],'synthetic')
                self.assertEqual(manifest['cut']['cutSha256'],bundle.digest((source/'cut.txt').read_bytes()))
            for kind in ('positions','contracts'):
                self.assertEqual((source/'v1'/(kind+'.csv')).read_bytes(),(source/'v2'/(kind+'.csv')).read_bytes())
            self.assertEqual(len(parsed['positions'][1]),0 if name=='sofr' else 2)
            self.assertEqual(len(parsed['contracts'][1]),1 if name=='sofr' else 0)

    def test_v1_golden_matches_implementation_and_independent_verifier(self):
        root=FIXTURES/'golden-v1'
        self.assertEqual(verify_golden.verify(root),2)
        for case in json.loads((root/'expected.json').read_bytes())['cases']:
            p=root/case['name'];m=json.loads((p/'manifest.json').read_bytes())
            actual=bundle.manifest_for({k:(p/(k+'.csv')).read_bytes() for k in bundle.KINDS},
                                      m['clusterEpoch'],m['valuationTime'],m['inputOrigin'])
            self.assertEqual(bundle.encoded(actual),(p/'manifest.json').read_bytes())
            self.assertEqual(actual['bundleId'],case['bundleId'])
            for label, profile in [('local',PROFILE),('http',HTTP_PROFILE)]:
                self.assertEqual(bundle.digest(bundle.encoded({'bundleId':actual['bundleId'],'profile':profile})),case['workloads'][label])

    def test_golden_verifier_rejects_changed_csv(self):
        dst=self.root/'golden';shutil.copytree(FIXTURES/'golden-v1',dst)
        p=dst/'basic/positions.csv';p.write_bytes(p.read_bytes()+b'\n')
        with self.assertRaises(AssertionError):verify_golden.verify(dst)

    def test_terms_roundtrip_identity_and_local_coordinator(self):
        m=self.build(name='inbox/cut')
        with Coordinator(self.root/'state') as c:
            self.assertEqual(len(c.discover(self.root/'inbox')['queued']),1)
            job=c.run()['jobs'][0]
            self.assertEqual(job['status'],'MOCK_COMPLETE')
            self.assertEqual(job['resultIntegrity'],'VERIFIED')
            self.assertFalse(job['usableForRisk'])
            self.assertEqual((self.root/'state/inputs'/m['bundleId']/'instrument-terms.json').read_bytes(),
                             (self.root/'terms.json').read_bytes())
            self.assertEqual(len(c.discover(self.root/'inbox')['duplicates']),1)

    def test_v2_cli_build_validate(self):
        command=[sys.executable,str(Path(bundle.__file__))]
        built=subprocess.run(command+['build','--positions',str(self.source/'positions.csv'),
            '--contracts',str(self.source/'contracts.csv'),'--terms',str(self.source/'instrument-terms.json'),
            '--output',str(self.root/'cli'),'--epoch','synthetic-shared-examples-v1','--origin','synthetic',
            '--valuation-time','2025-06-02T16:00:00-04:00'],capture_output=True)
        self.assertEqual(built.returncode,0,built.stderr)
        self.assertEqual(bundle.validate(self.root/'cli')[0]['schema'],'traderx.eod-bundle.v2')

    def test_terms_change_changes_bundle_and_workload_identity(self):
        a=self.build(name='inbox/a')
        self.terms['entries'][0]['terms']['faceDenomination']='1000'
        b=self.build(name='inbox/b');self.assertNotEqual(a['bundleId'],b['bundleId'])
        with Coordinator(self.root/'state') as c:
            self.assertEqual(len(c.discover(self.root/'inbox')['queued']),2)

    def test_missing_extra_duplicate_identity_rejected(self):
        for entries in ([],self.terms['entries']*2,[{**self.terms['entries'][0], 'identity':{'source':'positions','security':'WRONG'}}]):
            with self.subTest(entries=entries),self.assertRaises(ValueError):self.build({**self.terms,'entries':entries})

    def test_export_disagreement_rejected(self):
        for field,value in [('couponRatePercent','5'),('maturityDate','2027-12-15'),('currency','EUR'),('dayCount','ACT/365'),('couponFrequency','1Y'),('settlementDays',True)]:
            doc=copy.deepcopy(self.terms);doc['entries'][0]['terms'][field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):self.build(doc)

    def test_missing_term_is_explicit_and_allowed(self):
        entry=self.terms['entries'][0];del entry['terms']['calendar']
        with self.assertRaises(ValueError):self.build()
        entry['missingTerms']=['calendar']
        self.build()
        self.assertEqual(bundle.validate(self.root/'out')[0]['schema'],'traderx.eod-bundle.v2')

    def test_synthetic_terms_cannot_be_labeled_real_export(self):
        with self.assertRaisesRegex(ValueError,'synthetic terms'):self.build(origin='export')

    def test_term_tampering_missing_and_symlink_rejected(self):
        for mode in ('tamper','missing','symlink'):
            self.build(name=mode);p=self.root/mode/'instrument-terms.json'
            if mode=='tamper':p.write_bytes(p.read_bytes()+b' ')
            else:
                p.unlink()
                if mode=='symlink':p.symlink_to(self.root/'terms.json')
            with self.subTest(mode=mode),self.assertRaises(ValueError):bundle.validate(self.root/mode)

    def test_unknown_artifact_path_rejected(self):
        self.build();p=self.root/'out/manifest.json';m=json.loads(p.read_bytes())
        m['artifacts']['instrumentTerms']['path']='../terms.json';p.write_bytes(bundle.encoded(m))
        with self.assertRaises(ValueError):bundle.validate(self.root/'out')

    def test_schedule_gap_and_null_day_count_rejected(self):
        for field,value in [('schedule',[{'startDate':'2025-06-15','endDate':'2026-12-15','paymentDate':'2026-12-15'}]),('dayCount',None)]:
            doc=copy.deepcopy(self.terms);doc['entries'][0]['terms'][field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):self.build(doc)

    def test_sofr_epoch_and_fixed_frequency_not_float_tenor(self):
        self.source=FIXTURES/'shared/sofr/v2';self.terms=json.loads((self.source/'instrument-terms.json').read_bytes())
        with self.assertRaisesRegex(ValueError,'epoch mismatch'):self.build(epoch='other')
        entry=self.terms['entries'][0]
        self.assertEqual(entry['terms']['fixedPaymentFrequency'],'1Y')
        self.assertNotIn('floatingSchedule',entry['terms'])
        self.assertIn('overnightCompounding',entry['missingTerms'])
        case=json.loads((self.source.parent/'case.json').read_bytes())
        self.assertEqual(case['expectedWorkerOutcome'],'unsupported')
        self.assertFalse(case['expectationIsObservedResult'])

    def test_http_v1_draft_refuses_v2_before_network(self):
        self.build()
        adapter=HttpAdapter('http://127.0.0.1:1')
        with self.assertRaisesRegex(ValueError,'v1 only'):adapter.execute(self.root/'out',self.root/'result')

    def test_note_has_signed_accrual_and_bill_no_coupon_schedule(self):
        _,parsed=bundle.validate(self.source)
        rows=parsed['positions'][1]
        values=[bundle.decimal(r['quantity'],'quantity')*bundle.decimal(r['accruedInterestFraction'],'accrual') for r in rows]
        self.assertGreater(values[0],0);self.assertEqual(values[0],-values[1])
        bill=json.loads((FIXTURES/'shared/bill/v2/instrument-terms.json').read_bytes())['entries'][0]
        self.assertEqual(bill['terms']['schedule'],[])
        self.assertIsNone(bill['terms']['firstCouponDate'])
        self.assertEqual(bill['missingTerms'],[])

    def test_duplicate_json_key_rejected(self):
        with self.assertRaisesRegex(ValueError,'duplicate'):instrument_terms.decode(b'{"schema":1,"schema":2}')


if __name__=='__main__':unittest.main()
