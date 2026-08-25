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
#   EXPECT=after  (this change): the endpoint resolves rule 2, the controls open AT their published
#     closes, /health names the winning source per class, and switching the read off flips every
#     one of those readings back.
#
# WHY THERE ARE FOUR ARMS, AND WHY ARM 4 IS NOT OPTIONAL. This ADR's stated trap is that a failed
# close-read and a successful one produce prices that are equally plausible — "there is no wrong
# number to notice". So a green arm 3 on its own proves very little: the opening price might match
# the close because the close read worked, or because the seed happens to sit near the close.
# Arm 4 switches the read off ON THE SAME BUILD and requires the readings to change — that is the
# control that makes arm 3 mean something, and it is why every price assertion below is EXACT
# equality against a number read out of the database or out of the pod's own seed file, never a
# tolerance wide enough to swallow the difference.
#
# THE ARMS ARE REFUSED RATHER THAN ROUNDED. Step 0 asserts that the published close and the static
# seed DIFFER for both controls. If they ever coincide, the two arms predict the same number and
# the proof discriminates nothing — it exits 2 saying so instead of reporting a pass.
#
# ROLLING, NOT DESTRUCTIVE. It restarts the price-publisher Deployment (up to three times on the
# after arm) and plants + removes DRAFT rows in eod_price_session/eod_price_snapshot. No epoch, no
# PVC wipe, no member roll, no gateway roll, nothing sequenced through consensus. The restart
# re-seeds the feed, so the reference steps by |close - seed| — printed in step 0, and bounded by
# the same magnitudes ADR-069 measured (a few percent).
#
# THE PLANTED DRAFTS ARE KEYED ON A MARKER, NOT A PRICE. Every planted row carries
# override_reason='yu17-session-opens-from-close' and the cleanup deletes on that, so a run killed
# by SIGKILL (which no EXIT trap survives) leaves rows a later run's PRE-clean removes — and it
# removes every stratum, not just its own. A leaked DRAFT here is not cosmetic: it is precisely the
# object rule 2 exists to refuse, so leaving one behind would poison the thing under test.
#
# Usage:  [EXPECT=before|after] [EQ_CTL=AAPL] [UST_CTL=UST-20280630] ./yu17-session-opens-from-close.sh
set -uo pipefail

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K=(kubectl --context "${CTX}" -n "${NS}")
EXPECT="${EXPECT:-after}"
EQ_CTL="${EQ_CTL:-AAPL}"
UST_CTL="${UST_CTL:-UST-20280630}"
DB_DEPLOY="${DB_DEPLOY:-eod-price-db}"
MARKER='yu17-session-opens-from-close'
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
TOK='T=$(curl -s -m8 -X POST http://trade-processor:18091/auth/dev-token -H "X-Auth-Master-Secret: '"${MASTER}"'" -H "Content-Type: application/json" -d "{\"subject\":\"proof-open\",\"accounts\":[],\"admin\":true,\"ttlSeconds\":600}")'
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

# EVERY plant this proof can ever have made, on every date, keyed on the marker — not on this
# run's version numbers. A version-scoped delete tidies only the run that ran it, so a killed
# run's leftovers become the operative rows the moment a later run tidies its own. The second
# statement sweeps session headers left with no rows at all (a kill between the two INSERTs).
unplant() {
  db "DELETE FROM eod_price_snapshot WHERE override_reason = '${MARKER}';
      DELETE h FROM eod_price_session h
        LEFT JOIN eod_price_snapshot s
          ON s.session_date = h.session_date AND s.version = h.version
        WHERE h.status = 'DRAFT' AND s.security IS NULL AND h.session_date < '${DATE}'
          AND h.instrument_count = 1 AND h.flagged_count = 0;" >/dev/null 2>&1
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

# PRE-clean before anything is measured. An EXIT trap does not survive SIGKILL, a dead kubectl
# session or the box going down, and a leaked DRAFT from a killed run is the exact object rule 2
# exists to refuse — it would sit there being ignored (correct) or accepted (a real defect) with
# nothing distinguishing the two.
unplant

# The oracle, written differently from the implementation on purpose: the repository groups by
# date and takes MAX(version) INSIDE the status filter; this orders and limits. Same answer, and
# an independent spelling of it.
read -r RES_DATE RES_VER <<<"$(db "SELECT session_date, version FROM eod_price_session
   WHERE status = 'PUBLISHED' AND session_date < '${DATE}'
   ORDER BY session_date DESC, version DESC LIMIT 1;" | tr '\t' ' ')"
[[ "${RES_DATE}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ && "${RES_VER}" =~ ^[0-9]+$ ]] \
  || skip "no PUBLISHED session strictly before ${DATE} on this rig (got '${RES_DATE:-}' '${RES_VER:-}'):
       there is no previous close for a session to open FROM, so neither arm can say anything.
       Cut and publish one (yu06-quality-gate's step 1-4 is the recipe) and re-run."
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
# CLOSE (241.8). Comparing against the wrong one would fail the red arm for a reason that has
# nothing to do with this ADR. `round`/`floor(x*1000+0.5)` mirrors JS Math.round rather than
# Python's banker's rounding, so the two implementations agree on a .5 that a 3dp seed could
# legitimately carry.
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
echo "    ${EQ_CTL}:  close ${CLOSE_EQ}  vs static seed ${SEED_EQ}"
echo "    ${UST_CTL}: close ${CLOSE_UST}  vs static seed ${SEED_UST}"

# THE ARMS MUST PREDICT DIFFERENT NUMBERS. If a control's close equals its seed, "opened from the
# close" and "opened from the seed" are the same reading and a green means nothing.
# The thresholds are tied to the tolerances step 3 asserts with, not picked to be small: the
# equity is compared EXACTLY (prices are 3dp, so any real difference is at least 1e-3), and the
# bond is compared to 1e-5, so its gap must be at least 10x that or the tolerance could swallow
# the very difference the two arms turn on.
python3 -c "
import sys
pairs = [('${EQ_CTL}', ${CLOSE_EQ}, ${SEED_EQ}, 1e-3), ('${UST_CTL}', ${CLOSE_UST}, ${SEED_UST}, 1e-4)]
bad = [n for n, c, s, floor in pairs if abs(c - s) < floor]
sys.exit(0 if not bad else 1)
" || skip "a control's published close equals its static seed, so the two arms predict the SAME
       opening price and this proof would discriminate nothing. Pick other controls:
       EQ_CTL=<ticker> UST_CTL=<key> bash scripts/proofs/yu17-session-opens-from-close.sh"
ok "close and seed differ for both controls — the arms predict different numbers"

# ---------------------------------------------------------------------------------------------
step "1. the previous-session read (rule 3): resolved server-side, over HTTP"

PREV_CODE="$(api "curl -s -o /dev/null -w '%{http_code}' -m15 \
  'http://trade-processor:18091/eod/session/previous?before=${DATE}' -H \"Authorization: Bearer \$T\"")"
PREV_BODY="$(api "curl -s -m15 \
  'http://trade-processor:18091/eod/session/previous?before=${DATE}' -H \"Authorization: Bearer \$T\"")"

# SHAPE-TEST THE CODE BEFORE BRANCHING ON IT. A failed `kubectl exec` prints nothing and curl's
# own 000 means the connection never happened -- and on the before arm BOTH would sail straight
# through a bare `!= 200` and be reported as "the endpoint is correctly absent". No answer and the
# answer no are different verdicts.
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
step "2. rule 2's whole content: a DRAFT never wins, however recent it is"

if [[ "${EXPECT}" == "before" ]]; then
  echo "    N/A on this build: there is no previous-session read, so there is nothing that could"
  echo "    prefer a DRAFT. Recorded as absent rather than passed — the defect on this build is"
  echo "    step 3's, not this one's."
else
  plant_draft() { # plant_draft <session_date> <version> <price>
    db "INSERT INTO eod_price_session (session_date, version, status, instrument_count, flagged_count, created_at)
          VALUES ('$1', $2, 'DRAFT', 1, 0, NOW());
        INSERT INTO eod_price_snapshot (session_date, version, security, closing_price, quality, override_reason)
          VALUES ('$1', $2, '${EQ_CTL}', $3, 'OVERRIDDEN', '${MARKER}');" >/dev/null \
      || fail "could not plant the DRAFT at $1 v$2"
    # VERIFY THE PLANT. "I ran the INSERT" is not "the row is there", and a DRAFT that never
    # landed makes this arm pass against nothing at all.
    local n; n="$(db "SELECT COUNT(*) FROM eod_price_session WHERE session_date='$1' AND version=$2 AND status='DRAFT';" | tr -d '[:space:]')"
    [[ "${n}" == "1" ]] || fail "the DRAFT at $1 v$2 is not in the table after the INSERT (count=${n:-?})"
  }
  resolved_now() { api "curl -s -m15 'http://trade-processor:18091/eod/session/previous?before=${DATE}' \
      -H \"Authorization: Bearer \$T\"" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d['sessionDate'], d['version'])
"; }

  # 2a — same date, HIGHER version. This is the exact bug a MAX(version) taken OUTSIDE the status
  # filter would produce: the newest version wins, and it is a DRAFT.
  DRAFT_V=$(( $(db "SELECT COALESCE(MAX(version),0) FROM eod_price_session WHERE session_date='${RES_DATE}';" | tr -d '[:space:]') + 1 ))
  plant_draft "${RES_DATE}" "${DRAFT_V}" 424242.424242
  read -r A_DATE A_VER <<<"$(resolved_now)"
  [[ "${A_DATE}" == "${RES_DATE}" && "${A_VER}" == "${RES_VER}" ]] \
    || fail "a DRAFT at ${RES_DATE} v${DRAFT_V} displaced the published close: the endpoint now
       resolves ${A_DATE:-?} v${A_VER:-?} instead of ${RES_DATE} v${RES_VER}"
  ok "DRAFT ${RES_DATE} v${DRAFT_V} (higher version, same date) ignored — still ${A_DATE} v${A_VER}"

  # 2b — a LATER date, when one exists strictly between the resolved date and today. This is the
  # other half of rule 2: "most recent session_date" means the most recent one that HAS a
  # published version, not the most recent one that exists.
  LATER="$(python3 -c "
import datetime as d
res = d.date.fromisoformat('${RES_DATE}')
today = d.date.fromisoformat('${DATE}')
nxt = res + d.timedelta(days=1)
print(nxt.isoformat() if nxt < today else '')
")"
  if [[ -n "${LATER}" ]]; then
    LATER_V=$(( $(db "SELECT COALESCE(MAX(version),0) FROM eod_price_session WHERE session_date='${LATER}';" | tr -d '[:space:]') + 1 ))
    plant_draft "${LATER}" "${LATER_V}" 424242.424242
    read -r B_DATE B_VER <<<"$(resolved_now)"
    [[ "${B_DATE}" == "${RES_DATE}" && "${B_VER}" == "${RES_VER}" ]] \
      || fail "a DRAFT dated ${LATER} (later than the published close, still before ${DATE}) won:
       the endpoint resolves ${B_DATE:-?} v${B_VER:-?} instead of ${RES_DATE} v${RES_VER}"
    ok "DRAFT dated ${LATER} (a later date entirely) ignored — still ${B_DATE} v${B_VER}"
  else
    echo "    2b not run: ${RES_DATE} is the day before ${DATE}, so no date sits strictly between"
    echo "    them to plant a later DRAFT on. 2a covers the version half of rule 2; the date half"
    echo "    is covered whenever the rig's newest published close is older than yesterday."
  fi
  unplant
  read -r C_DATE C_VER <<<"$(resolved_now)"
  [[ "${C_DATE}" == "${RES_DATE}" && "${C_VER}" == "${RES_VER}" ]] \
    || fail "the plants did not come out cleanly: the endpoint now resolves ${C_DATE:-?} v${C_VER:-?}"
  ok "plants removed; the endpoint is back on ${C_DATE} v${C_VER}"
fi

# ---------------------------------------------------------------------------------------------
step "3. the open itself: restart the publisher and read where the session actually started"

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

eq() { python3 -c "import sys; sys.exit(0 if abs(float('$1') - float('$2')) <= float('$3') else 1)"; }

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
eq "${EQ_OPEN}" "${CLOSE_EQ}" 1e-9 \
  || fail "${EQ_CTL} opened at ${EQ_OPEN}, not at its published close ${CLOSE_EQ} (seed is ${SEED_EQ})"
[[ "${EQ_SRC}" == "previous-close" ]] \
  || fail "${EQ_CTL}'s wire provenance is '${EQ_SRC}', expected 'previous-close'"
# 1e-5, and only here: the close is a 6dp fraction of par, the walk state is 3dp percent-of-par,
# so the round trip through treasury.round3 can move the last digit. Every other comparison in
# this proof is exact, and this bound is 100x smaller than the gap between close and seed asserted
# in step 0 — it cannot swallow the difference the arms turn on.
eq "${UST_OPEN}" "${CLOSE_UST}" 1e-5 \
  || fail "${UST_CTL} opened at ${UST_OPEN}, not at its published close ${CLOSE_UST} (seed ${SEED_UST})"
ok "both controls opened AT the published close of ${RES_DATE} v${RES_VER}"

OS_DATE="$(pyget openingSource.previousSession.sessionDate <<<"${HEALTH}")"
OS_VER="$(pyget openingSource.previousSession.version <<<"${HEALTH}")"
# haskey first: pyget prints an empty string BOTH for `error: null` (what a clean open looks
# like) and for a key that is not there at all (a /health that cannot report failure). A bare
# emptiness test would call the second one healthy.
[[ "$(haskey openingSource.error <<<"${HEALTH}")" == "yes" ]] \
  || fail "/health's openingSource carries no error field at all, so it has no way to report a
       failed read -- the absence of continuity could never be loud"
OS_ERR="$(pyget openingSource.error <<<"${HEALTH}")"
OS_EQC="$(pyget openingSource.byClass.equity.source <<<"${HEALTH}")"
OS_USC="$(pyget openingSource.byClass.treasury.source <<<"${HEALTH}")"
OS_OPT="$(pyget openingSource.byClass.option.source <<<"${HEALTH}")"
[[ "${OS_DATE}" == "${RES_DATE}" && "${OS_VER}" == "${RES_VER}" ]] \
  || fail "/health names ${OS_DATE:-?} v${OS_VER:-?} as the opening session; the prices came from ${RES_DATE} v${RES_VER}"
[[ -z "${OS_ERR}" ]] || fail "/health reports a successful open AND an error: '${OS_ERR}'"
[[ "${OS_EQC}" == "previous-close" ]] || fail "/health says equities opened on '${OS_EQC}'"
[[ "${OS_USC}" == "previous-close" ]] || fail "/health says treasuries opened on '${OS_USC}'"
[[ "${OS_OPT}" == "derived-from-underlying" ]] \
  || fail "/health says options opened on '${OS_OPT}': an option must inherit its underlying's gap
       through the re-price it already uses, never carry a stored close of its own"
ok "rule 4: /health names ${OS_DATE} v${OS_VER} and the winning rung per class (equity/treasury previous-close, option derived)"

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
ok "read off -> both controls back on the seed, /health says enabled=false, source=static-seed, error='${OFF_ERR}'"
ok "step 3's readings therefore depend on the close read, which is what makes them evidence"
[[ "${OFF_SESS}" == "yes" ]] || fail "openingSource.previousSession disappeared rather than reading null"

# ---------------------------------------------------------------------------------------------
step "5. restore: the rig must not be left with the read switched off"

"${K[@]}" set env deploy/price-publisher PRICE_OPEN_FROM_PREVIOUS_CLOSE- >/dev/null \
  || fail "could not remove PRICE_OPEN_FROM_PREVIOUS_CLOSE"
ENV_TOUCHED=0
restart_publisher
RESTORED="$(pub /health)"
R_EN="$(pyget openingSource.enabled <<<"${RESTORED}")"
R_DATE="$(pyget openingSource.previousSession.sessionDate <<<"${RESTORED}")"
[[ "${R_EN}" == "True" || "${R_EN}" == "true" ]] || fail "the read is still off after the restore (enabled=${R_EN})"
[[ "${R_DATE}" == "${RES_DATE}" ]] \
  || fail "after the restore the publisher opens from '${R_DATE:-nothing}', not ${RES_DATE} — the rig
       is left in a state a later proof would read as a defect in something else"
ok "restored: the publisher is opening from ${R_DATE} again"

echo
echo "[PASS] yu17-session-opens-from-close (EXPECT=after): rule 1's rung is live, rule 2 refuses a"
echo "       DRAFT however recent, rule 3 reads it over HTTP with no schema dependency, and rule 4"
echo "       makes the answer readable in one request — including when it is 'the seed'."
