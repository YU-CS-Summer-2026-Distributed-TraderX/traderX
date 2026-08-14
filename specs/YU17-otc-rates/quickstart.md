# Quickstart: OTC Interest-Rate Swaps and Swaptions

## Local (kind)

The state runs on the YU15 Aeron cluster rig (`kind-traderx-yu12-cluster`, namespace `traderx`);
the YU17 tree composes over YU16 and the same bring-up scripts build and run it.

```bash
# 1. Generate the composed tree (recursively generates YU16 and its ancestry)
bash pipeline/generate-state.sh YU17-otc-rates

# 2. Build the cluster image from the composed tree and (re)start the rig
bash scripts/yu15/build-cluster-image.sh
bash scripts/yu15/start-cluster-kind.sh

# 3. Attach the Angular UI
bash scripts/yu15/start-frontend-kind.sh

# 4. Seed fixtures and run the whole proof suite, then this state's headline proof
bash scripts/yu15/seed-proof-fixtures.sh
bash scripts/yu15/run-proofs.sh
bash scripts/proofs/yu17-swap-netting.sh
bash scripts/proofs/yu17-swaption-terms.sh
```

On a rig already running `YU16-cdm-instruments`, step 2 is a roll FORWARD onto the existing epoch:
`MIN_READABLE_SNAPSHOT_FORMAT` stays at 3, so the format-4 snapshot on disk restores here
untouched. The same holds for a format-5 snapshot from this state's first phase — its eight-column
contract records are read at their own width and restore as swaps. No scale-to-zero, no PVC wipe,
no fresh epoch (NFR-OTC05).

Rolling BACK is the direction that costs an epoch. A format-6 snapshot is unreadable by a
`YU16-cdm-instruments` build, which refuses it at the header naming the direction of the mismatch.
The remedy is to roll forward again; wiping the PVCs is not required to recover the snapshot, only
to run the older build.

`.claude/skills/prove-cluster-engine-change` applies to any change to the apply path in this state:
a deterministic-core change cannot be rolled gradually, and the log tail is itself a mixed-version
window. Take a snapshot barrier on all three members before rolling.

## Booking a swap and a swaption by hand

With the gateway forwarded (`svc/order-matcher` fronts `cluster-gateway` on this rig):

```bash
kubectl -n traderx port-forward svc/order-matcher 18110:18110 &
```

```bash
# Account 22214 must be enabled — /seed sequences an ACCOUNT_CONTROL that does it
curl -s -X POST localhost:18110/seed -H 'Content-Type: application/json' \
  -d '{"accountId":22214,"tickers":"AAPL","price":150.00}'
```

```bash
# Receive fixed 4.2% on 10mm for five years
curl -s -X POST localhost:18110/swaps -H 'Content-Type: application/json' -d '{
  "clientOrderId":"demo-recv-1",
  "accountId":22214,
  "payReceive":"Receive",
  "notional":10000000,
  "fixedRate":0.042,
  "effectiveDate":"2026-08-17",
  "maturityDate":"2031-08-17",
  "conventions":"USD-SOFR-1Y-ACT360"}'
```

```bash
# The offsetting leg: pay fixed 4.3% on the same notional and dates
curl -s -X POST localhost:18110/swaps -H 'Content-Type: application/json' -d '{
  "clientOrderId":"demo-pay-1",
  "accountId":22214,
  "payReceive":"Pay",
  "notional":10000000,
  "fixedRate":0.043,
  "effectiveDate":"2026-08-17",
  "maturityDate":"2031-08-17",
  "conventions":"USD-SOFR-1Y-ACT360"}'
```

Each returns `{"contractId":"SW-<N>","sequence":N,"booked":true}` where N is the consensus sequence
the booking landed at. Re-sending either body verbatim returns the SAME contract id — the
idempotency key makes a retried confirmation safe.

A swaption on the same underlying — the swap body plus an expiry and a style:

```bash
curl -s -X POST localhost:18110/swaptions -H 'Content-Type: application/json' -d '{
  "clientOrderId":"demo-swpt-1",
  "accountId":22214,
  "payReceive":"Pay",
  "notional":25000000,
  "fixedRate":0.0415,
  "effectiveDate":"2027-02-15",
  "maturityDate":"2032-02-15",
  "conventions":"USD-SOFR-1Y-ACT360",
  "expiryDate":"2027-02-15",
  "exerciseStyle":"Bermudan"}'
```

Returns `{"contractId":"SWPT-<N>",…}`. Every field except the last two describes the UNDERLYING
swap: `fixedRate` is the strike, and `"Pay"` makes it a payer swaption. Send the same body with
`"exerciseStyle":"European"` and you have two contracts that differ in exactly one column — and are
worth materially different amounts.

Refusals, to see both boundaries:

```bash
# 422 from the risk gate: account 999123 was never enabled. Sequenced, decided, no contract.
curl -s -X POST localhost:18110/swaps -H 'Content-Type: application/json' \
  -d '{"accountId":999123,"payReceive":"Pay","notional":10000000,"fixedRate":0.043,
       "effectiveDate":"2026-08-17","maturityDate":"2031-08-17","conventions":"USD-SOFR-1Y-ACT360"}'
```

```bash
# 400 from the boundary: an option cannot expire after the swap it is an option on. Never sequenced.
curl -s -X POST localhost:18110/swaptions -H 'Content-Type: application/json' \
  -d '{"accountId":22214,"payReceive":"Pay","notional":25000000,"fixedRate":0.0415,
       "effectiveDate":"2027-02-15","maturityDate":"2032-02-15","conventions":"USD-SOFR-1Y-ACT360",
       "expiryDate":"2027-06-15","exerciseStyle":"European"}'
```

```bash
# 400 from the boundary: LIBOR is not in the convention table. Never sequenced.
curl -s -X POST localhost:18110/swaps -H 'Content-Type: application/json' \
  -d '{"accountId":22214,"payReceive":"Pay","notional":10000000,"fixedRate":0.043,
       "effectiveDate":"2026-08-17","maturityDate":"2031-08-17","conventions":"USD-LIBOR-3M"}'
```

## Seeing both artifacts

Run the EOD chain, which is what triggers the extract:

```bash
TOKEN=$(kubectl -n traderx exec deploy/trade-processor -- sh -c 'curl -fsS -X POST http://localhost:18091/auth/dev-token -H "X-Auth-Master-Secret: kind-local-dev-token-secret-not-a-real-credential" -H "Content-Type: application/json" -d "{\"subject\":\"demo\",\"accounts\":[],\"admin\":true,\"ttlSeconds\":900}"')
kubectl -n traderx exec deploy/trade-processor -- sh -c "curl -fsS -X POST http://localhost:18091/eod/session/close -H 'Authorization: Bearer ${TOKEN}'"
```

```bash
# The announcement carries both artifacts under one consensusSequence and one cutSha256
kubectl -n traderx logs deploy/risk-extract | grep RISK-EXTRACT-READY | tail -1
```

```bash
# Every member rendered the same state at N, contracts included
for i in 0 1 2; do kubectl -n traderx logs "order-matcher-cluster-$i" | grep RISK-EXTRACT-CUT | tail -1; done
```

```bash
# The contracts, swaps and swaptions in one file
POD=$(kubectl -n traderx get pod -l app=risk-extract -o jsonpath='{.items[0].metadata.name}')
kubectl -n traderx exec "$POD" -- sh -c 'cat $(ls -t /data/risk-extracts/*/*/seq-*-contracts.csv | head -1)'
```

```bash
# Rebuild BOTH from the stored cut alone and byte-compare
kubectl -n traderx exec "$POD" -- sh -c '
  CUT=$(ls -t /data/risk-extracts/*/*/seq-*.cut | head -1)
  java -cp "/opt/app/classes:/opt/app/lib/*" finos.traderx.ordermatcher.cluster.RiskExtractMain \
    --rebuild "$CUT" /tmp/rebuild.csv /tmp/rebuild-contracts.csv
  cmp "${CUT%.cut}.csv" /tmp/rebuild.csv && cmp "${CUT%.cut}-contracts.csv" /tmp/rebuild-contracts.csv && echo REPRODUCIBLE'
```

## Stopping

```bash
bash scripts/yu15/stop-cluster-kind.sh
```
