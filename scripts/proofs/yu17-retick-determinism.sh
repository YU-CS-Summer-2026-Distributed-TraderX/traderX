#!/usr/bin/env bash
# yu17-retick-determinism.sh — format-8 §2.3.3: grid re-derivation is replicated state, not a
# member-local opinion (design §5 row 6).
#
# THE CLAIM: the full sequence — create-before-tick -> reject -> tick -> (format 8: retick) ->
# trade -> re-anchor — leaves all three members digest-identical, and a snapshot barrier plus a
# leader kill mid-sequence changes nothing. DIVERGENCE HERE IS THE FINDING. Assertion end is the
# members' own counters and book digest (prove-cluster-engine-change §3), never the read model.
#
# ARMS:
#   EXPECT=before (current build): the same sequence minus the retick (no derivation exists);
#     determinism must hold today too — this arm is the harness's own control: if it cannot go
#     green on the current build, the harness is broken, not the mint (vacuous-pass-audit,
#     "a check that has never been green is not a check yet").
#   EXPECT=after (format-8 build): identical sequence; additionally every member must agree the
#     retick HAPPENED (traderx_book_reticks identical and moved on all three) — a member that
#     re-derived while another kept the frozen grid is precisely the divergence §2.3.3 forbids.
#
# DESTRUCTIVE TAIL (the trade + snapshot barrier + leader kill), gated on DESTRUCTIVE=1 (default):
# the trade books through the venue and the leader kill forces a failover mid-sequence. With
# DESTRUCTIVE=0 the script runs the resting-only prefix and EXITS 2 — a partial run must not
# read as the full claim (the exit code is the verdict). Standing scope-§5 discipline: do not run
# the destructive tail against a shared epoch another lane is measuring.
#
# Step 1's refusal is itself a recorded fixture of the §1.1 window: /resolve registers the ticker
# WITHOUT enabling it, so the order creates-and-anchors a book and is then refused
# UNKNOWN_SECURITY (engine ack byte 22) — the exact population the provisional grid exists for.
set -euo pipefail
CTX="${CTX:-kind-traderx-yu12-cluster}"; NS="${NS:-traderx}"
K=(kubectl --context "${CTX}" -n "${NS}")
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
EXPECT="${EXPECT:-after}"
DESTRUCTIVE="${DESTRUCTIVE:-1}"
ACCT="${ACCT:-22214}"; ACCT2="${ACCT2:-52355}"   # both in reference data (yu17-band-follows-market)
TICKER="${TICKER:-RTD$(date +%H%M%S)}"
SEED_PX="${SEED_PX:-1.15}"
IN_PX="${IN_PX:-1.10}"
fail() { echo "[FAIL] $*" >&2; exit 1; }
ok() { echo "[ok] $*"; }
field() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$2',''))" <<<"$1"; }
# Step 4 reads the trade counter through assert_order_effects rather than trusting the REST ack's
# kind — see the block there. The library is the only place a proof takes a consensus reading from
# (scripts/proofs/README.md); it uses this script's fail/ok and resolves K itself.
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/lib-consensus-readings.sh"
# `|| true` IS LOAD-BEARING, not sloppiness -- it is the same idiom applied_seq uses in the
# library next door. Under `set -euo pipefail` a member whose HTTP server is not up yet makes the
# exec's `wget` exit 4, the pipeline inherits it, and `D0="$(digest 0)"` then EXITS THE SCRIPT --
# so agree()'s 45-attempt retry loop below, which exists precisely to ride out a member catching
# up, could never take its second attempt. A reading that aborts the run is not a reading the
# caller can retry; callers here already validate the SHAPE of what comes back, so an empty answer
# is handled where it should be.
metric() { "${K[@]}" exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
  | awk -v k="$2" 'index($1, k"{")==1 || $1==k {print $2}' || true; }
digest() { "${K[@]}" exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
  | awk '/^traderx_book_open_orders/ {d=$2} /^traderx_book_order_hash/ {h=$2} END {print d, h}' || true; }
# THE TAG IS NOT DECORATION — WITHOUT IT STEP 6 IS AN IDEMPOTENT REPLAY OF STEP 3.
# The clientOrderId was "${TICKER}-<side>-<price>", and steps 3 and 6 both submit
# `order ${ACCT} Buy ${IN_PX}` — byte-identical, so the same id. The gateway dedups on
# clientOrderId (that is what yu13-clordid-suppression proves), so step 6 never submitted an order
# at all: it replayed step 3's ref and step 3's TERMINAL verdict, which step 4 had turned into
# FILLED. It surfaced as `post-failover BUY @1.10 did not rest: {"orderRef":32,"kind":4}` — an
# order that "filled" against an empty book. The arm could never have passed, and worse, it never
# exercised the post-failover re-anchor it exists to test. Found 2026-08-25, the first run that
# ever reached step 6 (step 3's reticks assertion blocked it on every pre-mint build).
order() { # account side price [tag]  -- tag disambiguates otherwise-identical submissions
  curl -s -m20 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
    -d "{\"accountId\":$1,\"ticker\":\"${TICKER}\",\"side\":\"$2\",\"quantity\":10,\"limitPrice\":$3,\"clientOrderId\":\"${TICKER}-$2-$3${4:+-$4}\"}"; }
CLEANUP_REFS=()
cleanup() { local r; for r in ${CLEANUP_REFS[@]+"${CLEANUP_REFS[@]}"}; do
  curl -s -m20 -X POST "${MATCHER_URL}/cancel" -H 'Content-Type: application/json' -d "{\"orderRef\":${r}}" >/dev/null || true
done; }
trap cleanup EXIT
# Three members, one answer — retried, because followers apply the committed tail moments later.
agree() { # agree <label>
  local i D0 D1 D2
  for i in $(seq 1 45); do
    D0="$(digest 0)"; D1="$(digest 1)"; D2="$(digest 2)"
    if [[ "${D0}" =~ ^[0-9]+\ -?[0-9]+$ && "${D0}" == "${D1}" && "${D1}" == "${D2}" ]]; then
      ok "digest agreement after $1: ${D0}"; return 0
    fi
    sleep 2
  done
  fail "members diverged after $1: [${D0}] [${D1}] [${D2}] — on a deterministic core this is permanent; THIS is the finding"
}
leader() { for m in 0 1 2; do [[ "$(metric "${m}" traderx_cluster_role 2>/dev/null)" == "1" ]] && { echo "${m}"; return 0; }; done; return 1; }
snap_count() { metric "$1" traderx_cluster_snapshots; }
# The members' own periodic trigger (60 s) — nothing to race (see yu13-stp-and-replace.sh).
snapshot_barrier() {
  local b0 b1 b2 i
  b0="$(snap_count 0)"; b1="$(snap_count 1)"; b2="$(snap_count 2)"
  [[ -n "${b0}" && -n "${b1}" && -n "${b2}" ]] || fail "cannot read snapshot counters ([${b0}] [${b1}] [${b2}])"
  echo "    waiting for a snapshot barrier (from [${b0} ${b1} ${b2}])"
  for i in $(seq 1 150); do
    if [[ "$(snap_count 0)" -gt "${b0}" && "$(snap_count 1)" -gt "${b1}" && "$(snap_count 2)" -gt "${b2}" ]]; then
      ok "snapshot barrier taken"; return 0
    fi
    sleep 1
  done
  fail "no snapshot barrier within 150s"
}

echo "=== yu17-retick-determinism, EXPECT=${EXPECT}, DESTRUCTIVE=${DESTRUCTIVE}, ticker ${TICKER} ==="
agree "baseline"

echo "--- 1. create-before-tick: /resolve registers ${TICKER} (no enable, no tick); the order must be refused"
RES="$(curl -s -m20 -X POST "${MATCHER_URL}/resolve" -H 'Content-Type: application/json' -d "{\"ticker\":\"${TICKER}\"}")"
[[ "${RES}" == *'"securityId"'* ]] || fail "/resolve did not register ${TICKER}: ${RES}"
O1="$(order "${ACCT}" Buy 50.00)"
[[ "$(field "${O1}" kind)" == "2" && "$(field "${O1}" reason)" == "UNKNOWN_SECURITY" ]] \
  || fail "registered-but-unenabled order should be refused UNKNOWN_SECURITY (the §1.1 window, book created then risk refused); got ${O1}"
ok "the §1.1 window, recorded: book created-and-anchored, order refused UNKNOWN_SECURITY (ack byte 22)"
agree "the rejected create"

echo "--- 2. tick: /seed ${TICKER} @${SEED_PX} (enable + reference)"
SEEDED="$(curl -s -m20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
  -d "{\"accountId\":${ACCT},\"tickers\":\"${TICKER}\",\"price\":${SEED_PX}}")"
[[ "${SEEDED}" == *'"seeded":true'* ]] || fail "seed did not take: ${SEEDED}"

# PER-MEMBER BASELINES, because the assertion below is a DELTA. See the block at step 3.
T0_0="$(metric 0 traderx_book_reticks || true)"
T0_1="$(metric 1 traderx_book_reticks || true)"
T0_2="$(metric 2 traderx_book_reticks || true)"
echo "--- 3. first admission after the tick: BUY @${IN_PX} rests (format 8: this is the retick)"
O2="$(order "${ACCT}" Buy "${IN_PX}")"
[[ "$(field "${O2}" kind)" == "1" ]] || fail "BUY @${IN_PX} did not rest: ${O2}"
CLEANUP_REFS+=("$(field "${O2}" orderRef)")
if [[ "${EXPECT}" == "after" ]]; then
  sleep 2   # followers apply the committed tail
  RT0="$(metric 0 traderx_book_reticks)"; RT1="$(metric 1 traderx_book_reticks)"; RT2="$(metric 2 traderx_book_reticks)"
  [[ -n "${RT0}" ]] \
    || fail "no traderx_book_reticks counter on this build — format 8 exports it beside traderx_band_reanchors (design §5)"
  # DELTAS, NOT ABSOLUTES — and this proof already said so at step 6 before it did the opposite here.
  #
  # `traderx_book_reticks` is a plain in-process field on MatchingEngine, never snapshotted, so a
  # member's ABSOLUTE reading is a function of how much log THAT PROCESS has applied since it
  # started. Any member restarted since the epoch began (this proof's own step 5 kills one, and the
  # durability proofs restart others) legitimately reads lower for ever after. Measured 2026-08-25,
  # on a cluster in perfect digest agreement, the run after a leader kill:
  #     reticks [4] [1] [4]   <- member 1 had restarted; nothing was wrong
  # so `RT0 == RT1 == RT2` was a check that cannot pass against a CORRECT system. Same defect,
  # same counter family and same fix as
  # issues/resolved/the-band-follows-market-guard-asserts-absolute-counters-and-cannot-fail.md.
  # The replicated reading is the per-member DELTA: every member applies the same commands, so each
  # must count EXACTLY ONE re-derivation across this admission — `>= 1` would pass on a book that
  # re-ticked twice, which is the §2.3.3 divergence this arm exists to exclude.
  for _m in 0 1 2; do
    _b="T0_${_m}"; _a="RT${_m}"
    [[ "${!_b}" =~ ^[0-9]+$ && "${!_a}" =~ ^[0-9]+$ ]] \
      || fail "member-${_m} reticks unreadable (${!_b:-<none>} -> ${!_a:-<none>}) — a proof that cannot read the counter it asserts on has measured nothing"
    (( ${!_a} - ${!_b} == 1 )) \
      || fail "member-${_m} counted $(( ${!_a} - ${!_b} )) re-derivation(s) across the first post-tick admission, not exactly 1 (${!_b} -> ${!_a}). Deltas on all three: [$((RT0-T0_0))] [$((RT1-T0_1))] [$((RT2-T0_2))]. A member that re-derives a different number of times than its peers holds a member-local grid — the §2.3.3 divergence."
  done
  ok "all three members counted exactly one re-derivation (deltas [$((RT0-T0_0))] [$((RT1-T0_1))] [$((RT2-T0_2))]; absolutes [${RT0}] [${RT1}] [${RT2}] differ legitimately by restart history)"
fi
agree "the post-tick admission"

if [[ "${DESTRUCTIVE}" != "1" ]]; then
  # Cancel the resting order so the prefix leaves nothing, then refuse to claim the full sequence.
  curl -s -m20 -X POST "${MATCHER_URL}/cancel" -H 'Content-Type: application/json' \
    -d "{\"orderRef\":${CLEANUP_REFS[0]}}" >/dev/null; CLEANUP_REFS=()
  agree "cleanup"
  echo "[SKIP] DESTRUCTIVE=0: the trade + snapshot barrier + leader-kill tail did not run, so the"
  echo "       full-sequence claim is NOT made. The resting-only prefix (create-before-tick,"
  echo "       reject, tick, admission$( [[ "${EXPECT}" == "after" ]] && echo ", retick agreement")) held digest-identical on all three members."
  exit 2
fi

echo "--- 4. trade: ${ACCT2} SELL @${IN_PX} crosses the resting bid"
# A FILL IS READ OFF THE TRADE COUNTER, NOT OFF THE REST ACK'S KIND.
#
# This asserted kind 3/4 (PARTIALLY_FILLED/FILLED) and could never pass. The gateway completes a
# pipelined /orders response on the FIRST direct ack carrying the request id, and a crossing order
# emits ACCEPTED *then* its per-match-step FILLs — all in one apply, all under one id — so the
# fills "find the entry already gone and are ignored" (ClusterGatewayMain, the onDirectAck block).
# kind 1 on a fully-filled aggressor is the DESIGNED answer, not a missed match.
#
# Measured 2026-08-25 on the minted rig, both books, both crossing and both booking two legs:
#     retick'd sub-dollar book (tick 10):  ack {"orderRef":26,"kind":1}   trades 12 -> 14
#     ordinary $150 book       (tick 1000): ack {"orderRef":28,"kind":1}   trades 14 -> 16
# so it is not retick-specific and never was — this arm simply never ran until the mint, because
# step 3's reticks assertion failed first on every pre-mint build.
#
# The trade counter is the reading scripts/proofs/README.md prescribes for exactly this, and
# assert_order_effects brackets it with the order-ref generator so the delta is attributable to
# THIS proof's orders rather than to the feed or another writer.
REFS_B="$(quiesced_order_refs)"; TRD_B="$(quiesced_trades)"
O3="$(order "${ACCT2}" Sell "${IN_PX}")"
O3_KIND="$(field "${O3}" kind)"
[[ "${O3_KIND}" != "2" ]] || { fail "the crossing SELL was REFUSED (kind=2 reason=$(field "${O3}" reason)): ${O3}"; }
REFS_A="$(quiesced_order_refs)"; TRD_A="$(quiesced_trades)"
# one order submitted, two trade legs (both sides of one match) — exact, not >=.
assert_order_effects "${REFS_B}" "${REFS_A}" 1 "${TRD_B}" "${TRD_A}" 2 \
  "the crossing SELL @${IN_PX} on the re-ticked book"
CLEANUP_REFS=()   # both sides are terminal now
agree "the trade"

echo "--- 5. snapshot barrier, then leader kill mid-sequence"
snapshot_barrier
LDR="$(leader)" || fail "no leader found"
# THE GATE MUST OUTLIVE THE POD IT NAMES. This used to break out of its wait on
# `get pod order-matcher-cluster-${LDR} ... containerStatuses[0].ready == true`, which is STILL
# TRUE for ~6s for the pod that was just deleted -- trap 1, written up in
# lib-consensus-readings.sh. So the loop could return before the REPLACEMENT had even started, and
# step 6's first `digest ${LDR}` then exec'd into a JVM with no HTTP server yet: `wget` exits 4,
# and under `set -e` that kills the script before agree()'s own retry loop gets a single turn.
# Measured 2026-08-25 in a full suite run -- the log ends at step 6's header with one bare
# `command terminated with exit code 4` and no [FAIL] line, which is the signature.
#
# await_member_restored is the gate that cannot do that, and this script already sources it: a
# DIFFERENT pod uid AND an applied sequence at or past where the cluster stood before the kill.
# Both halves are needed -- the uid alone lets the outgoing container answer, and the sequence
# alone is satisfied by the very pod being killed, which had already applied it.
#
# WHAT IT STILL CATCHES, because a wait that cannot fail is this family's own failure mode: a
# member that never returns as a new pod (Pending, ImagePullBackOff, a wedged StatefulSet) never
# changes uid and times out; one that returns but cannot rejoin consensus -- stuck in election,
# diverged, replaying forever -- never reaches PRE_SEQ and times out. Both are the conditions step
# 6 exists to run against, so failing here is the honest verdict, not a flake.
#
# WHAT IT DOES NOT CLAIM: that the member is Ready or serving traffic, only that it has APPLIED
# past PRE_SEQ. That is deliberate -- readiness is the reading that lied here in the first place --
# and the replicated claim is carried by step 6's digest agreement across all three members.
OLD_UID="$(member_pod_uid "${LDR}")"
[[ -n "${OLD_UID}" ]] || fail "cannot read member ${LDR}'s pod uid before killing it"
SURV=$(( (LDR + 1) % 3 ))
PRE_SEQ="$(applied_seq "${SURV}")"
[[ "${PRE_SEQ}" =~ ^[0-9]+$ ]] || fail "cannot read the applied sequence on member ${SURV} before the kill (${PRE_SEQ})"
echo "    killing leader member ${LDR} (cluster at applied ${PRE_SEQ} on member ${SURV})"
"${K[@]}" delete pod "order-matcher-cluster-${LDR}" --wait=false >/dev/null
await_member_restored "${LDR}" "${OLD_UID}" "${PRE_SEQ}" 300 \
  || fail "member ${LDR} did not rejoin and reach applied >= ${PRE_SEQ} within 300s"
# BOUNDED RETRY, not a single sample: "a leader emerges" is a converging property, and the one-shot
# read raced the election it was asserting — measured 2026-08-26 on a loaded box: the read found no
# leader, and seconds later member 0 was LEADER with all three agreed and advancing. The restored
# member rejoining (asserted above) does not imply the election has settled. 60s is a liveness
# budget; the assertion itself is unchanged and still goes red on a cluster that truly cannot elect.
NEW=""
_waited=0
while (( _waited < 60 )); do
  NEW="$(leader || true)"
  [[ -n "${NEW}" ]] && break
  sleep 2; _waited=$(( _waited + 2 ))
done
[[ -n "${NEW}" ]] || fail "no leader re-elected within 60s of killing member ${LDR}"
ok "leader ${LDR} killed and recovered; leader now ${NEW}"

echo "--- 6. re-anchor after the failover: a far-but-market-backed order still answers identically"
O4="$(order "${ACCT}" Buy "${IN_PX}" postfailover)"
[[ "$(field "${O4}" kind)" == "1" ]] || fail "post-failover BUY @${IN_PX} did not rest: ${O4}"
CLEANUP_REFS+=("$(field "${O4}" orderRef)")
C="$(curl -s -m20 -X POST "${MATCHER_URL}/cancel" -H 'Content-Type: application/json' -d "{\"orderRef\":$(field "${O4}" orderRef)}")"
[[ "$(field "${C}" canceled)" == "True" || "$(field "${C}" canceled)" == "true" ]] || fail "post-failover cancel did not take: ${C}"
CLEANUP_REFS=()
# Deliberately NO cross-member counter comparison here: counters are per-process observability,
# and the killed member restored from the snapshot skips the applies the barrier already captured,
# so its counter is legitimately lower. Comparing them would be a check that cannot pass against a
# CORRECT system (vacuous-pass-audit, "the mirror"). The replicated claim is the book digest.
agree "the failover + re-anchor"
ok "the full sequence left all three members identical, across a snapshot barrier and a leader kill"
