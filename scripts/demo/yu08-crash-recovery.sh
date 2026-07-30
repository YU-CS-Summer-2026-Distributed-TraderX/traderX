#!/usr/bin/env bash
# YU08 — PROOF: the engine owns its durability. Every parent-order transition is appended to the
# TRADERX_ALGO_ENGINE JetStream stream BEFORE it is applied in memory (FR-AE07), so killing the
# pod mid-run and letting Kubernetes reschedule it resumes the SAME parent order — schedule and
# already-observed fills replayed from the stream, no re-submission of already-sent buckets, no
# operator intervention (SC-AE06, FR-AE08, NFR-AE03).
#
# Prereq: kube context on the YU08 kind cluster; execution-algo-engine Ready.
# Usage: bash yu08-crash-recovery.sh
here="$(cd "$(dirname "$0")" && pwd)"; . "$here/yu08-common.sh"
algo_pf || exit 1

QTY=${QTY:-500}; DUR=${DUR:-60}; BKT=${BKT:-10}   # 6 buckets — long enough to kill mid-flight
echo "── durable resume: $QTY IBM Buy over ${DUR}s / ${BKT}s buckets ──"
PID=$(algo_post "{\"accountId\":22214,\"security\":\"IBM\",\"side\":\"Buy\",\"quantity\":$QTY,\"algoType\":\"TWAP\",\"durationSeconds\":$DUR,\"bucketSeconds\":$BKT}" | jfield '"parentOrderId"')
[ -z "$PID" ] && { echo "   POST failed"; exit 1; }
printf "   %-22s %s\n" "parentOrderId" "$PID"

# let ~2-3 buckets submit, then snapshot the children we must NOT lose or duplicate
sleep $(( BKT*2 + 2 ))
before=$(algo_get "$PID")
echo "   BEFORE KILL   $(echo "$before" | bucket_summary)"
before_children=$(echo "$before" | python3 -c 'import sys,json;print(",".join(x["childOrderId"] for x in json.load(sys.stdin)["buckets"] if x.get("childOrderId")))')

echo "   ── kubectl delete pod (crash mid-run) ──"
algo_pf_stop
algo_kill_and_wait
algo_pf || { echo "   replacement pod not reachable ✘"; exit 1; }

after=$(algo_get "$PID")
echo "   AFTER RESTART $(echo "$after" | bucket_summary)   (rebuilt from JetStream, not re-created)"
# the pre-kill children must all still be present (replayed, not lost)
echo "$after" | python3 -c '
import sys,json
o=json.load(sys.stdin)
have=set(x["childOrderId"] for x in o["buckets"] if x.get("childOrderId"))
need=set(filter(None, "'"$before_children"'".split(",")))
print("   %-22s %s" % ("pre-kill children kept", "YES ✔" if need<=have else "MISSING %s ✘" % (need-have)))'

# let it finish; every submitted bucket must map to a UNIQUE child (no double-submit on replay)
deadline=$(( DUR + BKT + 20 )); waited=0
while [ "$waited" -lt "$deadline" ]; do
  sleep 5; waited=$(( waited + 5 ))
  line=$(algo_get "$PID" | bucket_summary)
  case "$line" in COMPLETED*) break;; esac
done
printf "   FINAL         %s\n" "$line"
echo "$line" | python3 -c '
import sys
l=sys.stdin.read()
import re
sub=int(re.search(r"submitted=(\d+)/(\d+)",l).group(1)); n=int(re.search(r"submitted=\d+/(\d+)",l).group(1))
uniq=int(re.search(r"uniqueChildren=(\d+)",l).group(1))
ok = l.startswith("COMPLETED") and uniq==sub==n
print("   → resumed from the stream, no bucket re-submitted (%d unique children == %d buckets), COMPLETED %s"
      % (uniq, n, "✔" if ok else "✘"))'
