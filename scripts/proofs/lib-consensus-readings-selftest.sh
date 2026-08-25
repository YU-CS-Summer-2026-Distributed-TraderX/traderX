#!/usr/bin/env bash
# lib-consensus-readings-selftest.sh — the predicates in lib-consensus-readings.sh decide whether
# three proofs pass, so they get their own check: every arm below asserts the predicate goes RED
# on a genuinely false property and GREEN on a true one. Offline — no rig, no cluster, no kubectl.
# Run it after touching the lib:  ./scripts/proofs/lib-consensus-readings-selftest.sh
#
# The numbers are real readings from kind-traderx-yu12-cluster on 2026-08-24.
fail() { echo "  [FAIL] $*" >&2; exit 1; }
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/lib-consensus-readings.sh"
run() { local want="$1" name="$2"; shift 2
  if ( "$@" ) >/dev/null 2>&1; then got=green; else got=red; fi
  [[ "$got" == "$want" ]] && echo "ok   $name ($got)" || { echo "BAD  $name: want $want got $got"; exit 1; }
}
run green "two ids in window"        assert_sequenced_in_window 100 200 eur=SWPT-150 ber=SWPT-151
run green "far apart, feed between"  assert_sequenced_in_window 100 200 eur=SWPT-110 ber=SWPT-199
run red   "same id twice"            assert_sequenced_in_window 100 200 eur=SWPT-150 ber=SWPT-150
run red   "reversed order"           assert_sequenced_in_window 100 200 eur=SWPT-151 ber=SWPT-150
run red   "id below window"          assert_sequenced_in_window 100 200 eur=SWPT-99  ber=SWPT-150
run red   "id above window"          assert_sequenced_in_window 100 200 eur=SWPT-150 ber=SWPT-201
run red   "empty contract id"        assert_sequenced_in_window 100 200 eur=
run red   "non-numeric bracket"      assert_sequenced_in_window "" 200 eur=SWPT-150

CSV='SW-90,22214,PAY_FIXED,1,0.04
SWPT-150,22214,PAY_FIXED,1,0.04
SWPT-151,22214,PAY_FIXED,1,0.04'
run green "nothing in refusal window" assert_no_contracts_in_window 100 120 "$CSV" "the Asian booking"
run red   "a contract in the window"  assert_no_contracts_in_window 149 152 "$CSV" "the Asian booking"
run red   "empty artifact"            assert_no_contracts_in_window 100 120 "" "the Asian booking"
# The vacuity guard, and the exact shape that caught it: a double-prefixed id matches no pattern,
# so without the guard an artifact holding two contracts reports "none in the window" and passes.
run red   "unrecognised id format"    assert_no_contracts_in_window 100 200 "SWPT-SWPT-150,22214,PAY_FIXED" "x"
run red   "artifact with no id column" assert_no_contracts_in_window 100 200 "just,some,text" "x"
run green "boundary lo is exclusive"  assert_no_contracts_in_window 150 150 "$CSV" "x"
run red   "boundary hi is inclusive"  assert_no_contracts_in_window 149 150 "$CSV" "x"

run green "order refs unmoved"        assert_no_orders_sequenced 3629333 3629333 "face 50"
run red   "order refs moved"          assert_no_orders_sequenced 3629333 3629334 "face 50"

# assert_order_effects <refs0> <refs1> <orders> <trades0> <trades1> <legs> <what>
# Real brackets: refs 3629345, trades 3627116 (kind rig, 2026-08-25).
run green "2 queued, nothing traded"  assert_order_effects 3629345 3629347 2 3627116 3627116 0 "the queue"
run green "2 sent, one match (+2)"    assert_order_effects 3629345 3629347 2 3627116 3627118 2 "the cross"
run red   "queued orders TRADED"      assert_order_effects 3629345 3629347 2 3627116 3627118 0 "the queue"
run red   "the match never happened"  assert_order_effects 3629345 3629347 2 3627116 3627116 2 "the cross"
# The bracket that makes the trade reading attributable. A foreign order in the window must be a
# hard failure, NOT an accepted drift — with the ref delta wrong, a 0 trade delta says nothing.
run red   "a foreign order slipped in" assert_order_effects 3629345 3629351 2 3627116 3627116 0 "the queue"
run red   "our order never sequenced"  assert_order_effects 3629345 3629345 2 3627116 3627116 0 "the queue"
# ...and the vacuity guard: an unreadable counter must be loud, never "0 == 0, green".
run red   "counter unreadable (-1)"    assert_order_effects 3629345 -1 2 3627116 3627116 0 "the queue"
run red   "counter unreadable (empty)" assert_order_effects 3629345 "" 2 3627116 3627116 0 "the queue"

# assert_band_effects <r0> <c0> <r1> <c1> <reanchors> <strands> <what>
# THE VACUITY THAT WAS LIVE, as a standing red arm. reanchors=1 / stranded=3 is what the rig read
# on 2026-08-25 BEFORE yu17-band-follows-market.sh did anything; its old assertion (R1>=1 && C1>=1)
# was already satisfied by those absolutes and could not fail. Against the delta it fails, loudly.
run red   "old vacuity: nothing moved" assert_band_effects 1 3 1 3 1 1 "the re-anchor"
run green "one re-anchor, one strand"  assert_band_effects 1 3 2 4 1 1 "the re-anchor"
run green "pre-change arm: no movement" assert_band_effects 1 3 1 3 0 0 "the pinned band"
run red   "re-anchored twice"          assert_band_effects 1 3 3 4 1 1 "the re-anchor"
run red   "re-anchor stranded nothing" assert_band_effects 1 3 2 3 1 1 "the re-anchor"
run red   "counter absent from metrics" assert_band_effects 1 3 -1 -1 1 1 "the re-anchor"
echo "ALL GREEN"
