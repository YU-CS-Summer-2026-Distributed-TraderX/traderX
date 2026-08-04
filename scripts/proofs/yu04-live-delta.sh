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
#   bash yu04-live-delta.sh -v [TICK]  # verbose: the URLs, the injected body, each replica poll
set -uo pipefail
REF=${REF:-http://localhost:18085}
# The cluster gateway directly — no edge-proxy on this rig. For the state-014 rig:
# OM=http://127.0.0.1:8080/order-matcher
OM=${OM:-http://localhost:18110}
VERBOSE=0
case "${1:-}" in -v|--verbose) VERBOSE=1; shift ;; esac
# STDERR: wm() and replica_has() are both captured with $(...).
vlog(){ [ "${VERBOSE}" = 1 ] && printf '%s\n' "$@" >&2 || true; }

# Reject a flag-looking ticker instead of injecting it. $1 is the TICKER, and there was no -v until
# now -- so `yu04-live-delta.sh -v` created a security literally named "-V" in reference-data, which
# the feed then applied perfectly while the proof reported TIMEOUT (it searched for "-v" against a
# stored "-V"). A silent injection of a junk instrument is a worse outcome than an unknown flag.
case "${1:-}" in
  -*) echo "usage: $0 [-v] [TICKER]   (unknown option: $1)" >&2; exit 1 ;;
esac
# Uppercased because the platform normalises tickers on the way in; comparing the raw argument
# against the stored value is how the case mismatch above went unexplained.
TK=$(printf '%s' "${1:-Z$(date +%s | tail -c 5)}" | tr '[:lower:]' '[:upper:]')

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

wm(){ # source watermark line
  vlog "      GET ${REF}/stocks/control-snapshot"
  curl -s -m8 "$REF/stocks/control-snapshot" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print('epoch=%s watermark=%s count=%s'%(d.get('sourceEpoch'),d.get('watermark'),d.get('count')))" 2>/dev/null
}
replica_has(){ # -> the replica's securities entry for <TK> (array of {ticker,...}), or 'null'
  vlog "      GET ${OM}/risk/control/snapshot  looking for ticker=${TK}"
  curl -s -m8 "$OM/risk/control/snapshot" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print(next((s for s in d.get('securities',[]) if s.get('ticker')=='$TK'),'null'))" 2>/dev/null
}

echo "── LIVE DURABLE DELTA (inject $TK, no restart) ──"
printf "   %-30s %s\n" "source watermark (before)" "$(wm)"
printf "   %-30s %s\n" "replica has $TK (before)" "$(replica_has)"

curl -s -m8 -X POST "$REF/stocks" -H "Content-Type: application/json" \
  -d "{\"ticker\":\"$TK\",\"companyName\":\"Demo $TK Inc.\"}" >/dev/null
vlog "      POST ${REF}/stocks  {\"ticker\":\"${TK}\",\"companyName\":\"Demo ${TK} Inc.\"}"
printf "   %-30s %s\n" "inject $TK via reference-data" "POST /stocks (stocks + outbox, one txn)"

printf "   %-30s " "replica catches up"
FAIL=1
for i in $(seq 1 20); do
  v=$(replica_has)
  if [ "$v" != "null" ] && [ -n "$v" ]; then echo "$TK present after ~$((i))×0.5s — no restart ✔"; FAIL=0; break; fi
  [ "$i" = 20 ] && echo "TIMEOUT — $TK not seen in 10s ✘"
  sleep 0.5
done

# Read the source watermark AFTER the replica has caught up, not immediately after the POST.
# Publishing the outbox row is asynchronous, so an immediate read catches the source mid-flight and
# prints the PREVIOUS watermark beside an already-incremented count -- which reads as "the watermark
# never moved" and invites a hunt for a defect in a feed that is working. Observed exactly that:
# "watermark=509 count=510" on a run whose delta arrived in ~1s, with the source settling to
# watermark=510 moments later.
printf "   %-30s %s\n" "source watermark (after)" "$(wm)"

echo
echo "── feed health ──"
# Informational only — the deployed image may predate these metric names; never the verdict.
curl -s -m8 "$OM/actuator/prometheus" \
  | { grep -E "traderx_replica_source_watermark|traderx_replica_quarantine_total|traderx_control_update_rejected_total" || echo "   (feed metrics not exposed on this build)"; } \
  | sed 's/^/   /'
# The exit code is the catch-up verdict — before this it was whatever the grep above returned.
exit $FAIL
