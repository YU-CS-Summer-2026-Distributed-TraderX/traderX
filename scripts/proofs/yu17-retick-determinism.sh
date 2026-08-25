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
metric() { "${K[@]}" exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
  | awk -v k="$2" 'index($1, k"{")==1 || $1==k {print $2}'; }
digest() { "${K[@]}" exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
  | awk '/^traderx_book_open_orders/ {d=$2} /^traderx_book_order_hash/ {h=$2} END {print d, h}'; }
order() { # account side price
  curl -s -m20 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
    -d "{\"accountId\":$1,\"ticker\":\"${TICKER}\",\"side\":\"$2\",\"quantity\":10,\"limitPrice\":$3,\"clientOrderId\":\"${TICKER}-$2-$3\"}"; }
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

T0_0="$(metric 0 traderx_book_reticks || true)"
echo "--- 3. first admission after the tick: BUY @${IN_PX} rests (format 8: this is the retick)"
O2="$(order "${ACCT}" Buy "${IN_PX}")"
[[ "$(field "${O2}" kind)" == "1" ]] || fail "BUY @${IN_PX} did not rest: ${O2}"
CLEANUP_REFS+=("$(field "${O2}" orderRef)")
if [[ "${EXPECT}" == "after" ]]; then
  sleep 2   # followers apply the committed tail
  RT0="$(metric 0 traderx_book_reticks)"; RT1="$(metric 1 traderx_book_reticks)"; RT2="$(metric 2 traderx_book_reticks)"
  [[ -n "${RT0}" ]] \
    || fail "no traderx_book_reticks counter on this build — EXPECTED RED until the format-8 mint (design §5): the re-derivation and its counter ship with the mint"
  [[ "${RT0}" =~ ^[0-9]+$ && "${RT0}" == "${RT1}" && "${RT1}" == "${RT2}" ]] \
    || fail "members disagree the retick happened: reticks [${RT0}] [${RT1}] [${RT2}] — a member-local grid is the §2.3.3 divergence"
  [[ "${T0_0}" =~ ^[0-9]+$ && "${RT0}" -gt "${T0_0}" ]] || fail "no retick was counted (${T0_0} -> ${RT0}) on the first post-tick admission"
  ok "all three members counted the same retick (${RT0})"
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
O3="$(order "${ACCT2}" Sell "${IN_PX}")"
O3_KIND="$(field "${O3}" kind)"
[[ "${O3_KIND}" == "3" || "${O3_KIND}" == "4" ]] || { CLEANUP_REFS+=("$(field "${O3}" orderRef)"); fail "the cross did not fill (kind=${O3_KIND}): ${O3}"; }
CLEANUP_REFS=()   # both sides are terminal now
agree "the trade"

echo "--- 5. snapshot barrier, then leader kill mid-sequence"
snapshot_barrier
LDR="$(leader)" || fail "no leader found"
echo "    killing leader member ${LDR}"
"${K[@]}" delete pod "order-matcher-cluster-${LDR}" --wait=false >/dev/null
for i in $(seq 1 90); do
  NEW="$(leader || true)"
  [[ -n "${NEW}" && "$("${K[@]}" get pod "order-matcher-cluster-${LDR}" -o jsonpath='{.status.containerStatuses[0].ready}' 2>/dev/null)" == "true" ]] && break
  sleep 2
done
[[ -n "${NEW:-}" ]] || fail "no leader re-elected and member ${LDR} ready within 180s"
ok "leader ${LDR} killed and recovered; leader now ${NEW}"

echo "--- 6. re-anchor after the failover: a far-but-market-backed order still answers identically"
O4="$(order "${ACCT}" Buy "${IN_PX}")"
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
