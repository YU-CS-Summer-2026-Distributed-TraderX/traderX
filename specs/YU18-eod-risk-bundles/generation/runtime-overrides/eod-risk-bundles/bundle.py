#!/usr/bin/env python3
"""Local EOD transport prototype. Standard library only; no financial valuation."""
import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
from datetime import date, datetime
from decimal import Decimal, InvalidOperation

POSITION_FIELDS = 'accountId,security,instrumentType,quantity,contractMultiplier,costBasis,closingMark,markSource,markQuality,marketValue,unrealizedPnl,currency,counterpartyId,nettingSetId,coupon,maturityDate,lastCouponDate,accruedInterestFraction'.split(',')
CONTRACT_FIELDS = 'contractId,accountId,payReceive,notional,fixedRate,floatIndex,effectiveDate,maturityDate,paymentFrequency,dayCount,currency,counterpartyId,nettingSetId,productType,expiryDate,exerciseStyle'.split(',')
KINDS = {'positions': (3, POSITION_FIELDS, 'rows', 'traderx-risk-extract'),
         'contracts': (2, CONTRACT_FIELDS, 'contracts', 'traderx-swap-contracts')}
STAMP = ('consensusSequence', 'sessionDate', 'priceSnapshotVersion', 'cutSha256')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def encoded(value):
    return (json.dumps(value, sort_keys=True, indent=2, allow_nan=False) + '\n').encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def decimal(value, field):
    try:
        number = Decimal(value)
    except InvalidOperation as exc:
        raise ValueError(f'invalid decimal: {field}') from exc
    require(number.is_finite(), f'non-finite decimal: {field}')
    return number


def read_extract(data, kind):
    schema, fields, count_key, title = KINDS[kind]
    lines = data.decode('utf-8').splitlines()
    require(bool(lines) and lines[0] == f'# {title} schema={schema}', f'{kind}: unsupported schema')
    metadata = {}
    start = 1
    while start < len(lines) and lines[start].startswith('#'):
        key, sep, value = lines[start][1:].strip().partition('=')
        if sep and key in (*STAMP, count_key):
            require(key not in metadata, f'duplicate metadata: {key}')
            metadata[key] = value
        start += 1
    require(all(key in metadata for key in (*STAMP, count_key)), f'{kind}: missing provenance')
    require(re.fullmatch('[0-9a-f]{64}', metadata['cutSha256']), 'invalid cut hash')
    date.fromisoformat(metadata['sessionDate'])
    for key in ('consensusSequence', 'priceSnapshotVersion', count_key):
        require(metadata[key].isdigit(), f'invalid {key}')
    reader = csv.DictReader(io.StringIO('\n'.join(lines[start:])))
    require(reader.fieldnames == fields, f'{kind}: unexpected CSV header')
    rows = list(reader)
    require(len(rows) == int(metadata[count_key]), f'{kind}: row count mismatch')
    identities = set()
    for row in rows:
        require(None not in row and all(v is not None for v in row.values()), 'malformed CSV row')
        required = ('accountId', 'security', 'currency', 'instrumentType', 'markSource', 'markQuality') if kind == 'positions' else tuple(f for f in fields if f not in ('expiryDate', 'exerciseStyle', 'counterpartyId', 'nettingSetId'))
        require(all(row[f].strip() for f in required), f'{kind}: missing required field')
        identity = (row['accountId'], row['security']) if kind == 'positions' else row['contractId']
        require(identity not in identities, f'{kind}: duplicate identity')
        identities.add(identity)
        if kind == 'positions':
            require(row['instrumentType'] in ('EQUITY', 'OPTION', 'TREASURY', 'CORPORATE'), 'unknown instrument type')
            for f in ('quantity', 'contractMultiplier', 'costBasis', 'closingMark', 'marketValue', 'unrealizedPnl'):
                decimal(row[f], f)
            require(decimal(row['contractMultiplier'], 'contractMultiplier') > 0, 'invalid multiplier')
            if row['instrumentType'] in ('TREASURY', 'CORPORATE'):
                coupon = decimal(row['coupon'], 'coupon')
                date.fromisoformat(row['maturityDate'])
                if coupon == 0:
                    require(not row['lastCouponDate'] and not row['accruedInterestFraction'],
                            'zero-coupon bonds must have empty coupon schedule/accrual fields')
                else:
                    decimal(row['accruedInterestFraction'], 'accruedInterestFraction')
                    date.fromisoformat(row['lastCouponDate'])
        else:
            require(row['productType'] in ('SWAP', 'SWAPTION'), 'unknown OTC product')
            require(row['payReceive'] in ('PAY_FIXED', 'RECEIVE_FIXED'), 'invalid fixed-leg direction')
            require(decimal(row['notional'], 'notional') > 0, 'invalid notional')
            decimal(row['fixedRate'], 'fixedRate')
            require(date.fromisoformat(row['effectiveDate']) < date.fromisoformat(row['maturityDate']), 'invalid contract dates')
            if row['productType'] == 'SWAPTION':
                require(row['exerciseStyle'] in ('EUROPEAN', 'BERMUDAN', 'AMERICAN'), 'invalid exercise style')
                require(date.fromisoformat(row['expiryDate']) <= date.fromisoformat(row['effectiveDate']), 'invalid expiry')
            else:
                require(not row['expiryDate'] and not row['exerciseStyle'], 'swap contains option fields')
    return metadata, rows


def read_pair(payloads):
    parsed = {kind: read_extract(payloads[kind], kind) for kind in KINDS}
    require(all(parsed['positions'][0][k] == parsed['contracts'][0][k] for k in STAMP), 'extracts belong to different EOD cuts')
    return parsed


def manifest_for(payloads, epoch, valuation_time, origin):
    require(isinstance(epoch, str) and bool(epoch.strip()), 'cluster epoch is required')
    require(origin in ('synthetic', 'export'), 'unknown input origin')
    timestamp = datetime.fromisoformat(valuation_time.replace('Z', '+00:00'))
    require(timestamp.tzinfo is not None and timestamp.utcoffset() is not None, 'valuation time needs an explicit UTC offset')
    parsed = read_pair(payloads)
    manifest = {'schema': 'traderx.eod-bundle.v1', 'clusterEpoch': epoch,
                'valuationTime': timestamp.isoformat(), 'inputOrigin': origin,
                'cut': {k: parsed['positions'][0][k] for k in STAMP},
                'marketInputs': {'status': 'NOT_SUPPLIED'},
                'artifacts': {kind: {'path': f'{kind}.csv', 'schema': KINDS[kind][0],
                                   'sha256': digest(payloads[kind]), 'rows': len(parsed[kind][1])}
                              for kind in KINDS}}
    manifest['bundleId'] = digest(encoded(manifest))
    return manifest


def publish_directory(output, files):
    """Stage privately, publish once. Caller controls retention of input artifacts."""
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    require(not output.exists() and not output.is_symlink(), 'output already exists; choose a new path')
    # Exclusive sibling lock makes competing CLI publishers fail rather than overwrite.
    lock = output.with_name(output.name + '.publish-lock')
    fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.close(fd)
    temporary = None
    try:
        require(not output.exists() and not output.is_symlink(), 'output already exists')
        temporary = Path(tempfile.mkdtemp(prefix='.eod-stage-', dir=output.parent))
        for name, data in files.items():
            path = temporary / name
            with path.open('xb') as stream:
                os.chmod(path, 0o600)
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
        os.rename(temporary, output)
        temporary = None
    finally:
        if temporary is not None:
            shutil.rmtree(temporary)
        lock.unlink()


def build(positions, contracts, output, epoch, valuation_time, origin):
    payloads = {'positions': Path(positions).read_bytes(), 'contracts': Path(contracts).read_bytes()}
    manifest = manifest_for(payloads, epoch, valuation_time, origin)
    publish_directory(output, {**{f'{k}.csv': v for k, v in payloads.items()}, 'manifest.json': encoded(manifest)})
    return manifest


def validate(bundle):
    root = Path(bundle)
    expected_files = {'manifest.json', 'positions.csv', 'contracts.csv'}
    require({p.name for p in root.iterdir()} == expected_files, 'unexpected or missing bundle files')
    require(all((root / p).is_file() and not (root / p).is_symlink() for p in expected_files), 'bundle files must be regular, non-symlink files')
    manifest = json.loads((root / 'manifest.json').read_text())
    payloads = {k: (root / f'{k}.csv').read_bytes() for k in KINDS}
    expected = manifest_for(payloads, manifest['clusterEpoch'], manifest['valuationTime'], manifest['inputOrigin'])
    require(manifest == expected, 'manifest identity, schema, provenance or integrity mismatch')
    return manifest, read_pair(payloads)


def mock(bundle, output):
    manifest, parsed = validate(bundle)
    items = []
    for kind, (_, rows) in parsed.items():
        for row in rows:
            identity = {'accountId': row['accountId']}
            identity.update({'security': row['security']} if kind == 'positions' else {'contractId': row['contractId']})
            items.append({'source': kind, **identity, 'currency': row['currency'],
                          'status': 'NOT_PRICED', 'reason': 'MOCK_ONLY', 'npv': None, 'greeks': {}})
    result = {'schema': 'traderx.mock-result.v1', 'bundleId': manifest['bundleId'],
              'clusterEpoch': manifest['clusterEpoch'], 'valuationTime': manifest['valuationTime'],
              'engine': 'transport-mock', 'synthetic': True, 'usableForRisk': False,
              'status': 'MOCK_COMPLETE', 'coverage': {'submitted': len(items), 'priced': 0},
              'items': items}
    publish_directory(output, {'results.json': encoded(result)})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    create = sub.add_parser('build')
    for flag in ('positions', 'contracts', 'output', 'epoch', 'valuation-time'):
        create.add_argument('--' + flag, required=True)
    create.add_argument('--origin', choices=('synthetic', 'export'), required=True)
    check = sub.add_parser('validate')
    check.add_argument('bundle')
    consume = sub.add_parser('mock')
    consume.add_argument('bundle')
    consume.add_argument('--output', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'build':
            result = build(args.positions, args.contracts, args.output, args.epoch, args.valuation_time, args.origin)
        elif args.command == 'validate':
            result, _ = validate(args.bundle)
        else:
            result = mock(args.bundle, args.output)
        print(json.dumps({'ok': True, 'bundleId': result['bundleId']}))
    except (ValueError, OSError, KeyError, TypeError, csv.Error) as exc:
        parser.exit(1, f'error: {exc}\n')


if __name__ == '__main__':
    main()
