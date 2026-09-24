#!/usr/bin/env python3
"""Package completed local risk.extract.ready receipts. No network or pricing."""
import argparse
import csv
from datetime import date
import os
import re
from pathlib import Path
from urllib.parse import urlsplit, unquote

import bundle
import receipt_scope
from coordinator import load_json, private_directory, decode_json


def integer(value, field):
    bundle.require(type(value) is int or isinstance(value, str) and value.isascii() and value.isdigit(),
                   f'invalid integer: {field}')
    value = int(value)
    bundle.require(value >= 0, f'negative integer: {field}')
    return value


def artifact(uri, root):
    bundle.require(isinstance(uri, str), 'artifact URI must be a string')
    parsed = urlsplit(uri)
    bundle.require(parsed.scheme == 'file' and not parsed.netloc and not parsed.query and not parsed.fragment,
                   'only local file URIs are supported')
    path = Path(unquote(parsed.path))
    bundle.require(path.is_absolute(), 'artifact URI must be absolute')
    resolved = path.resolve()
    bundle.require(resolved.is_relative_to(root.resolve()), 'artifact escapes allowed root')
    bundle.require(not any(p.is_symlink() for p in (path, *path.parents)), 'symlink artifact path')
    bundle.require(path.is_file(), 'missing artifact')
    return path.read_bytes()


def validate_event(event, payloads, session_date, cut=None):
    bundle.require(isinstance(event, dict), 'receipt must be an object')
    fields = {'schema', 'uri', 'consensusSequence', 'sessionDate', 'priceSnapshotVersion', 'rows',
              'sha256', 'cutSha256', 'quiesceWitnessSequence', 'contractsSchema', 'contractsUri',
              'contracts', 'contractsSha256'}
    if 'receiptSchema' in event:
        bundle.require(event['receiptSchema'] == 'traderx.risk-extract.ready.v2', 'unsupported receipt schema')
        fields |= {'receiptSchema', 'platformIdentity', 'cutUri'}
        identity = event.get('platformIdentity')
        bundle.require(isinstance(identity, dict) and set(identity) == {'epoch', 'eventIdScheme', 'projectionScope', 'storageLineage', 'runDescriptorSha256'}, 'invalid platform identity')
        bundle.require(identity['eventIdScheme'] == 'epoch-v1', 'unsupported platform event scheme')
        for key, pattern in [('epoch', r'[a-z0-9_]{1,25}'), ('projectionScope', r'[a-z0-9_-]{1,64}'),
                             ('storageLineage', r'[a-z0-9_-]{1,64}'), ('runDescriptorSha256', r'[0-9a-f]{64}')]:
            bundle.require(isinstance(identity[key], str) and re.fullmatch(pattern, identity[key]), 'invalid platform identity: ' + key)
        bundle.require(identity['projectionScope'] != 'legacy-unknown', 'managed scope cannot be legacy unknown')
        bundle.require(cut is not None and bundle.digest(cut) == event['cutSha256'], 'managed receipt source cut hash mismatch')
        head = cut.decode('ascii').splitlines()[0]
        tokens = [token.split('=', 1) for token in head.split(' ') if '=' in token]
        cut_fields = dict(tokens)
        bundle.require(len(tokens) == len(cut_fields), 'duplicate source cut field')
        bundle.require(head.startswith('#cut ') and cut_fields.get('schema') == '3'
                       and cut_fields.get('seq') == str(integer(event['consensusSequence'], 'consensusSequence'))
                       and cut_fields.get('priceVersion') == str(integer(event['priceSnapshotVersion'], 'priceSnapshotVersion'))
                       and cut_fields.get('sessionDateEpochDay') == str((date.fromisoformat(session_date) - date(1970, 1, 1)).days), 'source cut stamp mismatch')
        bundle.require(cut_fields.get('runDescriptorHash') == identity['runDescriptorSha256']
                       and cut_fields.get('projectionScope') == identity['projectionScope']
                       and cut_fields.get('eventIdScheme') == identity['eventIdScheme'], 'source cut platform identity mismatch')
    bundle.require(set(event) == fields, 'unexpected or missing receipt fields')
    bundle.require(event['sessionDate'] == session_date, 'receipt is for a different business date')
    sequence = integer(event['consensusSequence'], 'consensusSequence')
    bundle.require(integer(event['quiesceWitnessSequence'], 'quiesceWitnessSequence') == sequence + 1,
                   'non-adjacent quiescence witness')
    version = integer(event['priceSnapshotVersion'], 'priceSnapshotVersion')
    bundle.require(integer(event['schema'], 'schema') == 3
                   and integer(event['contractsSchema'], 'contractsSchema') == 2, 'unsupported export schema')
    parsed = bundle.read_pair(payloads)
    for kind, hash_field, count in [('positions', 'sha256', 'rows'),
                                     ('contracts', 'contractsSha256', 'contracts')]:
        bundle.require(bundle.digest(payloads[kind]) == event[hash_field], f'{kind}: receipt hash mismatch')
        metadata, rows = parsed[kind]
        bundle.require(len(rows) == integer(event[count], count), f'{kind}: receipt count mismatch')
        expected = {'consensusSequence': str(sequence), 'sessionDate': event['sessionDate'],
                    'priceSnapshotVersion': str(version), 'cutSha256': event['cutSha256']}
        bundle.require(all(metadata[key] == value for key, value in expected.items()), 'receipt cut mismatch')


def package(receipt, artifact_root, inbox, epoch, valuation_time, origin, session_date, run_descriptor=None):
    receipt, artifact_root, inbox = Path(receipt), Path(artifact_root), Path(inbox)
    bundle.require(receipt.is_file() and not receipt.is_symlink(), 'invalid receipt file')
    event = load_json(receipt)
    payloads = {'positions': artifact(event['uri'], artifact_root),
                'contracts': artifact(event['contractsUri'], artifact_root)}
    cut = artifact(event['cutUri'], artifact_root) if 'receiptSchema' in event else None
    validate_event(event, payloads, session_date, cut)
    if 'platformIdentity' in event:
        identity = event['platformIdentity']
        bundle.require(run_descriptor is not None, 'managed receipt requires --run-descriptor')
        descriptor_path = Path(run_descriptor)
        bundle.require(descriptor_path.is_file() and not descriptor_path.is_symlink(), 'invalid run descriptor file')
        raw = descriptor_path.read_bytes()
        bundle.require(bundle.digest(raw) == identity['runDescriptorSha256'], 'run descriptor hash mismatch')
        descriptor = decode_json(raw)
        bundle.require(isinstance(descriptor, dict) and set(descriptor) == {'schema', 'epoch', 'eventIdScheme', 'storageLineage', 'projectionScope', 'adoptionEvidenceSha256'}
                       and descriptor['schema'] == 'traderx.run.v1' and descriptor['adoptionEvidenceSha256'] is None, 'invalid managed run descriptor')
        bundle.require(epoch == identity['epoch'] and all(descriptor[k] == identity[k] for k in ('epoch', 'eventIdScheme', 'projectionScope', 'storageLineage')), 'platform epoch or lineage mismatch')
    manifest = bundle.manifest_for(payloads, epoch, valuation_time, origin)
    bundle.require(not any((p / '.git').exists() for p in (inbox.resolve(), *inbox.resolve().parents)),
                   'inbox must be outside a Git checkout')
    private_directory(inbox)
    receipt_scope.bind(receipt.parent, inbox, epoch)
    output = inbox / manifest['bundleId']
    duplicate = output.exists()
    if duplicate:
        existing, _ = bundle.validate(output)
        bundle.require(existing == manifest, 'existing bundle identity mismatch')
    else:
        bundle.publish_directory(output, {'manifest.json': bundle.encoded(manifest),
                                           **{f'{k}.csv': data for k, data in payloads.items()}})
    return {'bundleId': manifest['bundleId'], 'path': str(output), 'duplicate': duplicate}


def scan(receipts, artifact_root, inbox, epoch, valuation_time, origin, session_date, run_descriptor=None):
    root = Path(receipts)
    bundle.require(root.is_dir() and not root.is_symlink(), 'receipt directory does not exist or is a symlink')
    date.fromisoformat(session_date)
    result = {'packaged': [], 'invalid': [], 'skippedOtherDates': []}
    # Scope one invocation to one valuation context; operators use separate receipt directories
    # per run/date when their epoch or valuation time differs.
    for path in sorted(root.glob('*.ready.json')):
        try:
            bundle.require(path.is_file() and not path.is_symlink(), 'invalid receipt file')
            event = load_json(path)
            bundle.require(isinstance(event, dict), 'receipt must be an object')
            event_date = date.fromisoformat(event['sessionDate'])
            if event_date.isoformat() != session_date:
                result['skippedOtherDates'].append(str(path))
                continue
            result['packaged'].append(package(path, artifact_root, inbox, epoch, valuation_time, origin, session_date, run_descriptor))
        except (ValueError, OSError, KeyError, TypeError, csv.Error) as exc:
            result['invalid'].append({'receipt': str(path), 'reason': str(exc)})
    return result


def main():
    import json
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ('receipts', 'artifact-root', 'inbox', 'epoch', 'valuation-time', 'session-date'):
        parser.add_argument('--' + flag, required=True)
    parser.add_argument('--origin', choices=('synthetic', 'export'), required=True)
    parser.add_argument("--run-descriptor")
    args = parser.parse_args()
    os.umask(0o077)
    try:
        result = scan(args.receipts, args.artifact_root, args.inbox, args.epoch, args.valuation_time, args.origin, args.session_date, args.run_descriptor)
        print(json.dumps(result, indent=2))
        return int(bool(result['invalid']))
    except (ValueError, OSError, KeyError, TypeError, csv.Error) as exc:
        parser.exit(1, f'error: {exc}\n')


if __name__ == '__main__':
    raise SystemExit(main())
