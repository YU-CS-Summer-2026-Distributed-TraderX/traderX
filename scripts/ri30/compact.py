#!/usr/bin/env python3
"""Lossless source/member/date compaction. Publication requires independent readback."""
from collections import Counter
from datetime import date
import json
from pathlib import Path
import re
from urllib.parse import quote

import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.parquet as pq
from preserve import PROVENANCE, sha256

VERSION = 'ri30-catalog-v1'
TARGET_BYTES = 192 * 1024**2


def partition_date(value):
    # Strict canonical date only; original text is never modified.
    if not re.fullmatch(r'\d{4}-\d{2}-\d{2}', value):
        return 'invalid'
    try:
        return value if date.fromisoformat(value).isoformat() == value else 'invalid'
    except ValueError:
        return 'invalid'


def check_input(table, part, member, source_id):
    schema = pa.schema([(h, pa.string()) for h in member['header']] +
                       [(PROVENANCE[0], pa.string()), (PROVENANCE[1], pa.string()),
                        (PROVENANCE[2], pa.int64())])
    if not table.schema.equals(schema, check_metadata=False) or table.num_rows != part['rows']:
        raise ValueError('input schema/count mismatch')
    if any(c.null_count for c in table.columns):
        raise ValueError('input nulls forbidden')
    for field, expected in zip(PROVENANCE[:2], [source_id, member['name']]):
        if not pc.all(pc.equal(table[field], expected)).as_py():
            raise ValueError('input source/member provenance mismatch')
    expected = pa.array(range(part['first_record'], part['first_record']+part['rows']), type=pa.int64())
    if not table[PROVENANCE[2]].combine_chunks().equals(expected):
        raise ValueError('input record coverage mismatch')


def compact(commit, input_paths, output, target_bytes=TARGET_BYTES, row_group_rows=250000, on_file=None):
    if target_bytes < 1 or row_group_rows < 1 or len(input_paths) != len(commit['parts']):
        raise ValueError('invalid resource bounds/input population')
    output = Path(output); output.mkdir(parents=True, exist_ok=False)
    source = commit['source']; archive = Path(source['name']).stem
    kind = 'quotes' if archive.startswith('taq_quotes_') else 'trades' if archive.startswith('taq_trades_') else None
    if kind is None: raise ValueError('unsupported archive type')
    members = {m['name']:m for m in commit['members']}
    if len(members) != len(commit['members']) or not members: raise ValueError('invalid members')
    active, next_no, files, coverage = {}, Counter(), [], Counter()
    result = dict(format=VERSION, source=source, source_id=source['source_id'], archive=archive,
                  kind=kind, rows=0, files=files, members=commit['members'], status='incomplete',
                  target_bytes=target_bytes, row_group_rows=row_group_rows)

    def finish(key):
        item = active.pop(key); item.pop('writer').close(); item.pop('sink').close()
        path = item.pop('path'); item.update(bytes=path.stat().st_size, sha256=sha256(path))
        if on_file: item.update(on_file(path, item))
        files.append(item)

    try:
        for index, (part, path) in enumerate(zip(commit['parts'], input_paths)):
            table = pq.ParquetFile(path).read()
            member = members.get(part['member'])
            if member is None: raise ValueError('unknown input member')
            check_input(table, part, member, source['source_id'])
            if part['first_record'] != coverage[member['name']]+1: raise ValueError('noncontiguous input coverage')
            coverage[member['name']] += table.num_rows
            if 'DATE' not in table.column_names: raise ValueError('missing DATE column')
            values = pc.unique(table['DATE']).to_pylist()
            # One input part can contain multiple valid dates and multiple invalid texts.
            dates = sorted({partition_date(v) for v in values})
            for day in dates:
                raw_dates = [v for v in values if partition_date(v)==day]
                selected = table.filter(pc.is_in(table['DATE'], value_set=pa.array(raw_dates, type=pa.string())))
                if not selected.num_rows: raise ValueError('empty selected partition')
                key = (member['name'], day)
                if key not in active:
                    ordinal = list(members).index(member['name'])
                    relative = (f'{kind}/date={day}/archive={quote(archive, safe="")}/'
                                f'source_generation={source["generation"]}/member={ordinal:04d}/'
                                f'part-{next_no[key]:06d}.parquet')
                    next_no[key] += 1
                    path_out = output/relative; path_out.parent.mkdir(parents=True, exist_ok=True)
                    sink = pa.OSFile(str(path_out), 'wb')
                    active[key] = dict(path=path_out, sink=sink,
                        writer=pq.ParquetWriter(sink, selected.schema, compression='zstd'),
                        file=relative, member=member['name'], date=day, rows=0, segments=[],
                        schema=[dict(name=f.name,type=str(f.type)) for f in selected.schema],
                        root_min=None, root_max=None)
                item=active[key]
                item['writer'].write_table(selected, row_group_size=row_group_rows)
                item['rows'] += selected.num_rows
                item['segments'].append(dict(input_index=index, rows=selected.num_rows))
                if 'SYM_ROOT' in selected.column_names:
                    lo=pc.min(selected['SYM_ROOT']).as_py(); hi=pc.max(selected['SYM_ROOT']).as_py()
                    item['root_min']=lo if item['root_min'] is None else min(item['root_min'],lo)
                    item['root_max']=hi if item['root_max'] is None else max(item['root_max'],hi)
                result['rows'] += selected.num_rows
                if item['sink'].tell() >= target_bytes: finish(key)
            if index % 100 == 0:
                print(json.dumps(dict(event='compact_progress', input_parts=index+1, rows=result['rows'], files=len(files))),flush=True)
        for key in list(active): finish(key)
        if result['rows'] != commit['rows'] or any(coverage[m['name']] != m['rows'] for m in members.values()):
            raise ValueError('source/member reconciliation mismatch')
        result['status']='written'  # NOT verified or publishable yet.
        (output/'written.json').write_text(json.dumps(result,sort_keys=True))
        return result
    except Exception as exc:
        for item in active.values(): item['writer'].close(); item['sink'].close()
        (output/'failure.json').write_text(json.dumps(dict(status='failed',error_type=type(exc).__name__)))
        raise
