import copy
import json
from decimal import Decimal
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bundle
import coordinator
import job_status
import pricing_result as p
import w0_result

FIX = Path(__file__).parent/'fixtures'


def original(name='note'):
    return json.loads((FIX/'pricing-results'/(name+'.json')).read_bytes())


def source(name='note'):
    return FIX/'shared'/name/'v2'


def leaves(value, path=()):
    if isinstance(value, dict):
        for key, item in value.items():
            yield from leaves(item, path+(key,))
    elif isinstance(value, list) and value:
        for key, item in enumerate(value):
            yield from leaves(item, path+(key,))
    else:
        yield path, value


class PricingTests(unittest.TestCase):
    def test_real_outputs_and_w0_separation(self):
        for name in ('bill','note'):
            data=(FIX/'pricing-results'/(name+'.json')).read_bytes()
            summary=p.validate(source(name),data)
            self.assertEqual(summary['pricedItems'],2)
            self.assertEqual(summary['noteParallelBumpItems'],2 if name=='note' else 0)
            self.assertFalse(summary['usableForRisk']);self.assertFalse(summary['portfolioRiskAvailable'])
            with self.assertRaisesRegex(ValueError,'permits no market computation'):
                w0_result.validate(source(name),data)
            with self.assertRaises(ValueError):
                p.validate(source(name),(FIX/'w0-results'/(name+'.json')).read_bytes())

    def test_every_result_leaf_is_checked(self):
        # Adversaries derived from the producer output, not the expected-value builder.
        for name in ('bill','note'):
            for path,value in leaves(original(name)):
                changed=copy.deepcopy(original(name));target=changed
                for key in path[:-1]:target=target[key]
                replacement = (not value if isinstance(value,bool) else value+1 if isinstance(value,(int,float))
                               else ['invented'] if isinstance(value,list) else 'tampered')
                target[path[-1]]=replacement
                with self.subTest(name=name,path=path):
                    with self.assertRaises(ValueError):p.validate(source(name),json.dumps(changed).encode())

    def test_missing_extra_reordered_items_and_capabilities(self):
        for name in ('bill','note'):
            base=original(name)
            for items in [base['items'][:1], base['items']*2, base['items'][::-1], []]:
                data=copy.deepcopy(base);data['items']=items
                order={'scheme':'traderx-item-v1','itemIds':[i['itemId'] for i in items]}
                data['itemOrder']={**order,'itemCount':len(items),'sha256':bundle.digest(p.compact(order))}
                with self.assertRaises(ValueError):p.validate(source(name),bundle.encoded(data))
            for key in ('capabilities','schema','usableForRisk'):
                data=copy.deepcopy(base);data[key]=True
                with self.assertRaises(ValueError):p.validate(source(name),bundle.encoded(data))
            for calc in ('rateGamma','theta','vega','varEs','rateSensitivity'):
                data=copy.deepcopy(base)
                data['items'][0]['calculations'][calc]={'status':'ok','value':0}
                with self.assertRaises(ValueError):p.validate(source(name),bundle.encoded(data))
            for calc in ('npv','accruedInterest'):
                data=copy.deepcopy(base);del data['items'][0]['calculations'][calc]
                with self.assertRaises(ValueError):p.validate(source(name),bundle.encoded(data))

    def test_numeric_types_nonfinite_units_sign_and_bump(self):
        base=original()
        paths=[('npv','value'),('npv','cleanNpv'),('accruedInterest','value'),('rateSensitivity','value'),('rateSensitivity','bump')]
        for calc,key in paths:
            for value in (float('nan'),float('inf'),-float('inf'),True,'1',None,0):
                data=copy.deepcopy(base);data['items'][0]['calculations'][calc][key]=value
                with self.subTest(calc=calc,key=key,value=value):
                    with self.assertRaises(ValueError):p.validate(source(),json.dumps(data).encode())
        data=copy.deepcopy(base);data['items'][0]['calculations']['rateSensitivity']['value']/=0.0001
        with self.assertRaises(ValueError):p.validate(source(),bundle.encoded(data))
        data=copy.deepcopy(base);data['items'][0]['calculations']['rateSensitivity']['value']*=-1
        with self.assertRaises(ValueError):p.validate(source(),bundle.encoded(data))
        with self.assertRaises(ValueError):p.validate(source(),b'{"bundleId":1,"bundleId":2}')

    def test_numerical_allowance_and_unrounded_accrual_bound(self):
        self.assertEqual(p.accrual_bound(Decimal('124000')),Decimal('0.072'))
        self.assertEqual(p.accrual_bound(Decimal('-130000')),Decimal('0.075'))
        data=original();data['items'][0]['calculations']['npv']['value']+=1e-9
        p.validate(source(),bundle.encoded(data))
        data=original();data['items'][0]['calculations']['npv']['value']+=1e-6
        with self.assertRaises(ValueError):p.validate(source(),bundle.encoded(data))
        data=original();data['items'][0]['calculations']['npv']['accrualReconciliation']['tolerance']=0.07
        with self.assertRaises(ValueError):p.validate(source(),bundle.encoded(data))

    def test_independent_reference_known_economics(self):
        m,rows,terms=p.inputs(source())
        npv,accrual,bump=p.reference(rows[0],terms,m['cut']['sessionDate'])
        self.assertEqual(len(npv['coupons']),4)
        self.assertLess(abs(npv['coupons'][0]['yearFraction'].value-Decimal(13)/Decimal(365)),Decimal('1e-27'))
        self.assertAlmostEqual(float(npv['value'].value),103308.32664442991,places=8)
        self.assertAlmostEqual(float(bump['value'].value),-15.283220896104467,places=8)
        self.assertEqual(accrual['value'].value,Decimal('1857.100000'))
        self.assertAlmostEqual(float(npv['accrualReconciliation']['recomputedFraction'].value),169/182*0.04/2,places=14)

    def test_rehashed_input_changes_and_unsupported_cases_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for field,value in [('epoch','other'),('valuation','2025-06-02T17:00:00-04:00'),
                                ('date','2025-02-30'),('face','124000'),('account','other')]:
                copied=root/field;shutil.copytree(source(),copied)
                if field=='date':
                    doc=json.loads((copied/'instrument-terms.json').read_bytes())
                    doc['entries'][0]['terms']['schedule'][0]['endDate']=value
                    (copied/'instrument-terms.json').write_bytes(bundle.encoded(doc))
                if field in ('face','account'):
                    f=copied/'positions.csv';f.write_text(f.read_text().replace('100000' if field=='face' else '22214',value))
                try:
                    m,_=bundle.validate(source())
                    payloads={k:(copied/(k+'.csv')).read_bytes() for k in ('positions','contracts')}
                    new=bundle.manifest_for(payloads,value if field=='epoch' else m['clusterEpoch'],
                        value if field=='valuation' else m['valuationTime'],'synthetic',(copied/'instrument-terms.json').read_bytes())
                    (copied/'manifest.json').write_bytes(bundle.encoded(new))
                except ValueError:
                    pass  # Some invalid economics/dates already fail the independent bundle gate.
                with self.assertRaises(ValueError):p.validate(copied,bundle.encoded(original()))
            for path in (FIX/'shared/sofr/v2', FIX/'compatibility/note-structured-basis'):
                with self.assertRaises(ValueError):p.validate(path,bundle.encoded(original()))


class PricingIntakeTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.inbox=self.root/'inbox';self.inbox.mkdir()
        self.incoming=self.root/'incoming';self.incoming.mkdir();self.state=self.root/'state'
        shutil.copytree(source(),self.inbox/'note')
        self.adapter=p.PricingFileAdapter(self.incoming)

    def deliver(self):
        m,_=bundle.validate(source());path=self.incoming/(m['bundleId']+'.json')
        path.write_bytes((FIX/'pricing-results/note.json').read_bytes());return path

    def test_late_result_restart_dedup_retention_and_status(self):
        with coordinator.Coordinator(self.state,self.adapter) as ctl:
            ctl.discover(self.inbox);job=ctl.run()['jobs'][0]
            self.assertEqual(job['status'],'RUNNING');self.assertEqual(len(job['attempts']),1)
        self.deliver()
        with coordinator.Coordinator(self.state,self.adapter) as ctl:
            job=ctl.run()['jobs'][0]
            self.assertEqual(job['status'],'SYNTHETIC_PRICING_VALIDATED')
            self.assertTrue(job['selectedSyntheticPricingResult']);self.assertFalse(job['selectedW0Result'])
            self.assertFalse(job['selectedMockResult']);self.assertFalse(job['usableForRisk'])
            self.assertEqual(len(job['attempts']),1);self.assertEqual(len(ctl.discover(self.inbox)['duplicates']),1)
            self.assertEqual(ctl.run()['processed'],[])
            snapshot=job_status.snapshot(self.state)['jobs'][0]
            self.assertTrue(snapshot['pricingAvailable']);self.assertFalse(snapshot['portfolioRiskAvailable'])
            self.assertNotIn('result_path',snapshot)
            self.deliver().write_text('{}')
            stored=self.state/job['result_path']/'results.json'
            self.assertEqual(stored.read_bytes(),(FIX/'pricing-results/note.json').read_bytes())
        with coordinator.Coordinator(self.state,self.adapter) as ctl:
            self.assertEqual(ctl.status()['jobs'][0]['resultIntegrity'],'VERIFIED')
            stored.write_text('{}');bad=ctl.status()['jobs'][0]
            self.assertFalse(bad['selectedSyntheticPricingResult'])
            snapshot=job_status.snapshot(self.state)['jobs'][0]
            self.assertFalse(snapshot['pricingAvailable']);self.assertIsNone(snapshot['coverage'])
        for adapter in (None,w0_result.W0FileAdapter(self.incoming)):
            with self.assertRaises(ValueError):
                with coordinator.Coordinator(self.state,adapter):pass

    def test_process_exit_after_publication_recovers_same_attempt(self):
        self.deliver()
        code = """import os,sys
sys.path.insert(0,sys.argv[1])
import coordinator,pricing_result
class Crash(pricing_result.PricingFileAdapter):
 def execute(self,source,destination):
  super().execute(source,destination)
  os._exit(17)
with coordinator.Coordinator(sys.argv[2],Crash(sys.argv[3])) as ctl:
 ctl.discover(sys.argv[4]);ctl.run()
"""
        proc=subprocess.run([sys.executable,'-c',code,str(Path(p.__file__).parent),str(self.state),str(self.incoming),str(self.inbox)])
        self.assertEqual(proc.returncode,17)
        with coordinator.Coordinator(self.state,self.adapter) as ctl:
            job=ctl.run()['jobs'][0]
            self.assertEqual(job['status'],'SYNTHETIC_PRICING_VALIDATED');self.assertEqual(len(job['attempts']),1)

    def test_old_cut_not_selected_when_newer_unsupported_cut_arrives(self):
        self.deliver()
        with coordinator.Coordinator(self.state,self.adapter) as ctl:
            ctl.discover(self.inbox);old=ctl.run()['jobs'][0]
            raw=self.root/'raw';shutil.copytree(source(),raw)
            for kind in ('positions','contracts'):
                path=raw/(kind+'.csv');path.write_text(path.read_text().replace('# consensusSequence=8','# consensusSequence=9'))
            m,_=bundle.validate(source())
            bundle.build(raw/'positions.csv',raw/'contracts.csv',self.inbox/'new',m['clusterEpoch'],m['valuationTime'],'synthetic',raw/'instrument-terms.json')
            ctl.discover(self.inbox);jobs=ctl.run()['jobs']
            old=next(j for j in jobs if j['job_id']==old['job_id'])
            self.assertEqual(old['resultIntegrity'],'VERIFIED');self.assertFalse(old['selectedSyntheticPricingResult'])
            self.assertEqual(sum(j['status']=='FAILED' for j in jobs),1)
            self.assertFalse(any(j['selectedSyntheticPricingResult'] for j in jobs))

    def test_receipt_symlink_and_immutable_publication(self):
        incoming=self.deliver();out=self.root/'out';self.adapter.execute(source(),out)
        receipt=out/'acceptance.json';original_receipt=receipt.read_bytes()
        self.assertIn(b'not authenticated producer identity',original_receipt)
        with self.assertRaises(ValueError):self.adapter.execute(source(),out)
        doc=json.loads(original_receipt);doc['compatibilityProfile']['engineCommit']='future'
        receipt.write_bytes(bundle.encoded(doc))
        with self.assertRaises(ValueError):self.adapter.validate_result(source(),out)
        incoming.unlink();incoming.symlink_to((FIX/'pricing-results/note.json').resolve())
        with self.assertRaises(ValueError):self.adapter.execute(source(),self.root/'other')

if __name__=='__main__':unittest.main()
