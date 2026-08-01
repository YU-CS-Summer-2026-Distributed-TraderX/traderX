#!/usr/bin/env bash
# YU04 durable control feeds — PROOF 2: offline replica catches up on reconnect.
# Take order-matcher DOWN, inject a control change while it's offline, bring it back:
# it bootstraps from the watermarked snapshot + buffered JetStream deltas and only declares
# ready once caught up (FR-IMRG05). (YU03's replica had no update path at all while running —
# a boot-time REST snapshot, stale until the next restart; YU04 makes it a live durable feed.)
#
# Prereq (separate terminal): reference-data port-forward. order-matcher is reached via the
# edge-proxy (127.0.0.1:8080) so the view survives the pod going away.
#   kubectl port-forward -n traderx svc/reference-data 18085:18085 --context "${CTX:-kind-traderx-yu12-cluster}"
# Usage:
#   bash yu04-offline-catchup.sh          # auto ticker (Z + time)
#   bash yu04-offline-catchup.sh ZOFF     # explicit ticker
set -uo pipefail
CTX=${CTX:-kind-traderx-yu12-cluster}
NS=${NS:-traderx}
REF=${REF:-http://localhost:18085}
OM=${OM:-http://127.0.0.1:8080/order-matcher}
TK=${1:-Z$(date +%s | tail -c 5)}

# CAPABILITY CHECK, round two — and the state of it is now precise. The machinery EXISTS on this
# tier: reference-data runs here, and the gateway carries a control-feed subscriber (see
# startControlFeedSubscriber in ClusterGatewayMain) that was OBSERVED consuming this stream and
# sequencing SECURITY_CONTROL commands through consensus. It is disabled by default because the
# feed carries the whole 510-security universe while the clustered service's symbol table is
# MAX_SECURITIES = 64 -- replaying the stream fills the table, and a consumer that refuses to
# silently drop securities (ADR-021) then cannot make progress past the 64th ticker. The blocker
# is a replicated-state CAPACITY decision, not missing plumbing.
if ! curl -sf -m8 -o /dev/null "${REF}/stocks/control-snapshot" 2>/dev/null; then
  echo "   ✘ ${REF}/stocks/control-snapshot unreachable — is reference-data deployed and forwarded (18085)?"
  exit 2
fi
if ! curl -sf -m8 "${OM:-http://localhost:18110}/risk/control/snapshot" 2>/dev/null     | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if d.get('count',0) > 64 else 1)" 2>/dev/null; then
  echo "   ✘ the gateway's control-feed subscriber is not consuming (CONTROL_FEED_SUBSCRIBER=0 by default)."
  echo "   The subscriber works — it was observed applying this stream through consensus — but the"
  echo "   feed's 510-security universe does not fit the engine's MAX_SECURITIES=64 symbol table,"
  echo "   and a consumer that will not silently drop securities cannot get past the 64th."
  echo "   Unblocking is a capacity decision on replicated state (raise MAX_SECURITIES / narrow the"
  echo "   feed / bound the table), then: CONTROL_FEED_SUBSCRIBER=1 on the cluster-gateway."
  exit 2
fi
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
