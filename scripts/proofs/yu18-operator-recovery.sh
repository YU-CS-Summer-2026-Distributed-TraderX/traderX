#!/usr/bin/env bash
# yu18-operator-recovery.sh — YU18 event-recovery FR-ER01/06/08 and NFR-ER02 on a live stack, at the
# effect end. Interface: docs/risk-integration/event-recovery.md (`POST /v2/projection-recovery/catch-up`
# on trade-processor, risk-control token + operator header, a loopback gateway origin) and FR-ER08's
# position-service readers.
#
# What this tier can prove. A POSITIVE catch-up needs a managed epoch-v1 run (a descriptor on every
# member, a registered and selected scope, the ri06 migrations): the docs say nothing enables that on
# a GKE profile, and selecting a fresh scope takes a frozen predecessor run. So on the rendered tier
# this proves that every way in is REFUSED explicitly and writes nothing:
#
#   1. no token, and a token without X-Risk-Operator -> 401/403 (FR-ER01 "explicit operator")
#   2. a scope that is not registered -> a 4xx refusal (FR-ER01 binds the registered scope only)
#   3. a non-loopback gateway origin, and an origin with a path or credentials -> 4xx (NFR-ER02)
#   4. FR-ER08: GET /v2/projections/active -> 503 with no selected pointer; GET /v2/projections -> 200
#      list; an unknown scope's orders on trade-processor -> 404
#   5. effect end: SQL trades/orderbook/positions and the recovery checkpoint table are unchanged
#
# A 5xx anywhere is a failure: a refusal must say why, not crash. MANAGED=1 is reserved for a rig with
# a selected scope (not implemented here: no such rig exists on this tier).
# Usage: CTX=<ctx> NS=<ns> bash scripts/proofs/yu18-operator-recovery.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
pf TP "${TP_TARGET:-deploy/trade-processor}" 18091
pf PS "${PS_TARGET:-svc/position-service}" 18090
TOK="$(operator_token)"; [[ -n "${TOK}" ]] || fail "auth-secrets has no risk-control-token"
ADMIN="$(mint true '[]')"
URL="${TP}/v2/projection-recovery/catch-up"
counts() { sql "SELECT (SELECT COUNT(*) FROM trades),(SELECT COUNT(*) FROM orderbook),(SELECT COUNT(*) FROM positions),(SELECT COALESCE(SUM(quantity),0) FROM positions)" | tr '\t' ' '; }
ckpt() { sql "SELECT COUNT(*) FROM projection_recovery" || echo absent; }
catchup() { # catchup <scope> <endpoint> [operator] [token]   (BEARER=<jwt> adds Authorization)
  local h=(-H 'Content-Type: application/json')
  [[ -n "${BEARER:-}" ]] && h+=(-H "Authorization: Bearer ${BEARER}")
  [[ -n "${4-${TOK}}" ]] && h+=(-H "X-Risk-Control-Token: ${4-${TOK}}")
  [[ -n "${3-yu18-operator-recovery}" ]] && h+=(-H "X-Risk-Operator: ${3-yu18-operator-recovery}")
  C="$(code -X POST "${URL}" "${h[@]}" -d "{\"projectionScope\":\"$1\",\"endpoint\":\"$2\"}")"; R="$(body)"
  echo "    scope=$1 endpoint=$2 -> ${C} ${R:0:160}"
}
refused() { [[ "${C}" == 4* ]] || fail "$1: answered ${C} (want a 4xx refusal) ${R:0:200}"; }

step "0. before"
B="$(counts)"; K0="$(ckpt)"
ACTIVE="$(curl -s -m 10 "${PS}/v2/projections/active")"; ac="$(curl -s -m 10 -o /dev/null -w '%{http_code}' "${PS}/v2/projections/active")"
LIST="$(curl -s -m 10 "${PS}/v2/projections")"
SCOPE="$(jget '(d[0].get("projection_scope") or d[0].get("projectionScope")) if d else ""' <<<"${LIST}")"
ok "SQL trades/orders/positions/net ${B}; recovery checkpoints ${K0}; active ${ac} ${ACTIVE:0:80}; registry ${LIST:0:120}"

# The vacuous-pass guard. Steps 1-3 tell refusals apart only if a FULLY credentialed call gets past
# authentication; if it is 401 too, every refusal below would be the same 401 and prove nothing.
BEARER="${ADMIN}" catchup "pa-unregistered-$$" http://127.0.0.1:18110
if [[ "${C}" == 401 || "${C}" == 403 ]]; then
  echo "[SKIP] steps 1-3: a call with the risk-control token, an operator header AND an admin bearer is ${C}:"
  echo "       the operator route is closed on this tier, so its refusals cannot be told apart (FR-ER01..06 not exercised)"
  CLOSED=1
fi
if [[ -z "${CLOSED:-}" ]]; then
step "1. FR-ER01: an explicit operator only"
catchup "${SCOPE:-legacy-unknown}" http://127.0.0.1:18110 "" ""; [[ "${C}" == 401 || "${C}" == 403 ]] || fail "no token: ${C}, want 401/403"
catchup "${SCOPE:-legacy-unknown}" http://127.0.0.1:18110 "" "${TOK}"; [[ "${C}" == 4* ]] || fail "token without X-Risk-Operator: ${C}, want 4xx"
catchup "${SCOPE:-legacy-unknown}" http://127.0.0.1:18110 yu18-operator-recovery "wrong-${TOK:0:8}"; [[ "${C}" == 401 || "${C}" == 403 ]] || fail "wrong token: ${C}, want 401/403"
ok "bare, operator-less and wrong-token calls refused"

step "2. FR-ER01: only a registered scope"
catchup "pa-unregistered-$$" http://127.0.0.1:18110; refused "an unregistered scope"
ok "unregistered scope refused"

step "3. NFR-ER02: loopback gateway origins only"
catchup "${SCOPE:-legacy-unknown}" http://order-matcher:18110; refused "a non-loopback origin"
catchup "${SCOPE:-legacy-unknown}" http://127.0.0.1:18110/recon; refused "an origin with a path"
catchup "${SCOPE:-legacy-unknown}" http://user:pw@127.0.0.1:18110; refused "an origin with credentials"
ok "non-loopback, path and credential origins refused"
fi

step "4. FR-ER08 readers"
if [[ -z "${SCOPE}" ]]; then
  [[ "${ac}" == 503 ]] || fail "GET /v2/projections/active with an empty registry answered ${ac} ${ACTIVE:0:120}, want 503"
fi
[[ "$(jget 'isinstance(d,list)' <<<"${LIST}")" == True ]] || fail "GET /v2/projections is not a list: ${LIST:0:200}"
oc="$(curl -s -m 10 -o /dev/null -w '%{http_code}' "${TP}/v2/projections/pa-unregistered-$$/accounts/22214/orders")"
[[ "${oc}" == 404 ]] || fail "unknown scope's orders answered ${oc}, want 404 (FR-ER08)"
ok "active ${ac}; registry is a list (${SCOPE:-empty}); unknown-scope orders 404"

step "5. effect end: nothing written"
A="$(counts)"; K1="$(ckpt)"
[[ "${A}" == "${B}" && "${K1}" == "${K0}" ]] || fail "SQL moved: ${B} -> ${A}; checkpoints ${K0} -> ${K1}"
ok "SQL ${A} unchanged; checkpoints ${K1}"
echo; echo "YU18-OPERATOR-RECOVERY PASS (FR-ER08 readers, nothing written${CLOSED:+; operator route CLOSED, steps 1-3 not exercised}; the positive catch-up needs a managed run)"
