#!/usr/bin/env python3
"""Synthetic external build tools only; no real compilation/image store."""
import hashlib,json,os
from pathlib import Path
import subprocess,sys
store=Path(os.environ['RI18_FIXTURE_STORE']);tool=Path(sys.argv[0]).name;args=sys.argv[1:]
if tool=='docker' and args[:1]==['build'] and '--label' not in args:
 with (store/'trace.jsonl').open('a') as f:f.write(json.dumps([tool,*args])+'\n')
 (store/'generic-built').write_text(args[-1]);sys.exit(0)
legacy=Path(os.environ['RI20_LEGACY_TOOLS'])/tool
sys.exit(subprocess.run([str(legacy),*args],env=os.environ).returncode)
