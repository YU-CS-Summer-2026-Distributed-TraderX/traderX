import sys, glob, re, collections
ev, label, m = sys.argv[1], sys.argv[2], sys.argv[3]
d = f'{ev}/tail-{label}/hx-m{m}'
f = open(d + '/sel.tsv'); hdr = f.readline().rstrip('\n').split('\t')
ix = lambda *n, no=(): [i for i, h in enumerate(hdr) if all(x in h for x in n)]
rows = [list(map(int, l.split('\t'))) for l in f]
def live(c): return max(c, key=lambda i: rows[-1][i] - rows[0][i])
pub, snd, rec, com, svc = live(ix('t12:', 'alias=log|')), live(ix('t2:', 'alias=log|')), live(ix('t100:')), ix('t203')[0], live(ix('t4:', 'alias=log-sc'))
wins = [tuple(int(x) * 1_000_000 for x in open(w).read().split()[:2]) for w in sorted(glob.glob(f'{ev}/{label}-300/window-*.at'))]
R = [r for r in rows if any(a <= r[0] < b for a, b in wins)]
bps = (R[-1][pub] - R[0][pub]) / ((R[-1][0] - R[0][0]) / 1e6)
os_ = []
for l in open(d + '/os.log', errors='replace'):
    p = l.split(' ', 1)
    if p[0].isdigit(): os_.append((int(p[0]), p[1]))
import bisect; ot = [x[0] for x in os_]
def thr(t):
    i = bisect.bisect_left(ot, t); 
    if i >= len(os_): return {}
    seg = os_[i][1].split('|T')[1].split(' Z:')[0].split()
    return {s.split(':')[1]: s.split(':')[2] + '/' + s.split(':')[3] for s in seg if s.count(':') >= 6}
for name, a, b, bar in (('rec-commit', rec, com, 1000), ('commit-svc', com, svc, 1500), ('pub-snd', pub, snd, 800)):
    eps, cur = [], None
    for r in R:
        v = (r[a] - r[b]) / bps * 1e6
        if v > bar:
            if cur is None: cur = [r[0], r[0], v]
            cur[1] = r[0]; cur[2] = max(cur[2], v)
        elif cur: eps.append(cur); cur = None
    durs = sorted((e[1] - e[0]) // 1000 + 1 for e in eps); gaps = sorted((b[0] - a[1]) // 1000 for a, b in zip(eps, eps[1:]))
    q = lambda X, p: X[int(len(X) * p)] if X else -1
    print(f'{name} > {bar} us: {len(eps)} episodes in {len(R)/1000:.0f} s, total {sum(durs)} ms; dur ms p50/p90/max {q(durs,.5)}/{q(durs,.9)}/{durs[-1] if durs else 0}; gap ms p10/p50/p90 {q(gaps,.1)}/{q(gaps,.5)}/{q(gaps,.9)}')
    st = collections.Counter()
    for e in eps:
        for k, v in thr(e[0]).items():
            if re.search('clustered|sender|receiver|consensus|archive|conductor', k): st[k[:14] + ' ' + v] += 1
    print('   thread state/wchan at episode start:', st.most_common(14))
base = collections.Counter()
for t in range(R[0][0], R[-1][0], 500_000):
    for k, v in thr(t).items():
        if re.search('clustered|sender|receiver|consensus|archive|conductor', k): base[k[:14] + ' ' + v] += 1
print('baseline states (every 0.5 s):', base.most_common(14))
# the big stall
big = max(range(1, len(R)), key=lambda i: (R[i][pub] - R[i][snd]))
t0 = R[big][0]; print('largest pub-snd at +%.3f s' % ((t0 - wins[0][0]) / 1e6))
for t, l in os_:
    if t0 - 120_000 <= t <= t0 + 30_000 and (t // 10000) % 2 == 0:
        T = l.split('|T')[1].split(' Z:')[0].split(); keep = [':'.join(s.split(':')[1:4]) for s in T if re.search('clustered|sender|archive|consensus|receiver', s)]
        print((t - t0) // 1000, ' '.join(keep)[:230], '|M', l.split('|M')[1].split('|P')[0].strip()[:90], '|P', l.split('|P')[1].split('|C')[0].strip(), '|N', l.split('|N')[1][:70])
