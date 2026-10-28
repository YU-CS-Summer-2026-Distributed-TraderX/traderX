#!/usr/bin/env bash
# yu18-desk.sh — the hosted operator Desk (YU18 trader-workspace-combined, FR-CUI) driven in headless
# Chrome on a live side namespace, with every claim the browser cannot settle read back at the effect end.
#
#   browser (yu18-desk.mjs): brand + public-selector login, wrong password refused, a new workspace has no
#     accounts, no password/hash/salt in any bundle (CUI-01, -11); five tabs, More with Demo console last,
#     separate admin workspace (CUI-02, -09); Risk unavailable with labelled synthetic references (CUI-05);
#     Markets sort with aria-sort, numeric order, missing last (CUI-14); + Add account (CUI-12); typed orders
#     iff /capabilities advertises all seven (CUI-15); Price at market fills from a fresh mark only (CUI-13);
#     instrument change clears the ticket (CUI-04); refused keeps the draft, accepted clears it (CUI-16);
#     no cancel/replace on an unmanaged run (CUI-17); foreign account reads recorded (CUI-21's membership
#     rule is for non-admin local sessions; every hosted session is admin); 390 px
#     (acceptance); DESTRUCTIVE=1: a hung read clears rows (CUI-20).
#   effect end (here): the new account is a SQL row AND admitted at the gateway (the Desk's order on it
#     rests in SQL; a never-created account is refused there — negative control); exactly one order for it
#     on the ticker, at the accepted price, none at the refused price; signed out, the Desk's proxy refuses
#     a raw order (CUI-21); a WebSocket reset leaves the Desk server serving, not restarted (CUI-10); the
#     console still serves beside it (CUI-06).
#
# Usage: CTX=<ctx> NS=<ns> CONSOLE_PASSWORD=<operator pw> [FRESH_TICKER=AAPL STALE_TICKER=A DESTRUCTIVE=1] \
#          bash scripts/proofs/yu18-desk.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
: "${CONSOLE_PASSWORD:?set CONSOLE_PASSWORD (the operator password; it is never printed)}"
"${K[@]}" get deploy web-front-end-desk >/dev/null 2>&1 || { echo "[SKIP] no web-front-end-desk Deployment in ${NS}"; exit 2; }
OUT="${OUT:-$(mktemp -d)/desk}"; mkdir -p "${OUT}"
USER_A="pa$(date +%s)"
pf DESK "${DESK_TARGET:-svc/desk-https}" 8080
pf CON "${CONSOLE_TARGET:-svc/web-front-end-console}" 8080
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
pf TP "${TP_TARGET:-deploy/trade-processor}" 18091   # here, not inside mint: $(mint) runs in a subshell
ADMIN="$(mint true '[]')"; [[ -n "${ADMIN}" ]] || fail "could not mint an admin bearer"
HASH="$(secret auth-secrets console-admin-hash)"; IFS='$' read -r _ SALT DIG <<<"${HASH}"
CAPS="$(curl -s -m 10 "${GW}/capabilities" -H "Authorization: Bearer ${ADMIN}")"
RESTARTS0="$("${K[@]}" get pods -l app=web-front-end-desk -o jsonpath='{.items[0].status.containerStatuses[0].restartCount}')"
u="$(secret mariadb-credentials username)"; p="$(secret mariadb-credentials password)"
HOLD="kubectl --context ${CTX} -n ${NS} exec -i deploy/${DB_DEPLOY} -c ${DB_CONTAINER} -- mariadb -u${u:-traderx} -p${p:-traderx} traderx <<<'LOCK TABLES positions WRITE, trades WRITE, orderbook WRITE, accounts WRITE; SELECT SLEEP(40); UNLOCK TABLES;'"

step "browser"
FACTS="${OUT}/facts.txt"
DESK_URL="${DESK}" PASSWORD="${CONSOLE_PASSWORD}" PW_HASH="${DIG}" PW_SALT="${SALT}" USER_A="${USER_A}" OUT="${OUT}" \
  GW_CAPS="${CAPS}" FRESH_TICKER="${FRESH_TICKER:-AAPL}" STALE_TICKER="${STALE_TICKER:-A}" FOREIGN_ACCT="${FOREIGN_ACCT:-22214}" \
  DESTRUCTIVE="${DESTRUCTIVE:-0}" HOLD_CMD="${HOLD}" GW="${GW}" TP="${TP}" MASTER="${AUTH_MASTER_SECRET:-$(secret auth-secrets dev-token-master-secret)}" node "${here}/yu18-desk.mjs" | tee "${FACTS}"
BROWSER_RC=${PIPESTATUS[0]}
fact() { sed -n "s/^\[fact\] $1=//p" "${FACTS}" | tail -1; }
A="$(fact account)"; TK="${FRESH_TICKER:-AAPL}"; PXA="$(fact probe-px)"; SUBMIT="$(fact submit)"

step "effect end"
if [[ -n "${A}" ]]; then
  [[ -n "$(sql "SELECT id FROM accounts WHERE id=${A}")" ]] || fail "FR-CUI-12: account ${A} has no SQL row"
  ok "FR-CUI-12: account ${A} is a SQL row"
  [[ -n "${PXA}" ]] || fail "no probe price from the browser half (it stopped before the ticket)"
  n="$(sql "SELECT COUNT(*) FROM orderbook WHERE accountid=${A}")"
  case "${SUBMIT}" in
    refused-unmanaged) [[ "${n}" == 1 ]] || fail "FR-CUI-17: the Desk said nothing was sent, but SQL holds ${n} order(s) for ${A} (want only the REST one)" ;;
    sent) [[ "${n}" == 2 ]] || fail "FR-CUI-16: want the REST order and the Desk's for ${A}, SQL has ${n}" ;;
  esac
  REF="$(fact rest-ref)"; AB="$(mint false "[${A}]")"
  rows="$(sql "SELECT quantity, limitprice FROM orderbook WHERE accountid=${A}")"
  [[ "${SUBMIT}" == sent ]] || [[ "$(wc -l <<<"${rows}" | tr -d ' ')" == 1 && "${rows}" == 1$'\t'"${PXA}"* ]] || fail "FR-CUI-12/17: want only the REST Buy 1 @ ${PXA} for ${A} (the Desk's refused Buy 2 sent nothing); SQL: ${rows:-none}"
  ok "FR-CUI-12/17: ${A} holds exactly the REST order (qty 1 @ ${PXA}, ref ${REF}); the Desk's refused submit left nothing"
  NONE=$((A + 7919)); [[ -z "$(sql "SELECT id FROM accounts WHERE id=${NONE}")" ]] || NONE=$((A + 104729))
  NB="$(mint false "[${NONE}]")"
  c="$(code -X POST "${GW}/orders" -H "Authorization: Bearer ${NB}" -H 'Content-Type: application/json' -d "{\"accountId\":${NONE},\"ticker\":\"${TK}\",\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":${PXA}}")"
  [[ "$(body)" != *'"kind":1'* && "$(body)" == *ACCOUNT* ]] || fail "negative control: never-created account ${NONE} at ${PXA} answered ${c} $(body | head -c 160) (want an account refusal)"
  ok "negative control: never-created account ${NONE} refused ($(body | head -c 100))"
  [[ -n "${REF}" ]] && curl -s -m 10 -o /dev/null -X POST "${GW}/cancel" -H "Authorization: Bearer ${AB}" -H 'Content-Type: application/json' -d "{\"accountId\":${A},\"orderRef\":${REF}}"
fi
st="$(curl -s -m 10 -o "${BODY_FILE}" -w '%{http_code}' -X POST "${DESK}/order-matcher/orders" -H 'Content-Type: application/json' -d "{\"accountId\":22214,\"ticker\":\"${TK}\",\"side\":\"Buy\",\"quantity\":1,\"limitPrice\":1}")"
[[ "${st}" == 401 || "${st}" == 403 ]] || fail "FR-CUI-21: a signed-out raw order through the Desk proxy answered ${st} $(body | head -c 160)"
ok "FR-CUI-21: signed out, the Desk proxy refuses a raw order (${st})"
WS="$(fact ws)"
if [[ -n "${WS}" ]]; then
  path="/${WS#*://*/}"; port="${DESK##*:}"
  python3 - "${port}" "${path}" <<'PY' || fail "FR-CUI-10: could not open the Desk WebSocket for the reset"
import socket, struct, sys, base64, os
s = socket.create_connection(('127.0.0.1', int(sys.argv[1])), timeout=10)
k = base64.b64encode(os.urandom(16)).decode()
s.sendall(f"GET {sys.argv[2]} HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {k}\r\nSec-WebSocket-Version: 13\r\n\r\n".encode())
r = s.recv(4096); assert b' 101 ' in r.split(b'\r\n')[0], r[:120]
s.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0)); s.close()   # RST, not a close frame
PY
  sleep 3
  [[ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' "${DESK}/healthz")" == 200 ]] || fail "FR-CUI-10: the Desk stopped serving after a WebSocket reset"
  r1="$("${K[@]}" get pods -l app=web-front-end-desk -o jsonpath='{.items[0].status.containerStatuses[0].restartCount}')"
  [[ "${r1}" == "${RESTARTS0}" ]] || fail "FR-CUI-10: the Desk container restarted (${RESTARTS0} -> ${r1})"
  ok "FR-CUI-10: after a WebSocket reset (RST on ${path}) /healthz 200 and restarts unchanged (${r1})"
fi
[[ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' "${CON}/")" == 200 ]] || fail "FR-CUI-06: the console stopped serving"
ok "FR-CUI-06: the console still serves beside the Desk"
(( BROWSER_RC == 0 )) || fail "browser checks failed (evidence ${OUT})"
echo "[PASS] desk: browser checks + effect end (evidence ${OUT})"
