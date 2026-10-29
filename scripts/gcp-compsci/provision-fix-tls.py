#!/usr/bin/env python3
"""ADR-095: the FIX-over-TLS entry's certificate, keystore and external CompID password, and its `fix-tls` Secret.

Generates once into <private-dir>/fix-tls (mode 0700, outside the checkout): an EC P-256 certificate for the entry's
hostname, the gateway's PKCS12 keystore, the client's PKCS12 trust store holding exactly that certificate (QuickFIX/J
2.3 checks no hostname, so the trust store is the pin), and client.json with the passwords. Re-running reuses them.
With --apply, creates Secret `fix-tls` (keys keystore.p12, keystore-password, external-password) through stdin, never
argv; an existing Secret must match, it is never rotated implicitly (provision-secrets.py's rule).
"""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys

CONTEXT = 'gke_project-compsci-484414_us-east1-b_traderx-demo'
KEYTOOL = shutil.which('keytool') or 'keytool'


def keytool(*args):
    subprocess.run([KEYTOOL, *args], check=True, capture_output=True, timeout=60)


def generate(d, hostname, days):
    # keytool takes -storepass on argv; this runs only on the operator's machine, once, before the files exist.
    ks_pw, trust_pw = secrets.token_hex(16), secrets.token_hex(16)
    keytool('-genkeypair', '-alias', 'fix', '-keyalg', 'EC', '-groupname', 'secp256r1', '-validity', str(days),
            '-dname', f'CN={hostname}', '-ext', f'SAN=dns:{hostname}', '-storetype', 'PKCS12',
            '-keystore', str(d / 'keystore.p12'), '-storepass', ks_pw)
    keytool('-exportcert', '-rfc', '-alias', 'fix', '-keystore', str(d / 'keystore.p12'), '-storepass', ks_pw,
            '-file', str(d / 'server.crt'))
    keytool('-importcert', '-noprompt', '-alias', 'fix', '-file', str(d / 'server.crt'), '-storetype', 'PKCS12',
            '-keystore', str(d / 'truststore.p12'), '-storepass', trust_pw)
    # Hex, so the password never carries FIX_SESSION_AUTH's ':' or ','.
    client = {'hostname': hostname, 'sender': 'EXTQFJ', 'password': secrets.token_hex(32),
              'keystorePassword': ks_pw, 'truststorePassword': trust_pw}
    fd = os.open(d / 'client.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as f:
        json.dump(client, f, indent=2)
    for name in ('keystore.p12', 'truststore.p12', 'server.crt'):
        os.chmod(d / name, 0o600)


def kubectl(k, args, data=None):
    r = subprocess.run(k + args, input=data, capture_output=True, text=True, timeout=60)
    if r.returncode:
        raise RuntimeError(f'kubectl {args[0]} failed (exit {r.returncode}); secret values suppressed')
    return r.stdout


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--private-dir', type=Path, required=True)
    p.add_argument('--hostname', default='fix.yaakovseif.dev')
    p.add_argument('--days', type=int, default=397)
    p.add_argument('--context', default=CONTEXT)
    p.add_argument('--namespace', default='traderx')
    p.add_argument('--apply', action='store_true')
    a = p.parse_args()
    root = Path(__file__).resolve().parents[2]
    d = a.private_dir.expanduser().resolve() / 'fix-tls'
    if d.is_relative_to(root):
        raise ValueError('credentials must be outside the checkout')
    d.mkdir(mode=0o700, parents=True, exist_ok=True)
    if d.stat().st_mode & 0o077:
        raise ValueError(f'{d} must have mode 0700')
    if not (d / 'client.json').exists():
        generate(d, a.hostname, a.days)
        print(f'[generated] {a.hostname} certificate, keystore and pinned trust store in {d}')
    client = json.loads((d / 'client.json').read_text())
    if client['hostname'] != a.hostname:
        raise ValueError(f"saved certificate is for {client['hostname']}, not {a.hostname}")
    if not a.apply:
        print('No cluster mutation (--apply not given).')
        return
    data = {'keystore.p12': base64.b64encode((d / 'keystore.p12').read_bytes()).decode(),
            'keystore-password': base64.b64encode(client['keystorePassword'].encode()).decode(),
            'external-password': base64.b64encode(client['password'].encode()).decode()}
    k = ['kubectl', f'--context={a.context}', '--request-timeout=30s', '-n', a.namespace]
    names = kubectl(k, ['get', 'secrets', '-o', 'name']).split()
    if 'secret/fix-tls' in names:
        if json.loads(kubectl(k, ['get', 'secret', 'fix-tls', '-o', 'json'])).get('data') != data:
            raise ValueError('existing Secret fix-tls differs; implicit rotation refused')
    else:
        obj = {'apiVersion': 'v1', 'kind': 'Secret', 'type': 'Opaque', 'data': data,
               'metadata': {'name': 'fix-tls', 'namespace': a.namespace}}
        kubectl(k, ['create', '-f', '-', '-o', 'name'], json.dumps(obj))
    print(f'[verified] {a.context}/{a.namespace} secret/fix-tls keys=' + ','.join(sorted(data)))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f'[failed] {error}', file=sys.stderr)
        sys.exit(1)
