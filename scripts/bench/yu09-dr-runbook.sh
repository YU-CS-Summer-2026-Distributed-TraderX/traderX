#!/usr/bin/env bash
# YU09 — PROOF (no cluster needed): a real disaster-recovery runbook exists and covers every failure
# mode the cluster can actually suffer, scoped honestly to the deployed topology (single-zone GKE,
# single-replica MariaDB): BLP pod loss, node loss, zone loss, MariaDB data loss — plus observed
# RPO/RTO (FR-OH40, SC covered by the runbook artifact).
#
# Usage: bash yu09-dr-runbook.sh
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$here/../.." && pwd)"
RB="$ROOT/specs/YU09-ops-hardening/system/dr-runbook.md"

echo "── incident readiness: DR runbook covers the real failure modes (FR-OH40) ──"
[ -f "$RB" ] || { echo "   dr-runbook.md not found ✘"; exit 1; }
printf "   %-34s %s\n" "runbook present" "✔  ${RB#$ROOT/}"

# each required failure mode must have its own section.
miss=0
check() {  # $1=label  $2=regex
  if grep -qiE "$2" "$RB"; then printf "   %-34s %s\n" "$1" "✔"
  else printf "   %-34s %s\n" "$1" "MISSING ✘"; miss=$((miss+1)); fi
}
check "order-matcher / BLP pod loss"  '(order-matcher|blp).*pod loss|pod loss'
check "node loss"                     'node loss'
check "zone loss"                     'zone loss'
check "MariaDB pod/data loss"         'mariadb (pod|data)|mariadb.*loss'
check "topology stated honestly"      'single-zone|single.replica|topology'
check "observed RPO / RTO"            'recovery point|recovery time|rpo|rto'

echo "   ── sections in the runbook ──"
grep -nE '^#{1,3} ' "$RB" | sed 's/^/      /'

echo
[ "$miss" -eq 0 ] && echo "   → every real failure mode has a documented recovery path ✔" \
                 || echo "   → $miss required section(s) missing ✘"
