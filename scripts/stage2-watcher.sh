#!/usr/bin/env bash
# Autonomous Stage 2 watcher: waits for BOTH the tick-store-stage2 Job AND the one-off
# tick-store-stage2-retry-311 Job (added 2026-07-12 to handle taq_quotes_20250311_csv.zip, the one
# file too large for the batch job's memory limit -- index 25 there will deterministically OOM
# forever and exhaust its own backoffLimit; that Job going Failed is EXPECTED, not an alarm, as
# long as retry-311 succeeds) to each reach a terminal state (Complete or Failed), then after a 90s
# grace period scales default-pool AND batch-private-pool to 0. If retry-311 isn't present (e.g. a
# future run of this script with no OOM outlier), it's treated as already-terminal so the original
# single-job wait behaves exactly as before. Appends everything it does to STAGE2-STATUS.txt.
STATUS="/Users/yaakov/dev/lmax/traderX-YU07-historical-tick-store/STAGE2-STATUS.txt"
log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$STATUS"; }

job_terminal_state() {
  local job="$1"
  if ! kubectl get job "$job" -n traderx >/dev/null 2>&1; then
    echo "absent"; return
  fi
  local complete failed
  complete=$(kubectl get job "$job" -n traderx -o jsonpath='{.status.conditions[?(@.type=="Complete")].status}' 2>/dev/null)
  failed=$(kubectl get job "$job" -n traderx -o jsonpath='{.status.conditions[?(@.type=="Failed")].status}' 2>/dev/null)
  if [[ "$complete" == "True" ]]; then echo "complete"
  elif [[ "$failed" == "True" ]]; then echo "failed"
  else echo "running"
  fi
}

log "Watcher (re)started. Polling tick-store-stage2 + tick-store-stage2-retry-311 every 60s."
main_reported=""
retry_reported=""
while true; do
  main_state=$(job_terminal_state tick-store-stage2)
  retry_state=$(job_terminal_state tick-store-stage2-retry-311)

  if [[ -z "$main_reported" && "$main_state" != "running" ]]; then
    succeeded=$(kubectl get job tick-store-stage2 -n traderx -o jsonpath='{.status.succeeded}' 2>/dev/null)
    log "tick-store-stage2 reached ${main_state} (${succeeded:-0}/28 succeeded within this Job)."
    main_reported=1
  fi
  if [[ -z "$retry_reported" && "$retry_state" != "running" ]]; then
    log "tick-store-stage2-retry-311 reached ${retry_state}."
    retry_reported=1
  fi

  if [[ "$main_state" != "running" && "$retry_state" != "running" ]]; then
    log "Both jobs terminal (main=${main_state}, retry-311=${retry_state}). Proceeding to shutdown."
    break
  fi
  sleep 60
done

log "90s grace period before scale-down..."
sleep 90
# Scale BOTH pools the batch job used to 0 so nothing bills after completion. blp-pool is
# already 0. batch-private-pool was added for NAT/private-node parallelism -- it must be
# scaled down too or its 4 nodes keep billing.
rc=0
for pool in default-pool batch-private-pool; do
  log "Scaling ${pool} to 0..."
  if gcloud container clusters resize traderx-lmax --node-pool "${pool}" --num-nodes 0 --zone us-east1-b --quiet >> "$STATUS" 2>&1; then
    log "${pool} scaled to 0."
  else
    log "ERROR: ${pool} scale-down FAILED -- nodes may still be running/billing. Check manually."
    rc=1
  fi
done
[[ $rc -eq 0 ]] && log "All pools at 0 (blp-pool was already 0). Cluster fully offline. Done." || log "Scale-down had errors -- verify node pools manually."
