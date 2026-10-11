#!/usr/bin/env python3
"""Explicit CompSci cold-demo initialization; default is read-only verification."""
import argparse
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--apply', action='store_true', help='Admit absent directory accounts; preserve explicit suspensions')
a = p.parse_args()
cmd = ['kubectl', '--kubeconfig=' + str(Path.home()/'.kube/traderx-compsci'),
       '--context=gke_project-compsci-484414_us-east1-b_traderx-demo', '--request-timeout=30s',
       '-n', 'traderx', 'exec', '-i', 'deployment/web-front-end-console', '--',
       'node', '--input-type=module', '-']
if a.apply:
    cmd.append('--apply')
raise SystemExit(subprocess.run(cmd, input=Path(__file__).with_suffix('.mjs').read_text(), text=True).returncode)
