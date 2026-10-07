#!/usr/bin/env python3
"""Offline command fixture. Never delegates to a real executable."""
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
name = Path(sys.argv[0]).name
with open(os.environ['FAKE_TRACE'], 'a') as f:
    f.write(json.dumps({'command': name, 'args': args}) + '\n')
fixture = json.loads(Path(os.environ['FAKE_FIXTURE']).read_text())
if name == 'gcloud':
    sys.exit('gcloud must never be invoked')
for rule in fixture.get('rules', []):
    if name == rule.get('command', 'kubectl') and all(v in args for v in rule['contains']):
        if '-' in args and ('apply' in args):
            with open(os.environ['FAKE_TRACE'], 'a') as f:
                f.write(json.dumps({'stdin': sys.stdin.read()}) + '\n')
        output = rule.get('output', '')
        if output == 'auto-configmap':
            literal = next(a for a in args if a.startswith('--from-literal=epochStartMs='))
            output = {'apiVersion':'v1','kind':'ConfigMap','metadata':{'name':'replay-epoch', 'namespace':args[args.index('-n')+1]}, 'data':{'epochStartMs':literal.split('=',2)[2]}}
        print(json.dumps(output) if isinstance(output, (dict, list)) else output, end='')
        sys.exit(rule.get('rc', 0))
sys.exit('unexpected offline command: ' + repr((name, args)))
