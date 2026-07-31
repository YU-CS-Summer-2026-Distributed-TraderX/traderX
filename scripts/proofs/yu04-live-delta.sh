#!/usr/bin/env bash
# YU04 durable control feeds — PROOF 1: live delta, no restart.
# A security injected through reference-data's outbox write path reaches order-matcher's
# Gateway replica over the JetStream control feed within a poll interval — the BLP never restarts.
#
# Prereq (separate terminal): reference-data port-forward; order-matcher via the edge-proxy.
#   kubectl port-forward -n traderx svc/reference-data 18085:18085 --context "${CTX:-kind-traderx-yu12-cluster}"
#   (edge-proxy already serves 127.0.0.1:8080)
# Usage:
#   bash yu04-live-delta.sh            # auto ticker (Z + time)
#   bash yu04-live-delta.sh ZZZZ       # explicit ticker
set -uo pipefail
REF=${REF:-http://localhost:18085}
OM=${OM:-http://127.0.0.1:8080/order-matcher}
TK=${1:-Z$(date +%s | tail -c 5)}

# CAPABILITY CHECK. This proof needs two things the YU15 cluster rig does not have:
#   1. reference-data deployed (it injects a security via POST /stocks and reads its outbox
#      watermark from GET /stocks/control-snapshot) -- not in the cluster kustomization; and
#   2. a consumer of that JetStream control feed ON THIS TIER. ControlFeedSubscriber is referenced
#      only by ReplicaBootstrap, the Spring app path -- it is NOT wired into ClusterNodeMain or
#      MatchingEngineClusteredService. So even with reference-data running, its deltas would reach
#      no member here.
# GET /risk/control/snapshot does now exist on the cluster gateway, but nothing populates it from a
# feed, so this proof would report a control that never arrived -- which reads as a broken durable
# feed rather than an absent consumer. Say which, and stop.
if ! curl -sf -m8 -o /dev/null "${REF}/stocks/control-snapshot" 2>/dev/null; then
  echo "   ✘ ${REF}/stocks/control-snapshot unreachable"
  echo "   reference-data is not deployed on this rig, and the cluster tier has no consumer for its"
  echo "   control feed (ControlFeedSubscriber is wired into the Spring app only, not the clustered"
  echo "   service). Both are needed before this proof can mean anything here."
  echo "   Run against the state-014 rig:  CTX=kind-traderx-state-014 bash $0"
  exit 2
fi

wm(){ # source watermark line
  curl -s -m8 "$REF/stocks/control-snapshot" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print('epoch=%s watermark=%s count=%s'%(d.get('sourceEpoch'),d.get('watermark'),d.get('count')))" 2>/dev/null
}
replica_has(){ # -> the replica's securities entry for <TK> (array of {ticker,...}), or 'null'
  curl -s -m8 "$OM/risk/control/snapshot" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print(next((s for s in d.get('securities',[]) if s.get('ticker')=='$TK'),'null'))" 2>/dev/null
}

echo "── LIVE DURABLE DELTA (inject $TK, no restart) ──"
printf "   %-30s %s\n" "source watermark (before)" "$(wm)"
printf "   %-30s %s\n" "replica has $TK (before)" "$(replica_has)"

curl -s -m8 -X POST "$REF/stocks" -H "Content-Type: application/json" \
  -d "{\"ticker\":\"$TK\",\"companyName\":\"Demo $TK Inc.\"}" >/dev/null
printf "   %-30s %s\n" "inject $TK via reference-data" "POST /stocks (stocks + outbox, one txn)"
printf "   %-30s %s\n" "source watermark (after)" "$(wm)"

printf "   %-30s " "replica catches up"
FAIL=1
for i in $(seq 1 20); do
  v=$(replica_has)
  if [ "$v" != "null" ] && [ -n "$v" ]; then echo "$TK present after ~$((i))×0.5s — no restart ✔"; FAIL=0; break; fi
  [ "$i" = 20 ] && echo "TIMEOUT — $TK not seen in 10s ✘"
  sleep 0.5
done

echo
echo "── feed health ──"
# Informational only — the deployed image may predate these metric names; never the verdict.
curl -s -m8 "$OM/actuator/prometheus" \
  | { grep -E "traderx_replica_source_watermark|traderx_replica_quarantine_total|traderx_control_update_rejected_total" || echo "   (feed metrics not exposed on this build)"; } \
  | sed 's/^/   /'
# The exit code is the catch-up verdict — before this it was whatever the grep above returned.
exit $FAIL
