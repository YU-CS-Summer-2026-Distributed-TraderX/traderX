#!/usr/bin/env bash
# yu17-closed-survives-restart.sh — ADR-069's headline sentence, as a measurement: A HALT A RESTART
# CAN BYPASS IS NOT A HALT (scope §5 row 7, §1.4).
#
# THE CLAIM: with the venue CLOSED, delete a member's pod. It comes back, restores, catches up —
# and still reads CLOSED, and still refuses the same order MARKET_CLOSED. The distinction this
# proof exists to make is between a phase that lives in the CONSENSUS LOG AND THE SNAPSHOT and one
# that lives in a process: the second is indistinguishable from the first until something dies.
#
# The probe is submitted to the SAME gateway before and after the restart, at the same price, from
# the same account, on a ticker whose control leg rested while OPEN. Only the restart differs.
#
# ============================================================================================
# THE LIVE ARM IS PENDING THE MINT AND DOES NOT RUN BY DEFAULT.
#
# It restarts a member, which the shared kind rig's standing epoch cannot absorb while other lanes
# are measuring it — the same constraint under which chip 1 excluded yu17-retick-determinism's
# leader-kill tail. DESTRUCTIVE=0 (the default) prints what did not run and EXITS 2; a partial run
# must never read as the claim. The mint chip runs `DESTRUCTIVE=1 EXPECT=after` on the fresh epoch.
#
# ITS RED HALF IS BANKED ELSEWHERE, AT THE SEAM WHERE THE CLAIM ACTUALLY LIVES.
# A member that restarts is a member that restores and replays, so "CLOSED survives a restart" is
# decided in the snapshot codec. The red half is SessionSnapshotRestoreTest — which measures that
# this build writes NO session record (so the phase cannot survive anything) and, in
# restoreSilentlyAcceptsATruncatedRecordStream, that a restore accepting fewer records than it was
# given is SILENT. That second one is why the post-mint round trip must assert the queue's CONTENT
# and never merely that the restore returned. Running THIS script red against the current build
# would only re-measure that POST /session 404s: an API-shaped red, worth nothing at the mint.
# ============================================================================================
set -euo pipefail
CTX="${CTX:-kind-traderx-yu12-cluster}"; NS="${NS:-traderx}"
K=(kubectl --context "${CTX}" -n "${NS}")
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
EXPECT="${EXPECT:-after}"
DESTRUCTIVE="${DESTRUCTIVE:-0}"
MEMBER="${MEMBER:-2}"                    # a FOLLOWER by default: this claim is about restore, not
                                         # about election. yu17-halt-survives-failover kills the leader.
ACCT="${ACCT:-22214}"
TICKER="${TICKER:-CSR$(date +%H%M%S)}"
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
digest() { "${K[@]}" exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
  | awk '/^traderx_book_open_orders/ {d=$2} /^traderx_book_order_hash/ {h=$2} END {print d, h}'; }
set_phase() { curl -s -m20 -w ' %{http_code}' -X POST "${MATCHER_URL}/session" \
  -H 'Content-Type: application/json' -d "{\"phase\":\"$1\"}" 2>/dev/null | awk '{c=$NF; $NF=""; print c, $0}'; }
order() { curl -s -m20 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
  -d "{\"accountId\":${ACCT},\"ticker\":\"${TICKER}\",\"side\":\"$1\",\"quantity\":10,\"limitPrice\":$2,\"clientOrderId\":\"${TICKER}-$3-$$\"}"; }
cancel() { curl -s -m20 -X POST "${MATCHER_URL}/cancel" -H 'Content-Type: application/json' -d "{\"orderRef\":$1}"; }
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
      && { ok "snapshot barrier taken — CLOSED is now in a snapshot, not only in the log tail"; return 0; }
    sleep 1
  done
  fail "no snapshot barrier within 150s"; }

CLEANUP_REFS=(); PHASE_TOUCHED=0
cleanup() { local r
  (( PHASE_TOUCHED == 1 )) && { set_phase OPEN >/dev/null 2>&1 || true; }
  for r in ${CLEANUP_REFS[@]+"${CLEANUP_REFS[@]}"}; do cancel "${r}" >/dev/null 2>&1 || true; done; }
trap cleanup EXIT

echo "=== yu17-closed-survives-restart, EXPECT=${EXPECT}, DESTRUCTIVE=${DESTRUCTIVE}, member ${MEMBER}, ticker ${TICKER} ==="
if [[ "${DESTRUCTIVE}" != "1" ]]; then
  cat <<'MSG'
[SKIP] PENDING THE MINT. DESTRUCTIVE=0 (the default): this proof deletes a member pod, and every
       step of it is destructive — there is no safe prefix to run against a shared standing epoch.
       NOT RUN: CLOSED -> snapshot barrier -> member restarted -> it comes back CLOSED and the
       identical probe is still refused MARKET_CLOSED by the engine.
       The red half for this claim is BANKED OFF-RIG, at the seam where durability is decided:
       SessionSnapshotRestoreTest (sessionStateIsAbsentFromTheSnapshotToday, and
       restoreSilentlyAcceptsATruncatedRecordStream — the reason the post-mint round trip must
       assert CONTENT, not merely that the restore returned).
       At the mint, on the fresh epoch:  DESTRUCTIVE=1 EXPECT=after bash scripts/proofs/yu17-closed-survives-restart.sh
MSG
  exit 2
fi
for m in 0 1 2; do "${K[@]}" get pod "order-matcher-cluster-${m}" -o jsonpath='{.spec.containers[0].image}{"\n"}'; done | sort -u
[[ "${EXPECT}" == "after" ]] || fail "EXPECT=before is not a thing here: on a pre-mint build there is no CLOSED to survive a restart, and re-measuring a 404 across a pod delete is an API-shaped red. The pre-mint half is SessionSnapshotRestoreTest."

echo "--- 0. the venue is OPEN, and the probe RESTS while it is"
[[ "$(health_field 0 phase)" == "OPEN" ]] || fail "the venue reads phase='$(health_field 0 phase)', not OPEN (decision a)"
[[ "$(curl -s -m20 -o /dev/null -w '%{http_code}' -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
  -d "{\"accountId\":${ACCT},\"tickers\":\"${TICKER}\",\"price\":${SEED_PX}}")" == 2* ]] || fail "seed did not take"
PROBE_PX="$(python3 -c "print(f'{${SEED_PX} + 1:.3f}')")"
CTRL="$(order Sell "${PROBE_PX}" ctrl)"
[[ "$(field "${CTRL}" kind)" == "1" ]] \
  || fail "the control SELL @${PROBE_PX} did not rest on an OPEN venue (${CTRL}) — without it, a refusal after the restart could be a dead ticker or a collar rather than the session"
C="$(cancel "$(field "${CTRL}" orderRef)")"; [[ "$(field "${C}" canceled)" == *rue ]] || fail "control cancel failed: ${C}"
ok "control: SELL @${PROBE_PX} rests and cancels while OPEN"

echo "--- 1. CLOSE, and confirm the refusal BEFORE the restart"
RESP="$(set_phase CLOSED)"; [[ "${RESP%% *}" == 2* ]] || fail "CLOSED answered ${RESP%% *}"
PHASE_TOUCHED=1
REFS0="$(quiesced_order_refs)"; T0="$(quiesced_trades)"
P1="$(order Sell "${PROBE_PX}" pre)"
[[ "$(field "${P1}" kind)" == "2" && "$(field "${P1}" reason)" == "MARKET_CLOSED" ]] \
  || { CLEANUP_REFS+=("$(field "${P1}" orderRef)"); fail "the probe was not refused MARKET_CLOSED before the restart (kind=$(field "${P1}" kind) reason=$(field "${P1}" reason)) — there is nothing for a restart to preserve"; }
ok "before the restart: the probe is refused MARKET_CLOSED by the engine"

echo "--- 2. snapshot barrier"
# Without it the restarting member could reach CLOSED by replaying the log tail alone, and the
# format-8 T_SESSION record — the thing that makes a SNAPSHOTTED halt restore as a halt (§1.4,
# "a member that snapshots CLOSED restores CLOSED") — would go untested.
snapshot_barrier

echo "--- 3. restart member ${MEMBER}"
"${K[@]}" delete pod "order-matcher-cluster-${MEMBER}" --wait=false >/dev/null
sleep 5
for i in $(seq 1 90); do
  [[ "$("${K[@]}" get pod "order-matcher-cluster-${MEMBER}" -o jsonpath='{.status.containerStatuses[0].ready}' 2>/dev/null)" == "true" ]] && break
  sleep 2
done
[[ "$("${K[@]}" get pod "order-matcher-cluster-${MEMBER}" -o jsonpath='{.status.containerStatuses[0].ready}' 2>/dev/null)" == "true" ]] \
  || fail "member ${MEMBER} did not come back ready within 180s"
ok "member ${MEMBER} restarted and is ready"

echo "--- 4. it came back CLOSED"
for m in 0 1 2; do
  P="$(health_field "${m}" phase)"
  echo "    member-${m}: phase=${P:-<absent>}"
  [[ "${P}" == "CLOSED" ]] \
    || fail "member-${m} reads phase='${P:-<absent>}' after the restart, not CLOSED$( [[ "${m}" == "${MEMBER}" ]] && echo ' — and this is the member that restarted, so its restore did not carry the phase: a halt a restart can bypass is not a halt' )"
done
ok "all three members read CLOSED, including the one that restored from disk"

echo "--- 5. and it still refuses"
P2="$(order Sell "${PROBE_PX}" post)"
echo "    SELL @${PROBE_PX} (identical to the pre-restart probe) -> kind=$(field "${P2}" kind) reason=$(field "${P2}" reason)"
[[ "$(field "${P2}" kind)" == "2" ]] \
  || { CLEANUP_REFS+=("$(field "${P2}" orderRef)"); fail "the probe was ACCEPTED after the restart: the restart lifted the halt. ${P2}"; }
[[ "$(field "${P2}" reason)" == "MARKET_CLOSED" ]] \
  || fail "refused after the restart, but the engine ack reason byte says '$(field "${P2}" reason)', not MARKET_CLOSED — a refusal wearing another gate's reason is not the session gate"
REFS1="$(quiesced_order_refs)"; T1="$(quiesced_trades)"
# Both probes were SEQUENCED (a ref is issued on apply, before any verdict — §1.3) and NEITHER
# traded. The trade bracket is what says the refusals were real and not a rest that quietly crossed.
assert_order_effects "${REFS0}" "${REFS1}" 2 "${T0}" "${T1}" 0 "the two MARKET_CLOSED refusals"
ok "the halt survived a member restart: same probe, same MARKET_CLOSED, nothing traded"

RESP="$(set_phase OPEN)"; [[ "${RESP%% *}" == 2* ]] || fail "could not reopen the venue (HTTP ${RESP%% *})"
PHASE_TOUCHED=0
[[ "$(health_field 0 phase)" == "OPEN" ]] || fail "reopen answered 200 but member-0 reads '$(health_field 0 phase)'"
ok "the venue is OPEN again"
agree "the restart-and-reopen"
