"""Offline handoff protocol tests. No real Batch or Storage requests."""
import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch, Mock
from urllib.error import HTTPError

import batch_bootstrap as b
import batch_handoff as h
from corpus import encoded


def contract():
    return {'version':2,'project_id':h.PROJECT,'project_number':'918355398369','region':h.REGION,
        'run_id':'risk-cpu-test-01','image':'image@sha256:'+'a'*64,'build_sha256':'b'*64,
        'package_sha256':'c'*64,'bootstrap_sha256':'d'*64,'engine_revision':'engine','numerical_revision':'numerical',
        'input':{'bucket':h.BUCKET,'object':'inputs/risk-cpu-test-01/request.json','generation':'8','sha256':'e'*64}}


class HandoffTests(unittest.TestCase):
    def setUp(self):
        self.expected=contract()
        self.identity={'contract':copy.deepcopy(self.expected),'job_name':'projects/'+h.PROJECT+'/locations/'+h.REGION+'/jobs/risk-cpu-test-01','batch_uid':'actual-uid-123'}
        self.meta={'bucket':h.BUCKET,'name':'control/risk-cpu-test-01/job-identity.json','generation':'77','size':'1000'}
        self.config=h.configuration(self.expected,'reviewed source')
        self.actual=copy.deepcopy(self.config)
        self.actual.update(name=self.identity['job_name'],uid=self.identity['batch_uid'],status={'state':'QUEUED'})
        self.actual['taskGroups'][0]['taskSpec'].pop('maxRetryCount')
        self.actual['allocationPolicy']['labels']['batch-job-id']='risk-cpu-test-01'
        self.actual['allocationPolicy']['location']['allowedLocations'] += ['zones/us-east1-b','zones/us-east1-c','zones/us-east1-d']

    def test_actual_job_and_identity_binding(self):
        self.assertEqual(h.verify_job(self.actual,self.config,self.expected),self.identity)
        for key in self.expected:
            bad=copy.deepcopy(self.identity);bad['contract'][key]='wrong'
            with self.subTest(key=key),self.assertRaises(ValueError):b.validate_identity(bad,self.expected)
        for key,value in [('job_name','projects/other/locations/us-east1/jobs/risk-cpu-test-01'),('batch_uid','')]:
            with self.subTest(key=key),self.assertRaises(ValueError):b.validate_identity(dict(self.identity,**{key:value}),self.expected)

    def test_effective_job_changes_refused(self):
        changes=[lambda x:x['taskGroups'][0]['taskSpec']['runnables'][0]['container'].update(entrypoint='sh'),
                 lambda x:x['taskGroups'][0]['taskSpec']['runnables'][0]['container'].update(commands=['-c','changed']),
                 lambda x:x['taskGroups'][0].update(taskCount='2'),
                 lambda x:x['taskGroups'][0]['taskSpec'].update(maxRetryCount=1),
                 lambda x:x['allocationPolicy']['serviceAccount'].update(email='other'),
                 lambda x:x['allocationPolicy']['location'].update(allowedLocations=['regions/us-west1']),
                 lambda x:x['taskGroups'][0]['taskSpec']['environment']['variables'].update(BATCH_JOB_UID='invented')]
        for change in changes:
            bad=copy.deepcopy(self.actual);change(bad)
            with self.assertRaises(ValueError):h.verify_job(bad,self.config,self.expected)

    def test_discovery_race_fetches_exact_generation(self):
        get=Mock(side_effect=[HTTPError('url',404,'missing',{},None),self.meta,self.identity])
        sleep=Mock()
        record=b.load_identity(self.expected,'token',get=get,sleep=sleep)
        self.assertEqual(record['generation'],'77');sleep.assert_called_once()
        self.assertTrue(get.call_args.args[0].endswith('?alt=media&generation=77'))

    def test_permission_malformed_and_mismatch_refused_without_retry(self):
        for failure in (HTTPError('url',403,'denied',{},None),ValueError('malformed')):
            get=Mock(side_effect=failure)
            with self.assertRaises(type(failure)):b.load_identity(self.expected,'token',get=get)
            self.assertEqual(get.call_count,1)
        get=Mock(side_effect=[self.meta,dict(self.identity,batch_uid='')])
        with self.assertRaises(ValueError):b.load_identity(self.expected,'token',get=get)
        self.assertEqual(get.call_count,2)

    def test_missing_identity_has_finite_deadline(self):
        get=Mock(side_effect=HTTPError('url',404,'missing',{},None))
        with self.assertRaisesRegex(ValueError,'timed out'):
            b.load_identity(self.expected,'token',get=get,clock=Mock(side_effect=[0,0,180,181]),sleep=Mock())
        self.assertEqual(get.call_count,1)

    def test_metadata_generation_and_target_mutations(self):
        for key,value in [('generation','0'),('size','20000'),('bucket','other'),('name','other')]:
            with self.subTest(key=key),self.assertRaises(ValueError):
                b.load_identity(self.expected,'token',get=Mock(return_value=dict(self.meta,**{key:value})))

    def test_submit_unknown_outcome_blocks_second_job_create(self):
        with tempfile.TemporaryDirectory() as d:
            work=Path(d);(work/'job.json').write_bytes(encoded(self.config))
            with patch.object(h,'check',return_value=(self.expected,self.config)),patch.object(h,'gcloud',side_effect=TimeoutError) as cloud:
                with self.assertRaises(TimeoutError):h.submit(work)
                with self.assertRaises(FileExistsError):h.submit(work)
                self.assertEqual(cloud.call_count,1)

    def test_sync_failure_never_submits(self):
        with tempfile.TemporaryDirectory() as d:
            work=Path(d);(work/'job.json').write_bytes(encoded(self.config))
            with patch.object(h,'check',return_value=(self.expected,self.config)),patch.object(h.os,'fsync',side_effect=OSError),patch.object(h,'gcloud') as cloud:
                with self.assertRaises(OSError):h.submit(work)
                cloud.assert_not_called()

    def test_changed_actual_uid_rejected_by_final_intake(self):
        with tempfile.TemporaryDirectory() as d:
            work=Path(d);(work/'job-identity.json').write_bytes(encoded(self.identity))
            bad=copy.deepcopy(self.actual);bad['uid']='different-actual-uid';bad['status']['state']='SUCCEEDED'
            with patch.object(h,'check',return_value=(self.expected,self.config)),self.assertRaisesRegex(ValueError,'UID differs'):
                h.verify_result(work,work,{},bad)

    def test_submit_publishes_actual_uid_create_only_then_reads_generation(self):
        with tempfile.TemporaryDirectory() as d:
            work=Path(d);(work/'job.json').write_bytes(encoded(self.config))
            calls=[]
            def cloud(*args):
                calls.append(args)
                self.assertTrue((work/'batch-submit-intent.json').exists())
                if args[:3]==('batch','jobs','submit'):return encoded(self.actual)
                if args[:3]==('storage','objects','describe'):return encoded({'generation':'77'})
                if args[:2]==('storage','cp') and args[2].startswith('gs://'):
                    self.assertTrue(args[2].endswith('#77'))
                    Path(args[3]).write_bytes((work/'job-identity.json').read_bytes())
                return b''
            with patch.object(h,'check',return_value=(self.expected,self.config)),patch.object(h,'gcloud',side_effect=cloud):
                result=h.submit(work)
            self.assertEqual(result['batch_uid'],self.identity['batch_uid'])
            self.assertIn('--if-generation-match=0',calls[1])
            self.assertEqual(len(calls),4)

    def test_unexpected_submitted_config_never_publishes_control(self):
        with tempfile.TemporaryDirectory() as d:
            work=Path(d);(work/'job.json').write_bytes(encoded(self.config))
            bad=copy.deepcopy(self.actual);bad['taskGroups'][0]['parallelism']='2'
            with patch.object(h,'check',return_value=(self.expected,self.config)),patch.object(h,'gcloud',return_value=encoded(bad)) as cloud:
                with self.assertRaises(ValueError):h.submit(work)
                self.assertEqual(cloud.call_count,1)
                self.assertTrue((work/'batch-submit.json').exists())

    def test_missing_or_replayed_checksummed_control_refused(self):
        with tempfile.TemporaryDirectory() as d:
            work=Path(d);(work/'job-identity.json').write_bytes(encoded(self.identity))
            (work/'job-identity-object.json').write_bytes(encoded({'generation':'77'}))
            actual=copy.deepcopy(self.actual);actual['status']['state']='SUCCEEDED'
            record={'identity':self.identity,'object':'control/risk-cpu-test-01/job-identity.json','generation':'77'}
            (work/'operator-job-identity.json').write_bytes(encoded(record))
            completion={'batch_uid':self.identity['batch_uid'],'files':[{'path':'operator-job-identity.json'}]}
            with patch.object(h,'check',return_value=(self.expected,self.config)),patch.object(h,'verify_download',return_value={'fixture':'accepted'}):
                self.assertEqual(h.verify_result(work,work,completion,actual),{'fixture':'accepted'})
                record['generation']='78';(work/'operator-job-identity.json').write_bytes(encoded(record))
                with self.assertRaises(ValueError):h.verify_result(work,work,completion,actual)
                completion['files']=[]
                with self.assertRaises(ValueError):h.verify_result(work,work,completion,actual)
