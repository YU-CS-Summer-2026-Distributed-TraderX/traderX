#!/usr/bin/env bash
# YU06 — PROOF: closing prices are a versioned, IMMUTABLE snapshot (FR-EOD03/20, NFR-EOD01).
# Re-running production for a date creates a NEW version; the prior version is never mutated.
#
# Prereq: source terminals in yu05-common.sh (trade-processor port-forward) + kube context.
# Usage: bash yu06-versioning.sh
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/yu05-common.sh"
ADMIN=$(mint true '[]')
DATE=$(date +%F)

echo "── EOD VERSIONING & IMMUTABILITY ($DATE) ──"
sess(){ dbq "SELECT version, status, instrument_count, flagged_count FROM eod_price_session WHERE session_date='$DATE' ORDER BY version;"; }

# close #1
curl -s -m15 -X POST "$TP/eod/session/close" -H "Authorization: Bearer $ADMIN" >/dev/null
sleep 1
printf "   %-30s\n" "after close #1 (version/status/instr/flagged):"; sess | sed 's/^/      v/'
# capture v1's first snapshot price to prove immutability later
V1=$(dbq "SELECT MIN(version) FROM eod_price_session WHERE session_date='$DATE';")
P1=$(dbq "SELECT closing_price FROM eod_price_snapshot WHERE session_date='$DATE' AND version=$V1 ORDER BY security LIMIT 1;")

# close #2 -> new version
curl -s -m15 -X POST "$TP/eod/session/close" -H "Authorization: Bearer $ADMIN" >/dev/null
sleep 1
printf "   %-30s\n" "after close #2 (expect a NEW version row):"; sess | sed 's/^/      v/'

# prove v1 is unchanged
P1b=$(dbq "SELECT closing_price FROM eod_price_snapshot WHERE session_date='$DATE' AND version=$V1 ORDER BY security LIMIT 1;")
nver=$(dbq "SELECT COUNT(DISTINCT version) FROM eod_price_session WHERE session_date='$DATE';")
printf "   %-30s v1 first price %s -> %s\n" "immutability check" "$P1" "$P1b"
{ [ "$P1" = "$P1b" ] && [ "${nver:-0}" -ge 2 ]; } \
  && echo "   → new version created, v1 unchanged ✔" \
  || echo "   → check: versions=$nver, v1 price stable=$([ "$P1" = "$P1b" ] && echo yes || echo NO)"
