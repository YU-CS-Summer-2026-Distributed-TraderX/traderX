#!/usr/bin/env bash
# yu18-fix-tls-entry.sh — ADR-095 (FR-FTL-01..05) on a kind rig: the external FIX TLS entry, from outside the cluster.
# The clients are containers on the kind docker network, so each has a source address of its own:
#
#   - ALLOWED_IP (in fix-tls-external's loadBalancerSourceRanges) runs scripts/fix-tls/fix-tls-smoke.sh, the Mac
#     client, against the entry: TLS logon as EXTQFJ with its password, a crossing order, the fill and the read
#     model's status over FIX. This script then reads the effect end: every member booked the cross (two trade sides), the
#     database's orderbook holds both orders FILLED, positions +1/-1.
#   - the same source speaks plaintext FIX to the entry: no FIX answer.
#   - DENIED_IP (not in the allowlist) cannot connect to the entry; ALLOWED_IP's identical connect is the control.
#
# kind has no load-balancer controller: when fix-tls-external has no address, this sets its status to the host IP of
# a gateway pod, and kube-proxy enforces loadBalancerSourceRanges for that address exactly as it does for a cloud
# LB's (on GKE the same field is a VPC firewall rule). externalTrafficPolicy Local keeps the container's address.
#
# Usage: CTX=<kind ctx> NS=<ns> PRIVATE_DIR=<provision-fix-tls.py --private-dir> [ALLOWED_IP=172.18.0.200
#        DENIED_IP=172.18.0.201] [ACCT_A=22214 ACCT_B=42422] bash scripts/proofs/yu18-fix-tls-entry.sh
here="$(cd "$(dirname "$0")" && pwd)"
. "${here}/lib-yu18.sh"
: "${PRIVATE_DIR:?set PRIVATE_DIR (provision-fix-tls.py --private-dir)}"
[[ "${CTX}" == kind-* ]] || fail "kind only: on GKE the clients are real hosts (see the runbook)"
ALLOWED_IP="${ALLOWED_IP:-172.18.0.200}"; DENIED_IP="${DENIED_IP:-172.18.0.201}"
ACCT_A="${ACCT_A:-22214}"; ACCT_B="${ACCT_B:-42422}"
T="$(mint_ticker)"; PX=50; PORT=18143
trades_all() { local m s=""; for ((m=0; m<MEMBERS; m++)); do s+="$(mget "${m}" /health | jget 'd.get("trades")') "; done; echo "${s% }"; }
pos() { sql "SELECT COALESCE(SUM(quantity),0) FROM positions WHERE security='${T}' AND accountid=$1"; }
jar() { find "${GRADLE_USER_HOME:-${HOME}/.gradle}/caches/modules-2" -name "$1" 2>/dev/null | head -1; }
JARS=(); QCP=""; for j in quickfixj-core-2.3.1.jar quickfixj-messages-fix44-2.3.1.jar mina-core-2.1.4.jar 'slf4j-api-1.7.*.jar'; do
  p="$(jar "${j}")"; [[ -n "${p}" ]] || fail "${j} not in the Gradle cache"
  JARS+=(-v "${p}:/qfj/$(basename "${p}"):ro"); QCP+="/qfj/$(basename "${p}"):"; done
# client <ip> <cmd...> — a container on the kind network at <ip>, with the client files mounted read-only.
client() { local ip="$1"; shift
  docker run --rm --network kind --ip "${ip}" -v "$(cd "${here}/../fix-tls" && pwd):/fix-tls:ro" -v "${PRIVATE_DIR}:/private:ro" \
    "${JARS[@]}" -e QFJ_CLASSPATH="${QCP%:}" "$@"; }
IMG="${CLIENT_IMAGE:-eclipse-temurin:21-jdk}"

step "0. fix-tls-external: its own passthrough L4 Service, the TLS port only, /32 allowlist"
s="$("${K[@]}" get svc fix-tls-external -o json)" || fail "no Service fix-tls-external (render-fix-tls-access.py)"
got="$(jget '[d["spec"]["type"], d["spec"].get("externalTrafficPolicy"), d["spec"].get("loadBalancerSourceRanges"), [p["port"] for p in d["spec"]["ports"]]]' <<<"${s}")"
[[ "${got}" == "[\"LoadBalancer\", \"Local\", [\"${ALLOWED_IP}/32\"], [${PORT}]]" ]] || fail "fix-tls-external is ${got}"
ENTRY="$(jget '(d["status"].get("loadBalancer",{}).get("ingress") or [{}])[0].get("ip","")' <<<"${s}")"
if [[ -z "${ENTRY}" ]]; then
  ENTRY="$("${K[@]}" get pods -l app=cluster-gateway -o jsonpath='{.items[0].status.hostIP}')"
  "${K[@]}" patch svc fix-tls-external --subresource=status --type=merge \
    -p "{\"status\":{\"loadBalancer\":{\"ingress\":[{\"ip\":\"${ENTRY}\"}]}}}" >/dev/null || fail "could not set the entry address"
  sleep 3   # kube-proxy programs the address
fi
ok "${got}; entry ${ENTRY}:${PORT}"

step "1. seed ${T} @${PX}"
pf GW "${GATEWAY_TARGET:-svc/order-matcher}" "${GATEWAY_PORT:-18110}"
for acct in "${ACCT_A}" "${ACCT_B}"; do   # /seed also enables the account in the engine's risk gate
  c="$(code -X POST "${GW}/seed" -H 'Content-Type: application/json' -H "X-Risk-Control-Token: $(operator_token)" -H 'X-Risk-Operator: yu18-fix-tls-entry' \
    -d "{\"accountId\":${acct},\"tickers\":\"${T}\",\"price\":${PX}}")"
  [[ "${c}" == 200 ]] || fail "operator /seed ${T} for ${acct}: ${c} $(body)"
done
T0="$(trades_all)"; ok "members' trades ${T0}"

step "2. allowlisted source ${ALLOWED_IP}: the Mac client over TLS (FR-FTL-01/03/05)"
client "${ALLOWED_IP}" -e PRIVATE_DIR=/private -e FIX_HOST="${ENTRY}" -e FIX_PORT="${PORT}" -e TICKER="${T}" -e PX="${PX}" \
  -e ACCT_A="${ACCT_A}" -e ACCT_B="${ACCT_B}" "${IMG}" bash /fix-tls/fix-tls-smoke.sh | tee "${BODY_FILE}.smoke"
[[ "${PIPESTATUS[0]}" == 0 ]] || fail "the smoke failed through the entry (above)"

step "3. effect end: members and the database agree with the session"
for i in $(seq 1 30); do T1="$(trades_all)"; [[ "${T1}" != "${T0}" ]] && break; sleep 1; done
read -r -a a <<<"${T0}"; read -r -a b <<<"${T1}"
for ((m=0; m<MEMBERS; m++)); do [[ $(( b[m] - a[m] )) == 2 ]] || fail "member ${m} trades ${a[m]} -> ${b[m]}, want +2 (one cross, two sides)"; done
for i in $(seq 1 30); do [[ "$(pos "${ACCT_A}")" == 1 ]] && break; sleep 1; done
rows="$(sql "SELECT orderid, accountid, side, status FROM orderbook WHERE security='${T}'")"
printf '    %s\n' "${rows}"
[[ "$(grep -c 'FILLED' <<<"${rows}")" == 2 && "$(grep -c . <<<"${rows}")" == 2 ]] || fail "orderbook on ${T}: want exactly 2 rows, both FILLED"
[[ "$(pos "${ACCT_A}")" == 1 && "$(pos "${ACCT_B}")" == -1 ]] || fail "positions ${ACCT_A}=$(pos "${ACCT_A}") ${ACCT_B}=$(pos "${ACCT_B}"), want +1/-1"
ok "members ${T0} -> ${T1}; orderbook 2 FILLED; positions +1/-1"

# probe <ip> <mode> — mode connect: TCP connect within 5 s or not; plain: a plaintext FIX Logon, then hex of the answer.
# A dropped SYN hangs a connect for minutes, so a connect gets 5 s.
probe() { client "$1" "${IMG}" bash -c '
  if [[ "$2" == connect ]]; then timeout 5 bash -c "exec 3<>/dev/tcp/$0/$1" 2>/dev/null && echo CONNECTED || echo CONNECT-FAILED; exit 0; fi
  exec 3<>"/dev/tcp/$0/$1" 2>/dev/null || { echo CONNECT-FAILED; exit 0; }
  printf "8=FIX.4.4\x019=60\x0135=A\x0134=1\x0149=EXTQFJ\x0156=TRADERX\x0152=20261029-12:00:00.000\x0198=0\x01108=30\x0110=000\x01" >&3
  timeout 5 head -c 64 <&3 | od -An -tx1 | tr -d " \n"; echo' "${ENTRY}" "${PORT}" "$2"; }

step "4. plaintext FIX to the TLS entry gets no FIX answer (FR-FTL-01)"
ans="$(probe "${ALLOWED_IP}" plain)"
[[ "${ans}" != CONNECT-FAILED ]] || fail "the allowlisted source could not connect at all"
[[ "${ans}" != *383d4649582e* ]] || fail "the TLS port answered plaintext in FIX: ${ans}"   # "8=FIX."
ok "answer bytes: '${ans:-none}' (no 8=FIX; a 15 03 0x prefix is a TLS alert)"

step "5. a source outside the allowlist cannot reach the entry (FR-FTL-04)"
ctl="$(probe "${ALLOWED_IP}" connect)"; den="$(probe "${DENIED_IP}" connect)"
[[ "${ctl}" == CONNECTED ]] || fail "control: the allowlisted source's connect ${ctl}"
[[ "${den}" == CONNECT-FAILED ]] || fail "${DENIED_IP} is outside the allowlist and connected"
ok "${ALLOWED_IP} ${ctl}; ${DENIED_IP} ${den}"

echo; echo "YU18-FIX-TLS-ENTRY PASS ticker=${T} entry=${ENTRY}:${PORT}"
