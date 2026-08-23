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
PRICE="${PRICE:-200}"

echo "[seed] matcher ${MATCHER_URL}"
if ! curl -sf -m8 -o /dev/null "${MATCHER_URL}/ready"; then
  echo "[fail] matcher not reachable at ${MATCHER_URL}"
  echo "[hint] kubectl --context ${CTX} -n ${NS} port-forward svc/order-matcher 18110:18110 &"
  exit 1
fi

for acct in "${ACCOUNTS[@]}"; do
  code="$(curl -s -m20 -o /dev/null -w '%{http_code}' -X POST "${MATCHER_URL}/seed" \
    -H 'Content-Type: application/json' \
    -d "{\"accountId\":${acct},\"tickers\":\"${TICKERS}\",\"price\":${PRICE}}")"
  printf "   %-8s %s\n" "${acct}" "seed HTTP ${code}"
done

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
# SEEDING ONLY, DELIBERATELY NO CROSS. The price collar band is anchored by the first LIMIT into a
# book (slotFor()), never by a price tick, so seeding cannot pin an option book -- crossing here
# would, and yu15-option-persistence.sh must cross AAPL261218C00260000 at 2.40 against a live ~8.85.
#
# THE CAPACITY THAT USED TO BOUND THIS IS GONE, which is what makes seeding the whole feed possible.
# The historical builds yu13-stp-and-replace rolls the members onto held MAX_SECURITIES=64, so the
# 20 tickers + 24 contracts seeded here (44) were already most of the budget and the remaining 24
# instruments could not be added without breaking that proof. Those two images now carry 1024, same
# as every current build -- see IMAGE_PRE in scripts/proofs/yu13-stp-and-replace.sh. The assertion
# below is what keeps that from being rediscovered as a {"seeded":false} two proofs later.
echo "[seed] the rest of the quoted universe at live prices"
# FULL PRECISION, NOT 2dp. This block used to round to cents, which is right for an option premium
# and destroys a bond: UST-BILL-20260910 quotes 0.9968 and rounds to 1.0, UST-STRIP-20560515 quotes
# 0.21969 and rounds to 0.22. POST /seed sends a PRICE_TICK at the price passed and that becomes the
# risk anchor, so a rounded bond is a wrong anchor on every bond in the universe.
FEED_PX="$(kubectl --context "${CTX}" -n "${NS}" exec deploy/price-publisher -- \
  wget -qO- http://localhost:18100/prices 2>/dev/null \
  | python3 -c 'import sys, json, re
seeded = set("'"${TICKERS}"'".split(","))
opt = re.compile(r"[A-Z]+[0-9]{6}[CP][0-9]{8}")
rows, census = [], {"option": 0, "bond": 0, "etf": 0}
for q in json.load(sys.stdin)["prices"]:
    t, px = q["ticker"], q.get("price", 0)
    if t in seeded or px <= 0:
        continue
    # Shape, not a reference-data lookup: options match the OCC symbol, everything hyphenated is a
    # UST/CORP debt instrument, and the rest are the plain-ticker ETFs.
    census["option" if opt.fullmatch(t) else "bond" if "-" in t else "etf"] += 1
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
hold() { # hold <buyer> <seller> <ticker> <qty> <px>
  for body in \
    "{\"accountId\":$2,\"ticker\":\"$3\",\"side\":\"Sell\",\"quantity\":$4,\"limitPrice\":$5}" \
    "{\"accountId\":$1,\"ticker\":\"$3\",\"side\":\"Buy\",\"quantity\":$4,\"limitPrice\":$5}"; do
    curl -s -m20 -o /dev/null -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' -d "${body}"
  done
  printf "   %-8s holds %s x%s\n" "$1" "$3" "$4"
}

echo "[seed] positions"
hold 10031 42422 NVDA 25 200     # yu06-consumer-halt: the held security it excludes from the universe
hold 44044 42422 AAPL 10 200     # a control account marked in the same version
hold 22214 42422 IBM  10 200     # yu05 recon/settlement have something to reconcile

# Clear positions in the throwaway instruments other proofs mint.
#
# yu13-clordid-suppression and yu13-stp-and-replace each trade a time-derived ticker (DUP…, RM…,
# STP…) so their runs cannot collide. Those instruments are never in an EOD price universe, and a
# position row in one -- even at quantity zero -- halts that account's end-of-day P&L forever.
# yu15-risk-extract then fails with "an unpriced holding blocks its P&L", which is a true statement
# about a fixture no one intended to keep.
#
# Deliberately scoped to those generated prefixes. It does NOT touch real tickers, because a real
# holding with no published price is a genuine halt condition -- and proving exactly that is what
# yu06-consumer-halt exists for. Widening this would quietly disarm that proof.
echo "[clean] positions in generated throwaway instruments"
kubectl --context "${CTX}" -n "${NS}" exec deploy/eod-price-db -c mariadb -- \
  mariadb -utraderx -ptraderx traderx -N -B -e \
  "DELETE FROM positions WHERE security REGEXP '^(DUP|RM|STP|Z)[0-9]';
   SELECT CONCAT('   remaining throwaway rows: ', COUNT(*)) FROM positions
     WHERE security REGEXP '^(DUP|RM|STP|Z)[0-9]';" 2>/dev/null

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
