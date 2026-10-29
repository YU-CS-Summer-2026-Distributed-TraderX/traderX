#!/usr/bin/env bash
# fix-tls-smoke.sh — ADR-095 (FR-FTL-05): an external QuickFIX/J client logs on through the FIX TLS entry with
# quickfixj-client.cfg, crosses a Sell and a Buy on two of its accounts, and confirms the order at the effect end:
#   - the Buy's Filled ExecutionReport, over TLS;
#   - the Sell Filled through OrderStatusRequest, which the gateway answers from the order read model;
#   - with CTX and NS (kube access), the two `orderbook` rows FILLED in the database.
#
# Credentials and the pinned trust store come from provision-fix-tls.py's private directory, never from argv.
# TICKER must have a price (a proof seeds a fresh one; on production use a ticker the venue quotes).
#
# Usage: PRIVATE_DIR=<dir> FIX_HOST=fix.yaakovseif.dev TICKER=<sym> PX=<price> [FIX_PORT=18143]
#        [ACCT_A=22214 ACCT_B=42422] [CTX=<kube ctx> NS=<ns>] bash scripts/fix-tls/fix-tls-smoke.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
D="${PRIVATE_DIR:?set PRIVATE_DIR to provision-fix-tls.py --private-dir}/fix-tls"
: "${FIX_HOST:?}" "${TICKER:?}" "${PX:?}"
[[ -f "${D}/client.json" && -f "${D}/truststore.p12" ]] || { echo "[FAIL] no client.json/truststore.p12 in ${D}"; exit 2; }
cj() { sed -n "s/^ *\"$1\": \"\(.*\)\",\{0,1\}$/\1/p" "${D}/client.json"; }   # provision-fix-tls.py's flat JSON
FIX_PASSWORD="$(cj password)"; FIX_TRUSTSTORE_PASSWORD="$(cj truststorePassword)"; SENDER="$(cj sender)"
export FIX_PASSWORD FIX_TRUSTSTORE_PASSWORD TICKER PX
export ACCT_A="${ACCT_A:-22214}" ACCT_B="${ACCT_B:-42422}"

# QuickFIX/J 2.3.1, MINA 2.1.4 and SLF4J: the gateway's own versions, from the Gradle cache its build filled.
if [[ -z "${QFJ_CLASSPATH:-}" ]]; then
  jar() { find "${GRADLE_USER_HOME:-${HOME}/.gradle}/caches/modules-2" -name "$1" 2>/dev/null | head -1; }
  parts=("$(jar quickfixj-core-2.3.1.jar)" "$(jar quickfixj-messages-fix44-2.3.1.jar)" "$(jar mina-core-2.1.4.jar)" "$(jar 'slf4j-api-1.7.*.jar')")
  for p in "${parts[@]}"; do [[ -n "${p}" ]] || { echo "[FAIL] QuickFIX/J jars not in the Gradle cache; set QFJ_CLASSPATH"; exit 2; }; done
  QFJ_CLASSPATH="$(IFS=:; echo "${parts[*]}")"
fi

set +e
OUT="$(java -cp "${QFJ_CLASSPATH}" -Dfix.host="${FIX_HOST}" -Dfix.port="${FIX_PORT:-18143}" -Dfix.sender="${SENDER}" \
  -Dfix.truststore="${D}/truststore.p12" "${here}/FixTlsSmoke.java" "${here}/quickfixj-client.cfg" 2>&1)"; rc=$?
set -e
printf '%s\n' "${OUT}" | grep -v -e '^RESULT' -e 'SLF4J'
[[ "${rc}" == 0 ]] || { echo "[FAIL] the TLS session did not confirm the order (above)"; exit 1; }

if [[ -n "${CTX:-}" && -n "${NS:-}" ]]; then
  ids="$(printf '%s\n' "${OUT}" | sed -En 's/^RESULT (sell|buy)_order_id=ord-//p' | paste -sd, -)"
  [[ "${ids}" =~ ^[0-9]+,[0-9]+$ ]] || { echo "[FAIL] the smoke printed no order refs (${ids})"; exit 1; }
  K=(kubectl --context "${CTX}" -n "${NS}")
  sec() { "${K[@]}" get secret mariadb-credentials -o "go-template={{index .data \"$1\"}}" | base64 -d; }
  for i in $(seq 1 30); do
    rows="$("${K[@]}" exec deploy/eod-price-db -c mariadb -- env MYSQL_PWD="$(sec password)" mariadb -u"$(sec username)" traderx -sN \
      -e "SELECT orderid, accountid, side, status FROM orderbook WHERE security='${TICKER}' AND status='FILLED'
          AND SUBSTRING_INDEX(orderid, '-', -1) IN (${ids})" 2>/dev/null || true)"   # orderid is <epoch>-<ref>
    [[ "$(grep -c . <<<"${rows}")" -ge 2 ]] && break; sleep 1
  done
  printf '    %s\n' "${rows}"
  [[ "$(grep -c . <<<"${rows}")" == 2 ]] || { echo "[FAIL] orderbook holds $(grep -c . <<<"${rows}") of the 2 orders FILLED, want 2"; exit 1; }
  echo "[ok] database: both orders' orderbook rows FILLED"
fi
echo "FIX-TLS-SMOKE PASS ${FIX_HOST}:${FIX_PORT:-18143} as ${SENDER}"
