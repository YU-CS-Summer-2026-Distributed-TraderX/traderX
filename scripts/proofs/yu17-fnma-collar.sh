#!/usr/bin/env bash
# yu17-fnma-collar.sh — format-8 §7f: the collar must bind for a sub-$2 equity (FNMA).
#
# THE CLAIM (design: specs/YU17-otc-rates/system/format-8-price-derived-grid-design.md §5):
# a FNMA resting limit at ~20x the live feed reference (~$22 against ~$1.13) is refused
# PRICE_COLLAR by the ENGINE. Under the price-derived grid FNMA ($1-$10) holds tick 10 Px, so its
# half-band is 65536 x 10 Px = ±$0.655 — a 20x limit is ~30 bands out.
#
#   EXPECT=before (current build): the SAME probe is ACCEPTED and RESTS (kind 1) — the live §7f
#     defect: the global tick (1000 Px) gives every equity a ±$65.54 half-band, which admits a 20x
#     fat finger on a $1.13 instrument. Measured red 2026-08-25 on :yu17-markwait2
#     (orderRef 3626938, kind 1, rested, cancelled).
#   EXPECT=after  (format-8 build): kind 2, reason PRICE_COLLAR.
#
# WHICH GATE ANSWERED: the assertion is the engine ack's reason (byte 22, surfaced verbatim as the
# gateway's "reason" field), never an HTTP code. Both arms also run an INSIDE-BAND control that
# must REST — that is what proves an "after" refusal is the band and not UNKNOWN_SECURITY /
# SECURITY_DISABLED / a dead ticker (vacuous-pass-audit: a red for the wrong reason proves nothing).
#
# NON-DESTRUCTIVE BY DESIGN (scope §5 discipline): every probe is a non-crossing resting limit,
# cancelled on the way out including on the failure path. The FNMA book must be EMPTY before we
# start — a probe into an occupied shared book could trigger an ADR-066 re-anchor that
# stranded-cancels a PEER's resting order — so an occupied book is a SKIP (exit 2), not a pass.
set -euo pipefail
CTX="${CTX:-kind-traderx-yu12-cluster}"; NS="${NS:-traderx}"
K=(kubectl --context "${CTX}" -n "${NS}")
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
EXPECT="${EXPECT:-after}"   # the suite runs the post-mint claim; the mint chip runs before/after
TICKER="${TICKER:-FNMA}"
ACCT="${ACCT:-22214}"       # exists in reference data (see yu17-band-follows-market.sh)
fail() { echo "[FAIL] $*" >&2; exit 1; }
ok() { echo "[ok] $*"; }
field() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$2',''))" <<<"$1"; }

# Live feed reference, from the publisher — the same source the collar's feed-first reference
# tracks (ADR-066/ADR-068). Shape-tested: an unreachable publisher must not read as a price.
live_px() { "${K[@]}" exec deploy/price-publisher -- \
  wget -qO- "http://localhost:18100/prices/$1" 2>/dev/null \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['price'])"; }
# One member's /bbo row for a ticker: "<bid> <ask>" with 0 for an absent side, "" for no row.
bbo_row() { "${K[@]}" exec order-matcher-cluster-0 -- sh -c 'wget -qO- http://localhost:8080/bbo 2>/dev/null' \
  | python3 -c "
import sys,json
for b in json.load(sys.stdin)['books']:
    if b['ticker']=='$1':
        print(b.get('bid',0), b.get('ask',0)); break
"; }
order() { curl -s -m20 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
  -d "{\"accountId\":$1,\"ticker\":\"${TICKER}\",\"side\":\"$2\",\"quantity\":10,\"limitPrice\":$3,\"clientOrderId\":\"${TICKER}-collar-$2-$3-$$\"}"; }
CLEANUP_REFS=()
cleanup() { local r; for r in ${CLEANUP_REFS[@]+"${CLEANUP_REFS[@]}"}; do
  curl -s -m20 -X POST "${MATCHER_URL}/cancel" -H 'Content-Type: application/json' -d "{\"orderRef\":${r}}" >/dev/null || true
done; }
trap cleanup EXIT
digest() { "${K[@]}" exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
  | awk '/^traderx_book_open_orders/ {d=$2} /^traderx_book_order_hash/ {h=$2} END {print d, h}'; }

echo "=== yu17-fnma-collar, EXPECT=${EXPECT}, ticker ${TICKER} ==="
REF="$(live_px "${TICKER}")"
[[ "${REF}" =~ ^[0-9]+(\.[0-9]+)?$ ]] || fail "no live publisher price for ${TICKER} (got '${REF}')"
echo "    live feed reference: ${REF}"

ROW="$(bbo_row "${TICKER}")"
if [[ -n "${ROW}" && "${ROW}" != "0 0" ]]; then
  echo "[SKIP] ${TICKER} book is occupied (bid/ask: ${ROW}) — probing a shared occupied book can"
  echo "       stranded-cancel a peer's resting order via the ADR-066 re-anchor; re-run when quiet"
  exit 2
fi

# DERIVED FROM THE LIVE REFERENCE, NEVER A LITERAL. The publisher walks (FNMA moved 1.13 -> 1.164
# during one authoring session) and the collar follows the FEED, so a hardcoded probe price drifts
# out of the claim it encodes — a literal here is how this proof quietly stops discriminating. Do
# not "simplify" it to a number.
# Prices land on the CURRENT global grid ($0.001), which is also on every producible format-8 grid
# (every map tick divides 10 000 Px — design §2.1), so neither arm can trip on-grid by accident.
PX20="$(python3 -c "print(f'{round(${REF}*20,3):.3f}')")"
PX_IN="$(python3 -c "print(f'{max(round(${REF},3)-0.010,0.001):.3f}')")"
# The before-arm's meaning depends on 20x still being INSIDE today's ±$65.54 band: if the
# reference ever walks above ~$3.40 the probe would be refused on BOTH builds and discriminate
# nothing. Refuse to run rather than report a vacuous red.
python3 -c "import sys; sys.exit(0 if ${PX20} - ${REF} < 60 else 1)" \
  || fail "20x probe ${PX20} is outside today's global band too — the arm no longer discriminates"

# Inside-band control: must REST on both builds (|PX_IN - ref| ~ $0.01 << ±$0.655 new, ±$65.54 old).
CTRL="$(order "${ACCT}" Buy "${PX_IN}")"
CTRL_KIND="$(field "${CTRL}" kind)"
[[ "${CTRL_KIND}" == "1" ]] || fail "inside-band control BUY @${PX_IN} did not rest (kind=${CTRL_KIND} reason=$(field "${CTRL}" reason)): the ticker itself is not tradeable here, so nothing below can discriminate"
CTRL_REF="$(field "${CTRL}" orderRef)"; CLEANUP_REFS+=("${CTRL_REF}")
ok "inside-band control BUY @${PX_IN} rests (orderRef ${CTRL_REF}) — the ticker is live, the collar is the only gate left"

# The probe: a non-crossing SELL far above the market (the book held no bids; the control above is
# a BUY below the reference, which a SELL at 20x cannot reach).
PROBE="$(order "${ACCT}" Sell "${PX20}")"
PROBE_KIND="$(field "${PROBE}" kind)"; PROBE_REASON="$(field "${PROBE}" reason)"
echo "    SELL @${PX20} (20x) -> kind=${PROBE_KIND} reason=${PROBE_REASON:-<none>}"
case "${EXPECT}" in
  before)
    [[ "${PROBE_KIND}" != "3" && "${PROBE_KIND}" != "4" ]] || fail "the probe CROSSED (kind ${PROBE_KIND}) — the book was not empty; a trade entered the epoch: ${PROBE}"
    [[ "${PROBE_KIND}" == "1" ]] || fail "pre-change build should ACCEPT the 20x probe (the live defect); got kind=${PROBE_KIND} reason=${PROBE_REASON} — already on the format-8 build? run EXPECT=after"
    CLEANUP_REFS+=("$(field "${PROBE}" orderRef)")
    ROW="$(bbo_row "${TICKER}")"
    python3 -c "import sys; sys.exit(0 if abs(float('${ROW##* }' or 0) - ${PX20}) < 1e-6 else 1)" 2>/dev/null \
      || fail "the accepted 20x probe is not visibly resting on /bbo (row: '${ROW}', wanted ask ${PX20})"
    ok "the defect, live: a ${TICKER} limit at ${PX20} (~20x the ${REF} reference) RESTS today (ack reason byte: none — accepted)"
    ;;
  after)
    [[ "${PROBE_KIND}" == "2" ]] || { CLEANUP_REFS+=("$(field "${PROBE}" orderRef)"); fail "the 20x probe was ACCEPTED (kind=${PROBE_KIND}) — the format-8 collar must refuse it (design §5). ${PROBE}"; }
    [[ "${PROBE_REASON}" == "PRICE_COLLAR" ]] \
      || fail "refused, but by the WRONG gate: engine ack reason byte says ${PROBE_REASON:-<none>}, not PRICE_COLLAR — a vacuous red, not the band"
    ok "the 20x probe is refused PRICE_COLLAR by the engine — the band binds at ±\$0.655"
    ;;
  *) fail "EXPECT must be before|after" ;;
esac

# Cancel what rested (control, plus the before-arm probe), verifying each cancel took.
for r in ${CLEANUP_REFS[@]+"${CLEANUP_REFS[@]}"}; do
  C="$(curl -s -m20 -X POST "${MATCHER_URL}/cancel" -H 'Content-Type: application/json' -d "{\"orderRef\":${r}}")"
  [[ "$(field "${C}" canceled)" == "True" || "$(field "${C}" canceled)" == "true" ]] || fail "cleanup cancel of ${r} did not take: ${C}"
done
CLEANUP_REFS=()
ROW="$(bbo_row "${TICKER}")"
[[ -z "${ROW}" || "${ROW}" == "0 0" ]] || fail "the ${TICKER} book is not empty after cleanup (${ROW})"
ok "book restored empty"

# Three members, one answer — the probes must have left every member identical.
for i in $(seq 1 30); do
  D0="$(digest 0)"; D1="$(digest 1)"; D2="$(digest 2)"
  if [[ "${D0}" =~ ^[0-9]+\ -?[0-9]+$ && "${D0}" == "${D1}" && "${D1}" == "${D2}" ]]; then
    ok "all three members agree on the book digest: ${D0}"
    exit 0
  fi
  sleep 2
done
fail "members never agreed on the book digest: [${D0}] [${D1}] [${D2}]"
