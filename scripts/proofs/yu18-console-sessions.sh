#!/usr/bin/env bash
# yu18-console-sessions.sh — the console half of the authenticated boundary (FR-CS-01, -04, -05;
# FR-ERH-C1), through the console's own HTTP surface:
#
#   CS-04  every /auth/* response carries Cache-Control: no-store (signed out, bad password, good
#          password, /auth/me, sign-out).
#   CS-05  signed out, each of the seven S1 routes proxied under /order-matcher answers
#   ERH-C1 401 admin_auth_required, and nothing reaches the read model; signed in, an order placed
#          through the same proxy is accepted and reaches the read model (the console attached its JWT).
#   CS-01  two sign-ins (two cookie jars) get two different session ids, each /auth/me returns its own;
#          signing one out leaves the other signed in.
#
# The desk half (CS-02, CS-03) needs the hosted Desk deployment and DESK_USERS_FILE; it SKIPs when
# no web-front-end-desk Deployment exists.
#
# Usage: CTX=<ctx> NS=<ns> CONSOLE_PASSWORD=<admin password> [CONSOLE_USER=admin ACCT=22214] \
#          bash scripts/proofs/yu18-console-sessions.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
: "${CONSOLE_PASSWORD:?set CONSOLE_PASSWORD (the console admin password; it is never printed)}"
USER_="${CONSOLE_USER:-admin}"; ACCT="${ACCT:-22214}"; T="$(mint_ticker)"
pf CON "${CONSOLE_TARGET:-svc/web-front-end-console}" 8080
pf TP "${TP_TARGET:-deploy/trade-processor}" 18091
JARS="$(mktemp -d)"; H="${JARS}/h"
ADMIN="$(mint true '[]')"
req() { # req <jar> <METHOD> <path> [json] -> status; headers in $H, body in $BODY_FILE
  local d=(); [[ -n "${4:-}" ]] && d=(-H 'Content-Type: application/json' -d "$4")
  curl -s -m 20 -b "${JARS}/$1" -c "${JARS}/$1" -D "${H}" -o "${BODY_FILE}" -w '%{http_code}' -X "$2" ${d[@]+"${d[@]}"} "${CON}$3"
}
nostore() { grep -qi '^cache-control:.*no-store' "${H}" || fail "$1 lacks Cache-Control: no-store ($(grep -i '^cache-control' "${H}" | tr -d '\r'))"; }
sid() { body | jget "next((v for k,v in (d.items() if isinstance(d,dict) else []) if 'session' in k.lower() and isinstance(v,str)), '') or next((v for k,v in (d.get('user',{}) or {}).items() if 'session' in k.lower()), '')"; }
login() { req "$1" POST /auth/login "{\"user\":\"${USER_}\",\"password\":\"${CONSOLE_PASSWORD}\"}"; }
orders_on() { curl -s -m 10 "${TP}/accounts/${ACCT}/orders?status=all" -H "Authorization: Bearer ${ADMIN}" | jget "len([o for o in d if o.get('security')=='${T}'])"; }

step "0. preflight: a ticker for the signed-in order"
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-console-sessions' \
  -d "{\"accountId\":${ACCT},\"tickers\":\"${T}\",\"price\":100}")"
[[ "${c}" == 200 ]] || fail "operator /seed ${T}: ${c}"
ok "seeded ${T}"

step "CS-04. /auth/* is never cacheable"
[[ "$(req anon GET /auth/me)" == 401 ]] || fail "signed-out /auth/me answered $(cat "${BODY_FILE}")"; nostore "signed-out /auth/me"
[[ "$(req anon POST /auth/login '{"user":"admin","password":"definitely-wrong"}')" == 401 ]] || fail "a wrong password was accepted"; nostore "bad-password /auth/login"
ok "signed-out /auth/me 401 and bad-password /auth/login 401, both no-store"

step "CS-05 / ERH-C1. signed out, the seven S1 routes are refused at the console"
ORD="{\"accountId\":${ACCT},\"ticker\":\"${T}\",\"side\":\"Buy\",\"quantity\":4,\"limitPrice\":90}"
for route in orders orders/batch cancel replace trades swaps swaptions; do
  case "${route}" in orders/batch) b="[${ORD}]";; cancel) b='{"orderRef":1}';; replace) b='{"orderRef":1,"quantity":2,"limitPrice":91}';; *) b="${ORD}";; esac
  st="$(req anon POST "/order-matcher/${route}" "${b}")"
  [[ "${st}" == 401 && "$(body)" == *admin_auth_required* ]] || fail "signed-out /order-matcher/${route} answered ${st} $(body | head -c 160) (want 401 admin_auth_required)"
done
sleep 3; [[ "$(orders_on)" == 0 ]] || fail "a signed-out console request left an order on ${T}"
ok "all seven: 401 admin_auth_required; nothing on ${T} in the read model"

step "CS-01. two sign-ins, two sessions"
[[ "$(login j1)" == 200 ]] || fail "sign-in 1 refused: $(body | head -c 160)"; nostore "/auth/login"; S1="$(sid)"
[[ "$(login j2)" == 200 ]] || fail "sign-in 2 refused"; S2="$(sid)"
[[ -n "${S1}" && -n "${S2}" ]] || fail "/auth/login returned no session id: $(body | head -c 200)"
[[ "${S1}" != "${S2}" ]] || fail "two sign-ins share one session id"
[[ "$(req j1 GET /auth/me)" == 200 && "$(sid)" == "${S1}" ]] || fail "/auth/me for jar 1 is not session 1"; nostore "signed-in /auth/me"
[[ "$(req j2 GET /auth/me)" == 200 && "$(sid)" == "${S2}" ]] || fail "/auth/me for jar 2 is not session 2"
ok "two distinct session ids, each /auth/me returns its own"

step "CS-05. signed in, the proxy carries the console's JWT"
st="$(req j1 POST /order-matcher/orders "${ORD}")"
[[ "${st}" == 200 && "$(body)" != *'"kind":2'* ]] || fail "signed-in order through the console refused: ${st} $(body | head -c 200)"
REF="$(body | jget 'd.get("orderRef")')"
for i in $(seq 1 30); do [[ "$(orders_on)" == 1 ]] && break; sleep 1; done
[[ "$(orders_on)" == 1 ]] || fail "signed-in order ${REF} never reached the read model"
req j1 POST /order-matcher/cancel "{\"orderRef\":${REF}}" >/dev/null
ok "order ${REF} accepted through the console and present in the read model (cancelled again)"

step "CS-01. signing one session out leaves the other signed in"
st="$(req j1 POST /auth/logout '{}')"; [[ "${st}" == 200 ]] || fail "sign-out answered ${st}"; nostore "/auth/logout"
[[ "$(req j1 GET /auth/me)" == 401 ]] || fail "jar 1 still signed in after its sign-out"
[[ "$(req j2 GET /auth/me)" == 200 && "$(sid)" == "${S2}" ]] || fail "signing jar 1 out signed jar 2 out too"
ok "jar 1 signed out; jar 2 still session ${S2:0:8}…"

step "CS-02/03. the hosted Desk"
if "${K[@]}" get deploy web-front-end-desk >/dev/null 2>&1; then
  echo "[todo] web-front-end-desk is deployed; the desk arm is not written yet (needs DESK_USERS_FILE credentials)"
else
  echo "[skip] no web-front-end-desk Deployment in ${NS}"
fi
rm -rf "${JARS}"
echo; echo "YU18-CONSOLE-SESSIONS PASS ticker=${T}"
