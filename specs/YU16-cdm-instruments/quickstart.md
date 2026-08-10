# Quickstart: CDM Instruments

## Local (kind)

The state runs on the YU15 Aeron cluster rig (`kind-traderx-yu12-cluster`, namespace `traderx`);
the YU16 tree composes over YU15 and the same bring-up scripts build and run it.

```bash
# 1. Generate the composed tree (recursively generates YU15 and its ancestry)
bash pipeline/generate-state.sh YU16-cdm-instruments

# 2. Build the cluster image from the composed tree and (re)start the rig
bash scripts/yu15/build-cluster-image.sh
bash scripts/yu15/start-cluster-kind.sh

# 3. Attach the Angular UI (asset-class filter, Treasury tickets)
bash scripts/yu15/start-frontend-kind.sh

# 4. Seed fixtures and run the whole proof suite
bash scripts/yu15/seed-proof-fixtures.sh
bash scripts/yu15/run-proofs.sh
```

On a rig that is already running this state's services, step 2 is a rolling deployment update —
no scale-to-zero, no PVC wipe and no fresh epoch is needed or performed (NFR-CDM03). The one
ordering rule (ADR-060): every member runs this state's image BEFORE any `UST-` security is
registered — which the scripts guarantee, since seeding (step 4) follows the roll (step 2).

## The instrument model, by hand

With reference-data forwarded on 18085:

```bash
# CDM views
curl -s localhost:18085/instruments | jq length
curl -s localhost:18085/instruments/SPY | jq '{securityType, fundType, identifiers}'
curl -s localhost:18085/instruments/UST-20360515 \
  | jq '{securityType, shortDisplayName, matured, debtEconomics: {coupon: .debtEconomics.fixedInterest.couponRatePercent, maturity: .debtEconomics.maturityDate}}'

# The retained routes (FR-CDM09/10) and the general snapshot (FR-CDM11)
curl -s -o /dev/null -w '%{http_code}\n' localhost:18085/stocks            # 200
curl -s localhost:18085/stocks/control-snapshot | jq '.watermark'
curl -s localhost:18085/instruments/control-snapshot | jq '.watermark'    # same watermark
```

## Trading a Treasury

With the gateway forwarded (svc/order-matcher fronts cluster-gateway on this rig):

```bash
# Face 100,000 at 99.886% of par — limitPrice is the FRACTION (ADR-057)
curl -s -XPOST localhost:18110/orders -H 'Content-Type: application/json' -d '{
  "accountId": 17017, "ticker": "UST-20280630", "side": "BUY",
  "quantity": 100000, "limitPrice": 0.998860
}' | jq

# Face validation happens before the engine (FR-CDM16): both of these are rejected
curl -s -XPOST localhost:18110/orders -d '{"accountId":17017,"ticker":"UST-20280630","side":"BUY","quantity":50,"limitPrice":0.998860}'
curl -s -XPOST localhost:18110/orders -d '{"accountId":17017,"ticker":"UST-20280630","side":"BUY","quantity":150,"limitPrice":0.998860}'
```

## Watching Treasury pricing

```bash
# JSON payload: cleanPrice is a fraction; YTM is publisher-computed (FR-CDM19/20)
kubectl -n traderx exec deploy/price-publisher -- wget -qO- localhost:18100/prices/UST-20560515 \
  | jq '{price, cleanPrice, priceSemantics, approximateYtmPercent, maturityDate, matured}'
```

## Verifying the extract (schema 2)

Run a session with a Treasury fill, publish EOD, and inspect the delivered fixture: the header
reads `# traderx-risk-extract schema=2`, the bond row carries `TREASURY` with its coupon and
maturity, and `costBasis`/`closingMark` are fractions of par at scale 6. Rebuilding from the
stored cut reproduces identical bytes (inherited FR-RXT10; the cut format is unchanged).

## Proofs

`scripts/yu15/run-proofs.sh` runs everything, including this state's Treasury pricing and bond
position proofs and the YU04 pair on the general snapshot route. `scripts/proofs/README.md` has
the by-hand order and the port-forwards each proof needs.

## Configuration

| Variable | Default | Effect |
|---|---|---|
| `REFERENCE_DATA_SUPPORTED_TICKERS` | inherited list + `SPY,QQQ,IWM,VTI,GLD` + the five `UST-*` keys | the served universe (name literal per source FR-01704; values are general keys) |
| `PRICE_TICKERS` | same additions | the quoted universe |
| `RISK_BOOTSTRAP_SECURITIES_SNAPSHOT_URL` | `http://reference-data:18085/instruments/control-snapshot` | bootstrap snapshot source (repointed default at this layer; env override unchanged) |
| `REFERENCE_DATA_CONNECT_TIMEOUT_MS` / `REFERENCE_DATA_READ_TIMEOUT_MS` | `2000` / `5000` | trade-processor metadata client (fail closed on expiry) |
| `TRADERX_FIXED_UTC_INSTANT` | unset | pins the clock for maturity behavior in reference-data and price-publisher |
