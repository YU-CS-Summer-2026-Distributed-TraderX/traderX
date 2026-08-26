#!/usr/bin/env bash
# yu17-fine-grid.sh — format-8: the GRID itself moves, not just the band (design §5 row 3).
#
# THE DISCRIMINATING PROBE: a FNMA limit at <cents>+$0.00001 — e.g. $1.13001 = 1 130 010 Px —
# is %10 == 0 (ON the new tick-10 grid) and %1000 != 0 (OFF the old tick-1000 grid). One price,
# opposite verdicts:
#
#   EXPECT=before (current build): refused kind 2 INVALID (off the global $0.001 grid) — a red
#     half that INVERTS: the refusal today is exactly what proves the grid, not merely the band,
#     moved when the same probe RESTS post-mint. Measured red 2026-08-25 on :yu17-markwait2
#     (orderRef 3626939, kind 2, INVALID).
#   EXPECT=after (format-8 build): the same probe RESTS (kind 1) — FNMA ($1-$10) holds tick 10 Px.
#
# FALSIFICATION ARMS, run on BOTH builds (a proof that can only pass proves nothing):
#   - sub-tick probe <cents>+$0.000001 (…001 Px, %10 == 1): refused INVALID on BOTH builds —
#     the fine grid is finite, not gone.
#   - negative control on a >=$100 equity (default NVDA): its grid is the map's cap (tick 1000,
#     unchanged — design §3), so an off-grid $0.0001 offset refuses INVALID and an on-cent limit
#     rests, identically on BOTH builds.
#
# WHICH GATE ANSWERED: engine ack reason byte 22 ("reason" field). INVALID here is the on-grid
# check (MatchingEngine.onGrid, before the band and before any reservation); PRICE_COLLAR or a
# risk reason in its place is a different gate and fails the proof.
#
# NON-DESTRUCTIVE: non-crossing resting limits (buys below the reference into an asserted-empty
# book), cancelled on the way out including on failure; occupied book = SKIP (exit 2).
set -euo pipefail
CTX="${CTX:-kind-traderx-yu12-cluster}"; NS="${NS:-traderx}"
K=(kubectl --context "${CTX}" -n "${NS}")
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
EXPECT="${EXPECT:-after}"
TICKER="${TICKER:-FNMA}"
# GOOGL, NOT NVDA (changed 2026-08-26). The control only has to be a >=$100 equity with a live
# publisher price and an EMPTY book, and since ADR-072 the tape replay keeps live depth in every
# symbol it trades — so NVDA is now permanently occupied and this proof SKIPPED every run, which
# reads as a pass in the suite summary. GOOGL is one of ADR-070's two deliberate tape exclusions
# (the store merges Alphabet's share classes), so it is priced by the publisher, sits in the cap
# grid's decade, and is a book the replay will never touch. If it ever stops being excluded, this
# control needs a different name — not a widened skip.
CONTROL_TICKER="${CONTROL_TICKER:-GOOGL}"
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
order() { # ticker side price
  curl -s -m20 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${ACCT},\"ticker\":\"$1\",\"side\":\"$2\",\"quantity\":10,\"limitPrice\":$3,\"clientOrderId\":\"$1-grid-$2-$3-$$\"}"; }
CLEANUP_REFS=()
cleanup() { local r; for r in ${CLEANUP_REFS[@]+"${CLEANUP_REFS[@]}"}; do
  curl -s -m20 -X POST "${MATCHER_URL}/cancel" -H 'Content-Type: application/json' -d "{\"orderRef\":${r}}" >/dev/null || true
done; }
trap cleanup EXIT
digest() { "${K[@]}" exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
  | awk '/^traderx_book_open_orders/ {d=$2} /^traderx_book_order_hash/ {h=$2} END {print d, h}'; }
cancel_ok() { local C; C="$(curl -s -m20 -X POST "${MATCHER_URL}/cancel" -H 'Content-Type: application/json' -d "{\"orderRef\":$1}")"
  [[ "$(field "${C}" canceled)" == "True" || "$(field "${C}" canceled)" == "true" ]] || fail "cancel of $1 did not take: ${C}"; }

echo "=== yu17-fine-grid, EXPECT=${EXPECT}, ticker ${TICKER}, control ${CONTROL_TICKER} ==="
REF="$(live_px "${TICKER}")"; CREF="$(live_px "${CONTROL_TICKER}")"
[[ "${REF}" =~ ^[0-9]+(\.[0-9]+)?$ ]] || fail "no live publisher price for ${TICKER} (got '${REF}')"
[[ "${CREF}" =~ ^[0-9]+(\.[0-9]+)?$ ]] || fail "no live publisher price for ${CONTROL_TICKER} (got '${CREF}')"
python3 -c "import sys; sys.exit(0 if 1 <= ${REF} < 10 else 1)" \
  || fail "${TICKER} reference ${REF} is outside [\$1,\$10) — the tick-10 claim no longer applies; probe prices need re-deriving"
python3 -c "import sys; sys.exit(0 if 100 <= ${CREF} < 1000 else 1)" \
  || fail "${CONTROL_TICKER} reference ${CREF} is outside [\$100,\$1000) — not a cap-grid control"
echo "    references: ${TICKER}=${REF}  ${CONTROL_TICKER}=${CREF}"

for t in "${TICKER}" "${CONTROL_TICKER}"; do
  ROW="$(bbo_row "${t}")"
  if [[ -n "${ROW}" && "${ROW}" != "0 0" ]]; then
    echo "[SKIP] ${t} book is occupied (bid/ask: ${ROW}) — probing a shared occupied book risks a"
    echo "       peer's resting order (ADR-066 re-anchor); re-run when quiet"
    exit 2
  fi
done

# Probe prices, derived from the LIVE reference so drift cannot carry them outside the new band —
# NEVER literals: the publisher walks and the collar follows the feed, so a hardcoded probe price
# is how this proof quietly stops discriminating. Do not "simplify" these to numbers.
#   P_FINE = cents(ref) + $0.00001  -> on tick-10, off tick-1000
#   P_SUB  = cents(ref) + $0.000001 -> off both grids
CENTS="$(python3 -c "print(f'{round(${REF},2):.2f}')")"
P_FINE="$(python3 -c "print(f'{${CENTS}+0.00001:.5f}')")"
P_SUB="$(python3 -c "print(f'{${CENTS}+0.000001:.6f}')")"

echo "--- discriminating probe: BUY ${TICKER} @${P_FINE} (Px %10==0, %1000!=0)"
FINE="$(order "${TICKER}" Buy "${P_FINE}")"
FINE_KIND="$(field "${FINE}" kind)"; FINE_REASON="$(field "${FINE}" reason)"
echo "    -> kind=${FINE_KIND} reason=${FINE_REASON:-<none>}"
case "${EXPECT}" in
  before)
    [[ "${FINE_KIND}" == "2" && "${FINE_REASON}" == "INVALID" ]] \
      || { [[ "${FINE_KIND}" == "1" ]] && CLEANUP_REFS+=("$(field "${FINE}" orderRef)"); \
           fail "pre-change build should refuse ${P_FINE} INVALID (off the \$0.001 grid); got kind=${FINE_KIND} reason=${FINE_REASON:-<none>} — already on the format-8 build? run EXPECT=after"; }
    ok "the inverting red: ${P_FINE} is INVALID today (ack reason byte: INVALID) — the grid it needs does not exist yet"
    ;;
  after)
    [[ "${FINE_KIND}" == "1" ]] \
      || fail "${P_FINE} was refused (kind=${FINE_KIND} reason=${FINE_REASON:-<none>}) — the format-8 price-derived grid must admit it at tick 10 (design §5)"
    CLEANUP_REFS+=("$(field "${FINE}" orderRef)")
    ROW="$(bbo_row "${TICKER}")"
    python3 -c "import sys; sys.exit(0 if abs(float('${ROW%% *}' or 0) - ${P_FINE}) < 1e-7 else 1)" 2>/dev/null \
      || fail "the fine-grid limit is not visibly resting on /bbo (row: '${ROW}')"
    cancel_ok "$(field "${FINE}" orderRef)"; CLEANUP_REFS=()
    ok "${P_FINE} rests on the new grid (and is cancelled)"
    ;;
  *) fail "EXPECT must be before|after" ;;
esac

echo "--- falsification arm (both builds): BUY ${TICKER} @${P_SUB} must be INVALID"
SUB="$(order "${TICKER}" Buy "${P_SUB}")"
SUB_KIND="$(field "${SUB}" kind)"; SUB_REASON="$(field "${SUB}" reason)"
[[ "${SUB_KIND}" == "2" && "${SUB_REASON}" == "INVALID" ]] \
  || { [[ "${SUB_KIND}" == "1" ]] && CLEANUP_REFS+=("$(field "${SUB}" orderRef)"); \
       fail "sub-tick ${P_SUB} must be INVALID on every build; got kind=${SUB_KIND} reason=${SUB_REASON:-<none>}"; }
ok "sub-tick ${P_SUB} refused INVALID — the fine grid is finite, not gone"

# Negative control: a >=$100 equity keeps the cap grid (tick 1000) on BOTH builds.
C_CENTS="$(python3 -c "print(f'{round(${CREF},2):.2f}')")"
C_OFF="$(python3 -c "print(f'{${C_CENTS}+0.0001:.4f}')")"
echo "--- negative control (both builds): ${CONTROL_TICKER} off-grid @${C_OFF} INVALID, on-cent @${C_CENTS} rests"
COFF="$(order "${CONTROL_TICKER}" Buy "${C_OFF}")"
[[ "$(field "${COFF}" kind)" == "2" && "$(field "${COFF}" reason)" == "INVALID" ]] \
  || { [[ "$(field "${COFF}" kind)" == "1" ]] && CLEANUP_REFS+=("$(field "${COFF}" orderRef)"); \
       fail "${CONTROL_TICKER} @${C_OFF} must stay INVALID on every build (the cap grid is unchanged); got ${COFF}"; }
CON="$(order "${CONTROL_TICKER}" Buy "${C_CENTS}")"
[[ "$(field "${CON}" kind)" == "1" ]] \
  || fail "${CONTROL_TICKER} on-cent @${C_CENTS} must rest on every build; got ${CON}"
CLEANUP_REFS+=("$(field "${CON}" orderRef)")
cancel_ok "$(field "${CON}" orderRef)"; CLEANUP_REFS=()
ok "${CONTROL_TICKER} grid unchanged both ways (off-grid INVALID, on-cent rests)"

for t in "${TICKER}" "${CONTROL_TICKER}"; do
  ROW="$(bbo_row "${t}")"
  [[ -z "${ROW}" || "${ROW}" == "0 0" ]] || fail "the ${t} book is not empty after cleanup (${ROW})"
done
ok "books restored empty"

for i in $(seq 1 30); do
  D0="$(digest 0)"; D1="$(digest 1)"; D2="$(digest 2)"
  if [[ "${D0}" =~ ^[0-9]+\ -?[0-9]+$ && "${D0}" == "${D1}" && "${D1}" == "${D2}" ]]; then
    ok "all three members agree on the book digest: ${D0}"
    exit 0
  fi
  sleep 2
done
fail "members never agreed on the book digest: [${D0}] [${D1}] [${D2}]"
