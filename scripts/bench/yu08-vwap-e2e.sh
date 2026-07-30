#!/usr/bin/env bash
# YU08 — PROOF: a VWAP parent order end to end. First its buckets are sized by a VOLUME PROFILE, not
# a flat split (FR-AE03, SC-AE05): the targets follow the intraday U-curve and differ from the equal
# TWAP split of the same quantity. The profile comes from a pluggable VolumeProfileSource — the
# default `synthetic` U-curve, or `duckdb` reading YU07's real tick store, which falls back to the
# SAME synthetic curve for any security with no historical trade rows (FR-AE09), so enabling it never
# blocks or fails an order. Then the SAME weighted order runs to completion: each weighted bucket is
# submitted to order-matcher's shared POST /orders as it comes due, observed accepted and filled,
# parent COMPLETED (SC-AE04, FR-AE04/05 — identical execution path to TWAP, only the sizing differs).
#
# Prereq: kube context on the YU08 kind cluster; execution-algo-engine Ready.
# Usage: bash yu08-vwap-e2e.sh
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/yu08-common.sh"
algo_pf || exit 1

QTY=${QTY:-600}; DUR=${DUR:-60}; BKT=${BKT:-10}
SRC=$(kubectl get deploy execution-algo-engine -n "$NS" \
        -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="ALGO_VOLUME_PROFILE_SOURCE")].value}' 2>/dev/null)
echo "── VWAP vs TWAP: $QTY IBM over ${DUR}s / ${BKT}s buckets  (profile source: ${SRC:-synthetic}) ──"

vwap=$(algo_post "{\"accountId\":22214,\"security\":\"IBM\",\"side\":\"Sell\",\"quantity\":$QTY,\"algoType\":\"VWAP\",\"durationSeconds\":$DUR,\"bucketSeconds\":$BKT}")
twap=$(algo_post "{\"accountId\":22214,\"security\":\"IBM\",\"side\":\"Sell\",\"quantity\":$QTY,\"algoType\":\"TWAP\",\"durationSeconds\":$DUR,\"bucketSeconds\":$BKT}")
PID=$(echo "$vwap" | jfield '"parentOrderId"')
[ -z "$PID" ] && { echo "   VWAP POST failed: $vwap"; exit 1; }

python3 - "$vwap" "$twap" <<'PY'
import sys,json
vwap=json.loads(sys.argv[1]); twap=json.loads(sys.argv[2])
vt=[b["targetQuantity"] for b in vwap["buckets"]]
tt=[b["targetQuantity"] for b in twap["buckets"]]
print("   %-16s %s  sum=%d" % ("TWAP (flat)", tt, sum(tt)))
print("   %-16s %s  sum=%d" % ("VWAP (profile)", vt, sum(vt)))
flat = len(set(vt))<=2   # a flat split is all-equal but for the remainder bucket
print("   %-16s %s" % ("differ?", "YES — VWAP is volume-weighted, not a flat split ✔" if vt!=tt and not flat
                        else "NO — weights look flat ✘"))
PY
printf "   %-16s %s\n" "parentOrderId" "$PID"

# now run THIS weighted order to completion — same execution path as TWAP, weighted slices
echo "   ── execution (each weighted bucket → order-matcher as it comes due) ──"
deadline=$(( DUR + BKT + 15 )); waited=0; step=5; line=""
while [ "$waited" -lt "$deadline" ]; do
  sleep "$step"; waited=$(( waited + step ))
  line=$(algo_get "$PID" | bucket_summary)
  printf "   t+%2ds  %s\n" "$waited" "$line"
  case "$line" in COMPLETED*) break;; esac
done
echo "$line" | grep -q '^COMPLETED' \
  && echo "   → volume-weighted children accepted by order-matcher, filled, parent COMPLETED ✔" \
  || echo "   → did not complete in ${deadline}s ✘"
