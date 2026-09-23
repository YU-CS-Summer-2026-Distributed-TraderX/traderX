#!/usr/bin/env python3
"""Repeat a synthetic market-input package build without network or pricing."""
import argparse
from pathlib import Path
import sys
import tempfile

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--component', type=Path, default=Path(__file__).resolve().parents[1]/'specs/YU18-risk-integration/generation/runtime-overrides/eod-risk-bundles')
args=parser.parse_args()
sys.path.insert(0,str(args.component.resolve()))
import market_inputs
fixture=args.component/'tests/fixtures/market-inputs'
root=Path(tempfile.mkdtemp(prefix='traderx-market-inputs-'))
a=market_inputs.build(fixture/'metadata.json',fixture/'observations.json',root/'first')
b=market_inputs.build(fixture/'metadata.json',fixture/'observations.json',root/'repeat')
assert a==b==market_inputs.validate(root/'first')
assert a[0]['packageId']==(fixture/'expected-package-id.txt').read_text().strip()
assert a[1]['suitableForSelection'] and not a[1]['usableForRisk']
print(f'Synthetic observation package verified: {a[0]["packageId"]}\nEvidence: {root}\nPricing readiness: NOT_ASSESSED; usableForRisk=false')
