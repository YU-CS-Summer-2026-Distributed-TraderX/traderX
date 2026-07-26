#!/usr/bin/env bash
# YU04 durable control feeds — PROOF 2: offline replica catches up on reconnect.
# Take order-matcher DOWN, inject a control change while it's offline, bring it back:
# it bootstraps from the watermarked snapshot + buffered JetStream deltas and only declares
# ready once caught up (FR-IMRG05). (YU03's replica had no update path at all while running —
# a boot-time REST snapshot, stale until the next restart; YU04 makes it a live durable feed.)
#
# Prereq (separate terminal): reference-data port-forward. order-matcher is reached via the
# edge-proxy (127.0.0.1:8080) so the view survives the pod going away.
#   kubectl port-forward -n traderx svc/reference-data 18085:18085 --context kind-traderx-state-014
# Usage:
#   bash yu04-offline-catchup.sh          # auto ticker (Z + time)
#   bash yu04-offline-catchup.sh ZOFF     # explicit ticker
set -uo pipefail
CTX=${CTX:-kind-traderx-state-014}
NS=${NS:-traderx}
REF=${REF:-http://localhost:18085}
OM=${OM:-http://127.0.0.1:8080/order-matcher}
TK=${1:-Z$(date +%s | tail -c 5)}
K="kubectl -n $NS --context $CTX"

# Works whether order-matcher is a Deployment (kind) or StatefulSet (GKE).
WL=$($K get deploy order-matcher -o name 2>/dev/null || $K get statefulset order-matcher -o name 2>/dev/null)
[ -z "$WL" ] && { echo "order-matcher workload not found in ns/$NS"; exit 1; }

wm(){ curl -s -m8 "$REF/stocks/control-snapshot" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('epoch=%s watermark=%s count=%s'%(d.get('sourceEpoch'),d.get('watermark'),d.get('count')))" 2>/dev/null; }
replica_has(){ curl -s -m8 "$OM/risk/control/snapshot" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(next((s for s in d.get('securities',[]) if s.get('ticker')=='$TK'),'null'))" 2>/dev/null; }

echo "── OFFLINE CATCH-UP (change published while $WL is DOWN) ──"
printf "   %-30s %s\n" "source watermark (before)" "$(wm)"
printf "   %-30s %s\n" "replica has $TK (before)" "$(replica_has)"

printf "   %-30s " "scale $WL -> 0 (offline)"
$K scale "$WL" --replicas=0 >/dev/null && $K rollout status "$WL" --timeout=60s >/dev/null 2>&1; echo "down"

curl -s -m8 -X POST "$REF/stocks" -H "Content-Type: application/json" \
  -d "{\"ticker\":\"$TK\",\"companyName\":\"Demo $TK Inc.\"}" >/dev/null
printf "   %-30s %s\n" "inject $TK while OFFLINE" "POST /stocks — published to JetStream, retained"
printf "   %-30s %s\n" "source watermark (after)" "$(wm)"

printf "   %-30s " "scale $WL -> 1, wait ready"
$K scale "$WL" --replicas=1 >/dev/null && $K rollout status "$WL" --timeout=180s >/dev/null 2>&1; echo "up"

printf "   %-30s " "replica after bootstrap"
for i in $(seq 1 40); do
  v=$(replica_has)
  if [ "$v" != "null" ] && [ -n "$v" ]; then echo "$TK present — durable catch-up ✔ (no re-push, no manual recon)"; break; fi
  [ "$i" = 40 ] && echo "TIMEOUT — $TK not seen 20s after ready ✘"
  sleep 0.5
done
