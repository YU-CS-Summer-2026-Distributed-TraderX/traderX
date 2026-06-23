#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "${ROOT}/test-state-009b-lmax-sequencer-architecture.sh" "$@"

ORDER_MATCHER_URL="${ORDER_MATCHER_URL:-http://127.0.0.1:18110}"
ACCOUNT_SERVICE_URL="${ACCOUNT_SERVICE_URL:-http://127.0.0.1:18088}"
REFERENCE_DATA_URL="${REFERENCE_DATA_URL:-http://127.0.0.1:18085}"

metrics="$(curl -fsS "${ORDER_MATCHER_URL}/metrics")"
for family in \
  traderx_gateway_validation_latency_seconds \
  traderx_risk_decision_latency_seconds \
  traderx_replica_ready \
  traderx_replica_rebootstrap_total \
  traderx_risk_reserved_notional_total \
  traderx_control_update_rejected_total; do
  if ! grep -q "${family}" <<<"${metrics}"; then
    echo "[error] missing in-memory risk metric: ${family}" >&2
    exit 1
  fi
done

account_snapshot="$(curl -fsS "${ACCOUNT_SERVICE_URL}/account/control/snapshot")"
risk_snapshot="$(curl -fsS "${ACCOUNT_SERVICE_URL}/risk-admin/control/snapshot")"
security_snapshot="$(curl -fsS "${REFERENCE_DATA_URL}/stocks/control/snapshot")"
grep -q '"watermark"' <<<"${account_snapshot}"
grep -q '"watermark"' <<<"${risk_snapshot}"
grep -q '"watermark"' <<<"${security_snapshot}"

echo "[ok] in-memory risk snapshots, readiness, and bounded metric families verified"
