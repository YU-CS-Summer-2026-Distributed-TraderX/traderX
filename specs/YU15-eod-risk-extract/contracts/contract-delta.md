# Contract Delta: YU15 over YU14-listed-equity-options

All existing order/trade/position/risk/post-trade/EOD REST, NATS, FIX, and UI contracts are
retained unchanged. `ClusterGatewayMain` has zero changes. Every delta below is additive.

## 1. Cluster ingress (new SBE template)

| Template | Message | Direction | Payload |
|---|---|---|---|
| `8` | `RiskExtractMessage` | client → leader | `requestId` (uint64), `sessionDateEpochDay` (int64), `priceVersion` (uint32), `reserved` (uint32) |

The marker mutates no replicated state. Its egress ack reuses the inherited 24-byte ack layout with
a new kind byte `KIND_RISK_EXTRACT_MARKED` (101): consensus sequence at offset 0, cut row count at
8, kind at 12, `requestId` at 13.

A malformed marker is dropped without advancing any sequence (fail closed), exactly as an
unrecognised input event is.

## 2. Position cut (new NATS subject, `risk.extract.cut`)

Published by the cluster **leader only**, one message per extract, US-ASCII:

```
#cut schema=1 seq=1544685 sessionDateEpochDay=20656 priceVersion=1 rows=14
accountId,security,quantity,avgCostTicks,contractMultiplier,lastTradePxTicks
22214,AAPL,10,241800000,1,241800000
22214,AAPL260918C00240000,10,3900010,100,4000000
```

Rows are ordered by `(accountId, securityId)`. The `rows=` count is part of the contract: a
consumer that receives a different number of rows must treat the cut as truncated.

## 3. Delivered fixture (new object)

`<sink>/<sessionDate>/v<priceVersion>/seq-<consensusSequence>.csv`, with the cut it was built from
beside it as `.cut`. Both are write-once. The fixture is `#`-prefixed metadata, then:

```
accountId,security,instrumentType,quantity,contractMultiplier,costBasis,closingMark,
markSource,markQuality,marketValue,unrealizedPnl,currency,counterpartyId,nettingSetId
```

Guarantees a consumer may rely on:

- rows are un-netted at `(accountId, security)` and are never aggregated by counterparty or
  netting set;
- every row is state at `consensusSequence` on the consensus log — one instant, all accounts;
- `markSource` names where each row's mark came from, per ADR-056;
- the same `(cut)` input always produces the same bytes, so a fixture can be re-derived and
  byte-compared at any time.

## 4. Delivery announcement (new NATS subject, `risk.extract.ready`)

```json
{ "schema": 1, "uri": "file:///data/risk-extracts/2026-07-22/v1/seq-1544685.csv",
  "consensusSequence": 1544685, "sessionDate": "2026-07-22", "priceSnapshotVersion": 1,
  "rows": 14, "sha256": "79e57c8d...", "cutSha256": "f10a554d...",
  "quiesceWitnessSequence": 1544686 }
```

`quiesceWitnessSequence` always equals `consensusSequence + 1`; the producer does not announce
otherwise.

## 5. Trigger (existing subject, new consumer)

`eod.pnl.done` gains a durable JetStream consumer named `risk-extract`. The event's shape and
producer are unchanged; YU06 documented it as available for a later subscriber and this is that
subscriber. Both ends now ensure the `TRADERX_EOD` stream idempotently, so neither has to start
first.

## 6. Producer entrypoint (new)

`finos.traderx.ordermatcher.cluster.RiskExtractMain`, run from the existing cluster image.

| Mode | Invocation | Effect |
|---|---|---|
| service | *(no arguments)* | Subscribe to the trigger and produce an extract per event. |
| rebuild | `--rebuild <cut-file> <out-file>` | Re-derive a fixture from a stored cut with no cluster involved. Used to verify reproducibility. |

## 7. Member health (changed field semantics)

`GET /health` on a cluster member:

| Field | Before | Now |
|---|---|---|
| `applied` | `engine().blpSeq()` | consensus-log position |
| `engineApplied` | *(absent)* | `engine().blpSeq()` |

`/ready` compares the consensus-log position against peers instead of `blpSeq`, and the
`traderx_cluster_applied` metric reports the same. A member restored from a snapshot into an idle
cluster now reports itself caught up, which it is; previously it reported `-1` and never rejoined
the Service.

## 8. Database schema (widened columns)

Every instrument-identifier column is `VARCHAR(32)`, up from `VARCHAR(15)`/`VARCHAR(16)`:
`positions.security`, `trades.security`, `orderbook.security`, `eod_price_snapshot.security`,
`eod_position_pnl.security`, `stocks.ticker`, `stocks_control_outbox.ticker`.

Purely widening, so every existing row and query is unaffected. `900-migrations.sql` gains seven
matching `ALTER TABLE ... MODIFY COLUMN` statements, because the block's `CREATE TABLE IF NOT
EXISTS` statements cannot alter a table that already exists — the same reason YU05's
`settlementdate` needed an explicit `ADD COLUMN`. This is the first migration in the lineage that
modifies rather than only adds.

Effect: an option fill published by the ADR-048 trade bridge now persists. Before this, MariaDB's
strict mode rejected it with `Data too long for column 'security'` and the SQL blotter, the
positions read model, and the YU06 price chain silently excluded every option.

The JPA entities were already correct (`@Column(length = 50)`), so no service code changed.

## Not changed

Order, trade, position, risk, settlement, reconciliation, regulatory, TCA, and EOD payload shapes
and subjects; the gateway's REST and FIX surfaces; the matching engine; the risk gate; snapshot
format 3; the output-ring topology; the BLP hot path; UI journeys.
