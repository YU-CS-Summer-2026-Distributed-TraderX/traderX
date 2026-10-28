#!/usr/bin/env bash
# lib-yu18.sh — the preamble the yu18-* proofs share. Written from the YU18 specs, black-box.
#
# CTX AND NS ARE REQUIRED, NO DEFAULTS. On traderx-demo one kube context serves the production
# namespace `traderx` beside every side namespace, and 53 files in this directory and scripts/yu15
# default NS to `traderx`. A proof that guesses its namespace on that context mutates production.
# So these refuse instead (the same choice yu13-otel-trace-join made for its context).
#
# Credentials come from the rig's own Secrets (auth-secrets, mariadb-credentials), never from a
# committed literal: the CompSci profile generates random values, so `-utraderx -ptraderx` and the
# kind dev-token literal are wrong there and fail as an opaque 401 / access denied.
#
#   . "$(dirname "$0")/lib-yu18.sh"      then: pf GW svc/order-matcher 18110; mint true '[]'; sql "..."
#
# Overridable: GATEWAY_TARGET (svc/order-matcher:18110), TP_TARGET (deploy/trade-processor:18091),
# DB_DEPLOY (eod-price-db), DB_CONTAINER (mariadb), MEMBERS (3). BEARER: a JWT the operator minted, used by
# mint() instead of trade-processor's dev-token route, for a rig that runs only the cluster tier.
set -uo pipefail
: "${CTX:?set CTX to the kube context (no default: see lib-yu18.sh)}"
: "${NS:?set NS to the namespace (no default: see lib-yu18.sh)}"
K=(kubectl --context "${CTX}" -n "${NS}")
MEMBERS="${MEMBERS:-3}"
DB_DEPLOY="${DB_DEPLOY:-eod-price-db}"
DB_CONTAINER="${DB_CONTAINER:-mariadb}"

fail() { echo "[FAIL] $*" >&2; exit 1; }
ok()   { echo "[ok] $*"; }
step() { echo; echo "=== $* ==="; }

# --- port-forwards, owned by the proof and torn down on exit ------------------------------------
PF_PIDS=()
_lib_yu18_cleanup() { local p; for p in "${PF_PIDS[@]:-}"; do [[ -n "${p}" ]] && kill "${p}" 2>/dev/null; done; true; }
trap _lib_yu18_cleanup EXIT
# pf <VAR> <target> <remotePort> [tcp] — sets VAR to http://127.0.0.1:<free port> once the forward
# answers (with `tcp`: to the bare port, once it accepts a connection — a FIX port speaks no HTTP).
# A proof that adds its own EXIT trap must call _lib_yu18_cleanup from it.
pf() {
  local var="$1" target="$2" rport="$3" mode="${4:-http}" lp i
  lp="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')"
  # Up to ~2 min, re-dialling: trade-processor has no readiness probe, so `rollout status` returns
  # before Spring listens, and a forward opened against a not-yet-listening pod dies on first use.
  for i in $(seq 1 40); do
    "${K[@]}" port-forward "${target}" "${lp}:${rport}" >/dev/null 2>&1 &
    PF_PIDS+=($!)
    for j in 1 2 3 4 5 6 7 8 9 10; do
      if [[ "${mode}" == tcp ]]; then
        (exec 3<>"/dev/tcp/127.0.0.1/${lp}") 2>/dev/null && { printf -v "${var}" '%s' "${lp}"; return 0; }
      else
        curl -s -o /dev/null -m 2 "http://127.0.0.1:${lp}/" && { printf -v "${var}" 'http://127.0.0.1:%s' "${lp}"; return 0; }
      fi
      kill -0 "${PF_PIDS[${#PF_PIDS[@]}-1]}" 2>/dev/null || break
      sleep 0.3
    done
    kill "${PF_PIDS[${#PF_PIDS[@]}-1]}" 2>/dev/null; sleep 1
  done
  fail "port-forward ${target} ${rport} never answered"
}

# --- credentials ----------------------------------------------------------------------------------
secret() { # secret <name> <key>
  "${K[@]}" get secret "$1" -o "go-template={{index .data \"$2\"}}" 2>/dev/null | base64 -d 2>/dev/null
}
# mint <admin true|false> <accounts JSON array> [subject] — a JWT from trade-processor's dev-token
# route, which signs with the same AUTH_JWT_SECRET the gateway verifies (ADR-025, ADR-082 S1).
mint() {
  [[ -n "${BEARER:-}" ]] && { printf '%s\n' "${BEARER}"; return 0; }
  [[ -n "${TP:-}" ]] || pf TP "${TP_TARGET:-deploy/trade-processor}" 18091
  local master r
  master="${AUTH_MASTER_SECRET:-$(secret auth-secrets dev-token-master-secret)}"
  [[ -n "${master}" ]] || fail "auth-secrets has no dev-token-master-secret"
  r="$(curl -s -m 10 -X POST "${TP}/auth/dev-token" -H "X-Auth-Master-Secret: ${master}" \
       -H 'Content-Type: application/json' \
       -d "{\"subject\":\"${3:-${0##*/}}\",\"accounts\":$2,\"admin\":$1,\"ttlSeconds\":1800}")"
  printf '%s' "${r}" | python3 -c 'import sys,json
s=sys.stdin.read().strip()
try:
    d=json.loads(s); print(d.get("token") or d.get("accessToken") or d.get("jwt") or "")
except Exception:
    print(s.strip("\"") if s.count(".")==2 else "")'
}
operator_token() { secret auth-secrets risk-control-token; }

# --- effect-end readers ---------------------------------------------------------------------------
sql() { # sql "<statement>" -> tab-separated rows, no header
  local u p
  u="$(secret mariadb-credentials username)"; p="$(secret mariadb-credentials password)"
  "${K[@]}" exec "deploy/${DB_DEPLOY}" -c "${DB_CONTAINER}" -- mariadb -u"${u:-traderx}" -p"${p:-traderx}" traderx -sN -e "$1" 2>/dev/null
}
# mget <member> <path> [bearer] — GET on a member's own HTTP port, from inside the member.
mget() {
  local hdr=()
  [[ -n "${3:-}" ]] && hdr=(--header "Authorization: Bearer $3")
  "${K[@]}" exec -c "${MEMBER_CONTAINER:-cluster-node}" "order-matcher-cluster-$1" -- wget -qO- ${hdr[@]+"${hdr[@]}"} "http://localhost:8080$2" 2>&1
}
# mstatus <member> <path> [bearer] — the HTTP status a member answers (GNU wget -S, headers on stderr).
mstatus() {
  local hdr=()
  [[ -n "${3:-}" ]] && hdr=(--header "Authorization: Bearer $3")
  "${K[@]}" exec -c "${MEMBER_CONTAINER:-cluster-node}" "order-matcher-cluster-$1" -- wget -S -O /dev/null ${hdr[@]+"${hdr[@]}"} "http://localhost:8080$2" 2>&1 \
    | awk '/^  HTTP\// {c=$2} END {print c}'
}
member_applied() { mget "$1" /health | jget 'd.get("applied")'; }
# jget '<python expr over d>' — reads JSON on stdin; prints '' on a parse failure.
jget() { python3 -c "import sys,json
try:
    d=json.load(sys.stdin); v=($1); print('' if v is None else (json.dumps(v) if isinstance(v,(dict,list)) else v))
except Exception:
    print('')"; }
# code <curl args...> — HTTP status only; body to $BODY_FILE.
BODY_FILE="$(mktemp)"
code() { curl -s -m 30 -o "${BODY_FILE}" -w '%{http_code}' "$@"; }
body() { cat "${BODY_FILE}"; }
# mint_ticker — a ticker no other run has used: PA + 6 digits, a prefix
# scripts/yu15/seed-proof-fixtures.sh sweeps from positions like every other proof's throwaways.
mint_ticker() { python3 -c 'import random;print("PA%06d" % random.randrange(10**6))'; }
