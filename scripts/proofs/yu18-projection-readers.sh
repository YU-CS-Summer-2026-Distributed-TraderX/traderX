#!/usr/bin/env bash
# yu18-projection-readers.sh — YU18 FR-ER08 on a live stack: the projection registry readers.
#   GET /v2/projections/active -> 503 with no selected pointer; GET /v2/projections -> 200 list;
#   an unknown scope's orders on trade-processor -> 404. A 5xx anywhere is a failure.
# The rest of event recovery (FR-ER01..07, operator catch-up) was retired with rows mode on 2026-11-06
# (trade-history-tiers FR-THT-01); this is what remained of yu18-operator-recovery.sh.
# Usage: CTX=<ctx> NS=<ns> bash scripts/proofs/yu18-projection-readers.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
pf TP "${TP_TARGET:-deploy/trade-processor}" 18091
pf PS "${PS_TARGET:-svc/position-service}" 18090

step "FR-ER08 readers"
ACTIVE="$(curl -s -m 10 "${PS}/v2/projections/active")"; ac="$(curl -s -m 10 -o /dev/null -w '%{http_code}' "${PS}/v2/projections/active")"
LIST="$(curl -s -m 10 "${PS}/v2/projections")"
SCOPE="$(jget '(d[0].get("projection_scope") or d[0].get("projectionScope")) if d else ""' <<<"${LIST}")"
if [[ -z "${SCOPE}" ]]; then
  [[ "${ac}" == 503 ]] || fail "GET /v2/projections/active with an empty registry answered ${ac} ${ACTIVE:0:120}, want 503"
fi
[[ "${ac}" != 5* || "${ac}" == 503 ]] || fail "GET /v2/projections/active answered ${ac}"
[[ "$(jget 'isinstance(d,list)' <<<"${LIST}")" == True ]] || fail "GET /v2/projections is not a list: ${LIST:0:200}"
oc="$(curl -s -m 10 -o /dev/null -w '%{http_code}' "${TP}/v2/projections/pa-unregistered-$$/accounts/22214/orders")"
[[ "${oc}" == 404 ]] || fail "unknown scope's orders answered ${oc}, want 404 (FR-ER08)"
ok "active ${ac}; registry is a list (${SCOPE:-empty}); unknown-scope orders 404"
echo; echo "YU18-PROJECTION-READERS PASS (FR-ER08)"
