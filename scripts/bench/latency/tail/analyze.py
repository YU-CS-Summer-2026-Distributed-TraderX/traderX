"""latency-tail: per-arm stage lags from HxProbe (1 ms) on each member, tagged by that member's own GC cycles.
   python3 analyze.py EVIDENCE LABEL [RATE=300]"""
import sys, re, os, glob, subprocess, tarfile, bisect, calendar, time
ev, label = sys.argv[1], sys.argv[2]; rate = sys.argv[3] if len(sys.argv) > 3 else '300'
here = os.path.dirname(os.path.abspath(__file__)); td = f'{ev}/tail-{label}'
def pct(a, p): a = sorted(a); return a[min(len(a) - 1, int(len(a) * p))] if a else -1
def ts(s): return calendar.timegm(time.strptime(s[:19], '%Y-%m-%dT%H:%M:%S')) * 1_000_000 + int(s[20:23]) * 1000
def gc(m):
    cyc, sp, open_ = [], [], {}
    for l in open(f'{td}/member-{m}.log', errors='replace'):
        mm = re.match(r'\[(\S+?)\+0000\]\[info\]\[gc\s*\] GC\((\d+)\) (Minor|Major) Collection \(([^)]*)\)(.*)', l)
        if mm:
            t = ts(mm.group(1))
            if '->' in mm.group(5): cyc.append((open_.pop(mm.group(2), t), t, mm.group(3)))
            else: open_[mm.group(2)] = t
            continue
        mm = re.match(r'\[(\S+?)\+0000\]\[info\]\[safepoint\s*\] Safepoint "(\w+)".*Reaching safepoint: (\d+) ns.*Total: (\d+) ns', l)
        if mm: sp.append((ts(mm.group(1)), mm.group(2), int(mm.group(3)), int(mm.group(4))))
    return sorted(cyc), sp
def load(m):
    d = f'{td}/hx-m{m}'
    if not os.path.exists(d + '/counters.bin'):
        os.makedirs(d, exist_ok=True); tarfile.open(f'{td}/hxprobe-m{m}.tgz').extractall(d)
    out = f'{d}/sel.tsv'
    if not os.path.exists(out):
        subprocess.run(['java', f'{here}/Decode.java', d, r'alias=log|^203 |^205 |^100 |^10[5-7] |exceeded|max cycle|max write', out], check=True)
    f = open(out); hdr = f.readline().rstrip('\n').split('\t')
    cols = [[] for _ in hdr]
    for l in f:
        p = l.split('\t')
        if len(p) == len(hdr):
            for i, x in enumerate(p): cols[i].append(int(x))
    return hdr, cols
def col(hdr, cols, *need, no=()):
    c = [i for i, h in enumerate(hdr) if all(n in h for n in need) and not any(n in h for n in no)]
    if not c: return None
    return max((cols[i] for i in c), key=lambda v: v[-1] - v[0])   # the live one, if a label repeats
wins = [tuple(int(x) * 1_000_000 for x in open(w).read().split()[:2]) for w in sorted(glob.glob(f'{ev}/{label}-{rate}/window-*.at'))]
roles = {l.split()[0]: l.split('role=')[1].strip() for l in open(f'{td}/probes.txt')}
print(f'## {label} @{rate}: roles {roles}, {len(wins)} windows')
def inwin(t): return any(a <= t < b for a, b in wins)
for m in '012':
    try: hdr, c = load(m)
    except Exception as e: print(f'm{m}: no probe data ({e})'); continue
    t = c[0]; cyc, sp = gc(m); starts = [a for a, _, _ in cyc]
    def ingc(x):
        i = bisect.bisect_right(starts, x) - 1
        return i >= 0 and cyc[i][1] >= x
    idx = [i for i, x in enumerate(t) if inwin(x)]
    if not idx: print(f'm{m}: no ticks in windows'); continue
    span = (t[idx[-1]] - t[idx[0]]) / 1e6
    gaps = sum(1 for a, b in zip(idx, idx[1:]) if b == a + 1 and t[b] - t[a] > 3000)
    dutyc = sum(1 for i in idx if ingc(t[i])) / len(idx)
    wc = [x for x in cyc if inwin(x[0])]
    print(f'\n### m{m} {roles.get(m)}: {len(idx)} ticks, probe gaps>3ms {gaps}, GC cycles {len(wc)} (major {sum(1 for x in wc if x[2]=="Major")}), mean {sum(b-a for a,b,_ in wc)/max(1,len(wc))/1e3:.0f} ms, duty {dutyc:.0%}; safepoints>1ms in windows: {[(n, r//1000, tot//1000) for (x, n, r, tot) in sp if inwin(x) and tot > 1_000_000][:12]}')
    snap = col(hdr, c, 't205')
    snapt = [t[i] for i in range(1, len(t)) if snap and snap[i] != snap[i - 1]]
    def nearsnap(x): return any(s - 2_000_000 <= x <= s + 1_000_000 for s in snapt)
    if roles.get(m) == 'LEADER':
        pub = col(hdr, c, 't12:', 'alias=log|'); snd = col(hdr, c, 't2:', 'alias=log|'); rec = col(hdr, c, 't100 ' if False else 't100:'); com = col(hdr, c, 't203')
        svc = col(hdr, c, 't4:', 'alias=log-sc'); arc = col(hdr, c, 't4:', 'alias=log @')
        stages = {'pub-commit': (pub, com), 'pub-snd': (pub, snd), 'pub-rec(leader archive)': (pub, rec), 'rec-commit(quorum follower)': (rec, com), 'commit-svc(service)': (com, svc), 'pub-svc(all)': (pub, svc)}
        ref = pub
    else:
        hwm = col(hdr, c, 't3:', 'alias=log') or col(hdr, c, 't3:'); rcv = col(hdr, c, 't5:', 'alias=log') or col(hdr, c, 't5:'); rec = col(hdr, c, 't100:'); svc = col(hdr, c, 't4:', 'alias=log-sc')
        stages = {'hwm-rec(follower archive)': (hwm, rec), 'hwm-rcvpos': (hwm, rcv), 'rec-svc': (rec, svc)}
        ref = hwm
    if ref is None: print('  no reference position; columns:', hdr[:40]); continue
    bps = (ref[idx[-1]] - ref[idx[0]]) / span
    print(f'  byte rate {bps/1e6:.1f} MB/s; snapshots at {[(s - wins[0][0])//1_000_000 for s in snapt if inwin(s)]} s')
    print('  stage | all p50 p99 p99.9 max (us) | in-GC p50 p99 p99.9 | out-GC p50 p99 p99.9 | near-snapshot max')
    for name, (a, b) in stages.items():
        if a is None or b is None: print(f'  {name}: counter missing'); continue
        A, G, O, Sn = [], [], [], []
        for i in idx:
            v = max(0, a[i] - b[i]) / bps * 1e6
            if nearsnap(t[i]): Sn.append(v); continue
            A.append(v); (G if ingc(t[i]) else O).append(v)
        f = lambda X: ' '.join(f'{pct(X, p):.0f}' for p in (.5, .99, .999))
        print(f'  {name} | {f(A)} {max(A):.0f} | {f(G)} | {f(O)} | {max(Sn) if Sn else 0:.0f}')
    # commit-flat (or hwm-flat) runs >= 5 ms: whole-cluster stalls on this member's clock
    adv = com if roles.get(m) == 'LEADER' else ref
    runs, s0 = [], None
    for k in range(1, len(idx)):
        i, j = idx[k - 1], idx[k]
        if adv[j] == adv[i] and j == i + 1:
            if s0 is None: s0 = i
        else:
            if s0 is not None and t[i] - t[s0] >= 5000: runs.append((t[s0], (t[i] - t[s0]) // 1000, nearsnap(t[s0]), ingc(t[s0])))
            s0 = None
    print(f'  flat runs >=5 ms (offset s, ms, near-snapshot, in-GC): {[((a - wins[0][0]) // 1_000_000, d, s, g) for a, d, s, g in runs][:20]}')
    ex = [(h.split(":", 1)[1][:38], c[i][idx[-1]] - c[i][idx[0]]) for i, h in enumerate(hdr) if 'exceeded' in h]
    print('  cycles over threshold in windows:', [e for e in ex if e[1]])
