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

VERBOSE=0
while [ $# -gt 0 ]; do
  case "$1" in -v|--verbose) VERBOSE=1; shift ;; *) break ;; esac
done
# STDERR: every value this script checks is captured with $(...), so a verbose line on stdout would
# be parsed as an HTTP status code.
vlog(){ [ "$VERBOSE" = 1 ] && printf '%s\n' "$@" >&2 || true; }
vlog "   endpoints: TP=${TP}  ctx=${CTX}  db=deploy/${DB_DEPLOY}"

# hsv <METHOD> <url> <token> <who>  -> status code on stdout, the call on stderr under -v
hsv(){ vlog "      ${1} ${2}   as: ${4}"; hs "$1" "$2" "$3"; }

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
# Pick a session date that actually HAS a published version, rather than assuming today does.
#
# This axis tests the ADMIN CLAIM; whether an EOD session exists is a different proof's business.
# Coupling the two made this fail at 20:00 EDT — that is midnight UTC, a brand-new session date
# with no data, so the admin got a truthful 404 and the proof called an authorization decision
# wrong. (The tell: 403 and 401 were still correct, i.e. the gate was working perfectly.) An
# earlier local-vs-UTC fix moved the failure window without removing it; discovery removes it.
published_date(){ dbq "SELECT session_date FROM eod_price_session WHERE status='PUBLISHED' ORDER BY session_date DESC, version DESC LIMIT 1;" | tr -d '\r'; }
SESSION_DATE="${SESSION_DATE:-$(published_date)}"

# SELF-PROVISIONING. This axis is about the ADMIN CLAIM; it needs *a* published session only as
# something to be admin ABOUT. Requiring the operator to have run yu06-quality-gate first made this
# proof's result depend on run order — and the suite's own PROOFS array runs this FIFTH and the
# quality gate EIGHTH, so on a genuinely fresh rig the suite would hit exactly this. It passed for
# months only because the rig always happened to carry a session from an earlier run.
#
# So close and publish one. That is two ordinary admin calls, not a reimplementation of the quality
# gate: if the close comes back FLAGGED the publish is refused with 409 and we say so rather than
# overriding, because resolving a flagged session IS the quality gate's proof and duplicating it
# here would mean two scripts asserting the same thing differently.
if [ -z "$SESSION_DATE" ]; then
  vlog "   no PUBLISHED session — provisioning one"
  CL=$(curl -s -m60 -o /dev/null -w '%{http_code}' -X POST "$TP/eod/session/close" -H "Authorization: Bearer $ADMIN")
  vlog "      POST ${TP}/eod/session/close -> HTTP ${CL}"
  D=$(dbq "SELECT session_date FROM eod_price_session ORDER BY session_date DESC, version DESC LIMIT 1;" | tr -d '\r')
  PB=$(curl -s -m60 -o /dev/null -w '%{http_code}' -X POST "$TP/eod/prices/${D}/publish" -H "Authorization: Bearer $ADMIN")
  vlog "      POST ${TP}/eod/prices/${D}/publish -> HTTP ${PB}"
  SESSION_DATE="$(published_date)"
  printf "   %-38s %s\n" "provisioned a published session" "${SESSION_DATE:-<none>}"
fi
if [ -z "$SESSION_DATE" ]; then
  echo "   ✘ could not provision a PUBLISHED EOD session (close=${CL:-?} publish=${PB:-?})."
  [ "${PB:-}" = "409" ] && echo "   409 = the session is FLAGGED: a ticker in the universe cannot be priced."
  echo "   Resolve it there, since that is what it proves:  bash scripts/proofs/yu06-quality-gate.sh"
  exit 1
fi
echo "   authorizing against published session ${SESSION_DATE}"
XACCT="$TP/eod/prices/${SESSION_DATE}"
check "admin token"          "$(hsv GET "$XACCT" "$ADMIN"  'admin')"        200 "admin sees cross-account data"
check "account-scoped token" "$(hsv GET "$XACCT" "$SCOPED" 'scoped-no-admin')" 403 "authenticated, but no admin claim"
check "no token"             "$(hsv GET "$XACCT" ""        'anonymous')"    401 "no bearer at all"

echo "── account-scoped endpoint: /tca/report/{tradeId} ──"
check "scoped → own-account trade" "$(hsv GET "$TP/tca/report/$OWN" "$SCOPED" "scoped[${OWN_ACCT}]")"     200 "owns ${OWN_ACCT}"
check "scoped → foreign trade"     "$(hsv GET "$TP/tca/report/$FOREIGN" "$SCOPED" "scoped[${OWN_ACCT}]")" 403 "not its account"
check "admin  → foreign trade"     "$(hsv GET "$TP/tca/report/$FOREIGN" "$ADMIN" 'admin')"                200 "admin sees all"

echo
if [ "$FAILED" = "0" ]; then
  echo "[PASS] JWT auth and entitlements are enforced on both axes: the admin claim gates"
  echo "       cross-account data, and account scope is checked against the trade's own account."
else
  echo "[FAIL] at least one authorization decision was wrong — see the ✘ lines above"
fi
exit "$FAILED"
