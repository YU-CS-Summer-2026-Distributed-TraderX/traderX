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
# ONLY place a proof should get them from. Two exist today:
#
#   order-shaped commands   -> quiesced_order_refs   (the ORDER_NEW generator; ticks never touch it)
#   OTC bookings            -> the contract id ITSELF is the consensus sequence it landed at
#
# If you need a third, add it here with the same test: name a counter the feed adapter does not
# advance, and show it standing still on a live rig while `applied` climbs.
# ============================================================================================
#
# Sourcing: `here="$(cd "$(dirname "$0")" && pwd)"; . "$here/lib-consensus-readings.sh"`
# Requires the sourcing script to define `fail()` and `K` (its kubectl prefix). Both are resolved
# at CALL time, so the source line may sit anywhere above the first use.

# --- raw per-member readings -----------------------------------------------------------------

# The applied sequence off a MEMBER's health port. Port 8080 is the member; 18110 is the
# gateway's REST port, whose /health answers {"connected":true} and has no opinion about
# consensus — asking it there makes every sequence assertion vacuous.
#
# GLOBAL. Use it as a window BRACKET (see assert_sequenced_in_window), never as a delta.
applied_seq() { # applied_seq <member-ordinal>
  ${K} exec "order-matcher-cluster-${1}" -- wget -qO- localhost:8080/health 2>/dev/null \
    | python3 -c 'import sys,json;print(json.load(sys.stdin).get("applied", -1))' 2>/dev/null || echo -1
}

# The ORDER_NEW ref generator. MatchingEngineClusteredService advances `nextOrderRef` on
# TYPE_ORDER_NEW and on nothing else — a PRICE_TICK, an FX rate, a symbol register and an OTC
# booking all leave it alone. So its delta counts order-shaped commands that reached consensus,
# and only those. Replicated state, so it is deterministic on every member and across replay.
order_refs_issued() { # order_refs_issued <member-ordinal>
  ${K} exec "order-matcher-cluster-${1}" -- wget -qO- localhost:8080/metrics 2>/dev/null \
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
