import base64
import hashlib
import json
from types import SimpleNamespace
import google_crc32c
import pytest
from preserve import convert
import catalog_cloud as cloud
from test_worker import Store,Blob

@pytest.fixture
def rig(tmp_path,monkeypatch):
    store=Store();original=Blob.download_as_bytes
    def download(self,**kwargs):
        if 'if_generation_match' in kwargs:
            assert kwargs['if_generation_match']==self.generation
        return original(self)
    monkeypatch.setattr(Blob,'download_as_bytes',download)
    source_path=tmp_path/'taq_quotes_20250303_csv.csv'
    source_path.write_text('DATE,SYM_ROOT,SYM_SUFFIX,PRICE\n2025-03-03,ABC,,001.00\n2025-03-03,ABC,A,002.00\n')
    source=dict(source_id='source',name=source_path.name,generation=1)
    c=convert(source_path,tmp_path/'preserved',source_id='source',chunk_rows=1)
    c.update(source=source,execution_image=cloud.PRESERVATION_IMAGE,input_manifest_sha256=cloud.PRESERVATION_SHA,attempt='preservation')
    def put(name,data,metadata=None):
        key=(cloud.BUCKET,name);store.objects[key]=dict(data=data,metadata=metadata,generation=len(store.objects)+1)
        return dict(bucket=cloud.BUCKET,object=name,generation=store.objects[key]['generation'],sha256=hashlib.sha256(data).hexdigest())
    for p in c['parts']:
        data=(tmp_path/'preserved'/p['file']).read_bytes();ref=put(f'{cloud.ROOT}/attempts/source/preservation/{p["file"]}',data,dict(sha256=p['sha256']))
        p.update({k:ref[k] for k in ['bucket','object','generation']},crc32c=base64.b64encode(google_crc32c.Checksum(data).digest()).decode())
    ref=put(f'{cloud.ROOT}/commits/source.json',json.dumps(c).encode());ref.update(source=source,rows=2,input_bytes=sum(p['bytes'] for p in c['parts']),parts=2)
    manifest=dict(format='ri30-compaction-input-v1',preservation_manifest_sha256=cloud.PRESERVATION_SHA,preservation_image=cloud.PRESERVATION_IMAGE,sources=[ref])
    manifest_ref=put('ri30/inputs/compaction.json',json.dumps(manifest).encode())
    for k,v in dict(GOOGLE_CLOUD_PROJECT='p',COMPACTION_INPUT_REF=json.dumps(manifest_ref),BATCH_TASK_INDEX='0',
                   WORKER_IMAGE='private/image@sha256:'+'a'*64,SCRATCH_DIR=str(tmp_path)).items(): monkeypatch.setenv(k,v)
    monkeypatch.setattr(cloud.storage,'Client',lambda **kw:store)
    monkeypatch.setattr(cloud.shutil,'disk_usage',lambda p:SimpleNamespace(free=100*1024**3))
    return store

def published(store):return [k for k in store.objects if '/published/' in k[1] and '/commits/' in k[1]]

def test_retry_validates_complete_without_duplicate_rows(rig):
    cloud.run();before=dict(rig.objects);cloud.run();assert before==rig.objects
    assert len(published(rig))==1
    key=next(k for k in rig.objects if '/published/' in k[1] and k[1].endswith('part-000000.parquet'))
    rig.objects[key]['data']=b'corrupt'
    with pytest.raises(ValueError,match='integrity'):cloud.run()

def test_interrupted_commit_has_no_publication_then_retry(rig):
    rig.fail_commit=True
    with pytest.raises(RuntimeError):cloud.run()
    assert not published(rig)
    assert any('/published/' in k[1] and k[1].endswith('.parquet') for k in rig.objects)
    rig.fail_commit=False;cloud.run();assert len(published(rig))==1

def test_exact_verification_failure_never_uploads_publication(rig,monkeypatch):
    def fail(*args):raise ValueError('injected changed value')
    monkeypatch.setattr(cloud,'verify',fail)
    with pytest.raises(ValueError,match='changed value'):cloud.run()
    assert not any('/published/' in k[1] for k in rig.objects)

def test_concurrent_create_only_winner_validated(rig):
    rig.race_commit=True;cloud.run();assert len(published(rig))==1

def test_negative_index_no_source_download(rig,monkeypatch):
    monkeypatch.setenv('BATCH_TASK_INDEX','-1')
    with pytest.raises(ValueError,match='index'):cloud.run()
    assert not any('/commits/' in name for _,name in rig.downloads)

def test_wrong_image_uses_separate_publication(rig,monkeypatch):
    cloud.run();monkeypatch.setenv('WORKER_IMAGE','private/image@sha256:'+'b'*64);cloud.run()
    assert len(published(rig))==2


def test_cli_credentials_refresh_without_persisting_tokens(monkeypatch):
    tokens=iter(['first-token\n','renewed-token\n'])
    monkeypatch.setattr(cloud.subprocess,'check_output',lambda *a,**k:next(tokens))
    monkeypatch.setattr(cloud.storage,'Client',lambda **kw:SimpleNamespace(**kw))
    client=cloud.client_for_cli()
    assert client.credentials.token=='first-token'
    client.credentials.expiry=cloud.datetime.now(cloud.timezone.utc).replace(tzinfo=None)-cloud.timedelta(seconds=1)
    headers={}
    client.credentials.before_request(None,'GET','https://storage.googleapis.com',headers)
    assert client.credentials.token=='renewed-token'
    assert headers['authorization']=='Bearer renewed-token'
    assert client.credentials.expiry>cloud.datetime.now(cloud.timezone.utc).replace(tzinfo=None)


def test_cli_refresh_failure_is_not_hidden(monkeypatch):
    def fail(*a,**k):raise cloud.subprocess.CalledProcessError(1,['gcloud'])
    monkeypatch.setattr(cloud.subprocess,'check_output',fail)
    with pytest.raises(cloud.subprocess.CalledProcessError):cloud.client_for_cli()


def test_paged_inventory_checks_only_referenced_exact_current_objects(rig):
    cloud.run()
    key=published(rig)[0];c=json.loads(rig.objects[key]['data'])
    ref=c['preservation_commit'];objects={}
    for f in c['files']:
        b=rig.bucket(f['bucket']).blob(f['object']);b.reload();objects[f['bucket'],f['object']]=b
    objects[cloud.BUCKET,'unreferenced-attempt']=SimpleNamespace(generation=999)
    cloud.validate_publication(rig,c,ref,c['execution_image'],c['input_manifest_sha256'],objects=objects)
    target=(c['files'][0]['bucket'],c['files'][0]['object']);b=objects.pop(target)
    with pytest.raises(ValueError,match='missing'):cloud.validate_publication(rig,c,ref,c['execution_image'],c['input_manifest_sha256'],objects=objects)
    objects[target]=b;b.generation+=1
    with pytest.raises(ValueError,match='generation'):cloud.validate_publication(rig,c,ref,c['execution_image'],c['input_manifest_sha256'],objects=objects)
    b.generation-=1;b.size+=1
    with pytest.raises(ValueError,match='integrity'):cloud.validate_publication(rig,c,ref,c['execution_image'],c['input_manifest_sha256'],objects=objects)
