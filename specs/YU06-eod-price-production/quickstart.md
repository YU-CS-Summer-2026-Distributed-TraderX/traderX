# Quickstart: YU06-eod-price-production

## Local (kind)

### First run

```bash
bash pipeline/generate-state.sh YU06-eod-price-production
bash generated/code/target-generated/scripts/start-state-YU06-eod-price-production-generated.sh \
  --provider kind --without-sail
```

UI at **http://127.0.0.1:8080**. This inherits the `YU05-post-trade-compliance` (`YU02`) kind
runtime unchanged; only `trade-processor` and `position-service` carry the EOD overlay.

### Subsequent runs (skip rebuild if code unchanged)

```bash
bash generated/code/target-generated/scripts/start-state-YU06-eod-price-production-generated.sh \
  --provider kind --without-sail --skip-build
```

### Validate

```bash
bash scripts/test-state-YU06-eod-price-production.sh
```

### Stop

```bash
bash generated/code/target-generated/scripts/stop-state-YU06-eod-price-production-generated.sh
```

---

## Demo the EOD chain

```bash
TRADE_PROCESSOR_URL="${TRADE_PROCESSOR_URL:-http://localhost:18091}"

# 1. Mint an admin token (local dev only — no live OIDC provider in this environment)
TOKEN=$(curl -s -X POST "$TRADE_PROCESSOR_URL/auth/dev-token" \
  -H "X-Auth-Master-Secret: dev-token-master-secret" \
  -H "Content-Type: application/json" \
  -d '{"subject":"demo","accounts":[],"admin":true,"ttlSeconds":600}')

# 2. Close today's session (produces a snapshot version; auto-publishes if nothing is flagged)
curl -s -X POST "$TRADE_PROCESSOR_URL/eod/session/close" \
  -H "Authorization: Bearer $TOKEN" | jq .

# 3. Inspect the latest version for a date
curl -s "$TRADE_PROCESSOR_URL/eod/prices/$(date +%F)" \
  -H "Authorization: Bearer $TOKEN" | jq .

# 4. If publish was blocked (flaggedCount > 0), override the flagged instrument, then publish
curl -s -X POST "$TRADE_PROCESSOR_URL/eod/prices/$(date +%F)/override" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"security":"IBM","price":136.25,"reason":"manual close"}' | jq .
curl -s -X POST "$TRADE_PROCESSOR_URL/eod/prices/$(date +%F)/publish" \
  -H "Authorization: Bearer $TOKEN" | jq .
```

Publishing emits `EOD_PRICES_READY`; `position-service`'s durable consumer picks it up and writes
`eod_position_pnl` rows, then emits `eod.pnl.done`.

## Inspect EOD observability

```bash
TRADE_PROCESSOR_PORT="${TRADE_PROCESSOR_PORT:-18091}"
POSITION_SERVICE_PORT="${POSITION_SERVICE_PORT:-18090}"

# producer metrics
curl -s "http://localhost:${TRADE_PROCESSOR_PORT}/actuator/prometheus" \
  | rg "traderx_eod_sessions_published_total|traderx_eod_quality_flagged_total"

# consumer metrics
curl -s "http://localhost:${POSITION_SERVICE_PORT}/actuator/prometheus" \
  | rg "traderx_eod_pnl_accounts_marked_total|traderx_eod_pnl_halted_total"

# dashboard landing (traderx-eod-batch-chain)
open http://localhost:3001
```
