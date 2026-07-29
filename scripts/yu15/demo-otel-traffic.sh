#!/usr/bin/env bash
# Steady order flow so the OTel demo has something live to look at.
#
# Every order carries a distinct clientOrderId, which is what the trace identity is DERIVED from
# (see docs/handoff/production-readiness/05-RESULT-opentelemetry-observability.md) — so each order
# is its own searchable trace rather than a duplicate suppressed by the idempotency ledger.
#
# Usage:
#   bash scripts/yu15/demo-otel-traffic.sh            # 120 orders, ~2/s
#   RATE=10 COUNT=600 bash scripts/yu15/demo-otel-traffic.sh
set -uo pipefail

MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
COUNT="${COUNT:-120}"
RATE="${RATE:-2}"
TICKER="${TICKER:-AAPL}"
TAG="${TAG:-demo-$(date +%H%M%S)}"

curl -sf --max-time 5 "${MATCHER_URL}/health" >/dev/null || {
  echo "[fail] gateway not reachable at ${MATCHER_URL}"
  echo "       kubectl --context kind-traderx-yu12-cluster -n traderx port-forward svc/order-matcher 18110:18110 &"
  exit 1
}

# Seeding is idempotent — the control events land in the consensus log, so re-running is a no-op on
# an already-seeded cluster. Without it the risk gate rejects every order with UNKNOWN_ACCOUNT: the
# order still SEQUENCES (next_order_ref advances) and still produces a full trace, so a demo that
# watched only the counter or only Tempo would look perfectly healthy while booking nothing.
echo "[seed] enabling the 7 real accounts + ticker universe (idempotent)"
for A in 10031 11413 22214 42422 44044 52355 62654; do
  curl -s -m20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${A},\"tickers\":\"AAPL,MSFT,IBM,META,AMZN,GOOG,NVDA\",\"price\":150}" >/dev/null
done

echo "[run] ${COUNT} orders at ~${RATE}/s, tag=${TAG}  (ctrl-C to stop early)"
booked=0; rejected=0
for i in $(seq 1 "${COUNT}"); do
  # Alternate side AND account. Two accounts is not cosmetic: YU13 ships self-trade prevention, so
  # a single account buying and selling the same book never trades (STP cancels the resting side)
  # and traderx_cluster_trades stays flat while every order is happily "accepted".
  if [ $((i % 2)) -eq 0 ]; then side=Sell; acct=42422; else side=Buy; acct=22214; fi
  # kind=1 accepted, kind=2 rejected. Read the BODY, not the status: a rejected order has been
  # sequenced by consensus, so "the HTTP call worked" is not the same as "the order booked".
  body="$(curl -s --max-time 10 \
    -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${acct},\"ticker\":\"${TICKER}\",\"side\":\"${side}\",\"quantity\":10,\"limitPrice\":150.0,\"clientOrderId\":\"${TAG}-${i}\"}")"
  case "${body}" in
    *'"kind":1'*) booked=$((booked+1)) ;;
    *) rejected=$((rejected+1)); last_reject="${body}" ;;
  esac
  printf '\r   sent %d/%d  accepted=%d  rejected=%d' "${i}" "${COUNT}" "${booked}" "${rejected}"
  sleep "$(python3 -c "print(1/${RATE})")"
done
echo
[ "${rejected}" -gt 0 ] && echo "   last rejection: ${last_reject:-}"

# Ground truth from the member, not the gateway. NOTE next_order_ref counts SEQUENCED orders,
# including ones the risk gate rejected — it proves consensus is committing, not that anything
# traded. traderx_cluster_trades is the booking-side counter.
read -r ref trades <<<"$(kubectl --context kind-traderx-yu12-cluster -n traderx exec order-matcher-cluster-0 -- \
  sh -lc 'curl -s localhost:8080/metrics' 2>/dev/null |
  awk '/^traderx_cluster_next_order_ref/{r=$2} /^traderx_cluster_trades/{t=$2} END{print r, t}')"
echo "[done] accepted=${booked} rejected=${rejected}; member next_order_ref=${ref:-?} trades=${trades:-?}"
echo "       search these in Grafana Explore -> Tempo:  {name=\"order\"}   (tag ${TAG})"
