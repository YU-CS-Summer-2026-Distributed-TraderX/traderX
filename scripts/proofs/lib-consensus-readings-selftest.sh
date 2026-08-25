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
echo "ALL GREEN"
