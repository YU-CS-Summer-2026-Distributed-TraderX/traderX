#!/usr/bin/env python3
"""Independent exact comparison: each original part decoded once, bounded output streams.

Original schema, values, provenance and DATE populations decide equality. Producer
hashes never decide equality; each source/date segment must be consumed exactly once.
"""
from collections import defaultdict
from datetime import date
import re
import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.parquet as pq
from preserve import PROVENANCE


def valid_date(value):
    if re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}', value):
        try:
            if date.fromisoformat(value).isoformat()==value:return value
        except ValueError:pass
    return 'invalid'


class OutputStream:
    """At most one 64K-row batch retained per original member/date partition."""
    def __init__(self, files):
        self.files=iter(sorted(files,key=lambda item:item[0]['file']))
        self.info=None;self.batches=None;self.pending=None;self.offset=0
        self.segments=None;self.compared=0;self.files_read=0
    def start_file(self):
        item=next(self.files,None)
        if item is None:raise ValueError('missing output population')
        self.info,path=item;reader=pq.ParquetFile(path)
        if reader.metadata.num_rows!=self.info['rows'] or not self.info['rows']:raise ValueError('output count mismatch')
        self.batches=iter(reader.iter_batches(batch_size=65536))
        self.segments=iter(self.info['segments']);self.compared=0;self.files_read+=1
    def compare(self,index,wanted):
        if self.info is None:self.start_file()
        segment=next(self.segments,None)
        if not segment or segment['input_index']!=index or segment['rows']!=wanted.num_rows:
            raise ValueError('missing/duplicate/wrong source-date lineage')
        pos=0
        while pos<wanted.num_rows:
            if self.pending is None:
                try:self.pending=pa.Table.from_batches([next(self.batches)]);self.offset=0
                except StopIteration:raise ValueError('missing output rows')
            take=min(wanted.num_rows-pos,self.pending.num_rows-self.offset)
            if not wanted.slice(pos,take).equals(self.pending.slice(self.offset,take),check_metadata=False):
                raise ValueError('exact schema/value/provenance mismatch')
            pos+=take;self.offset+=take;self.compared+=take
            if self.offset==self.pending.num_rows:self.pending=None
        if self.compared>self.info['rows']:raise ValueError('output count overflow')
        if self.compared==self.info['rows']:
            if self.pending is not None or next(self.batches,None) is not None or next(self.segments,None) is not None:
                raise ValueError('extra/duplicate output rows or lineage')
            self.info=None;self.batches=None;self.segments=None
    def finish(self):
        if self.info is not None or next(self.files,None) is not None:raise ValueError('unconsumed extra output population')


def verify(commit,input_paths,report,output_paths):
    if not input_paths or not output_paths or len(report['files'])!=len(output_paths) or len(input_paths)!=len(commit['parts']):
        raise ValueError('empty/missing population')
    if report['source']!=commit['source'] or report['rows']!=commit['rows']:raise ValueError('source identity/count mismatch')
    members={m['name']:m for m in commit['members']}
    if not members or len(members)!=len(commit['members']):raise ValueError('invalid members')
    grouped=defaultdict(list)
    for info,path in zip(report['files'],output_paths):
        if info['member'] not in members:raise ValueError('unknown output member')
        grouped[info['member'],info['date']].append((info,path))
    streams={key:OutputStream(files) for key,files in grouped.items()}
    coverage={name:0 for name in members};total=0;partitions=set()
    for index,(path,part) in enumerate(zip(input_paths,commit['parts'])):
        original=pq.ParquetFile(path).read();member=members[part['member']]
        if original.num_rows!=part['rows'] or not original.num_rows or part['first_record']!=coverage[part['member']]+1:
            raise ValueError('input count/contiguous record mismatch')
        if original.column_names!=member['header']+PROVENANCE or any(c.null_count for c in original.columns):
            raise ValueError('input schema/null mismatch')
        for field in original.schema:
            if field.type!=(pa.int64() if field.name==PROVENANCE[2] else pa.string()):raise ValueError('input type mismatch')
        for field,value in zip(PROVENANCE[:2],[commit['source']['source_id'],part['member']]):
            if not pc.all(pc.equal(original[field],value)).as_py():raise ValueError('input identity mismatch')
        if not original[PROVENANCE[2]].combine_chunks().equals(pa.array(range(part['first_record'],part['first_record']+part['rows']),type=pa.int64())):
            raise ValueError('input record mismatch')
        coverage[part['member']]+=original.num_rows
        raw_dates=defaultdict(list)
        for value in pc.unique(original['DATE']).to_pylist():raw_dates[valid_date(value)].append(value)
        counted=0
        for day,values in raw_dates.items():
            wanted=original.filter(pc.is_in(original['DATE'],value_set=pa.array(values,type=pa.string())))
            key=part['member'],day
            if key not in streams or not wanted.num_rows:raise ValueError('missing date partition')
            streams[key].compare(index,wanted);counted+=wanted.num_rows;partitions.add(day)
        if counted!=original.num_rows:raise ValueError('date population coverage mismatch')
        total+=counted
    for stream in streams.values():stream.finish()
    if total!=commit['rows'] or any(coverage[name]!=m['rows'] for name,m in members.items()):
        raise ValueError('member/source population mismatch')
    return dict(status='verified',rows=total,input_parts=len(input_paths),output_files=len(output_paths),
                partitions=len(partitions),input_decodes=len(input_paths),
                comparison='independent exact all-column Arrow equality, contiguous provenance and source/date stream coverage')
