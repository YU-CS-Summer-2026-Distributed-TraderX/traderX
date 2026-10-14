import csv
import io
import json
from pathlib import Path
import zipfile

import pyarrow as pa
import pyarrow.parquet as pq
import pytest
from preserve import convert, record_digest, verify_part, PROVENANCE
from inventory import freeze
from worker import validate_commit


def source(tmp_path, text):
    p = tmp_path / 'input.csv'
    p.write_text(text)
    return p


def test_all_fields_and_record_order(tmp_path):
    header = ['SYM_ROOT', 'SYM_SUFFIX', 'TIME_M', 'PRICE', 'TR_CORR', 'UNKNOWN', 'EMPTY']
    rows = [['BRK', 'A', '9:30:00.123456789', '0700000.000', '00', 'line\n"quoted",x', ''],
            ['BRK', 'B', '9:30:00.123456788', '00451.8400', '01', 'NULL', ''],
            ['GOOG', 'L', '9:30:00.000000001', '1e2', '02', '  spaces  ', '']]
    f = io.StringIO(newline=''); w = csv.writer(f); w.writerow(header); w.writerows(rows)
    result = convert(source(tmp_path, f.getvalue()), tmp_path / 'out', chunk_rows=1)
    assert result['rows'] == 3 and len(result['parts']) == 3
    actual = []
    for p in result['parts']:
        t = pq.read_table(tmp_path / 'out' / p['file']).to_pydict()
        actual += list(zip(*(t[name] for name in header)))
    assert actual == [tuple(r) for r in rows]


@pytest.mark.parametrize('text', ['', 'A,B\n', 'A,A\n1,2\n', 'A,a\n1,2\n',
                                  '__ri30_record\n1\n', 'A,B\n1\n', 'A\n"unclosed\n', 'A\n\n'])
def test_bad_input_never_completes(tmp_path, text):
    with pytest.raises((ValueError, csv.Error)):
        convert(source(tmp_path, text), tmp_path / 'out')
    assert not (tmp_path / 'out' / 'manifest.json').exists()
    assert json.loads((tmp_path / 'out' / 'failure.json').read_text())['status'] == 'failed'


def test_late_failure_has_no_completion(tmp_path):
    with pytest.raises(ValueError):
        convert(source(tmp_path, 'A,B\n1,2\n3,4\n5\n'), tmp_path / 'out', chunk_rows=1)
    assert not (tmp_path / 'out' / 'manifest.json').exists()


def test_multiple_zip_members_and_crc(tmp_path):
    z = tmp_path / 'input.zip'
    with zipfile.ZipFile(z, 'w', compression=zipfile.ZIP_STORED) as f:
        f.writestr('one.csv', 'A\nx\n'); f.writestr('two.csv', 'B,C\ny,z\n')
    result = convert(z, tmp_path / 'out')
    assert result['rows'] == 2 and len(result['members']) == 2
    raw = z.read_bytes().replace(b'A\nx\n', b'A\nq\n')
    bad = tmp_path / 'bad.zip'; bad.write_bytes(raw)
    with pytest.raises(zipfile.BadZipFile):
        convert(bad, tmp_path / 'bad-out')
    assert not (tmp_path / 'bad-out' / 'manifest.json').exists()


def test_tampered_field_detected(tmp_path):
    result = convert(source(tmp_path, 'A\n001\n'), tmp_path / 'out')
    p = result['parts'][0]; path = tmp_path / 'out' / p['file']
    table = pq.read_table(path).set_column(0, 'A', pa.array(['1']))
    pq.write_table(table, path)
    with pytest.raises(ValueError, match='round-trip'):
        verify_part(path, ['A'], 1, result['source_id'], 'input.csv', 1, p['field_sha256'])


def test_output_reuse_refused(tmp_path):
    p = source(tmp_path, 'A\nx\n'); convert(p, tmp_path / 'out')
    with pytest.raises(FileExistsError):
        convert(p, tmp_path / 'out')


def test_upload_failure_never_completes(tmp_path):
    def fail(*args):
        raise RuntimeError('upload failed')
    with pytest.raises(RuntimeError):
        convert(source(tmp_path, 'A\nx\n'), tmp_path / 'out', on_part=fail)
    assert not (tmp_path / 'out' / 'manifest.json').exists()


def test_inventory_frozen_and_nonempty():
    item = {'metadata': {'bucket': 'b', 'name': 'x.zip', 'generation': '3', 'size': '7',
                         'crc32c': 'test', 'storageClass': 'NEARLINE'}}
    result = freeze([item], ['x.zip'])
    assert result['objects'][0]['generation'] == 3
    with pytest.raises(ValueError): freeze([item], ['missing'])
    with pytest.raises(ValueError): freeze([], [])
    with pytest.raises(ValueError): freeze([item, item], ['x.zip'])


def test_completion_requires_nonempty_effects():
    with pytest.raises(ValueError):
        validate_commit(None, {'format': 'ri30-v2', 'source': {}, 'input_manifest_sha256': 'x',
                               'status': 'complete', 'rows': 0, 'parts': [], 'execution_image': 'img'}, {}, 'x', 'img')


def test_digest_unambiguous():
    assert record_digest([['ab', 'c']]) != record_digest([['a', 'bc']])
    assert record_digest([['a'], ['b']]) != record_digest([['a', 'b']])
