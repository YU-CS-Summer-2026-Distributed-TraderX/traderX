#!/usr/bin/env python3
"""ADR-096 (FR-PRG-06): each FIX session the drain logged out, and its next logon on a remaining gateway.

fix-resume.py <evidence dir>: reads drained-*.log (each drained gateway, followed from its start) and remaining-*.log
(the gateways left). Only logouts at or after that gateway's `GATEWAY-DRAIN begin` count: the log also holds every
earlier logout of its life (production 2026-10-29: smoke and load-run logouts reported as 805 s "resumes").
"""
import glob
import re
import sys
from datetime import datetime


def ts(line):
    return datetime.fromisoformat(line.split(' ', 1)[0].replace('Z', '+00:00')[:26] + '+00:00')


def report(evidence):
    out, back = [], []
    for f in glob.glob(evidence + '/drained-*.log'):
        lines = open(f).read().splitlines()
        begin = next((ts(l) for l in lines if 'GATEWAY-DRAIN begin' in l), None)
        if begin is None:
            yield f'{f}: no GATEWAY-DRAIN begin'; continue
        out += [(ts(l), m.group(1)) for l in lines if (m := re.search(r'FIX logout: (\S+)', l)) and ts(l) >= begin]
    for f in glob.glob(evidence + '/remaining-*.log'):
        back += [(ts(l), m.group(1)) for l in open(f) if (m := re.search(r'FIX logon: (\S+)', l))]
    if not out:
        yield 'no FIX session was logged on at the drained gateways'; return
    worst = 0
    for t, comp in sorted(out):
        nxt = min((b for b, c in back if c == comp and b >= t), default=None)
        if nxt is None:
            yield f'{comp}: logged out {t:%H:%M:%S.%f}Z, not back on a remaining gateway yet'; continue
        ms = (nxt - t).total_seconds() * 1000; worst = max(worst, ms)
        yield f'{comp}: logout -> logon on a remaining gateway {ms:.0f} ms'
    yield f'sessions {len(out)}, worst resume {worst:.0f} ms'


if __name__ == '__main__':
    for line in report(sys.argv[1]):
        print(line)
