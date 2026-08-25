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
  local r rc st rs got
  CHECKS=$((CHECKS + 1))
  vlog "      → POST ${U}/orders" "        ${3}" "        expect: ${2//·/ · }"
  r=$(curl -s -m8 "$U/orders" -H "Content-Type: application/json" -d "$3"); rc=$?
  vlog "      ← ${r:-<empty>}"
  # An empty body means nothing answered at $U at all, NOT a risk verdict. curl's rc says which
  # transport failure it was; what should be listening there is a fact about the rig, so this
  # prints the rc and the URL and leaves the remedy to whoever knows which rig they are on.
  # Reported separately on purpose: the whole failure class this proof exists to catch is a
  # rejection reason being wrong, and a connection problem rendered as "expected REJECTED, got
  # nothing" would read as a gateway defect. Say which one it is.
  if [[ -z "${r}" ]]; then
    printf "   %-26s ✘ UNREACHABLE — no response from %s (curl rc=%s; 7=nothing listening, 28=timed out)\n" "$1" "$U" "$rc"
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
# BAC IS PRICED OFF THE LIVE FEED, NOT A LITERAL. It used to rest at a literal 40, seeded at 40.
# That was safe only while the band was +/-$65.54 wide. Format 8 derives BAC's grid from its own
# reference (~$43 -> tick 100), which narrows the band to +/-$6.55, and the feed adapter has been
# re-sequencing the publisher's BAC since 2026-08-24 -- so the literal became a bet that the
# publisher's walk stays inside [33.45, 46.55], about $4.85 of headroom against the committed
# close of 41.70. A coin flip per week, and it would surface as a PRICE_COLLAR rejection on the
# arm that expects NEW: the collar being right, blamed on the restriction control.
# (specs/YU17-otc-rates/system/format-8-producer-sweep.md section 1)
#
# Reading the publisher directly is the same source seed-proof-fixtures.sh's live_px uses, via the
# 18100 forward run-proofs.sh owns. Standalone with no forward it falls back to the old literal,
# which is no worse than what this line said before.
PUB=${PRICE_PUBLISHER_URL:-http://localhost:18100}
BAC_PX="$(curl -s -m8 "$PUB/prices" 2>/dev/null \
  | python3 -c 'import sys,json
try:
    for q in json.load(sys.stdin)["prices"]:
        if q["ticker"] == "BAC" and q.get("price", 0) > 0:
            print(q["price"]); break
    else:
        print(40)
except Exception:
    print(40)' 2>/dev/null)"
[[ "${BAC_PX}" =~ ^[0-9.]+$ ]] || BAC_PX=40
BAC="{\"accountId\":52355,\"security\":\"BAC\",\"side\":\"Buy\",\"quantity\":11,\"limitPrice\":${BAC_PX}}"
IBM='{"accountId":22214,"security":"IBM","side":"Buy","quantity":10,"limitPrice":190}'

# On the cluster tier an account and a security only exist once they have been sequenced, and an
# order for one that has not been is rejected UNKNOWN_ACCOUNT / UNKNOWN_SECURITY before any risk
# control is consulted -- which would make every line below read like a rejection the demo caused.
# /seed is idempotent and is the same sequenced control path the proof then exercises, so seeding
# here costs nothing and makes the script self-contained on a fresh rig. Skipped silently if the
# endpoint is absent (the Spring matcher seeds from its database instead).
# Each ticker is seeded AT THE PRICE THE PROOF TRADES IT. This used to seed BAC at 200 alongside
# IBM and then order BAC at 40, which worked only because the band ignored the seed and anchored
# on the first limit. Since ADR-066 the band is centred on the seeded reference, so a BAC seeded
# at 200 refuses 40 as PRICE_COLLAR (160 off) — which is the collar being right about a wrong seed.
#
# CORRECTED 2026-08-25 (format-8 mint): the sentence above said the seed PINS the reference. The
# feed adapter revoked that on 2026-08-24 — it re-sequences the publisher's price within one flush,
# so for a ticker the publisher quotes, the seed is a starting point the feed then walks away from.
# That is why BAC is priced off the publisher above rather than seeded at a number of our choosing;
# IBM at 200 survives because the mint leaves its ±$65.54 band alone and Δ ≤ 15.4 fits inside it.
for acct in 52355 22214; do
  for pair in IBM:200 "BAC:${BAC_PX}"; do
    curl -s -m8 -o /dev/null -X POST "$U/seed" -H "Content-Type: application/json" \
      -d "{\"accountId\":$acct,\"tickers\":\"${pair%%:*}\",\"price\":${pair##*:}}" || true
  done
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
