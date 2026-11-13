"""latency-tail: split the leader's service-thread profile (async-profiler JFR, itimer 1 ms) by whether each sample
   falls in a service-lag episode (commit - service sub-pos > BAR us on the 1 ms probe), or in a snapshot halt.
   python3 prof.py EVIDENCE LABEL LEADER JFR_GZ [BAR=1500]"""
import sys, os, gzip, shutil, subprocess, collections, bisect
ev, label, m, jfr = sys.argv[1:5]; bar = float(sys.argv[5]) if len(sys.argv) > 5 else 1500
here = os.path.dirname(os.path.abspath(__file__)); d = f'{ev}/tail-{label}/hx-m{m}'
raw = jfr[:-3]; tsv = raw + '.tsv'
if not os.path.exists(tsv):
    with gzip.open(jfr) as a, open(raw, 'wb') as b: shutil.copyfileobj(a, b)
    subprocess.run(['java', f'{here}/JfrDump.java', raw, '', '48', tsv], check=True); os.remove(raw)
f = open(d + '/sel.tsv'); h = f.readline().rstrip('\n').split('\t'); rows = [list(map(int, l.split('\t'))) for l in f]
live = lambda c: max(c, key=lambda i: rows[-1][i] - rows[0][i])
pub = live([i for i, x in enumerate(h) if x.startswith('t12:') and 'alias=log|' in x]); com = [i for i, x in enumerate(h) if x.startswith('t203')][0]
svc = live([i for i, x in enumerate(h) if x.startswith('t4:') and 'alias=log-sc' in x]); snap = [i for i, x in enumerate(h) if x.startswith('t205')][0]
S = [l.rstrip('\n').split('\t') for l in open(tsv)]
threads = collections.Counter(s[1].rstrip('0123456789#-') for s in S); print('samples by thread:', threads.most_common(10))
S = [(int(s[0]), s[2]) for s in S if 'clustered' in s[1]]
if not S: sys.exit('no clustered-service samples')
t0, t1 = S[0][0], S[-1][0]; R = [r for r in rows if t0 <= r[0] <= t1]
bps = (R[-1][pub] - R[0][pub]) / ((R[-1][0] - R[0][0]) / 1e6); print(f'service samples {len(S)} over {(t1-t0)/1e6:.0f} s; byte rate {bps/1e6:.1f} MB/s')
T = [r[0] for r in R]; snapt = [R[i][0] for i in range(1, len(R)) if R[i][snap] != R[i - 1][snap]]
# snapshot halt windows: the commit-flat run that contains or precedes each snapshot-count step
halts = []
for s in snapt:
    i = bisect.bisect_left(T, s); j = i
    while j > 0 and T[i] - T[j] < 300_000 and not (R[j][svc] != R[j - 1][svc] and R[j - 1][svc] == R[max(0, j - 3)][svc] and False): j -= 1
    # the longest service-position-flat run within 300 ms before the step
    best = (0, 0, 0); k = j; s0 = j
    for k in range(j + 1, i + 1):
        if R[k][svc] != R[k - 1][svc]:
            if T[k - 1] - T[s0] > best[0]: best = (T[k - 1] - T[s0], T[s0], T[k - 1])
            s0 = k
    if T[i] - T[s0] > best[0]: best = (T[i] - T[s0], T[s0], T[i])
    halts.append(best)
print('snapshot steps:', len(snapt), 'service-flat ms before each:', [b[0] // 1000 for b in halts])
def cls(t):
    for _, a, b in halts:
        if a <= t <= b: return 'snapshot'
    i = min(len(R) - 1, bisect.bisect_left(T, t))
    return 'episode' if (R[i][com] - R[i][svc]) / bps * 1e6 > bar else 'normal'
FAM = [('snapshot write', ('onTakeSnapshot', 'Snapshot')), ('recon blotter', ('TradeBlotter', 'ClusterRecon')), ('output ring / egress', ('OutputPublisher', 'drainOutputs', 'flushCoalesced', 'Disruptor', 'RingBuffer', 'BlockingWaitStrategy', 'offer')),
       ('exactly-once tables', ('SessionRing', 'StreamDedupe', 'dempot')), ('risk state', ('BlpRisk', 'RiskState')), ('matching', ('MatchingEngine', 'LimitBook')),
       ('bridge enqueue', ('NatsPublisher',)), ('kdb tap', ('KdbTap',)), ('GC barrier / runtime', ('ZBarrier', 'z_load', 'ZRelocate', 'SafepointSynchronize', 'ZMark', 'ZPage', 'ZForwarding')),
       ('decode', ('Codec',)), ('session message, self', ('onSessionMessage',)), ('agent poll / idle', ('ClusteredServiceAgent', 'BoundedLogAdapter', 'Idle', 'poll'))]
fam = collections.defaultdict(collections.Counter); leaf = collections.defaultdict(collections.Counter)
for t, st in S:
    c = cls(t); fr = st.split(';')
    k = next((n for n, keys in FAM if any(x in st for x in keys)), 'other'); fam[c][k] += 1; leaf[c][';'.join(fr[:2])] += 1
tot = {c: sum(v.values()) for c, v in fam.items()}; print('samples:', tot)
print('family | normal % | episode % | snapshot %')
for k in sorted({k for v in fam.values() for k in v}, key=lambda k: -fam['episode'][k] - fam['normal'][k] / 1e6):
    print(f'  {k} | ' + ' | '.join(f"{100 * fam[c][k] / max(1, tot.get(c, 0)):.1f}" for c in ('normal', 'episode', 'snapshot')))
for c in ('episode', 'normal', 'snapshot'):
    print(c, 'top leaves:', [(k[:70], f'{100 * v / max(1, tot.get(c, 0)):.1f}%') for k, v in leaf[c].most_common(8)])
if 'snapshot' in fam:
    sub = collections.Counter()
    for t, st in S:
        if cls(t) == 'snapshot':
            fr = st.split(';'); i = next((i for i, x in enumerate(fr) if 'onTakeSnapshot' in x), None)
            sub[fr[i - 1] if i else 'outside onTakeSnapshot: ' + fr[0]] += 1
    print('under onTakeSnapshot, by callee:', [(k[:60], v) for k, v in sub.most_common(12)])
