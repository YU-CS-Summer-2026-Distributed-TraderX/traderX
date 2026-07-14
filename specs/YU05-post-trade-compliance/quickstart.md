# Quickstart: YU05-post-trade-compliance

A back-office compliance layer downstream of the BLP: settlement + reconciliation, journal-sourced
regulatory reporting, TCA, and real JWT auth/entitlements gating every new endpoint.

## Local (kind)

### First run

```bash
bash pipeline/generate-state.sh YU05-post-trade-compliance
bash generated/code/target-generated/scripts/start-state-YU05-post-trade-compliance-generated.sh \
  --provider kind --without-sail
```

UI at **http://127.0.0.1:8080**. Same kind harness as YU04/YU03/YU02 (`traderx-state-014` cluster);
this state adds order-matcher and trade-processor overlays only.

> **If you already have the cluster running from a previous deploy**, wipe and rebuild cleanly:
> ```bash
> bash generated/code/target-generated/scripts/start-state-YU05-post-trade-compliance-generated.sh \
>   --provider kind --without-sail --recreate-cluster
> ```

### Subsequent runs (skip rebuild if code unchanged)

```bash
bash generated/code/target-generated/scripts/start-state-YU05-post-trade-compliance-generated.sh \
  --provider kind --without-sail --skip-build
```

### Validate

```bash
bash scripts/test-state-YU05-post-trade-compliance.sh
```

### Stop

```bash
bash generated/code/target-generated/scripts/stop-state-YU05-post-trade-compliance-generated.sh
# or delete the cluster entirely:
kind delete cluster --name traderx-state-014
```

---

## Demo the post-trade compliance APIs

Every endpoint requires a real JWT. Mint one with the dev-token endpoint (local dev only — no live
OIDC provider in this environment), then call the settlement/recon/TCA/regulatory surfaces.

```bash
TRADE_PROCESSOR_URL="${TRADE_PROCESSOR_URL:-http://localhost:18091}"
ORDER_MATCHER_URL="${ORDER_MATCHER_URL:-http://127.0.0.1:8080/order-matcher}"

# 1. Mint an admin token (admin claim → access to the cross-account endpoints)
TOKEN=$(curl -s -X POST "$TRADE_PROCESSOR_URL/auth/dev-token" \
  -H "X-Auth-Master-Secret: dev-token-master-secret" \
  -H "Content-Type: application/json" \
  -d '{"subject":"demo","accounts":[],"admin":true,"ttlSeconds":600}')

# 2. Reconciliation status (matched / missing / mismatch counts + cursor)
curl -s "$TRADE_PROCESSOR_URL/recon/status" \
  -H "Authorization: Bearer $TOKEN" | jq .

# 3. TCA for one trade (arrival price, TWAP benchmark, signed slippage-bps)
#    Use a real trade id from the blotter; {tradeId} is the deterministic journal-sourced id.
curl -s "$TRADE_PROCESSOR_URL/tca/report/<tradeId>" \
  -H "Authorization: Bearer $TOKEN" | jq .

# 4. Regulatory audit export over an input-sequence range (journal-sourced, reproducible)
curl -s "$ORDER_MATCHER_URL/regulatory/report?fromSeq=0&toSeq=1000000" \
  -H "Authorization: Bearer $TOKEN" | jq '.records | length'

# 5. Full-history orphan sweep (admin): reindex the whole journal, then flag projection orphans
curl -s -X POST "$ORDER_MATCHER_URL/recon/full-history/reindex" -H "Authorization: Bearer $TOKEN"
curl -s -X POST "$TRADE_PROCESSOR_URL/recon/orphan-sweep" -H "Authorization: Bearer $TOKEN" | jq .
```

An account-scoped token (`admin:false`, `accounts:[<id>]`) can hit the account-scoped endpoints
(settlement force, TCA for its own account) but is rejected from the cross-account ones (blotter,
full-history, orphan-sweep, regulatory report).

## Inspect post-trade observability

```bash
TRADE_PROCESSOR_PORT="${TRADE_PROCESSOR_PORT:-18091}"

curl -s "http://localhost:${TRADE_PROCESSOR_PORT}/actuator/prometheus" \
  | rg "traderx_recon_matched_total|traderx_recon_missing_in_projection_total|traderx_settlement_swept_total|traderx_recon_orphan_total"

# dashboard: traderx-post-trade-compliance
open http://localhost:3001
```

---

## Runtime notes

- **Deterministic trade id**: a MariaDB trade row's id is `OrderSnapshot.tradeIdFor(tradeSeq)` from
  the journal fill that produced it, so reconciliation and TCA can link a row to its journal event.
- **Settlement lifecycle**: booked trades start `Processing` with a T+N settlement date
  (`settlement.t-plus-days`, default T+1 business day) and advance to `Settled` on a scheduled sweep
  or a manual force.
- **Read-only against the BLP**: full-history reindex and regulatory reports are shadow-engine
  replays; settlement/recon write only trade-processor's own MariaDB rows. The BLP admission path,
  journal, and snapshot format are untouched.
- Everything from YU04's/YU03's/YU02's runtime notes still applies — see their quickstarts.
