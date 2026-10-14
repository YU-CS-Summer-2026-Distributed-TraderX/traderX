#!/usr/bin/env python3
"""Audit exact source publications and publish private catalog/completion create-only, last."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import tempfile
from catalog_cloud import read_pinned,validate_publication,client_for_cli,BUCKET
from google.api_core.exceptions import PreconditionFailed
from catalog import build_catalog
from compact import VERSION
from preserve import sha256


def collect(client,manifest_ref,image):
    manifest=read_pinned(client,manifest_ref)
    refs=manifest['sources']
    if not refs or len({r['source']['source_id'] for r in refs})!=len(refs): raise ValueError('invalid expected source set')
    pub=hashlib.sha256((manifest_ref['sha256']+image+VERSION).encode()).hexdigest();root=f'ri30/published/{pub}'
    # Listing supplies current integrity metadata in pages rather than one GET
    # per file. Only exact manifest-referenced files decide acceptance; extra
    # attempts in this inventory are never included in the catalog.
    objects={(BUCKET,b.name):b for b in client.list_blobs(BUCKET,prefix=root+'/',
        fields='items(name,generation,size,crc32c,metadata),nextPageToken',page_size=1000)}
    def one(ref):
        b=client.bucket(BUCKET).blob(f'{root}/commits/{ref["source"]["source_id"]}.json');b.reload()
        raw=b.download_as_bytes(if_generation_match=b.generation,checksum='crc32c');c=json.loads(raw)
        validate_publication(client,c,ref,image,manifest_ref['sha256'],objects=objects)
        if not c.get('query_demo',{}).get('rows',0): raise ValueError('missing actual query proof')
        return c,dict(bucket=BUCKET,object=b.name,generation=int(b.generation),sha256=hashlib.sha256(raw).hexdigest())
    # Independent metadata reads; map retains frozen manifest order. Any failure
    # propagates before the caller can publish catalog objects or COMPLETE.
    with ThreadPoolExecutor(max_workers=8) as pool:
        results=list(pool.map(one,refs))
    commits=[c for c,_ in results];commit_refs=[r for _,r in results]
    if sum(c['rows'] for c in commits)!=manifest['rows']: raise ValueError('total row reconciliation mismatch')
    return root,commits,commit_refs,manifest


def put_bytes(client,name,data,content_type):
    b=client.bucket(BUCKET).blob(name)
    b.metadata={'sha256':hashlib.sha256(data).hexdigest()}
    try: b.upload_from_string(data,content_type=content_type,if_generation_match=0)
    except PreconditionFailed:
        existing=b.download_as_bytes()
        if existing!=data: raise ValueError('conflicting immutable catalog/publication record')
    b.reload()
    return dict(bucket=BUCKET,object=name,generation=int(b.generation),sha256=hashlib.sha256(data).hexdigest(),bytes=len(data),crc32c=b.crc32c)


def validate_acceptance(client,refs,image):
    if len(refs)!=2: raise ValueError('exact two independent pilot receipts required')
    receipts=[read_pinned(client,ref) for ref in refs]
    first=receipts[0].get('input_manifest')
    if not first or any(r.get('input_manifest')!=first for r in receipts): raise ValueError('pilot receipt manifest mismatch')
    pilot=read_pinned(client,first)
    expected={r['source']['source_id']:r for r in pilot['sources']}
    if len(expected)!=2: raise ValueError('pilot source set mismatch')
    seen=set()
    for r in receipts:
        sid=r.get('source',{}).get('source_id')
        if sid in seen or sid not in expected or r.get('source')!=expected[sid]['source']:
            raise ValueError('pilot receipt source identity mismatch')
        seen.add(sid)
        if r.get('status')!='accepted' or r.get('execution_image')!=image or r.get('verification',{}).get('status')!='verified' or r['verification']['rows']!=expected[sid]['rows'] or r.get('query_rows',0)<=0:
            raise ValueError('invalid independent acceptance receipt')
        committed=read_pinned(client,r['publication_commit'])
        validate_publication(client,committed,expected[sid],image,first['sha256'])
    return receipts


def finish(client,manifest_ref,image,evidence,phase,acceptance_refs=None):
    root,commits,commit_refs,manifest=collect(client,manifest_ref,image)
    if phase=='full' and len(commits)!=132: raise ValueError('full corpus requires exact 132 sources')
    if phase not in ('pilot','full'): raise ValueError('unknown publication phase')
    if not acceptance_refs: raise ValueError('independent pilot acceptance receipts required before final publication')
    validate_acceptance(client,acceptance_refs,image)
    with tempfile.TemporaryDirectory(prefix='ri30-catalog-metadata-') as tmp:
        output=Path(tmp)/'catalog';catalog=build_catalog(commits,[r['source'] for r in manifest['sources']],output)
        records=[]
        for name in ['catalog.json','catalog.parquet','browse.html']:
            path=output/name
            content_type='application/json' if name.endswith('.json') else 'text/html' if name.endswith('.html') else 'application/octet-stream'
            records.append(put_bytes(client,f'{root}/catalog/{name}',path.read_bytes(),content_type))
        report=dict(status='complete',format='ri30-publication-v1',phase=phase,publication_root=root,
            sources=len(commits),rows=catalog['rows'],files=len(catalog['files']),input_manifest=manifest_ref,
            execution_image=image,source_commits=commit_refs,catalog_objects=records,
            independent_pilot_acceptance=acceptance_refs,overlaps=catalog['overlaps'],
            output_bytes=sum(f['bytes'] for f in catalog['files']),
            verification='generation-pinned source commits, worker independent exact all-field/record/date coverage, current output object integrity, independent real pilot acceptance')
        put_bytes(client,f'{root}/COMPLETE.json',json.dumps(report,sort_keys=True).encode(),'application/json')
        evidence=Path(evidence);evidence.mkdir(parents=True,exist_ok=True)
        (evidence/f'{phase}-completion.json').write_text(json.dumps(report,indent=2))
        # Metadata artifacts contain no TAQ row samples but remain in private evidence.
        for name in ['catalog.json','catalog.parquet','browse.html']: (evidence/f'{phase}-{name}').write_bytes((output/name).read_bytes())
        return report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--input-ref',required=True);p.add_argument('--image',required=True)
    p.add_argument('--evidence',required=True);p.add_argument('--phase',choices=['pilot','full'],required=True)
    p.add_argument('--pilot-acceptance-refs',required=True);a=p.parse_args()
    print(json.dumps(finish(client_for_cli(),json.loads(Path(a.input_ref).read_text()),a.image,a.evidence,a.phase,json.loads(Path(a.pilot_acceptance_refs).read_text()))))
