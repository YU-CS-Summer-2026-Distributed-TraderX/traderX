# Runtime Topology: YU15-eod-risk-extract

The inherited cluster tier is unchanged in shape: three Aeron Cluster members behind a stateless
gateway tier. This state adds a producer alongside them, plus the two dependencies it needs — a
NATS broker with JetStream and a database holding the published closing prices.

## Entrypoints

| Entrypoint | Process | Purpose |
|---|---|---|
| `ClusterNodeMain` | `order-matcher-cluster` StatefulSet | Hosts the clustered service. Serves `/health` and `/ready`; readiness now reports the consensus-log position. |
| `ClusterGatewayMain` | `cluster-gateway` Deployment | Inherited unchanged: REST and FIX ingress, forwarded through the cluster client. |
| `RiskExtractMain` | `risk-extract` Deployment | The EOD producer. Same image, different main. |
| `nats-server --jetstream` | `nats` Deployment | Trigger stream, cut transport, announcement transport. |
| `mariadb` | `eod-price-db` Deployment | The two YU06 price tables, read-only from this state's view. |

## Components

| Component | Role | State |
|---|---|---|
| Sequenced marker | Names the consensus sequence the extract is cut at | None — mutates nothing |
| `RiskExtractCut` | Renders the canonical cut on every member | Pure function of replicated state |
| `RiskExtractCutPublisher` | Leader-side cut → NATS bridge | Bounded SPSC queue, daemon thread |
| `RiskExtractCsv` | Renders the delivered fixture | Pure function of cut + reference data |
| `RiskExtractMain` | Orchestrates one EOD batch | Durable consumer cursor only |
| `RiskExtractGcsSink` | Immutable object delivery | None |
| Object sink | Holds fixtures and their cuts | Write-once, keyed by the stamp |

## Networking

| Path | Transport | Notes |
|---|---|---|
| producer → members | Aeron Cluster client, UDP 21800–22200 | The producer's pod label must appear in the cluster NetworkPolicy ingress allowlist; without it the client silently cannot reach any member. |
| leader → producer | NATS `risk.extract.cut` | One message per extract, self-counting rows. |
| producer ↔ NATS | TCP 4222 | Trigger consumption and delivery announcement. |
| producer → price DB | TCP 3306 | Read-only, one query per extract. |
| producer → object sink | Filesystem, or HTTPS to `storage.googleapis.com` | Write-once either way. |

## Startup / Health Order

1. NATS becomes ready; the producer creates the EOD stream if position-service has not already.
2. The price database becomes ready. The producer does not connect to it until a batch fires.
3. Cluster members form and elect; readiness gates each member on its consensus-log position
   relative to its peers.
4. The gateway connects; `/ready` turns 200 once its cluster session is live.
5. The producer connects to NATS (retrying until it is there) and subscribes to its durable
   trigger. It holds no cluster session while idle — one is opened per batch.

Ordering between the producer and everything else does not matter: it retries NATS, ensures the
stream idempotently, and connects to the cluster and the database only when a batch actually fires.

## Degraded Behavior

| Condition | Behavior |
|---|---|
| NATS unavailable at producer start | Producer retries indefinitely, logging each attempt. It does not exit — a batch producer that dies on a cold dependency simply is not there when the batch fires. |
| Cluster unreachable when a batch fires | The marker ack times out, the extract fails, the trigger stays unacked, and JetStream redelivers. Nothing partial is written or announced. |
| `RISK_EXTRACT_NATS_URL` unset on members | The marker still sequences and every member still renders and hashes the cut; nothing is published, so the producer times out waiting for it and reports the missing configuration in the failure. |
| Cut lost or truncated in flight | A lost message is a timeout; a truncated one fails the declared row-count check. Neither can pass as a complete portfolio. |
| Trading occurs during the build | The witness marker lands beyond `N + 1`, the producer refuses to emit, and the trigger is redelivered. |
| A security has neither a published close nor a trade at `N` | The whole extract aborts. No zero-filled or omitted row is ever delivered. |
| An account holds a position but has no counterparty mapping | The whole extract aborts. |
| Price database unavailable | The extract fails and is redelivered. With `RISK_EXTRACT_JDBC_URL` deliberately unset, every row instead marks from the cluster's last trade and says so per row. |
| Object already exists at the key | The write is refused — on a filesystem by `CREATE_NEW`, on GCS by `if-generation-match: 0`. A redelivered trigger cannot replace a fixture already scored against. |
| A member restarts during the EOD window | It replays, re-renders the identical cut for any marker in the replayed range, and rejoins the Service on its consensus-log position — it does not have to wait for trading to resume. |
| Leader changes between the two markers | The producer's session follows the new leader; if the session is lost the marker ack times out and the extract is retried rather than emitted against a partial view. |
