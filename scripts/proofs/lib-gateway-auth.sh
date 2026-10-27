#!/usr/bin/env bash
# lib-gateway-auth.sh — one admin bearer for a proof's gateway calls.
#
# ENGINE-REVIEW S1 put a bearer JWT (bound to the account) on /orders, /cancel and /replace; S2 put the
# operator pair (X-Risk-Control-Token + X-Risk-Operator) OR an admin JWT on /seed, /session, the
# business-day roll and /risk/control. Every proof that called those routes bare then failed with 401
# "bearer JWT required" / "operator credentials required" — a statement about the credential, not about
# the property the proof asserts. One admin JWT satisfies both families (JwtPrincipal.allows() is
# `admin || entitled`), so this mints one, the way seed-proof-fixtures does: trade-processor's dev-token
# route shares the gateway's AUTH_JWT_SECRET, and EOD_MASTER_SECRET is the kind rig's committed literal.
#
# Usage, after CTX/NS are set:   source "$(dirname "${BASH_SOURCE[0]}")/lib-gateway-auth.sh"
# then                           curl "${AUTH[@]}" -X POST "${MATCHER_URL}/orders" ...
# PROOF_BEARER in the environment is reused as is (a runner can mint once for a whole suite).
if [[ -z "${PROOF_BEARER:-}" ]]; then
  PROOF_BEARER="$(kubectl --context "${CTX:-kind-traderx-yu12-cluster}" -n "${NS:-traderx}" exec deploy/trade-processor -- \
    sh -c 'curl -fsS -X POST http://localhost:18091/auth/dev-token \
      -H "X-Auth-Master-Secret: '"${EOD_MASTER_SECRET:-kind-local-dev-token-secret-not-a-real-credential}"'" \
      -H "Content-Type: application/json" \
      -d "{\"subject\":\"'"${0##*/}"'\",\"accounts\":[],\"admin\":true,\"ttlSeconds\":3600}"' 2>/dev/null)"
fi
if [[ -z "${PROOF_BEARER}" ]]; then
  echo "[FAIL] could not mint an admin bearer from trade-processor (is it up? EOD_MASTER_SECRET right?)" >&2
  exit 1
fi
export PROOF_BEARER
AUTH=(-H "Authorization: Bearer ${PROOF_BEARER}")
