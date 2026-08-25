#!/usr/bin/env bash
# lib-consensus-readings.sh — how a proof reads "did MY command reach consensus?"
#
# ============================================================================================
# READ THIS BEFORE YOU ASSERT ANYTHING ABOUT THE APPLIED SEQUENCE.
#
# The applied sequence (`applied` on a member's /health, traderx_cluster_applied on /metrics) is
# a GLOBAL counter. It counts every command any writer sequences through consensus, and this
# cluster has had more than one writer since the feed adapter went live (2026-08-24): the adapter
# holds its own AeronCluster session and offers PRICE_TICK directly, never through the gateway.
# One price-publisher flush is 69 symbols, so 69 sequences with nothing of yours in them.
#
#   Measured 2026-08-24 on kind-traderx-yu12-cluster, idle, no proof running:
#     applied 3937958 -> 3938027 over ~20s   (+69, one flush)
#     next_order_ref  3629333 -> 3629333     (unmoved across the same window)
#
# So:
#   * `AFTER - BEFORE == 2` is NOT "my two bookings were sequenced". It is "the cluster applied
#     two commands", which is a statement about the feed.
#   * `AFTER == BEFORE` is NOT "nothing of mine was sequenced". It is "no flush landed inside my
#     window", which is a statement about how fast my two HTTP calls were.
#   Neither reading was wrong when it was written. The environment grew a second writer and they
#   stopped being about the thing they name.
#
# Widening the tolerance (`>= 2`, "allow some drift") does not fix this — it deletes the check.
# The reading has to be one the feed cannot move. This file holds those readings, and it is the
# ONLY place a proof should get them from. Three exist today:
#
#   order-shaped commands   -> quiesced_order_refs   (the ORDER_NEW generator; ticks never touch it)
#   OTC bookings            -> the contract id ITSELF is the consensus sequence it landed at
#   what an order DID       -> assert_order_effects  (trades, bracketed by the ref generator so the
#                                                     trade delta is attributable to OUR orders)
#   ADR-066 band movement   -> assert_band_effects   (reanchors / stranded cancels, as DELTAS —
#                                                     they are lifetime counters, see the note there)
#
# If you need a fifth, add it here with the same test: name a counter the feed adapter does not
# advance, and show it standing still on a live rig while `applied` climbs.
# ============================================================================================
#
# Sourcing: `here="$(cd "$(dirname "$0")" && pwd)"; . "$here/lib-consensus-readings.sh"`
# Requires the sourcing script to define `fail()` and `K` (its kubectl prefix). Both are resolved
# at CALL time, so the source line may sit anywhere above the first use.
#
# `K` may be EITHER a string ("kubectl --context ... -n traderx", which these bash scripts
# word-split) OR an array (the ~/dev/lmax/CLAUDE.md preference — it survives a path with a space
# and is the only form zsh splits). Proofs in this directory use both. `_k` below resolves either,
# so the library never cares which its caller picked; a bare `${K}` here would silently collapse
# an array to `kubectl` and every reading would come back as the tool's usage text.
_k() {
  if [[ "$(declare -p K 2>/dev/null)" == "declare -"[aA]* ]]; then "${K[@]}" "$@"; else ${K} "$@"; fi
}

# --- raw per-member readings -----------------------------------------------------------------

# The applied sequence off a MEMBER's health port. Port 8080 is the member; 18110 is the
# gateway's REST port, whose /health answers {"connected":true} and has no opinion about
# consensus — asking it there makes every sequence assertion vacuous.
#
# GLOBAL. Use it as a window BRACKET (see assert_sequenced_in_window), never as a delta.
applied_seq() { # applied_seq <member-ordinal>
  _k exec "order-matcher-cluster-${1}" -- wget -qO- localhost:8080/health 2>/dev/null \
    | python3 -c 'import sys,json;print(json.load(sys.stdin).get("applied", -1))' 2>/dev/null || echo -1
}

# The ORDER_NEW ref generator. MatchingEngineClusteredService advances `nextOrderRef` on
# TYPE_ORDER_NEW and on nothing else — a PRICE_TICK, an FX rate, a symbol register and an OTC
# booking all leave it alone. So its delta counts order-shaped commands that reached consensus,
# and only those. Replicated state, so it is deterministic on every member and across replay.
order_refs_issued() { # order_refs_issued <member-ordinal>
  _k exec "order-matcher-cluster-${1}" -- wget -qO- localhost:8080/metrics 2>/dev/null \
    | awk 'index($1,"traderx_cluster_next_order_ref{")==1{print $2; found=1} END{if(!found) print -1}'
}

# --- quiesce -----------------------------------------------------------------------------------

# Read a counter only once ALL THREE members agree on it. Sampling one member races with catch-up:
# a member that has just restored from a snapshot reports the position it restored to while the
# others are already past it, and a delta measured across that gap is a statement about
# replication lag, not about how many commands were sequenced. (Observed: member 0 at 22927 with
# engineApplied -1 while 1 and 2 were at 22929.) Same quiesce rule the cross-member digest follows.
_agreed() { # _agreed <reader-fn> <what-it-is>
  local tries=0 a b c
  while (( tries < 60 )); do
    a="$("$1" 0)"; b="$("$1" 1)"; c="$("$1" 2)"
    if [[ "${a}" =~ ^[0-9]+$ && "${a}" == "${b}" && "${b}" == "${c}" ]]; then
      printf '%s' "${a}"; return 0
    fi
    tries=$((tries + 1)); sleep 2
  done
  fail "the three members never agreed on ${2} (last: ${a:-?} ${b:-?} ${c:-?})"
}

quiesced_seq()        { _agreed applied_seq        "an applied sequence"; }
quiesced_order_refs() { _agreed order_refs_issued  "an order-ref counter"; }

# --- the predicates a proof should actually assert ---------------------------------------------

# "No order-shaped command of mine reached consensus."
#
# Still fails when: a boundary-rejected order is sequenced anyway (it consumes a ref on apply,
# before any verdict), a retry is sequenced twice, an order leaks in from another writer on the
# gateway. Does not fail on: price ticks, FX rates, symbol registrations, OTC bookings — none of
# which are orders, which is the whole point.
assert_no_orders_sequenced() { # <before-refs> <after-refs> <what-was-supposed-to-be-refused>
  [[ "${2}" == "${1}" ]] \
    || fail "${3}: the order-ref generator moved ${1} -> ${2}, so $(( ${2} - ${1} )) order(s) reached
  consensus. Order refs are issued on apply, BEFORE any verdict, so a rejected order that was
  sequenced shows up here — which is exactly what must not happen."
}

# "Each of these bookings was sequenced through consensus, inside this window, as its own command."
#
# An OTC contract id IS the consensus sequence its booking was applied at — onSwapBook does
# `contractId = appliedSeq` precisely so the id needs no generator and no snapshot field of its
# own. So the ids returned to the caller ARE the evidence, and they are tied to THESE bookings in
# a way a global delta never was: `+2` would have passed just as happily if one booking and one
# unrelated command had landed, or if the feed had flushed two ticks and neither booking had been
# sequenced at all.
#
# Still fails when: an id was invented at the gateway rather than assigned by consensus (outside
# the window), two bookings collapsed onto one contract (the strict ascent), an id predates the
# window (a replayed or idempotency-echoed contract presented as new), the bookings landed out of
# submission order, or a booking answered 200 with no contract at all.
assert_sequenced_in_window() { # <before> <after> <label>=<contractId> ...
  local before="${1}" after="${2}"; shift 2
  local prev="${before}" pair label id
  [[ "${before}" =~ ^[0-9]+$ && "${after}" =~ ^[0-9]+$ ]] \
    || fail "window brackets are unreadable (${before} .. ${after})"
  for pair in "$@"; do
    label="${pair%%=*}"; id="${pair#*=}"
    id="${id##*-}"   # accept SW-1234 / SWPT-1234 as well as a bare sequence
    [[ "${id}" =~ ^[0-9]+$ ]] \
      || fail "${label} came back with contract id '${pair#*=}', which carries no consensus sequence"
    (( id > prev )) \
      || fail "${label} claims consensus sequence ${id}, which is not strictly after ${prev}. Either it
  was never sequenced in this window, or two bookings collapsed onto one contract."
    (( id <= after )) \
      || fail "${label} claims consensus sequence ${id}, past the ${after} all three members had applied
  when it answered. A contract id the cluster has not reached was not assigned by consensus."
    prev="${id}"
  done
}

# "No OTC contract was created in this window."
#
# The tick-insensitive form of "the boundary refused it and never sequenced it", for a booking
# that is supposed to die at the gateway. Ticks create no contracts, so the artifact's contract
# ids are a reading the feed cannot move. Read against the contracts artifact, which every OTC
# proof already pulls.
#
# Still fails when: a booking the boundary was supposed to refuse was sequenced and accepted, so
# a contract exists at a sequence inside the refused booking's window. Boundary: it cannot see a
# booking that was sequenced and then REFUSED past the boundary — but that case answers 422 with
# a reason (the risk-gate path), not the 400 the caller asserts alongside this.
assert_no_contracts_in_window() { # <before> <after> <contracts-csv> <what-was-supposed-to-be-refused>
  local before="${1}" after="${2}" csv="${3}" what="${4}" seen intruders
  [[ -n "${csv}" ]] || fail "assert_no_contracts_in_window got an empty artifact — it would prove nothing"
  # `seen` is the anti-vacuity guard, and it is not theoretical: while writing this, a caller
  # double-prefixed the ids into SWPT-SWPT-<n>, nothing matched the pattern, and "no contract in
  # the window" came back GREEN off an artifact that held two. An id shape this filter does not
  # recognise must be a loud probe failure, never a clean bill of health.
  seen="$(printf '%s\n' "${csv}" | awk -F, '$1 ~ /^(SW|SWPT)-[0-9]+$/' | wc -l | tr -d ' ')"
  [[ "${seen}" -gt 0 ]] \
    || fail "assert_no_contracts_in_window recognised no SW-/SWPT-<sequence> id anywhere in the artifact,
  so it cannot tell 'nothing was sequenced' from 'this filter does not understand the id format'.
  First column of the first data row: $(printf '%s\n' "${csv}" | awk -F, 'NR<=3{print $1}' | tr '\n' ' ')"
  intruders="$(printf '%s\n' "${csv}" | awk -F, -v lo="${before}" -v hi="${after}" \
    '$1 ~ /^(SW|SWPT)-[0-9]+$/ {
       n = $1; sub(/^[A-Z]+-/, "", n);
       if ((n + 0) > (lo + 0) && (n + 0) <= (hi + 0)) print $1
     }')"
  [[ -z "${intruders}" ]] \
    || fail "${what}: contract(s) $(printf '%s\n' "${intruders}" | tr '\n' ' ')exist at a consensus sequence
  inside (${before}, ${after}] — the window in which nothing of ours should have been sequenced."
}

# The venue's trade counter. PRICE_TICK books no trade, so — unlike `applied` — the feed adapter
# cannot move this one. Measured 2026-08-25 on kind-traderx-yu12-cluster, idle, no proof running:
#
#     applied 3945397 -> 3945530 -> 3945599   (+133 over ~50s, two flushes)
#     trades  3627116 -> 3627116 -> 3627116   (unmoved across the same window)
#     refs    3629345 -> 3629345 -> 3629345   (unmoved across the same window)
#
# STILL GLOBAL with respect to ORDER writers: the algo engine, another lane's proof, or a human
# with curl all book trades that land here. That is why the predicate below never reads it alone.
trades_booked() { # trades_booked <member-ordinal>
  _k exec "order-matcher-cluster-${1}" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
    | awk 'index($1,"traderx_cluster_trades{")==1 || $1=="traderx_cluster_trades" {print $2; found=1}
           END{if(!found) print -1}'
}
quiesced_trades() { _agreed trades_booked "a trade counter"; }

# "In this window, exactly MY orders were sequenced, and they had exactly THIS trade effect."
#
# The trade counter is feed-proof but not writer-proof, so it is never asserted on its own. The
# order-ref bracket is what makes the trade delta attributable: if `next_order_ref` moved by
# exactly the number of orders this proof submitted, then no other order-shaped command was
# sequenced inside the window, and the trade delta measured across it is therefore about THIS
# proof's orders. Either half alone is the vacuous form —
#   * trades alone: "0" reads as "my crossing orders were queued" when it may mean "the window
#     closed before my orders were applied", and "+2" reads as "my order matched" when a
#     concurrent writer's cross would say the same;
#   * refs alone: says the orders were sequenced, says nothing about what they did.
#
# traderx_cluster_trades counts ONE LEG PER SIDE, so one match is +2 (the same convention
# yu13-stp-and-replace reads as "6 vs 8" for one wash trade). Pass 0 for "nothing traded".
#
# Still fails when: a crossing order traded that should have been queued or refused (delta too
# high), a released order failed to trade (delta too low), an order of ours was refused before
# sequencing or retried into a second sequence (ref delta wrong), or another writer slipped an
# order into the window (ref delta wrong — the check that makes the trade delta mean anything).
assert_order_effects() { # <refs-before> <refs-after> <orders-submitted> <trades-before> <trades-after> <trade-legs-expected> <what>
  local rb="${1}" ra="${2}" n="${3}" tb="${4}" ta="${5}" legs="${6}" what="${7}"
  local rd td
  for v in "${rb}" "${ra}" "${tb}" "${ta}"; do
    [[ "${v}" =~ ^[0-9]+$ ]] || fail "${what}: unreadable counter bracket ('${v}') — a proof that
  cannot read the counters it asserts on has not measured anything"
  done
  rd=$(( ra - rb )); td=$(( ta - tb ))
  (( rd == n )) \
    || fail "${what}: the order-ref generator moved by ${rd}, not the ${n} order(s) this proof
  submitted (${rb} -> ${ra}). Until that matches, the trade delta below is not attributable to us:
  someone else's order is in the window (algo engine? another lane?), or one of ours was never
  sequenced. Do NOT widen this tolerance — it is what makes the trade reading mean anything."
  (( td == legs )) \
    || fail "${what}: trades moved by ${td} leg(s), expected ${legs} (${tb} -> ${ta}), across a window
  in which exactly our ${n} order(s) were sequenced. $( (( legs == 0 )) \
      && echo 'Something matched that was supposed to rest, queue, or be refused.' \
      || echo 'The match this proof exists to observe did not happen as specified.' )"
}

# The ADR-066 band counters. Also feed-proof, and for a stronger reason than the trade counter:
# `bandSlot` — the ONLY writer of either counter — is reached from order placement and replace
# alone (MatchingEngine:578, :1005). A PRICE_TICK moves the collar REFERENCE without ever entering
# it, so the feed can walk a mark all day and neither counter twitches. Verified by reading, and
# visible on the rig: reanchors=1 / stranded=3 have stood unchanged for days of live ticking.
#
# GLOBAL over order writers, exactly like the trade counter — which is the whole reason
# assert_band_effects takes a BASELINE and not a floor.
band_counters() { # band_counters <member-ordinal> -> "<reanchors> <stranded_cancels>"
  _k exec "order-matcher-cluster-${1}" -- sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
    | awk '/^traderx_band_reanchors[{ ]/ {r=$2} /^traderx_band_stranded_cancels[{ ]/ {c=$2}
           END {print (r==""?-1:r), (c==""?-1:c)}'
}

# "This scenario caused exactly THIS much band movement."
#
# WHY A BASELINE AND NOT A FLOOR — this is the defect this function exists to make unrepeatable.
# yu17-band-follows-market.sh asserted `R1 >= 1 && C1 >= 1` on the ABSOLUTE readings, captured a
# baseline it never compared to, and printed "member-0 counters did not move" on failure. These
# are lifetime counters on a long-lived epoch: the rig read reanchors=1 / stranded=3 BEFORE the
# proof did anything, so the assertion was already true on arrival and could never fail again.
# A movement test on a monotone counter is a delta or it is nothing.
#
# EXACT, not `>=`: the scenario's re-anchor count is a property of the scenario. `>= 1` would pass
# on two re-anchors, which would mean the band moved twice where the design says once.
assert_band_effects() { # <r-before> <c-before> <r-after> <c-after> <reanchors-expected> <strands-expected> <what>
  local rb="${1}" cb="${2}" ra="${3}" ca="${4}" wr="${5}" wc="${6}" what="${7}"
  local v
  for v in "${rb}" "${cb}" "${ra}" "${ca}"; do
    [[ "${v}" =~ ^[0-9]+$ ]] || fail "${what}: band counter unreadable ('${v}') — traderx_band_reanchors /
  traderx_band_stranded_cancels absent from /metrics means this build predates ADR-066 or the probe
  is pointed at the wrong port. Either way nothing below was measured."
  done
  (( ra - rb == wr )) \
    || fail "${what}: the band re-anchored $(( ra - rb )) time(s), expected ${wr} (reanchors ${rb} -> ${ra}).
  These are LIFETIME counters — the reading is the delta across this scenario, never the absolute."
  (( ca - cb == wc )) \
    || fail "${what}: ${wc} resting order(s) should have been stranded by the re-anchor, ${ca} - ${cb} =
  $(( ca - cb )) were (stranded_cancels ${cb} -> ${ca})."
}
