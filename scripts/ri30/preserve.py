#!/usr/bin/env python3
"""Strict CSV -> DuckDB -> Parquet preservation. No source field normalization."""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import struct
import zipfile

import duckdb
import pyarrow as pa
import pyarrow.parquet as pq
import pyarrow.csv as pacsv
import pyarrow.compute as pc

VERSION = 'ri30-v2'
PROVENANCE = ['__ri30_source_id', '__ri30_member', '__ri30_record']


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(8 * 1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def record_digest(rows):
    h = hashlib.sha256()
    for row in rows:
        h.update(struct.pack('>Q', len(row)))
        for value in row:
            raw = value.encode('utf-8')
            h.update(struct.pack('>Q', len(raw)))
            h.update(raw)
    return h.hexdigest()


def table_digest(table):
    """Canonical non-null string arrays; native IPC avoids Python per-field hashing."""
    columns = []
    for column in table.columns:
        arr = pa.concat_arrays(column.chunks)
        if arr.null_count:
            raise ValueError('source nulls are forbidden')
        arr = pa.Array.from_buffers(pa.string(), len(arr), [None, *arr.buffers()[1:]])
        columns.append(arr)
    canonical = pa.Table.from_arrays(columns, names=table.column_names)
    sink = pa.BufferOutputStream()
    with pa.ipc.new_stream(sink, canonical.schema) as writer:
        writer.write_table(canonical, max_chunksize=max(1, canonical.num_rows))
    return hashlib.sha256(sink.getvalue()).hexdigest()


def verify_part(path, header, expected_rows, source_id, member, first_record, expected_digest):
    table = pq.read_table(path)
    if table.column_names != header + PROVENANCE or table.num_rows != expected_rows:
        raise ValueError('Parquet columns/row count mismatch')
    for name in header:
        if not pa.types.is_string(table.schema.field(name).type) or table[name].null_count:
            raise ValueError('source field type/null mismatch')
    for name, value in zip(PROVENANCE[:2], [source_id, member]):
        if not pc.all(pc.equal(table[name], value)).as_py():
            raise ValueError('source/member provenance mismatch')
    if not table[PROVENANCE[2]].combine_chunks().equals(pa.array(range(first_record, first_record + expected_rows), type=pa.int64())):
        raise ValueError('record provenance mismatch')
    if table_digest(table.select(header)) != expected_digest:
        raise ValueError('source field round-trip mismatch')


def convert(source, output, source_id=None, chunk_rows=100000, chunk_bytes=32 * 1024 * 1024,
            memory_limit='2GB', threads=2, on_part=None):
    source, output = Path(source), Path(output)
    if chunk_rows < 1 or chunk_bytes < 1 or threads < 1:
        raise ValueError('resource bounds must be positive')
    if source.suffix.lower() not in ('.csv', '.zip'):
        raise ValueError('unsupported source container')
    output.mkdir(parents=True, exist_ok=False)
    input_hash = sha256(source)
    source_id = source_id or input_hash
    report = {'format': VERSION, 'source_id': source_id, 'input_sha256': input_hash,
              'source_bytes': source.stat().st_size, 'members': [], 'parts': [],
              'rows': 0, 'malformed_rows': 0, 'status': 'incomplete'}
    con = duckdb.connect()
    con.execute('SET memory_limit = ?', [memory_limit])
    con.execute('SET threads = ?', [threads])
    con.execute('SET preserve_insertion_order = true')
    csv.field_size_limit(1024 * 1024)

    def process(binary, member, ordinal):
        # Header-only Python CSV read; reset the seekable local file/ZIP member before native parsing.
        wrapper = io.TextIOWrapper(binary, encoding='utf-8-sig', newline='')
        try:
            strict_reader = csv.reader(wrapper, strict=True)
            header = next(strict_reader, None)
            expected_count = 0
            for row in strict_reader:
                if not header or len(row) != len(header):
                    report['malformed_rows'] += 1
                    raise ValueError('CSV width mismatch')
                expected_count += 1
                if expected_count % 10000000 == 0:
                    print(json.dumps({'event':'strict_csv_validation','records':expected_count}), flush=True)
        except csv.Error:
            report['malformed_rows'] += 1
            raise
        finally:
            wrapper.detach()
            binary.seek(0)
        if not header or any(not name for name in header):
            raise ValueError('missing/empty CSV header')
        names = [name.casefold() for name in header]
        if len(set(names)) != len(names) or set(names) & set(PROVENANCE):
            raise ValueError('duplicate or reserved CSV header')
        count, part_no = 0, 0
        try:
            reader = pacsv.open_csv(binary,
                read_options=pacsv.ReadOptions(block_size=chunk_bytes, use_threads=False),
                parse_options=pacsv.ParseOptions(newlines_in_values=True, ignore_empty_lines=False),
                convert_options=pacsv.ConvertOptions(column_types={h: pa.string() for h in header},
                    strings_can_be_null=False, null_values=[], check_utf8=True))
            if reader.schema.names != header:
                raise ValueError('native/header parser disagreement')
            for batch in reader:
                for offset in range(0, batch.num_rows, chunk_rows):
                    data = pa.Table.from_batches([batch.slice(offset, chunk_rows)])
                    for column in data.columns:
                        if pc.max(pc.binary_length(column)).as_py() > 1024 * 1024:
                            raise ValueError('source field exceeds 1 MiB limit')
                    if data.num_rows == 0:
                        continue
                    first = count + 1
                    digest = table_digest(data)
                    table = data.append_column(PROVENANCE[0], pa.array([source_id] * data.num_rows))
                    table = table.append_column(PROVENANCE[1], pa.array([member] * data.num_rows))
                    table = table.append_column(PROVENANCE[2], pa.array(range(first, first + data.num_rows), type=pa.int64()))
                    filename = f'member-{ordinal:04d}-part-{part_no:06d}.parquet'
                    path = output / filename
                    con.register('source_chunk', table)
                    quoted = str(path).replace("'", "''")
                    con.execute(f"COPY source_chunk TO '{quoted}' (FORMAT PARQUET, COMPRESSION ZSTD)")
                    con.unregister('source_chunk')
                    verify_part(path, header, data.num_rows, source_id, member, first, digest)
                    info = {'file': filename, 'member': member, 'first_record': first, 'rows': data.num_rows,
                            'field_sha256': digest, 'sha256': sha256(path), 'bytes': path.stat().st_size}
                    if 'DATE' in header:
                        info['dates'] = sorted(pc.unique(data['DATE']).to_pylist())
                    if on_part:
                        info.update(on_part(path, info))
                        path.unlink()
                    report['parts'].append(info)
                    count += data.num_rows
                    part_no += 1
        except pa.ArrowInvalid as exc:
            report['malformed_rows'] += 1
            raise ValueError('invalid CSV input') from exc
        if count == 0 or count != expected_count:
            raise ValueError('empty CSV or independent parser row count mismatch')
        report['members'].append({'name': member, 'header': header, 'rows': count})
        report['rows'] += count

    try:
        if source.suffix.lower() == '.zip':
            with zipfile.ZipFile(source) as archive:
                entries = [i for i in archive.infolist() if not i.is_dir()]
                if not entries or len({i.filename for i in entries}) != len(entries):
                    raise ValueError('empty archive or duplicate member names')
                if any(not i.filename.lower().endswith('.csv') or i.flag_bits & 1 for i in entries):
                    raise ValueError('unsupported/encrypted archive member')
                for ordinal, entry in enumerate(entries):
                    with archive.open(entry) as f:
                        process(f, entry.filename, ordinal)  # reading EOF verifies ZIP CRC
        else:
            with source.open('rb') as f:
                process(f, source.name, 0)
        if not report['rows'] or sum(p['rows'] for p in report['parts']) != report['rows']:
            raise ValueError('empty or unreconciled output')
        report['status'] = 'complete'
        (output / 'manifest.json').write_text(json.dumps(report, indent=2) + '\n')
        return report
    except Exception as exc:
        report.update(status='failed', error_type=type(exc).__name__)
        (output / 'failure.json').write_text(json.dumps(report, indent=2) + '\n')
        raise
    finally:
        con.close()


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('source'); p.add_argument('output')
    args = p.parse_args()
    result = convert(args.source, args.output)
    print(json.dumps({'status': result['status'], 'rows': result['rows'], 'parts': len(result['parts'])}))
