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
set -uo pipefail

MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"

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

echo "[ok] fixtures seeded"
