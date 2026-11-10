#!/usr/bin/env python3
"""Latency L0: one table row per measurement window from l0-baseline.sh's evidence.
  python3 l0-report.py EVIDENCE probe.log [jvm-pauses.log]
Per window: the probe's client figure (from intended send), every gateway's segments (median over gateways, with the
lowest and highest gateway in brackets for p99), the leader's commit/apply, the leader's busiest threads (cores over
the window) and the leader's JVM pauses that fell inside it (safepoint total, count and max). Percentiles are per
segment; they do not add up across segments.
"""
import os, re, statistics, sys
from datetime import datetime, timezone

ev, probe_log = sys.argv[1], sys.argv[2]
pauses_log = sys.argv[3] if len(sys.argv) > 3 else None
SEG = re.compile(r'segment="(\w+)",pct="(\w+)"\} ([\d.]+)')
COUNT = re.compile(r'latency_count\{segment="(\w+)"\} (\d+)')


def segs(text):
    out = {}
    for s, p, v in SEG.findall(text):
        out.setdefault(s, {})[p] = float(v)
    for s, n in COUNT.findall(text):
        out.setdefault(s, {})['n'] = int(n)
    return out


def gateways(path):
    # a gateway no probe or load session landed on has an empty histogram (all zeros): left out
    blocks = [segs(b) for b in open(path).read().split('== ')[1:] if 'segment=' in b]
    return [b for b in blocks if b.get('total', {}).get('n', 0) >= 5]


probe = {}
for line in open(probe_log):
    if line.startswith('PROBE-WINDOW'):
        kv = dict(re.findall(r'(\S+?)=(\S+)', line.split('|')[0]))
        probe[kv['at'][:19]] = kv

pauses = []
if pauses_log:
    # [2026-11-10T17:05:01.123+0000][info][safepoint] Safepoint "ZMarkStart", ... Total: 123456 ns
    for line in open(pauses_log):
        m = re.match(r'\[(\S+?)\]\[info\]\[safepoint\s*\].*Total: (\d+) ns', line)
        if m:
            t = datetime.strptime(m.group(1)[:23], '%Y-%m-%dT%H:%M:%S.%f').replace(tzinfo=timezone.utc).timestamp()
            pauses.append((t, int(m.group(2)) / 1e3))


def cpu(start, end, secs):
    by = {}   # threads that share a name (bridge lanes, ZGC workers) are summed under it
    for f, sign in ((start, -1), (end, 1)):
        for line in open(f):
            name, ticks = line.rsplit(' ', 1)
            by[name] = by.get(name, 0) + sign * int(ticks) / 100 / secs
    top = sorted(by.items(), key=lambda x: -x[1])[:6]
    return sum(by.values()), ', '.join(f'{n} {c:.2f}' for n, c in top)


print('| block | window (UTC) | probe p50/p99/p99.9 µs | gw total p50/p99/p999 | gw cluster p50/p99/p999 (p99 range) | '
      'gw queue p99 | leader commit p50/p99/p999 | apply p50/p99 | leader cores (top threads) | safepoints n / sum / max µs |')
print('|' + '---|' * 10)
for block in sorted(os.listdir(ev)):
    d = os.path.join(ev, block)
    if not os.path.isdir(d):
        continue
    for w in sorted(f for f in os.listdir(d) if f.startswith('window-')):
        i = w[len('window-'):-len('.at')]
        t0, t1, at = open(os.path.join(d, w)).read().split()
        g = gateways(os.path.join(d, f'gw-{i}.txt'))
        med = lambda s, p: statistics.median(x[s][p] for x in g if s in x)
        rng = lambda s, p: (min(x[s][p] for x in g if s in x), max(x[s][p] for x in g if s in x))
        ld = segs(open(os.path.join(d, f'leader-{i}.txt')).read())
        cores, top = cpu(os.path.join(d, f'threads-{i}-start.txt'), os.path.join(d, f'threads-{i}-end.txt'),
                         int(t1) - int(t0))
        pr = probe.get(at[:19], {})
        inside = [us for t, us in pauses if int(t0) <= t < int(t1)]
        lo, hi = rng('cluster', 'p99')
        print(f"| {block} | {at[11:19]} | {pr.get('p50', '-')}/{pr.get('p99', '-')}/{pr.get('p99.9', '-')} (n={pr.get('count', 0)}) "
              f"| {med('total', 'p50'):.0f}/{med('total', 'p99'):.0f}/{med('total', 'p999'):.0f} "
              f"| {med('cluster', 'p50'):.0f}/{med('cluster', 'p99'):.0f}/{med('cluster', 'p999'):.0f} ({lo:.0f}–{hi:.0f}) "
              f"| {med('queue', 'p99'):.0f} (gw {len(g)}, n={sum(x['total']['n'] for x in g)}) "
              f"| {ld['commit']['p50']:.0f}/{ld['commit']['p99']:.0f}/{ld['commit']['p999']:.0f} "
              f"| {ld['apply']['p50']:.2f}/{ld['apply']['p99']:.2f} | {cores:.2f} ({top}) "
              f"| {len(inside)} / {sum(inside):.0f} / {max(inside, default=0):.0f} |")
