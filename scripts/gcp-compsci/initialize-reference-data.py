#!/usr/bin/env python3
"""Explicit full-catalog CompSci initialization; no order submission or external data fetch."""
import argparse
import os
from pathlib import Path
import subprocess
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--apply',action='store_true')
a=p.parse_args()
cmd=['kubectl','--kubeconfig='+str(Path.home()/'.kube/traderx-compsci'),
     '--context=gke_project-compsci-484414_us-east1-b_traderx-demo','--request-timeout=30s',
     '-n', os.environ.get('TRADERX_NAMESPACE') or 'traderx','exec','-i','deployment/web-front-end-console','--','node','--input-type=module','-']
if a.apply:cmd.append('--apply')
raise SystemExit(subprocess.run(cmd,input=Path(__file__).with_suffix('.mjs').read_text(),text=True).returncode)
