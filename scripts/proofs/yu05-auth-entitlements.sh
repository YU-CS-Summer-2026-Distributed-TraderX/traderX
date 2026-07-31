#!/usr/bin/env bash
# YU05 — PROOF: real JWT auth + entitlements (FR-PTC40/41), replacing YU02-YU04's shared-token
# stopgap. Two axes, both enforced, both asserted:
#   • cross-account endpoints need an `admin` claim
#   • account-scoped endpoints check the caller against the TRADE's own account
#
# Prereq: trade-processor reachable (port-forward 18091) — see yu05-common.sh.
# Usage: bash yu05-auth-entitlements.sh
#
# PORTED TO THE CLUSTER TIER. Two things had to change, and neither was the capability:
#
#   1. The cross-account axis used to drive $OM/regulatory/report on the SPRING order-matcher, which
#      this tier does not run. But that axis is about the ADMIN CLAIM, not about that URL, and
#      trade-processor serves admin-gated cross-account endpoints of its own. GET /eod/prices/{date}
#      is used here: 200 to an admin token, 403 to a token without the admin claim, 401 to no token
#      — three distinct outcomes, which is a sharper test than the original. That expected 401 for
#      the scoped case, conflating "not authenticated" with "authenticated but not authorised".
#
#   2. The trade ids were hardcoded to trd-09b-2 / trd-09b-4, the deterministic journal-replay ids
#      of the state-014 rig. They do not exist here, so every TCA call 404'd. They are now
#      DISCOVERED from the read model, which works on any rig — and a 404 can no longer be mistaken
#      for an entitlement decision.
#
# AND IT NOW ASSERTS. The original printed each status code beside an "(expect NNN)" annotation and
# exited 0 regardless, so a run where every call returned 404 was reported as a pass. An expectation
# that is only ever printed is a comment, not a check.
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/yu05-common.sh"

FAILED=0
check() { # check <label> <actual> <expected> <why>
  if [ "$2" = "$3" ]; then
    printf "   %-38s %s  ✔ %s\n" "$1" "$2" "$4"
  else
    printf "   %-38s %s  ✘ expected %s · %s\n" "$1" "$2" "$3" "$4"
    FAILED=1
  fi
}

# Trade ids for two DIFFERENT accounts, read from the projection so this runs on any rig. Both the
# id and its owning account come from the same row, so the own/foreign relationship is a fact about
# the data rather than an assumption about a fixture.
OWN_ACCT="${OWN_ACCT:-22214}"
OWN=${OWN_TRADE:-$(dbq "SELECT id FROM trades WHERE accountid=${OWN_ACCT} LIMIT 1;" | tr -d '\r')}
FOREIGN_ACCT="${FOREIGN_ACCT:-$(dbq "SELECT accountid FROM trades WHERE accountid<>${OWN_ACCT} LIMIT 1;" | tr -d '\r')}"
FOREIGN=${FOREIGN_TRADE:-$(dbq "SELECT id FROM trades WHERE accountid=${FOREIGN_ACCT} LIMIT 1;" | tr -d '\r')}

if [ -z "$OWN" ] || [ -z "$FOREIGN" ]; then
  echo "   ✘ no trades in the projection for two distinct accounts — nothing to entitle against."
  echo "   Seed and cross some trades first:  bash scripts/yu15/seed-proof-fixtures.sh"
  exit 1
fi
echo "   own trade ${OWN} (account ${OWN_ACCT}) · foreign trade ${FOREIGN} (account ${FOREIGN_ACCT})"

ADMIN=$(mint true  '[]')
SCOPED=$(mint false "[${OWN_ACCT}]")
[ -n "$ADMIN" ] && [ -n "$SCOPED" ] || { echo "   ✘ could not mint tokens from $TP/auth/dev-token"; exit 1; }
printf "   %-38s %s\n" "mint admin + account-scoped tokens" "ok"

echo "── cross-account endpoint: admin claim required ──"
XACCT="$TP/eod/prices/$(date +%F)"
check "admin token"          "$(hs GET "$XACCT" "$ADMIN")"  200 "admin sees cross-account data"
check "account-scoped token" "$(hs GET "$XACCT" "$SCOPED")" 403 "authenticated, but no admin claim"
check "no token"             "$(hs GET "$XACCT" "")"        401 "no bearer at all"

echo "── account-scoped endpoint: /tca/report/{tradeId} ──"
check "scoped → own-account trade" "$(hs GET "$TP/tca/report/$OWN" "$SCOPED")"     200 "owns ${OWN_ACCT}"
check "scoped → foreign trade"     "$(hs GET "$TP/tca/report/$FOREIGN" "$SCOPED")" 403 "not its account"
check "admin  → foreign trade"     "$(hs GET "$TP/tca/report/$FOREIGN" "$ADMIN")"  200 "admin sees all"

echo
if [ "$FAILED" = "0" ]; then
  echo "[PASS] JWT auth and entitlements are enforced on both axes: the admin claim gates"
  echo "       cross-account data, and account scope is checked against the trade's own account."
else
  echo "[FAIL] at least one authorization decision was wrong — see the ✘ lines above"
fi
exit "$FAILED"
