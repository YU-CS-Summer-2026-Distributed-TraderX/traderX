#!/usr/bin/env bash
# Seed every account/security the proof suite drives, on the cluster tier.
#
# Why this is needed at all: on this tier an account and a security exist only once they have been
# SEQUENCED. An order naming one that has not been is rejected UNKNOWN_ACCOUNT / UNKNOWN_SECURITY
# before any risk control is consulted -- and most proofs count effects rather than inspecting
# rejections, so a missing fixture surfaces as a false accusation about the system. Real examples
# hit while porting the suite: yu08 reported "the scheduler is not running" when the scheduler was
# fine and the venue was refusing account 62654; yu10 reported "no projection growth" for the same
# reason on 11413.
#
# It is also NOT one-and-done. Any proof that swaps the member image restarts the cluster on a
# fresh epoch and takes the seeded risk state with it -- yu13-stp-and-replace does exactly that.
# So this is safe and cheap to re-run, and worth re-running after any proof that rolls the members.
#
#   bash scripts/yu15/seed-proof-fixtures.sh            # via a port-forward on 18110
#   MATCHER_URL=http://localhost:18110 bash scripts/yu15/seed-proof-fixtures.sh
#
# RUN THE RIG QUIET. Several proofs assert EXACT ground-truth deltas -- yu13-readmodel-effect-end
# checks next_order_ref moves by precisely 2 and not at all on a cancel. Any other traffic breaks
# them, and the execution-algo-engine is a continuous source of it: it was observed moving the
# counter by 24 mid-proof, failing a proof about a system that was behaving correctly. Before the
# counter-exact proofs:
#     kubectl -n traderx scale deploy/execution-algo-engine --replicas=0
# and scale it back to 1 for yu08. Same reason a proof that leaves the members on its own image
# (yu13-stp-and-replace) is a hazard to everything that runs after it.
#
# AFTER A PVC WIPE, CLEAR THE PROJECTION TOO. Wiping the members' PVCs gives a fresh epoch whose
# counters restart near zero, but the SQL read model still holds the OLD epoch's rows -- and
# yu13-stp-and-replace correctly refuses to run when the engine's tradeCounter is below the highest
# trade id already in SQL, because this epoch's trades would be silently dropped as duplicates.
# A fresh epoch needs a fresh projection; the read model is a projection of a log that no longer
# exists. Run FRESH_EPOCH=1 to clear it here:
#     FRESH_EPOCH=1 bash scripts/yu15/seed-proof-fixtures.sh
#
# When is a wipe needed at all? When the members diverge for real. Distinguish it from lag by the
# applied sequence: lagging members show DIFFERENT sequences and are still moving; diverged members
# sit at the SAME sequence with different books. Seen here as
#     [53 ...] [53 ...] [54 ...]  applied: m0=seq=18707 m1=seq=18707 m2=seq=18707
# which is permanent -- a mixed-version window during a member roll, exactly the hazard
# yu13-stp-and-replace warns about. The only recovery is a wipe to a fresh epoch.
#
# THE OTel PROOFS NEED THE OBSERVABILITY STACK, which start-cluster-kind.sh does not deploy:
#     bash scripts/yu15/start-observability-kind.sh
# plus port-forwards for tempo 3200, loki 3100 and grafana 3000 alongside order-matcher 18110.
# Tempo answers 503 on /ready for a while after it starts -- wait for 200 before running them, or
# the proof fails with a bare connection error that says nothing about Tempo still booting.
set -uo pipefail

MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"

# Fresh-epoch mode: the read model belongs to a log that no longer exists. This runs FIRST -- before
# the matcher gate and the seeding below -- for two reasons learned the hard way:
#   * it must not depend on a port-forward: it is kubectl-only, and run-proofs.sh's baseline pin
#     used to invoke it before any forward existed, so the script bailed at the matcher gate and
#     the clear silently never ran -- a whole suite pass then deduped this epoch's trades against
#     the dead epoch's rows and three proofs failed on trades the engine definitely booked;
#   * clearing AFTER seeding wiped the very positions the hold() crossings below had just built.
# And it is VERIFIED: a clear that did not happen is exit 1, not a log line. A fresh epoch with a
# stale projection silently drops every trade (ids <tradeSeq>-<side> restart and collide, and
# trade-processor's idempotency dedup does exactly what it is told).
if [[ "${FRESH_EPOCH:-0}" == "1" ]]; then
  echo "[fresh-epoch] clearing the SQL projection"
  left="$(kubectl --context "${CTX}" -n "${NS}" exec deploy/eod-price-db -c mariadb -- \
    mariadb -utraderx -ptraderx traderx -N -B -e \
    "DROP TABLE IF EXISTS yu15_parked_trades; DROP TABLE IF EXISTS yu15_parked_positions;
     DELETE FROM trades; DELETE FROM positions; DELETE FROM orderbook;
     SELECT (SELECT COUNT(*) FROM trades)+(SELECT COUNT(*) FROM positions)+(SELECT COUNT(*) FROM orderbook);" \
    2>/dev/null | tail -1)"
  if [[ "${left}" != "0" ]]; then
    echo "[fail] fresh-epoch clear left '${left:-kubectl exec failed}' rows in the projection"
    exit 1
  fi
  echo "   trades, positions and orderbook cleared"
fi

# Every account the committed proofs name, with the tickers they trade in them.
#   22214 52355  yu03 (risk controls, IBM/BAC)
#   42422 22214  yu13 / quickstart crossing pair, yu15 extract positions
#   62654        yu08 TWAP parent
#   11413        yu10 FIX session
#   10031 44044  yu06 EOD P&L consumer (10031 must HOLD stock for the halt proof)
ACCOUNTS=(22214 52355 42422 62654 11413 10031 44044)
TICKERS="${TICKERS:-IBM,BAC,AAPL,MSFT,NVDA,GOOGL,AMZN,META,TSLA,C,JPM,GS,MS,UBS,DB,COF,DFS,FNMA,FIS,FNF}"
# THE OPENING SEED IS THE TAPE'S OWN FIRST PRICE, not a round number.
#
# POST /seed sends a PRICE_TICK at the price passed and the FIRST one a book ever gets becomes its
# mark, its ADR-066 collar anchor and (format 8) the grid its scale is derived from. A re-seed does
# not move a mark that already exists, so whatever this loop passes is what the book carries for the
# rest of the epoch -- the block further down that re-seeds the whole feed at live prices CANNOT
# correct it. Measured on the rig 2026-08-26: 17 of 69 books sat at `mark 200.000`, every one of
# them a ticker named here and never crossed, while FNMA's reference was 1.157 -- a mark 173x its
# own market, invented by this line.
#
# So each ticker is seeded from prices[SYMBOL][0][0] of the replay extract: the median of the first
# 195s window of 2025-02-03, which is exactly where the tape starts a fresh epoch. That makes the
# opening mark and the opening reference the SAME number rather than two unrelated ones, and it is
# on the venue's grid for free (the extract is rounded to 3dp against an equity tickPx of 1000).
#
# Read off the publisher's own mount, so this seeds from the extract that is actually replaying
# rather than a bucket copy that may be a rebuild ahead. Two fallbacks, in order, because ADR-068
# rule 1 says a rig with no tape must still come up: a name the extract does not carry (FNMA is OTC
# and not in TAQ; GOOGL is a suffix-merged root) takes the publisher's live quote, and only a rig
# with no publisher at all falls back to ${PRICE}.
PRICE="${PRICE:-200}"
TAPE_PX="$(kubectl --context "${CTX}" -n "${NS}" exec deploy/price-publisher -- node -e '
const z = require("zlib"), f = require("fs");
const p = process.env.TAQ_REPLAY_EXTRACT_PATH || "/etc/taq-replay/extract.json.gz";
for (const [t, series] of Object.entries(JSON.parse(z.gunzipSync(f.readFileSync(p)).toString()).prices)) {
  console.log(t, series[0][0]);
}' 2>/dev/null || true)"
LIVE_PX="$(kubectl --context "${CTX}" -n "${NS}" exec deploy/price-publisher -- \
  wget -qO- http://localhost:18100/prices 2>/dev/null \
  | python3 -c 'import sys, json
for q in json.load(sys.stdin)["prices"]:
    if q.get("price", 0) > 0: print(q["ticker"], "%.6f" % q["price"])' 2>/dev/null || true)"
if [[ -z "${TAPE_PX}" ]]; then
  echo "[warn] no replay extract on price-publisher -- opening seeds fall back to the live feed"
fi
# First hit wins: tape, then the live quote, then the flat default.
seed_px() {
  awk -v t="$1" -v d="${PRICE}" '$1 == t { print $2; found = 1; exit }
                                 END { if (!found) print d }' <<< "${TAPE_PX}
${LIVE_PX}"
}

echo "[seed] matcher ${MATCHER_URL}"
if ! curl -sf -m8 -o /dev/null "${MATCHER_URL}/ready"; then
  echo "[fail] matcher not reachable at ${MATCHER_URL}"
  echo "[hint] kubectl --context ${CTX} -n ${NS} port-forward svc/order-matcher 18110:18110 &"
  exit 1
fi

# One call per ticker rather than one per account: the prices differ per ticker now, and /seed
# carries a single price for the whole list it is given.
#
# A newline-separated "TICKER PX" string, NOT an associative array: macOS ships bash 3.2, which has
# no `declare -A`, and this suite runs on a laptop. Same shape FEED_PX uses below.
OPEN_PX="$(tr ',' '\n' <<< "${TICKERS}" | while read -r t; do
  [[ -n "${t}" ]] && printf '%s %s\n' "${t}" "$(seed_px "${t}")"
done)"
TICKER_N="$(grep -c . <<< "${OPEN_PX}")"
echo "[seed] ${#ACCOUNTS[@]} accounts x ${TICKER_N} tickers at the tape's opening prices"
for acct in "${ACCOUNTS[@]}"; do
  bad=0
  while read -r t px; do
    [[ -n "${t}" ]] || continue
    code="$(curl -s -m20 -o /dev/null -w '%{http_code}' -X POST "${MATCHER_URL}/seed" \
      -H 'Content-Type: application/json' \
      -d "{\"accountId\":${acct},\"tickers\":\"${t}\",\"price\":${px}}")"
    [[ "${code}" == "200" ]] || { echo "[warn] seed ${acct} ${t} @ ${px}: HTTP ${code}"; bad=$((bad + 1)); }
  done <<< "${OPEN_PX}"
  printf "   %-8s %s\n" "${acct}" "$(( TICKER_N - bad ))/${TICKER_N} seeded"
done
# Printed, so a wrong opening mark is visible HERE rather than three proofs later.
sed 's/^/   /' <<< "${OPEN_PX}" | sort

# EVERY OTHER QUOTED INSTRUMENT -- the same enablement step, for every class TICKERS does not name.
#
# WIDENED 2026-08-22 from listed options only to the WHOLE quoted universe (options, ETFs,
# treasuries, bills, strips, corporates). Seeding one class at a time reproduced the very defect
# the option block was added to close, one class further along: bonds and treasuries were enabled
# only because yu16-bond-position.sh and yu16-treasury-pricing.sh each POST /seed their own
# instrument as a setup step, so the tradeable set depended on which proofs had happened to run --
# and ETFs, which no proof seeds, were never enabled at all.
# (issues/open/the-fixture-seeder-enables-only-equities-and-options.md)
#
# BlpRiskState enables securities PER EPOCH and a fresh epoch starts with NONE enabled. This script
# seeded equities only, so after every roll the whole YU14 option class was untradeable and every
# option order came back UNKNOWN_SECURITY -- a reason code every reader parses as "wrong ticker".
# Nothing on the surface contradicted that reading: /resolve still SUCCEEDED (reference data knows
# the contract) and price-publisher still MARKED it, so the instrument looked live while only the
# enablement was missing. scripts/proofs/seed-option-chain.sh does enable the chain, but nothing
# called it -- so whether options worked depended on which proofs a human had run by hand that day.
# (issues/resolved/an-epoch-roll-silently-drops-instrument-classes.md)
#
# THE CHAIN IS READ FROM THE FEED, NOT COPIED. price-publisher's /prices is the list of what this
# tier actually quotes; a second hardcoded copy of the chain is a copy that drifts. kubectl exec
# rather than a forward, like the DB calls above, so this does not depend on port 18100 being up.
#
# AND AT THE LIVE PREMIUM, NOT A ROUND NUMBER. POST /seed sends a PRICE_TICK at the price passed
# and that becomes the risk anchor -- yu08-algo-slicing.sh passing 200 unconditionally is how IBM's
# anchor sat at 200 while the feed had walked to ~185.
#
# SEEDING ONLY, DELIBERATELY NO CROSS. yu15-option-persistence.sh must cross AAPL261218C00260000
# at 2.40 against a live ~8.85, so a cross here would pin that book where the proof cannot use it.
#
# (The old reason given here -- "the collar band is anchored by the first LIMIT into a book
# (slotFor()), never by a price tick, so seeding cannot pin an option book" -- is stale pre-ADR-066
# prose and was deleted at the format-8 mint. Since ADR-066 a new book anchors on the REFERENCE, so
# a seeded tick does pin the band; and since format 8 the tick a book seeds at also decides its
# GRID, because an empty book re-derives its scale from that same reference.)
#
# THE CAPACITY THAT USED TO BOUND THIS IS GONE, which is what makes seeding the whole feed possible.
# The historical builds yu13-stp-and-replace rolls the members onto held MAX_SECURITIES=64, so the
# 20 tickers + 24 contracts seeded here (44) were already most of the budget and the remaining 24
# instruments could not be added without breaking that proof. Those two images now carry 1024, same
# as every current build -- see IMAGE_PRE in scripts/proofs/yu13-stp-and-replace.sh. The assertion
# below is what keeps that from being rediscovered as a {"seeded":false} two proofs later.
echo "[seed] the whole quoted universe at live prices"
# FULL PRECISION, NOT 2dp. This block used to round to cents, which is right for an option premium
# and destroys a bond: UST-BILL-20260910 quotes 0.9968 and rounds to 1.0, UST-STRIP-20560515 quotes
# 0.21969 and rounds to 0.22. POST /seed sends a PRICE_TICK at the price passed and that becomes the
# risk anchor, so a rounded bond is a wrong anchor on every bond in the universe.
FEED_PX="$(kubectl --context "${CTX}" -n "${NS}" exec deploy/price-publisher -- \
  wget -qO- http://localhost:18100/prices 2>/dev/null \
  | python3 -c 'import sys, json, re
seeded = set("'"${TICKERS}"'".split(","))
opt = re.compile(r"[A-Z]+[0-9]{6}[CP][0-9]{8}")
rows, census = [], {"equity": 0, "option": 0, "bond": 0, "etf": 0}
for q in json.load(sys.stdin)["prices"]:
    t, px = q["ticker"], q.get("price", 0)
    if px <= 0:
        continue
    # Shape, not a reference-data lookup: options match the OCC symbol, everything hyphenated is a
    # UST/CORP debt instrument, and the rest are the plain-ticker ETFs.
    # The equities are NOT excluded any more (2026-08-23): the block above enabled them at a flat
    # 200, and once the ADR-045 feed adapter sequences live ticks that 200 is a lie the collar
    # (ADR-066) believes for up to one flush -- and believes FOREVER on a rig with the adapter off.
    # Re-seeding them here at the live price is the same enablement the other classes get.
    census["equity" if t in seeded else "option" if opt.fullmatch(t) else "bond" if "-" in t else "etf"] += 1
    rows.append("%s %.6f" % (t, px))
# A CLASS THAT VANISHES FROM THE FEED MUST FAIL HERE, not surface as UNKNOWN_SECURITY in a proof.
# Seeding whatever the feed happens to quote is what removes the hardcoded per-class lists; it also
# removes the per-class guard those lists gave for free, so assert the classes back explicitly.
absent = [k for k, n in census.items() if n == 0]
if absent:
    sys.exit("[fail] no instruments of class(es) %s in price-publisher /prices" % ",".join(absent))
sys.stderr.write("   feed census: %s\n" % census)
print("\n".join(rows))')"
# A silently-skipped enablement is the whole defect this block closes, so an unreadable feed is
# exit 1 and not a warning: the alternative is a suite that runs green against a rig where the
# option class does not exist.
if [[ -z "${FEED_PX}" ]]; then
  echo "[fail] nothing readable from price-publisher /prices beyond the equities above -- the option,"
  echo "       bond and ETF classes would be left unenabled on this epoch, which surfaces as"
  echo "       UNKNOWN_SECURITY on every order against them"
  exit 1
fi
# ASSERT THE COUNT AGAINST THE ENGINE'S CAPACITY. Seeding the feed rather than a fixed list means
# this script's own footprint now grows with instruments.csv, and the failure mode at the far end is
# a fast 422 {"seeded":false} in whichever proof happens to mint a ticker next -- a symptom that
# points nowhere near here. MAX_SECURITIES is 1024 on every build the suite runs, including the two
# historical ones; the headroom left over is for the tickers the proofs mint themselves.
FEED_COUNT="$(grep -c . <<< "${FEED_PX}")"
TOTAL=$(( FEED_COUNT + $(tr ',' '\n' <<< "${TICKERS}" | grep -c .) ))
if [[ "${TOTAL}" -gt 900 ]]; then
  echo "[fail] this epoch would seed ${TOTAL} securities against MAX_SECURITIES=1024 -- too little"
  echo "       headroom for the tickers the proofs mint on top. Narrow the feed or raise capacity."
  exit 1
fi
seeded_n=0
while read -r otick opx; do
  [[ -n "${otick}" ]] || continue
  out="$(curl -s -m20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${ACCOUNTS[0]},\"tickers\":\"${otick}\",\"price\":${opx}}")"
  [[ "${out}" == *'"seeded":true'* ]] \
    || { echo "[fail] seed ${otick} @ ${opx}: ${out:-no answer from the matcher}"; exit 1; }
  seeded_n=$((seeded_n + 1))
done <<< "${FEED_PX}"
echo "   ${seeded_n} instruments enabled at their live prices (${TOTAL} securities this epoch)"

# yu06-consumer-halt and yu15-risk-extract need an account that provably HOLDS stock -- a seeded
# account with no position proves nothing, and both scripts correctly refuse to run without one.
# Cross a real trade rather than writing a row: the position has to arrive the way every other
# position does, through the book.
# A refused leg is FATAL here. This used to `-o /dev/null` both legs and print "holds" regardless,
# so a PRICE_COLLAR on either side reported a position that did not exist and the proof that needed
# it failed three steps later with a message about itself. The engine's ack carries kind=2 plus a
# reason on a refusal; read it.
# CANCELS ITS OWN RESIDUE, and that is not tidiness -- it is what keeps every counter-exact proof
# in the suite readable.
#
# This crossing rests a SELL and then aggresses it with a BUY, on a TAPE symbol. Since ADR-072 the
# replay trades those symbols continuously, so a replayed order can take the resting side first and
# leave OURS resting instead. An operator order left resting on a replayed book is then picked off
# at some random later moment, and it books an operator trade leg inside whichever proof's window
# happens to be open -- which is exactly how yu17-retick-determinism read "trades moved by 3, expected
# 2" (2026-08-26) on a scenario whose own orders were on a freshly minted ticker and were perfectly
# correct. The counters attribute replayed flow correctly; what they cannot do is un-rest an
# operator order that the replay is entitled to fill.
#
# Cancelling is safe on either outcome: a filled order answers the cancel by returning unchanged
# (009 parity), so this is a no-op on the happy path and a cleanup on the interfered one. The
# POSITION -- the thing this function exists to build -- is untouched either way.
hold() { # hold <buyer> <seller> <ticker> <qty> <px>
  local out refs=()
  for body in \
    "{\"accountId\":$2,\"ticker\":\"$3\",\"side\":\"Sell\",\"quantity\":$4,\"limitPrice\":$5}" \
    "{\"accountId\":$1,\"ticker\":\"$3\",\"side\":\"Buy\",\"quantity\":$4,\"limitPrice\":$5}"; do
    out="$(curl -s -m20 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' -d "${body}")"
    [[ "${out}" == *'"kind":2'* ]] && { echo "[fail] hold $3 x$4 @ $5 refused: ${out}"; exit 1; }
    refs+=("$(printf '%s' "${out}" | python3 -c 'import sys,json
try: print(json.load(sys.stdin).get("orderRef",""))
except Exception: print("")')")
  done
  local left=0 r
  for r in "${refs[@]}"; do
    [[ "${r}" =~ ^[0-9]+$ ]] || continue
    curl -s -m20 -X POST "${MATCHER_URL}/cancel" -H 'Content-Type: application/json' \
      -d "{\"orderRef\":${r}}" >/dev/null 2>&1 && left=$((left + 1))
  done
  printf "   %-8s holds %s x%s @ %s (both legs cancelled after the cross: no residue on a replayed book)\n" \
    "$1" "$3" "$4" "$5"
}

# AT THE LIVE PRICE, NOT 200. The collar band is +/-$65.50 around the security's sequenced
# reference (ADR-066), and with the feed adapter live that reference is the publisher's price, not
# this script's. NVDA quoted ~916 on 2026-08-23: both legs of `hold NVDA 25 200` were refused
# PRICE_COLLAR and 10031 held nothing, which yu06-consumer-halt correctly refuses to run without.
# Deterministic, not a flake -- the publisher quotes every instrument every ~4s and this script
# takes longer than that to get here. AAPL (Δ38) and IBM (Δ13) happened to fit; the fix is the
# same for all three. (issues/open/a-live-feed-refuses-the-fixture-seeders-nvda-crossing.md)
live_px() { awk -v t="$1" '$1==t {print $2; f=1} END {if (!f) print "200"}' <<< "${FEED_PX}"; }
echo "[seed] positions (crossed at the live price)"
hold 10031 42422 NVDA 25 "$(live_px NVDA)"   # yu06-consumer-halt: the held security it excludes from the universe
hold 44044 42422 AAPL 10 "$(live_px AAPL)"   # a control account marked in the same version
hold 22214 42422 IBM  10 "$(live_px IBM)"    # yu05 recon/settlement have something to reconcile

# SWEEP: NO DEMO ACCOUNT LEAVES AN ORDER RESTING, ANYWHERE.
#
# Since ADR-072 the tape replay trades the equity/ETF universe continuously, and an operator order
# left resting on one of those books is picked off at some random later moment. That books an
# operator trade leg inside whichever proof happens to have a counter window open, and no counter
# can fix it: the leg IS the operator's. Measured 2026-08-26, yu17-retick-determinism read "trades
# moved by 3 leg(s), expected 2" on a scenario that was entirely correct, because a seeder order was
# still sitting on NVDA from an earlier run.
#
# Fixing it proof by proof is a losing game -- yu03 leaves four on IBM, yu08 left six, the crossings
# above leave their own -- so it is enforced HERE, in the one place the runner calls before every
# proof. Establishing a known starting state is this script's whole job, and a leftover resting
# order is not part of one. Nothing depends on an order surviving between proofs: each places its
# own after this runs, and the POSITIONS built above are untouched by a cancel.
echo "[seed] cancelling any order the demo accounts left resting"
swept=0
for acct in "${ACCOUNTS[@]}" 17017; do
  refs="$(kubectl --context "${CTX}" -n "${NS}" exec deploy/trade-processor -- \
    wget -qO- "http://localhost:18091/accounts/${acct}/orders" 2>/dev/null \
    | python3 -c '
import sys, json
try:
    rows = json.load(sys.stdin)
except Exception:
    rows = []
for r in rows:
    ref = str(r.get("id", "")).rsplit("-", 1)[-1]
    if ref.isdigit():
        print(ref)' 2>/dev/null)"
  for ref in ${refs}; do
    curl -s -m20 -o /dev/null -X POST "${MATCHER_URL}/cancel" \
      -H 'Content-Type: application/json' -d "{\"orderRef\":${ref}}" && swept=$((swept + 1))
  done
done
echo "   ${swept} resting order(s) cancelled — no demo account is holding a book open"

# Clear positions in the throwaway instruments other proofs mint.
#
# Several proofs each trade a time-derived ticker so their runs cannot collide. Those instruments
# are never in an EOD price universe, and a position row in one -- even at quantity zero -- halts
# that account's end-of-day P&L forever. yu15-risk-extract then fails with "an unpriced holding
# blocks its P&L", which is a true statement about a fixture no one intended to keep.
#
# THE LIST HAS TO TRACK THE PROOFS THAT MINT, and it had fallen behind by nine prefixes. It read
# DUP|RM|STP|Z|BND while the suite was also minting CSR, HSF, KAC, PQO, RJT, RPL, RTD and RTK --
# and three of those BOOK TRADES: yu17-halt-survives-failover (HSF) fills a queued order at the
# open, yu17-retick-determinism (RTD) crosses its resting bid, yu17-preopen-queue-open (PQO) books
# one match. Measured 2026-08-25 on the format-8 mint: a suite run left those positions behind and
# the NEXT run's yu15-risk-extract reported `accounts=3 halted=2` at position 13, long before the
# proofs that created them ran again. Standalone on a clean projection the same proof reports
# `accounts=4 halted=0`. The failure is a full suite-length away from its cause, which is why it
# reads as a risk-extract defect and is not one.
#
# Still deliberately scoped to GENERATED prefixes followed by a digit. It does NOT touch real
# tickers, because a real holding with no published price is a genuine halt condition -- and proving
# exactly that is what yu06-consumer-halt exists for. Widening this to real symbols would quietly
# disarm that proof.
echo "[clean] positions in generated throwaway instruments"
kubectl --context "${CTX}" -n "${NS}" exec deploy/eod-price-db -c mariadb -- \
  mariadb -utraderx -ptraderx traderx -N -B -e \
  "DELETE FROM positions WHERE security REGEXP '^(DUP|RM|STP|Z|BND|CSR|HSF|KAC|PQO|RJT|RPL|RTD|RTK|SES)[0-9]';
   SELECT CONCAT('   remaining throwaway rows: ', COUNT(*)) FROM positions
     WHERE security REGEXP '^(DUP|RM|STP|Z|BND|CSR|HSF|KAC|PQO|RJT|RPL|RTD|RTK|SES)[0-9]';" 2>/dev/null

# YU17 FX-rate fix: the credit gate values swap notionals in USD off SEQUENCED rates, which are
# replicated state and die with the epoch. Until rates are re-sequenced, every non-USD swap
# booking is refused PRICE_MISSING -- deliberate fail-closed, but an operator who has not read the
# fix will misread it as a regression, so every fresh epoch re-seeds here. USD is identity by
# construction and not settable.
#
# GBP is DELIBERATELY not seeded: yu17-fx-credit.sh's fail-closed arm needs one currency that is
# still rate-less (refused before the rate, accepted after -- the negative control). Seeding it
# here would make that arm skip on every suite run. A GBP swap on this rig is refused until that
# proof runs or an operator sets the rate.
#
# A pre-YU17-fix build answers 404 (unknown control) -- declared as a skip, never silently absorbed.
echo "[seed] fx rates (YU17 credit gate; GBP left for yu17-fx-credit.sh)"
RISK_CONTROL_TOKEN="${RISK_CONTROL_TOKEN:-dev-risk-control}"
for pair in EUR:1.0842 JPY:0.0067; do
  ccy="${pair%%:*}"; rate="${pair##*:}"
  code="$(curl -s -m20 -o /dev/null -w '%{http_code}' -X POST "${MATCHER_URL}/risk/control/fxrate" \
    -H 'Content-Type: application/json' \
    -H "X-Risk-Control-Token: ${RISK_CONTROL_TOKEN}" -H 'X-Risk-Operator: seed-proof-fixtures' \
    -d "{\"currency\":\"${ccy}\",\"rate\":${rate}}")"
  case "${code}" in
    200) printf "   %-8s %s USD/unit\n" "${ccy}" "${rate}" ;;
    404) echo "   [skip] build predates the fxrate control (HTTP 404) -- non-USD swaps refused on this rig"; break ;;
    *)   echo "[fail] fxrate ${ccy} returned HTTP ${code} -- non-USD swap bookings will be refused PRICE_MISSING"
         exit 1 ;;
  esac
done

echo "[ok] fixtures seeded"
