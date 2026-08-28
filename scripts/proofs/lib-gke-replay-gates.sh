#!/usr/bin/env bash
# lib-gke-replay-gates.sh — the preamble every GKE proof needs once ADR-072 made the tape a
# permanent third writer, and the write-pressure table that qualifies the green it prints.
#
# WHY THIS IS A LIBRARY AND NOT FOUR COPIES. `yu13-gke-replace-proof` grew this preamble inline
# and it came to ~110 lines. Four more proofs need exactly the same five things, and the lesson
# this whole class of defect keeps teaching is that a PRIVATE REIMPLEMENTATION is what a sweep
# cannot see: eleven exposed assertions hid behind hand-rolled copies of readings the library
# already had. One copy is one place to fix, and one place to grep.
#
# Requires the sourcing script to define `fail()` and `K` (its kubectl prefix, string or array).
# Resolved at CALL time, so the source line may sit anywhere above the first use.
#
#     here="$(cd "$(dirname "$0")" && pwd)"; . "${here}/lib-gke-replay-gates.sh"
#
# SELFTEST=1 on any sourcing proof should reach `gates_selftest` — it exercises the rate-band and
# pressure arithmetic offline, with no cluster. The gates are the instrument that decides whether
# a run is admissible at all; a gate that cannot fail is worth less than no gate, because it reads
# as one. (`a-guard-that-cannot-prove-it-can-read`.)

# `K` may be a string or an array — same resolution as lib-consensus-readings.sh, and for the same
# reason: a bare ${K} collapses an array to `kubectl` and every reading comes back as usage text.
_gk() {
  if [[ "$(declare -p K 2>/dev/null)" == "declare -"[aA]* ]]; then "${K[@]}" "$@"; else ${K} "$@"; fi
}

# ADR-072: "sample to a target order rate -- order 5-20/sec, tunable". Overridable because the ADR
# calls it tunable, NOT to make a failing run pass.
REPLAY_MIN_RATE="${REPLAY_MIN_RATE:-5}"
REPLAY_MAX_RATE="${REPLAY_MAX_RATE:-20}"

# --- the destructive gate ----------------------------------------------------------------------
#
# REFUSE, RATHER THAN RUN A REDUCED SUBSET. Every proof sourcing this file kills a member, scales
# the set to zero, or deletes a PVC, and each prints a [PASS] banner claiming survival across that
# event. A run that skipped the destructive step has not shown the claim, so a partial run must
# never be the default -- and maintaining a second, honestly-worded banner for the reduced run is a
# burden that rots. EXIT 2 keeps "refused" separable from "passed" and "failed" for a caller.
require_destructive() { # require_destructive <what-it-destroys> <what-does-not-run> [extra-env]
  [[ "${DESTRUCTIVE:-0}" == "1" ]] && return 0
  cat >&2 <<EOF
[SKIP] DESTRUCTIVE=0 (the default). Nothing has run and nothing was touched.

       THIS PROOF ${1}

       NOT RUN: ${2}

       The destructive step is not incidental -- it is the event the claim is about, so running the
       remaining steps would print a PASS for something that was never tested. This refuses instead
       of reducing itself.

       When you have a rig to spend, and the tape live (it asserts that too):

         DESTRUCTIVE=1 CTX=<context> IMAGE=<the image all members are on> ${3:+${3} }\\
           bash ${0##*/}
EOF
  exit 2
}

# --- the divergence rule ------------------------------------------------------------------------
#
# Every member on the SAME image and ready, before any traffic. A rolling window is a divergence
# window while members compute different functions of the same log, and a proof that starts inside
# one is measuring the roll, not the system.
require_uniform_image() { # require_uniform_image <image> [tries]
  local want="${1:?image}" tries="${2:-120}" i state
  for i in $(seq 1 "${tries}"); do
    state="$(_gk get pods -l app=order-matcher-cluster \
      -o jsonpath='{range .items[*]}{.spec.containers[0].image}{" "}{.status.containerStatuses[0].ready}{"\n"}{end}' \
      2>/dev/null | sort -u | tr -d '\n')"
    [[ "${state}" == "${want} true" ]] && { echo "  all members: ${want##*:}, ready, uniform"; return 0; }
    sleep 5
  done
  fail "members never all reached ${want} and ready (saw: ${state:-nothing}).
  A mixed-image cluster is a DIVERGENCE window, not a slow rollout: members computing different
  functions of the same log disagree permanently, and nothing below would be measuring the system."
}

# --- the tape ------------------------------------------------------------------------------------
_pub()  { _gk exec deploy/price-publisher -- wget -qO- "http://localhost:18100$1" 2>/dev/null || true; }
_jget() { python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(3)
for k in sys.argv[1].split('.'):
    if isinstance(d, dict) and k in d:
        d = d[k]
    else:
        sys.exit(4)
print('' if d is None else d)" "$1"; }

# Sets REPLAY_SUBMITTED0 and REPLAY_RATE0 in the caller's scope.
#
# ENABLED IS NOT THE SAME AS WRITING AT PRESSURE. A rig shipped 2026-08-27 reported enabled with
# error:null and plausible orders while replaying at 1.53/s -- a quarter of target, because the
# print-sample Secret and PRICE_TICKERS disagreed, and nothing reported it. A quarter-rate tape
# still CLIMBS, so every field-based check and every "submitted went up" check passes on it. The
# band is therefore asserted, not printed; and the OBSERVED rate is asserted separately at the end,
# because a config field cannot be faked into a measured delta.
require_tape_live() {
  local h err
  h="$(_pub /health)"
  [[ -n "${h}" ]] || fail "price-publisher /health did not answer — cannot establish the tape is live"
  REPLAY_SUBMITTED0="$(printf '%s' "${h}" | _jget printReplay.submitted)" \
    || fail "price-publisher /health carries NO printReplay block: this build predates ADR-072.
  This proof asserts it passed WHILE the tape was writing, and on this rig it cannot."
  err="$(printf '%s' "${h}" | _jget printReplay.error)" || err=""
  [[ -z "${err}" ]] || fail "the replay is OFF: ${err}
  A green with the tape stopped proves nothing about the class these assertions were rewritten for,
  and would quietly restore confidence in the shapes that had to be removed."
  REPLAY_RATE0="$(printf '%s' "${h}" | _jget printReplay.ordersPerSecond)" || REPLAY_RATE0=""
  in_rate_band "${REPLAY_RATE0}" \
    || fail "the tape REPORTS ${REPLAY_RATE0:-no}/s, outside ADR-072's ${REPLAY_MIN_RATE}-${REPLAY_MAX_RATE}/s band.
  Enabled and error-free is NOT the same as writing at pressure. Do not widen the band to make a run
  pass: a quiet tape is the condition under which the assertions this file removed ALSO passed.
  Check the print-sample Secret against PRICE_TICKERS."
  echo "  tape live: $(printf '%s' "${h}" | _jget printReplay.symbols) symbols at ~${REPLAY_RATE0}/s, ${REPLAY_SUBMITTED0} submitted so far"
}

in_rate_band() { # in_rate_band <rate> — separated out so the selftest can exercise it offline
  awk -v r="${1:-0}" -v lo="${REPLAY_MIN_RATE}" -v hi="${REPLAY_MAX_RATE}" \
    'BEGIN{exit !(r+0 >= lo+0 && r+0 <= hi+0)}'
}

# MEASURE THE RATE, NEVER READ IT. submitted delta / elapsed. The reported field is a config-shaped
# claim; this is the only reading that cannot be faked by a misconfigured sample.
assert_observed_rate() { # assert_observed_rate <elapsed-seconds> <what>
  local elapsed="${1:?elapsed}" what="${2:-this run}" s1 delta rate
  s1="$(_pub /health | _jget printReplay.submitted)" \
    || fail "could not re-read printReplay.submitted to measure the observed rate"
  (( elapsed > 0 )) || fail "observed-rate window was ${elapsed}s — nothing can be measured across it"
  delta=$(( s1 - REPLAY_SUBMITTED0 ))
  rate="$(awk -v d="${delta}" -v e="${elapsed}" 'BEGIN{printf "%.2f", d/e}')"
  echo "  tape wrote throughout: submitted ${REPLAY_SUBMITTED0} -> ${s1} (+${delta}) over ${elapsed}s = ${rate}/s observed"
  (( delta > 0 )) || fail "${what}: the tape submitted NOTHING across this run. Every assertion above
  was rewritten for a venue under foreign write pressure; with none, this run re-proves none of it."
  in_rate_band "${rate}" \
    || fail "${what}: the tape OBSERVED rate was ${rate}/s (${delta} orders over ${elapsed}s), outside
  ADR-072's ${REPLAY_MIN_RATE}-${REPLAY_MAX_RATE}/s band. This is the measured reading, not the reported one, so it is
  the tape's actual behaviour during this run that is wrong -- or the run was too short to measure."
}

# --- the write-pressure table --------------------------------------------------------------------
#
# SIX COUNTERS FROM ONE SCRAPE, deliberately a single HTTP call, so the readings are mutually
# coherent. Six separate calls would reproduce exactly the sequential-sampling skew that the retry
# loops elsewhere exist to survive -- measured on the GKE bench 2026-08-27 at 5/20 coherent for a
# four-quantity read. Reporting only: `assert_observed_rate` is what ASSERTS the pressure was real.
pressure_row() {
  _gk exec order-matcher-cluster-0 -c cluster-node -- \
    sh -c 'wget -qO- http://localhost:8080/metrics 2>/dev/null' \
    | awk '/^traderx_cluster_trades[{ ]/                  {a=$2}
           /^traderx_cluster_operator_trades[{ ]/         {b=$2}
           /^traderx_stp_cancels[{ ]/                     {c=$2}
           /^traderx_stp_operator_cancels[{ ]/            {d=$2}
           /^traderx_cluster_next_order_ref[{ ]/          {e=$2}
           /^traderx_cluster_operator_next_order_ref[{ ]/ {f=$2}
           END{print a, b, c, d, e, f}'
}
print_pressure() { # print_pressure <before-row> <after-row>
  awk -v b="$1" -v a="$2" 'BEGIN{
    split(b,B," "); split(a,A," ");
    n[1]="traderx_cluster_trades";             n[2]="  traderx_cluster_operator_trades";
    n[3]="traderx_stp_cancels";                n[4]="  traderx_stp_operator_cancels";
    n[5]="traderx_cluster_next_order_ref";     n[6]="  traderx_cluster_operator_next_order_ref";
    for(i=1;i<=6;i++) printf "    %-44s %10s -> %-10s %+d\n", n[i], B[i], A[i], A[i]-B[i];
  }'
}

# --- selftest -------------------------------------------------------------------------------------
gates_selftest() {
  local t=0 f=0
  chk() { t=$((t+1)); [[ "$2" == "$3" ]] || { echo "  [FAIL] $1: got '$2', want '$3'"; f=$((f+1)); }; }
  REPLAY_MIN_RATE=5 REPLAY_MAX_RATE=20
  in_rate_band 6.13  && chk "6.13 in band"        ok ok || chk "6.13 in band"        bad ok
  in_rate_band 1.53  && chk "1.53 REJECTED"       bad ok || chk "1.53 REJECTED"      ok ok
  in_rate_band 25    && chk "25 REJECTED"         bad ok || chk "25 REJECTED"        ok ok
  in_rate_band 5     && chk "5 (lower edge) in"   ok ok || chk "5 (lower edge) in"   bad ok
  in_rate_band 20    && chk "20 (upper edge) in"  ok ok || chk "20 (upper edge) in"  bad ok
  # The empty reading must be REFUSED, not treated as 0-and-therefore-out-of-band by accident:
  # either way it fails, but a rate that cannot be read is the probe failing, and awk's "" -> 0
  # would make an unreadable tape indistinguishable from a stopped one.
  in_rate_band ""    && chk "empty REJECTED"      bad ok || chk "empty REJECTED"     ok ok
  chk "pressure delta arithmetic" \
    "$(print_pressure "10 1 20 2 30 3" "18 3 24 2 41 5" | awk '{printf "%s ", $NF}')" \
    "+8 +2 +4 +0 +11 +2 "
  echo "gates selftest: $(( t - f ))/${t} passed"
  return $(( f > 0 ))
}

# --- the context guard -------------------------------------------------------------------------
#
# DESTRUCTIVE=1 records that the operator accepted destroying *a* cluster. It does not record that
# they accepted destroying THIS one. `CTX` is a default, and defaults rot: these proofs shipped for
# weeks pointing at `traderx-501015`, a project deleted 2026-08-01, and a wrong-context kubectl
# answers truthfully about the wrong cluster — which this project has already paid for once.
#
# For a member rebuild that is recoverable. For a scale-to-zero or a PVC delete it is not, so the
# irreversible proofs require the operator to NAME the target and refuse on a mismatch. That turns
# "I forgot to set CTX" from an outage into a refusal, which is free.
require_expected_context() { # require_expected_context <ctx> <what-is-irreversible>
  local ctx="${1:?ctx}" what="${2:?what}"
  [[ -z "${EXPECT_CTX:-}" ]] && {
    cat >&2 <<EOF
[SKIP] EXPECT_CTX is not set, and this proof does something IRREVERSIBLE: ${what}
       The data is not coming back. DESTRUCTIVE=1 says you accepted destroying a cluster; it does
       not say you accepted destroying THIS one, and CTX is a default that rots.

       Name the target to proceed:   EXPECT_CTX='${ctx}'

       Nothing has run and nothing was touched.
EOF
    exit 2; }
  [[ "${EXPECT_CTX}" == "${ctx}" ]] || fail "EXPECT_CTX does not match the context this run would use:
    EXPECT_CTX = ${EXPECT_CTX}
    CTX        = ${ctx}
  Refusing. ${what} — on the wrong cluster that is unrecoverable, and a wrong-context kubectl
  answers truthfully about the wrong place rather than erroring."
  echo "  target confirmed: ${ctx} (ns ${NS:-traderx})"
}
