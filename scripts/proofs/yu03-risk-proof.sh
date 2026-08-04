#!/usr/bin/env bash
# YU03 risk gateway — live proof. One readable line per step, each ASSERTED.
#
# It was a demo before: it printed whatever the gateway answered and always exited 0. Every step
# could have come back wrong — a dead price collar answering NEW instead of REJECTED — and the
# output would have looked almost identical while the suite recorded a PASS. It was carried in
# run-proofs.sh's PROOFS array, so it contributed one unconditional pass to every sweep.
#
# Each step now declares what it expects and the exit code is the verdict. The expectations are
# the ones the working gateway actually produces, captured from a live run on 2026-08-03.
#
# Prereq (separate terminal) — svc/, NOT deploy/: on the cluster tier svc/order-matcher fronts
# cluster-gateway and there is no Deployment by that name.
#   kubectl port-forward -n traderx svc/order-matcher 18110:18110 --context "${CTX:-kind-traderx-yu12-cluster}"
# Usage:
#   bash yu03-risk-proof.sh restriction   # just the restricted-security toggle
#   bash yu03-risk-proof.sh killswitch    # just the kill switch
#   bash yu03-risk-proof.sh controls      # every rejection control at once
#   bash yu03-risk-proof.sh               # all of the above
#   bash yu03-risk-proof.sh -v [mode]     # verbose: show each request and the raw response
#
# -v exists because the one-line-per-step form is deliberately lossy: it reports the VERDICT and
# hides what produced it. When a step fails, the next question is always "what did we actually
# send, and what came back" — and re-deriving that from the source is slower than printing it.
set -uo pipefail
U=${MATCHER_URL:-http://localhost:18110}
TOK=${RISK_CONTROL_TOKEN:-dev-risk-control}

VERBOSE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    -v|--verbose) VERBOSE=1; shift ;;
    --) shift; break ;;
    *) break ;;
  esac
done
MODE="${1:-all}"

CHECKS=0
FAILED=0

vlog(){ (( VERBOSE )) && printf '%s\n' "$@" >&2 || true; }

order(){ # $1=label  $2=expected ("NEW" | "REJECTED·REASON")  $3=json body
  local r st rs got
  CHECKS=$((CHECKS + 1))
  vlog "      → POST ${U}/orders" "        ${3}" "        expect: ${2//·/ · }"
  r=$(curl -s -m8 "$U/orders" -H "Content-Type: application/json" -d "$3")
  vlog "      ← ${r:-<empty>}"
  # An empty body is a dead port-forward or a gateway that is not serving, NOT a risk verdict.
  # Reported separately on purpose: the whole failure class this proof exists to catch is a
  # rejection reason being wrong, and a connection problem rendered as "expected REJECTED, got
  # nothing" would read as a gateway defect. Say which one it is.
  if [[ -z "${r}" ]]; then
    printf "   %-26s ✘ UNREACHABLE — no response from %s (is the port-forward up?)\n" "$1" "$U"
    FAILED=$((FAILED + 1)); return
  fi
  # Two response shapes. The Spring matcher answers {"status":...}; the cluster gateway answers
  # {"orderRef":N,"kind":K,"reason":...} and carries a reason only when it rejected. Falling back
  # to reason-presence rather than decoding `kind` keeps this correct without pinning the numeric
  # kind values, which are an internal egress detail.
  st=$(printf '%s' "$r" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('status') or d.get('decision') or ('REJECTED' if d.get('reason') else 'NEW'))" 2>/dev/null)
  rs=$(printf '%s' "$r" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('riskReason') or d.get('reason') or '')" 2>/dev/null)
  got="${st}${rs:+·$rs}"
  if [[ "${got}" == "$2" ]]; then
    printf "   %-26s %s%s ✔\n" "$1" "$st" "${rs:+ · $rs}"
  else
    printf "   %-26s %s%s ✘  expected %s\n" "$1" "$st" "${rs:+ · $rs}" "${2//·/ · }"
    FAILED=$((FAILED + 1))
  fi
}
ctl(){ # $1=label  $2=endpoint  $3=json body   (control-plane mutations must answer HTTP 200)
  local code
  CHECKS=$((CHECKS + 1))
  vlog "      → POST ${U}/risk/control/${2}" "        ${3}" "        expect: HTTP 200"
  code=$(curl -s -m8 -o /dev/null -w "%{http_code}" -X POST "$U/risk/control/$2" \
    -H "Content-Type: application/json" -H "X-Risk-Control-Token: $TOK" -H "X-Risk-Operator: demo" -d "$3")
  vlog "      ← HTTP ${code}"
  if [[ "${code}" == "200" ]]; then
    printf "   %-26s [control HTTP %s] ✔\n" "$1" "$code"
  else
    # A non-200 here is why the NEXT order's verdict is wrong, so failing on it names the cause
    # rather than leaving the following step to report a confusing rejection.
    printf "   %-26s [control HTTP %s] ✘  expected 200\n" "$1" "$code"
    FAILED=$((FAILED + 1))
  fi
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
  order "order BAC"           NEW                   "$BAC"
  ctl   "restrict BAC"        restriction '{"ticker":"BAC","restricted":true}'
  order "order BAC"           REJECTED·RESTRICTED   "$BAC"
  ctl   "un-restrict BAC"     restriction '{"ticker":"BAC","restricted":false}'
  order "order BAC"           NEW                   "$BAC"
}
killswitch(){
  echo "── KILL SWITCH (operator halts all trading) ──"
  ctl   "engage kill switch"  policy '{"policyVersion":40,"killSwitch":true,"maxPositionQuantity":null,"maxConcentrationNotionalTicks":null}'
  order "any order"           REJECTED·KILL_SWITCH  "$IBM"
  ctl   "disengage"           policy '{"policyVersion":41,"killSwitch":false,"maxPositionQuantity":null,"maxConcentrationNotionalTicks":null}'
  order "any order"           NEW                   "$IBM"
}
controls(){
  echo "── PRE-TRADE REJECTIONS (one order per control) ──"
  order "valid order"         NEW                      '{"accountId":22214,"security":"IBM","side":"Buy","quantity":10,"limitPrice":200}'
  order "unknown account"     REJECTED·UNKNOWN_ACCOUNT '{"accountId":99999,"security":"IBM","side":"Buy","quantity":10,"limitPrice":200}'
  order "price collar"        REJECTED·PRICE_COLLAR    '{"accountId":22214,"security":"IBM","side":"Buy","quantity":10,"limitPrice":400}'
  order "max order size"      REJECTED·ORDER_SIZE      '{"accountId":22214,"security":"IBM","side":"Buy","quantity":2000000,"limitPrice":200}'
}

vlog "   endpoint: ${U}" "   mode:     ${MODE}" ""
case "${MODE}" in
  restriction) restriction ;;
  killswitch)  killswitch ;;
  controls)    controls ;;
  all)         controls; echo; restriction; echo; killswitch ;;
  *) echo "usage: $0 [-v] [restriction|killswitch|controls|all]"; exit 1 ;;
esac

echo
# A run that asserted nothing must not report success. Without this a selector that matched no
# steps -- or a future edit that drops a call -- exits 0 with an empty transcript, which is the
# exact defect this rewrite removes: silence reading as a pass.
if (( CHECKS == 0 )); then
  echo "[fail] no checks ran — nothing was asserted, so this is not a pass"
  exit 1
fi
if (( FAILED > 0 )); then
  echo "[FAIL] ${FAILED} of ${CHECKS} checks did not match the expected verdict"
  exit 1
fi
echo "[ok] ${CHECKS}/${CHECKS} checks matched"
