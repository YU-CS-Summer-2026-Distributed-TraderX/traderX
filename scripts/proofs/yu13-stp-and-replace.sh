#!/usr/bin/env bash
# yu13-stp-and-replace.sh — falsifiable proof of the member bundle: self-trade prevention
# (ADR-057, cancel-oldest) and engine-native atomic replace (ADR-058).
#
# Falsifiable by construction. It runs the SAME two scenarios against the pre-change members first
# and shows the real failures against the real system:
#   * a self-cross BOOKS A WASH TRADE, visible as 2 rows in the MariaDB `trades` table;
#   * POST /replace 404s, because no such ingress exists.
# Then it rolls the members and gateway forward and shows the same two scenarios behave correctly.
#
# The members are rolled with their PVCs INTACT, deliberately: snapshot format 3 is unchanged by
# this bundle, so the cluster recovers its epoch across the image change and the before/after runs
# are against the same state machine lineage rather than two different clusters.
#
# Assertion ends, honestly stated:
#   * trades  -> the ENGINE's own trade counter, on ALL THREE members. MariaDB `trades` is reported
#                alongside but NOT asserted: it is a best-effort bridged view (leader-only, NATS,
#                non-blocking offer), and on 2026-07-22 the engine booked 5.4M trades while that
#                table stayed frozen at 939,019 rows. An SQL-only assertion therefore reports "no
#                trade" for trades that definitely happened.
#   * orders  -> the replicated book digest agreed by ALL THREE members.
#   There is NO order read model: `orderbook` holds 0 rows for every order ever submitted, so
#   order-state assertions have no SQL effect end today. That gap is named, not papered over.
#
# Usage: ./yu13-stp-and-replace.sh            (needs both images present locally)
set -euo pipefail

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
IMAGE_PRE="${IMAGE_PRE:-traderx/cluster-node:yu15-pre}"
IMAGE_FIX="${IMAGE_FIX:-traderx/cluster-node:yu15-stp}"
SELF="${SELF:-42422}"      # the account that trades against itself
OTHER="${OTHER:-22214}"    # the genuine counterparty
TICKER="${TICKER:-STP$(date +%H%M%S)}"
PRICE="${PRICE:-100.00}"
QTY=5

fail() { echo "[FAIL] $*" >&2; exit 1; }
step() { echo; echo "=== $* ==="; }
# The trade bridge projects into the `database` deploy on the current rigs (eod-price-db carries
# the same schema but only EOD pricing data). Override SQL_DB for a rig wired differently.
# Deployment name and CONTAINER name are not the same thing on every rig: the cluster rig runs
# deploy/eod-price-db whose container is plainly "mariadb", while the state-014 rig ran
# deploy/database with a container of the same name. Assuming they match made `sql` fail with
# "container ... is not valid for pod", which rows() then returned as empty -- and an empty rows()
# is reported by the preflight as "already has trade rows", i.e. the single most misleading
# possible message for a container-name mismatch.
SQL_DB="${SQL_DB:-eod-price-db}"
SQL_CONTAINER="${SQL_CONTAINER:-mariadb}"
sql() { ${K} exec deploy/${SQL_DB} -c ${SQL_CONTAINER} -- mariadb -utraderx -ptraderx traderx -sN -e "$1" 2>&1 \
          | { grep -v "Using a password on the command line" || true; }; }
rows() { sql "SELECT COUNT(*) FROM trades WHERE security='${TICKER}';"; }

members() { ${K} get pods -l app=order-matcher-cluster -o name | sed 's|pod/||' | sort; }

book() { # book <ordinal> -> "<openOrders> <orderHash>"
  ${K} exec "order-matcher-cluster-$1" -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null || curl -s http://localhost:8080/metrics' \
    | awk '/^traderx_book_open_orders/ {d=$2} /^traderx_book_order_hash/ {h=$2} END {print d, h}'
}
# The ENGINE's own trade counter, per member. This is the authoritative booked-trade end: the
# MariaDB `trades` table is a bridged VIEW of it, and the bridge is best-effort (leader-only, NATS,
# non-blocking offer). Measured 2026-07-22: the engine booked 5.4M trades while the table stayed
# frozen at 939,019 rows -- so an SQL-only assertion can report "no trade" for a trade that
# definitely happened. Assert here; confirm in SQL when SQL is keeping up.
trade_count() {
  ${K} exec "order-matcher-cluster-$1" -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
    | awk '/^traderx_cluster_trades/ {print $2}'
}
trades_all() { for m in 0 1 2; do printf "%s " "$(trade_count "${m}")"; done; }

stp_count() {
  ${K} exec "order-matcher-cluster-$1" -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null || curl -s http://localhost:8080/metrics' \
    | awk '/^traderx_stp_cancels/ {print $2}'
}
# All three members must agree. Retried, because they apply the committed tail at slightly
# different times and an immediate sample can catch one mid-apply — which looks exactly like a
# determinism failure and is not one. Persistent disagreement IS the failure.
# 30s was not enough. This is called immediately after roll_to has restarted all three members,
# and a follower replaying a log tens of thousands of sequences deep legitimately trails for a
# while -- observed as member 2 one order ahead of 0 and 1, which converged on its own moments
# later (all three at seq 18254). Failing at 30s reported that transient lag as "members never
# agreed on the book", i.e. as a book divergence, which on a deterministic core is the most
# serious thing this proof could say. It is worth waiting to be sure before saying it.
DIGEST_TIMEOUT_S="${DIGEST_TIMEOUT_S:-180}"
digest_consensus() {
  local b0 b1 b2 i
  for i in $(seq 1 "${DIGEST_TIMEOUT_S}"); do
    b0="$(book 0)"; b1="$(book 1)"; b2="$(book 2)"
    if [[ "${b0}" == "${b1}" && "${b1}" == "${b2}" ]]; then
      echo "${b0}"
      return 0
    fi
    sleep 1
  done
  # Print each member's applied sequence alongside the digests. Lagging members show DIFFERENT
  # sequences and are still moving; genuinely diverged members sit at the SAME sequence with
  # different books. Without that the two are indistinguishable in the failure message.
  local seqs=""
  for m in 0 1 2; do
    seqs+="m${m}=$(${K} logs "order-matcher-cluster-${m}" --tail=40 2>/dev/null \
      | grep -oE 'seq=[0-9]+' | tail -1) "
  done
  fail "members never agreed on the book after ${DIGEST_TIMEOUT_S}s: [${b0}] [${b1}] [${b2}] (applied: ${seqs})"
}

# The script owns its port-forward: every gateway rollout tears one down, and a dead tunnel would
# be indistinguishable from "the feature is absent" — the ambiguity that makes a proof worthless.
PF_PID=""
PF_PORT="${MATCHER_URL##*:}"
stop_pf() { if [[ -n "${PF_PID}" ]]; then kill "${PF_PID}" 2>/dev/null || true; wait "${PF_PID}" 2>/dev/null || true; fi; PF_PID=""; }
start_pf() {
  stop_pf
  ${K} port-forward svc/order-matcher "${PF_PORT}:18110" >/dev/null 2>&1 & PF_PID=$!
  local tries=0
  until curl -sf --max-time 5 "${MATCHER_URL}/ready" >/dev/null 2>&1; do
    tries=$((tries + 1)); [[ ${tries} -lt 90 ]] || fail "gateway never became reachable"
    kill -0 "${PF_PID}" 2>/dev/null || { ${K} port-forward svc/order-matcher "${PF_PORT}:18110" >/dev/null 2>&1 & PF_PID=$!; }
    sleep 2
  done
}
# Whatever image the cluster was on before this proof touched it. Restored on EXIT, because a
# failure part-way used to LEAVE the members on this proof's own image -- and that image predates
# the gateway's instrumentOf alias, so every later proof silently broke (children rejected with a
# bare {"kind":2} and no reason). A proof that changes the cluster owes it back.
ORIGINAL_IMAGE="$(${K} get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)"
restore_image() {
  stop_pf
  [[ -n "${ORIGINAL_IMAGE}" ]] || return 0
  local now
  now="$(${K} get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)"
  [[ "${now}" == "${ORIGINAL_IMAGE}" ]] && return 0
  echo "[restore] returning the cluster to ${ORIGINAL_IMAGE}"
  roll_to "${ORIGINAL_IMAGE}" || echo "[warn] could not restore ${ORIGINAL_IMAGE} -- do it before running other proofs"
}
trap restore_image EXIT

roll_to() { # roll_to <image>   — PVCs intact: the epoch survives, format 3 is unchanged
  local image="$1"
  ${K} set image statefulset/order-matcher-cluster \
    "$(${K} get sts order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].name}')=${image}" >/dev/null
  ${K} set image deployment/cluster-gateway \
    "$(${K} get deploy cluster-gateway -o jsonpath='{.spec.template.spec.containers[0].name}')=${image}" >/dev/null
  ${K} rollout status statefulset/order-matcher-cluster --timeout=600s >/dev/null
  ${K} rollout status deployment/cluster-gateway --timeout=600s >/dev/null
  # kubectl's StatefulSet rollout status returned here while member 0 was STILL ON THE OLD IMAGE.
  # For a change in the deterministic core that is not a cosmetic race: a self-cross applied in
  # that window fills on the old member and cancels on the new ones, and the three state machines
  # diverge PERMANENTLY. It happened on 2026-07-22 and this proof caught it. Wait for the fact --
  # every pod running the target image AND ready -- not for the controller's opinion of it.
  local tries=0
  until [[ "$(${K} get pods -l app=order-matcher-cluster \
      -o jsonpath='{range .items[*]}{.spec.containers[0].image}{" "}{.status.containerStatuses[0].ready}{"\n"}{end}' \
      | sort -u | tr -d '\n')" == "${image} true" ]]; do
    tries=$((tries + 1)); [[ ${tries} -lt 120 ]] || fail "members never all reached ${image} and ready"
    sleep 5
  done
  start_pf
  seed
  # And prove the members agree BEFORE any traffic, so a later disagreement cannot be blamed on
  # the step that produced it.
  echo "  members + gateway now on ${image}; book agreed at [$(digest_consensus)]"
}

seed() {
  # Retried: this runs moments after roll_to replaced the gateway, and the OLDER builds this proof
  # deliberately rolls to answer /ready 200 before their cluster session is actually usable -- the
  # first /seed can then time out. That is fixture setup racing an old build's shallow readiness,
  # not the behaviour under proof; run 1 of the suite died exactly here ("seed failed for 42422")
  # and the failure-path restore then diverged the members. Seeding is idempotent, so retrying is
  # safe.
  local acct try
  for acct in "${SELF}" "${OTHER}"; do
    for try in 1 2 3 4 5; do
      curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
        -d "{\"accountId\":${acct},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}" >/dev/null && break
      [[ ${try} -lt 5 ]] || fail "seed failed for ${acct} after 5 attempts"
      sleep 5
    done
  done
}

order() { # order <account> <side> [price] -> body
  curl -s --max-time 30 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
    -d "{\"accountId\":$1,\"ticker\":\"${TICKER}\",\"side\":\"$2\",\"quantity\":${QTY},\"limitPrice\":${3:-${PRICE}}}"
}
replace() { # replace <orderRef> <qty> <price> -> "<http> <body>"
  local out
  out="$(curl -s --max-time 30 -o /tmp/yu13-rep-body -w '%{http_code}' \
    -X POST "${MATCHER_URL}/replace" -H 'Content-Type: application/json' \
    -d "{\"orderRef\":$1,\"quantity\":$2,\"limitPrice\":$3}")"
  echo "${out} $(cat /tmp/yu13-rep-body)"
}
ref_of() { sed -n 's/.*"orderRef":\([0-9]*\).*/\1/p' <<<"$1"; }

# ---------------------------------------------------------------------------------------------
step "0. preflight"
# kind compares the DOCKER manifest digest against containerd's config digest, so it decides an
# image is "not yet present" every single time and re-copies 194MB into four nodes. On a host where
# three busy-spinning Aeron members already burn ~150-200% CPU each, that load can take longer than
# the whole proof. SKIP_KIND_LOAD=1 when the nodes demonstrably already have both tags.
for img in "${IMAGE_PRE}" "${IMAGE_FIX}"; do
  docker image inspect "${img}" >/dev/null 2>&1 || fail "image ${img} not present locally"
  [[ "${SKIP_KIND_LOAD:-0}" == "1" ]] || kind load docker-image "${img}" --name "${CTX#kind-}" >/dev/null
done
${K} get deploy trade-processor >/dev/null 2>&1 || fail "trade-processor is not deployed"
# A WIPED epoch restarts the engine's tradeCounter at 1, while MariaDB still holds every trade any
# earlier epoch ever booked. Trade ids are <tradeSeq>-<side>, so the new epoch's trades collide with
# old rows and trade-processor drops them as "Duplicate trade delivery ignored" -- silently, with
# the cluster reporting success. That is the third instance of the silent-read-model-drop class in
# this project, after the VARCHAR(15) OCC bug and the trades.accountid foreign key. Refuse to run
# rather than assert against a read model that cannot see this epoch.
SQL_MAX_TRADE="$(sql "SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(id,'-',1) AS UNSIGNED)),0) FROM trades;")"
ENGINE_TRADES="$(${K} exec order-matcher-cluster-0 -- \
  sh -c 'wget -qO- http://localhost:8080/metrics' 2>/dev/null | awk '/^traderx_cluster_trades/ {print $2}')"
[[ "${ENGINE_TRADES:-0}" -ge "${SQL_MAX_TRADE:-0}" ]] || fail \
  "engine tradeCounter ${ENGINE_TRADES} < highest trade id already in SQL ${SQL_MAX_TRADE}: this
  epoch's trades would be dropped as duplicates. Run load until the counter passes it, or use an
  epoch that was never wiped." 
[[ "$(${K} get deploy trade-processor -o jsonpath='{.status.readyReplicas}')" == "1" ]] \
  || fail "trade-processor is not READY — no fill can reach SQL, so this proof cannot run"
# Member restart counts are ASSERTED against a preflight baseline, never printed: a real member
# bounce mid-proof would otherwise pass silently as a success.
RESTARTS0="$(${K} get pods -l app=order-matcher-cluster \
  -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')"
echo "[ok] preflight: both images loaded, trade-processor ready, ticker ${TICKER}"

step "1. roll BACK to the pre-change members (${IMAGE_PRE})"
roll_to "${IMAGE_PRE}"
[[ "$(rows)" == "0" ]] || fail "${TICKER} already has trade rows; pick a fresh ticker"

step "2. on the pre-change engine a self-cross BOOKS A WASH TRADE"
BEFORE="$(digest_consensus)"
# Take the baseline only once the three members AGREE on it. These reads happen moments after
# roll_to restarted every member, and sampling each in turn while they are still catching up
# captured [6 12 12] -- a baseline the members never simultaneously held. want = baseline + 2 is
# then a different target per member and cannot all be satisfied, so the proof failed reporting
# [14 14 14] against "want +2 on each of [6 12 12]" -- i.e. it failed at the exact moment all
# three DID agree. The baseline has to be a consistent cut, not three separate snapshots.
await_trades_agree() {
  local t0 t1 t2 tries=0
  while [[ ${tries} -lt 120 ]]; do
    t0="$(trade_count 0)"; t1="$(trade_count 1)"; t2="$(trade_count 2)"
    if [[ "${t0}" == "${t1}" && "${t1}" == "${t2}" ]]; then
      T0_0="${t0}"; T0_1="${t1}"; T0_2="${t2}"
      return 0
    fi
    tries=$((tries + 1)); sleep 1
  done
  fail "members never agreed on a trade-count baseline: [${t0} ${t1} ${t2}]"
}
await_trades_agree
ROWS0="$(rows)"
SELF_SELL="$(order "${SELF}" Sell)"; echo "  ${SELF} sell -> ${SELF_SELL}"
SELF_BUY="$(order "${SELF}" Buy)";   echo "  ${SELF} buy  -> ${SELF_BUY}"
# Poll for the effect rather than sleeping a fixed 5s and asserting once. These members were
# restarted moments ago by roll_to and a follower still catching up on the log legitimately reports
# a stale trade count for a beat -- observed as [140 140 140] -> [140 142 142], which is member 0
# lagging, NOT the three disagreeing. A fixed sleep turns that into a false divergence report, the
# most alarming possible way to fail. Wait for the fact, with an explicit timeout.
await_trades() { # await_trades <want-delta>
  local want tries=0 m b ok
  want="$1"
  while [[ ${tries} -lt 60 ]]; do
    ok=1
    for m in 0 1 2; do
      b="T0_${m}"
      [[ "$(trade_count "${m}")" -eq "$(( ${!b} + want ))" ]] || ok=0
    done
    [[ ${ok} -eq 1 ]] && return 0
    tries=$((tries + 1)); sleep 1
  done
  return 1
}
await_trades 2 \
  || fail "members did not all book the 2-sided wash trade within 60s: [$(trades_all)] (want +2 on each of [${T0_0} ${T0_1} ${T0_2}])"
echo "  engine trades: [${T0_0} ${T0_1} ${T0_2}] -> [$(trades_all)]  (a self-trade books BOTH sides)"
echo "  book: ${BEFORE} -> $(digest_consensus)"
# Secondary, and explicitly secondary: the bridged read model. Reported, not asserted, because the
# bridge is best-effort by design and its state is not evidence about the engine.
PRE_ROWS="$(rows)"
echo "  MariaDB trades rows for ${TICKER}: ${ROWS0} -> ${PRE_ROWS}$( \
  [[ "${PRE_ROWS}" == "2" ]] && echo "   (read model agrees)" \
                             || echo "   (READ MODEL LAGGING OR DOWN -- not evidence either way)")"

step "3. on the pre-change gateway /replace does not exist"
PRE_REPLACE="$(replace "$(ref_of "${SELF_BUY}")" 9 "${PRICE}")"
echo "  POST /replace -> ${PRE_REPLACE}"
[[ "${PRE_REPLACE}" == 404* ]] || fail "expected 404 from the pre-change gateway, got ${PRE_REPLACE}"

step "4. roll FORWARD to the member bundle (${IMAGE_FIX})"
roll_to "${IMAGE_FIX}"
STP0_0="$(stp_count 0)"; STP0_1="$(stp_count 1)"; STP0_2="$(stp_count 2)"

step "5. the SAME self-cross now books nothing and cancels the resting order instead"
T0_0="$(trade_count 0)"; T0_1="$(trade_count 1)"; T0_2="$(trade_count 2)"
BEFORE="$(digest_consensus)"
SELF_SELL2="$(order "${SELF}" Sell)"; echo "  ${SELF} sell -> ${SELF_SELL2}"
MID="$(digest_consensus)"; echo "  book with the sell resting: ${MID}"
SELF_BUY2="$(order "${SELF}" Buy)";   echo "  ${SELF} buy  -> ${SELF_BUY2}"
sleep 5
AFTER="$(digest_consensus)"
echo "  engine trades: [${T0_0} ${T0_1} ${T0_2}] -> [$(trades_all)]   (must not move)"
echo "  book after:  ${AFTER}"
for m in 0 1 2; do
  b="T0_${m}"
  [[ "$(trade_count "${m}")" -eq "${!b}" ]] || fail "member ${m} booked a self-trade under STP"
done
# The self sell left the book (STP-cancelled); the self buy took its place, so depth is unchanged
# from "sell resting" — but the CONTENT hash must differ, or nothing actually happened.
[[ "${AFTER}" != "${MID}" ]] || fail "the book is byte-identical: the STP cancel did not happen"
for m in 0 1 2; do
  before_var="STP0_${m}"
  [[ "$(stp_count "${m}")" -gt "${!before_var}" ]] \
    || fail "member ${m} recorded no STP cancel — the three members did not all apply it"
done
echo "  traderx_stp_cancels advanced on all three members"

step "6. falsification arm: the identical economics from TWO accounts still fill"
T0_0="$(trade_count 0)"; T0_1="$(trade_count 1)"; T0_2="$(trade_count 2)"
order "${OTHER}" Sell >/dev/null
order "${SELF}" Buy >/dev/null
sleep 5
echo "  engine trades: [${T0_0} ${T0_1} ${T0_2}] -> [$(trades_all)]"
for m in 0 1 2; do
  b="T0_${m}"
  [[ "$(trade_count "${m}")" -eq "$(( ${!b} + 2 ))" ]] \
    || fail "a genuine two-account cross did not book on member ${m}: step 5 proves nothing"
done

step "7. atomic replace takes effect, under the SAME orderRef"
REST="$(order "${OTHER}" Sell "$(python3 -c "print(${PRICE} + 5)")")"
REF="$(ref_of "${REST}")"; echo "  resting sell ref=${REF} @ $(python3 -c "print(${PRICE} + 5)")"
BEFORE="$(digest_consensus)"
REP="$(replace "${REF}" 9 "$(python3 -c "print(${PRICE} + 3)")")"
echo "  POST /replace (qty 5->9, px +5 -> +3) -> ${REP}"
[[ "${REP}" == 200* ]] || fail "replace was not accepted: ${REP}"
[[ "${REP}" == *"\"orderRef\":${REF}"* ]] || fail "replace minted a new orderRef; identity was not preserved"
AFTER="$(digest_consensus)"
echo "  book: ${BEFORE} -> ${AFTER}"
[[ "${BEFORE}" != "${AFTER}" ]] || fail "the replace changed nothing on the members"
[[ "${BEFORE%% *}" == "${AFTER%% *}" ]] \
  || fail "depth changed: a replace must be one order in and one order out, not two orders"

step "8. a REJECTED replace leaves the order untouched — the atomicity claim"
BEFORE="$(digest_consensus)"
REJ="$(replace "${REF}" 9 "$(python3 -c "print(${PRICE} + 500)")")"   # far outside the price band
echo "  POST /replace to an out-of-band price -> ${REJ}"
[[ "${REJ}" == 422* ]] || fail "expected 422 for a rejected replace, got ${REJ}"
[[ "${REJ}" == *PRICE_COLLAR* ]] || fail "expected the reason PRICE_COLLAR in the body: ${REJ}"
AFTER="$(digest_consensus)"
echo "  book: ${BEFORE} -> ${AFTER}"
[[ "${BEFORE}" == "${AFTER}" ]] \
  || fail "a REJECTED replace changed the book — the client's order was not left intact"
# ...and it is still tradeable at the price the accepted replace moved it to.
T0_0="$(trade_count 0)"; T0_1="$(trade_count 1)"; T0_2="$(trade_count 2)"
order "${SELF}" Buy "$(python3 -c "print(${PRICE} + 3)")" >/dev/null
sleep 5
echo "  engine trades: [${T0_0} ${T0_1} ${T0_2}] -> [$(trades_all)]   (the survived order fills)"
for m in 0 1 2; do
  b="T0_${m}"
  [[ "$(trade_count "${m}")" -eq "$(( ${!b} + 2 ))" ]] \
    || fail "the order that survived the rejected replace could not be traded (member ${m})"
done

step "9. no member bounced during the proof"
RESTARTS1="$(${K} get pods -l app=order-matcher-cluster \
  -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')"
[[ "${RESTARTS0}" == "${RESTARTS1}" ]] \
  || fail "a member restarted mid-proof (${RESTARTS0} -> ${RESTARTS1}); results are not trustworthy"

echo
echo "[PASS] self-trade prevention and atomic replace, proven against the pre-change failure."
echo "       Known limitation: order state has no SQL effect end (the orderbook table holds 0 rows"
echo "       for every order ever submitted), so order assertions are against the three members'"
echo "       agreed book digest. Trade assertions are in MariaDB."
