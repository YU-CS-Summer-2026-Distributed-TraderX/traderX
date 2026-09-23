#!/usr/bin/env python3
"""Standalone v1 golden verifier. Uses stdlib only; does not import TraderX implementation."""
import hashlib
import json
from pathlib import Path
import sys


def encoded(value):
    return (json.dumps(value, sort_keys=True, indent=2, ensure_ascii=True, allow_nan=False)+'\n').encode('utf-8')


def sha(value):
    return hashlib.sha256(value).hexdigest()


def verify(root):
    root = Path(root)
    for path in sorted(root.rglob('*')):
        if path.is_file() and path.suffix in ('.json', '.csv') and b'\r\n' in path.read_bytes():
            raise ValueError(f'CRLF line endings in {path}: these fixtures require original LF bytes. '
                             'Check Git attributes and obtain a fresh checkout or pristine committed files; '
                             'do not regenerate expected hashes or normalize real input data.')
    expected = json.loads((root/'expected.json').read_bytes())
    assert expected['schema'] == 'traderx.golden-v1.1' and len(expected['cases']) == 2
    assert {c['name'] for c in expected['cases']} == {'basic', 'exchange'}
    for case in expected['cases']:
        folder = root/case['name']
        raw = (folder/'manifest.json').read_bytes()
        manifest = json.loads(raw)
        assert raw == encoded(manifest)
        assert sha(raw) == case['manifestFileSha256']
        assert manifest['bundleId'] == case['bundleId']
        body = {k:v for k,v in manifest.items() if k != 'bundleId'}
        assert encoded(body) == (folder/'bundle-preimage.json').read_bytes()
        assert sha(encoded(body)) == case['bundleId']
        for kind in ('positions', 'contracts'):
            assert sha((folder/(kind+'.csv')).read_bytes()) == case[kind+'Sha256'] == manifest['artifacts'][kind]['sha256']
        assert set(case['workloads']) == {'local', 'http'}
        for label, adapter in [('local','transport-mock-v1'),('http','http-transport-mock-draft-1')]:
            preimage = encoded({'bundleId':case['bundleId'], 'profile':{
                'adapter':adapter, 'calculations':['transport-check'],
                'marketInputs':'NOT_SUPPLIED', 'usableForRisk':False}})
            assert preimage == (folder/(label+'-workload-preimage.json')).read_bytes()
            assert sha(preimage) == case['workloads'][label]
    return len(expected['cases'])


if __name__ == '__main__':
    if not __debug__:
        raise SystemExit('Run without Python -O: verification requires assertions.')
    try:
        count = verify(Path(sys.argv[1]) if len(sys.argv)>1 else Path(__file__).parent/'tests/fixtures/golden-v1')
    except (ValueError, OSError, AssertionError) as exc:
        raise SystemExit('FAIL: '+(str(exc) or 'golden bytes or expected hashes differ'))
    print(f'PASS: {count} fixed v1 golden cases; both workload profiles verified')
