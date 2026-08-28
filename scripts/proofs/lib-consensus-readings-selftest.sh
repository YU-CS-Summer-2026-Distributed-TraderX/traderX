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

# ---------------------------------------------------------------------------------------------
# ADR-072: THE READERS MUST TAKE THE OPERATOR METRIC, NOT THE GLOBAL ONE.
#
# Everything above tests the PREDICATES, which take numbers and never look at a rig. The thing
# ADR-072 actually changed is one level below that — WHICH LINE of /metrics each reader greps —
# and none of the arms above can see it. A reader silently reverted to the global counter would
# leave every predicate green and every proof measuring the tape replay.
#
# So: stub `_k` with a /metrics payload in which the global and operator values DIFFER, and
# require the operator one. The two numbers are the shape a live rig produces — the replay has
# been running, so the globals are far ahead.
_k() {
  cat <<'METRICS'
traderx_cluster_applied{member="0"} 41000
traderx_cluster_trades{member="0"} 900
traderx_cluster_operator_trades{member="0"} 6
traderx_cluster_next_order_ref{member="0"} 1500
traderx_cluster_operator_next_order_ref{member="0"} 8
traderx_cluster_external_order_refs{member="0"} 1491
traderx_band_reanchors{member="0"} 40
traderx_band_stranded_cancels{member="0"} 12
traderx_band_operator_reanchors{member="0"} 1
traderx_band_operator_stranded_cancels{member="0"} 3
METRICS
}
expect() { # expect <what> <want> <got>
  [[ "$3" == "$2" ]] && echo "ok   $1 (${3})" \
    || { echo "BAD  $1: want ${2} got ${3} — the reader is on the GLOBAL counter, which the ADR-072"
         echo "     replay moves continuously. Every proof sourcing this file is then measuring the tape."
         exit 1; }
}
expect "refs reader takes the operator line"   8 "$(order_refs_issued 0)"
expect "trades reader takes the operator line" 6 "$(trades_booked 0)"
expect "band reader takes the operator lines"  "1 3" "$(band_counters 0)"

# ...and the absence path. All three members answering -1 is agreement ON THE METRIC BEING ABSENT,
# which used to burn the full 60 x 2s budget and then report a disagreement that was not happening.
# A run against a pre-change build is a STEP in this project's proof procedure, so it has to be
# legible and fast.
_k() { echo "traderx_cluster_next_order_ref{member=\"0\"} 1500"; }   # pre-ADR-072 build
start=${SECONDS}
if ( _AGREE_TRIES=60 quiesced_order_refs ) >/dev/null 2>&1; then
  echo "BAD  a pre-ADR-072 build must be refused, not quiesced"; exit 1
fi
elapsed=$(( SECONDS - start ))
(( elapsed < 10 )) && echo "ok   pre-ADR-072 build refused immediately (${elapsed}s)" \
  || { echo "BAD  the absent-metric path waited ${elapsed}s; it must not retry an absence"; exit 1; }

# The give-up path still exists for a REAL disagreement (a member catching up), and still retries.
_disagree_calls=0
_k() { :; }
_disagreeing() { _disagree_calls=$((_disagree_calls + 1)); echo "$(( 100 + $1 ))"; }
if ( _AGREE_TRIES=2 _agreed _disagreeing "a test counter" ) >/dev/null 2>&1; then
  echo "BAD  three different readings must never be reported as agreement"; exit 1
fi
echo "ok   genuine disagreement is retried and then refused"

# --- the read-model identity readers (YU17, 2026-08-27) -----------------------------------------
#
# These are the instrument behind fix #1 ("ask the order"), and a bug in one does NOT read as a
# parser bug: it reads as "the order never became visible", i.e. a verdict about the scenario. So
# they get exercised offline, with fixture JSON, before any proof arms them on a rig.
tp_orders() {
  [[ "$1" == "empty"  ]] && { echo "[]"; return 0; }
  [[ "$1" == "broken" ]] && { echo "<html>502</html>"; return 0; }
  cat <<'JSON'
[{"id":"7-101","accountId":22214,"security":"ACME","side":"Sell","quantity":9,"limitPrice":153.000000,"status":"NEW"},
 {"id":"7-17","accountId":22214,"security":"ACME","side":"Buy","quantity":4,"limitPrice":150.000000,"status":"CANCELED"},
 {"id":"7-2","accountId":22214,"security":"ZZZZ","side":"Buy","quantity":1,"limitPrice":9.500,"status":"NEW"}]
JSON
}
expect "row reads status+qty+price"      "NEW 9 153.0"      "$(order_row x 101)"
expect "row reads a terminal state"      "CANCELED 4 150.0" "$(order_row x 17)"
expect "absent ref reads empty"          ""                 "$(order_row x 999)"
# '-7' must not match '-17'. A suffix compare that used endswith/LIKE returns the WRONG order's
# status here — the dash-anchoring bug yu13-readmodel-effect-end calls out in SQL.
expect "no suffix bleed, 7 vs 17"        ""                 "$(order_row x 7)"
# NUMERIC sort, not lexicographic: lexicographic gives "101 17" and turns a correct venue red.
expect "open set is ticker-scoped, numeric" "17 101"        "$(open_refs_on x ACME)"
expect "other tickers excluded"          "2"                "$(open_refs_on x ZZZZ)"
expect "empty array is not a crash"      ""                 "$(order_row empty 101)"
expect "empty array open set"            ""                 "$(open_refs_on empty ACME)"
# A 502 must parse as "cannot read", never as "the order is absent" — require_read_model is what
# turns that into a refusal to run, and this arm proves the parser does not throw first.
expect "non-JSON is not a crash"         ""                 "$(order_row broken 101)"
same_num 153.000000 153.0 || { echo "BAD  same_num 153.000000 == 153.0"; exit 1; }
same_num 153.0 154.0 && { echo "BAD  same_num must reject 153.0 == 154.0"; exit 1; }
echo "ok   read-model parsers (9 shapes + numeric price compare)"
# assert_row_terms must REFUSE an empty row rather than pass it: an unreadable probe reported as a
# satisfied claim is this whole issue's defect, reintroduced at the fix.
if ( assert_row_terms "" NEW 9 153.0 "selftest" ) >/dev/null 2>&1; then
  echo "BAD  assert_row_terms accepted an EMPTY row — an unreadable probe must never read as a pass"; exit 1
fi
assert_row_terms "NEW 9 153.0" NEW 9 153.000000 "selftest"
echo "ok   assert_row_terms accepts a real row and refuses an unreadable one"

expect "epoch qualifier off an id"     "7"                "$(order_epoch_of x 101)"
expect "qualifier absent ref reads empty" ""              "$(order_epoch_of x 999)"
echo "ok   epoch qualifier reader"

echo "ALL GREEN"
