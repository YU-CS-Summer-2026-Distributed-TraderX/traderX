#!/usr/bin/env python3
"""Separate native Batch auditor rereads all original and compacted pilot rows."""
import hashlib
import json
import os
from pathlib import Path
import resource
import shutil
import tempfile
import time
from google.cloud import storage
from catalog_cloud import read_pinned,admit_commit,validate_publication,BUCKET
from compact import VERSION
from verify_compact import verify
from preserve import sha256
from catalog import query_paths
import pyarrow.parquet as pq


def run():
    started=time.monotonic();client=storage.Client(project=os.environ['GOOGLE_CLOUD_PROJECT'])
    ref=json.loads(os.environ['COMPACTION_INPUT_REF']);manifest=read_pinned(client,ref)
    if len(manifest['sources'])!=2: raise ValueError('independent pilot auditor requires two frozen sources')
    index=int(os.environ['BATCH_TASK_INDEX'])
    if index not in (0,1): raise ValueError('invalid pilot index')
    source_ref=manifest['sources'][index];image=os.environ['WORKER_IMAGE']
    root='ri30/published/'+hashlib.sha256((ref['sha256']+image+VERSION).encode()).hexdigest()
    original=read_pinned(client,source_ref);admit_commit(original,source_ref['source'])
    b=client.bucket(BUCKET).blob(f'{root}/commits/{source_ref["source"]["source_id"]}.json');b.reload()
    raw=b.download_as_bytes(if_generation_match=b.generation,checksum='crc32c');published=json.loads(raw)
    validate_publication(client,published,source_ref,image,ref['sha256'])
    scratch=os.environ.get('SCRATCH_DIR','/scratch')
    if shutil.disk_usage(scratch).free<source_ref['input_bytes']+published['output_bytes']+8*1024**3: raise ValueError('insufficient auditor scratch')
    with tempfile.TemporaryDirectory(dir=scratch,prefix='ri30-independent-') as tmp:
        groups=[]
        for kind,parts in [('input',original['parts']),('output',published['files'])]:
            paths=[]
            for i,p in enumerate(parts):
                path=Path(tmp)/f'{kind}-{i}.parquet';blob=client.bucket(p['bucket']).blob(p['object'],generation=p['generation'])
                blob.download_to_filename(str(path),if_generation_match=p['generation'],checksum='crc32c')
                if path.stat().st_size!=p['bytes'] or sha256(path)!=p['sha256']: raise ValueError('independent byte integrity mismatch')
                paths.append(path)
                if i%100==0: print(json.dumps(dict(event='independent_download',kind=kind,parts=i+1,total=len(parts))),flush=True)
            groups.append(paths)
        verification=verify(original,groups[0],published,groups[1])
        demo=next(pq.ParquetFile(groups[1][0]).iter_batches(batch_size=1)).to_pylist()[0]
        query=query_paths(groups[1],demo['DATE'],demo['SYM_ROOT'],demo['SYM_SUFFIX'])
        if not query.num_rows: raise ValueError('independent actual-row query empty')
        sizes=[f['bytes'] for f in published['files']]
        report=dict(status='accepted',source=source_ref['source'],execution_image=image,input_manifest=ref,
            publication_commit=dict(bucket=BUCKET,object=b.name,generation=int(b.generation),sha256=hashlib.sha256(raw).hexdigest()),
            verification=verification,query_rows=query.num_rows,file_count=len(sizes),output_bytes=sum(sizes),
            compressed_size_min=min(sizes),compressed_size_max=max(sizes),
            compressed_size_median=sorted(sizes)[len(sizes)//2],files_under_128mib=sum(s<128*1024**2 for s in sizes),
            files_over_256mib=sum(s>256*1024**2 for s in sizes),peak_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
            scratch_peak_bytes=sum(p.stat().st_size for paths in groups for p in paths),elapsed_seconds=time.monotonic()-started)
        name=f'{root}/acceptance/{source_ref["source"]["source_id"]}.json'
        client.bucket(BUCKET).blob(name).upload_from_string(json.dumps(report,sort_keys=True),content_type='application/json',if_generation_match=0)
        print(json.dumps(report),flush=True)
if __name__=='__main__':run()
