#!/usr/bin/env bash
# yu17-option-collar.sh — scope §5 (unchanged by the design): the collar must bind for listed
# options priced off their own live premium.
#
# THE CLAIM (design §5 / scope §5): an option limit at ~20x the live premium is refused
# PRICE_COLLAR by the ENGINE, at a qty that clears every other gate (qty 1; the bond lot rule
# cannot fire on an OCC ticker, the risk size/notional caps are effectively unlimited, and on the
# cluster tier there is NO gateway price screen to mask the engine — scope §0.3).
# Under the price-derived grid the book's tick derives from the premium itself (no
# OPTION_BOOK_TICK_PX constant ships — design §2.1/§8): a $0.22 premium gives tick 1 Px,
# half-band ±$0.0655.
#
#   EXPECT=before (current build): the SAME probe is ACCEPTED and RESTS (kind 1) — the filed
#     issue's one surviving non-equity row, live: every option book sits on the global grid, so
#     the cheapest premium gets a ±$65.54 band (~130x). Measured red 2026-08-25 on
#     :yu17-markwait2 (orderRef 3626940, kind 1, rested, cancelled).
#   EXPECT=after  (format-8 build): kind 2, reason PRICE_COLLAR.
#
# WHICH GATE ANSWERED: engine ack reason byte 22 (the gateway's "reason" field), never an HTTP
# code. Both arms run an inside-band control that must REST (vacuous-red guard: an "after" refusal
# for UNKNOWN_SECURITY or SECURITY_DISABLED proves nothing about the band).
#
# NON-DESTRUCTIVE: non-crossing resting probes, cancelled on the way out including on failure;
# an occupied shared book is a SKIP, not a pass (re-anchor could stranded-cancel a peer's order).
#
# OPT defaults to the cheapest fixture option (premium ~$0.22 live), where the defect multiple is
# largest. A ~$30-premium option would put 20x OUTSIDE today's ±$65.54 band and the before arm
# would stop discriminating — the guard below refuses that rather than reporting a vacuous red.
set -euo pipefail
CTX="${CTX:-kind-traderx-yu12-cluster}"; NS="${NS:-traderx}"
K=(kubectl --context "${CTX}" -n "${NS}")
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
EXPECT="${EXPECT:-after}"
OPT="${OPT:-AAPL260918P00220000}"
ACCT="${ACCT:-22214}"
fail() { echo "[FAIL] $*" >&2; exit 1; }
ok() { echo "[ok] $*"; }
field() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$2',''))" <<<"$1"; }
live_px() { "${K[@]}" exec deploy/price-publisher -- \
  wget -qO- "http://localhost:18100/prices/$1" 2>/dev/null \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['price'])"; }
bbo_row() { "${K[@]}" exec order-matcher-cluster-0 -- sh -c 'wget -qO- http://localhost:8080/bbo 2>/dev/null' \
  | python3 -c "
import sys,json
for b in json.load(sys.stdin)['books']:
    if b['ticker']=='$1':
        print(b.get('bid',0), b.get('ask',0)); break
"; }
order() { curl -s -m20 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
  -d "{\"accountId\":$1,\"ticker\":\"${OPT}\",\"side\":\"$2\",\"quantity\":1,\"limitPrice\":$3,\"clientOrderId\":\"${OPT}-collar-$2-$3-$$\"}"; }
CLEANUP_REFS=()
cleanup() { local r; for r in ${CLEANUP_REFS[@]+"${CLEANUP_REFS[@]}"}; do
  curl -s -m20 -X POST "${MATCHER_URL}/cancel" -H 'Content-Type: application/json' -d "{\"orderRef\":${r}}" >/dev/null || true
done; }
trap cleanup EXIT
digest() { "${K[@]}" exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
  | awk '/^traderx_book_open_orders/ {d=$2} /^traderx_book_order_hash/ {h=$2} END {print d, h}'; }

echo "=== yu17-option-collar, EXPECT=${EXPECT}, option ${OPT} ==="
PREM="$(live_px "${OPT}")"
[[ "${PREM}" =~ ^[0-9]+(\.[0-9]+)?$ ]] || fail "no live publisher premium for ${OPT} (got '${PREM}')"
echo "    live premium: ${PREM}"

ROW="$(bbo_row "${OPT}")"
if [[ -n "${ROW}" && "${ROW}" != "0 0" ]]; then
  echo "[SKIP] ${OPT} book is occupied (bid/ask: ${ROW}) — a probe could stranded-cancel a peer's"
  echo "       resting order via the ADR-066 re-anchor; re-run when quiet"
  exit 2
fi

# DERIVED FROM THE LIVE PREMIUM, NEVER A LITERAL. Option premiums are a black-scholes walk off a
# simulated underlying — this one moved 0.22 -> 0.63 during one authoring session — and the collar
# follows the feed, so a hardcoded probe price drifts out of the claim it encodes. Do not
# "simplify" this to a number.
PX20="$(python3 -c "print(f'{round(${PREM}*20,3):.3f}')")"
# Inside-band control ABOVE the premium (a second SELL, so it cannot cross anything the probe
# rests either): +$0.010 stays inside the tightest possible new band (±$0.0655 at tick 1).
PX_IN="$(python3 -c "print(f'{round(${PREM},3)+0.010:.3f}')")"
python3 -c "import sys; sys.exit(0 if ${PX20} - ${PREM} < 60 else 1)" \
  || fail "20x of premium ${PREM} is outside today's ±\$65.54 band too — pick a cheaper OPT; this arm no longer discriminates"

CTRL="$(order "${ACCT}" Sell "${PX_IN}")"
CTRL_KIND="$(field "${CTRL}" kind)"
[[ "${CTRL_KIND}" == "1" ]] || fail "inside-band control SELL @${PX_IN} did not rest (kind=${CTRL_KIND} reason=$(field "${CTRL}" reason)): the option is not tradeable here, nothing below can discriminate"
CTRL_REF="$(field "${CTRL}" orderRef)"; CLEANUP_REFS+=("${CTRL_REF}")
ok "inside-band control SELL @${PX_IN} rests (orderRef ${CTRL_REF})"

PROBE="$(order "${ACCT}" Sell "${PX20}")"
PROBE_KIND="$(field "${PROBE}" kind)"; PROBE_REASON="$(field "${PROBE}" reason)"
echo "    SELL @${PX20} (20x premium) -> kind=${PROBE_KIND} reason=${PROBE_REASON:-<none>}"
case "${EXPECT}" in
  before)
    [[ "${PROBE_KIND}" != "3" && "${PROBE_KIND}" != "4" ]] || fail "the probe CROSSED (kind ${PROBE_KIND}) — a trade entered the epoch: ${PROBE}"
    [[ "${PROBE_KIND}" == "1" ]] || fail "pre-change build should ACCEPT the 20x probe (the live defect); got kind=${PROBE_KIND} reason=${PROBE_REASON} — already on the format-8 build? run EXPECT=after"
    CLEANUP_REFS+=("$(field "${PROBE}" orderRef)")
    ok "the defect, live: ${OPT} limit at ${PX20} (~20x the ${PREM} premium) RESTS today (ack reason byte: none — accepted)"
    ;;
  after)
    [[ "${PROBE_KIND}" == "2" ]] || { CLEANUP_REFS+=("$(field "${PROBE}" orderRef)"); fail "the 20x probe was ACCEPTED (kind=${PROBE_KIND}) — the format-8 collar must refuse it (design §5). ${PROBE}"; }
    [[ "${PROBE_REASON}" == "PRICE_COLLAR" ]] \
      || fail "refused, but by the WRONG gate: engine ack reason byte says ${PROBE_REASON:-<none>}, not PRICE_COLLAR — a vacuous red"
    ok "the 20x probe is refused PRICE_COLLAR by the engine — the premium-derived band binds"
    ;;
  *) fail "EXPECT must be before|after" ;;
esac

for r in ${CLEANUP_REFS[@]+"${CLEANUP_REFS[@]}"}; do
  C="$(curl -s -m20 -X POST "${MATCHER_URL}/cancel" -H 'Content-Type: application/json' -d "{\"orderRef\":${r}}")"
  [[ "$(field "${C}" canceled)" == "True" || "$(field "${C}" canceled)" == "true" ]] || fail "cleanup cancel of ${r} did not take: ${C}"
done
CLEANUP_REFS=()
ROW="$(bbo_row "${OPT}")"
[[ -z "${ROW}" || "${ROW}" == "0 0" ]] || fail "the ${OPT} book is not empty after cleanup (${ROW})"
ok "book restored empty"

for i in $(seq 1 30); do
  D0="$(digest 0)"; D1="$(digest 1)"; D2="$(digest 2)"
  if [[ "${D0}" =~ ^[0-9]+\ -?[0-9]+$ && "${D0}" == "${D1}" && "${D1}" == "${D2}" ]]; then
    ok "all three members agree on the book digest: ${D0}"
    exit 0
  fi
  sleep 2
done
fail "members never agreed on the book digest: [${D0}] [${D1}] [${D2}]"
