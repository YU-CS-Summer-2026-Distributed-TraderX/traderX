#!/usr/bin/env python3
"""Validate feature and maintenance spec packs without implying implementation."""
import sys
from pathlib import Path

pack = Path(sys.argv[1])
required = ['README.md', 'spec.md', 'plan.md', 'tasks.md', 'quickstart.md',
            'system/architecture.model.json', 'generation/generation-hook.md']
missing = [str(pack / name) for name in required if not (pack / name).is_file()]
counts = {}
seen = {}
for group in ('components', 'maintenance'):
    root = pack / group
    entries = sorted(p for p in root.iterdir() if p.is_dir()) if root.is_dir() else []
    counts[group] = len(entries)
    if group == 'components' and not entries:
        missing.append(str(root / '<component>'))
    if root.exists() and not (root / 'README.md').is_file():
        missing.append(str(root / 'README.md'))
    for entry in entries:
        if entry.name in seen:
            missing.append(f'{entry}: duplicates {seen[entry.name]}')
        seen[entry.name] = entry
        for name in ('README.md', 'spec.md', 'plan.md', 'tasks.md'):
            path = entry / name
            if not path.is_file() or not path.read_text().strip():
                missing.append(str(path))
        if (entry / 'generation').exists():
            missing.append(f'{entry}: generation inputs belong at the state parent')
if missing:
    raise SystemExit('Invalid state spec layout:\n' + '\n'.join(missing))
print(f"PASS: {counts['components']} feature packs, {counts['maintenance']} maintenance packs and shared state files present")
