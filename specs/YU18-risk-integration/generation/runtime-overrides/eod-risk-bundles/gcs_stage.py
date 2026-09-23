#!/usr/bin/env python3
"""Read-only, generation-pinned GCS staging. Archives are not completion receipts."""
import argparse
import base64
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import urlsplit

import bridge
import bundle
from coordinator import decode_json, private_directory


def object_uri(uri, prefix):
    # Restrict CLI wildcard/URL interpretation: one concrete object, optional generation.
    bundle.require(isinstance(uri, str) and not any(c in uri for c in '*?[]%\\\r\n'),
                   'expected a literal GCS object URI')
    parsed = urlsplit(uri)
    bundle.require(parsed.scheme == 'gs' and re.fullmatch(r'[a-z0-9][a-z0-9._-]+', parsed.netloc)
                   and parsed.path.startswith('/') and parsed.path != '/'
                   and not parsed.query, 'invalid GCS object URI')
    bundle.require(not parsed.fragment or re.fullmatch(r'[0-9]+', parsed.fragment),
                   'invalid object generation')
    base = f'gs://{parsed.netloc}{parsed.path}'
    bundle.require(prefix.startswith('gs://') and prefix.endswith('/') and '#' not in prefix
                   and base.startswith(prefix), 'object outside allowed GCS prefix')
    bundle.require(all(part not in ('', '.', '..') for part in parsed.path[1:].split('/')),
                   'invalid object path')
    return base, parsed.netloc, parsed.path[1:], parsed.fragment


class GcloudStore:
    """Uses the installed gcloud credentials; never prints credentials or object bodies."""
    def describe(self, uri):
        result = subprocess.run(['gcloud', 'storage', 'objects', 'describe', uri,
                                 '--raw', '--format=json', '--quiet'],
                                capture_output=True, check=True, timeout=60)
        return json.loads(result.stdout)

    def read(self, uri, size):
        # Read exactly the pinned metadata length; gcloud rejects ranges extending past EOF.
        if size == 0:
            return b''
        with tempfile.TemporaryFile() as output:
            subprocess.run(['gcloud', 'storage', 'cat', uri, f'--range=0-{size - 1}', '--quiet'],
                           stdout=output, stderr=subprocess.PIPE, check=True, timeout=60)
            output.seek(0)
            return output.read(size + 1)


def fetch(store, uri, prefix, max_bytes):
    base, bucket, name, requested_generation = object_uri(uri, prefix)
    metadata = store.describe(uri)
    bundle.require(metadata['bucket'] == bucket and metadata['name'] == name,
                   'GCS metadata identity mismatch')
    generation = metadata['generation']
    bundle.require(isinstance(generation, str) and re.fullmatch(r'[0-9]+', generation)
                   and int(generation) > 0, 'invalid metadata generation')
    bundle.require(not requested_generation or generation == requested_generation,
                   'requested generation mismatch')
    size = bridge.integer(metadata['size'], 'GCS object size')
    bundle.require(size <= max_bytes, 'object exceeds requested byte limit')
    data = store.read(f'{base}#{generation}', size)
    bundle.require(len(data) == size, 'download size mismatch')
    if metadata.get('md5Hash'):
        # gcloud cat does not validate checksums. SHA-256 remains the bundle identity hash.
        md5 = base64.b64encode(hashlib.md5(data, usedforsecurity=False).digest()).decode('ascii')
        bundle.require(md5 == metadata['md5Hash'], 'GCS metadata checksum mismatch')
    return data, {'uri': base, 'generation': generation, 'size': size,
                  'sha256': bundle.digest(data)}


def stage(output, prefix, max_bytes, *, receipt=None, archive_positions=None, store=None):
    output = Path(output).absolute()
    bundle.require(bool(receipt) != bool(archive_positions), 'choose receipt or archive positions')
    bundle.require(type(max_bytes) is int and max_bytes > 0, 'max bytes must be positive')
    bundle.require(not any(p.is_symlink() for p in (output, *output.parents)), 'symlink output path')
    bundle.require(not any((p / '.git').exists() for p in (output, *output.parents)),
                   'staging must be outside a Git checkout')
    private_directory(output.parent)
    if output.exists():
        private_directory(output)
    store = store or GcloudStore()
    files, sources = {}, {}
    if receipt:
        path = Path(receipt)
        bundle.require(path.is_file() and not path.is_symlink(), 'invalid local receipt')
        bundle.require(path.stat().st_size <= max_bytes, 'receipt exceeds requested byte limit')
        files['source-receipt.json'] = path.read_bytes()
        bundle.require(len(files['source-receipt.json']) <= max_bytes, 'receipt exceeds requested byte limit')
        event = decode_json(files['source-receipt.json'])
        bundle.require(isinstance(event, dict), 'receipt must be an object')
        uris = {'positions': event['uri'], 'contracts': event['contractsUri']}
        evidence = 'PRODUCER_RECEIPT_VERIFIED'
    else:
        base, _, name, _ = object_uri(archive_positions, prefix)
        match = re.search(r'(^|/)(\d{4}-\d{2}-\d{2})/v(\d+)/seq-(\d+)\.csv$', name)
        bundle.require(match is not None, 'archive positions must follow date/vN/seq-N.csv naming')
        uris = {'positions': archive_positions, 'contracts': base[:-4] + '-contracts.csv',
                'cut': base[:-4] + '.cut'}
        evidence = 'ARCHIVE_ONLY_NO_COMPLETION_RECEIPT'
    for kind, uri in uris.items():
        data, sources[kind] = fetch(store, uri, prefix, max_bytes)
        files[f'{kind}.csv' if kind != 'cut' else 'source.cut'] = data
    payloads = {kind: files[f'{kind}.csv'] for kind in ('positions', 'contracts')}
    parsed = bundle.read_pair(payloads)
    if receipt:
        bridge.validate_event(event, payloads, event['sessionDate'])
        local_event = dict(event, uri=(output / 'positions.csv').as_uri(),
                           contractsUri=(output / 'contracts.csv').as_uri())
        files['local.ready.json'] = bundle.encoded(local_event)
    else:
        metadata = parsed['positions'][0]
        bundle.require(metadata['sessionDate'] == match[2]
                       and metadata['priceSnapshotVersion'] == match[3]
                       and metadata['consensusSequence'] == match[4], 'archive path/cut mismatch')
        bundle.require(bundle.digest(files['source.cut']) == metadata['cutSha256'],
                       'archived source cut hash mismatch')
    provenance = {'schema': 'traderx.gcs-stage.v1', 'evidence': evidence,
                  'sources': sources, 'cut': {k: parsed['positions'][0][k] for k in bundle.STAMP},
                  'rows': {k: len(v[1]) for k, v in parsed.items()},
                  'producerAuthenticated': False,
                  'clusterEpoch': None, 'valuationTime': None}
    if receipt:
        provenance['receiptSha256'] = bundle.digest(files['source-receipt.json'])
    files['source.json'] = bundle.encoded(provenance)
    duplicate = output.exists()
    if duplicate:
        bundle.require(output.is_dir() and {p.name for p in output.iterdir()} == set(files),
                       'existing staging files mismatch')
        for name, data in files.items():
            path = output / name
            bundle.require(path.is_file() and not path.is_symlink() and path.read_bytes() == data,
                           'existing staging content mismatch')
    else:
        bundle.publish_directory(output, files)
    return {'path': str(output), 'duplicate': duplicate, **provenance}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--receipt', help='local captured risk.extract.ready JSON with GCS URIs')
    mode.add_argument('--archive-positions', help='one historical positions object; never creates a receipt')
    parser.add_argument('--allowed-prefix', required=True, help='GCS bucket/path ending in /')
    parser.add_argument('--max-bytes', type=int, required=True, help='maximum bytes per object (up to three objects)')
    parser.add_argument('--output', required=True, help='private local staging directory outside Git')
    args = parser.parse_args()
    os.umask(0o077)
    try:
        result = stage(args.output, args.allowed_prefix, args.max_bytes,
                       receipt=args.receipt, archive_positions=args.archive_positions)
        print(json.dumps(result, indent=2))
    except (ValueError, OSError, KeyError, TypeError, csv.Error,
            subprocess.SubprocessError) as exc:
        parser.exit(1, f'error: {exc}\n')


if __name__ == '__main__':
    main()
