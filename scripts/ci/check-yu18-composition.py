#!/usr/bin/env python3
"""Fail closed on missing/stale YU18 overrides or absent typed-order JUnit evidence."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--generated', type=Path, default=ROOT/'generated/code/target-generated')
p.add_argument('--junit', action='store_true', help='also require typed suites after engine/service tests')
a = p.parse_args()
source = ROOT/'specs/YU18-risk-integration/generation/runtime-overrides'
files = [f for f in source.rglob('*') if f.is_file() and '__pycache__' not in f.parts and f.suffix != '.pyc']
if not files:
    raise SystemExit('FAIL: no YU18 source overrides')
for f in files:
    dest = a.generated/f.relative_to(source)
    if not dest.is_file() or dest.read_bytes() != f.read_bytes():
        raise SystemExit(f'FAIL: missing/stale generated override: {dest}')
print(f'PASS: {len(files)} YU18 source/generated files match')
if a.junit:
    required = {
        'order-matcher': ['OrderTypesEngineTest', 'OrderTypesServiceTest', 'OrderTypesBoundaryTest',
                          'OrderTypesBaselineTest', 'OrderTypesSessionBaselineTest',
                          'OrderTypesAllocationGateTest'],
        'trade-processor': ['OrderTypesReadModelTest'],
    }
    for module, classes in required.items():
        for name in classes:
            matches = list((a.generated/module/'build/test-results').glob(f'*/TEST-*.{name}.xml'))
            if len(matches) != 1:
                raise SystemExit(f'FAIL: expected one result for {module}/{name}, found {len(matches)}')
            suite = ET.parse(matches[0]).getroot()
            cases = suite.findall('testcase')
            if (not cases or int(suite.get('tests', '0')) != len(cases)
                or any(int(suite.get(k, '0')) for k in ('failures', 'errors', 'skipped'))
                or any(c.find(tag) is not None for c in cases for tag in ('failure', 'error', 'skipped'))):
                raise SystemExit(f'FAIL: incomplete/failing typed suite: {matches[0]}')
            print(f'PASS: {module}/{name}: {len(cases)} executed')
