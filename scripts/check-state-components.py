#!/usr/bin/env python3
"""Validate component doc structure without treating planned work as implemented."""
import sys
from pathlib import Path
pack = Path(sys.argv[1])
required = ['README.md', 'spec.md', 'plan.md', 'tasks.md', 'quickstart.md',
            'system/architecture.model.json', 'generation/generation-hook.md']
missing = [str(pack / name) for name in required if not (pack / name).is_file()]
components = sorted(p for p in (pack / 'components').iterdir() if p.is_dir())
if not components:
    missing.append(str(pack / 'components/<component>'))
for component in components:
    for name in ['README.md', 'spec.md', 'plan.md', 'tasks.md']:
        path = component / name
        if not path.is_file() or not path.read_text().strip():
            missing.append(str(path))
    if (component / 'generation').exists():
        missing.append(f'{component}: generation inputs belong at the state parent')
if missing:
    raise SystemExit('Invalid state component layout:\n' + '\n'.join(missing))
print(f'PASS: {len(components)} component spec packs and shared state files present')
