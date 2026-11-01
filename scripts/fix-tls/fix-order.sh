#!/usr/bin/env bash
# fix-order.sh — FR-FTL-08: send ONE order from this machine through the external FIX-over-TLS entry (ADR-095) and
# print its ExecutionReports. The session (EXTQFJ) may trade any account (FR-FTL-07), so an account created on the Desk
# a minute ago works; the order then shows on that account's Desk pages.
#
#   scripts/fix-tls/fix-order.sh ACCOUNT Buy|Sell QTY TICKER [PX]      PX: Limit DAY at PX; none: Market IOC
#   e.g. scripts/fix-tls/fix-order.sh 65000 Buy 10 MSFT 390.50
#
# PRIVATE_DIR (default ~/traderx-private) holds provision-fix-tls.py's client.json and pinned trust store; FIX_HOST
# (default fix.yaakovseif.dev) and FIX_PORT (default 18143). Your public IP must be on the entry's /32 allowlist.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
[[ $# -ge 4 ]] || { sed -n '5,6p' "$0"; exit 64; }
export ACCOUNT=$1 SIDE=$2 QTY=$3 TICKER=$4 PX=${5:-}
[[ $ACCOUNT =~ ^[0-9]+$ && $SIDE =~ ^(Buy|Sell)$ && $QTY =~ ^[1-9][0-9]*$ && ( -z $PX || $PX =~ ^[0-9]+(\.[0-9]+)?$ ) ]] \
  || { echo "usage: $0 ACCOUNT Buy|Sell QTY TICKER [PX]"; exit 64; }
D="${PRIVATE_DIR:-${HOME}/traderx-private}/fix-tls"
[[ -f "${D}/client.json" && -f "${D}/truststore.p12" ]] || { echo "[FAIL] no client.json/truststore.p12 in ${D}"; exit 2; }
cj() { sed -n "s/^ *\"$1\": \"\(.*\)\",\{0,1\}$/\1/p" "${D}/client.json"; }
FIX_PASSWORD="$(cj password)"; FIX_TRUSTSTORE_PASSWORD="$(cj truststorePassword)"; SENDER="$(cj sender)"
export FIX_PASSWORD FIX_TRUSTSTORE_PASSWORD
if [[ -z "${QFJ_CLASSPATH:-}" ]]; then
  jar() { find "${GRADLE_USER_HOME:-${HOME}/.gradle}/caches/modules-2" -name "$1" 2>/dev/null | head -1; }
  parts=("$(jar quickfixj-core-2.3.1.jar)" "$(jar quickfixj-messages-fix44-2.3.1.jar)" "$(jar mina-core-2.1.4.jar)" "$(jar 'slf4j-api-1.7.*.jar')")
  for p in "${parts[@]}"; do [[ -n "${p}" ]] || { echo "[FAIL] QuickFIX/J jars not in the Gradle cache; set QFJ_CLASSPATH"; exit 2; }; done
  QFJ_CLASSPATH="$(IFS=:; echo "${parts[*]}")"
fi
java -cp "${QFJ_CLASSPATH}" -Dfix.host="${FIX_HOST:-fix.yaakovseif.dev}" -Dfix.port="${FIX_PORT:-18143}" -Dfix.sender="${SENDER}" \
  -Dfix.truststore="${D}/truststore.p12" "${here}/FixOrder.java" "${here}/quickfixj-client.cfg" 2>&1 | grep -v SLF4J
exit "${PIPESTATUS[0]}"
