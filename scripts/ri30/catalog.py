#!/usr/bin/env python3
"""Catalog committed sources; browse metadata privately; query explicitly selected rows."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import tempfile
import duckdb
import pyarrow as pa
import pyarrow.parquet as pq
from preserve import sha256
from compact import partition_date


def build_catalog(commits, expected_sources, output):
    output=Path(output); output.mkdir(parents=True,exist_ok=False)
    expected={s['source_id']:s for s in expected_sources}
    if not expected or len(expected)!=len(expected_sources) or len(commits)!=len(expected):
        raise ValueError('empty/duplicate/missing catalog sources')
    seen=set(); files=[]; sources=[]; overlaps=defaultdict(set)
    for c in commits:
        sid=c['source_id']
        if sid in seen or expected.get(sid)!=c['source'] or c['status']!='complete' or c['verification']['status']!='verified':
            raise ValueError('unverified or unexpected catalog source')
        seen.add(sid)
        if not c['files'] or sum(f['rows'] for f in c['files'])!=c['rows']: raise ValueError('catalog count mismatch')
        sources.append({k:c[k] for k in ['source_id','source','archive','kind','rows','members','verification']})
        for f in c['files']:
            if not all(f.get(k) for k in ['bucket','object','generation','crc32c','sha256','bytes','rows']):
                raise ValueError('missing file integrity')
            item={k:v for k,v in f.items() if k!='segments'}
            item.update(source_id=sid, archive=c['archive'],kind=c['kind'],source_generation=c['source']['generation'],
                        uri=f"gs://{f['bucket']}/{f['object']}")
            files.append(item); overlaps[c['kind'],f['date']].add(c['archive'])
    catalog=dict(format='ri30-catalog-index-v1',status='complete',sources=sources,files=files,
        rows=sum(c['rows'] for c in commits),overlaps=[dict(kind=k,date=d,archives=sorted(v))
            for (k,d),v in sorted(overlaps.items()) if len(v)>1],
        query_policy='exactly one explicit archive and date; originals remain strings; no automatic overlap union')
    (output/'catalog.json').write_text(json.dumps(catalog,sort_keys=True))
    # Nested file schema and integrity are retained in JSON; Parquet catalog is directly queryable.
    pq.write_table(pa.Table.from_pylist(files),output/'catalog.parquet',compression='zstd')
    metadata=[dict(archive=s['archive'],kind=s['kind'],rows=s['rows'],generation=s['source']['generation']) for s in sources]
    payload=json.dumps(metadata).replace('<','\\u003c')
    page='''<!doctype html><meta charset="utf-8"><title>Private TAQ catalog</title>
<style>body{font:16px system-ui;margin:3rem;max-width:1100px}input{padding:.6rem;width:70%}td,th{padding:.5rem;border-bottom:1px solid #ddd;text-align:left}</style>
<h1>Private TAQ catalog</h1><p>Metadata search. Actual row queries require an explicit archive, date, symbol root and suffix. Overlapping archives remain separate.</p>
<input id="q" placeholder="Search archive, type or generation"><table><thead><tr><th>Archive</th><th>Type</th><th>Rows</th><th>Source generation</th></tr></thead><tbody id="rows"></tbody></table>
<script>const data=PAYLOAD;const q=document.getElementById('q');function draw(){const body=document.getElementById('rows');body.replaceChildren();for(const s of data.filter(x=>JSON.stringify(x).toLowerCase().includes(q.value.toLowerCase()))){const tr=document.createElement('tr');for(const k of ['archive','kind','rows','generation']){const td=document.createElement('td');td.textContent=s[k];tr.append(td)}body.append(tr)}}q.addEventListener('input',draw);draw()</script>'''.replace('PAYLOAD',payload)
    (output/'browse.html').write_text(page)
    return catalog


def select_files(catalog, archive, day, root, suffix):
    if not archive or day is None or root is None or suffix is None: raise ValueError('explicit archive/date/root/suffix required')
    sources=[s for s in catalog['sources'] if s['archive']==archive]
    if len(sources)!=1: raise ValueError('archive must select exactly one source generation')
    return [f for f in catalog['files'] if f['source_id']==sources[0]['source_id'] and f['date']==partition_date(day)
            and (f['root_min'] is None or f['root_min']<=root<=f['root_max'])]


def query_paths(paths, day, root, suffix, limit=20):
    if not paths or limit<1: raise ValueError('no candidate files or invalid limit')
    con=duckdb.connect(); con.execute("SET memory_limit='2GB'"); con.execute('SET threads=2')
    try:
        # Disable hive virtual columns; return exactly original fields/provenance.
        con.read_parquet([str(p) for p in paths],hive_partitioning=False).create_view('taq')
        return con.execute('SELECT * FROM taq WHERE "DATE"=? AND "SYM_ROOT"=? AND "SYM_SUFFIX"=? LIMIT ?',
                           [day,root,suffix,limit]).to_arrow_table()
    finally: con.close()


def run_query(client,catalog,archive,day,root,suffix,output,limit=20,scratch=None):
    files=select_files(catalog,archive,day,root,suffix)
    if not files: raise ValueError('no candidate files; query unexecuted')
    with tempfile.TemporaryDirectory(dir=scratch,prefix='ri30-query-') as tmp:
        import shutil
        total=sum(f['bytes'] for f in files)
        if shutil.disk_usage(tmp).free<total+1024**3: raise ValueError('insufficient private query scratch')
        paths=[]
        for i,f in enumerate(files):
            path=Path(tmp)/f'{i}.parquet'; blob=client.bucket(f['bucket']).blob(f['object'],generation=f['generation'])
            blob.download_to_filename(str(path),checksum='crc32c',if_generation_match=f['generation'])
            if path.stat().st_size!=f['bytes'] or sha256(path)!=f['sha256']: raise ValueError('query file integrity mismatch')
            paths.append(path)
        table=query_paths(paths,day,root,suffix,limit); pq.write_table(table,output)
        return dict(rows=table.num_rows,candidate_files=len(paths),download_bytes=total,archive=archive,date=day,
                    symbol_root=root,symbol_suffix=suffix,columns=table.column_names)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('catalog');p.add_argument('--archive',required=True);p.add_argument('--date',required=True)
    p.add_argument('--root',required=True);p.add_argument('--suffix',required=True)
    p.add_argument('--output',required=True);p.add_argument('--limit',type=int,default=20)
    a=p.parse_args()
    from catalog_cloud import client_for_cli
    print(json.dumps(run_query(client_for_cli(),json.loads(Path(a.catalog).read_text()),a.archive,a.date,a.root,a.suffix,a.output,a.limit)))
