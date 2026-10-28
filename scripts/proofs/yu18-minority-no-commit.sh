#!/usr/bin/env bash
# yu18-minority-no-commit.sh — YU12 FR-AC12 on the live cluster: a minority does not elect a leader,
# extend the committed log, or admit orders. The minority is made by taking the two higher ordinals away
# (StatefulSet scaled to 1): member 0 is then alone, and from where it stands a dead majority and a
# partitioned one are the same thing. DESTRUCTIVE (the cluster loses quorum for ~1 min), disposable rigs.
#
#   1. control: with 3 members an order is acked and every member's applied moves (so 3 can mean something)
#   2. scale to 1; for WINDOW s (default 45, several election timeouts) member 0 never reports LEADER and
#      its applied/engineApplied never move
#   3. an order sent in the window is not acked as accepted, and member 0's applied still has not moved
#   4. scale back to 3: one leader, the three agree on applied, and a fresh order is acked again
#   5. the window's order: reported (re-submitted after quorum or not) — FR-AC12 constrains the window only
#
# Usage: CTX=<ctx> NS=<ns> DESTRUCTIVE=1 [WINDOW=45 ACCT=22214] bash scripts/proofs/yu18-minority-no-commit.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
[[ "${DESTRUCTIVE:-0}" == 1 ]] || fail "DESTRUCTIVE=1 required: this takes the cluster below quorum"
ACCT="${ACCT:-22214}"; WINDOW="${WINDOW:-45}"; T="$(mint_ticker)"
STS="${STS:-order-matcher-cluster}"
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
ADMIN="$(mint true '[]')"; [[ -n "${ADMIN}" ]] || fail "could not mint an admin bearer"
J=(-H 'Content-Type: application/json' -H "Authorization: Bearer ${ADMIN}")
h0() { mget 0 /health | jget '[d.get("role"), d.get("applied"), d.get("engineApplied")]' | tr -d '[]",' ; }
applied_all() { local m s=""; for ((m=0; m<MEMBERS; m++)); do s+="$(member_applied "${m}") "; done; echo "${s% }"; }
rest() { code -m 20 -X POST "${GW}/orders" "${J[@]}" -d "{\"accountId\":${ACCT},\"ticker\":\"${T}\",\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":$1}"; }
restore() { "${K[@]}" scale sts "${STS}" --replicas=3 >/dev/null 2>&1; }
trap 'restore; _lib_yu18_cleanup' EXIT

step "1. control: three members admit"
c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-minority' -d "{\"accountId\":${ACCT},\"tickers\":\"${T}\",\"price\":50}")"
[[ "${c}" == 200 ]] || fail "seed ${T}: ${c}"
A0="$(applied_all)"; c="$(rest 49)"
[[ "${c}" == 200 && "$(body)" == *'"kind":1'* ]] || fail "control order not acked: ${c} $(body)"
for i in $(seq 1 20); do A1="$(applied_all)"; read -r -a a <<<"${A1}"; read -r -a b <<<"${A0}"; (( a[0] > b[0] && a[0] == a[1] && a[1] == a[2] )) && break; sleep 1; done
(( a[0] > b[0] && a[0] == a[1] && a[1] == a[2] )) || fail "control: applied ${A0} -> ${A1}"
ok "acked $(body); applied ${A0} -> ${A1}"

step "2. member 0 alone for ${WINDOW} s"
"${K[@]}" scale sts "${STS}" --replicas=1 >/dev/null
"${K[@]}" wait --for=delete pod "${STS}-1" "${STS}-2" --timeout=180s >/dev/null 2>&1 || fail "members 1 and 2 did not go"
read -r R0 P0 E0 <<<"$(h0)"; echo "    member 0 at the start: role ${R0} applied ${P0} engineApplied ${E0}"
ORDER_C=""; t0=${SECONDS}; leader_seen=""; moved=""
while (( SECONDS - t0 < WINDOW )); do
  read -r R P E <<<"$(h0)"
  [[ "${R}" == LEADER ]] && leader_seen="${leader_seen:-at +$((SECONDS - t0))s}"
  [[ "${P}" != "${P0}" || "${E}" != "${E0}" ]] && moved="${moved:-applied ${P0}->${P} engine ${E0}->${E} at +$((SECONDS - t0))s}"
  if [[ -z "${ORDER_C}" ]] && (( SECONDS - t0 > 15 )); then
    step "3. an order inside the window"
    ORDER_C="$(rest 48)"; ORDER_B="$(body)"; echo "    -> ${ORDER_C} ${ORDER_B:0:200}"
  fi
  sleep 2
done
[[ -z "${leader_seen}" ]] || fail "member 0 alone reported LEADER ${leader_seen} (FR-AC12: a minority SHALL NOT elect a leader)"
[[ -z "${moved}" ]] || fail "member 0 alone moved its log: ${moved} (FR-AC12: SHALL NOT extend the committed log)"
[[ "${ORDER_C}" == 200 && "${ORDER_B}" == *'"kind":1'* ]] && fail "an order was acked accepted while member 0 was alone: ${ORDER_B} (FR-AC12: SHALL NOT admit orders)"
ok "never LEADER, applied ${P0} and engineApplied ${E0} unchanged for ${WINDOW} s; the window's order answered ${ORDER_C}"

step "4. quorum back"
restore
"${K[@]}" rollout status sts "${STS}" --timeout=600s >/dev/null || fail "the members did not come back"
for i in $(seq 1 90); do
  roles="$(for ((m=0; m<MEMBERS; m++)); do mget "${m}" /health | jget 'd.get("role")'; done | sort | uniq -c | tr -s ' ' | tr '\n' ';')"
  A2="$(applied_all)"; read -r -a a <<<"${A2}"
  [[ "${roles}" == *"1 LEADER"* && "${a[0]}" == "${a[1]}" && "${a[1]}" == "${a[2]}" && -n "${a[0]}" ]] && break; sleep 2
done
[[ "${roles}" == *"1 LEADER"* && "${a[0]}" == "${a[1]}" && "${a[1]}" == "${a[2]}" ]] || fail "after restore: roles ${roles} applied ${A2}"
_lib_yu18_cleanup; PF_PIDS=(); TP=""; pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
for i in $(seq 1 30); do c="$(rest 47)"; [[ "${c}" == 200 && "$(body)" == *'"kind":1'* ]] && break; sleep 2; done
[[ "${c}" == 200 && "$(body)" == *'"kind":1'* ]] || fail "after restore a fresh order is not acked: ${c} $(body)"
ok "roles ${roles} applied ${A2}; a fresh order acked"

step "5. the window's order, after quorum returned"
for i in $(seq 1 15); do n="$(sql "SELECT COUNT(*) FROM orderbook WHERE security='${T}' AND limitprice=48")"; [[ "${n}" != 0 ]] && break; sleep 1; done
echo "    SQL orders on ${T} @48 (the window's order): ${n:-0} — $([[ "${n:-0}" == 0 ]] && echo 'never admitted' || echo 'admitted AFTER quorum returned (a gateway re-submission; not inside the window)')"
for r in $(sql "SELECT SUBSTRING_INDEX(orderid,'-',-1) FROM orderbook WHERE security='${T}' AND status='NEW'"); do curl -s -m 10 -o /dev/null -X POST "${GW}/cancel" "${J[@]}" -d "{\"orderRef\":${r}}"; done
echo; echo "YU18-MINORITY-NO-COMMIT PASS window=${WINDOW}s"
