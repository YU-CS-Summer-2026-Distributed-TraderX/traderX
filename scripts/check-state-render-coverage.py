#!/usr/bin/env python3
"""Check a state's component-coverage table against the checkout and an empty-root render.

Every table row must name a spec and a source that exist in the checkout, and every render path must be
present: tg:<path> under <generated>/code/target-generated, cand:<Kind>/<name> in the CompSci candidate,
root:<path> in the checkout. Exits nonzero on the first table with a miss, listing every miss.
"""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def rows(table):
    for line in table.read_text().splitlines():
        cells = [c.strip() for c in line.strip().strip('|').split('|')]
        if len(cells) < 4 or not line.lstrip().startswith('|') or set(cells[0]) <= set('-') or cells[0] == 'Component':
            continue
        yield cells[0], [re.findall(r'`([^`]+)`', c) for c in cells[1:4]]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--state', default='YU18-risk-integration')
    p.add_argument('--generated', type=Path, required=True, help='the TRADERX_GENERATED_ROOT that was rendered into')
    p.add_argument('--candidate', type=Path, help='prepare.py candidate.yaml; required when the table has cand: rows')
    a = p.parse_args()
    table = ROOT / 'specs' / a.state / 'generation/component-coverage.md'
    target = a.generated / 'code/target-generated'
    resources = None
    if a.candidate:
        import yaml
        resources = {f"{d['kind']}/{d['metadata']['name']}" for d in yaml.safe_load_all(a.candidate.read_text()) if d}
    misses, count = [], 0
    for component, (specs, sources, renders) in rows(table):
        count += 1
        if not specs or not sources or not renders:
            misses.append(f'{component}: empty spec, source or render cell')
        for path in specs + sources:
            if not (ROOT / path).exists():
                misses.append(f'{component}: {path} not in the checkout')
        for ref in renders:
            kind, _, value = ref.partition(':')
            if kind == 'tg':
                ok = (target / value).exists()
            elif kind == 'root':
                ok = (ROOT / value).exists()
            elif kind == 'cand':
                if resources is None:
                    sys.exit('[fail] the table has cand: rows; pass --candidate')
                ok = value in resources
            else:
                ok = False
            if not ok:
                misses.append(f'{component}: {ref} not rendered')
    if not count:
        sys.exit(f'[fail] no rows parsed from {table}')
    if misses:
        sys.exit('[fail] coverage misses:\n' + '\n'.join(misses))
    print(f'PASS: {count} components, every spec, source and render path present')


if __name__ == '__main__':
    main()
