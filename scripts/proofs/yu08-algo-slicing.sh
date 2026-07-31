#!/usr/bin/env bash
# yu08-algo-slicing.sh — proves the execution-algo engine actually slices: a TWAP parent order
# emits N child orders ACROSS the schedule (count, quantity conservation, and timing all bounded),
# and every child books on the matching engine — asserted at the engine's own blotter, never at
# the algo engine's word for it.
#
# Falsifiability guards:
#   * Count + conservation: exactly N = duration/bucket children; their target quantities sum to
#     the parent quantity (a lost or duplicated bucket breaks either).
#   * "Across the schedule", not front-loaded: sampled MID-schedule, some — but NOT all — children
#     exist. An engine that fired everything at T0 fails here; so does one that fired nothing.
#   * Timing bounded per bucket: each child's createdAt ON THE MATCHER (the effect end's own
#     clock) falls inside [bucket.startEpochMs, start + bucketSeconds + slack].
#   * Booked, not claimed: every childOrderId the algo engine reports must exist on the matcher's
#     own GET /orders/{id} — the BLP's state, which on this rig is the engine of record. (Not the
#     /orders LIST: that defaults to status=open, and marketable children FILL instantly and leave
#     it — which would read as "never booked", the exact ambiguity a proof can't carry.)
#
# kind-runnable: pure correctness. The timing bound is a whole-bucket envelope (seconds), not a
# latency claim, so kind's idle-CPU distortion cannot flip it. Usage: ./yu08-algo-slicing.sh
set -uo pipefail

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
# Rig-dependent workload names. On the cluster rig "order-matcher" is a SERVICE fronting the
# cluster gateway -- the Deployment behind it is cluster-gateway, and there is no Deployment of
# that name at all, so a readiness check on it fails while the matcher is perfectly healthy.
# edge-proxy does not exist on this rig either; execution-algo-engine can curl the same in-cluster
# addresses itself. For the state-014 rig: MATCHER_DEPLOY=order-matcher EXEC_POD=edge-proxy.
MATCHER_DEPLOY="${MATCHER_DEPLOY:-cluster-gateway}"
EXEC_POD="${EXEC_POD:-execution-algo-engine}"
# Where the children are read back from. The single-BLP matcher answers a blotter on
# /orders?accountId=..&status=open; the cluster gateway's /orders is POST-only and it serves no
# blotter at all -- the read model lives in trade-processor on this tier. That is the better read
# anyway: the status=open list drops marketable children the instant they fill, which is exactly
# the hazard this script's own header warns about, whereas the read model keeps them with a status.
BLOTTER="${BLOTTER:-http://trade-processor:18091/accounts}"
# A quiet account on purpose: the preflight probe lists its open orders, and 22214's open set is
# ~14MB on a long-lived rig — a kubectl-exec pull that size times out and indicts nothing.
ACCT="${ACCT:-62654}"
SEC="${SEC:-IBM}"
QTY="${QTY:-60}"
DURATION="${DURATION:-60}"
BUCKET="${BUCKET:-10}"
SLACK_S="${SLACK_S:-8}"     # scheduler tick + submit + projection tolerance, per bucket
N=$(( DURATION / BUCKET ))

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }
kx() { kubectl --context "${CTX}" -n "${NS}" "$@"; }
# In-cluster curl via the edge-proxy pod: no port-forward to die mid-schedule.
api() { kx exec "deploy/${EXEC_POD}" -- sh -c "curl -s -m30 $1" 2>/dev/null; }

py() { python3 -c "$1"; }

# ---------------------------------------------------------------------------------------------
step "0. preflight"
for d in execution-algo-engine "${MATCHER_DEPLOY}"; do
  [[ "$(kx get deploy "${d}" -o jsonpath='{.status.readyReplicas}' 2>/dev/null)" == "1" ]] \
    || fail "${d} is not READY"
done
# An account and a security exist on the cluster tier only once sequenced, so without this the
# engine slices correctly and every child is rejected UNKNOWN_ACCOUNT -- which reads as "the
# scheduler is not running" when the scheduler is in fact working perfectly. Idempotent.
api "-X POST -H 'Content-Type: application/json' -d '{\"accountId\":${ACCT},\"tickers\":\"${SEC}\",\"price\":200}' 'http://order-matcher:18110/seed'" >/dev/null 2>&1 || true

[[ $(( N * BUCKET )) -eq "${DURATION}" ]] || fail "duration must be a whole number of buckets"
PROBE="$(api "'${BLOTTER}/${ACCT}/orders'")"
[[ "${PROBE}" == \[* ]] || fail "matcher order endpoint did not answer: ${PROBE:-nothing}"
echo "[ok] engine + matcher ready; TWAP ${QTY} ${SEC} over ${DURATION}s in ${N} buckets of ${BUCKET}s"

step "1. submit the TWAP parent order"
PARENT="$(api "-X POST http://execution-algo-engine:18120/algo/orders -H 'Content-Type: application/json' -d '{\"accountId\":${ACCT},\"security\":\"${SEC}\",\"side\":\"Buy\",\"quantity\":${QTY},\"algoType\":\"TWAP\",\"durationSeconds\":${DURATION},\"bucketSeconds\":${BUCKET}}'")"
PID="$(py "
import json,sys
try: print(json.loads('''${PARENT}''')['parentOrderId'])
except Exception: print('')")"
[[ -n "${PID}" ]] || fail "parent order was not created: ${PARENT}"
T0_MS="$(py "import time; print(int(time.time()*1000))")"
# The schedule is part of the contract: N buckets, target quantities summing to the parent.
SCHED_CHECK="$(py "
import json
p=json.loads('''${PARENT}''')
b=p['buckets']
print('OK' if len(b)==${N} and sum(x['targetQuantity'] for x in b)==${QTY} else
      'BAD buckets=%d sum=%d' % (len(b), sum(x['targetQuantity'] for x in b)))")"
[[ "${SCHED_CHECK}" == "OK" ]] || fail "schedule malformed: ${SCHED_CHECK}"
echo "  parent ${PID}: ${N} buckets, quantities sum to ${QTY} ✔"

parent_json() { api "http://execution-algo-engine:18120/algo/orders/${PID}"; }
submitted_count() { parent_json | py "
import json,sys
try: print(sum(1 for b in json.load(sys.stdin)['buckets'] if b.get('childOrderId')))
except Exception: print(-1)"; }

step "2. mid-schedule: sliced, not front-loaded"
sleep $(( DURATION / 2 ))
MID="$(submitted_count)"
echo "  submitted children at T+$(( DURATION / 2 ))s: ${MID}/${N}"
[[ "${MID}" -ge 1 ]] || fail "no child submitted by mid-schedule — the scheduler is not running"
[[ "${MID}" -lt "${N}" ]] || fail "all ${N} children existed mid-schedule — the parent was front-loaded, not sliced"

step "3. end of schedule: exactly ${N} children submitted"
DONE=""
for i in $(seq 1 $(( DURATION / 2 + 4 * SLACK_S ))); do
  DONE="$(submitted_count)"
  [[ "${DONE}" == "${N}" ]] && break
  sleep 2
done
[[ "${DONE}" == "${N}" ]] || fail "only ${DONE}/${N} children were ever submitted"
FINAL="$(parent_json)"
echo "  all ${N} buckets carry a childOrderId ✔"

step "4. every child BOOKED on the matcher, at its bucket's time (the effect end's own clock)"
# Fetch each child from the matcher BY ID — terminal states included, unlike the open-orders list.
ROWS="["
FIRST=1
for cid in $(py "
import json
print(' '.join(b['childOrderId'] for b in json.loads('''${FINAL}''')['buckets']))"); do
  row="$(api "http://order-matcher:18110/orders/${cid}")"
  [[ ${FIRST} -eq 1 ]] || ROWS+=","
  ROWS+="${row:-null}"; FIRST=0
done
ROWS+="]"
VERDICT="$(py "
import json, datetime
parent = json.loads('''${FINAL}''')
rows = {r['orderId']: r for r in json.loads('''${ROWS}''') if r}
slack_ms = ${SLACK_S} * 1000
bucket_ms = ${BUCKET} * 1000
problems = []
for b in parent['buckets']:
    cid = b.get('childOrderId')
    row = rows.get(cid)
    if row is None:
        problems.append('bucket %d: child %s NOT on the matcher' % (b['index'], cid)); continue
    if row['quantity'] != b['targetQuantity']:
        problems.append('bucket %d: booked qty %s != target %s' % (b['index'], row['quantity'], b['targetQuantity']))
    created = datetime.datetime.fromisoformat(row['createdAt'].replace('Z','+00:00')).timestamp()*1000
    lo, hi = b['startEpochMs'], b['startEpochMs'] + bucket_ms + slack_ms
    if not (lo <= created <= hi):
        problems.append('bucket %d: booked at %+dms relative to its bucket window' % (b['index'], created - lo))
    else:
        print('   bucket %d -> %s qty=%-3d %-7s booked %+.1fs into its window ✔' % (b['index'], cid, row['quantity'], row['status'], (created - lo)/1000.0))
for p in problems: print('   ✘ ' + p)
print('VERDICT ' + ('PASS' if not problems else 'FAIL'))")"
echo "${VERDICT}" | grep -v '^VERDICT'
[[ "${VERDICT}" == *"VERDICT PASS"* ]] || fail "child orders did not all book inside their bucket windows"

echo
echo "[PASS] TWAP slicing: ${N} children (none early, none missing, none front-loaded), quantities"
echo "       conserved to the parent's ${QTY}, each booked on the matcher's own order state inside its"
echo "       bucket window."
