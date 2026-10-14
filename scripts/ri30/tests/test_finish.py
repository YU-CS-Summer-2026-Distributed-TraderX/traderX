import pytest
from test_worker import Store
from catalog_finish import put_bytes,validate_acceptance
from catalog_cloud import BUCKET


def test_create_only_catalog_retry_checks_exact_bytes():
    store=Store()
    a=put_bytes(store,'ri30/published/test/catalog/catalog.json',b'expected','application/json')
    b=put_bytes(store,'ri30/published/test/catalog/catalog.json',b'expected','application/json')
    assert a==b
    with pytest.raises(ValueError,match='conflicting'):put_bytes(store,a['object'],b'changed','application/json')
    assert store.objects[BUCKET,a['object']]['data']==b'expected'


def test_empty_acceptance_never_admitted():
    with pytest.raises(ValueError,match='two'):validate_acceptance(Store(),[],'image')


def test_parallel_collection_retains_order_and_rejects_failed_source(monkeypatch):
    import catalog_finish as finish
    import hashlib,json
    refs=[{'source':{'source_id':str(i)},'rows':i+1} for i in range(12)]
    manifest={'sources':refs,'rows':sum(r['rows'] for r in refs)}
    store=Store();image='image';sha='input-sha'
    root='ri30/published/'+hashlib.sha256((sha+image+finish.VERSION).encode()).hexdigest()
    for ref in refs:
        commit={'source':ref['source'],'rows':ref['rows'],'query_demo':{'rows':1}}
        store.objects[BUCKET,f'{root}/commits/{ref["source"]["source_id"]}.json']={'data':json.dumps(commit).encode(),'generation':int(ref['source']['source_id'])+1,'metadata':None}
    monkeypatch.setattr(finish,'read_pinned',lambda *a:manifest)
    monkeypatch.setattr(Store,'list_blobs',lambda *a,**kw:[] ,raising=False)
    from test_worker import Blob
    original=Blob.download_as_bytes
    monkeypatch.setattr(Blob,'download_as_bytes',lambda self,**kw:original(self))
    checked=set()
    def check(client,c,ref,*a,**kw):
        assert c['source']==ref['source'];checked.add(c['source']['source_id'])
    monkeypatch.setattr(finish,'validate_publication',check)
    _,commits,commit_refs,_=finish.collect(store,{'sha256':sha},image)
    assert [c['source'] for c in commits]==[r['source'] for r in refs]
    assert checked=={r['source']['source_id'] for r in refs}
    assert len(commit_refs)==12
    def fail(client,c,ref,*a,**kw):
        if c['source']['source_id']=='5':raise ValueError('changed object')
    monkeypatch.setattr(finish,'validate_publication',fail)
    with pytest.raises(ValueError,match='changed object'):finish.collect(store,{'sha256':sha},image)
