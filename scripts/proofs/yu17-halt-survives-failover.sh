#!/usr/bin/env bash
# yu17-halt-survives-failover.sh — format-8 §1.4 / ADR-069: the halt and its queue are REPLICATED
# STATE, so a leader kill mid-halt changes nothing (scope §5 row 6).
#
# THE CLAIM: PRE_OPEN with a non-empty queue -> snapshot barrier -> kill the leader -> the NEW
# leader still reads PRE_OPEN, queueDepth is intact, and the OPEN releases the queue identically
# (insertion order preserved, one match, all three members digest-identical). This is the entire
# consensus-over-gateway argument of ADR-069 made a measured fact: a phase held in the gateway
# would evaporate with the process that held it.
#
# ============================================================================================
# THE LIVE ARM IS PENDING THE MINT AND DOES NOT RUN BY DEFAULT.
#
# It kills a leader, which the shared kind rig's standing epoch cannot absorb while other lanes
# are measuring it — the same constraint under which chip 1 excluded yu17-retick-determinism's
# leader-kill tail. DESTRUCTIVE=0 (the default) prints what did not run and EXITS 2, because a
# partial run must never read as the claim. The mint chip runs `DESTRUCTIVE=1 EXPECT=after` on the
# fresh epoch, where the leader kill is free.
#
# ITS RED HALF IS BANKED ELSEWHERE, AT THE SEAM WHERE THE CLAIM ACTUALLY LIVES.
# "The halt survives a failover" is a DURABILITY claim, and durability is decided in the snapshot
# codec, not in the cluster: a new leader is a member that restored and replayed. So the red half
# is a unit test over MECS's writeSnapshot/onSnapshotRecord seams —
# SessionSnapshotRestoreTest.sessionStateIsAbsentFromTheSnapshotToday, which MEASURES that this
# build writes no session or queue record at all, so nothing about a halt could survive anything.
# That is the assertion that catches "a halt a restart can bypass is not a halt", and it needs no
# rig. Running THIS script red against the current build would only re-measure that POST /session
# 404s, which is an API-shaped red and worth nothing at the mint.
# ============================================================================================
set -euo pipefail
CTX="${CTX:-kind-traderx-yu12-cluster}"; NS="${NS:-traderx}"
K=(kubectl --context "${CTX}" -n "${NS}")
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
EXPECT="${EXPECT:-after}"
DESTRUCTIVE="${DESTRUCTIVE:-0}"          # opposite default to yu17-retick-determinism: EVERY step
                                         # here is destructive, so there is no safe prefix to run.
ACCT="${ACCT:-22214}"; ACCT2="${ACCT2:-52355}"
TICKER="${TICKER:-HSF$(date +%H%M%S)}"
SEED_PX="${SEED_PX:-150}"
fail() { echo "[FAIL] $*" >&2; exit 1; }
ok() { echo "[ok] $*"; }
field() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$2',''))" <<<"$1"; }
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/lib-consensus-readings.sh"

health_field() { "${K[@]}" exec "order-matcher-cluster-${1}" -- wget -qO- localhost:8080/health 2>/dev/null \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('$2',''))" 2>/dev/null || echo ""; }
metric() { "${K[@]}" exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
  | awk -v k="$2" 'index($1, k"{")==1 || $1==k {print $2}'; }
snap_count() { metric "$1" traderx_cluster_snapshots; }
leader() { for m in 0 1 2; do [[ "$(metric "${m}" traderx_cluster_role 2>/dev/null)" == "1" ]] && { echo "${m}"; return 0; }; done; return 1; }
digest() { "${K[@]}" exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
  | awk '/^traderx_book_open_orders/ {d=$2} /^traderx_book_order_hash/ {h=$2} END {print d, h}'; }
set_phase() { curl -s -m20 -w ' %{http_code}' -X POST "${MATCHER_URL}/session" \
  -H 'Content-Type: application/json' -d "{\"phase\":\"$1\"}" 2>/dev/null | awk '{c=$NF; $NF=""; print c, $0}'; }
order() { curl -s -m20 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
  -d "{\"accountId\":$1,\"ticker\":\"${TICKER}\",\"side\":\"$2\",\"quantity\":$3,\"limitPrice\":$4,\"clientOrderId\":\"${TICKER}-$5-$$\"}"; }
cancel() { curl -s -m20 -X POST "${MATCHER_URL}/cancel" -H 'Content-Type: application/json' -d "{\"orderRef\":$1}"; }
rm_status() { "${K[@]}" exec deploy/trade-processor -- sh -c "wget -qO- 'http://localhost:18091/accounts/$2/orders?status=all' 2>/dev/null" \
  | python3 -c "
import sys,json
for r in json.load(sys.stdin):
    if str(r.get('id','')).endswith('-$1'): print(r.get('status','')); break
else: print('')
" 2>/dev/null || echo ""; }
agree() { local i D0 D1 D2
  for i in $(seq 1 60); do
    D0="$(digest 0)"; D1="$(digest 1)"; D2="$(digest 2)"
    [[ "${D0}" =~ ^[0-9]+\ -?[0-9]+$ && "${D0}" == "${D1}" && "${D1}" == "${D2}" ]] && { ok "digest agreement after $1: ${D0}"; return 0; }
    sleep 2
  done
  fail "members never agreed on the book digest after $1: [${D0}] [${D1}] [${D2}]"; }
snapshot_barrier() {
  local b0 b1 b2 i
  b0="$(snap_count 0)"; b1="$(snap_count 1)"; b2="$(snap_count 2)"
  [[ -n "${b0}" && -n "${b1}" && -n "${b2}" ]] || fail "cannot read snapshot counters ([${b0}] [${b1}] [${b2}])"
  echo "    waiting for a snapshot barrier (from [${b0} ${b1} ${b2}])"
  for i in $(seq 1 150); do
    [[ "$(snap_count 0)" -gt "${b0}" && "$(snap_count 1)" -gt "${b1}" && "$(snap_count 2)" -gt "${b2}" ]] \
      && { ok "snapshot barrier taken — the halt and its queue are now IN a snapshot, not only in the log"; return 0; }
    sleep 1
  done
  fail "no snapshot barrier within 150s"; }

CLEANUP_REFS=(); PHASE_TOUCHED=0
cleanup() { local r
  (( PHASE_TOUCHED == 1 )) && { set_phase OPEN >/dev/null 2>&1 || true; }
  for r in ${CLEANUP_REFS[@]+"${CLEANUP_REFS[@]}"}; do cancel "${r}" >/dev/null 2>&1 || true; done
  "${K[@]}" exec deploy/eod-price-db -c mariadb -- mariadb -utraderx -ptraderx traderx -N -B \
    -e "DELETE FROM positions WHERE security='${TICKER}';" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "=== yu17-halt-survives-failover, EXPECT=${EXPECT}, DESTRUCTIVE=${DESTRUCTIVE}, ticker ${TICKER} ==="
if [[ "${DESTRUCTIVE}" != "1" ]]; then
  cat <<'MSG'
[SKIP] PENDING THE MINT. DESTRUCTIVE=0 (the default): this proof kills a leader, and every step of
       it is destructive — there is no safe prefix to run against a shared standing epoch.
       NOT RUN: PRE_OPEN + queue -> snapshot barrier -> leader kill -> new leader still PRE_OPEN
       with the queue intact -> OPEN releases in insertion order, all three members identical.
       The red half for this claim is BANKED OFF-RIG, at the seam where durability is decided:
       SessionSnapshotRestoreTest.sessionStateIsAbsentFromTheSnapshotToday measures that this
       build writes NO session or queue record, so no halt could survive any restart.
       At the mint, on the fresh epoch:  DESTRUCTIVE=1 EXPECT=after bash scripts/proofs/yu17-halt-survives-failover.sh
MSG
  exit 2
fi
for m in 0 1 2; do "${K[@]}" get pod "order-matcher-cluster-${m}" -o jsonpath='{.spec.containers[0].image}{"\n"}'; done | sort -u
[[ "${EXPECT}" == "after" ]] || fail "EXPECT=before is not a thing here: on a pre-mint build there is no phase to survive a failover, and re-measuring a 404 across a leader kill is an API-shaped red. The pre-mint half is SessionSnapshotRestoreTest."

echo "--- 0. the venue is OPEN and the ticker is live"
[[ "$(health_field 0 phase)" == "OPEN" ]] || fail "the venue reads phase='$(health_field 0 phase)', not OPEN (decision a)"
for a in "${ACCT}" "${ACCT2}"; do
  [[ "$(curl -s -m20 -o /dev/null -w '%{http_code}' -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${a},\"tickers\":\"${TICKER}\",\"price\":${SEED_PX}}")" == 2* ]] || fail "seed for ${a} did not take"
done
CTRL="$(order "${ACCT}" Buy 10 "${SEED_PX}" ctrl)"
[[ "$(field "${CTRL}" kind)" == "1" ]] || fail "the control BUY did not rest on an OPEN venue (${CTRL}) — the ticker is the problem, not the session"
C="$(cancel "$(field "${CTRL}" orderRef)")"; [[ "$(field "${C}" canceled)" == *rue ]] || fail "control cancel failed: ${C}"
ok "control: the ticker trades at ${SEED_PX} while OPEN"

echo "--- 1. PRE_OPEN, and a queue built in a known order"
RESP="$(set_phase PRE_OPEN)"; [[ "${RESP%% *}" == 2* ]] || fail "PRE_OPEN answered ${RESP%% *}"
# The sequence the halt landed at — step 4's gate. See await_member_restored in
# lib-consensus-readings.sh: reading a just-killed member's phase without it is a race in both
# directions (no answer, then the fresh-epoch default OPEN, before the replayed value).
HALT_SEQ="$(field "${RESP#* }" sequence)"
[[ "${HALT_SEQ}" =~ ^[0-9]+$ ]] || fail "POST /session did not return the sequence its halt landed at (got '${RESP#* }') — without it the post-failover read cannot be gated on the restarted member having replayed the halt"
PHASE_TOUCHED=1
REFS0="$(quiesced_order_refs)"; T0="$(quiesced_trades)"
A1="$(order "${ACCT}"  Buy  10 "${SEED_PX}" a1)"; A1_REF="$(field "${A1}" orderRef)"
A2="$(order "${ACCT}"  Buy  10 "${SEED_PX}" a2)"; A2_REF="$(field "${A2}" orderRef)"
S="$( order "${ACCT2}" Sell 10 "${SEED_PX}" s )"; S_REF="$( field "${S}"  orderRef)"
CLEANUP_REFS=("${A1_REF}" "${A2_REF}" "${S_REF}")
REFS1="$(quiesced_order_refs)"; T1="$(quiesced_trades)"
assert_order_effects "${REFS0}" "${REFS1}" 3 "${T0}" "${T1}" 0 "orders queued during PRE_OPEN"
QD="$(health_field 0 queueDepth)"
[[ "${QD}" == "3" ]] || fail "queueDepth reads '${QD:-<absent>}', not 3"
ok "3 orders queued (A1=${A1_REF} A2=${A2_REF} S=${S_REF}), nothing traded, depth 3"

echo "--- 2. snapshot barrier: the halt must be in the snapshot, not only in the log tail"
# WITHOUT the barrier this proof would only prove the LOG carries the phase — a member that
# restores from a snapshot and replays would still get there. The barrier is what forces the
# question the format-8 record types exist to answer: is the phase IN the snapshot?
snapshot_barrier

echo "--- 3. kill the leader"
LDR="$(leader)" || fail "no leader found"
echo "    killing leader member ${LDR}"
# Captured BEFORE the delete: the terminating pod keeps reporting ready for several seconds, which
# is what made the old loop exit immediately and exec into a container that was going away.
OLD_UID="$(member_pod_uid "${LDR}")"
[[ -n "${OLD_UID}" ]] || fail "could not read leader ${LDR}'s pod uid before the kill"
"${K[@]}" delete pod "order-matcher-cluster-${LDR}" --wait=false >/dev/null
for i in $(seq 1 90); do
  NEW="$(leader || true)"; [[ -n "${NEW}" && "${NEW}" != "${LDR}" ]] && break
  sleep 2
done
[[ -n "${NEW:-}" && "${NEW}" != "${LDR}" ]] || fail "no NEW leader re-elected within 180s (leader reads '${NEW:-<none>}') — the failover did not happen, so nothing below is a verdict about the halt"
ok "leader ${LDR} killed; leader is now ${NEW}"
# The killed member has to be back AND past the halt before step 4 reads its phase, or the read is
# a race rather than a measurement.
await_member_restored "${LDR}" "${OLD_UID}" "${HALT_SEQ}" 300 \
  || fail "member ${LDR} did not come back and replay past the halt sequence ${HALT_SEQ} within 300s — an incomplete restart, NOT a verdict about whether the halt survived the failover"

echo "--- 4. the halt survived, on EVERY member — including the one that came back from disk"
for m in 0 1 2; do
  P="$(health_field "${m}" phase)"; Q="$(health_field "${m}" queueDepth)"
  echo "    member-${m}: phase=${P:-<absent>} queueDepth=${Q:-<absent>}"
  [[ "${P}" == "PRE_OPEN" ]] \
    || fail "member-${m} reads phase='${P:-<absent>}' after the failover, not PRE_OPEN. A halt that a leader change lifts is the gateway-held phase ADR-069 exists to reject$( [[ "${m}" == "${LDR}" ]] && echo ' — and this is the member that restarted, so the phase was not in its snapshot' )"
  [[ "${Q}" == "3" ]] \
    || fail "member-${m} reads queueDepth='${Q:-<absent>}', not 3. The queue is replicated state (§1.4, T_QUEUED_ORDER); losing it across a failover silently drops three client orders that were ACKED"
done
ok "phase PRE_OPEN and queueDepth 3 on all three members after the leader kill"

echo "--- 5. the open still releases identically"
REFS2="$(quiesced_order_refs)"; T2="$(quiesced_trades)"
RESP="$(set_phase OPEN)"; [[ "${RESP%% *}" == 2* ]] || fail "OPEN answered ${RESP%% *}"
PHASE_TOUCHED=0
sleep 3
REFS3="$(quiesced_order_refs)"; T3="$(quiesced_trades)"
assert_order_effects "${REFS2}" "${REFS3}" 0 "${T2}" "${T3}" 2 "the post-failover release at the open"
[[ "$(health_field 0 queueDepth)" == "0" ]] || fail "the queue did not drain at the open"
ST1="$(rm_status "${A1_REF}" "${ACCT}")"; ST2="$(rm_status "${A2_REF}" "${ACCT}")"
echo "    read model: A1(${A1_REF})=${ST1:-<absent>}  A2(${A2_REF})=${ST2:-<absent>}"
[[ "${ST1}" == "FILLED" ]] || fail "insertion order did not survive the failover: A1 (queued FIRST) reads '${ST1:-<absent>}'. The queue restored, but in the wrong order — §1.4 makes its order load-bearing precisely because it is the release order"
[[ "${ST2}" == "NEW" ]] || fail "A2 (queued SECOND) reads '${ST2:-<absent>}', expected NEW"
CLEANUP_REFS=("${A2_REF}")
ok "the open released the restored queue in insertion order: one match, A1 filled, A2 resting"

for r in ${CLEANUP_REFS[@]+"${CLEANUP_REFS[@]}"}; do
  C="$(cancel "${r}")"; [[ "$(field "${C}" canceled)" == *rue ]] || fail "cleanup cancel of ${r} did not take: ${C}"
done
CLEANUP_REFS=()
# NO cross-member counter comparison: counters are per-process observability and the killed member
# restored from the snapshot, skipping applies the barrier already captured, so its absolutes are
# legitimately lower. Comparing them is a check that cannot pass against a CORRECT system
# (vacuous-pass-audit, "the mirror"). The replicated claim is the book digest.
agree "the failover-and-open"
ok "a halt held through a snapshot barrier and a leader kill, and released identically"
