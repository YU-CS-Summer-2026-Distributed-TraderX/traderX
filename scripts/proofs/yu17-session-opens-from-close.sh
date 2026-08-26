#!/usr/bin/env bash
# yu17-session-opens-from-close.sh — ADR-069 rules 1-4: the session opens where the last one closed.
#
# THE CLAIM (specs/YU17-otc-rates/system/adr-069-the-session-opens-where-the-last-one-closed.md):
# price-publisher's opening price comes from the PRIOR PUBLISHED CLOSE, resolved server-side by
# trade-processor, with the static seed retained as the floor — and /health names which rung won,
# per instrument class.
#
#   EXPECT=before (pre-change build): GET /eod/session/previous does not exist, price-publisher's
#     /health carries no `openingSource` at all, and both controls open on the STATIC SEED while a
#     published close for them sits in the database. That is ADR-069's measured gap: open_today has
#     no causal connection to close_yesterday, and nothing in the system can say so.
#   EXPECT=after  (this change): the controls open AT their published closes WHILE A NEWER DRAFT
#     EXISTS, /health names the winning source per class, and switching the read off flips every
#     one of those readings back.
#
# THE ARM THAT MATTERS MOST IS STEP 3, AND IT IS COMPOSED ON PURPOSE. Asserting "the endpoint
# refuses a DRAFT" and separately "the publisher opens from a close" leaves the joint claim
# untested — and the joint claim IS rule 2: *the publisher opened on the PUBLISHED price while a
# newer DRAFT existed*. So step 2 plants the DRAFTs and LEAVES THEM PLANTED, and step 3 restarts
# the publisher underneath them. One restart proves both, and the three possible readings are
# mutually exclusive, so a failure names its own cause:
#
#     openPrice == published close   -> rule 2 holds and the session opened from it
#     openPrice == the DRAFT's price -> the hierarchy prefers a DRAFT (the defect rule 2 forbids)
#     openPrice == the static seed   -> the read failed and fell through
#
# WHY STEP 4 IS NOT OPTIONAL EITHER. This ADR's trap is that a failed close-read and a successful
# one produce prices that are equally plausible — "there is no wrong number to notice". A green
# step 3 alone is consistent with the seed happening to sit near the close. Step 4 switches the
# read off ON THE SAME BUILD and requires every reading to change; step 5 switches it back and
# requires them to return. That is what makes step 3 evidence rather than a coincidence.
#
# THE ARMS ARE REFUSED RATHER THAN ROUNDED. Step 0 asserts that the published close, the static
# seed and the planted DRAFT price are three DIFFERENT numbers. If any two ever coincide the arms
# stop discriminating, and the proof exits 2 saying so instead of reporting a pass.
#
# ROLLING, NOT DESTRUCTIVE. It restarts the price-publisher Deployment three times on the after
# arm and once on the before arm. No epoch, no PVC wipe, no member roll, no gateway roll, nothing
# sequenced through consensus. The restart re-seeds the feed, so the reference steps by
# |close - seed| — printed in step 0, and of the order ADR-069 measured (a few percent).
#
# ---- WHAT THIS WRITES TO A LIVE DATABASE, AND WHAT A KILL LEAVES BEHIND -----------------------
#
# `eod_price_snapshot` is REAL, LIVE AND GROWING — measured 4121 rows across 141 sessions (49 of
# them DRAFT) on 2026-08-25, having read 3981 minutes earlier and 3022 in an earlier session. The
# EOD chain keeps cutting. Three consequences, all load-bearing:
#
#   1. NO ABSOLUTE ROW COUNT IS ASSERTED ANYWHERE. A count is a live counter, so a fixed
#      expectation is stale the moment it is written.
#   2. NOR IS DELTA-ZERO ACROSS THE RUN, for the same reason — a session cut by the hourly CronJob
#      or by a concurrent proof legitimately grows the table mid-run, and a delta-zero assertion
#      would fail on somebody else's correct behaviour. What IS asserted is the pair that actually
#      means "I cleaned up after myself and touched nothing else": zero rows carrying my marker
#      survive, and the totals did not DECREASE. A decrease is the only reading that can mean this
#      script deleted a row it did not plant.
#   3. NOTHING IS DELETED THAT THIS SCRIPT DID NOT CREATE. Both cleanup statements are keyed on a
#      marker no other producer writes: snapshot rows carry
#      override_reason='yu17-session-opens-from-close', and their session headers carry the
#      sentinel instrument_count=4242 (verified against the live table to match zero existing rows
#      before it was adopted). No LEFT JOIN antijoin, no date-range sweep, nothing that could
#      match a real session.
#
# IF THIS SCRIPT IS KILLED BETWEEN PLANT AND CLEAN — SIGKILL, a dead kubectl session, the box
# going down — no EXIT trap fires and the rig is left holding one or two DRAFT sessions dated on
# or before the last published close, each with a single marked row for the equity control. What
# that costs while it sits there: nothing to the opening price (rule 2 refuses a DRAFT, which is
# the property under test) and nothing to the SPIKE baseline (priorPublishedClose reads PUBLISHED
# rows only). It is still not harmless — it is precisely the object rule 2 exists to refuse, so a
# leaked one makes the next run's step 2 test a rig that was already planted. That is why the
# pre-clean at the top of step 0 runs unconditionally and BEFORE any baseline is captured: it
# removes every stratum from every previous run, not just this one's.
#
# Usage:  [EXPECT=before|after] [EQ_CTL=AAPL] [UST_CTL=UST-20280630] ./yu17-session-opens-from-close.sh
set -uo pipefail

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K=(kubectl --context "${CTX}" -n "${NS}")
EXPECT="${EXPECT:-after}"
# GOOGL, not AAPL, since ADR-070: a TAPE symbol's `source`/`price` flip to taq-replay-2025-02 on
# its first published tick, so the previous-close provenance this proof asserts survives only on
# an equity the replay excludes. GOOGL is exactly that (suffix-merged root, deliberately kept on
# the walk) and it bootstraps from the prior close precisely as before. The openPrice half of the
# assertion never cared — replay moves price/source/asOf and leaves the bootstrap witness alone —
# but the wire-provenance half does.
EQ_CTL="${EQ_CTL:-GOOGL}"
UST_CTL="${UST_CTL:-UST-20280630}"
DB_DEPLOY="${DB_DEPLOY:-eod-price-db}"
# The two markers. Nothing else in this system writes either.
MARKER='yu17-session-opens-from-close'
HDR_SENTINEL=4242
# UTC, not host-local: trade-processor keys EOD sessions by UTC date and runs in a UTC container,
# and price-publisher resolves its own opening date the same way. A host-local date here would ask
# for the wrong "strictly earlier than" every evening — the trap yu06-quality-gate documents.
DATE="$(date -u +%F)"

fail() { echo "[FAIL] $*" >&2; exit 1; }
skip() { echo "[SKIP] $*" >&2; exit 2; }
step() { echo; echo "=== $* ==="; }
ok()   { echo "[ok] $*"; }

db() { "${K[@]}" exec "deploy/${DB_DEPLOY}" -c mariadb -- \
  mariadb -utraderx -ptraderx traderx -sN -e "$1" 2>/dev/null; }

# Read from the cluster, never hardcoded: this rig's master secret is deliberately NOT the dev
# literal, and a wrong one fails as an opaque 401 with nothing naming the secret as the cause.
MASTER="${AUTH_MASTER_SECRET:-$("${K[@]}" get secret auth-secrets -o 'jsonpath={.data.dev-token-master-secret}' 2>/dev/null | base64 -d 2>/dev/null)}"
[[ -n "${MASTER}" ]] || fail "could not read auth-secrets/dev-token-master-secret from the rig"
TOK='T=$(curl -s -m8 -X POST http://trade-processor:18091/auth/dev-token -H "X-Auth-Master-Secret: '"${MASTER}"'" -H "Content-Type: application/json" -d "{\"subject\":\"proof-open\",\"accounts\":[],\"admin\":true,\"ttlSeconds\":900}")'
api() { "${K[@]}" exec deploy/trade-processor -- sh -c "${TOK}; $1" 2>/dev/null; }
pub() { "${K[@]}" exec deploy/price-publisher -- wget -qO- "http://localhost:18100$1" 2>/dev/null; }

# python3 on the HOST, reading pod output on stdin. `jq` is not in these images.
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
eq() { python3 -c "import sys; sys.exit(0 if abs(float('$1') - float('$2')) <= float('$3') else 1)"; }

# MARKER-SCOPED, BOTH STATEMENTS. Never a date sweep, never an antijoin — this runs against a
# database holding thousands of real rows that ADR-069 itself depends on, in the same schema as
# the order book. The only rows either statement can reach are rows this script wrote.
unplant() {
  db "DELETE FROM eod_price_snapshot WHERE override_reason = '${MARKER}';
      DELETE FROM eod_price_session WHERE status = 'DRAFT' AND instrument_count = ${HDR_SENTINEL};" \
    >/dev/null 2>&1
}

ENV_TOUCHED=0
cleanup() {
  unplant
  if [[ "${ENV_TOUCHED}" == "1" ]]; then
    echo "[cleanup] restoring price-publisher's PRICE_OPEN_FROM_PREVIOUS_CLOSE"
    "${K[@]}" set env deploy/price-publisher PRICE_OPEN_FROM_PREVIOUS_CLOSE- >/dev/null 2>&1
    "${K[@]}" rollout status deploy/price-publisher --timeout=240s >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# A restart is only "done" when the pod UID has CHANGED and the new pod answers. `rollout status`
# returning is a statement about the Deployment; `kubectl exec deploy/...` during a roll can still
# resolve the OUTGOING pod, whose /health carries the PREVIOUS bootstrap's answer — which is
# exactly the reading this proof is about, so believing it would be a self-inflicted false green.
restart_publisher() {
  local old new waited=0
  old="$("${K[@]}" get pod -l app=price-publisher -o jsonpath='{.items[0].metadata.uid}' 2>/dev/null)"
  "${K[@]}" rollout restart deploy/price-publisher >/dev/null || fail "could not restart price-publisher"
  "${K[@]}" rollout status deploy/price-publisher --timeout=300s >/dev/null \
    || fail "price-publisher's rollout did not complete"
  while (( waited < 180 )); do
    new="$("${K[@]}" get pod -l app=price-publisher -o jsonpath='{.items[0].metadata.uid}' 2>/dev/null)"
    if [[ -n "${new}" && "${new}" != "${old}" ]] && [[ "$(pub /health | pyget status)" == "ok" ]]; then
      echo "    price-publisher restarted (new pod after ${waited}s)"
      return 0
    fi
    sleep 3; waited=$((waited + 3))
  done
  fail "price-publisher never came back on a NEW pod answering /health within 180s"
}

echo "=== yu17-session-opens-from-close, EXPECT=${EXPECT}, controls ${EQ_CTL} / ${UST_CTL} ==="

# ---------------------------------------------------------------------------------------------
step "0. preflight: resolve rule 2 out of the database, and prove the arms discriminate"

# PRE-CLEAN FIRST, BEFORE THE BASELINE IS TAKEN. An EXIT trap does not survive SIGKILL, and a
# leaked DRAFT from a killed run would make step 2 plant on top of an already-planted rig — and
# would be counted into the baseline the cleanup is later checked against.
unplant

read -r B_SNAP B_SESS <<<"$(db "SELECT (SELECT COUNT(*) FROM eod_price_snapshot),
                                       (SELECT COUNT(*) FROM eod_price_session);" | tr '\t' ' ')"
[[ "${B_SNAP}" =~ ^[0-9]+$ && "${B_SESS}" =~ ^[0-9]+$ ]] \
  || fail "could not read the baseline row counts (got '${B_SNAP:-}' '${B_SESS:-}')"
echo "    baseline: ${B_SNAP} snapshot rows across ${B_SESS} sessions (a LIVE counter — nothing below asserts it)"

# The oracle, written differently from the implementation on purpose: the repository groups by
# date and takes MAX(version) INSIDE the status filter; this orders and limits. Same answer, and
# an independent spelling of it.
read -r RES_DATE RES_VER <<<"$(db "SELECT session_date, version FROM eod_price_session
   WHERE status = 'PUBLISHED' AND session_date < '${DATE}'
   ORDER BY session_date DESC, version DESC LIMIT 1;" | tr '\t' ' ')"
[[ "${RES_DATE}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ && "${RES_VER}" =~ ^[0-9]+$ ]] \
  || skip "no PUBLISHED session strictly before ${DATE} on this rig (got '${RES_DATE:-}' '${RES_VER:-}'):
       there is no previous close for a session to open FROM, so neither arm can say anything.
       Cut and publish one (yu06-quality-gate's steps 1-4 are the recipe) and re-run."
ok "rule 2 resolves to ${RES_DATE} v${RES_VER} (latest PUBLISHED version of the most recent earlier date)"

close_of() { db "SELECT closing_price FROM eod_price_snapshot
    WHERE session_date = '${RES_DATE}' AND version = ${RES_VER} AND security = '$1'
      AND closing_price IS NOT NULL;" | tr -d '[:space:]'; }
CLOSE_EQ="$(close_of "${EQ_CTL}")"
CLOSE_UST="$(close_of "${UST_CTL}")"
[[ "${CLOSE_EQ}" =~ ^[0-9]+\.[0-9]+$ ]] \
  || skip "${EQ_CTL} has no non-null close in ${RES_DATE} v${RES_VER} (got '${CLOSE_EQ:-}'):
       the equity control is the load-bearing reading and there is nothing to compare against."
[[ "${CLOSE_UST}" =~ ^[0-9]+\.[0-9]+$ ]] \
  || skip "${UST_CTL} has no non-null close in ${RES_DATE} v${RES_VER} (got '${CLOSE_UST:-}'):
       the bond control proves the percent-of-par conversion, which nothing else here covers."

# THE SEED THE POD ACTUALLY CARRIES, read out of the running container — not out of the repo. The
# claim is about what THIS image opens from, and a spec-layer file is a proxy for that (a pod can
# be running an older build than the tree; this project has paid for that reading twice).
SEEDS="$("${K[@]}" exec deploy/price-publisher -- cat /app/data/snapshot-prices.json 2>/dev/null)"
[[ -n "${SEEDS}" ]] || fail "could not read /app/data/snapshot-prices.json out of the price-publisher pod"
# openPrice, not closePrice, and the difference is not cosmetic: normalizeQuote seeds the wire's
# `openPrice` field from the snapshot's OPEN (AAPL 240.1) while `price`/`closePrice` come from its
# CLOSE (241.8). Measured off-rig 2026-08-25 against the real module. Comparing against the wrong
# one fails the red arm for a reason that has nothing to do with this ADR.
# floor(x*1000+0.5) mirrors JS Math.round rather than Python's banker's rounding, so the two
# implementations agree on a .5 that a 3dp seed could legitimately carry.
SEED_EQ="$(python3 -c "
import sys, json, math
d = json.load(sys.stdin)['${EQ_CTL}']
print(math.floor(float(d['openPrice']) * 1000 + 0.5) / 1000)
" <<<"${SEEDS}")" || fail "${EQ_CTL} is not in the pod's seed file"
SEED_UST="$(python3 -c "
import sys, json, math
d = json.load(sys.stdin)['${UST_CTL}']
pct = math.floor(float(d['runtimeSeedCleanPrice']) * 1000 + 0.5) / 1000   # treasury.round3
print('%.6f' % (math.floor(pct * 10000 + 0.5) / 1000000))                 # treasury.pctToFraction
" <<<"${SEEDS}")" || fail "${UST_CTL} is not in the pod's seed file"

# The price the planted DRAFT will carry. DERIVED from the real close, never a literal: it has to
# be distinguishable from both the close and the seed to discriminate, and PLAUSIBLE in case a
# defect ever does put it on the wire — a sentinel like 424242 reaching the feed would move the
# ADR-066 band hard enough to stranded-cancel a peer's resting orders.
DRAFT_PX="$(python3 -c "print('%.3f' % (round(${CLOSE_EQ}, 3) + 1.111))")"
echo "    ${EQ_CTL}:  close ${CLOSE_EQ}  seed ${SEED_EQ}  planted-DRAFT ${DRAFT_PX}"
echo "    ${UST_CTL}: close ${CLOSE_UST}  seed ${SEED_UST}"

# THE THREE NUMBERS MUST BE THREE NUMBERS. If any two coincide, at least one arm predicts the same
# reading as another and the proof stops discriminating. The thresholds are tied to the tolerances
# step 3 asserts with, not picked to look small: the equity is compared EXACTLY (3dp prices, so a
# real difference is at least 1e-3) and the bond to 1e-5, so its gap must be 10x that at minimum.
python3 -c "
import sys
eq = [('close', ${CLOSE_EQ}), ('seed', ${SEED_EQ}), ('draft', ${DRAFT_PX})]
bad = [(a[0], b[0]) for i, a in enumerate(eq) for b in eq[i+1:] if abs(a[1] - b[1]) < 1e-3]
if abs(${CLOSE_UST} - ${SEED_UST}) < 1e-4:
    bad.append(('bond close', 'bond seed'))
sys.exit(0 if not bad else 1)
" || skip "two of the numbers this proof turns on coincide (${EQ_CTL}: close ${CLOSE_EQ} / seed
       ${SEED_EQ} / draft ${DRAFT_PX}; ${UST_CTL}: close ${CLOSE_UST} / seed ${SEED_UST}), so at
       least two arms predict the same reading and a green would mean nothing. Pick other controls:
       EQ_CTL=<ticker> UST_CTL=<key> bash scripts/proofs/yu17-session-opens-from-close.sh"
ok "close, seed and planted-DRAFT are three distinct numbers — every arm predicts a different reading"

# ---------------------------------------------------------------------------------------------
step "1. the previous-session read (rule 3): resolved server-side, over HTTP"

PREV_CODE="$(api "curl -s -o /dev/null -w '%{http_code}' -m15 \
  'http://trade-processor:18091/eod/session/previous?before=${DATE}' -H \"Authorization: Bearer \$T\"")"
PREV_BODY="$(api "curl -s -m15 \
  'http://trade-processor:18091/eod/session/previous?before=${DATE}' -H \"Authorization: Bearer \$T\"")"

# SHAPE-TEST THE CODE BEFORE BRANCHING ON IT. A failed `kubectl exec` prints nothing and curl's own
# 000 means the connection never happened — and on the before arm BOTH would sail straight through
# a bare `!= 200` and be reported as "the endpoint is correctly absent". No answer and the answer
# no are different verdicts.
[[ "${PREV_CODE}" =~ ^[0-9]{3}$ && "${PREV_CODE}" != "000" ]] \
  || fail "no HTTP answer from trade-processor for /eod/session/previous (got '${PREV_CODE}'):
       the exec or the connection failed, which says nothing about whether the route exists"

if [[ "${EXPECT}" == "before" ]]; then
  [[ "${PREV_CODE}" == "200" ]] \
    && fail "GET /eod/session/previous answered 200 on a build that should not have it — this is
       not the pre-change build, so nothing below measures the 'before' state"
  ok "no previous-session read on this build (HTTP ${PREV_CODE}) — rule 2 has no implementation to be wrong"
else
  [[ "${PREV_CODE}" == "200" ]] \
    || fail "GET /eod/session/previous?before=${DATE} answered ${PREV_CODE}, not 200 (body: ${PREV_BODY:0:200})"
  GOT_DATE="$(pyget sessionDate <<<"${PREV_BODY}")"
  GOT_VER="$(pyget version <<<"${PREV_BODY}")"
  GOT_STATUS="$(pyget status <<<"${PREV_BODY}")"
  [[ "${GOT_DATE}" == "${RES_DATE}" && "${GOT_VER}" == "${RES_VER}" ]] \
    || fail "the endpoint resolved ${GOT_DATE} v${GOT_VER}; the database says ${RES_DATE} v${RES_VER}"
  [[ "${GOT_STATUS}" == "PUBLISHED" ]] || fail "the endpoint returned a ${GOT_STATUS} session"
  ok "endpoint resolves ${GOT_DATE} v${GOT_VER} PUBLISHED, matching the database oracle exactly"
fi

# ---------------------------------------------------------------------------------------------
step "2. plant DRAFTs newer than the published close, and leave them planted"

PLANTED=()
DRAFT_V=0
if [[ "${EXPECT}" == "before" ]]; then
  echo "    N/A on this build: there is no previous-session read, so there is nothing that could"
  echo "    prefer a DRAFT, and planting one would write to a live database to prove nothing."
  echo "    Recorded as absent rather than passed — the defect on this build is step 3's."
else
  plant_draft() { # plant_draft <session_date> <version>
    db "INSERT INTO eod_price_session (session_date, version, status, instrument_count, flagged_count, created_at)
          VALUES ('$1', $2, 'DRAFT', ${HDR_SENTINEL}, 0, NOW());
        INSERT INTO eod_price_snapshot (session_date, version, security, closing_price, quality, override_reason)
          VALUES ('$1', $2, '${EQ_CTL}', ${DRAFT_PX}, 'OVERRIDDEN', '${MARKER}');" >/dev/null \
      || fail "could not plant the DRAFT at $1 v$2"
    # VERIFY THE PLANT. "I ran the INSERT" is not "the row is there", and a DRAFT that never landed
    # makes every assertion below pass against nothing at all — the destructive-precondition rule
    # applied to a constructive one.
    local n
    n="$(db "SELECT COUNT(*) FROM eod_price_session h JOIN eod_price_snapshot s
               ON s.session_date = h.session_date AND s.version = h.version
             WHERE h.session_date='$1' AND h.version=$2 AND h.status='DRAFT'
               AND s.override_reason='${MARKER}';" | tr -d '[:space:]')"
    [[ "${n}" == "1" ]] || fail "the DRAFT at $1 v$2 is not in the table after the INSERT (count=${n:-?})"
    PLANTED+=("$1 $2")
  }
  resolved_now() { api "curl -s -m15 'http://trade-processor:18091/eod/session/previous?before=${DATE}' \
      -H \"Authorization: Bearer \$T\"" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d['sessionDate'], d['version'])
"; }

  # 2a — same date, HIGHER version. This is the exact bug a MAX(version) taken OUTSIDE the status
  # filter produces: the newest version wins, and it is a DRAFT.
  DRAFT_V=$(( $(db "SELECT COALESCE(MAX(version),0) FROM eod_price_session WHERE session_date='${RES_DATE}';" | tr -d '[:space:]') + 1 ))
  plant_draft "${RES_DATE}" "${DRAFT_V}"
  read -r A_DATE A_VER <<<"$(resolved_now)"
  [[ "${A_DATE}" == "${RES_DATE}" && "${A_VER}" == "${RES_VER}" ]] \
    || fail "a DRAFT at ${RES_DATE} v${DRAFT_V} displaced the published close: the endpoint now
       resolves ${A_DATE:-?} v${A_VER:-?} instead of ${RES_DATE} v${RES_VER}"
  ok "DRAFT ${RES_DATE} v${DRAFT_V} (higher version, same date) ignored by the endpoint"

  # 2b — a LATER date, when one exists strictly between the resolved date and today. The other half
  # of rule 2: "most recent session_date" means the most recent one that HAS a published version,
  # not the most recent one that exists.
  LATER="$(python3 -c "
import datetime as d
res = d.date.fromisoformat('${RES_DATE}')
today = d.date.fromisoformat('${DATE}')
nxt = res + d.timedelta(days=1)
print(nxt.isoformat() if nxt < today else '')
")"
  if [[ -n "${LATER}" ]]; then
    LATER_V=$(( $(db "SELECT COALESCE(MAX(version),0) FROM eod_price_session WHERE session_date='${LATER}';" | tr -d '[:space:]') + 1 ))
    plant_draft "${LATER}" "${LATER_V}"
    read -r B_DATE B_VER <<<"$(resolved_now)"
    [[ "${B_DATE}" == "${RES_DATE}" && "${B_VER}" == "${RES_VER}" ]] \
      || fail "a DRAFT dated ${LATER} (later than the published close, still before ${DATE}) won:
       the endpoint resolves ${B_DATE:-?} v${B_VER:-?} instead of ${RES_DATE} v${RES_VER}"
    ok "DRAFT dated ${LATER} (a later date entirely) ignored by the endpoint"
  else
    echo "    2b not planted: ${RES_DATE} is the day before ${DATE}, so no date sits strictly"
    echo "    between them. 2a covers the version half of rule 2; the date half is covered"
    echo "    whenever the rig's newest published close is older than yesterday."
  fi
  echo "    ${#PLANTED[@]} DRAFT(s) left in place for step 3 — the publisher restarts underneath them"
fi

# ---------------------------------------------------------------------------------------------
step "3. the open itself: restart the publisher WITH the newer DRAFTs in place"

restart_publisher
HEALTH="$(pub /health)"
[[ -n "${HEALTH}" ]] || fail "price-publisher's /health returned nothing after the restart"
EQ_Q="$(pub "/prices/${EQ_CTL}")"
UST_Q="$(pub "/prices/${UST_CTL}")"
[[ -n "${EQ_Q}" && -n "${UST_Q}" ]] || fail "no quote for a control after the restart"

# openPrice, NOT price: openPrice is set once at bootstrap and no tick ever mutates it, so it is
# the stable witness of where this session OPENED. `price` walks immediately and would make every
# assertion below a race.
EQ_OPEN="$(pyget openPrice <<<"${EQ_Q}")"
EQ_SRC="$(pyget source <<<"${EQ_Q}")"
UST_OPEN="$(pyget openPrice <<<"${UST_Q}")"
HAS_OS="$(haskey openingSource <<<"${HEALTH}")"
echo "    ${EQ_CTL}: openPrice ${EQ_OPEN} source ${EQ_SRC} | ${UST_CTL}: openPrice ${UST_OPEN} | openingSource present: ${HAS_OS}"

if [[ "${EXPECT}" == "before" ]]; then
  [[ "${HAS_OS}" == "no" ]] \
    || fail "/health already carries openingSource — this is not the pre-change build"
  eq "${EQ_OPEN}" "${SEED_EQ}" 1e-9 \
    || fail "${EQ_CTL} opened at ${EQ_OPEN}, which is neither the static seed ${SEED_EQ} nor
       anything this build can produce — the reading is not the one this arm describes"
  [[ "${EQ_SRC}" == "snapshot" ]] || fail "${EQ_CTL}'s source is '${EQ_SRC}', expected 'snapshot'"
  eq "${UST_OPEN}" "${SEED_UST}" 1e-9 \
    || fail "${UST_CTL} opened at ${UST_OPEN}, expected the static seed ${SEED_UST}"
  echo
  echo "[ok] THE DEFECT, MEASURED: both controls opened on the static seed while a published close"
  echo "     for them sits in ${RES_DATE} v${RES_VER} — ${EQ_CTL} ${SEED_EQ} vs close ${CLOSE_EQ},"
  echo "     ${UST_CTL} ${SEED_UST} vs close ${CLOSE_UST} — and /health cannot say which rung won,"
  echo "     because it carries no openingSource at all. That is ADR-069's gap and its trap in one"
  echo "     reading: the numbers are plausible and nothing in the system contradicts them."
  exit 0
fi

[[ "${HAS_OS}" == "yes" ]] || fail "/health carries no openingSource: rule 4 is the countermeasure
       to this ADR's trap, and without it a seed open and a close open are indistinguishable"

# THE COMPOSED ASSERTION. Three mutually exclusive readings, so the failure names its own cause.
if ! eq "${EQ_OPEN}" "${CLOSE_EQ}" 1e-9; then
  if eq "${EQ_OPEN}" "${DRAFT_PX}" 1e-9; then
    fail "${EQ_CTL} opened at ${EQ_OPEN} — THE PLANTED DRAFT's price. The hierarchy prefers a
       DRAFT over the published close, which is exactly what rule 2 forbids, and every price it
       produces would look completely ordinary."
  elif eq "${EQ_OPEN}" "${SEED_EQ}" 1e-9; then
    fail "${EQ_CTL} opened at ${EQ_OPEN} — the STATIC SEED. The close read fell through; /health
       openingSource.error should name the reason: $(pyget openingSource.error <<<"${HEALTH}")"
  else
    fail "${EQ_CTL} opened at ${EQ_OPEN}, which is not the close ${CLOSE_EQ}, the seed ${SEED_EQ}
       or the planted DRAFT ${DRAFT_PX} — an opening price from a fourth source nobody predicted"
  fi
fi
[[ "${EQ_SRC}" == "previous-close" ]] \
  || fail "${EQ_CTL}'s wire provenance is '${EQ_SRC}', expected 'previous-close'"
# 1e-5, and only here: the close is a 6dp fraction of par, the walk state is 3dp percent-of-par,
# so the round trip through treasury.round3 can move the last digit. Every other comparison in
# this proof is exact, and step 0 refuses to run unless the bond's close/seed gap is at least 10x
# this — it cannot swallow the difference the arms turn on.
eq "${UST_OPEN}" "${CLOSE_UST}" 1e-5 \
  || fail "${UST_CTL} opened at ${UST_OPEN}, not at its published close ${CLOSE_UST} (seed ${SEED_UST})"
ok "RULE 2, END TO END: ${EQ_CTL} opened at ${CLOSE_EQ} — the PUBLISHED close — while ${#PLANTED[@]} newer"
ok "  DRAFT(s) carrying ${DRAFT_PX} sat in the table. The bond opened at its close too (${CLOSE_UST})."

# haskey first: pyget prints an empty string BOTH for `error: null` (what a clean open looks like)
# and for a key that is not there at all (a /health that cannot report failure). A bare emptiness
# test would call the second one healthy.
[[ "$(haskey openingSource.error <<<"${HEALTH}")" == "yes" ]] \
  || fail "/health's openingSource carries no error field at all, so it has no way to report a
       failed read -- the absence of continuity could never be loud"
OS_DATE="$(pyget openingSource.previousSession.sessionDate <<<"${HEALTH}")"
OS_VER="$(pyget openingSource.previousSession.version <<<"${HEALTH}")"
OS_ERR="$(pyget openingSource.error <<<"${HEALTH}")"
OS_EQC="$(pyget openingSource.byClass.equity.source <<<"${HEALTH}")"
OS_USC="$(pyget openingSource.byClass.treasury.source <<<"${HEALTH}")"
OS_OPT="$(pyget openingSource.byClass.option.source <<<"${HEALTH}")"
# RES_VER, and the planted DRAFT's version is strictly higher at the same date, so this equality
# is also the /health-level statement that the DRAFT did not win.
[[ "${OS_DATE}" == "${RES_DATE}" && "${OS_VER}" == "${RES_VER}" ]] \
  || fail "/health names ${OS_DATE:-?} v${OS_VER:-?} as the opening session; the published close is
       ${RES_DATE} v${RES_VER} and the planted DRAFT is v${DRAFT_V}"
[[ -z "${OS_ERR}" ]] || fail "/health reports a successful open AND an error: '${OS_ERR}'"
[[ "${OS_EQC}" == "previous-close" ]] || fail "/health says equities opened on '${OS_EQC}'"
[[ "${OS_USC}" == "previous-close" ]] || fail "/health says treasuries opened on '${OS_USC}'"
[[ "${OS_OPT}" == "derived-from-underlying" ]] \
  || fail "/health says options opened on '${OS_OPT}': an option must inherit its underlying's gap
       through the re-price it already uses, never carry a stored close of its own"
ok "rule 4: /health names ${OS_DATE} v${OS_VER} and the winning rung per class (equity/treasury previous-close, option derived)"

# ---------------------------------------------------------------------------------------------
step "3b. remove the plants, and account for exactly what was removed"

unplant
for pair in ${PLANTED[@]+"${PLANTED[@]}"}; do
  read -r pd pv <<<"${pair}"
  n="$(db "SELECT COUNT(*) FROM eod_price_session WHERE session_date='${pd}' AND version=${pv};" | tr -d '[:space:]')"
  [[ "${n}" == "0" ]] || fail "the planted DRAFT ${pd} v${pv} is still in eod_price_session (count=${n:-?})"
done
LEFT="$(db "SELECT COUNT(*) FROM eod_price_snapshot WHERE override_reason='${MARKER}';" | tr -d '[:space:]')"
[[ "${LEFT}" == "0" ]] || fail "${LEFT} row(s) carrying this proof's marker survived the cleanup"

read -r A_SNAP A_SESS <<<"$(db "SELECT (SELECT COUNT(*) FROM eod_price_snapshot),
                                       (SELECT COUNT(*) FROM eod_price_session);" | tr '\t' ' ')"
[[ "${A_SNAP}" =~ ^[0-9]+$ && "${A_SESS}" =~ ^[0-9]+$ ]] \
  || fail "could not re-read the row counts after the cleanup"
# NOT delta-zero. This table is live and grows on its own — the EOD chain cuts sessions on an
# hourly CronJob and concurrent proofs cut their own, so a session appearing mid-run is somebody
# else's CORRECT behaviour and must not fail this proof. A DECREASE is the only reading that can
# mean this script deleted something it did not plant, and that is what is asserted.
(( A_SNAP >= B_SNAP )) \
  || fail "eod_price_snapshot SHRANK across this run (${B_SNAP} -> ${A_SNAP}): the cleanup deleted
       rows this proof did not plant. Both delete statements are marker-scoped, so this should be
       unreachable — investigate before running it again."
(( A_SESS >= B_SESS )) \
  || fail "eod_price_session SHRANK across this run (${B_SESS} -> ${A_SESS}): the cleanup deleted
       sessions this proof did not plant."
ok "plants gone, marker rows 0; rows ${B_SNAP} -> ${A_SNAP}, sessions ${B_SESS} -> ${A_SESS} (growth is the live EOD chain, not this proof)"

read -r C_DATE C_VER <<<"$(api "curl -s -m15 'http://trade-processor:18091/eod/session/previous?before=${DATE}' \
    -H \"Authorization: Bearer \$T\"" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d['sessionDate'], d['version'])
")"
[[ "${C_DATE}" == "${RES_DATE}" && "${C_VER}" == "${RES_VER}" ]] \
  || fail "after the cleanup the endpoint resolves ${C_DATE:-?} v${C_VER:-?}, not ${RES_DATE} v${RES_VER}"
ok "endpoint is back on ${C_DATE} v${C_VER}"

# ---------------------------------------------------------------------------------------------
step "4. the control that makes step 3 mean something: switch the read off, same build"

"${K[@]}" set env deploy/price-publisher PRICE_OPEN_FROM_PREVIOUS_CLOSE=0 >/dev/null \
  || fail "could not set PRICE_OPEN_FROM_PREVIOUS_CLOSE=0"
ENV_TOUCHED=1
restart_publisher
HEALTH_OFF="$(pub /health)"
EQ_OFF="$(pyget openPrice <<<"$(pub "/prices/${EQ_CTL}")")"
UST_OFF="$(pyget openPrice <<<"$(pub "/prices/${UST_CTL}")")"
OFF_EN="$(pyget openingSource.enabled <<<"${HEALTH_OFF}")"
OFF_ERR="$(pyget openingSource.error <<<"${HEALTH_OFF}")"
OFF_SESS="$(haskey openingSource.previousSession <<<"${HEALTH_OFF}")"
OFF_EQC="$(pyget openingSource.byClass.equity.source <<<"${HEALTH_OFF}")"

eq "${EQ_OFF}" "${SEED_EQ}" 1e-9 \
  || fail "with the read off, ${EQ_CTL} opened at ${EQ_OFF} rather than its static seed ${SEED_EQ}:
       step 3's green did not depend on the read, so it proved nothing"
eq "${UST_OFF}" "${SEED_UST}" 1e-9 \
  || fail "with the read off, ${UST_CTL} opened at ${UST_OFF} rather than its static seed ${SEED_UST}"
[[ "${OFF_EN}" == "False" || "${OFF_EN}" == "false" ]] || fail "/health still reports enabled=${OFF_EN}"
[[ -n "${OFF_ERR}" ]] \
  || fail "/health reports a seed open with NO error text: the absence of continuity has to be
       LOUD, and a silent fall-through here is this ADR's trap in the countermeasure itself"
[[ "${OFF_EQC}" == "static-seed" ]] || fail "/health says equities opened on '${OFF_EQC}', expected static-seed"
[[ "${OFF_SESS}" == "yes" ]] || fail "openingSource.previousSession disappeared rather than reading null"
ok "read off -> both controls back on the seed, /health says enabled=false, source=static-seed, error='${OFF_ERR}'"
ok "step 3's readings therefore depend on the close read, which is what makes them evidence"

# ---------------------------------------------------------------------------------------------
step "5. restore: the rig must not be left with the read switched off"

"${K[@]}" set env deploy/price-publisher PRICE_OPEN_FROM_PREVIOUS_CLOSE- >/dev/null \
  || fail "could not remove PRICE_OPEN_FROM_PREVIOUS_CLOSE"
ENV_TOUCHED=0
restart_publisher
RESTORED="$(pub /health)"
R_EN="$(pyget openingSource.enabled <<<"${RESTORED}")"
R_DATE="$(pyget openingSource.previousSession.sessionDate <<<"${RESTORED}")"
R_EQ="$(pyget openPrice <<<"$(pub "/prices/${EQ_CTL}")")"
[[ "${R_EN}" == "True" || "${R_EN}" == "true" ]] || fail "the read is still off after the restore (enabled=${R_EN})"
[[ "${R_DATE}" == "${RES_DATE}" ]] \
  || fail "after the restore the publisher opens from '${R_DATE:-nothing}', not ${RES_DATE} — the rig
       is left in a state a later proof would read as a defect in something else"
# The clean continuity reading, with no plant in the table: step 3 proved the close beats a DRAFT,
# this proves the close is still what the rig opens from once the DRAFTs are gone.
eq "${R_EQ}" "${CLOSE_EQ}" 1e-9 \
  || fail "after the restore ${EQ_CTL} opens at ${R_EQ}, not at the published close ${CLOSE_EQ}"
ok "restored: the publisher opens from ${R_DATE} again, ${EQ_CTL} at ${CLOSE_EQ}"

echo
echo "[PASS] yu17-session-opens-from-close (EXPECT=after): rule 1's rung is live, rule 2 refuses a"
echo "       DRAFT however recent — proven at the PRICE, not just at the endpoint — rule 3 reads it"
echo "       over HTTP with no schema dependency, and rule 4 makes the answer readable in one"
echo "       request, including when the answer is 'the seed'."
