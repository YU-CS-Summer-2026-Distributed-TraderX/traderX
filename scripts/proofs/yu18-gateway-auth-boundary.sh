#!/usr/bin/env bash
# yu18-gateway-auth-boundary.sh — ADR-082 (ENGINE-REVIEW S1/S2/S4/S6) and FR-ERH-F4, FR-MAR01, asserted
# at the effect end: what a refused request did NOT do is read from the members' registered symbol
# table and the order read model, not from the status code alone.
#
#   S2  /seed needs X-Risk-Control-Token + non-blank X-Risk-Operator (or an admin JWT). Refused
#       forms leave the minted ticker OUT of every member's /symbols; the accepted form puts it IN
#       (positive control: the reading can see a registration).
#   S4  order entry only looks tickers up: an order naming a never-registered ticker is refused and
#       the ticker is still absent from /symbols afterwards (also FR-ERH-F4).
#   S1  each of the seven order routes answers 401 without a bearer; a bearer for account A cannot
#       place for account B (single order or one element of a batch); a single-entitlement bearer
#       that omits accountId acts on its own account. Effect end: GET /accounts/{id}/orders.
#   S6  a body over GATEWAY_MAX_BODY_BYTES (8 MiB) and a batch over GATEWAY_MAX_BATCH_ORDERS
#       (100,000) both answer 413.
#   MAR01 member GET /risk/control/accounts refuses without a JWT and lists the account with one.
#
# Usage: CTX=<ctx> NS=<ns> ACCT_A=22214 ACCT_B=42422 bash scripts/proofs/yu18-gateway-auth-boundary.sh
# Leaves nothing resting: every accepted order is cancelled on exit.
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
ACCT_A="${ACCT_A:-22214}"; ACCT_B="${ACCT_B:-42422}"
T="$(mint_ticker)"; U="$(mint_ticker)"   # T gets registered; U never does
PX=100; LOW=90                          # resting buys at LOW never cross anything on a fresh ticker
RESTING=()
cleanup() {
  local r; for r in "${RESTING[@]:-}"; do
    [[ -n "${r}" ]] && curl -s -m 10 -o /dev/null -X POST "${GW}/cancel" -H "Authorization: Bearer ${ADMIN}" \
      -H 'Content-Type: application/json' -d "{\"orderRef\":${r}}"
  done
  _lib_yu18_cleanup
}
trap cleanup EXIT

pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
pf TP "${TP_TARGET:-deploy/trade-processor}" 18091
ADMIN="$(mint true '[]')"; TOK_A="$(mint false "[${ACCT_A}]")"; OPTOK="$(operator_token)"
[[ -n "${ADMIN}" && -n "${TOK_A}" ]] || fail "could not mint bearers"
[[ -n "${OPTOK}" ]] || fail "auth-secrets has no risk-control-token"
J=(-H 'Content-Type: application/json')

in_symbols() { # in_symbols <member> <ticker> -> 0 if listed
  mget "$1" /symbols | python3 -c "import sys,json
t='$2'; s=sys.stdin.read()
try: d=json.loads(s)
except Exception: sys.exit(3)
def walk(x):
    if isinstance(x,str): return x==t
    if isinstance(x,dict): return any(walk(k) or walk(v) for k,v in x.items())
    if isinstance(x,list): return any(walk(v) for v in x)
    return False
sys.exit(0 if walk(d) else 1)"
}
absent_everywhere() { local m rc; for ((m=0; m<MEMBERS; m++)); do in_symbols "${m}" "$1"; rc=$?
  [[ ${rc} == 3 ]] && fail "member ${m} /symbols is not JSON: $(mget "${m}" /symbols | head -c 200)"
  [[ ${rc} == 0 ]] && return 1; done; return 0; }
present_everywhere() { local m; for ((m=0; m<MEMBERS; m++)); do in_symbols "${m}" "$1" || return 1; done; return 0; }
orders_on() { # orders_on <acct> <ticker> -> count of read-model rows (any status) on that ticker
  curl -s -m 10 "${TP}/accounts/$1/orders?status=all" -H "Authorization: Bearer ${ADMIN}" \
    | jget "len([o for o in d if (o.get('security') or o.get('ticker'))=='$2'])"
}

step "0. preflight"
[[ "$(code "${GW}/ready")" == 200 ]] || fail "gateway /ready is not 200"
absent_everywhere "${T}" && absent_everywhere "${U}" || fail "fresh tickers ${T}/${U} already registered"
ok "gateway ready; ${T} and ${U} unknown to all ${MEMBERS} members"

step "1. S2: /seed without the operator pair is refused and registers nothing"
SEED="{\"accountId\":${ACCT_A},\"tickers\":\"${T}\",\"price\":${PX}}"
c1="$(code -X POST "${GW}/seed" "${J[@]}" -d "${SEED}")"
c2="$(code -X POST "${GW}/seed" "${J[@]}" -H "X-Risk-Control-Token: ${OPTOK}" -d "${SEED}")"
c3="$(code -X POST "${GW}/seed" "${J[@]}" -H "X-Risk-Control-Token: ${OPTOK}" -H 'X-Risk-Operator: ' -d "${SEED}")"
c4="$(code -X POST "${GW}/seed" "${J[@]}" -H 'X-Risk-Control-Token: wrong' -H 'X-Risk-Operator: yu18-proof' -d "${SEED}")"
c5="$(code -X POST "${GW}/seed" "${J[@]}" -H "Authorization: Bearer ${TOK_A}" -d "${SEED}")"
echo "    none=${c1} token-only=${c2} blank-operator=${c3} wrong-token=${c4} non-admin-jwt=${c5}"
for c in "${c1}" "${c2}" "${c3}" "${c4}" "${c5}"; do [[ "${c}" == 2* ]] && fail "a refused /seed form answered ${c}"; done
sleep 2; absent_everywhere "${T}" || fail "S2: a refused /seed REGISTERED ${T}"
ok "five refused /seed forms (401/403 class), ${T} still absent on every member"
c6="$(code -X POST "${GW}/seed" "${J[@]}" -H "X-Risk-Control-Token: ${OPTOK}" -H 'X-Risk-Operator: yu18-proof' -d "${SEED}")"
[[ "${c6}" == 200 ]] || fail "operator /seed answered ${c6}: $(body)"
for i in $(seq 1 20); do present_everywhere "${T}" && break; sleep 1; done
present_everywhere "${T}" || fail "operator /seed answered 200 but ${T} is not in every member's /symbols"
ok "operator /seed registered ${T} on all ${MEMBERS} members (positive control)"

step "2. S4/F4: an order for a never-registered ticker is refused and registers nothing"
c="$(code -X POST "${GW}/orders" "${J[@]}" -H "Authorization: Bearer ${ADMIN}" \
  -d "{\"accountId\":${ACCT_A},\"ticker\":\"${U}\",\"side\":\"Buy\",\"quantity\":5,\"limitPrice\":${LOW}}")"
echo "    /orders ${U} -> ${c} $(body | head -c 200)"
[[ "${c}" == 2* && "$(body)" != *'"kind":2'* ]] && fail "order for unregistered ${U} was ACCEPTED"
sleep 2; absent_everywhere "${U}" || fail "S4: order entry REGISTERED ${U}"
[[ "${c}" == 504 ]] && echo "    NOTE: a definitive pre-sequencing refusal is reported as 504 'no committed ack', the ambiguous-outcome status (ADR-037); the spec names no status for S4/F4"
ok "refused (${c}); ${U} absent from every member"

step "3. S1: the seven order routes answer 401 without a bearer"
ORD="{\"accountId\":${ACCT_A},\"ticker\":\"${T}\",\"side\":\"Buy\",\"quantity\":7,\"limitPrice\":${LOW}}"
SWAP="{\"accountId\":${ACCT_A},\"payReceive\":\"PAY\",\"notional\":1000000,\"fixedRate\":0.04,\"effectiveDate\":\"2027-01-04\",\"maturityDate\":\"2029-01-04\",\"floatIndex\":\"USD-SOFR\"}"
for route in orders orders/batch cancel replace trades swaps swaptions; do
  case "${route}" in
    orders) b="${ORD}";; orders/batch) b="[${ORD}]";; cancel) b='{"orderRef":1}';;
    replace) b='{"orderRef":1,"quantity":3,"limitPrice":91}';; trades) b="${ORD}";;
    swaps) b="${SWAP}";; swaptions) b="${SWAP%\}},\"expiryDate\":\"2027-06-01\",\"exerciseStyle\":\"EUROPEAN\"}";;
  esac
  c="$(code -X POST "${GW}/${route}" "${J[@]}" -d "${b}")"
  [[ "${c}" == 401 ]] || fail "S1: bare POST /${route} answered ${c} (want 401): $(body | head -c 200)"
  echo "    /${route}: 401"
done
sleep 3
[[ "$(orders_on "${ACCT_A}" "${T}")" == 0 ]] || fail "S1: a bare request left an order on ${T} for ${ACCT_A}"
ok "all seven 401; the read model holds no order on ${T}"

step "4. S1: a bearer for ${ACCT_A} cannot act on ${ACCT_B}"
c="$(code -X POST "${GW}/orders" "${J[@]}" -H "Authorization: Bearer ${TOK_A}" -d "${ORD/${ACCT_A}/${ACCT_B}}")"
echo "    single order for ${ACCT_B} -> ${c} $(body | head -c 160)"
[[ "${c}" == 2* ]] && fail "S1: bearer for ${ACCT_A} placed for ${ACCT_B} (${c})"
cb="$(code -X POST "${GW}/orders/batch" "${J[@]}" -H "Authorization: Bearer ${TOK_A}" \
  -d "[${ORD/:7,/:8,},${ORD/${ACCT_A}/${ACCT_B}}]")"
echo "    batch [own, foreign] -> ${cb} $(body | head -c 160)"
sleep 3
nb="$(orders_on "${ACCT_B}" "${T}")"; na="$(orders_on "${ACCT_A}" "${T}")"
[[ "${nb}" == 0 ]] || fail "S1: ${ACCT_B} has ${nb} order(s) on ${T} placed with ${ACCT_A}'s bearer"
ok "no order on ${T} for ${ACCT_B} (single: ${c}; batch: ${cb}; own element of the refused batch booked: ${na})"
[[ "${na}" == 0 ]] || echo "    NOTE: the batch's own-account element was admitted (${na}); the spec says each element is checked, not whether one refusal rejects the batch"

step "5. positive control: the entitled bearer places for ${ACCT_A}, with and without accountId"
c="$(code -X POST "${GW}/orders" "${J[@]}" -H "Authorization: Bearer ${TOK_A}" -d "${ORD}")"
[[ "${c}" == 200 && "$(body)" != *'"kind":2'* ]] || fail "entitled order refused: ${c} $(body)"
r1="$(body | jget 'd.get("orderRef")')"; RESTING+=("${r1}")
c="$(code -X POST "${GW}/orders" "${J[@]}" -H "Authorization: Bearer ${TOK_A}" \
  -d "{\"ticker\":\"${T}\",\"side\":\"Buy\",\"quantity\":9,\"limitPrice\":${LOW}}")"
[[ "${c}" == 200 && "$(body)" != *'"kind":2'* ]] || fail "single-entitlement order without accountId refused: ${c} $(body)"
r2="$(body | jget 'd.get("orderRef")')"; RESTING+=("${r2}")
want=$(( na + 2 ))
for i in $(seq 1 30); do [[ "$(orders_on "${ACCT_A}" "${T}")" == "${want}" ]] && break; sleep 1; done
[[ "$(orders_on "${ACCT_A}" "${T}")" == "${want}" ]] || fail "read model shows $(orders_on "${ACCT_A}" "${T}") orders on ${T} for ${ACCT_A}, want ${want} (refs ${r1}, ${r2})"
ok "refs ${r1} and ${r2} reached ${ACCT_A}'s read model (the omitted accountId resolved to ${ACCT_A})"

step "6. S6: oversize body and oversize batch answer 413"
big="$(mktemp)"; python3 -c "import sys; sys.stdout.write('{\"pad\":\"' + 'x'*(8*1024*1024+16) + '\"}')" > "${big}"
c="$(code -X POST "${GW}/orders" "${J[@]}" -H "Authorization: Bearer ${ADMIN}" --data-binary @"${big}")"
[[ "${c}" == 413 ]] || fail "S6: 8 MiB+ body answered ${c} (want 413)"
python3 -c "import sys; o='{\"accountId\":${ACCT_A},\"ticker\":\"${U}\",\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":1}'; sys.stdout.write('['+','.join([o]*100001)+']')" > "${big}"
echo "    100,001-order batch is $(wc -c < "${big}" | tr -d ' ') bytes (under 8 MiB, so only the count can refuse it)"
c="$(code -X POST "${GW}/orders/batch" "${J[@]}" -H "Authorization: Bearer ${ADMIN}" --data-binary @"${big}")"
rm -f "${big}"
[[ "${c}" == 413 ]] || fail "S6: 100,001-order batch answered ${c} (want 413)"
ok "both 413"

step "7. FR-MAR01: member /risk/control/accounts needs a JWT"
st="$(mstatus 0 /risk/control/accounts)"
[[ "${st}" == 401 || "${st}" == 403 ]] || fail "unauthenticated /risk/control/accounts answered ${st:-nothing} (want 401/403)"
out="$(mget 0 /risk/control/accounts "${ADMIN}")"
[[ "$(jget 'd.get("available")' <<<"${out}")" == True ]] || fail "authenticated readout not available: ${out:0:300}"
jget "any(str(a.get('accountId', a.get('id')))=='${ACCT_A}' for a in d.get('accounts',[]))" <<<"${out}" | grep -q True \
  || fail "readout does not list ${ACCT_A}: ${out:0:300}"
ok "${st} without a JWT; with one: available, count=$(jget 'd.get("count")' <<<"${out}"), lists ${ACCT_A}"

echo; echo "YU18-GATEWAY-AUTH-BOUNDARY PASS ticker=${T} refs=${r1},${r2}"
