#!/usr/bin/env bash
# yu18-bridge-receipts.sh — ADR-083 / FR-DRS-11..14, 17 and 24: the leader-side trade and order bridges
# account for every message, read against what TRADERX_FEED actually stored (lib-jetstream.py), black-box.
#
#   1. FR-DRS-13: every member's /health `bridges` has the B shape; rejectedByPartition has exactly P
#      entries summing to `rejected` (P = the members' BRIDGE_PARTITIONS, 1 when unset); followers zero
#   2. FR-DRS-14: every member's /metrics carries traderx_bridge_published / _dropped / _ingest_rejected
#      with labels member and bridge, and _ingest_rejected equals /health rejected
#   3. N crosses on a fresh ticker, then quiet (no trade applied for 2 s, here 5 s)
#   4. FR-DRS-12: on the leader, Δpublished = Δacked + Δrejected for each bridge, and the trade bridge's
#      Δacked equals the trade sides the stream stored since the start (the stream is the witness)
#   5. the stream holds exactly 2N sides of the ticker, each once (FR-DRS-17: nothing published twice)
#   6. FR-DRS-11: on every trade subject the stored sides since the start are in non-decreasing tradeSeq
#   7. FR-DRS-24: TRADERX_FEED is capped at 20 GiB and 2 h on this (GKE) tier
#   FR-DRS-06, when the members run BRIDGE_PARTITIONS=P>1: every side is on /trades.<accountId mod P> and
#   its envelope `topic` is still /trades (step 5)
#
# Exact deltas need a quiet venue: run it alone. FR-DRS-15/16 (rejection ALERTs, named ids) need an ingest
# rejection, which this proof does not force; it reports `rejected` so a run that saw one says so.
# Usage: CTX=<ctx> NS=<ns> [N=20 ACCT_A=22214 ACCT_B=10031] bash scripts/proofs/yu18-bridge-receipts.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
ACCT_A="${ACCT_A:-22214}"; ACCT_B="${ACCT_B:-10031}"; N="${N:-20}"   # 22214 and 10031 differ mod 4, so P=4 uses two partitions
T="$(mint_ticker)"; PX=40
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
pf NP "${NATS_TARGET:-deploy/nats}" 4222 tcp
ADMIN="$(mint true '[]')"; [[ -n "${ADMIN}" ]] || fail "could not mint an admin bearer"
J=(-H 'Content-Type: application/json' -H "Authorization: Bearer ${ADMIN}")
STREAM="${PROJECTION_FEED_STREAM:-TRADERX_FEED}"
js() { python3 "${here}/lib-jetstream.py" "$1" "127.0.0.1:${NP}" "${STREAM}" "${@:2}"; }
P="$("${K[@]}" get sts order-matcher-cluster -o 'jsonpath={.spec.template.spec.containers[0].env[?(@.name=="BRIDGE_PARTITIONS")].value}' 2>/dev/null)"; P="${P:-1}"
health() { mget "$1" /health; }
leader() { local m; for ((m=0; m<MEMBERS; m++)); do [[ "$(health "${m}" | jget 'd.get("role")')" == LEADER ]] && { echo "${m}"; return; }; done; }
trades_all() { local m s=""; for ((m=0; m<MEMBERS; m++)); do s+="$(health "${m}" | jget 'd.get("trades")') "; done; echo "${s% }"; }
bridge() { health "$1" | jget "[d['bridges']['$2'][k] for k in ('published','acked','rejected','failed')]" | tr -d '[],'; }

step "1-2. FR-DRS-13/14: the receipt counters on every member (P=${P})"
L="$(leader)"; [[ -n "${L}" ]] || fail "no leader"
for ((m=0; m<MEMBERS; m++)); do
  H="$(health "${m}")"; M="$(mget "${m}" /metrics)"
  python3 - "${m}" "${L}" "${P}" "${H}" "${M}" <<'PY' || fail "member ${m}: receipt counters disagree with FR-DRS-13/14 (above)"
import json, re, sys
m, leader, P, h, metrics = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3]), json.loads(sys.argv[4]), sys.argv[5]
bad = []
for b in ('trades', 'orders'):
    B = (h.get('bridges') or {}).get(b)
    if B is None: bad.append(f'bridges.{b} is null (bridges not enabled)'); continue
    miss = {'published', 'acked', 'rejected', 'failed', 'rejectedByPartition'} - set(B)
    if miss: bad.append(f'bridges.{b} lacks {sorted(miss)}'); continue
    rp = B['rejectedByPartition']
    if len(rp) != P or sum(rp) != B['rejected']: bad.append(f'bridges.{b}.rejectedByPartition {rp}: want {P} entries summing to rejected={B["rejected"]}')
    if m != leader and any(B[k] for k in ('published', 'acked', 'rejected', 'failed')): bad.append(f'follower {m} reports nonzero bridges.{b}: {B}')
    for name in ('traderx_bridge_published', 'traderx_bridge_dropped', 'traderx_bridge_ingest_rejected'):
        rows = [l for l in metrics.splitlines() if l.startswith(name) and f'bridge="{b}"' in l and 'member=' in l]
        if not rows: bad.append(f'/metrics has no {name}{{member,bridge="{b}"}}')
        elif name.endswith('ingest_rejected') and float(rows[0].split()[-1]) != B['rejected']: bad.append(f'{rows[0]} != /health bridges.{b}.rejected {B["rejected"]}')
for x in bad: print('    ', x)
print(f"    member {m}: trades {h['bridges']['trades']}  orders {h['bridges']['orders']}" if not bad else '')
sys.exit(1 if bad else 0)
PY
done
ok "shape, partition sums, follower zeros and metrics agree on all ${MEMBERS} members; leader ${L}"

step "3. ${N} crosses on ${T}, then quiet"
c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-bridge-receipts' -d "{\"accountId\":${ACCT_A},\"tickers\":\"${T}\",\"price\":${PX}}")"
[[ "${c}" == 200 ]] || fail "seed ${T}: ${c}"
S0="$(js last)"; read -r tp0 ta0 tr0 tf0 <<<"$(bridge "${L}" trades)"; read -r op0 oa0 or0 of0 <<<"$(bridge "${L}" orders)"
SUBJ='/trades'; [[ "${P}" -gt 1 ]] && SUBJ='/trades.>'
ST0="$(js count "${SUBJ}")"; OSUBJ='/orders'; [[ "${P}" -gt 1 ]] && OSUBJ='/orders.>'; SO0="$(js count "${OSUBJ}")"
for ((i=0; i<N; i++)); do
  for x in "${ACCT_B} Sell ${PX}" "${ACCT_A} Buy $((PX + 1))"; do set -- ${x}
    c="$(code -X POST "${GW}/orders" "${J[@]}" -d "{\"accountId\":$1,\"ticker\":\"${T}\",\"side\":\"$2\",\"quantity\":1,\"limitPrice\":$3}")"
    [[ "${c}" == 200 && "$(body)" != *'"kind":2'* ]] || fail "$2 $1 not accepted: ${c} $(body)"
  done
done
prev=""; for i in $(seq 1 60); do cur="$(trades_all)"; [[ "${cur}" == "${prev}" ]] && break; prev="${cur}"; sleep 5; done
ok "stream at ${S0} before; members' trades now ${cur}"

step "4. FR-DRS-12: every published message has one outcome, and the stream is the witness"
read -r tp1 ta1 tr1 tf1 <<<"$(bridge "${L}" trades)"; read -r op1 oa1 or1 of1 <<<"$(bridge "${L}" orders)"
ST1="$(js count "${SUBJ}")"; SO1="$(js count "${OSUBJ}")"
echo "    trades: published +$((tp1-tp0)) acked +$((ta1-ta0)) rejected +$((tr1-tr0)) failed +$((tf1-tf0)); stream ${SUBJ} +$((ST1-ST0))"
echo "    orders: published +$((op1-op0)) acked +$((oa1-oa0)) rejected +$((or1-or0)) failed +$((of1-of0)); stream ${OSUBJ} +$((SO1-SO0))"
(( tp1 - tp0 == (ta1 - ta0) + (tr1 - tr0) )) || fail "trade bridge: Δpublished $((tp1-tp0)) != Δacked $((ta1-ta0)) + Δrejected $((tr1-tr0))"
(( op1 - op0 == (oa1 - oa0) + (or1 - or0) )) || fail "order bridge: Δpublished $((op1-op0)) != Δacked $((oa1-oa0)) + Δrejected $((or1-or0))"
(( ta1 - ta0 == ST1 - ST0 )) || fail "trade bridge Δacked $((ta1-ta0)) != trade sides the stream stored $((ST1-ST0)) (another writer on the rig makes this inconclusive)"
(( oa1 - oa0 == SO1 - SO0 )) || fail "order bridge Δacked $((oa1-oa0)) != order messages the stream stored $((SO1-SO0))"
ok "published = acked + rejected on both bridges; acked = stored on both"

step "5-6. FR-DRS-11/17: the ticker's sides on the stream, once each, in tradeSeq order"
js read "${SUBJ}" "$((S0 + 1))" | python3 -c "
import json, sys, collections
T, N, P = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
bad = []
rows = [json.loads(l) for l in sys.stdin if l.strip()]
tail = rows.pop() if rows and '_read' in rows[-1] else {}
by = collections.defaultdict(list); mine = []
for r in rows:
    p = (r['data'] or {}).get('payload') or {}
    sid = str(p.get('id', ''))
    num = [x for x in sid.split('-') if x.isdigit()]
    if num: by[r['subject']].append((int(num[-1] if sid.startswith('e1-') else num[0]), sid))
    if p.get('security') == T:
        mine.append(sid)
        # FR-DRS-06: P>1 publishes on <subject>.<accountId mod P> with the envelope (topic) unchanged
        want = '/trades' if P == 1 else '/trades.' + str(int(p.get('accountId')) % P)
        if r['subject'] != want or r['data'].get('topic') != '/trades': bad.append(sid + ' on ' + r['subject'] + ' topic ' + str(r['data'].get('topic')) + ': want ' + want + ' / /trades')
for subj, seqs in by.items():
    for (a, ia), (b, ib) in zip(seqs, seqs[1:]):
        if b < a: bad.append(f'{subj}: {ia} (tradeSeq {a}) before {ib} ({b})')
dups = [k for k, v in collections.Counter(mine).items() if v > 1]
print(f'    read {tail.get(\"_read\")} of {tail.get(\"_pending\")} stored; {len(mine)} sides of {T}; subjects {sorted(by)}')
for b in bad[:5]: print('    violation:', b)
if dups: print('    stored twice:', dups[:5])
sys.exit(0 if not bad and not dups and len(mine) == 2 * N else 1)
" "${T}" "${N}" "${P}" || fail "the stream's ${T} sides are not exactly ${N} crosses, once each, in tradeSeq order"
ok "$((2 * N)) sides of ${T}, each once, each on its partition's subject (P=${P}); every trade subject non-decreasing in tradeSeq"
[[ "$((tr1 - tr0))" == 0 ]] && echo "    (no ingest rejection this run: FR-DRS-15/16/17's rejection arms not exercised)"

step "7. FR-DRS-24: the stream's caps on this tier"
cfg="$(js info | jget '[d["config"]["max_bytes"], d["config"]["max_age"]]')"
[[ "${cfg}" == "[21474836480, 7200000000000]" ]] || fail "TRADERX_FEED max_bytes/max_age ${cfg}, want 20 GiB / 2 h (FR-DRS-24 GKE tier)"
ok "max_bytes 20 GiB, max_age 2 h"
echo; echo "YU18-BRIDGE-RECEIPTS PASS P=${P} N=${N} ticker=${T}"
