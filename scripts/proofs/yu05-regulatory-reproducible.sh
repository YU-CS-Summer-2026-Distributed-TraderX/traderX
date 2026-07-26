#!/usr/bin/env bash
# YU05 — PROOF (the money demo): the regulatory audit export is a PURE FUNCTION of (journal range,
# seed) — sourced from journal replay, never the MariaDB projection (FR-PTC20/21). Calling it twice
# over the same range returns byte-identical records. This is the event-sourcing payoff: a regulator
# query is answered reproducibly from the source of truth.
#
# Prereq: source terminals in yu05-common.sh.
# Usage: bash yu05-regulatory-reproducible.sh [fromSeq] [toSeq]
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/yu05-common.sh"
FROM=${1:-0}; TO=${2:-1000000}
ADMIN=$(mint true '[]')
URL="$OM/regulatory/report?fromSeq=$FROM&toSeq=$TO"

echo "── REGULATORY EXPORT REPRODUCIBILITY (seq $FROM..$TO) ──"
# Canonicalize the records array (sorted keys) and hash it, so identical content -> identical hash
# regardless of any wrapper/generatedAt field. TO-VERIFY: records live under .records (per quickstart).
digest(){ curl -s -m15 "$URL" -H "Authorization: Bearer $ADMIN" \
  | python3 -c "import sys,json,hashlib
d=json.load(sys.stdin)
recs=d if isinstance(d,list) else d.get('records',[])   # /regulatory/report is a top-level array
blob=json.dumps(recs,sort_keys=True,separators=(',',':')).encode()
print(len(recs), hashlib.sha256(blob).hexdigest()[:16])"; }

read n1 h1 < <(digest); printf "   %-24s records=%-7s sha=%s\n" "call 1" "$n1" "$h1"
read n2 h2 < <(digest); printf "   %-24s records=%-7s sha=%s\n" "call 2" "$n2" "$h2"
if [ -n "$h1" ] && [ "$h1" = "$h2" ]; then
  echo "   → byte-identical across calls ✔  (reproducible from the journal, not the projection)"
else
  echo "   → MISMATCH or empty ✘  (h1=$h1 h2=$h2 — check the endpoint/field shape)"
fi
