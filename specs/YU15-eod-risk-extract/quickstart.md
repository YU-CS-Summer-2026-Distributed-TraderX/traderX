# Quickstart: EOD Risk Extract

## Local (kind)

The state runs on the inherited cluster tier plus three additions: NATS, a database holding the
published closing prices, and the extract producer.

```bash
# 1. generate and build
bash pipeline/generate-state.sh YU15-eod-risk-extract
bash scripts/yu15/build-cluster-image.sh

# 2. bring the tier up (creates the kind cluster if absent)
bash scripts/yu15/start-cluster-kind.sh

# 3. seed instruments and prove a cross books before anything else
kubectl --context kind-traderx-yu12-cluster -n traderx port-forward svc/order-matcher 18110:18110 &
MATCHER_URL=http://localhost:18110 bash scripts/bench/seed-option-chain.sh

# 4. take positions: cross both sides between two real accounts
for body in \
  '{"accountId":42422,"ticker":"AAPL","side":"Sell","quantity":10,"limitPrice":241.80}' \
  '{"accountId":22214,"ticker":"AAPL","side":"Buy","quantity":10,"limitPrice":241.80}'; do
  curl -s -X POST http://localhost:18110/orders -H 'Content-Type: application/json' -d "$body"
done

# 5. run the acceptance proof
bash scripts/bench/yu15-risk-extract.sh

# teardown
bash scripts/yu15/stop-cluster-kind.sh
```

Step 3 is not optional. The engine silently rejects orders for a security that is not enabled or
has no price tick, and those rejects surface nowhere on some paths — `seed-option-chain.sh` fails
loudly with the triage hint if orders are accepted but nothing fills.

## Taking an extract by hand

The producer's only trigger is `eod.pnl.done`. Publish one and watch it work:

```bash
K="kubectl --context kind-traderx-yu12-cluster -n traderx"

# the published closing-price version the extract marks equities against
$K exec deploy/eod-price-db -- mariadb -utraderx -ptraderx traderx -e "
  INSERT INTO eod_price_session VALUES ('2026-07-22',1,'PUBLISHED',2,0,NOW(),NOW());
  INSERT INTO eod_price_snapshot VALUES ('2026-07-22',1,'AAPL',241.500000,'OK',NULL,NULL);"

# fire the trigger (see scripts/bench/yu15-risk-extract.sh for the wire-protocol publish)
$K port-forward svc/nats 14222:4222 &

# watch the producer
$K logs -f deploy/risk-extract
```

The announcement carries everything needed to fetch and verify the result:

```
RISK-EXTRACT-READY {"schema":1,"uri":"file:///data/risk-extracts/2026-07-22/v1/seq-1544685.csv",
  "consensusSequence":1544685,"quiesceWitnessSequence":1544686,"rows":14,
  "cutSha256":"f10a554d...","sha256":"79e57c8d..."}
```

## Verifying the properties yourself

```bash
K="kubectl --context kind-traderx-yu12-cluster -n traderx"
N=1544685   # the consensusSequence from the announcement

# every member rendered the same state at that sequence
for i in 0 1 2; do $K logs order-matcher-cluster-$i | grep "RISK-EXTRACT-CUT seq=${N} "; done

# the fixture rebuilds byte-identically from its cut, with no cluster involved
POD=$($K get pod -l app=risk-extract -o jsonpath='{.items[0].metadata.name}')
$K exec $POD -- java -cp '/opt/app/classes:/opt/app/lib/*' \
  finos.traderx.ordermatcher.cluster.RiskExtractMain \
  --rebuild /data/risk-extracts/2026-07-22/v1/seq-${N}.cut /tmp/rebuild.csv
$K exec $POD -- cmp /data/risk-extracts/2026-07-22/v1/seq-${N}.csv /tmp/rebuild.csv

# a member that crashes and replays re-renders the identical cut
$K delete pod order-matcher-cluster-2
$K logs order-matcher-cluster-2 | grep "RISK-EXTRACT-CUT seq=${N} "
```

## Cloud

The producer takes a `gs://` sink instead of `file://` and nothing else changes; GCS enforces
write-once server-side through `if-generation-match: 0`.

```yaml
- name: RISK_EXTRACT_SINK_URI
  value: "gs://<bucket>/risk-extracts"
- name: RISK_EXTRACT_GCS_HMAC_KEY_ID
  valueFrom: { secretKeyRef: { name: risk-extract-gcs-hmac, key: keyId } }
- name: RISK_EXTRACT_GCS_HMAC_SECRET_ACCESS_KEY
  valueFrom: { secretKeyRef: { name: risk-extract-gcs-hmac, key: secret } }
```

Build the image for the cloud architecture — a Mac-built image is arm64 and GKE nodes are amd64:

```bash
YU15_PLATFORM=linux/amd64 bash scripts/yu15/build-cluster-image.sh
```

## Configuration

| Variable | Default | Effect |
|---|---|---|
| `RISK_EXTRACT_NATS_URL` | `nats://localhost:4222` | On the cluster members, enables the leader-side cut bridge; unset leaves the marker inert. On the producer, its trigger and announcement transport. |
| `RISK_EXTRACT_CUT_SUBJECT` | `risk.extract.cut` | Where the leader publishes the cut. |
| `RISK_EXTRACT_READY_SUBJECT` | `risk.extract.ready` | Where delivery is announced. |
| `EOD_STREAM` / `EOD_PNL_DONE_SUBJECT` | `TRADERX_EOD` / `eod.pnl.done` | The trigger stream and subject. |
| `RISK_EXTRACT_DURABLE` | `risk-extract` | Durable consumer name. |
| `CLUSTER_INGRESS_ENDPOINTS` | `0=localhost:21802` | Member ingress endpoints; the client finds the leader. |
| `RISK_EXTRACT_JDBC_URL` | *(unset)* | Published closes. Unset marks every row from the cluster's last trade. |
| `RISK_EXTRACT_SINK_URI` | `file:///data/risk-extracts` | `file://` or `gs://`. |
| `RISK_EXTRACT_REFERENCE_DATA` | `/opt/app/classes/reference-data` | Where `counterparties.csv` is read from. |
