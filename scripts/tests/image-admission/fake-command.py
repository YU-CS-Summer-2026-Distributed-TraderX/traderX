#!/usr/bin/env python3
"""Offline controls only: external commands never reach a real rig or Docker."""
import json
import os
from pathlib import Path
import subprocess
import sys

store = Path(os.environ['RI20_FIXTURE'])
tool = Path(sys.argv[0]).name
args = sys.argv[1:]
with (store / 'trace.jsonl').open('a') as file:
    file.write(json.dumps([tool, *args]) + '\n')
if tool == 'kubectl' and args[:1] == ['kustomize']:
    print((store / 'rendered.json').read_text())
elif tool == 'kubectl' and 'get' in args and args[args.index('get') + 1] == 'nodes':
    print((store / 'nodes.json').read_text())
elif tool == 'docker' and args[:1] == ['exec']:
    assert args[2:] == ['crictl', 'images', '-o', 'json'], args
    print((store / (args[1] + '.json')).read_text())
elif os.environ.get('RI20_LEGACY_BIN'):
    legacy = Path(os.environ['RI20_LEGACY_BIN']) / tool
    result = subprocess.run([str(legacy), *args], env=os.environ)
    sys.exit(result.returncode)
else:
    # Fake apply controls are only used for REFUSAL; reaching this fails the test.
    print('unexpected external command: ' + str([tool, *args]), file=sys.stderr)
    sys.exit(91)
