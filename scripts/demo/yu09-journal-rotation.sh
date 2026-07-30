#!/usr/bin/env bash
# YU09 — PROOF (live): with journal archival enabled, every snapshot boundary rotates the active
# order-matcher journal to a NEW immutable segment file (input-events-<epoch-millis>.journal),
# bounding the active file instead of letting one journal grow without limit (FR-OH20, SC-OH04).
# The rotation is driven by the internal SNAPSHOT event on the input ring, so it happens on the
# snapshot timer regardless of order flow. Without the optional order-matcher-journal-gcs-hmac
# Secret, the closed segment is KEPT on disk (the GCS upload leg logs a warning and never deletes
# an unconfirmed segment — FR-OH23 fail-safe).
#
# Prereq: kube context on the YU09 kind cluster; order-matcher running the YU09 image.
# Usage: bash yu09-journal-rotation.sh   [SNAP_MS]   (default 20000)
set -uo pipefail
NS=${NS:-traderx}
SNAP_MS=${1:-20000}
JDIR=/var/lib/traderx-lmax/journal
seg_count() { kubectl exec -n "$NS" deploy/order-matcher -- sh -c "ls $JDIR/input-events-*.journal 2>/dev/null | wc -l" 2>/dev/null | tr -d ' '; }
seg_list()  { kubectl exec -n "$NS" deploy/order-matcher -- sh -c "ls -1 $JDIR/input-events-*.journal 2>/dev/null" 2>/dev/null; }

echo "── journal rotation at the snapshot boundary (SC-OH04) ──"
enabled=$(kubectl set env deploy/order-matcher -n "$NS" --list 2>/dev/null | grep ORDER_MATCHER_JOURNAL_ARCHIVE_ENABLED | cut -d= -f2)
printf "   %-34s %s\n" "archival enabled" "${enabled:-<unset>}"
before=$(seg_count)
printf "   %-34s %s rotated segment(s)\n" "before" "${before:-0}"

# force a short snapshot interval so a boundary comes quickly (triggers a rollout).
printf "   %-34s %sms (restarting order-matcher)\n" "set snapshot interval" "$SNAP_MS"
kubectl set env deploy/order-matcher -n "$NS" \
  ORDER_MATCHER_JOURNAL_ARCHIVE_ENABLED=true SNAPSHOT_INTERVAL_MS="$SNAP_MS" >/dev/null 2>&1
kubectl rollout status deploy/order-matcher -n "$NS" --timeout=120s >/dev/null 2>&1 \
  || { echo "   order-matcher not Ready after restart ✘"; exit 1; }

# poll for a NEW segment to appear at the next snapshot boundary.
echo "   ── waiting for a snapshot boundary to rotate the active journal ──"
deadline=$(( SNAP_MS/1000 + 60 )); waited=0; step=5; now=0
while [ "$waited" -lt "$deadline" ]; do
  sleep "$step"; waited=$(( waited + step ))
  now=$(seg_count)
  printf "   t+%2ds  rotated segments=%s  active=input-events.journal\n" "$waited" "${now:-0}"
  [ "${now:-0}" -gt "${before:-0}" ] && break
done

if [ "${now:-0}" -gt "${before:-0}" ]; then
  echo "   ── rotated segment(s) now on disk ──"
  seg_list | sed 's|.*/|      |'
  kubectl exec -n "$NS" deploy/order-matcher -- ls -la "$JDIR" 2>/dev/null \
    | grep -E 'input-events' | sed 's/^/      /'
  echo "   ── archiver behaviour without the GCS HMAC Secret (FR-OH23 fail-safe) ──"
  kubectl logs deploy/order-matcher -n "$NS" 2>&1 | grep -iE 'archiv|segment|upload|gcs|hmac' | tail -4 | sed 's/^/      /'
  echo "   → snapshot boundary closed the active journal into an immutable segment ✔"
else
  echo "   → no new segment in ${deadline}s ✘ (check SNAPSHOT_INTERVAL_MS + archival enabled)"
fi
