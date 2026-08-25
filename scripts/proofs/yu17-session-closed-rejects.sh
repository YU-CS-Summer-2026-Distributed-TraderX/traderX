#!/usr/bin/env bash
# yu17-session-closed-rejects.sh — format-8 §1.3 / ADR-069: while the session is CLOSED the venue's
# book refuses new orders, and it refuses them AS A SESSION REJECTION — not as a collar and not as
# a risk verdict (scope §5 row 4; decisions a, c, d of §7).
#
# THE CLAIM, four parts, all on one fresh ticker:
#   (a) a fresh epoch starts OPEN                      -> the phase is READABLE and reads OPEN
#   ( ) CLOSED refuses ORDER_NEW, reason MARKET_CLOSED -> engine ack byte 22, distinct from
#                                                         PRICE_COLLAR and from every risk reason
#   (c) a CANCEL is still ALLOWED while CLOSED         -> a cancel only ever reduces exposure
#   (d) an OTC swap booking does NOT halt with the session -> the halt is the venue's BOOK
#
# ARMS (chip-1 convention: EXPECT=after is the default, so the suite states the POST-MINT claim and
# is deliberately RED until the mint):
#   EXPECT=before (current build): there is no session at all. POST /session 404s and the member's
#     /health carries no `phase` — both RECORDED, not asserted, because "the API is missing" is not
#     the defect. The proof then PROCEEDS and records the observable defect: the identical probe
#     that must be refused is ACCEPTED, kind 1, and RESTS.
#   EXPECT=after  (format-8 build): kind 2, reason MARKET_CLOSED.
#
# WHY THE SAME PROBE RUNS TWICE. The probe is submitted ONCE WHILE OPEN (it must rest — that is the
# inside-band control) and then again after the CLOSE command. Identical account, ticker, side and
# price; only the phase differs. That is what makes an "after" refusal impossible to confuse with
# UNKNOWN_SECURITY / SECURITY_DISABLED / a collar (vacuous-pass-audit: a red for the wrong reason
# proves nothing), and it is why the reason BYTE is asserted rather than an HTTP code — the scope is
# explicit that each proof must name which gate answered.
#
# NON-DESTRUCTIVE (scope §5 discipline): a fresh ticker, non-crossing resting limits only, every
# one cancelled on the way out INCLUDING on the failure path, and the phase restored to OPEN in the
# same trap. Nothing crosses; no trade enters the epoch.
set -euo pipefail
CTX="${CTX:-kind-traderx-yu12-cluster}"; NS="${NS:-traderx}"
K=(kubectl --context "${CTX}" -n "${NS}")
MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
EXPECT="${EXPECT:-after}"
ACCT="${ACCT:-22214}"                       # exists in reference data (yu17-band-follows-market)
TICKER="${TICKER:-SES$(date +%H%M%S)}"
SEED_PX="${SEED_PX:-150}"
fail() { echo "[FAIL] $*" >&2; exit 1; }
ok() { echo "[ok] $*"; }
red() { echo "[RED] $*  <- EXPECTED RED until the format-8 mint (design §5)"; }
field() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$2',''))" <<<"$1"; }
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/lib-consensus-readings.sh"

# The phase, off the MEMBER's /health (scope §1.7: "was the market open, and is anything queued?"
# answerable in one request). Prints the empty string when the build has no phase at all — which is
# a RECORDED fact on the before arm, never a silent default.
phase_of() { # phase_of <member-ordinal>
  "${K[@]}" exec "order-matcher-cluster-${1}" -- wget -qO- localhost:8080/health 2>/dev/null \
    | python3 -c 'import sys,json;print(json.load(sys.stdin).get("phase",""))' 2>/dev/null || echo ""
}
# Issue the sequenced phase command through the gateway (scope §1.2: a human via POST /session).
# Returns "<http-code> <body>". Absent on any pre-mint build; the caller decides what that means.
set_phase() { # set_phase CLOSED|PRE_OPEN|OPEN
  curl -s -m20 -w ' %{http_code}' -X POST "${MATCHER_URL}/session" \
    -H 'Content-Type: application/json' -d "{\"phase\":\"$1\"}" 2>/dev/null \
    | awk '{code=$NF; $NF=""; print code, $0}'
}
order() { # order <side> <price> <tag>
  curl -s -m20 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${ACCT},\"ticker\":\"${TICKER}\",\"side\":\"$1\",\"quantity\":10,\"limitPrice\":$2,\"clientOrderId\":\"${TICKER}-$3-$$\"}"; }
cancel() { curl -s -m20 -X POST "${MATCHER_URL}/cancel" -H 'Content-Type: application/json' -d "{\"orderRef\":$1}"; }
digest() { "${K[@]}" exec "order-matcher-cluster-$1" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
  | awk '/^traderx_book_open_orders/ {d=$2} /^traderx_book_order_hash/ {h=$2} END {print d, h}'; }

CLEANUP_REFS=(); PHASE_TOUCHED=0
cleanup() {
  local r
  # Restore OPEN FIRST: a cancel is legal while CLOSED (decision c) but a leftover CLOSED venue is
  # the one thing this proof must never hand the next lane.
  (( PHASE_TOUCHED == 1 )) && { set_phase OPEN >/dev/null 2>&1 || true; }
  for r in ${CLEANUP_REFS[@]+"${CLEANUP_REFS[@]}"}; do cancel "${r}" >/dev/null 2>&1 || true; done
}
trap cleanup EXIT

echo "=== yu17-session-closed-rejects, EXPECT=${EXPECT}, ticker ${TICKER} ==="
for m in 0 1 2; do "${K[@]}" get pod "order-matcher-cluster-${m}" -o jsonpath='{.spec.containers[0].image}{"\n"}'; done | sort -u

# --- 0. decision (a): the phase is readable, and it is OPEN -----------------------------------
PHASE0="$(phase_of 0)"
echo "    member-0 /health phase: '${PHASE0:-<absent>}'"
case "${EXPECT}" in
  before)
    [[ -z "${PHASE0}" ]] || fail "this build already reports a phase ('${PHASE0}') — the mint has landed; run EXPECT=after"
    red "the member's /health carries NO phase field: scope §1.7's one-request question ('was the market open, and is anything queued?') cannot be asked on this build" ;;
  after)
    # Deliberately NOT a hard stop when the field is absent. Dying here would make this proof's red
    # half read "the API is missing", and an API-shaped red is worth nothing at the mint — the
    # observable defect is at step 3 and this arm must reach it. The phase must still be readable,
    # so the requirement is re-asserted at the end, where step 3's verdict has already been taken.
    if [[ -z "${PHASE0}" ]]; then
      red "the member's /health reports no phase: scope §1.7 requires phase and queueDepth beside /bbo. Continuing to the observable defect rather than stopping at a missing field."
    else
      [[ "${PHASE0}" == "OPEN" ]] || fail "decision (a): a fresh epoch must start OPEN, this venue reads '${PHASE0}'. If a previous proof left it halted, that is the finding"
      ok "decision (a): the venue reads phase=OPEN"
    fi ;;
  *) fail "EXPECT must be before|after" ;;
esac

# --- 1. the control: the identical probe RESTS while OPEN ---------------------------------------
# Everything below discriminates only against this. A refusal in step 3 that this step could not
# get accepted would be a dead ticker, not a session gate.
SEEDED="$(curl -s -m20 -o /dev/null -w '%{http_code}' -X POST "${MATCHER_URL}/seed" \
  -H 'Content-Type: application/json' -d "{\"accountId\":${ACCT},\"tickers\":\"${TICKER}\",\"price\":${SEED_PX}}")"
[[ "${SEEDED}" == 2* ]] || fail "seed of ${TICKER} @${SEED_PX} did not take (HTTP ${SEEDED})"
PROBE_PX="$(python3 -c "print(f'{${SEED_PX} + 1:.3f}')")"   # inside the band, above the (empty) book: cannot cross
CTRL="$(order Sell "${PROBE_PX}" ctrl)"
CTRL_KIND="$(field "${CTRL}" kind)"
[[ "${CTRL_KIND}" == "1" ]] \
  || fail "the control SELL @${PROBE_PX} did not rest while the venue was OPEN (kind=${CTRL_KIND} reason=$(field "${CTRL}" reason)): ${CTRL}. Nothing below can discriminate — fix the fixture, do not read this as a session rejection"
CTRL_REF="$(field "${CTRL}" orderRef)"; CLEANUP_REFS+=("${CTRL_REF}")
ok "control: SELL @${PROBE_PX} RESTS while OPEN (orderRef ${CTRL_REF}) — the ticker is live and the price is inside the band"

# --- 2. close the session -----------------------------------------------------------------------
RESP="$(set_phase CLOSED)"; CODE="${RESP%% *}"
echo "    POST /session {\"phase\":\"CLOSED\"} -> HTTP ${CODE} ${RESP#* }"
if [[ "${CODE}" == "2"* ]]; then
  PHASE_TOUCHED=1
  P="$(phase_of 0)"; [[ "${P}" == "CLOSED" ]] || fail "the close command answered ${CODE} but member-0 still reads phase='${P}'"
  ok "the venue is CLOSED (sequenced; member-0 /health agrees)"
else
  # BOTH arms record and proceed. Reporting red on a 404 proves the API is missing, not that the
  # behaviour is wrong — a vacuous red, worth nothing at the mint. The command's existence is
  # re-asserted at the end, after step 3 has taken the verdict that actually is the claim.
  red "POST /session is ABSENT on this build (HTTP ${CODE}) — recorded, NOT asserted: 'the API is missing' is not the defect. Proceeding to the observable one."
fi

# --- 3. the probe: the SAME order, now that the venue is supposed to be closed -------------------
PROBE="$(order Sell "${PROBE_PX}" probe)"
PROBE_KIND="$(field "${PROBE}" kind)"; PROBE_REASON="$(field "${PROBE}" reason)"
echo "    SELL @${PROBE_PX} (identical to the control) -> kind=${PROBE_KIND} reason=${PROBE_REASON:-<none>}"
case "${EXPECT}" in
  before)
    [[ "${PROBE_KIND}" != "3" && "${PROBE_KIND}" != "4" ]] || fail "the probe CROSSED (kind ${PROBE_KIND}) — a trade entered the epoch, which this proof must never do: ${PROBE}"
    [[ "${PROBE_KIND}" == "1" ]] || fail "pre-mint build should ACCEPT the probe (the live defect); got kind=${PROBE_KIND} reason=${PROBE_REASON}"
    CLEANUP_REFS+=("$(field "${PROBE}" orderRef)")
    red "THE OBSERVABLE DEFECT: with the venue supposed to be CLOSED, the probe was ACCEPTED, kind 1 (orderRef $(field "${PROBE}" orderRef)) and RESTS. Ack reason byte: none — accepted. Claim says MARKET_CLOSED." ;;
  after)
    [[ "${PROBE_KIND}" == "2" ]] || { CLEANUP_REFS+=("$(field "${PROBE}" orderRef)"); fail "the probe was ACCEPTED (kind=${PROBE_KIND}) while CLOSED — EXPECTED RED until the format-8 mint (design §5). ${PROBE}"; }
    [[ "${PROBE_REASON}" == "MARKET_CLOSED" ]] \
      || fail "refused, but by the WRONG gate: the engine ack reason byte says '${PROBE_REASON:-<none>}', not MARKET_CLOSED. The control at this exact price rested moments ago, so this is not the collar and not a risk cap — a session rejection that arrives wearing another gate's reason is the audit-surface defect ADR-069 forbids"
    ok "the probe is refused MARKET_CLOSED by the engine (ack byte 22), distinct from PRICE_COLLAR and from every risk reason" ;;
esac

# --- 4. decision (c): a CANCEL is allowed while CLOSED -------------------------------------------
# The control from step 1 is still resting. A cancel only ever REDUCES exposure, so the halt must
# not trap a client in it (§7c, which deliberately overrode the recommendation).
C="$(cancel "${CTRL_REF}")"
CANCELED="$(field "${C}" canceled)"
echo "    POST /cancel {orderRef:${CTRL_REF}} -> ${C}"
if [[ "${EXPECT}" == "after" ]]; then
  [[ "${CANCELED}" == "True" || "${CANCELED}" == "true" ]] \
    || fail "decision (c): the cancel of resting order ${CTRL_REF} was REFUSED while CLOSED (${C}). A cancel cannot cross, cannot trade and cannot re-open a halted book — forbidding it is strictly less safe than permitting it, and it locks the client into an order ADR-069 decision 7 may re-price at the open"
  ok "decision (c): the resting order was CANCELLED while the venue is CLOSED"
else
  [[ "${CANCELED}" == "True" || "${CANCELED}" == "true" ]] || fail "the control cancel failed on an OPEN venue (${C}) — a fixture problem, not a session verdict"
  red "decision (c) is NOT EXERCISED on this build: the cancel succeeded, but against a venue that was never closed, so it discriminates nothing"
fi
CLEANUP_REFS=("${CLEANUP_REFS[@]:1}")   # the control is gone; do not re-cancel it in the trap

# --- 5. decision (d): OTC bookings do NOT halt with the session ----------------------------------
# The halt is the VENUE'S BOOK; bilateral desk business never touches it (§7d). Untestable without
# a session, and a booking is a durable contract on a shared epoch — so on the before arm this is
# recorded as not-run rather than mutating the epoch to learn nothing.
if [[ "${EXPECT}" == "after" ]]; then
  BEFORE="$(quiesced_seq)"
  SW="$(curl -s -m20 -X POST "${MATCHER_URL}/swaps" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${ACCT},\"notional\":1000000,\"fixedRate\":0.04,\"payFixed\":true,\"currency\":\"USD\",\"tenorMonths\":60}")"
  AFTER="$(quiesced_seq)"
  CID="$(field "${SW}" contractId)"
  [[ -n "${CID}" ]] || fail "decision (d): the swap booking returned no contract while CLOSED (${SW}) — the session halted bilateral desk business, which §7d says it must not"
  assert_sequenced_in_window "${BEFORE}" "${AFTER}" "swap-while-closed=${CID}"
  ok "decision (d): a swap booked and reached consensus (contract ${CID}) while the venue's book is CLOSED"
else
  red "decision (d) is NOT EXERCISED on this build: with no session there is nothing for a booking to survive, and a contract is a durable mutation of a shared epoch — recorded as not-run rather than bought for nothing"
fi

# --- 6. restore, and three members one answer ----------------------------------------------------
if (( PHASE_TOUCHED == 1 )); then
  RESP="$(set_phase OPEN)"; [[ "${RESP%% *}" == "2"* ]] || fail "could not reopen the venue (HTTP ${RESP%% *}) — it must not be left CLOSED for the next lane"
  PHASE_TOUCHED=0
  P="$(phase_of 0)"; [[ "${P}" == "OPEN" ]] || fail "reopen answered 200 but member-0 reads phase='${P}'"
  ok "the venue is OPEN again"
fi
for r in ${CLEANUP_REFS[@]+"${CLEANUP_REFS[@]}"}; do
  C="$(cancel "${r}")"
  [[ "$(field "${C}" canceled)" == "True" || "$(field "${C}" canceled)" == "true" ]] || fail "cleanup cancel of ${r} did not take: ${C}"
done
CLEANUP_REFS=()
# Deferred from steps 0 and 2 (see there): the phase must be READABLE and the phase command must
# EXIST. Asserted here, so that step 3's order-level verdict — the actual claim — is always the red
# a reader sees first, and an API-shaped red can never stand in for it.
if [[ "${EXPECT}" == "after" ]]; then
  [[ -n "${PHASE0}" ]] \
    || fail "the member's /health reports no phase — EXPECTED RED until the format-8 mint (design §5): scope §1.7 requires phase and queueDepth beside /bbo, and decision (a) is unverifiable without it"
  (( PHASE_TOUCHED == 1 )) || [[ "${CODE}" == "2"* ]] \
    || fail "POST /session answered ${CODE} — EXPECTED RED until the format-8 mint (design §5): TYPE_SESSION_CONTROL and its gateway route do not exist on this build"
fi
for i in $(seq 1 30); do
  D0="$(digest 0)"; D1="$(digest 1)"; D2="$(digest 2)"
  if [[ "${D0}" =~ ^[0-9]+\ -?[0-9]+$ && "${D0}" == "${D1}" && "${D1}" == "${D2}" ]]; then
    ok "all three members agree on the book digest: ${D0}"
    [[ "${EXPECT}" == "before" ]] && { echo; echo "[RED] yu17-session-closed-rejects: the red half is BANKED. EXPECTED RED until the format-8 mint (design §5)."; }
    exit 0
  fi
  sleep 2
done
fail "members never agreed on the book digest: [${D0}] [${D1}] [${D2}]"
