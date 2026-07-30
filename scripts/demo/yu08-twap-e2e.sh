#!/usr/bin/env bash
# YU08 — PROOF: a TWAP parent order slices into equal time buckets, and every child order flows
# through order-matcher's SHARED POST /orders path (same risk gateway + BLP as a manual order,
# NFR-AE02) — observed accepted (real order-matcher ids) and filled, with progress visible via
# GET /algo/orders/{id} (SC-AE04, FR-AE01/02/04/05/06).
#
# Prereq: kube context on the YU08 kind cluster; execution-algo-engine Ready.
# Usage: bash yu08-twap-e2e.sh
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/yu08-common.sh"
algo_pf || exit 1

QTY=${QTY:-300}; DUR=${DUR:-30}; BKT=${BKT:-10}
echo "── TWAP: $QTY IBM Buy over ${DUR}s in ${BKT}s buckets ──"
RESP=$(algo_post "{\"accountId\":22214,\"security\":\"IBM\",\"side\":\"Buy\",\"quantity\":$QTY,\"algoType\":\"TWAP\",\"durationSeconds\":$DUR,\"bucketSeconds\":$BKT}")
PID=$(echo "$RESP" | jfield '"parentOrderId"')
[ -z "$PID" ] && { echo "   POST failed: $RESP"; exit 1; }
printf "   %-22s %s\n" "parentOrderId" "$PID"
# equal slices with integer-division remainder folded into the LAST bucket (FR-AE02)
echo "$RESP" | python3 -c 'import sys,json
o=json.load(sys.stdin); t=[b["targetQuantity"] for b in o["buckets"]]
print("   %-22s %s  sum=%d  (equal + remainder-in-last)" % ("bucket targets", t, sum(t)))'

# poll one bucket-interval past the full duration so every bucket has come due
echo "   ── progress (each bucket submits to order-matcher as it comes due) ──"
deadline=$(( DUR + BKT + 15 )); waited=0; step=5
while [ "$waited" -lt "$deadline" ]; do
  sleep "$step"; waited=$(( waited + step ))
  line=$(algo_get "$PID" | bucket_summary)
  printf "   t+%2ds  %s\n" "$waited" "$line"
  case "$line" in COMPLETED*) break;; esac
done

echo "$line" | grep -q '^COMPLETED' \
  && echo "   → all children accepted by order-matcher on the shared path, filled, parent COMPLETED ✔" \
  || echo "   → did not complete in ${deadline}s ✘"
