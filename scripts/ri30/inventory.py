#!/usr/bin/env python3
"""Freeze gcloud storage ls --json metadata; select explicit objects, never deduplicate rows."""
import argparse
import hashlib
import json
from pathlib import Path


def freeze(items, selected_names):
    objects = []
    seen = set()
    for item in items:
        m = item['metadata']
        if m['name'] not in selected_names:
            continue
        if m['name'] in seen:
            raise ValueError('duplicate input name')
        seen.add(m['name'])
        if not m.get('crc32c') or int(m['size']) <= 0 or int(m['generation']) <= 0:
            raise ValueError('missing checksum/size/generation')
        identity = f"gs://{m['bucket']}/{m['name']}#{m['generation']}"
        objects.append({'bucket': m['bucket'], 'name': m['name'], 'generation': int(m['generation']),
                        'size': int(m['size']), 'crc32c': m['crc32c'], 'storage_class': m['storageClass'],
                        'source_id': hashlib.sha256(identity.encode()).hexdigest()})
    if seen != set(selected_names) or not objects:
        raise ValueError('empty selection or selected source missing')
    return {'format': 'ri30-input-v1', 'objects': sorted(objects, key=lambda x: x['name'])}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('inventory'); p.add_argument('selection', help='JSON array of exact object names')
    p.add_argument('output')
    a = p.parse_args()
    result = freeze(json.loads(Path(a.inventory).read_text()), json.loads(Path(a.selection).read_text()))
    with open(a.output, 'x') as f:
        json.dump(result, f, indent=2)
        f.write('\n')
