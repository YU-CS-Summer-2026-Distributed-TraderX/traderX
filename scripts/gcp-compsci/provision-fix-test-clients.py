#!/usr/bin/env python3
"""ADR-096 (FR-PRG-11): passwords for the rehearsal's load CompIDs (prepare.py FIX_TEST_CLIENTS), and Secret
`fix-test-clients` (one key per CompID, lower case). Generates once into <private-dir>/fix-test-clients.json (mode 0600,
outside the checkout) for the load clients; re-running reuses it. With --apply, creates the Secret through stdin, never
argv; an existing Secret must match, never rotated implicitly (provision-secrets.py's rule). Prints no password.
"""
import argparse
import base64
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('provision_prepare', HERE / 'prepare.py')
prepare = importlib.util.module_from_spec(spec); spec.loader.exec_module(prepare)
CONTEXT = 'gke_project-compsci-484414_us-east1-b_traderx-demo'


def kubectl(k, args, data=None):
    r = subprocess.run(k + args, input=data, capture_output=True, text=True, timeout=60)
    if r.returncode:
        raise RuntimeError(f'kubectl {args[0]} failed (exit {r.returncode}); secret values suppressed')
    return r.stdout


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--private-dir', type=Path, required=True)
    p.add_argument('--context', default=CONTEXT)
    p.add_argument('--namespace', default='traderx')
    p.add_argument('--apply', action='store_true')
    p.add_argument('--count', type=int, default=7, help='CompIDs CLIENT2..; must match prepare.py --fix-test-clients N')
    a = p.parse_args()
    d = a.private_dir.expanduser().resolve()
    if d.is_relative_to(HERE.parents[1]):
        raise ValueError('credentials must be outside the checkout')
    d.mkdir(mode=0o700, parents=True, exist_ok=True)
    path = d / 'fix-test-clients.json'
    want = prepare.load_clients(a.count)
    clients = json.loads(path.read_text()) if path.exists() else {}
    if any(clients.get(c, {}).get('account', acct) != acct for c, acct in want.items()) or set(clients) - set(want):
        raise ValueError(f'{path} does not match {a.count} load CompIDs')
    added = [c for c in want if c not in clients]   # growing keeps every existing password
    if added:   # hex, so a password never carries FIX_SESSION_AUTH's ':' or ','
        clients.update({c: {'account': want[c], 'password': secrets.token_hex(32)} for c in added})
        tmp = path.with_suffix('.tmp')
        with os.fdopen(os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as f:
            json.dump(clients, f, indent=2)
        os.replace(tmp, path)
        print(f'[generated] {len(added)} CompID passwords in {path} ({len(clients)} in all)')
    if not a.apply:
        print('No cluster mutation (--apply not given).')
        return
    data = {c.lower(): base64.b64encode(v['password'].encode()).decode() for c, v in clients.items()}
    k = ['kubectl', f'--context={a.context}', '--request-timeout=30s', '-n', a.namespace]
    if 'secret/fix-test-clients' in kubectl(k, ['get', 'secrets', '-o', 'name']).split():
        have = json.loads(kubectl(k, ['get', 'secret', 'fix-test-clients', '-o', 'json'])).get('data') or {}
        if any(data.get(key) != v for key, v in have.items()):
            raise ValueError('existing Secret fix-test-clients differs; implicit rotation refused')
        if have != data:   # only new CompIDs are added
            kubectl(k, ['apply', '-f', '-'], json.dumps({'apiVersion': 'v1', 'kind': 'Secret', 'type': 'Opaque',
                    'data': data, 'metadata': {'name': 'fix-test-clients', 'namespace': a.namespace}}))
    else:
        obj = {'apiVersion': 'v1', 'kind': 'Secret', 'type': 'Opaque', 'data': data,
               'metadata': {'name': 'fix-test-clients', 'namespace': a.namespace}}
        kubectl(k, ['create', '-f', '-', '-o', 'name'], json.dumps(obj))
    print(f'[verified] {a.context}/{a.namespace} secret/fix-test-clients keys=' + ','.join(sorted(data)))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f'[failed] {error}', file=sys.stderr)
        sys.exit(1)
