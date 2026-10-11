#!/usr/bin/env python3
"""Provision the explicit CompSci profile, keeping values out of output/argv.

Credentials are saved outside the checkout with owner-only permissions. Existing
Secrets must match the saved credentials; this tool never rotates them implicitly.
An uncertain HMAC creation is recorded before submission and never retried.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys

PROJECT = 'project-compsci-484414'
ACCOUNT = 'aseif@mail.yu.edu'
CONTEXT = 'gke_project-compsci-484414_us-east1-b_traderx-demo'
WRITER = 'traderx-risk-extract@project-compsci-484414.iam.gserviceaccount.com'


def private_write(path, value):
    if path.is_symlink(): raise ValueError('private path must not be a symlink')
    temp = path.with_name(path.name + '.pending')
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(value, stream, indent=2); stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
    os.replace(temp, path)


def run(args, data=None):
    result = subprocess.run(args, input=data, capture_output=True, text=True, timeout=120)
    if result.returncode:
        # CLI errors can include submitted objects; keep diagnostics private.
        raise RuntimeError(f'{args[0]} failed (exit {result.returncode}); secret values suppressed')
    return json.loads(result.stdout) if result.stdout.strip().startswith(('{','[')) else result.stdout


def new_credentials():
    password = secrets.token_urlsafe(24); salt = secrets.token_hex(16)
    digest = hashlib.scrypt(password.encode(), salt=salt.encode(), n=16384, r=8, p=1, dklen=32).hex()
    return {'console': {'username':'admin', 'password':password},
            'grafana': {'username':'admin', 'password':secrets.token_urlsafe(24)},
            'secrets': {
                'auth-secrets': {'console-admin-hash':f'scrypt${salt}${digest}',
                    **{key:secrets.token_hex(32) for key in ['console-session-secret','dev-token-master-secret','jwt-secret','risk-control-token']}},
                'mariadb-credentials': {'username':'traderx','password':secrets.token_urlsafe(32),
                                       'root-password':secrets.token_urlsafe(32)},
            }}


def provision(private_dir, kubeconfig, apply):
    root = Path(__file__).resolve().parents[2]
    private_dir = private_dir.expanduser().resolve()
    if private_dir.is_relative_to(root): raise ValueError('credentials must be outside the checkout')
    private_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    if private_dir.stat().st_mode & 0o077: raise ValueError('private directory must have mode 0700')
    path = private_dir / 'credentials.json'
    if path.exists():
        if path.is_symlink() or path.stat().st_mode & 0o077: raise ValueError('credentials must have mode 0600')
        credentials = json.loads(path.read_text())
    else:
        credentials = new_credentials(); private_write(path, credentials)
    credentials['secrets']['grafana-credentials'] = {'admin-password':credentials['grafana']['password']}
    private_write(path, credentials)
    if not apply:
        print('Private application credentials saved; no cloud mutation.'); return
    k = ['kubectl', '--kubeconfig='+str(kubeconfig.expanduser()), '--context='+CONTEXT,
         '--request-timeout=30s','-n','traderx']
    # Check target before creating any credentials in it.
    ns = run(k+['get','namespace','traderx','-o','json'])
    if ns['metadata']['name'] != 'traderx': raise ValueError('namespace mismatch')
    g = ['gcloud','--project='+PROJECT,'--account='+ACCOUNT]
    hmac_path = private_dir/'extract-hmac.json'
    if not hmac_path.exists():
        keys = run(g+['storage','hmac','list','--service-account='+WRITER,'--format=json'])
        if keys: raise ValueError('writer has existing HMAC keys; reconcile existing credentials before creation')
        private_write(hmac_path, {'state':'creation-started','serviceAccount':WRITER})
        result = run(g+['storage','hmac','create',WRITER,'--format=json'])
        # Preserve the entire one-time result privately before interpreting it.
        private_write(hmac_path, {'state':'created','result':result})
    saved = json.loads(hmac_path.read_text())
    if saved.get('state') != 'created': raise ValueError('uncertain HMAC creation; GET-only reconciliation required')
    result = saved['result']
    metadata = result.get('metadata',result)
    access = metadata.get('accessId') or metadata.get('access_id')
    secret = result.get('secret') or metadata.get('secret')
    if not access or not secret: raise ValueError('one-time HMAC result shape unsupported; preserved privately, no retry')
    credentials['secrets']['risk-extract-gcs-hmac'] = {'access-key-id':access, 'secret-access-key':secret}
    private_write(path, credentials)
    inventory = run(k+['get','secrets','-o','json'])
    existing = {s['metadata']['name']:s for s in inventory['items']}
    for name, values in credentials['secrets'].items():
        encoded = {key:base64.b64encode(value.encode()).decode() for key,value in values.items()}
        if name in existing:
            if existing[name].get('data') != encoded: raise ValueError('existing Secret differs; implicit rotation refused: '+name)
        else:
            obj={'apiVersion':'v1','kind':'Secret','metadata':{'name':name,'namespace':'traderx'},
                 'type':'Opaque','data':encoded}
            run(k+['create','-f','-','-o','name'],json.dumps(obj))
        actual = run(k+['get','secret',name,'-o','json'])
        if actual.get('data') != encoded: raise ValueError('Secret content verification failed: '+name)
        print('[verified] '+name+' keys='+','.join(sorted(values)))
    print('Private credential file: '+str(path))


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--private-dir',type=Path,required=True)
    p.add_argument('--kubeconfig',type=Path,required=True)
    p.add_argument('--apply',action='store_true')
    a=p.parse_args()
    try: provision(a.private_dir,a.kubeconfig,a.apply)
    except Exception as error:
        # Only local controlled failures are disclosed. Never echo captured CLI responses.
        print('[failed] '+str(error) if isinstance(error,(ValueError,RuntimeError)) else
              '[failed] '+type(error).__name__+'; private state preserved, no automatic retry',file=sys.stderr)
        sys.exit(1)
