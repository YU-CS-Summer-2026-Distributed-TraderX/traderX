#!/usr/bin/env bash
# yu18-risk-capacity-metrics.sh — YU18 FR-RCM01..03: the members' risk-capacity gauges are the engine's own accounting.
#
#   1. every member: traderx_risk_state_available 1, the same traderx_risk_accounts, and identical per-account samples
#      (replicated deterministic state read at a quiet instant)
#   2. a resting Buy Q @ X on ACCT_A raises ACCT_A's reservation, and the member aggregate, by exactly Q*X*1e6 ticks on
#      every member; cancelled, both return to their values before it, and ACCT_A keeps its samples (FR-RCM02: an
#      occupied account retains explicit samples after release)
#   3. a cross of Q @ X between ACCT_A and ACCT_B raises each account's executed notional by exactly Q*X*1e6
#   4. an order for an account the engine never admitted is refused and creates no metric label (FR-RCM02)
#   5. traderx_risk_accounts counts exactly the account labels (FR-RCM03: series bounded by occupied slots)
#
# Negative control: NEGATIVE=1 rests Q+1 in step 2 while expecting Q. Step 2 must FAIL; a pass means the delta check
# cannot tell one lot.
# Needs a quiet rig (no other writer on ACCT_A/ACCT_B). Usage: CTX=<ctx> NS=<ns> [NEGATIVE=1] bash ...
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
ACCT_A="${ACCT_A:-22214}"; ACCT_B="${ACCT_B:-42422}"; NOBODY="${NOBODY:-98761}"
T="$(mint_ticker)"; PX=50; Q=7; X=$((PX - 3))
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
ADMIN="$(mint true '[]')"; [[ -n "${ADMIN}" ]] || fail "could not mint an admin bearer"
J=(-H 'Content-Type: application/json' -H "Authorization: Bearer ${ADMIN}")
place() { code -X POST "${GW}/orders" "${J[@]}" -d "{\"accountId\":$1,\"ticker\":\"${T}\",\"side\":\"$2\",\"quantity\":$3,\"limitPrice\":$4}"; }
# metric <member> <name> [account] -> the sample's value ('' when absent)
metrics() { mget "$1" /metrics; }
sample() { awk -v n="$2" -v a="${3:-}" '$1 ~ "^"n"\\{" { if (a == "" ? $1 !~ /account=/ : index($1, "account=\""a"\"")) { print $2; exit } }' <<<"$1"; }
snap() { # snap <member> -> "avail accounts aggregate resA execA resB execB"
  local m; m="$(metrics "$1")"
  echo "$(sample "${m}" traderx_risk_state_available) $(sample "${m}" traderx_risk_accounts) $(sample "${m}" traderx_risk_reserved_notional_ticks)" \
       "$(sample "${m}" traderx_risk_account_reserved_notional_ticks "${ACCT_A}") $(sample "${m}" traderx_risk_account_executed_notional_ticks "${ACCT_A}")" \
       "$(sample "${m}" traderx_risk_account_reserved_notional_ticks "${ACCT_B}") $(sample "${m}" traderx_risk_account_executed_notional_ticks "${ACCT_B}")"; }
all() { local m v=(); for ((m=0; m<MEMBERS; m++)); do v+=("$(snap "${m}")"); done; printf '%s\n' "${v[@]}"; }
agree() { local s; s="$(all)"; [[ "$(sort -u <<<"${s}" | wc -l | tr -d ' ')" == 1 ]] && head -1 <<<"${s}"; }
settle() { local i r; for i in $(seq 1 20); do r="$(agree)" && [[ -n "${r}" && "${r}" != "$1" ]] && { echo "${r}"; return 0; }; sleep 0.5; done; echo "$(all | tr '\n' '|')"; return 1; }

step "0. seed ${T} @${PX}"
c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-risk-capacity-metrics' \
  -d "{\"accountId\":${ACCT_A},\"tickers\":\"${T}\",\"price\":${PX}}")"; [[ "${c}" == 200 ]] || fail "seed: ${c} $(body)"

step "1. every member reports the same accounting"
S0="$(settle none)" || fail "members disagree at a quiet instant: ${S0}"
read -r av n agg ra ea rb eb <<<"${S0}"
[[ "${av}" == 1 && -n "${ra}" && -n "${rb}" ]] || fail "member gauges: available=${av} ACCT_A=${ra} ACCT_B=${rb} (FR-RCM01)"
ok "available 1, ${n} accounts, aggregate ${agg}; ${ACCT_A} reserved ${ra} executed ${ea}; ${ACCT_B} reserved ${rb} executed ${eb}"

step "2. a resting Buy ${Q} @ ${X} reserves exactly ${Q}*${X}*1e6${NEGATIVE:+ (NEGATIVE: rests $((Q + 1)))}"
q="${Q}"; [[ "${NEGATIVE:-0}" == 1 ]] && q=$((Q + 1))
c="$(place "${ACCT_A}" Buy "${q}" "${X}")"; R="$(body | jget 'd.get("orderRef")')"; [[ "${c}" == 200 && -n "${R}" ]] || fail "buy: ${c} $(body)"
trap 'code -X POST "${GW}/cancel" "${J[@]}" -d "{\"orderRef\":${R}}" >/dev/null; _lib_yu18_cleanup' EXIT   # a failed run leaves no reservation
S1="$(settle "${S0}")" || fail "members disagree after the buy: ${S1}"
read -r _ _ agg1 ra1 _ _ _ <<<"${S1}"; d=$(( Q * X * 1000000 ))
(( ra1 - ra == d && agg1 - agg == d )) || fail "reservation ${ACCT_A} ${ra} -> ${ra1}, aggregate ${agg} -> ${agg1}: want +${d} each (FR-RCM01)"
code -X POST "${GW}/cancel" "${J[@]}" -d "{\"orderRef\":${R}}" >/dev/null
S2="$(settle "${S1}")" || fail "members disagree after the cancel: ${S2}"
read -r _ _ agg2 ra2 _ _ _ <<<"${S2}"
[[ "${ra2}" == "${ra}" && "${agg2}" == "${agg}" ]] || fail "after the cancel ${ACCT_A} reserved ${ra2} (was ${ra}), aggregate ${agg2} (was ${agg})"
ok "+${d} on ${ACCT_A} and the aggregate on every member; released back to ${ra} / ${agg}, samples kept"

step "3. a cross of ${Q} @ ${PX}: executed notional +${Q}*${PX}*1e6 on each side"
place "${ACCT_B}" Sell "${Q}" "${PX}" >/dev/null; place "${ACCT_A}" Buy "${Q}" "${PX}" >/dev/null
S3="$(settle "${S2}")" || fail "members disagree after the cross: ${S3}"
read -r _ _ _ _ ea3 _ eb3 <<<"${S3}"; e=$(( Q * PX * 1000000 ))
(( ea3 - ea == e && eb3 - eb == e )) || fail "executed ${ACCT_A} ${ea} -> ${ea3}, ${ACCT_B} ${eb} -> ${eb3}: want +${e} each"
ok "executed +${e} on both accounts, every member"

step "4. a refused unknown account creates no label"
c="$(place "${NOBODY}" Buy 1 "${X}")"; echo "    order for ${NOBODY} -> ${c} $(body | head -c 120)"
[[ "$(body)" == *UNKNOWN_ACCOUNT* || "$(body)" == *REJECTED* || "${c}" != 200 ]] || fail "an order for never-admitted ${NOBODY} was accepted"
for ((m=0; m<MEMBERS; m++)); do ! metrics "${m}" | grep -q "account=\"${NOBODY}\"" || fail "member ${m} has a metric label for refused ${NOBODY} (FR-RCM02)"; done
ok "refused; no member labels ${NOBODY}"

step "5. the series are bounded by the occupied slots"
for ((m=0; m<MEMBERS; m++)); do
  mm="$(metrics "${m}")"; labels="$(grep -o '^traderx_risk_account_reserved_notional_ticks{[^}]*account="[0-9]*"' <<<"${mm}" | sort -u | wc -l | tr -d ' ')"
  [[ "${labels}" == "$(sample "${mm}" traderx_risk_accounts)" ]] || fail "member ${m}: ${labels} account labels, traderx_risk_accounts $(sample "${mm}" traderx_risk_accounts)"
done
ok "account labels = traderx_risk_accounts (${n}) on every member"

echo; echo "YU18-RISK-CAPACITY-METRICS PASS ticker=${T}"
