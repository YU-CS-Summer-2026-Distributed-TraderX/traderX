#!/usr/bin/env bash
# YU05 — PROOF (the money demo): the regulatory audit export is a PURE FUNCTION of (journal range,
# seed) — sourced from journal replay, never the MariaDB projection (FR-PTC20/21). Calling it twice
# over the same range returns byte-identical records. This is the event-sourcing payoff: a regulator
# query is answered reproducibly from the source of truth.
#
# ON THIS TIER the journal is the Aeron Cluster log: a member replays its archive through a shadow
# engine and renders the audit trail from the outputs that replay produces (ClusterRecon). The
# gateway forwards; it holds no history of its own.
#
# THE RANGE IS CLOSED ON PURPOSE. "toSeq = to the end" is not reproducible on a live tier — the
# control feed and the price publisher keep committing, so call 2 would answer over a longer log
# than call 1 and the proof would report a MISMATCH about a system that is behaving exactly as
# specified. FR-PTC21 claims the same RANGE in gives the same records out, so the range is pinned
# to a sequence the log has already passed.
#
# Prereq: source terminals in yu05-common.sh.
# Usage: bash yu05-regulatory-reproducible.sh [fromSeq] [toSeq]
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/yu05-common.sh"
ADMIN=$(mint true '[]')

# 000 means the request never reached anything -- a dead port-forward, usually. Treating that as
# "capability present" let the script sail past this check and produce a pass against a matcher it
# never contacted, which is precisely the vacuous result this guard exists to prevent.
_OM_CODE="$(curl -s -o /dev/null -w '%{http_code}' -m10 "$OM/regulatory/report" \
  -H "Authorization: Bearer $ADMIN")"
if [ "$_OM_CODE" = "000" ]; then
  echo "   ✘ $OM unreachable (curl 000) — port-forward svc/order-matcher 18110:18110?"
  exit 1
fi
if [ "$_OM_CODE" = "404" ]; then
  echo "   ✘ $OM/regulatory/report -> 404"
  echo "   A member build predating ClusterRecon does not serve the journal-sourced regulatory"
  echo "   surface. Rebuild (scripts/yu15/build-cluster-image.sh) and roll to a FRESH EPOCH, or"
  echo "   run against the state-014 rig:"
  echo "     ORDER_MATCHER_URL=http://localhost:8080/order-matcher bash $0"
  exit 2
fi
if [ "$_OM_CODE" = "503" ]; then
  # A disabled capability is a fact about this rig's env, not a verdict about the tier.
  echo "   ✘ members answer 503 — RECON_BLOTTER_CAPACITY unset on order-matcher-cluster."
  echo "   The capability exists in this build; this deployment has it switched off."
  exit 1
fi
if [ "$_OM_CODE" = "401" ] || [ "$_OM_CODE" = "403" ]; then
  echo "   ✘ admin JWT rejected ($_OM_CODE) — AUTH_JWT_SECRET differs between trade-processor and"
  echo "   the order-matcher-cluster StatefulSet. A configuration fault, not a reproducibility result."
  exit 1
fi

# The upper bound: a consensus sequence every member has already applied. Read from the log side,
# never from SQL — the whole claim is that this answer does not come from the projection.
APPLIED=$($K exec order-matcher-cluster-0 -- sh -c 'wget -qO- http://localhost:8080/health' 2>/dev/null \
  | jfield "d['applied']")
case "${APPLIED:-}" in ''|*[!0-9]*)
  echo "   ✘ could not read a member's applied sequence — refusing to bound the range by guesswork"
  exit 1 ;;
esac

FROM=${1:-0}; TO=${2:-$APPLIED}
URL="$OM/regulatory/report?fromSeq=$FROM&toSeq=$TO"

echo "── REGULATORY EXPORT REPRODUCIBILITY (seq $FROM..$TO) ──"
# Canonicalize the records array (sorted keys) and hash it, so identical content -> identical hash
# regardless of any wrapper/generatedAt field, plus the count of kinds that only journal replay can
# produce.
digest(){ curl -s -m600 "$URL" -H "Authorization: Bearer $ADMIN" \
  | python3 -c "import sys,json,hashlib
d=json.load(sys.stdin)
recs=d if isinstance(d,list) else d.get('records',[])   # /regulatory/report is a top-level array
blob=json.dumps(recs,sort_keys=True,separators=(',',':')).encode()
lifecycle=sum(1 for r in recs if r.get('kind')!='TRADE_BOOKED')
print(len(recs), hashlib.sha256(blob).hexdigest()[:16], lifecycle)"; }

read n1 h1 l1 < <(digest); printf "   %-24s records=%-7s sha=%-18s order-lifecycle=%s\n" "call 1" "$n1" "$h1" "$l1"
read n2 h2 l2 < <(digest); printf "   %-24s records=%-7s sha=%-18s order-lifecycle=%s\n" "call 2" "$n2" "$h2" "$l2"

FAIL=0
if [ -z "${n1:-}" ] || [ "${n1:-0}" -le 0 ]; then
  # An empty export hashes identically to another empty export. "Reproducible" over no records is
  # the vacuous pass, not the proof.
  echo "   ✘ the export is EMPTY over seq $FROM..$TO — two identical empty answers prove nothing"
  FAIL=1
elif [ "$h1" != "$h2" ]; then
  echo "   ✘ MISMATCH (h1=$h1 h2=$h2) — the same journal range answered differently twice"
  FAIL=1
else
  echo "   → byte-identical across calls ✔  ($n1 records, reproducible from the log)"
fi
if [ "${n1:-0}" -le 0 ]; then
  : # already reported; "no lifecycle records" in an empty export is the same finding twice
elif [ "${l1:-0}" -le 0 ]; then
  # ORDER_ACCEPTED / ORDER_CANCELED / ORDER_REJECTED exist nowhere in the projection — the trades
  # table cannot produce them. Their presence is what distinguishes a journal replay from a SQL
  # query dressed up as one.
  echo "   ✘ every record is a TRADE_BOOKED — an audit trail sourced from the log carries order"
  echo "     lifecycle events the projection has no table for; without them this could be SQL."
  FAIL=1
else
  echo "   → $l1 order-lifecycle records the projection cannot produce ✔  (sourced from replay)"
fi
exit $FAIL
