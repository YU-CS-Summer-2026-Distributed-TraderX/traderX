"""Offline Cloud Build contract tests; no cloud requests."""
import base64
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import cloud_handoff as c
from corpus import digest, encoded


class ReceiptTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.package = self.root / 'package'
        context = self.package / 'context'
        context.mkdir(parents=True)
        (context / 'build.json').write_bytes(b'{"test":true}\n')
        (self.package / 'request.json').write_bytes(b'{}')
        config = {'steps': [{'name': 'builder@sha256:'+'a'*64, 'args':['build']},
                            {'name': 'builder@sha256:'+'a'*64, 'args':['run']}],
                  'images':[c.IMAGE_ROOT+':risk-cpu-test-01'], 'timeout':'1800s',
                  'serviceAccount':'projects/'+c.PROJECT+'/serviceAccounts/builder',
                  'options':{'logging':'CLOUD_LOGGING_ONLY','machineType':'E2_STANDARD_2','diskSizeGb':'30'}}
        (context / 'cloudbuild.json').write_bytes(encoded(config))
        package = {'project':c.PROJECT,'region':c.REGION,'bucket':c.PROJECT+'-risk-engine-pilot',
                   'run_id':'risk-cpu-test-01','build_sha256':digest(context/'build.json'),
                   'input_sha256':digest(self.package/'request.json'),
                   'context_files':{p.name:digest(p) for p in context.iterdir()}}
        (self.package/'package.json').write_bytes(encoded(package))
        self.work=self.root/'cloud'
        e=c.archive(self.package,self.work)
        self.assertEqual((self.work/'source.tar.gz').read_bytes(),c.frozen_archive(self.package))
        meta={'bucket':c.SOURCE_BUCKET,'name':e['source_object'],'generation':'123',
              'size':(self.work/'source.tar.gz').stat().st_size}
        (self.root/'meta.json').write_bytes(encoded(meta))
        config=c.pin_source(self.work,self.root/'meta.json',self.work/'source.tar.gz')
        self.id='01234567-1234-1234-1234-0123456789ab'
        self.digest='sha256:'+'b'*64
        self.actual=copy.deepcopy(config)
        self.actual.update(id=self.id,projectId=c.PROJECT,status='SUCCESS',
            name='projects/918355398369/locations/'+c.REGION+'/builds/'+self.id,
            results={'images':[{'name':config['images'][0],'digest':self.digest}]},
            artifacts={'images':config['images']})
        self.actual['options'].update(pool={},workerRelease='legacy')
        for step in self.actual['steps']: step.update(status='SUCCESS',timing={})
        pin=config['source']['storageSource']
        key='gs://'+pin['bucket']+'/'+pin['object']+'#123'
        self.actual['sourceProvenance']={'resolvedStorageSource':pin,'fileHashes':{key:{'fileHash':[
           {'type':'SHA256','value':base64.urlsafe_b64encode(bytes.fromhex(e['archive_sha256'])).decode()}]}}}

    def tearDown(self):self.temp.cleanup()

    def test_valid_output_and_documented_defaults(self):
        self.assertEqual(c.admit_build(self.work,self.actual,self.id,self.digest)['status'],'BUILD_RECEIPT_VERIFIED')

    def test_security_relevant_mutations_refused(self):
        changes=[lambda x:x['source']['storageSource'].update(generation='124'),
          lambda x:x['sourceProvenance']['resolvedStorageSource'].update(generation='124'),
          lambda x:x['sourceProvenance'].update(fileHashes={}),
          lambda x:x['steps'][0].update(name='other-builder'),
          lambda x:x['steps'][1].update(entrypoint='sh'),
          lambda x:x['steps'][1].update(env=['OVERRIDE=yes']),
          lambda x:x['steps'][1].update(args=['skip-test']),
          lambda x:x.update(serviceAccount='other'), lambda x:x.update(substitutions={'_BAD':'yes'}),
          lambda x:x['options'].update(env=['BAD=yes']),lambda x:x.update(status='FAILURE'),
          lambda x:x['steps'][1].update(status='FAILURE'),lambda x:x.update(id='other'),
          lambda x:x['results']['images'][0].update(digest='sha256:'+'c'*64),
          lambda x:x['results']['images'][0].update(name='other-image'),
          lambda x:x.update(name='projects/other/locations/us-east1/builds/'+self.id),
          lambda x:x.update(availableSecrets={'secretManager':[]})]
        for i,change in enumerate(changes):
            bad=copy.deepcopy(self.actual);change(bad)
            with self.subTest(case=i),self.assertRaises(ValueError):
                c.admit_build(self.work,bad,self.id,self.digest)

    def test_package_and_request_cannot_select_new_expectations(self):
        config=c.read(self.work/'build-request.json');config['steps'][0]['name']='other'
        (self.work/'build-request.json').write_bytes(encoded(config))
        with self.assertRaises(ValueError):c.check_request(self.work)

    def test_submission_fence_refuses_second_post(self):
        class Response:
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def read(self,*args):return encoded({'name':'operation','metadata':{'build':{'id':self_id}}})
        self_id=self.id
        with patch.object(c.subprocess,'check_output',return_value='token'),patch.object(c.urllib.request,'urlopen',return_value=Response()) as post:
            c.submit_build(self.work)
            with self.assertRaises(FileExistsError):c.submit_build(self.work)
            self.assertEqual(post.call_count,1)

    def test_unknown_post_outcome_retains_fence(self):
        with patch.object(c.subprocess,'check_output',return_value='token'),patch.object(c.urllib.request,'urlopen',side_effect=TimeoutError) as post:
            with self.assertRaises(TimeoutError):c.submit_build(self.work)
            with self.assertRaises(FileExistsError):c.submit_build(self.work)
            self.assertEqual(post.call_count,1)


    def test_sync_before_post_and_required_project_parameter(self):
        events=[]
        class Response:
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def read(self,*args):return encoded({'name':'operation','metadata':{'build':{'id':build_id}}})
        build_id=self.id
        def post(request, **kwargs):
            self.assertEqual(events,['sync','sync'])
            self.assertEqual(request.full_url,'https://cloudbuild.googleapis.com/v1/projects/'+c.PROJECT+
                             '/locations/'+c.REGION+'/builds?projectId='+c.PROJECT)
            events.append('post')
            return Response()
        with patch.object(c.subprocess,'check_output',return_value='token'), \
             patch.object(c.os,'fsync',side_effect=lambda fd:events.append('sync')), \
             patch.object(c.urllib.request,'urlopen',side_effect=post):
            c.submit_build(self.work)
        self.assertEqual(events,['sync','sync','post'])

    def test_failed_file_or_directory_sync_never_posts(self):
        for failures in ([OSError('file sync')], [None,OSError('directory sync')]):
            with self.subTest(failures=failures):
                fence=self.work/'build-submit-intent.json'
                if fence.exists():fence.unlink()
                with patch.object(c.subprocess,'check_output',return_value='token'), \
                     patch.object(c.os,'fsync',side_effect=failures), \
                     patch.object(c.urllib.request,'urlopen') as post:
                    with self.assertRaises(OSError):c.submit_build(self.work)
                    post.assert_not_called()
