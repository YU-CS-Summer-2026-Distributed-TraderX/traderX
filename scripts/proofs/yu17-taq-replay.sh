#!/usr/bin/env bash
# ADR-070: the tape is the reference, replayed on an epoch clock.
#
# What is asserted, and against what:
#
#   1. THE CLOCK AND THE EXTRACT AGREE. The published price+asOf for a tape symbol must equal the
#      extract's value AT THE POSITION THE CLOCK DERIVES — re-derived here, independently, from
#      the same (now - epochStartMs) x compression arithmetic, off the same ConfigMap and Secret
#      the publisher reads. Bracketed (position before AND after the price read, minus a staleness
#      allowance) because the clock moves while we read it — the readings-taken-too-early class is
#      aimed straight at a proof like this one.
#   2. PROVENANCE IS ON THE WIRE: source = the extract's (taq-replay-2025-02), asOf inside the
#      tape's range — a real price at a fabricated time, which the `simulated` boolean cannot say.
#   3. THE EXCLUSIONS HOLD: GOOGL (suffix-merged root) and FNMA (OTC, not in TAQ) do NOT carry
#      tape provenance. A replayed GOOGL would be a price for no security that exists.
#   4. SEQUENCED, NOT JUST PUBLISHED: a member's applied /bbo `ref` — BlpRiskState.lastPrice, the
#      collar's own anchor, exported for exactly this proof — equals a recent extract value: the
#      feed adapter sequenced the replay through consensus and the members applied it. NOT the
#      /bbo `mark`, which is ADR-051's last-TRADE tier and does not move on a tick (measured
#      2026-08-26: every mark stood still under a live, sequencing feed — reading it here was
#      this proof's own first defect). The bracket tolerates FEED_FLUSH_MS=15s of lag, one window.
#   5. RESTARTS ARE INVISIBLE (decision 2): rolling the publisher does not rewind or advance the
#      clock — position stays monotonic across the roll, because it is derived, never stored.
#   6. THE END OF THE TAPE HOLDS (open question 2's ruling): with the epoch stamp forced past the
#      tape's 20h span, the price freezes at Mar 31's close and asOf STOPS ADVANCING. No loop, no
#      synthetic fallback. The stamp is restored from the PVC on every exit path.
#
#   EXPECT=before (pre-change build): /health carries no taqReplay block at all, and no equity
#   carries tape provenance — the gap, measured, so the after-arms are known to be able to fail.
#
# ROLLING, NOT DESTRUCTIVE: restarts price-publisher (twice + once in cleanup). No member, no
# gateway, no epoch, no database row. Requires the taq-replay-extract Secret and replay-epoch
# ConfigMap on the rig (start-cluster-kind.sh / rebuild_fresh_epoch create both); their absence is
# a FAIL, not a SKIP — a rig handed on without a live replay is exactly what this proof exists to
# catch.
set -uo pipefail

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K=(kubectl --context "${CTX}" -n "${NS}")
EXPECT="${EXPECT:-after}"
SYM="${SYM:-AAPL}"

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../yu15/lib-replay-epoch.sh
. "${here}/../yu15/lib-replay-epoch.sh"

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }
ok()   { echo "[ok] $*"; }

pub() { "${K[@]}" exec deploy/price-publisher -- wget -qO- "http://localhost:18100$1" 2>/dev/null; }
pyget() { python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(3)
for key in sys.argv[1].split('.'):
    if isinstance(d, dict) and key in d:
        d = d[key]
    else:
        sys.exit(4)
print('' if d is None else d)
" "$1"; }
haskey() { python3 -c "
import sys, json
d = json.load(sys.stdin)
for key in sys.argv[1].split('.'):
    if not isinstance(d, dict) or key not in d:
        print('no'); sys.exit(0)
    d = d[key]
print('yes')
" "$1"; }

STAMP_TOUCHED=0
cleanup() {
  if [[ "${STAMP_TOUCHED}" == "1" ]]; then
    echo "[cleanup] restoring the replay-epoch stamp from the member-0 PVC"
    stamp_replay_epoch >/dev/null 2>&1 || echo "[cleanup] WARNING: restamp failed — run stamp_replay_epoch by hand"
  fi
}
trap cleanup EXIT

roll_publisher() {
  local old new waited=0
  old="$("${K[@]}" get pod -l app=price-publisher -o jsonpath='{.items[0].metadata.uid}' 2>/dev/null)"
  "${K[@]}" rollout restart deploy/price-publisher >/dev/null || fail "could not restart price-publisher"
  "${K[@]}" rollout status deploy/price-publisher --timeout=300s >/dev/null \
    || fail "price-publisher's rollout did not complete"
  while (( waited < 180 )); do
    new="$("${K[@]}" get pod -l app=price-publisher -o jsonpath='{.items[0].metadata.uid}' 2>/dev/null)"
    if [[ -n "${new}" && "${new}" != "${old}" && -n "$(pub /health)" ]]; then return 0; fi
    sleep 3; waited=$((waited + 3))
  done
  fail "price-publisher never came back on a NEW pod answering /health"
}

step "yu17-taq-replay, EXPECT=${EXPECT}, symbol ${SYM}"

HEALTH="$(pub /health)"
[[ -n "${HEALTH}" ]] || fail "price-publisher /health returned nothing"

if [[ "${EXPECT}" == "before" ]]; then
  [[ "$(haskey taqReplay <<<"${HEALTH}")" == "no" ]] \
    || fail "/health already carries taqReplay — this is not the pre-change build"
  SRC="$(pub "/prices/${SYM}" | pyget source)"
  [[ "${SRC}" != taq-replay* ]] \
    || fail "${SYM} already carries tape provenance ('${SRC}') on a build claiming to predate ADR-070"
  echo
  echo "[ok] THE GAP, MEASURED: /health has no taqReplay block and ${SYM}'s source is '${SRC}' —"
  echo "     the reference is still the synthetic walk, and nothing on this build can say what"
  echo "     February 4th 2025 looked like. That is the state ADR-070 exists to end."
  exit 0
fi

# ---- 0. the replay must be LIVE, and its absence is a failing rig, not a skippable one --------
[[ "$(haskey taqReplay <<<"${HEALTH}")" == "yes" ]] \
  || fail "/health carries no taqReplay block: this build predates ADR-070"
ERR="$(pyget taqReplay.error <<<"${HEALTH}")"
[[ -z "${ERR}" ]] || fail "the replay is not live: '${ERR}'. The walk is what this rig is publishing,
       and every price it produces is completely plausible — which is why this is a FAIL."
ok "replay live: $(pyget taqReplay.symbols <<<"${HEALTH}") symbols, position $(pyget taqReplay.position.tapeDate <<<"${HEALTH}") day $(pyget taqReplay.position.dayIndex <<<"${HEALTH}") window $(pyget taqReplay.position.windowIndex <<<"${HEALTH}")"

# ---- the proof's OWN copies of the clock inputs — the same objects the publisher reads --------
EXTRACT_JSON="$("${K[@]}" get secret taq-replay-extract -o 'jsonpath={.data.extract\.json\.gz}' 2>/dev/null \
  | base64 -d | gunzip 2>/dev/null)"
[[ -n "${EXTRACT_JSON}" ]] || fail "could not read the taq-replay-extract Secret this rig's publisher mounts"
EPOCH_MS="$("${K[@]}" get configmap replay-epoch -o 'jsonpath={.data.epochStartMs}' 2>/dev/null)"
[[ "${EPOCH_MS}" =~ ^[0-9]+$ ]] || fail "replay-epoch ConfigMap is missing or unreadable ('${EPOCH_MS:-}')"

# Independent re-derivation: position(now) -> (price, asOf) straight from the extract. Emits the
# candidates for a bracket [t0 - staleness, t1] so the assertion tolerates the publisher's own
# update cadence (a ticker is re-published every ~4.4s mean; the sequenced mark lags one 15s
# flush). A match must be the PAIR — a price from one window with another window's asOf is a fail.
candidates() { # candidates <t0-ms> <t1-ms> <back-windows>  -> lines of "price asOfIso"
  python3 -c "
import sys, json
ex = json.loads(sys.argv[4])
epoch = int(sys.argv[5])
w, sess, comp = ex['windowSeconds'], ex['sessionSeconds'], ex['compression']
wpd = sess // w
days = ex['days']; series = ex['prices'][sys.argv[6]]
def linear(ms):
    tape = max(0, (ms - epoch) / 1000.0) * comp
    d = int(tape // sess); win = int((tape % sess) // w)
    if d >= len(days): d, win = len(days) - 1, wpd - 1
    return d * wpd + win
lo = max(0, linear(int(sys.argv[1])) - int(sys.argv[3]))
hi = linear(int(sys.argv[2]))
import datetime
seen = set()
for idx in range(lo, hi + 1):
    d, win = idx // wpd, idx % wpd
    if d >= len(days): d, win = len(days) - 1, wpd - 1
    if (d, win) in seen: continue
    seen.add((d, win))
    as_of = datetime.datetime.fromtimestamp((days[d]['openMs'] + (win + 1) * w * 1000) / 1000,
                                            datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.000Z')
    print(series[d][win], as_of, d * wpd + win)
" "$1" "$2" "$3" "${EXTRACT_JSON}" "${EPOCH_MS}" "${SYM}"
}
now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

# ---- 1 + 2. published price+asOf = the extract at the derived position, provenance on the wire -
step "1+2: clock/extract agreement and wire provenance for ${SYM}"
T0="$(now_ms)"
Q="$(pub "/prices/${SYM}")"
T1="$(now_ms)"
[[ -n "${Q}" ]] || fail "no quote for ${SYM}"
PX="$(pyget price <<<"${Q}")"; ASOF="$(pyget asOf <<<"${Q}")"; SRC="$(pyget source <<<"${Q}")"
WANT_SRC="$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['source'])" "${EXTRACT_JSON}")"
[[ "${SRC}" == "${WANT_SRC}" ]] \
  || fail "${SYM} source is '${SRC}', want '${WANT_SRC}' — the wire cannot say where this price came from"
[[ "${ASOF}" == 2025-0[23]-* ]] \
  || fail "${SYM} asOf is '${ASOF}', which is not inside the tape (Feb-Mar 2025). Decision 4's whole
       point is that a consumer reading this price can tell it is not this morning's."
# 3 windows of backward staleness: ~45s wall = one 15s publish gap + one flush + margin.
MATCH="$(candidates "${T0}" "${T1}" 3 | awk -v px="${PX}" -v asof="${ASOF}" \
  '($1+0)==(px+0) && $2==asof {print "yes"; exit}')"
if [[ "${MATCH}" != "yes" ]]; then
  echo "       published: price=${PX} asOf=${ASOF}" >&2
  echo "       derivable in the bracket:" >&2; candidates "${T0}" "${T1}" 3 | sed 's/^/         /' >&2
  fail "${SYM}'s published (price, asOf) pair matches NO position the clock can currently derive
       from the extract and the epoch stamp — the publisher is replaying a different mapping than
       the one this proof re-derived, which is exactly the divergence a stored cursor would cause"
fi
ok "${SYM} price ${PX} asOf ${ASOF} = the extract at the derived position (source ${SRC})"

# ---- 3. the exclusions keep their own provenance ----------------------------------------------
step "3: GOOGL and FNMA stay on the walk, honestly labelled"
for excl in GOOGL FNMA; do
  ESRC="$(pub "/prices/${excl}" | pyget source)"
  [[ -n "${ESRC}" && "${ESRC}" != taq-replay* ]] \
    || fail "${excl} carries source '${ESRC}'. A replayed ${excl} is a price for no security that
       exists (suffix-merged root / not in TAQ) — see the tick-store suffix issue."
  echo "    ${excl}: source ${ESRC}"
done
ok "exclusions hold"

# ---- 4. sequenced through consensus: the member-side reference is a tape value ----------------
step "4: a member's applied /bbo ref (the collar's anchor) for ${SYM} is a recent extract value"
T0="$(now_ms)"
REF="$("${K[@]}" exec order-matcher-cluster-0 -- sh -c 'wget -qO- http://localhost:8080/bbo 2>/dev/null' \
  | python3 -c "
import sys, json
for b in json.load(sys.stdin)['books']:
    if b.get('ticker') == '${SYM}':
        print(b.get('ref', '')); break
")"
T1="$(now_ms)"
[[ -n "${REF}" ]] || fail "member-0 /bbo carries no 'ref' for ${SYM} — either this member build
       predates the ADR-070 ref export (repin the cluster image) or the feed never sequenced a
       tick for it. Both mean the tape is not observably behind the collar."
# 5 windows back: the applied ref lags the publish by up to one FEED_FLUSH_MS (15s = one window)
# on top of the publish-cadence staleness above. Do NOT widen further — at 6+ windows a ref stuck
# on a stale flush for a full minute would start passing.
REF_OK="$(candidates "${T0}" "${T1}" 5 | awk -v m="${REF}" '($1+0)==(m+0) {print "yes"; exit}')"
[[ "${REF_OK}" == "yes" ]] \
  || fail "member-0's ${SYM} ref ${REF} is not any extract value derivable in the last ~5 windows —
       the replay is being published but is NOT what consensus is sequencing (adapter dead or
       conflating something else; run the roll_feed_adapter gate)"
ok "member-0 ${SYM} ref ${REF} came off the tape through consensus"

# ---- 5. a publisher restart is invisible to the clock -----------------------------------------
step "5: restart price-publisher; position must resume, not reset (stateless clock)"
IDX_BEFORE="$(candidates "$(now_ms)" "$(now_ms)" 0 | awk '{print $3; exit}')"
roll_publisher
H2="$(pub /health)"
D2="$(pyget taqReplay.position.dayIndex <<<"${H2}")"; W2="$(pyget taqReplay.position.windowIndex <<<"${H2}")"
[[ "${D2}" =~ ^[0-9]+$ && "${W2}" =~ ^[0-9]+$ ]] || fail "no readable position after the restart"
WPD="$(python3 -c "import json,sys; ex=json.loads(sys.argv[1]); print(ex['sessionSeconds']//ex['windowSeconds'])" "${EXTRACT_JSON}")"
IDX_AFTER=$(( D2 * WPD + W2 ))
(( IDX_AFTER >= IDX_BEFORE )) \
  || fail "position went BACKWARD across a restart (${IDX_BEFORE} -> ${IDX_AFTER}): the clock is not
       stateless — something stored a cursor and restored it stale"
ok "position ${IDX_BEFORE} -> ${IDX_AFTER} across the restart: derived, never stored"

# ---- 6. the end of the tape HOLDS ------------------------------------------------------------
step "6: force the clock past the tape's end; the price holds at Mar 31's close, asOf frozen"
SPAN_S="$(python3 -c "import json,sys; ex=json.loads(sys.argv[1]); print(int(len(ex['days'])*ex['sessionSeconds']/ex['compression']))" "${EXTRACT_JSON}")"
LAST_PX="$(python3 -c "import json,sys; ex=json.loads(sys.argv[1]); print(ex['prices']['${SYM}'][-1][-1])" "${EXTRACT_JSON}")"
LAST_ASOF="$(python3 -c "
import json, sys, datetime
ex = json.loads(sys.argv[1])
ms = ex['days'][-1]['openMs'] + ex['sessionSeconds'] * 1000
print(datetime.datetime.fromtimestamp(ms/1000, datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.000Z'))" "${EXTRACT_JSON}")"
STAMP_TOUCHED=1
PAST=$(( $(now_ms) - (SPAN_S + 3600) * 1000 ))
"${K[@]}" create configmap replay-epoch --from-literal=epochStartMs="${PAST}" \
  --dry-run=client -o yaml | "${K[@]}" apply -f - >/dev/null || fail "could not re-stamp replay-epoch"
roll_publisher
# WAIT FOR THE FIRST REPLAY TICK before reading a price. A freshly restarted publisher serves the
# BOOTSTRAP quote (the previous close, walk provenance, no asOf) until the publish loop first
# samples the ticker — up to ~15s at the 25%-batch cadence. Reading before that is the
# readings-taken-too-early class in its purest form: the first suite run read 248.334 (the
# bootstrap close) an instant after rollout and 222.47 (the correct hold) ten seconds later, and
# blamed the hold. The gate is provenance, not time: the assertion below stays as sharp as ever.
waited=0
until [[ "$(pub "/prices/${SYM}" | pyget source)" == "${WANT_SRC}" ]]; do
  (( waited >= 60 )) && fail "no replay tick reached ${SYM} within 60s of the publisher restart —
       the hold cannot be read because the replay never started (check /health.taqReplay.error)"
  sleep 3; waited=$((waited + 3))
done
H3="$(pub /health)"
[[ "$(pyget taqReplay.position.held <<<"${H3}")" == "True" || "$(pyget taqReplay.position.held <<<"${H3}")" == "true" ]] \
  || fail "clock forced $(( SPAN_S / 3600 ))h+1h past the epoch and /health does not report held=true
       (position: $(pyget taqReplay.position.tapeDate <<<"${H3}") day $(pyget taqReplay.position.dayIndex <<<"${H3}"))"
Q1="$(pub "/prices/${SYM}")"
sleep 10
Q2="$(pub "/prices/${SYM}")"
P1="$(pyget price <<<"${Q1}")"; A1="$(pyget asOf <<<"${Q1}")"
P2="$(pyget price <<<"${Q2}")"; A2="$(pyget asOf <<<"${Q2}")"
[[ "${P1}" == "${LAST_PX}" && "${P2}" == "${LAST_PX}" ]] \
  || fail "held price is ${P1} then ${P2}, want the last close ${LAST_PX} — looping or walking instead
       of holding (the two forbidden endings: a fabricated seam, or a silent provenance change)"
[[ "${A1}" == "${LAST_ASOF}" && "${A2}" == "${LAST_ASOF}" ]] \
  || fail "held asOf moved or is wrong (${A1} then ${A2}, want ${LAST_ASOF} frozen) — asOf is the
       one honest witness that this reference is old, and it must visibly age, not advance"
SRC_HELD="$(pyget source <<<"${Q2}")"
[[ "${SRC_HELD}" == "${WANT_SRC}" ]] \
  || fail "held source is '${SRC_HELD}' — the hold must not change provenance category (decision 4)"
ok "held at ${LAST_PX}, asOf frozen at ${LAST_ASOF}, source still ${WANT_SRC}"

step "restore the real epoch stamp"
stamp_replay_epoch || fail "could not restore the replay-epoch stamp from the PVC"
STAMP_TOUCHED=0
H4="$(pub /health)"
HELD4="$(pyget taqReplay.position.held <<<"${H4}")"
[[ "${HELD4}" == "False" || "${HELD4}" == "false" ]] \
  || fail "stamp restored but the publisher still reports held=${HELD4}"
ok "clock back on the epoch's own mint; position $(pyget taqReplay.position.tapeDate <<<"${H4}") day $(pyget taqReplay.position.dayIndex <<<"${H4}")"

echo
echo "[PASS] yu17-taq-replay: the tape is the reference — replayed at the derived position,"
echo "       sequenced through consensus, provenance and asOf on the wire, exclusions honest,"
echo "       restarts invisible, and the end of the tape holds at Mar 31's close."
