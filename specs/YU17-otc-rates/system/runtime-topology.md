# Runtime Topology: YU17-otc-rates

The inherited tier is unchanged: the same services, ports, cluster members and observability as
YU16. This state adds one gateway route, one command type on the existing ingress, one replicated
store inside the cluster members, and one artifact from the existing extract producer. No component
is added or removed, and no new network path exists.

## Entrypoints

| Entrypoint | Process | Purpose |
|---|---|---|
| `POST /swaps` | cluster gateway (:18110) | book an OTC fixed-float interest-rate swap |
| `POST /orders`, `/cancel`, `/replace`, `/trades` (REST), FIX `D` | cluster gateway | unchanged |
| cluster ingress UDP, `commandType` 12 | leader consensus module | `TYPE_SWAP_BOOK` on the inherited `InputEventMessage` (template 1) |
| cluster egress UDP, kind 102 | gateway | `KIND_SWAP_BOOKED` ack, correlated by `clientOrderKey` |
| `risk.extract.ready` | risk-extract | now announces both artifacts under one stamp |

## Components

| Component | Role | State |
|---|---|---|
| cluster gateway | resolves the conventions name, refuses unrepresentable terms pre-consensus, offers one command and awaits its committed decision | stateless |
| order-matcher cluster | applies `TYPE_SWAP_BOOK` in the clustered service; the matching engine is untouched | replicated, snapshot format 5 (`T_CONTRACT` added) |
| risk-extract | renders both artifacts from one cut under one stamp; writes both write-once | object store (write-once, inherited) |
| reference-data | unchanged; a swap has no instrument record and needs no lookup | SQL + outbox (inherited) |
| price-publisher | unchanged; a swap has no quote | in-memory quote state |
| trade-processor, position-service | unchanged; a swap books no trade and creates no position | SQL |
| web-front-end | unchanged | stateless |

## Networking

| Path | Transport | Notes |
|---|---|---|
| client → gateway | HTTP `POST /swaps` | one request, one sequenced command, one committed decision |
| gateway → cluster | Aeron Cluster ingress | `TYPE_SWAP_BOOK` on template 1; no new template, no schema version change |
| cluster → gateway | Aeron Cluster egress | `KIND_SWAP_BOOKED`, correlated by the `clientOrderKey` it echoes |
| everything else | inherited | no new paths, subjects or ports |

## Startup / Health Order

1. cluster members → gateway (unchanged): a booking needs a committed decision, so the gateway
   answers 504 until it is connected, never a rejection.
2. NATS → risk-extract (unchanged): the producer's trigger and durable binding are as inherited.
3. Everything else in any order — nothing new depends on reference-data, price-publisher or the
   post-trade tier, because a swap has no instrument record, no quote and no position.

Convention resolution has no startup dependency at all: the table is compiled into both the gateway
and the members.

## Degraded Behavior

| Condition | Behavior |
|---|---|
| Gateway not connected to the cluster | `POST /swaps` answers 504 "no committed decision". Ambiguous by design — the booking may or may not have been sequenced, and reporting it as a rejection would be a lie the client would act on. |
| The risk gate refuses the booking | 422 with the `RiskReason`. The command WAS sequenced and the refusal is a committed decision every member reached; no contract exists. |
| A term cannot be represented | 400, and the consensus sequence does not move. The boundary owns instrument semantics; the record never sees an unrepresentable value. |
| A booking is retried with the same `clientOrderId` | The original contract id is returned and no second contract is created. Without a `clientOrderId` each request is a distinct contract. |
| The contract store is at capacity (4096) | 422 `CAPACITY`, identically on every member. Checked before the gate, so the refused booking consumes no credit. |
| A member restarts | It restores the contract store from the snapshot in booking order and replays the tail, re-rendering identical cuts. Restore fails closed on a contract id beyond the restored applied sequence or out of order. |
| A format-4 snapshot is presented | It restores unchanged — `MIN_READABLE_SNAPSHOT_FORMAT` is 3 — which is what lets a YU16 epoch roll forward without a wipe. |
| A format-5 snapshot reaches an older build | Refused at the header with a message naming the direction of the mismatch and saying to roll forward rather than wipe the epoch. |
| The cut names a convention index the build does not know | The render aborts naming the index. A contract published under the wrong day count is worse than an extract that does not run; the remedy is to roll forward. |
| A cut carries no `#contracts` section | The contracts render aborts saying the producer predates this build. An absent section is never read as an empty portfolio. |
| A cut's declared contract count disagrees with its rows | The render aborts. The cut travels as one message precisely so truncation is detectable. |
| A swap is booked between the two extract markers | The quiescence witness fails and the extract refuses to emit, exactly as it does for any other sequenced command. The market being closed is the precondition, not an assumption. |
