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
#   * trades  -> MariaDB `trades` (a real effect end; the cluster bridges /trades)
#   * orders  -> the replicated book digest agreed by ALL THREE members
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
sql() { ${K} exec deploy/eod-price-db -- mariadb -utraderx -ptraderx traderx -sN -e "$1" 2>&1 \
          | { grep -v "Using a password on the command line" || true; }; }
rows() { sql "SELECT COUNT(*) FROM trades WHERE security='${TICKER}';"; }

members() { ${K} get pods -l app=order-matcher-cluster -o name | sed 's|pod/||' | sort; }

book() { # book <ordinal> -> "<openOrders> <orderHash>"
  ${K} exec "order-matcher-cluster-$1" -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null || curl -s http://localhost:8080/metrics' \
    | awk '/^traderx_book_open_orders/ {d=$2} /^traderx_book_order_hash/ {h=$2} END {print d, h}'
}
stp_count() {
  ${K} exec "order-matcher-cluster-$1" -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null || curl -s http://localhost:8080/metrics' \
    | awk '/^traderx_stp_cancels/ {print $2}'
}
# All three members must agree. Retried, because they apply the committed tail at slightly
# different times and an immediate sample can catch one mid-apply — which looks exactly like a
# determinism failure and is not one. Persistent disagreement IS the failure.
digest_consensus() {
  local b0 b1 b2 i
  for i in $(seq 1 30); do
    b0="$(book 0)"; b1="$(book 1)"; b2="$(book 2)"
    if [[ "${b0}" == "${b1}" && "${b1}" == "${b2}" ]]; then
      echo "${b0}"
      return 0
    fi
    sleep 1
  done
  fail "members never agreed on the book: [${b0}] [${b1}] [${b2}]"
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
trap stop_pf EXIT

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
  for acct in "${SELF}" "${OTHER}"; do
    curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
      -d "{\"accountId\":${acct},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}" >/dev/null \
      || fail "seed failed for ${acct}"
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
SELF_SELL="$(order "${SELF}" Sell)"; echo "  ${SELF} sell -> ${SELF_SELL}"
SELF_BUY="$(order "${SELF}" Buy)";   echo "  ${SELF} buy  -> ${SELF_BUY}"
sleep 5
PRE_ROWS="$(rows)"
echo "  trades rows for ${TICKER}: ${PRE_ROWS}   (both sides of one self-trade)"
echo "  book: ${BEFORE} -> $(digest_consensus)"
[[ "${PRE_ROWS}" == "2" ]] \
  || fail "expected the pre-change engine to book a 2-row wash trade, got ${PRE_ROWS}"

step "3. on the pre-change gateway /replace does not exist"
PRE_REPLACE="$(replace "$(ref_of "${SELF_BUY}")" 9 "${PRICE}")"
echo "  POST /replace -> ${PRE_REPLACE}"
[[ "${PRE_REPLACE}" == 404* ]] || fail "expected 404 from the pre-change gateway, got ${PRE_REPLACE}"

step "4. roll FORWARD to the member bundle (${IMAGE_FIX})"
roll_to "${IMAGE_FIX}"
STP0_0="$(stp_count 0)"; STP0_1="$(stp_count 1)"; STP0_2="$(stp_count 2)"

step "5. the SAME self-cross now books nothing and cancels the resting order instead"
ROWS_BEFORE="$(rows)"
BEFORE="$(digest_consensus)"
SELF_SELL2="$(order "${SELF}" Sell)"; echo "  ${SELF} sell -> ${SELF_SELL2}"
MID="$(digest_consensus)"; echo "  book with the sell resting: ${MID}"
SELF_BUY2="$(order "${SELF}" Buy)";   echo "  ${SELF} buy  -> ${SELF_BUY2}"
sleep 5
AFTER="$(digest_consensus)"
echo "  trades rows: ${ROWS_BEFORE} -> $(rows)   (must not move)"
echo "  book after:  ${AFTER}"
[[ "$(rows)" == "${ROWS_BEFORE}" ]] || fail "a self-trade booked under STP"
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
ROWS_BEFORE="$(rows)"
order "${OTHER}" Sell >/dev/null
order "${SELF}" Buy >/dev/null
sleep 5
echo "  trades rows: ${ROWS_BEFORE} -> $(rows)"
[[ "$(rows)" == "$(( ROWS_BEFORE + 2 ))" ]] \
  || fail "a genuine two-account cross did not book: step 5 proves nothing"

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
ROWS_BEFORE="$(rows)"
order "${SELF}" Buy "$(python3 -c "print(${PRICE} + 3)")" >/dev/null
sleep 5
echo "  trades rows: ${ROWS_BEFORE} -> $(rows)   (the survived order fills)"
[[ "$(rows)" == "$(( ROWS_BEFORE + 2 ))" ]] \
  || fail "the order that survived the rejected replace could not be traded"

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
