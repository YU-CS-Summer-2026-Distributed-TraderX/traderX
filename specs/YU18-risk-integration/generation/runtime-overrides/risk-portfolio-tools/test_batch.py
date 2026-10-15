"""Cloud pilot identity/custody controls; fake HTTP/storage are explicitly fixtures."""
import copy
import hashlib
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from urllib.error import HTTPError
from contextlib import closing

import batch_artifacts as a
import batch_prepare as p
from batch_cpu import run_local
from corpus import digest, encoded
from profiles import CURRENT_ENGINE, CONTRACT
import test_current as current_tests
current_result, FIXTURE = current_tests.current_result, current_tests.FIXTURE


class StorageTests(unittest.TestCase):
    def test_create_only_upload_checks_receipt_and_never_retries(self):
        storage = object.__new__(a.GCS)
        storage.bucket = 'private-bucket'
        storage.request = Mock(return_value=encoded({'name': 'test', 'size': '3', 'generation': '7'}))
        item = storage.put('test', b'abc')
        self.assertIn('ifGenerationMatch=0', storage.request.call_args.args[0])
        self.assertEqual(item['sha256'], hashlib.sha256(b'abc').hexdigest())
        storage.request = Mock(side_effect=HTTPError('url', 412, 'exists', {}, None))
        with self.assertRaises(HTTPError):
            storage.put('test', b'abc')
        self.assertEqual(storage.request.call_count, 1)

    def test_input_requires_positive_generation_and_exact_golden(self):
        storage = object.__new__(a.GCS)
        storage.bucket = 'private-bucket'
        storage.request = Mock(return_value=b'abc')
        with self.assertRaises(ValueError):
            storage.get('test', '0')
        storage.get('test', '7')
        self.assertTrue(storage.request.call_args.args[0].endswith('generation=7'))
        with tempfile.TemporaryDirectory() as tmp, patch('batch_cpu.subprocess.Popen') as process:
            with self.assertRaises(ValueError):
                run_local(b'changed', Path(tmp) / 'out', {'request_sha256': digest(FIXTURE)}, {})
            process.assert_not_called()

    def test_run_identity_refuses_paths_and_other_names(self):
        self.assertEqual(a.safe_run('risk-cpu-test-01'), 'risk-cpu-test-01')
        for value in ('', 'x', '../risk-cpu-test-01', 'risk-cpu-TEST-01', 'risk-cpu-test/01'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                a.safe_run(value)


class BatchPayloadTests(unittest.TestCase):
    setUp = current_tests.CurrentClientTests.setUp
    tearDown = current_tests.CurrentClientTests.tearDown
    replies_for_success = current_tests.CurrentClientTests.replies_for_success
    run_current = current_tests.CurrentClientTests.run_current

    def payload(self):
        driver, state = self.run_current('payload/job')
        self.assertEqual(state['outcome'], 'DONE')
        root = self.root / 'payload'
        with closing(sqlite3.connect(root / 'jobs.sqlite')) as con:
            con.execute('CREATE TABLE jobs(id,kind,status,execution_contract,request,result)')
            con.execute('INSERT INTO jobs VALUES (?,?,?,?,?,?)', ('fixture-job', 'portfolio', 'done', CONTRACT,
                        FIXTURE.read_bytes(), json.dumps(current_result())))
            con.commit()
        build = encoded({'engine_revision': 'engine', 'numerical_revision': CURRENT_ENGINE}) + b'\n'
        (root / 'build.json').write_bytes(build)
        (root / 'runtime.json').write_bytes(encoded({'batch_uid': 'batch-uid', 'image': 'image'}))
        expected = {'run_id': 'risk-cpu-fixture-01', 'image': 'image', 'build_sha256': digest(root / 'build.json'),
                    'engine_revision': 'engine', 'numerical_revision': CURRENT_ENGINE,
                    'input': {'bucket': 'bucket', 'object': 'request', 'generation': '8', 'sha256': digest(FIXTURE)}}
        prefix = 'runs/risk-cpu-fixture-01/attempts/' + 'a' * 32 + '/'
        records = [{'path': path.relative_to(root).as_posix(), 'object': prefix + path.relative_to(root).as_posix(),
                    'generation': '9', 'size': path.stat().st_size, 'sha256': digest(path)}
                   for path in root.rglob('*') if path.is_file()]
        receipt = dict(expected, version=1, status='VERIFIED', financial_validation=False,
                       batch_uid='batch-uid', attempt_prefix=prefix, files=records,
                       verification=a.verify_payload(root))
        return root, expected, receipt

    def test_valid_nonempty_fixture_and_receipt_mutations(self):
        root, expected, receipt = self.payload()
        self.assertEqual(a.verify_download(root, receipt, expected)['trade_count'], 4)
        changes = [lambda r: r.update(files=[]), lambda r: r.update(image='other'),
                   lambda r: r['input'].update(generation='10'),
                   lambda r: r.update(status='SUCCEEDED'), lambda r: r.update(batch_uid=''),
                   lambda r: r['files'][0].update(path='../escape'),
                   lambda r: r['files'].append(r['files'][0]),
                   lambda r: r['files'][0].update(sha256='0' * 64),
                   lambda r: r['files'][0].update(generation='0')]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                bad = copy.deepcopy(receipt)
                change(bad)
                a.verify_download(root, bad, expected)

    def test_actual_queue_missing_or_extra_or_changed_work_is_refused(self):
        root, expected, receipt = self.payload()
        original = (root / 'jobs.sqlite').read_bytes()
        for sql in ('DELETE FROM jobs', "UPDATE jobs SET status='pending'", "UPDATE jobs SET request='{}'",
                    "INSERT INTO jobs SELECT * FROM jobs"):
            with self.subTest(sql=sql):
                with closing(sqlite3.connect(root / 'jobs.sqlite')) as con:
                    con.execute(sql)
                    con.commit()
                with self.assertRaises((ValueError, KeyError)):
                    a.verify_payload(root)
                (root / 'jobs.sqlite').write_bytes(original)


class JobTests(unittest.TestCase):
    def test_job_has_one_task_pinned_input_and_image_and_finite_limits(self):
        package = {'run_id': 'risk-cpu-test-01', 'input_sha256': digest(FIXTURE), 'build_sha256': 'b' * 64,
                   'engine_revision': 'engine', 'numerical_revision': CURRENT_ENGINE}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'request.json').write_bytes(FIXTURE.read_bytes())
            meta = {'bucket': p.BUCKET, 'name': 'inputs/risk-cpu-test-01/request.json', 'generation': '3',
                    'size': FIXTURE.stat().st_size}
            (root / 'meta.json').write_bytes(encoded(meta))
            with patch.object(p, 'verify_package', return_value=package):
                with self.assertRaises(ValueError):
                    p.job(root, p.IMAGE_ROOT + ':latest', root / 'meta.json')
                p.job(root, p.IMAGE_ROOT + '@sha256:' + 'a' * 64, root / 'meta.json')
            config = json.loads((root / 'job.json').read_bytes())
            group = config['taskGroups'][0]
            self.assertEqual((len(config['taskGroups']), group['taskCount'], group['parallelism']), (1, 1, 1))
            self.assertEqual(group['taskSpec']['maxRetryCount'], 0)
            self.assertEqual(group['taskSpec']['maxRunDuration'], '1200s')
            self.assertEqual(group['taskSpec']['environment']['variables']['PILOT_INPUT_GENERATION'], '3')
            self.assertEqual(config['allocationPolicy']['instances'][0]['policy']['machineType'], 'e2-standard-2')

    def test_wrong_input_object_metadata_is_refused(self):
        package = {'run_id': 'risk-cpu-test-01'}
        with tempfile.TemporaryDirectory() as tmp, patch.object(p, 'verify_package', return_value=package):
            root = Path(tmp)
            (root / 'request.json').write_bytes(FIXTURE.read_bytes())
            (root / 'meta.json').write_bytes(encoded({'bucket': 'other', 'name': 'request',
                                                    'generation': '3', 'size': FIXTURE.stat().st_size}))
            with self.assertRaises(ValueError):
                p.job(root, p.IMAGE_ROOT + '@sha256:' + 'a' * 64, root / 'meta.json')


if __name__ == '__main__':
    unittest.main()
