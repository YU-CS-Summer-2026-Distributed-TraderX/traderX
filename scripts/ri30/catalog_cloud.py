#!/usr/bin/env python3
"""Frozen preservation admission and immutable compacted-source worker."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import resource
import shutil
import subprocess
import tempfile
import time
import uuid
from google.api_core.exceptions import NotFound,PreconditionFailed
from google.cloud import storage
from google.oauth2.credentials import Credentials
from compact import compact,VERSION
from verify_compact import verify
from preserve import sha256,VERSION as PRESERVATION_VERSION

PRESERVATION_IMAGE='us-east1-docker.pkg.dev/project-compsci-484414/taq-processing/taq-parquet@sha256:9ab57bfd37108d732bf98dc1fdf2ac22c4141a7ff442a7bc943381c8691521bd'
PRESERVATION_SHA='c81c8d72bea863076b6b6c8818bad2a551675148044ac83af35dd8df2cc0c39a'
BUCKET='project-compsci-484414-taq-parquet'
ROOT='ri30/v2/runs/b164dcf515cb4efdcfa5c0fc72503fefe8cd05fee35cfa20648d50092f6f1b27'


def client_for_cli():
    def refresh(request, scopes=None):
        token=subprocess.check_output(['gcloud','auth','print-access-token'],text=True).strip()
        if not token:
            raise ValueError('gcloud returned an empty access token')
        # Refresh conservatively before the CLI's one-hour access token expires.
        return token, datetime.now(timezone.utc).replace(tzinfo=None) + timedelta(minutes=40)
    credentials=Credentials(None,refresh_handler=refresh)
    credentials.refresh(None)
    return storage.Client(project='project-compsci-484414',credentials=credentials)


def read_pinned(client,ref):
    blob=client.bucket(ref['bucket']).blob(ref['object'],generation=int(ref['generation']))
    raw=blob.download_as_bytes(if_generation_match=int(ref['generation']),checksum='crc32c')
    if hashlib.sha256(raw).hexdigest()!=ref['sha256']: raise ValueError('pinned JSON integrity mismatch')
    return json.loads(raw)


def admit_commit(c,source):
    if c.get('source')!=source or c.get('execution_image')!=PRESERVATION_IMAGE or c.get('input_manifest_sha256')!=PRESERVATION_SHA:
        raise ValueError('preservation identity mismatch')
    if c.get('format')!=PRESERVATION_VERSION or c.get('status')!='complete' or c.get('malformed_rows')!=0 or c.get('rows',0)<=0 or not c.get('parts'):
        raise ValueError('uncommitted/empty preservation')
    members={m['name']:m for m in c['members']}
    if len(members)!=len(c['members']) or not members: raise ValueError('invalid member population')
    coverage={name:0 for name in members}; objects=set()
    for p in c['parts']:
        identity=(p['bucket'],p['object'],p['generation'])
        if identity in objects or p['bucket']!=BUCKET or not p['object'].startswith(f'{ROOT}/attempts/{source["source_id"]}/{c["attempt"]}/'):
            raise ValueError('duplicate/foreign input object')
        objects.add(identity)
        if p['member'] not in coverage or p['rows']<=0 or p['first_record']!=coverage[p['member']]+1:
            raise ValueError('input coverage gap/duplicate')
        coverage[p['member']]+=p['rows']
    if any(coverage[n]!=m['rows'] for n,m in members.items()) or sum(coverage.values())!=c['rows']:
        raise ValueError('member/source population mismatch')


def freeze(client,full_manifest):
    sources=full_manifest['objects']
    if len(sources)!=132 or len({s['source_id'] for s in sources})!=132: raise ValueError('expected exact 132 frozen sources')
    def one(source):
        blob=client.bucket(BUCKET).blob(f'{ROOT}/commits/{source["source_id"]}.json');blob.reload()
        raw=blob.download_as_bytes(if_generation_match=blob.generation,checksum='crc32c');c=json.loads(raw)
        admit_commit(c,source)
        return dict(source=source,bucket=BUCKET,object=blob.name,generation=int(blob.generation),sha256=hashlib.sha256(raw).hexdigest(),
                    rows=c['rows'],input_bytes=sum(p['bytes'] for p in c['parts']),parts=len(c['parts']))
    with ThreadPoolExecutor(max_workers=8) as pool: refs=list(pool.map(one,sources))
    return dict(format='ri30-compaction-input-v1',preservation_manifest_sha256=PRESERVATION_SHA,
                preservation_image=PRESERVATION_IMAGE,sources=refs,rows=sum(r['rows'] for r in refs))


def validate_publication(client,c,ref,image,input_sha,objects=None):
    if c.get('status')!='complete' or c.get('format')!=VERSION or c.get('source')!=ref['source'] or c.get('preservation_commit')!=ref or c.get('execution_image')!=image or c.get('input_manifest_sha256')!=input_sha:
        raise ValueError('publication identity mismatch')
    if c.get('verification',{}).get('status')!='verified' or c['rows']!=ref['rows'] or not c['files'] or sum(f['rows'] for f in c['files'])!=c['rows']:
        raise ValueError('unverified publication')
    for f in c['files']:
        if objects is None:
            b=client.bucket(f['bucket']).blob(f['object'],generation=f['generation']);b.reload()
        else:
            b=objects.get((f['bucket'],f['object']))
            if b is None or int(b.generation)!=int(f['generation']):
                raise ValueError('publication file missing or current generation mismatch')
        if b.size!=f['bytes'] or b.crc32c!=f['crc32c'] or (b.metadata or {}).get('sha256')!=f['sha256']:
            raise ValueError('publication file integrity mismatch')


def run():
    started=time.monotonic();client=storage.Client(project=os.environ['GOOGLE_CLOUD_PROJECT'])
    manifest_ref=json.loads(os.environ['COMPACTION_INPUT_REF']);manifest=read_pinned(client,manifest_ref)
    if manifest.get('format')!='ri30-compaction-input-v1' or manifest.get('preservation_manifest_sha256')!=PRESERVATION_SHA or manifest.get('preservation_image')!=PRESERVATION_IMAGE or not manifest.get('sources'):
        raise ValueError('compaction manifest identity mismatch')
    index=int(os.environ['BATCH_TASK_INDEX'])
    if index<0 or index>=len(manifest['sources']): raise ValueError('task index outside manifest')
    ref=manifest['sources'][index]
    image=os.environ['WORKER_IMAGE']
    import re
    if not re.fullmatch(r'[^\s]+@sha256:[a-f0-9]{64}',image): raise ValueError('image must be digest pinned')
    pub=hashlib.sha256((manifest_ref['sha256']+image+VERSION).encode()).hexdigest()
    root=f'ri30/published/{pub}';dest=client.bucket(BUCKET);sid=ref['source']['source_id']
    publication=dest.blob(f'{root}/commits/{sid}.json')
    try:
        existing=json.loads(publication.download_as_bytes())
    except NotFound: existing=None
    if existing:
        validate_publication(client,existing,ref,image,manifest_ref['sha256'])
        print(json.dumps(dict(status='already_complete',source_id=sid)),flush=True);return
    c=read_pinned(client,ref);admit_commit(c,ref['source'])
    scratch=os.environ.get('SCRATCH_DIR','/scratch');Path(scratch).mkdir(parents=True,exist_ok=True)
    required=3*ref['input_bytes']+8*1024**3
    if shutil.disk_usage(scratch).free<required: raise ValueError('insufficient cloud scratch for source, output, reserve')
    attempt=uuid.uuid4().hex
    with tempfile.TemporaryDirectory(dir=scratch,prefix='ri30-catalog-') as tmp:
        paths=[]
        for i,p in enumerate(c['parts']):
            path=Path(tmp)/f'input-{i:06d}.parquet'
            b=client.bucket(p['bucket']).blob(p['object'],generation=p['generation']);b.reload()
            if b.size!=p['bytes'] or b.crc32c!=p['crc32c'] or (b.metadata or {}).get('sha256')!=p['sha256']:
                raise ValueError('input object metadata mismatch')
            b.download_to_filename(str(path),if_generation_match=p['generation'],checksum='crc32c')
            if path.stat().st_size!=p['bytes'] or sha256(path)!=p['sha256']: raise ValueError('input bytes mismatch')
            paths.append(path)
            if i%100==0: print(json.dumps(dict(event='download_progress',parts=i+1,total=len(c['parts']),elapsed_seconds=round(time.monotonic()-started,1))),flush=True)
        output=Path(tmp)/'output';report=compact(c,paths,output)
        output_paths=[output/f['file'] for f in report['files']]
        report['verification']=verify(c,paths,report,output_paths)
        print(json.dumps(dict(event='exact_verification_passed',**report['verification'])),flush=True)
        from catalog import query_paths
        import pyarrow.parquet as pq
        demo=next(pq.ParquetFile(output_paths[0]).iter_batches(batch_size=1)).to_pylist()[0]
        query=query_paths(output_paths,demo['DATE'],demo['SYM_ROOT'],demo['SYM_SUFFIX'])
        if not query.num_rows: raise ValueError('actual row query returned empty')
        query_path=Path(tmp)/'query-demo.parquet';pq.write_table(query,query_path)
        query_blob=dest.blob(f'{root}/query-evidence/{sid}/{attempt}.parquet')
        query_blob.upload_from_filename(str(query_path),if_generation_match=0,checksum='crc32c');query_blob.reload()
        report['query_demo']=dict(rows=query.num_rows,archive=report['archive'],date=demo['DATE'],symbol_root=demo['SYM_ROOT'],symbol_suffix=demo['SYM_SUFFIX'],bucket=BUCKET,object=query_blob.name,generation=int(query_blob.generation))
        # All outputs independently read before uploads. New attempt never overwrites a prior attempt.
        for f,path in zip(report['files'],output_paths):
            relative=Path(f['file'])
            blob=dest.blob(f'{root}/{relative.parent}/attempt={attempt}/{relative.name}');blob.metadata={'sha256':f['sha256']}
            blob.upload_from_filename(str(path),if_generation_match=0,checksum='crc32c');blob.reload()
            f.update(bucket=BUCKET,object=blob.name,generation=int(blob.generation),crc32c=blob.crc32c)
        report.update(status='complete',preservation_commit=ref,execution_image=image,input_manifest_sha256=manifest_ref['sha256'],
            attempt=attempt,publication_root=root,elapsed_seconds=round(time.monotonic()-started,2),
            peak_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
            scratch_peak_bytes=sum(p.stat().st_size for p in paths+output_paths),
            output_bytes=sum(f['bytes'] for f in report['files']))
        validate_publication(client,report,ref,image,manifest_ref['sha256'])
        try: publication.upload_from_string(json.dumps(report,sort_keys=True),content_type='application/json',if_generation_match=0)
        except PreconditionFailed: validate_publication(client,json.loads(publication.download_as_bytes()),ref,image,manifest_ref['sha256'])
        print(json.dumps(dict(status='complete',source_id=sid,rows=report['rows'],files=len(report['files']),publication_root=root,
                             elapsed_seconds=report['elapsed_seconds'],peak_rss_kib=report['peak_rss_kib'],scratch_peak_bytes=report['scratch_peak_bytes'])),flush=True)

if __name__=='__main__': run()
