#!/usr/bin/env bash
# YU03 risk gateway — clean live demo. One readable line per step.
# Prereq (separate terminal):
#   kubectl port-forward -n traderx deploy/order-matcher 18110:18110 --context "${CTX:-kind-traderx-yu12-cluster}"
# Usage:
#   bash yu03-risk-demo.sh restriction   # just the restricted-security toggle
#   bash yu03-risk-demo.sh killswitch    # just the kill switch
#   bash yu03-risk-demo.sh controls      # every rejection control at once
#   bash yu03-risk-demo.sh               # all of the above
set -uo pipefail
U=${MATCHER_URL:-http://localhost:18110}
TOK=${RISK_CONTROL_TOKEN:-dev-risk-control}

order(){ # $1=label  $2=json body   ->  "label            NEW" / "label   REJECTED · REASON"
  local r st rs
  r=$(curl -s -m8 "$U/orders" -H "Content-Type: application/json" -d "$2")
  # Two response shapes. The Spring matcher answers {"status":...}; the cluster gateway answers
  # {"orderRef":N,"kind":K,"reason":...} and carries a reason only when it rejected. Falling back
  # to reason-presence rather than decoding `kind` keeps this correct without pinning the numeric
  # kind values, which are an internal egress detail.
  st=$(printf '%s' "$r" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('status') or d.get('decision') or ('REJECTED' if d.get('reason') else 'NEW'))" 2>/dev/null)
  rs=$(printf '%s' "$r" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('riskReason') or d.get('reason') or '')" 2>/dev/null)
  printf "   %-26s %s%s\n" "$1" "$st" "${rs:+ · $rs}"
}
ctl(){ # $1=label  $2=endpoint  $3=json body   ->  "label   [control HTTP nnn]"
  local code
  code=$(curl -s -m8 -o /dev/null -w "%{http_code}" -X POST "$U/risk/control/$2" \
    -H "Content-Type: application/json" -H "X-Risk-Control-Token: $TOK" -H "X-Risk-Operator: demo" -d "$3")
  printf "   %-26s [control HTTP %s]\n" "$1" "$code"
}
BAC='{"accountId":52355,"security":"BAC","side":"Buy","quantity":11,"limitPrice":40}'
IBM='{"accountId":22214,"security":"IBM","side":"Buy","quantity":10,"limitPrice":190}'

# On the cluster tier an account and a security only exist once they have been sequenced, and an
# order for one that has not been is rejected UNKNOWN_ACCOUNT / UNKNOWN_SECURITY before any risk
# control is consulted -- which would make every line below read like a rejection the demo caused.
# /seed is idempotent and is the same sequenced control path the proof then exercises, so seeding
# here costs nothing and makes the script self-contained on a fresh rig. Skipped silently if the
# endpoint is absent (the Spring matcher seeds from its database instead).
for acct in 52355 22214; do
  curl -s -m8 -o /dev/null -X POST "$U/seed" -H "Content-Type: application/json" \
    -d "{\"accountId\":$acct,\"tickers\":\"IBM,BAC\",\"price\":200}" || true
done

restriction(){
  echo "── RESTRICTED SECURITY (operator restricts BAC live) ──"
  order "order BAC"           "$BAC"
  ctl   "restrict BAC"        restriction '{"ticker":"BAC","restricted":true}'
  order "order BAC"           "$BAC"
  ctl   "un-restrict BAC"     restriction '{"ticker":"BAC","restricted":false}'
  order "order BAC"           "$BAC"
}
killswitch(){
  echo "── KILL SWITCH (operator halts all trading) ──"
  ctl   "engage kill switch"  policy '{"policyVersion":40,"killSwitch":true,"maxPositionQuantity":null,"maxConcentrationNotionalTicks":null}'
  order "any order"           "$IBM"
  ctl   "disengage"           policy '{"policyVersion":41,"killSwitch":false,"maxPositionQuantity":null,"maxConcentrationNotionalTicks":null}'
  order "any order"           "$IBM"
}
controls(){
  echo "── PRE-TRADE REJECTIONS (one order per control) ──"
  order "valid order"         '{"accountId":22214,"security":"IBM","side":"Buy","quantity":10,"limitPrice":200}'
  order "unknown account"     '{"accountId":99999,"security":"IBM","side":"Buy","quantity":10,"limitPrice":200}'
  order "price collar"        '{"accountId":22214,"security":"IBM","side":"Buy","quantity":10,"limitPrice":400}'
  order "max order size"      '{"accountId":22214,"security":"IBM","side":"Buy","quantity":2000000,"limitPrice":200}'
}

case "${1:-all}" in
  restriction) restriction ;;
  killswitch)  killswitch ;;
  controls)    controls ;;
  all)         controls; echo; restriction; echo; killswitch ;;
  *) echo "usage: $0 [restriction|killswitch|controls|all]"; exit 1 ;;
esac
