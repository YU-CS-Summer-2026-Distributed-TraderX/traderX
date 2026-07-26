#!/usr/bin/env bash
# YU05 — PROOF: real JWT auth + entitlements (FR-PTC40/41), replacing YU02-YU04's shared-token
# stopgap. Two axes, both enforced:
#   • cross-account endpoints (regulatory report) need an `admin` claim
#   • account-scoped endpoints (TCA) check the caller against the TRADE's own account
#
# Prereq: source terminals in yu05-common.sh (trade-processor port-forward + edge-proxy).
# Trade ids below are the deterministic journal-replay ids (stable across bring-ups); override if needed:
#   OWN_TRADE (account 22214), FOREIGN_TRADE (a different account).
# Usage: bash yu05-auth-entitlements.sh
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/yu05-common.sh"
OWN=${OWN_TRADE:-trd-09b-2}       # account 22214
FOREIGN=${FOREIGN_TRADE:-trd-09b-4} # account 62654

ADMIN=$(mint true  '[]')
SCOPED=$(mint false '[22214]')
printf "   %-40s %s\n" "mint admin token"          "$([ -n "$ADMIN" ]  && echo ok || echo FAILED)"
printf "   %-40s %s\n" "mint account-scoped token (22214)" "$([ -n "$SCOPED" ] && echo ok || echo FAILED)"

echo "── cross-account endpoint: /regulatory/report (admin-only) ──"
REG="$OM/regulatory/report?fromSeq=0&toSeq=1000000"
printf "   %-40s %s  (expect 200)\n" "admin token"                 "$(hs GET "$REG" "$ADMIN")"
printf "   %-40s %s  (expect 401 · no admin claim)\n" "account-scoped token" "$(hs GET "$REG" "$SCOPED")"
printf "   %-40s %s  (expect 401 · no bearer)\n" "no token"        "$(hs GET "$REG" "")"

echo "── account-scoped endpoint: /tca/report/{tradeId} ──"
printf "   %-40s %s  (expect 200 · owns 22214)\n" "scoped → own-account trade $OWN"     "$(hs GET "$TP/tca/report/$OWN" "$SCOPED")"
printf "   %-40s %s  (expect 403 · not its account)\n" "scoped → foreign trade $FOREIGN" "$(hs GET "$TP/tca/report/$FOREIGN" "$SCOPED")"
printf "   %-40s %s  (expect 200 · admin sees all)\n" "admin  → foreign trade $FOREIGN"  "$(hs GET "$TP/tca/report/$FOREIGN" "$ADMIN")"
