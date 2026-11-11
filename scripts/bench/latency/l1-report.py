#!/usr/bin/env python3
"""Latency L1/L2 (FR-OPL-06): the leader's state per window from l0-baseline.sh's state-W-{start,end}.txt, beside
l0-report.py's latency table.
  python3 l1-report.py EVIDENCE
Per window: CFS throttling (periods throttled, ms throttled), the Aeron agents' max cycle time where it rose inside the
window (Aeron's max is since start, so a value set during recovery shows in no window), cycles over the threshold in the
window, back pressure in the window, NET_RX softirqs on the member's own cores vs the rest, and the hot agents' CPUs
with any pair sharing a physical core (SMT siblings). NIC IRQs are not reported: the container runtime masks
/proc/interrupts (empty on GKE, 2026-11-10); NET_RX shows where receive work runs.
"""
import os, re, sys

HOT = ('sender', 'receiver', 'consensus-modul', 'clustered-servi', 'archive-record', 'archive-conduct', 'driver-conduct')
MAX = re.compile(r'^(.*?) max cycle time', re.I)
EXCEEDED = re.compile(r'(work cycle (time )?exceeded|cycle time exceeded)', re.I)
BACKPRESSURE = re.compile(r'(back-pressure|backpressure|short sends)', re.I)


def sections(path):
    out, cur = {}, None
    for line in open(path):
        line = line.rstrip('\n')
        if line.startswith('== '):
            cur = out.setdefault(line[3:], [])
        elif cur is not None:
            cur.append(line)
    return out


def cpus(spec):
    got = set()
    for part in spec.strip().split(','):
        if part:
            a, _, b = part.partition('-')
            got.update(range(int(a), int(b or a) + 1))
    return got


def counters(lines):
    out = {}
    for line in lines:
        parts = line.split(' ', 3)
        if len(parts) == 4 and parts[0].isdigit():
            out[parts[3]] = int(parts[2])
    return out


def per_cpu(lines, match):
    """Sum per CPU over the /proc/interrupts or /proc/softirqs rows whose label matches."""
    head = next((l.split() for l in lines if l.strip().startswith('CPU')), [])
    tot = [0] * len(head)
    for l in lines:
        f = l.split()
        if f and not l.strip().startswith('CPU') and re.search(match, l):
            for i, v in enumerate(f[1:1 + len(head)]):
                if v.isdigit():
                    tot[i] += int(v)
    return tot


def split(start, end, mine):
    d = [e - s for s, e in zip(start, end)]
    return sum(v for i, v in enumerate(d) if i in mine), sum(v for i, v in enumerate(d) if i not in mine)


ev = sys.argv[1]
print('| block | w | throttled periods / ms | max cycle µs (new in window) | cycles > threshold | back pressure | '
      'NET_RX own/other | hot agents → CPU (SMT pairs) |')
print('|' + '---|' * 8)
for block in sorted(os.listdir(ev)):
    d = os.path.join(ev, block)
    if not os.path.isdir(d):
        continue
    for w in sorted(f[6:-10] for f in os.listdir(d) if f.startswith('state-') and f.endswith('-start.txt')):
        if not os.path.exists(os.path.join(d, f'state-{w}-end.txt')):
            continue
        a, b = sections(os.path.join(d, f'state-{w}-start.txt')), sections(os.path.join(d, f'state-{w}-end.txt'))
        st = {k: int(v) for k, v in (l.split() for l in a.get('cpu.stat', []))}
        en = {k: int(v) for k, v in (l.split() for l in b.get('cpu.stat', []))}
        thr = f"{en.get('nr_throttled', 0) - st.get('nr_throttled', 0)} / " \
              f"{(en.get('throttled_usec', 0) - st.get('throttled_usec', 0)) / 1e3:.1f}"
        mine = cpus((b.get('cpuset') or [''])[0])
        ca, cb = counters(a.get('counters', [])), counters(b.get('counters', []))
        maxes = ', '.join(f"{MAX.match(k).group(1).lower()} {v / 1e3:.0f}" for k, v in cb.items()
                          if MAX.match(k) and v > ca.get(k, 0))
        over = ', '.join(f"{re.split(r' work cycle', k, flags=re.I)[0].lower()} {cb[k] - ca.get(k, 0)}" for k in cb
                         if EXCEEDED.search(k) and cb[k] - ca.get(k, 0))
        bp = sum(cb[k] - ca.get(k, 0) for k in cb if BACKPRESSURE.search(k))
        rx = split(per_cpu(a.get('softirqs', []), r'NET_RX'), per_cpu(b.get('softirqs', []), r'NET_RX'), mine)
        sib = {}
        for l in b.get('siblings', []):
            group = cpus(l)
            for c in group:
                sib[c] = group
        hot = {}
        for l in b.get('psr', []):
            name, _, cpu = l.rpartition(' ')
            if name.lower().startswith(HOT) and cpu.isdigit():
                hot.setdefault(name, int(cpu))
        pairs = [f'{x}+{y}' for x in hot for y in hot if x < y and (hot[x] == hot[y] or hot[y] in sib.get(hot[x], ()))]
        print(f"| {block} | {w} | {thr} | {maxes or '-'} | {over or '0'} | {bp} | {rx[0]}/{rx[1]} | "
              f"{', '.join(f'{n} {c}' for n, c in hot.items())}{' (' + ', '.join(pairs) + ')' if pairs else ''} |")
