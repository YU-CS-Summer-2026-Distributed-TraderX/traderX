#!/usr/bin/env python3
"""Offline source-role inventory. No runtime startup, network, or universe mutations."""
import argparse
import collections
import csv
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import zlib

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PUBLISHER = ROOT / 'specs/YU16-cdm-instruments/generation/runtime-overrides/price-publisher'
REFERENCE_READER = ROOT / 'specs/YU16-cdm-instruments/generation/runtime-overrides/reference-data/src/data-loader/load-csv-data.ts'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def strict_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError(f'duplicate JSON key: {key}')
            result[key] = value
        return result
    def invalid_constant(value):
        raise ValueError(f'non-JSON numeric constant: {value}')
    return json.loads(data.decode('utf-8'), object_pairs_hook=pairs,
                      parse_constant=invalid_constant)


def unavailable(reason, state='unavailable', **extra):
    return {'state': state, 'reason': reason, **extra}


def snapshot(path, limit):
    """Bounded read of a regular file; hashes describe exactly these captured bytes."""
    if path is None:
        return unavailable('not supplied', 'not_supplied'), None
    p = Path(path).resolve()
    role = {'suppliedPath': str(p)}
    try:
        with os.fdopen(os.open(p, os.O_RDONLY | os.O_NONBLOCK), 'rb') as stream:
            if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                raise ValueError('input must be a regular file')
            data = stream.read(limit + 1)
        if len(data) > limit:
            raise ValueError('input exceeds diagnostic byte budget')
        return {'state': 'valid', 'sha256': digest(data), 'bytes': len(data), **role}, data
    except OSError as error:
        return unavailable(str(error), **role), None
    except ValueError as error:
        return unavailable(str(error), 'invalid', **role), None


def gunzip(data, limit):
    with gzip.GzipFile(fileobj=io.BytesIO(data)) as stream:
        plain = stream.read(limit + 1)
    if len(plain) > limit:
        raise ValueError('decompressed input exceeds diagnostic byte budget')
    return plain


def identities(role, values, limit):
    if not isinstance(values, list) or not values:
        raise ValueError('empty identity population is not coverage evidence')
    if len(values) > limit:
        raise ValueError('identity population exceeds diagnostic budget')
    if any(not isinstance(s, str) or not s or s != s.strip() for s in values):
        raise ValueError('identities must be nonempty exact strings without boundary whitespace')
    duplicates = sorted(s for s, count in collections.Counter(values).items() if count > 1)
    if duplicates:
        role['duplicateIdentities'] = duplicates
        raise ValueError('duplicate effective identities; no silent set collapse')
    role['symbols'] = sorted(values)


def relation(left, right):
    """Both populations must be valid/nonempty; callers must not coerce unavailable to empty."""
    a, b = set(left), set(right)
    return {'overlap': sorted(a & b), 'leftWithoutRight': sorted(a - b),
            'rightWithoutLeft': sorted(b - a)}


def csv_directory(data, column):
    reader = csv.DictReader(io.StringIO(data.decode('utf-8'), newline=''), strict=True)
    fields = reader.fieldnames
    if not fields or len(fields) != len(set(fields)) or column not in fields:
        raise ValueError('missing/duplicate CSV headers or missing explicit identity column')
    result = []
    for row in reader:
        if None in row or any(value is None for value in row.values()):
            raise ValueError('CSV row length does not match headers')
        result.append(row[column])
    return result


def js_equal(a, b):
    # Clock compatibility in print.load uses JS strict equality, not Python bool/number equality.
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(js_equal(x, y) for x, y in zip(a, b))
    if type(a) in (int, float) and type(b) in (int, float):
        return a == b
    return type(a) is type(b) and a == b


def analyze(args):
    sources = {}
    declaration, config_bytes = snapshot(args.declaration_config, args.max_input_bytes)
    config = None
    if args.declaration_default:
        declaration = {'state': 'valid', 'selection': 'explicit_publisher_default'}
        config = {}
    elif config_bytes is not None:
        try:
            config = strict_json(config_bytes)
            if not isinstance(config, dict) or set(config) - {'PRICE_TICKERS'}:
                raise ValueError('config must contain only optional PRICE_TICKERS')
            if 'PRICE_TICKERS' in config and not isinstance(config['PRICE_TICKERS'], str):
                raise ValueError('PRICE_TICKERS must be a supplied environment string')
            declaration['selection'] = 'supplied_environment_config'
        except (ValueError, UnicodeError, RecursionError) as error:
            declaration.update(state='invalid', reason=str(error))
    declaration['role'] = 'PRICE_TICKERS declaration only, not the full quote/option universe'
    sources['declaration'] = declaration
    tape_role, tape_bytes = snapshot(args.tape_extract, args.max_input_bytes)
    sample_role, sample_bytes = snapshot(args.print_sample, args.max_input_bytes)
    sources['tape'] = tape_role
    sources['printSample'] = sample_role
    reference_role, reference_bytes = snapshot(args.reference_csv, args.max_input_bytes)
    reference_role['semantics'] = ('exact supplied CSV column only; not seeded/filter/capped '
        'reference service, database, outbox, engine admission or tradability')
    reference_role['column'] = args.reference_column
    sources['referenceDirectory'] = reference_role
    if reference_bytes is not None:
        try:
            identities(reference_role, csv_directory(reference_bytes, args.reference_column), args.max_identities)
        except (ValueError, UnicodeError, csv.Error) as error:
            reference_role.update(state='invalid', reason=str(error))
    decoded = {}
    request = {'publisherRoot': str(Path(args.publisher_root).resolve()), 'config': config or {}}
    with tempfile.TemporaryDirectory(prefix='tape-universe-') as directory:
        for role, data, name in [(tape_role, tape_bytes, 'tape'), (sample_role, sample_bytes, 'sample')]:
            if data is None:
                continue
            try:
                plain = gunzip(data, args.max_input_bytes)
                role['decodedSha256'] = digest(plain)
                role['decodedBytes'] = len(plain)
                if name == 'tape':
                    strict_json(plain)  # duplicate/UTF-8/constant preflight stricter than JSON.parse
                    captured = data
                else:
                    captured = plain
                file = Path(directory) / name
                file.write_bytes(captured)
                request['tapeSnapshot' if name == 'tape' else 'sampleSnapshot'] = str(file)
            except (ValueError, UnicodeError, OSError, EOFError, zlib.error, RecursionError) as error:
                role.update(state='invalid', reason=str(error))
        try:
            response = subprocess.run(['node', str(HERE / 'readers.cjs')], input=json.dumps(request),
                text=True, capture_output=True, timeout=20, check=True,
                env={k: os.environ[k] for k in ('PATH', 'TMPDIR') if k in os.environ})
            decoded = json.loads(response.stdout)
            for name, role in [('declaration', declaration), ('tape', tape_role), ('print', sample_role)]:
                if role['state'] != 'valid' or name not in decoded:
                    continue
                if name == 'declaration' and config is None:
                    continue
                value = decoded[name]
                key = 'tapeSnapshot' if name == 'tape' else 'sampleSnapshot'
                if 'reason' in value and key in request:
                    value['reason'] = value['reason'].replace(request[key], role.get('suppliedPath', '<supplied input>'))
                role.update(value)
                if role['state'] == 'valid':
                    try:
                        identities(role, role.get('symbols'), args.max_identities)
                    except ValueError as error:
                        role.update(state='invalid', reason=str(error))
        except (OSError, subprocess.SubprocessError, ValueError) as error:
            for role in (declaration, tape_role, sample_role):
                if role['state'] == 'valid':
                    role.update(state='invalid', reason=f'offline reader bridge failed: {error}')
    reader_inputs = decoded.get('readerInputs', {})
    core = None
    if declaration['state'] == tape_role['state'] == 'valid':
        core = relation(declaration['symbols'], tape_role['symbols'])
        core = {'declared': declaration['symbols'], 'tapeAvailable': tape_role['symbols'],
                'overlap': core['overlap'], 'declaredWithoutTape': core['leftWithoutRight'],
                'tapeExcludedByDeclaration': core['rightWithoutLeft']}
    directory = None
    if core is not None and reference_role['state'] == 'valid':
        refs = set(reference_role['symbols'])
        directory = {'declaredMissingFromDirectory': sorted(set(core['declared']) - refs),
                     'tapeMissingFromDirectory': sorted(set(core['tapeAvailable']) - refs),
                     'overlapMissingFromDirectory': sorted(set(core['overlap']) - refs)}
    prints = None
    if core is not None and sample_role['state'] == 'valid':
        sample, tape, declared = set(sample_role['symbols']), set(tape_role['symbols']), set(declaration['symbols'])
        clock_aligned = all(js_equal(sample_role[k], tape_role[k]) for k in ('days','windowSeconds','sessionSeconds'))
        missing = sorted(sample - tape)
        prints = {'sampleSymbols': sorted(sample), 'sampleWithoutTape': missing,
                  'staticDeclaredTapeCandidates': sorted(sample & tape & declared),
                  'sampleExcludedByDeclaration': sorted(sample - declared),
                  'declaredTapeWithoutSample': sorted((declared & tape) - sample),
                  'clockCalendarAligned': clock_aligned,
                  'runtimeArtifactAlignment': clock_aligned and not missing,
                  'semantics': 'static metadata only; actual loader refuses the whole sample on clock/calendar or tape mismatch; no addressable-clock/account/admission/flow claim'}
    context, _ = snapshot(REFERENCE_READER, args.max_input_bytes)
    if context['state'] == 'valid' and context['sha256'] != 'ca98c265b3d8a12906c1455c59d887187d1bf280601151f33560240b8984c916':
        context.update(state='invalid', reason='unsupported reference loader source')
    context['readerDifferences'] = 'runtime loader uppercases CSV first column, maps FB to META, deduplicates, adds supplemental/ETF/Treasury seeds, applies supportedTickers/maxTickers; diagnostic does none of these'
    required = declaration['state'] == tape_role['state'] == context['state'] == 'valid'
    optional_bad = any(r['state'] not in ('valid','not_supplied') for r in (reference_role,sample_role))
    return {'schema':1,'analysis':'complete' if required and not optional_bad else 'incomplete',
            'sources':sources,'relations':{'declaredTape':core,'referenceDirectory':directory,'printSample':prints},
            'readerContext':{'referenceLoader':context,'publisherSources':reader_inputs},
            'diagnostic':{'scriptSha256':digest(Path(__file__).read_bytes()),
                          'bridgeSha256':digest((HERE/'readers.cjs').read_bytes()),
                          'budgets':{'inputAndDecodedBytes':args.max_input_bytes,'identities':args.max_identities,
                                     'outputBytes':args.max_output_bytes},
                          'limitations':'supplied offline source roles only; no live quote timing, alias inference, engine admission, licensing, instrument support, stored-object integrity or financial validity'} }


def private_output(path, data):
    if path is None:
        folder = Path(tempfile.gettempdir()).resolve()
        if any((parent / '.git').exists() for parent in [folder, *folder.parents]):
            raise ValueError('diagnostic temporary output directory must be outside Git checkouts')
        fd, name = tempfile.mkstemp(prefix='tape-universe-', suffix='.json')
    else:
        p = Path(path).resolve()
        if any((parent / '.git').exists() for parent in [p.parent, *p.parents]):
            raise ValueError('diagnostic output must be outside Git checkouts')
        fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        name = str(p)
    with os.fdopen(fd,'wb') as stream:
        stream.write(data)
    return name


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--declaration-default', action='store_true')
    group.add_argument('--declaration-config')
    parser.add_argument('--tape-extract')
    parser.add_argument('--reference-csv')
    parser.add_argument('--reference-column', default='ticker')
    parser.add_argument('--print-sample')
    parser.add_argument('--publisher-root', default=str(PUBLISHER))
    parser.add_argument('--output')
    parser.add_argument('--max-input-bytes',type=int,default=16*1024*1024)
    parser.add_argument('--max-output-bytes',type=int,default=4*1024*1024)
    parser.add_argument('--max-identities',type=int,default=10000)
    args = parser.parse_args(argv)
    if min(args.max_input_bytes,args.max_output_bytes,args.max_identities) <= 0:
        parser.error('diagnostic budgets must be positive')
    report = analyze(args)
    data = (json.dumps(report,sort_keys=True,indent=2,ensure_ascii=True,allow_nan=False)+'\n').encode()
    try:
        if len(data) > args.max_output_bytes:
            raise ValueError('report exceeds diagnostic output byte budget')
        name = private_output(args.output,data)
    except (OSError,ValueError) as error:
        print(f'output refused: {error}',file=sys.stderr)
        return 2
    counts = {name:len(role['symbols']) for name,role in report['sources'].items() if role['state']=='valid' and 'symbols' in role}
    print(json.dumps({'analysis':report['analysis'],'counts':counts,'privateOutput':name},sort_keys=True))
    return 0 if report['analysis']=='complete' else 2


if __name__ == '__main__':
    sys.exit(main())
