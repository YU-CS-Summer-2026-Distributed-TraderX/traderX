#!/usr/bin/env bash
# yu13-readmodel-effect-end.sh — proves the order read model end-to-end at the SQL effect end:
# place → cluster books it (member next_order_ref delta, the ground truth) → leader-side /orders
# egress → trade-processor OrderFeedHandler → MariaDB `orderbook` row → GET /accounts/{id}/orders
# shows the order NEW; then a cancel makes the row CANCELED and removes it from the open set.
#
# This closes the gap the older yu13 proofs name explicitly ("there is NO order read model"):
# since brief 07 there IS one, and this is its committed, re-runnable proof.
#
# Discipline (why this proof can be trusted):
#   * Ground truth for "the order booked" is the members' traderx_cluster_next_order_ref delta,
#     agreed by ALL THREE — never the gateway's 200. A 200 has repeatedly meant nothing booked.
#   * Every read-model assertion is at the effect end: the SQL row / the REST enumeration, with a
#     bounded poll (the projection is async by design), never the ingress ack.
#   * A CONTROL order rests untouched throughout. "The canceled order left the open set" is only
#     evidence if the control is still there — otherwise an empty list (read model down, wrong DB,
#     dead NATS bridge) would pass the disappearance check.
#   * The projector's rejection signal must stay silent: a row that failed to persist is counted
#     and logged ("orderbook write rejected"), and any such log line for this proof's orders fails
#     the run — the silent-read-model-drop class (VARCHAR(15) OCC, trades FK, epoch collision) is
#     asserted absent, not assumed.
#
# kind-runnable: pure correctness, no timing claim. Usage: ./yu13-readmodel-effect-end.sh
set -euo pipefail

VERBOSE=0
case "${1:-}" in -v|--verbose) VERBOSE=1; shift ;; esac
# STDERR: order(), cancel(), rest_orders() and sql() are all captured with $(...), so a
# verbose line on stdout would be parsed as a body, a status code or a counter value.
vlog() { [ "${VERBOSE}" = 1 ] && printf '%s\n' "$@" >&2 || true; }

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K="kubectl --context ${CTX} -n ${NS}"
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
ACCT="${ACCT:-42422}"
TICKER="${TICKER:-RM$(date +%H%M%S)}"
PRICE="${PRICE:-100.00}"

fail() { echo "[FAIL] $*" >&2; exit 1; }
# Sourced for quiesced_order_refs (see refs_agreed below). Resolves `fail` and `K` at CALL time, so
# this line may sit above their definitions — `K` is set just above and `fail` on this one.
here_lib="$(cd "$(dirname "$0")" && pwd)"; . "${here_lib}/lib-consensus-readings.sh"
step() { echo; echo "=== $* ==="; }

# trade-processor projects into the `database` deploy (NOT eod-price-db, which carries the same
# schema but only the EOD pricing tables' data — orderbook there is empty forever).
# Deployment name and CONTAINER name differ on this rig: deploy/eod-price-db, container "mariadb".
# The old form failed with "container database is not valid for pod", sql() returned the error text,
# and the await loop then just spun to its timeout printing nothing at all -- the proof stopped mid
# step 2 with no message. Both overridable.
SQL_DB="${SQL_DB:-eod-price-db}"
SQL_CONTAINER="${SQL_CONTAINER:-mariadb}"
# 30s was not enough for a follower catching up after a member roll; the proof reported the three
# disagreeing on a book they agreed on moments later.
AGREE_TIMEOUT_S="${AGREE_TIMEOUT_S:-180}"
sql() { vlog "      SQL: $1"; ${K} exec "deploy/${SQL_DB}" -c "${SQL_CONTAINER}" -- mariadb -utraderx -ptraderx traderx -sN -e "$1" 2>&1 \
          | { grep -v "Using a password on the command line" || true; }; }

member_metric() { # member_metric <ordinal> <metric-prefix>
  ${K} exec "order-matcher-cluster-$1" -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' | awk -v m="^$2" '$0 ~ m {print $2}'
}
book() {
  ${K} exec "order-matcher-cluster-$1" -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
    | awk '/^traderx_book_open_orders/{d=$2} /^traderx_book_order_hash/{h=$2} END{print d, h}'
}
# Retried: members apply the committed tail at slightly different times; a single sample can catch
# one mid-apply, which looks exactly like a determinism failure and is not one. PERSISTENT
# disagreement is the failure.
digest_consensus() {
  local b0 b1 b2 i
  for i in $(seq 1 "${AGREE_TIMEOUT_S:-180}"); do
    b0="$(book 0)"; b1="$(book 1)"; b2="$(book 2)"
    if [[ "${b0}" == "${b1}" && "${b1}" == "${b2}" ]]; then echo "${b0}"; return 0; fi
    sleep 1
  done
  fail "members never agreed on the book: [${b0}] [${b1}] [${b2}]"
}
# refs_agreed -> the single agreed order-ref count, retried for the same mid-apply reason.
#
# IT DELEGATES NOW, and the delegation is the fix rather than tidiness. This file held a PRIVATE
# copy of the reading `scripts/proofs/lib-consensus-readings.sh` exists to be the only source of,
# and it read the GLOBAL traderx_cluster_next_order_ref. That was true when written and stopped
# being true when ADR-072 made the tape replay a continuous writer of order-shaped commands:
# measured 2026-08-26 on the first suite run with the replay live, this proof's step 4 reported
# "next_order_ref moved on a cancel: 3502 -> 3515" — thirteen replayed orders in the window,
# nothing wrong with the cancel, and the assertion correct about the counter and wrong about the
# world. ADR-072 names six dependent files; this was a seventh, missed because the earlier audit
# swept for `applied` rather than for the counter the proofs had retreated to.
#
# The library's quiesced_order_refs is the same shape (all three members must agree, retried) on
# the operator-scoped metric, so nothing about this proof's claim changes.
refs_agreed() { quiesced_order_refs; }

# The script owns its port-forward — a dead tunnel must not be mistakable for a missing feature.
PF_PID=""
PF_PORT="${MATCHER_URL##*:}"
stop_pf() { if [[ -n "${PF_PID}" ]]; then kill "${PF_PID}" 2>/dev/null || true; wait "${PF_PID}" 2>/dev/null || true; fi; PF_PID=""; }
start_pf() {
  stop_pf
  ${K} port-forward svc/order-matcher "${PF_PORT}:18110" >/dev/null 2>&1 & PF_PID=$!
  local tries=0
  until curl -sf --max-time 5 "${MATCHER_URL}/ready" >/dev/null 2>&1; do
    tries=$((tries + 1)); [[ ${tries} -lt 60 ]] || fail "gateway never became reachable"
    kill -0 "${PF_PID}" 2>/dev/null || { ${K} port-forward svc/order-matcher "${PF_PORT}:18110" >/dev/null 2>&1 & PF_PID=$!; }
    sleep 2
  done
}
trap stop_pf EXIT

order() { # order <side> -> body (fails the run unless it RESTS: kind=1)
  local body kind
  local req="{\"accountId\":${ACCT},\"ticker\":\"${TICKER}\",\"side\":\"$1\",\"quantity\":5,\"limitPrice\":${PRICE}}"
  vlog "      POST ${MATCHER_URL}/orders" "        ${req}"
  body="$(curl -s --max-time 30 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' -d "${req}")"
  vlog "      <- ${body}"
  kind="$(sed -n 's/.*"kind":\([0-9]*\).*/\1/p' <<<"${body}")"
  [[ "${kind}" == "1" ]] || fail "order did not rest (kind=${kind}, body=${body})"
  echo "${body}"
}
ref_of() { sed -n 's/.*"orderRef":\([0-9]*\).*/\1/p' <<<"$1"; }
cancel() { # cancel <ref> -> "<http> <body>"
  local out
  vlog "      POST ${MATCHER_URL}/cancel" "        {\"orderRef\":$1}"
  out="$(curl -s --max-time 30 -o /tmp/yu13-rme-body -w '%{http_code}' \
    -X POST "${MATCHER_URL}/cancel" -H 'Content-Type: application/json' -d "{\"orderRef\":$1}")"
  vlog "      <- ${out} $(cat /tmp/yu13-rme-body)"
  echo "${out} $(cat /tmp/yu13-rme-body)"
}

# The REST enumeration, from inside the trade-processor pod (no second tunnel to die mid-proof).
rest_orders() { # rest_orders [all] -> the raw JSON array
  local j
  vlog "      GET /accounts/${ACCT}/orders${1:+?status=all}   (from inside trade-processor)"
  j="$(${K} exec deploy/trade-processor -- \
    sh -c "wget -qO- 'http://localhost:18091/accounts/${ACCT}/orders${1:+?status=all}' 2>/dev/null")"
  vlog "      <- ${j}"
  printf '%s' "${j}"
}
# rest_status <ref> <all?> -> status of the row whose epoch-qualified id ends in "-<ref>", or ""
rest_status() {
  rest_orders "${2:-}" | python3 -c "import sys,json
try:
    rows=json.load(sys.stdin)
    print(next((r['status'] for r in rows if str(r.get('id','')).endswith('-$1')), ''))
except Exception:
    print('')"
}
sql_status() { # sql_status <ref> -> status of the SQL row, or "" (dash-anchored: '%-7' cannot match '-17')
  sql "SELECT status FROM orderbook WHERE accountid=${ACCT} AND security='${TICKER}' AND orderid LIKE '%-$1';"
}
# The projection is async by design: poll the effect end with a bounded timeout, never sleep-and-hope.
await() { # await <label> <want> <fn> <args...>
  local label="$1" want="$2"; shift 2
  local got i
  for i in $(seq 1 60); do
    got="$("$@")"
    [[ "${got}" == "${want}" ]] && { printf "   %-34s %s ✔\n" "${label}" "${want}"; return 0; }
    sleep 1
  done
  fail "${label}: wanted '${want}', still '${got}' after 60s"
}

# ---------------------------------------------------------------------------------------------
step "0. preflight"
[[ "$(${K} get pods -l app=order-matcher-cluster --no-headers 2>/dev/null | wc -l | tr -d ' ')" == "3" ]] \
  || fail "expected 3 cluster members"
[[ "$(${K} get deploy trade-processor -o jsonpath='{.status.readyReplicas}')" == "1" ]] \
  || fail "trade-processor is not READY — nothing can project to SQL, so this proof cannot run"
# Refuse to run against a trade-processor that predates the read model: a 404 here must be
# "feature absent", loudly, not a mysterious empty-list failure later.
PROBE="$(rest_orders || true)"
[[ "${PROBE}" == \[* ]] || fail "GET /accounts/${ACCT}/orders did not answer with a JSON array (got: ${PROBE:-nothing}) — deployed trade-processor lacks the order read model"
RESTARTS0="$(${K} get pods -l app=order-matcher-cluster \
  -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')"
TP_LOG_T0="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
start_pf
curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
  -d "{\"accountId\":${ACCT},\"tickers\":\"${TICKER}\",\"price\":${PRICE}}" >/dev/null \
  || fail "seed failed for ${ACCT}"
REF0="$(refs_agreed)"
echo "[ok] 3 members, read-model endpoint live, ticker ${TICKER}, next_order_ref agreed at ${REF0}"

step "1. place two resting orders: the SUBJECT (to be canceled) and the CONTROL (stays open)"
SUBJECT_BODY="$(order Buy)"; SUBJECT="$(ref_of "${SUBJECT_BODY}")"
CONTROL_BODY="$(order Buy)"; CONTROL="$(ref_of "${CONTROL_BODY}")"
echo "  subject ref=${SUBJECT}   control ref=${CONTROL}"
# Ground truth, not the 200s above: all three members agree next_order_ref advanced by exactly 2.
REF1="$(refs_agreed)"
echo "  next_order_ref: ${REF0} -> ${REF1} on all three members"
[[ "${REF1}" -eq "$(( REF0 + 2 ))" ]] \
  || fail "next_order_ref moved by $(( REF1 - REF0 )), not 2 — the 200s and the cluster disagree"

step "2. the SQL effect end shows both orders NEW"
await "orderbook row ${SUBJECT} (SQL)" "NEW" sql_status "${SUBJECT}"
await "orderbook row ${CONTROL} (SQL)" "NEW" sql_status "${CONTROL}"
SUBJECT_ID="$(sql "SELECT orderid FROM orderbook WHERE accountid=${ACCT} AND security='${TICKER}' AND orderid LIKE '%-${SUBJECT}';")"
[[ "${SUBJECT_ID}" == *-"${SUBJECT}" ]] || fail "no epoch-qualified SQL row for ref ${SUBJECT}"
echo "  epoch-qualified id: ${SUBJECT_ID}"

step "3. GET /accounts/${ACCT}/orders (open set) shows both orders NEW"
await "open set ${SUBJECT} (REST)" "NEW" rest_status "${SUBJECT}"
await "open set ${CONTROL} (REST)" "NEW" rest_status "${CONTROL}"

step "4. cancel the subject; the cluster applies it (ground truth, not the 200)"
BEFORE="$(digest_consensus)"
OUT="$(cancel "${SUBJECT}")"
echo "  POST /cancel ${SUBJECT} -> ${OUT}"
[[ "${OUT}" == 200*'"canceled":true'* ]] || fail "cancel not accepted: ${OUT}"
AFTER="$(digest_consensus)"
echo "  book: [${BEFORE}] -> [${AFTER}]"
[[ "$(( ${BEFORE%% *} - 1 ))" -eq "${AFTER%% *}" ]] \
  || fail "expected exactly one order to leave the replicated book"
# A cancel mints no orderRef: the counter must NOT have moved. This is the "never trust a 200"
# assertion in the other direction — the effect happened, and nothing else did.
REF2="$(refs_agreed)"
[[ "${REF2}" -eq "${REF1}" ]] || fail "next_order_ref moved on a cancel: ${REF1} -> ${REF2}"

step "5. the effect end: subject CANCELED, gone from the open set — control still open"
await "orderbook row ${SUBJECT} (SQL)"   "CANCELED" sql_status "${SUBJECT}"
await "open set drops ${SUBJECT} (REST)" ""         rest_status "${SUBJECT}"
await "?status=all keeps ${SUBJECT}"     "CANCELED" rest_status "${SUBJECT}" all
# The control is what makes the line above evidence: if the read model had died or emptied, the
# subject would be "gone" too — for the wrong reason.
[[ "$(rest_status "${CONTROL}")" == "NEW" ]] \
  || fail "the CONTROL order left the open set too — the subject's disappearance proves nothing"
printf "   %-34s %s ✔\n" "control ${CONTROL} still open (REST)" "NEW"

step "6. the projector's rejection signal stayed silent for this proof"
REJECTED="$(${K} logs deploy/trade-processor --since-time="${TP_LOG_T0}" 2>/dev/null \
  | { grep "orderbook write rejected" || true; })"
[[ -z "${REJECTED}" ]] || fail "the projector rejected a write during this proof: ${REJECTED}"
echo "  no 'orderbook write rejected' log lines since ${TP_LOG_T0}"

step "7. no member bounced during the proof"
RESTARTS1="$(${K} get pods -l app=order-matcher-cluster \
  -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')"
[[ "${RESTARTS0}" == "${RESTARTS1}" ]] \
  || fail "a member restarted mid-proof (${RESTARTS0} -> ${RESTARTS1}); results are not trustworthy"

echo
echo "[PASS] order read model proven at the effect end: place → next_order_ref +2 on all three"
echo "       members → orderbook rows NEW → open-set enumeration; cancel → row CANCELED, out of"
echo "       the open set, control untouched, rejection signal silent, ref counter unmoved."
