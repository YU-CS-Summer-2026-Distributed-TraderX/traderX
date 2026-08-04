# Quickstart: EOD Risk Extract

## Local (kind)

The state runs on the inherited cluster tier plus the pieces the extract's trigger actually needs:
NATS, a database running the state's own schema, the YU06 EOD chain (`price-publisher`,
`trade-processor`, `position-service`) and the extract producer. Nothing in the proof is
hand-seeded — the chain runs for real.

```bash
# 1. generate and build
bash pipeline/generate-state.sh YU15-eod-risk-extract
bash scripts/yu15/build-cluster-image.sh

# 2. bring the tier up (creates the kind cluster if absent)
bash scripts/yu15/start-cluster-kind.sh

# 3. seed instruments and prove a cross books before anything else
kubectl --context kind-traderx-yu12-cluster -n traderx port-forward svc/order-matcher 18110:18110 &
MATCHER_URL=http://localhost:18110 bash scripts/proofs/seed-option-chain.sh

# 4. take positions: cross both sides between two real accounts
for body in \
  '{"accountId":42422,"ticker":"AAPL","side":"Sell","quantity":10,"limitPrice":241.80}' \
  '{"accountId":22214,"ticker":"AAPL","side":"Buy","quantity":10,"limitPrice":241.80}'; do
  curl -s -X POST http://localhost:18110/orders -H 'Content-Type: application/json' -d "$body"
done

# 5. run the acceptance proofs
bash scripts/proofs/yu15-risk-extract.sh        # the extract: cut, quiescence, reproducibility
bash scripts/proofs/yu15-option-persistence.sh  # options reach SQL, and the migration fixes an old DB

# the two trace proofs need the observability stack, which the bring-up above does NOT deploy
bash scripts/yu15/start-observability-kind.sh   # OTel Collector, Tempo, Prometheus, Grafana, Loki
bash scripts/proofs/yu13-otel-trace-join.sh              # one order, one trace, across consensus
bash scripts/proofs/yu13-otel-reject-trace-log-join.sh   # a rejected order's log line joins its trace

# teardown
bash scripts/yu15/stop-cluster-kind.sh
```

Step 3 is not optional. The engine silently rejects orders for a security that is not enabled or
has no price tick, and those rejects surface nowhere on some paths — `seed-option-chain.sh` fails
loudly with the triage hint if orders are accepted but nothing fills.

The observability line in step 5 is not optional either, for the two proofs under it. The stack has
shipped in the manifests since state `007`, but `start-cluster-kind.sh` deploys only the trading
tier — so on kind the two halves land in different clusters and the collector endpoint resolves to
nothing. The trace pipeline is then **silently** dead: orders book normally, spans go nowhere, and
the only symptom is an empty Tempo. There is no error to search for, which is why it is called out
here rather than left to be discovered.

## Taking an extract by hand

The producer's only trigger is `eod.pnl.done`, and the EOD chain emits it. Close a session and
watch the whole thing run:

```bash
K="kubectl --context kind-traderx-yu12-cluster -n traderx"

TOKEN=$($K exec deploy/trade-processor -- sh -c 'curl -fsS -X POST http://localhost:18091/auth/dev-token \
  -H "X-Auth-Master-Secret: kind-local-dev-token-secret-not-a-real-credential" \
  -H "Content-Type: application/json" \
  -d "{\"subject\":\"manual\",\"accounts\":[],\"admin\":true,\"ttlSeconds\":600}"')

$K exec deploy/trade-processor -- sh -c \
  "curl -fsS -X POST 'http://localhost:18091/eod/session/close' -H 'Authorization: Bearer $TOKEN'"

$K logs -f deploy/risk-extract      # and: $K logs deploy/position-service | grep 'eod pnl'
```

What the feed is quoting, and with which model inputs:

```bash
$K exec deploy/price-publisher -- wget -qO- http://localhost:18100/health
$K exec deploy/price-publisher -- wget -qO- http://localhost:18100/prices/AAPL260918C00240000
```

The announcement carries everything needed to fetch and verify the result:

```
RISK-EXTRACT-READY {"schema":1,"uri":"file:///data/risk-extracts/2026-07-22/v1/seq-1544685.csv",
  "consensusSequence":1544685,"quiesceWitnessSequence":1544686,"rows":14,
  "cutSha256":"f10a554d...","sha256":"79e57c8d..."}
```

## Verifying option persistence

`yu15-option-persistence.sh` demonstrates the bug before fixing it: it narrows the
instrument-identifier columns back to an older state's widths, books an option cross, and shows
`trade-processor` rejecting it with `Data too long for column 'security'` while the cluster books
it regardless. It then applies the state's own `900-migrations.sql` — read from the applied
ConfigMap, exactly what the `schema-migrate` initContainer mounts — and books another cross that
persists with its 19-character OCC symbol intact.

To check the schema directly:

```bash
kubectl --context kind-traderx-yu12-cluster -n traderx exec deploy/eod-price-db -- \
  mariadb -utraderx -ptraderx traderx -e "
    SELECT table_name, column_name, character_maximum_length
    FROM information_schema.columns
    WHERE table_schema='traderx' AND column_name IN ('security','ticker') ORDER BY table_name;"
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
