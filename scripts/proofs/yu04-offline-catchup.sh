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

VERBOSE=0
case "${1:-}" in -v|--verbose) VERBOSE=1; shift ;; esac
# Reject a flag-looking ticker rather than injecting it — $1 is the TICKER here, and the sibling
# proof created a security literally named "-V" that way.
case "${1:-}" in -*) echo "usage: $0 [-v] [TICKER]   (unknown option: $1)" >&2; exit 1 ;; esac
# STDERR: wm() and replica_has() are both captured with $(...).
vlog(){ [ "${VERBOSE}" = 1 ] && printf '%s\n' "$@" >&2 || true; }
CTX=${CTX:-kind-traderx-yu12-cluster}
NS=${NS:-traderx}
REF=${REF:-http://localhost:18085}
# The cluster gateway directly — no edge-proxy on this rig. For the state-014 rig:
# OM=http://127.0.0.1:8080/order-matcher
OM=${OM:-http://localhost:18110}
TK=${1:-Z$(date +%s | tail -c 5)}

# CAPABILITY CHECK, round two — and the state of it is now precise. The machinery EXISTS on this
# tier: reference-data runs here, and the gateway carries a control-feed subscriber (see
# startControlFeedSubscriber in ClusterGatewayMain) that was OBSERVED consuming this stream and
# sequencing SECURITY_CONTROL commands through consensus. It is disabled by default because the
# feed carries the whole 510-security universe while the clustered service's symbol table is
# MAX_SECURITIES = 64 -- replaying the stream fills the table, and a consumer that refuses to
# silently drop securities (ADR-021) then cannot make progress past the 64th ticker. The blocker
# is a replicated-state CAPACITY decision, not missing plumbing.
if ! curl -sf -m8 -o /dev/null "${REF}/instruments/control-snapshot" 2>/dev/null; then
  echo "   ✘ ${REF}/instruments/control-snapshot unreachable — is reference-data deployed and forwarded (18085)?"
  exit 2
fi
K="kubectl -n $NS --context $CTX"

# IN-CLUSTER, for the same reason replica_has() is: this proof scales the gateway to zero and back,
# so a localhost read here polls a port-forward THIS PROOF'S OWN PREVIOUS RUN destroyed. It then
# reports "the subscriber is not consuming" — blaming a config flag for a tunnel the script killed
# itself. Observed exactly that. The Service address follows the new pod without a forward.
_SNAP="$($K exec deploy/trade-processor -- curl -s -m8 "http://order-matcher:18110/risk/control/snapshot" 2>/dev/null)"
if ! printf '%s' "${_SNAP}" | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if d.get('count',0) > 64 else 1)" 2>/dev/null; then
  if [ -z "${_SNAP}" ]; then
    echo "   ✘ could not read the gateway's control snapshot from inside the cluster —"
    echo "   is cluster-gateway up, and deploy/trade-processor reachable? (This is NOT a"
    echo "   statement about the control feed.)"
    exit 2
  fi
  echo "   ✘ the gateway's control-feed subscriber is not consuming (CONTROL_FEED_SUBSCRIBER=0 by default)."
  echo "   The subscriber works — it was observed applying this stream through consensus — but the"
  echo "   feed's 510-security universe does not fit the engine's MAX_SECURITIES=64 symbol table,"
  echo "   and a consumer that will not silently drop securities cannot get past the 64th."
  echo "   Unblocking is a capacity decision on replicated state (raise MAX_SECURITIES / narrow the"
  echo "   feed / bound the table), then: CONTROL_FEED_SUBSCRIBER=1 on the cluster-gateway."
  exit 2
fi

# Works whether order-matcher is a Deployment (kind) or StatefulSet (GKE).
# The workload that CONSUMES the feed — that is what must be down while the delta is published.
# On this rig it is the cluster gateway (its subscriber replays the durable stream from the start
# on every connect, which IS the bootstrap path); on the state-014 rig it was the Spring
# order-matcher. The members are deliberately NOT touched: the point is that the consumer catches
# up, not that the cluster survives — that is other proofs' job.
CONSUMER_WL="${CONSUMER_WL:-deploy/cluster-gateway}"
WL=$($K get "$CONSUMER_WL" -o name 2>/dev/null)
[ -z "$WL" ] && WL=$($K get deploy order-matcher -o name 2>/dev/null || $K get statefulset order-matcher -o name 2>/dev/null)
[ -z "$WL" ] && { echo "feed-consumer workload not found in ns/$NS (tried $CONSUMER_WL, order-matcher)"; exit 1; }

wm(){ vlog "      GET ${REF}/instruments/control-snapshot"; curl -s -m8 "$REF/instruments/control-snapshot" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('epoch=%s watermark=%s count=%s'%(d.get('sourceEpoch'),d.get('watermark'),d.get('count')))" 2>/dev/null; }
# Read the replica FROM INSIDE the cluster. This proof scales the gateway to zero and back, which
# kills any port-forward attached to its pod — so a localhost read here polls a dead socket forever
# and reports "not seen", i.e. the proof's own restart machinery masquerades as a catch-up failure.
# An exec through a stable pod (trade-processor) reaches the Service address, which follows the new
# pod on its own.
replica_has(){ vlog "      (via trade-processor) GET http://order-matcher:18110/risk/control/snapshot  looking for ${TK}"; $K exec deploy/trade-processor -- curl -s -m8 "http://order-matcher:18110/risk/control/snapshot" 2>/dev/null \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(next((s for s in d.get('securities',[]) if s.get('ticker')=='$TK'),'null'))" 2>/dev/null; }

echo "── OFFLINE CATCH-UP (change published while $WL is DOWN) ──"
printf "   %-30s %s\n" "source watermark (before)" "$(wm)"
printf "   %-30s %s\n" "replica has $TK (before)" "$(replica_has)"

printf "   %-30s " "scale $WL -> 0 (offline)"
$K scale "$WL" --replicas=0 >/dev/null && $K rollout status "$WL" --timeout=60s >/dev/null 2>&1; echo "down"

curl -s -m8 -X POST "$REF/stocks" -H "Content-Type: application/json" \
  -d "{\"ticker\":\"$TK\",\"companyName\":\"Demo $TK Inc.\"}" >/dev/null
printf "   %-30s %s\n" "inject $TK while OFFLINE" "POST /stocks — published to JetStream, retained"

vlog "      scale ${WL} --replicas=1"
printf "   %-30s " "scale $WL -> 1, wait ready"
$K scale "$WL" --replicas=1 >/dev/null && $K rollout status "$WL" --timeout=180s >/dev/null 2>&1; echo "up"

printf "   %-30s " "replica after bootstrap"
# 90s, not 20s: on the cluster tier "bootstrap" is the gateway replaying the ENTIRE durable stream
# through consensus (500+ sequenced commands), which is the mechanism under test, not overhead.
# And the verdict is now the exit code — this used to print ✘ and exit 0, which is how a timeout
# was reported as a pass earlier tonight.
FAIL=1
for i in $(seq 1 90); do
  v=$(replica_has)
  if [ "$v" != "null" ] && [ -n "$v" ]; then
    echo "$TK present — durable catch-up ✔ (no re-push, no manual recon)"
    FAIL=0
    break
  fi
  [ "$i" = 90 ] && echo "TIMEOUT — $TK not seen 90s after ready ✘"
  sleep 1
done

# Read the source watermark only AFTER catch-up has settled. Publishing the outbox row is
# asynchronous, so reading it straight after the POST catches the source mid-flight and prints the
# PREVIOUS watermark beside an already-incremented count — observed here as "watermark=516
# count=517" on a run that worked. Same display race as yu04-live-delta; it reads as a stalled feed
# and invites a hunt for a defect that is not there.
printf "   %-30s %s\n" "source watermark (after)" "$(wm)"
exit "$FAIL"
