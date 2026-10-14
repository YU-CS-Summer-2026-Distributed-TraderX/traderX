#!/usr/bin/env python3
"""Exact full-field multiset containment: daily archives versus monthly date slices."""
import hashlib
import json
import os
from pathlib import Path
import tempfile

import duckdb
from google.cloud import storage
from preserve import sha256, VERSION
from worker import validate_commit


def compare_files(con, daily_paths, monthly_paths, header, day):
    if not daily_paths or not monthly_paths or not header or 'DATE' not in header:
        raise ValueError('inconclusive: missing input population/schema')
    columns = ', '.join('"'+name.replace('"','""')+'"' for name in header)
    con.read_parquet(daily_paths).create_view('daily_input', replace=True)
    con.read_parquet(monthly_paths).create_view('monthly_input', replace=True)
    # DATE values remain source text. No symbol, correction, timestamp or decimal normalization.
    escaped = day.replace("'", "''")
    d = f'SELECT {columns} FROM daily_input'
    m = f"SELECT {columns} FROM monthly_input WHERE DATE = '{escaped}'"
    dn = con.execute('SELECT count(*) FROM daily_input').fetchone()[0]
    wrong_date = con.execute('SELECT count(*) FROM daily_input WHERE DATE != ?', [day]).fetchone()[0]
    mn = con.execute(f'SELECT count(*) FROM ({m})').fetchone()[0]
    if not dn or not mn or wrong_date:
        raise ValueError('inconclusive: empty day or unexpected daily dates')
    missing = con.execute(f'SELECT count(*) FROM (({d}) EXCEPT ALL ({m}))').fetchone()[0]
    extra = con.execute(f'SELECT count(*) FROM (({m}) EXCEPT ALL ({d}))').fetchone()[0]
    verdict = 'identical_multiset' if missing == extra == 0 else 'daily_contained' if missing == 0 else 'daily_not_contained'
    return {'day':day, 'daily_rows':dn, 'monthly_day_rows':mn, 'daily_rows_missing_from_monthly':missing,
            'monthly_rows_extra_to_daily':extra, 'verdict':verdict, 'comparison':'all original columns, EXCEPT ALL in both directions'}


def run():
    client = storage.Client(project=os.environ['GOOGLE_CLOUD_PROJECT'])
    bucket = client.bucket(os.environ['DESTINATION_BUCKET'])
    uri = os.environ['INPUT_MANIFEST_URI']; b,n = uri.removeprefix('gs://').split('/',1)
    raw = client.bucket(b).blob(n,generation=int(os.environ['INPUT_MANIFEST_GENERATION'])).download_as_bytes()
    digest=hashlib.sha256(raw).hexdigest()
    if digest != os.environ['INPUT_MANIFEST_SHA256']: raise ValueError('manifest mismatch')
    manifest=json.loads(raw); image=os.environ['WORKER_IMAGE']
    run_id=hashlib.sha256((digest+image+VERSION).encode()).hexdigest()
    root=f'ri30/v2/runs/{run_id}'
    commits={}
    for source in manifest['objects']:
        commit=json.loads(bucket.blob(f"{root}/commits/{source['source_id']}.json").download_as_bytes())
        validate_commit(client,commit,source,digest,image)
        commits[source['name']]=commit
    monthly=[c for n,c in commits.items() if n.endswith('taq_trades_mar2025_csv.zip')]
    if len(monthly)!=1 or len(commits)!=3: raise ValueError('inconclusive: expected exact three-archive selection')
    monthly=monthly[0]
    report={'status':'complete','input_manifest_sha256':digest,'execution_image':image,'results':[]}
    with tempfile.TemporaryDirectory(dir=os.environ.get('SCRATCH_DIR','/scratch')) as tmp:
        con=duckdb.connect()
        con.execute("SET memory_limit='10GB'"); con.execute('SET threads=4')
        con.execute('SET temp_directory=?',[tmp+'/spill'])
        con.execute("SET max_temp_directory_size='120GB'")
        for suffix,day in [('20250303','2025-03-03'),('20250304','2025-03-04')]:
            matches=[c for n,c in commits.items() if n.endswith(f'taq_trades_{suffix}_csv.zip')]
            if len(matches)!=1: raise ValueError('inconclusive: daily input missing')
            daily=matches[0]; headers=[m['header'] for c in [daily,monthly] for m in c['members']]
            if not headers or any(h != headers[0] for h in headers): raise ValueError('inconclusive: schema mismatch')
            paths=[]
            for group,commit in [('daily',daily),('monthly',monthly)]:
                selected=[]
                for i,part in enumerate(commit['parts']):
                    if 'dates' not in part: raise ValueError('inconclusive: missing date inventory')
                    if group=='monthly' and day not in part['dates']: continue
                    path=Path(tmp)/f'{suffix}-{group}-{i}.parquet'
                    blob=client.bucket(part['bucket']).blob(part['object'],generation=part['generation'])
                    blob.download_to_filename(str(path),checksum='crc32c',if_generation_match=part['generation'])
                    if sha256(path)!=part['sha256']: raise ValueError('Parquet checksum mismatch')
                    selected.append(str(path))
                paths.append(selected)
            result=compare_files(con,*paths,headers[0],day)
            result['daily_source_id']=daily['source_id'];result['monthly_source_id']=monthly['source_id']
            report['results'].append(result)
            print(json.dumps(result),flush=True)
            for group in paths:
                for path in group: Path(path).unlink()
        con.close()
    bucket.blob(f'{root}/comparison/march-daily-containment.json').upload_from_string(
        json.dumps(report,indent=2),content_type='application/json',if_generation_match=0)

if __name__=='__main__': run()
