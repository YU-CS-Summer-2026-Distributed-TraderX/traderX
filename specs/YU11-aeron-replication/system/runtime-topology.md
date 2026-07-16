# Runtime Topology: YU11-aeron-replication

## Entrypoints

| Entrypoint | Transport | Consumer |
|---|---|---|
| `order-matcher:18110` | HTTP REST/UI | unchanged YU10 clients |
| `order-matcher:18130` | FIX 4.4 over TCP | unchanged YU10 FIX initiators |
| data UDP port | Aeron reliable unicast | peer order-matcher replication follower |
| ACK UDP port | Aeron reliable unicast | peer order-matcher replication primary |
| control UDP port | Aeron reliable unicast | peer handshake, heartbeat, replay/snapshot control |
| Archive control/replay UDP ports | Aeron Archive protocol | peer sidecar/application during catch-up |

## Components

- **order-matcher application (2 StatefulSet replicas in HA)**: retains REST, FIX, input/output
  rings, primary journal, matching/risk, NATS rollback transport, Lease election, and readiness.
  Adds SBE codecs, Aeron replicator/follower/ACK agents, exact journal watermark mapping, shadow
  comparison, transport/policy selection, Archive recovery coordinator, and fast-witness gate.
- **aeron-replication-sidecar (one per order-matcher pod)**: Java Archiving Media Driver in shared
  threading mode. The media directory is a memory-backed shared `emptyDir`; Archive catalog/
  segments use the pod's persistent volume.
- **NATS/JetStream**: remains the non-replication message bus and File-backed rollback transport.
  Fast-witness mode also uses the `TRADERX_BLP_FAST_WITNESS` KV bucket for an atomic promotion
  claim.
- **Kubernetes Lease**: remains the synchronous default promotion authority and asynchronously
  reconciles fast-witness winners.
- All YU10 services and databases retain their inherited topology.

## Networking

- Data, ACK, control, and Archive replay are reliable unicast UDP between stable StatefulSet
  ordinal DNS names on the headless order-matcher Service.
- A namespace-scoped NetworkPolicy permits the named UDP ports only between
  `app=order-matcher` pods. No Aeron port uses ingress-nginx, LoadBalancer, NodePort, multicast,
  or a host mapping.
- Compose uses explicit primary/follower service names on one Docker network.
- Kind uses a dedicated named multi-node cluster with at least two workers; the ordinary shared
  single-node cluster is not modified.
- GKE required anti-affinity keeps each application+sidecar pair on a different c2 node.

## Startup / Health Order

1. The sidecar opens its Aeron directory, validates/opens the Archive catalog, recording path,
   schema checksum, and disk watermark, then reports healthy.
2. Each application completes inherited journal/snapshot recovery and loads the persisted
   replication checkpoint.
3. Peers authenticate cluster/pod/ordinal/transport/schema/epoch through the signed control
   handshake.
4. The follower catches up through retained journal plus Archive replay; empty-volume recovery
   installs a complete snapshot bundle first.
5. Journaled and applied watermarks converge at the observed live high watermark.
6. Default mode acquires/confirms the Kubernetes Lease; fast mode also requires an atomic witness
   revision. Only then does the primary admission fence open.

## Degraded Behavior

| Condition | Behavior |
|---|---|
| Follower disconnects under `degraded-solo` | Primary stops claiming synchronous follower durability, alerts, continues against its journal, and retries authenticated catch-up. |
| Follower disconnects under `strict` | Admission closes; new orders receive 503; already-sequenced outcomes remain ambiguous under inherited retry rules. |
| Aeron offer backpressure | Bounded retry/backoff; pressure reaches the input ring; timeout transitions through the selected failure policy; no record is dropped. |
| Schema/transport/epoch mismatch | Session rejected; both pods remain non-serving for that pair. |
| Sequence/checksum gap | Follower remains unready and unpromotable; strict primary refuses; shadow mode records a failed comparison. |
| Sidecar restart/catalog discontinuity | Application refuses replication readiness, reconnects, validates position continuity, and catches up before live state. |
| Archive disk watermark/full | Recording/catch-up refuses before unsafe exhaustion; diagnostics expose free bytes and affected position. |
| Empty follower volume | Checksummed snapshot bundle install plus Archive replay; any validation failure remains unready. |
| Fast peer silence, witness available | One atomic witness winner promotes and then reconciles the Lease. |
| Fast peer silence, witness unavailable/ambiguous | No promotion; readiness remains false. |
| Foreign witness/epoch/confirmed Lease proof | Local primary closes admission and demotes before another order. |
