import base64
import hashlib
import json
from types import SimpleNamespace

import google_crc32c
import pytest
from google.api_core.exceptions import NotFound, PreconditionFailed
import worker


class Store:
    def __init__(self):
        self.objects = {}; self.downloads = []; self.fail_commit = False; self.race_commit = False
    def bucket(self, name): return Bucket(self, name)


class Bucket:
    def __init__(self, store, name): self.store, self.name = store, name
    def blob(self, name, generation=None): return Blob(self, name, generation)


class Blob:
    def __init__(self, bucket, name, generation):
        self.bucket, self.name, self.generation = bucket, name, generation
        self.metadata = None
    @property
    def key(self): return self.bucket.name, self.name
    def reload(self):
        if self.key not in self.bucket.store.objects: raise NotFound('missing')
        obj = self.bucket.store.objects[self.key]
        if self.generation is not None and self.generation != obj['generation']: raise NotFound('generation')
        self.generation = obj['generation']; self.size = len(obj['data'])
        self.crc32c = base64.b64encode(google_crc32c.Checksum(obj['data']).digest()).decode()
        self.metadata = obj['metadata']
    def download_as_bytes(self):
        self.reload(); self.bucket.store.downloads.append(self.key)
        return self.bucket.store.objects[self.key]['data']
    def download_to_filename(self, path, **kwargs):
        assert kwargs['if_generation_match'] == self.generation
        with open(path, 'wb') as f: f.write(self.download_as_bytes())
    def upload_from_filename(self, path, **kwargs):
        with open(path, 'rb') as f: self.upload_from_string(f.read(), **kwargs)
    def upload_from_string(self, data, **kwargs):
        assert kwargs['if_generation_match'] == 0
        store = self.bucket.store
        if self.key in store.objects: raise PreconditionFailed('exists')
        if '/commits/' in self.name and store.fail_commit: raise RuntimeError('interrupted before commit')
        store.objects[self.key] = {'data': data.encode() if isinstance(data, str) else data,
                                  'metadata': self.metadata, 'generation': len(store.objects) + 1}
        self.reload()
        if '/commits/' in self.name and store.race_commit: raise PreconditionFailed('another attempt won')


@pytest.fixture
def rig(tmp_path, monkeypatch):
    store = Store()
    data = b'SYM_ROOT,SYM_SUFFIX,PRICE\nBRK,A,001.2300\nBRK,B,4.5600\n'
    source = {'bucket': 'raw', 'name': 'x.csv', 'generation': 1, 'size': len(data),
              'crc32c': base64.b64encode(google_crc32c.Checksum(data).digest()).decode(),
              'source_id': 'source1', 'storage_class': 'STANDARD'}
    raw = json.dumps({'format': 'ri30-input-v1', 'objects': [source]}).encode()
    store.objects[('raw', 'x.csv')] = {'data': data, 'metadata': None, 'generation': 1}
    store.objects[('out', 'inputs.json')] = {'data': raw, 'metadata': None, 'generation': 2}
    for k,v in {'GOOGLE_CLOUD_PROJECT':'p','INPUT_MANIFEST_URI':'gs://out/inputs.json',
                'INPUT_MANIFEST_OBJECT_COUNT':'1','INPUT_MANIFEST_GENERATION':'2','INPUT_MANIFEST_SHA256':hashlib.sha256(raw).hexdigest(),
                'WORKER_IMAGE':'example/image@sha256:'+'a'*64,'BATCH_TASK_INDEX':'0','DESTINATION_BUCKET':'out','SCRATCH_DIR':str(tmp_path)}.items():
        monkeypatch.setenv(k,v)
    monkeypatch.setattr(worker.storage, 'Client', lambda **kw: store)
    monkeypatch.setattr(worker.shutil, 'disk_usage', lambda path: SimpleNamespace(free=100*1024**3))
    return store


def test_retry_is_noop_and_references_verified(rig):
    worker.run(); before = dict(rig.objects)
    worker.run()
    assert rig.objects == before
    assert rig.downloads.count(('raw','x.csv')) == 1
    part = next(k for k in rig.objects if k[1].endswith('.parquet'))
    rig.objects[part]['data'] = b'corrupt'
    with pytest.raises(ValueError, match='metadata mismatch'): worker.run()


def test_interruption_does_not_publish_and_retry_can_finish(rig):
    rig.fail_commit = True
    with pytest.raises(RuntimeError): worker.run()
    assert not any('/commits/' in name for _,name in rig.objects)
    assert any(name.endswith('.parquet') for _,name in rig.objects)
    rig.fail_commit = False
    worker.run()
    commits = [json.loads(o['data']) for (_,name),o in rig.objects.items() if '/commits/' in name]
    assert len(commits) == 1 and commits[0]['rows'] == 2
    assert len(commits[0]['parts']) == 1


def test_concurrent_commit_winner_validated(rig):
    rig.race_commit = True
    worker.run()
    assert sum('/commits/' in name for _,name in rig.objects) == 1


def test_wrong_manifest_and_negative_index_fail(rig, monkeypatch):
    monkeypatch.setenv('INPUT_MANIFEST_SHA256','0'*64)
    with pytest.raises(ValueError, match='digest'): worker.run()
    assert rig.downloads.count(('raw','x.csv')) == 0


def test_source_checksum_mismatch_fails_before_conversion(rig):
    rig.objects[('raw','x.csv')]['data'] = b'broken'
    with pytest.raises(ValueError, match='source metadata'): worker.run()
    assert not any('/commits/' in name for _,name in rig.objects)


def test_other_image_uses_separate_run(rig, monkeypatch):
    worker.run()
    monkeypatch.setenv('WORKER_IMAGE', 'example/image@sha256:'+'b'*64)
    worker.run()
    assert sum('/commits/' in name for _,name in rig.objects) == 2


def test_count_and_index_fail_before_source_read(rig, monkeypatch):
    monkeypatch.setenv('INPUT_MANIFEST_OBJECT_COUNT','2')
    with pytest.raises(ValueError, match='object count'): worker.run()
    monkeypatch.setenv('INPUT_MANIFEST_OBJECT_COUNT','1')
    monkeypatch.setenv('BATCH_TASK_INDEX','-1')
    with pytest.raises(ValueError, match='task index'): worker.run()
    assert rig.downloads.count(('raw','x.csv')) == 0
