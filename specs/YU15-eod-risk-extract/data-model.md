# Data Model: EOD Risk Extract

## Sequenced marker (SBE `RiskExtractMessage`, template 8, block length 24)

The only new wire message. It carries the extract's stamp and no state.

| Field | Type | Notes |
|---|---|---|
| `requestId` | uint64 | Correlates the egress ack; the producer increments it per marker. |
| `sessionDateEpochDay` | int64 | The EOD session the extract names, from the trigger event. |
| `priceVersion` | uint32 | The closing-price snapshot version the extract marks against. |
| `reserved` | uint32 | Zero. |

Egress ack (24 bytes, the inherited layout): consensus sequence at offset 0, cut row count at 8,
kind `KIND_RISK_EXTRACT_MARKED` (101) at 12, `requestId` at 13.

## Position cut (`risk.extract.cut`, US-ASCII text, schema 1)

Rendered on every member by `RiskExtractCut`, published by the leader. Header line, then a column
header, then one row per position sorted by `(accountId, securityId)`.

```
#cut schema=1 seq=<N> sessionDateEpochDay=<d> priceVersion=<v> rows=<n>
accountId,security,quantity,avgCostTicks,contractMultiplier,lastTradePxTicks
```

| Column | Type | Notes |
|---|---|---|
| `accountId` | int | The real account id, as it appears in `counterparties.csv`. |
| `security` | ascii | Ticker or unpadded OCC symbol, from the cluster's symbol table. |
| `quantity` | long | Signed net position. Flat positions are emitted, not dropped. |
| `avgCostTicks` | long | Weighted average trade price, `Px.SCALE` = 1e6 ticks. |
| `contractMultiplier` | long | YU14 replicated state; 100 for OCC options, 1 otherwise. |
| `lastTradePxTicks` | long | Engine last trade at `seq`; 0 when the security has never traded. |

A position whose security has no registered ticker aborts the render — a risk extract that
silently omits a position is worse than no extract.

## Extract fixture (the delivered object, schema 1)

`#`-prefixed metadata preamble, then a CSV header, then one row per `(accountId, security)`.
Every preamble value is derived from the stamp, so the file is a pure function of the cut plus
immutable reference data.

Preamble keys: `schema`, `consensusSequence`, `sessionDate`, `priceSnapshotVersion`, `cutSha256`,
`rows`, plus the fixed convention lines (`cutConsistency`, `netting`, `quantityConvention`,
`costBasisConvention`, `marketValueConvention`, `unrealizedPnlConvention`, `markSourceLegend`,
`optionIdentity`).

| Column | Type | Notes |
|---|---|---|
| `accountId` | int | Un-netted grain, together with `security`. |
| `security` | ascii | OCC symbol for options; strike, expiry and call/put derive from it. |
| `instrumentType` | enum | `EQUITY` or `OPTION`, from `OccSymbol.isOption`. |
| `quantity` | long | Signed net position in contracts or shares. |
| `contractMultiplier` | long | From the cut, i.e. from cluster state. |
| `costBasis` | decimal(6) | Per contract or share; excludes fees and the multiplier. |
| `closingMark` | decimal(6) | The mark actually used for this row. |
| `markSource` | enum | `EOD_SNAPSHOT` or `CLUSTER_LAST_TRADE_AT_N` (ADR-056). |
| `markQuality` | enum | YU06's `OK`/`STALE`/`SPIKE`/`OVERRIDDEN`, or `LAST_TRADE`. |
| `marketValue` | decimal(6) | `quantity × closingMark × contractMultiplier`. |
| `unrealizedPnl` | decimal(6) | `(closingMark − costBasis) × quantity × contractMultiplier`. |
| `currency` | ascii | `USD` throughout; a field, not a conversion. |
| `counterpartyId` | ascii | From `counterparties.csv` by `accountId`. |
| `nettingSetId` | ascii | From `counterparties.csv`; an attribute, never applied here. |

All decimal columns are `BigDecimal` at scale 6 over integer ticks — exact, and free of the
overflow `quantity × priceTicks × multiplier` would hit in a `long`.

## Delivery record (`risk.extract.ready`)

```json
{ "schema": 1, "uri": "...", "consensusSequence": 1544685, "sessionDate": "2026-07-22",
  "priceSnapshotVersion": 1, "rows": 14, "sha256": "...", "cutSha256": "...",
  "quiesceWitnessSequence": 1544686 }
```

`quiesceWitnessSequence` is the sequence the second marker landed at; it must equal
`consensusSequence + 1`. It belongs here rather than in the fixture precisely so the fixture stays
a function of the cut alone and can be rebuilt from it.

## Object layout

```
<sink>/<sessionDate>/v<priceVersion>/seq-<consensusSequence>.csv    the fixture
<sink>/<sessionDate>/v<priceVersion>/seq-<consensusSequence>.cut    the cut it was built from
```

Write-once: `CREATE_NEW` on a filesystem sink, `x-goog-if-generation-match: 0` on GCS.

## Reference data (inherited from YU14, unchanged)

`counterparties.csv` — `accountId,counterpartyId,nettingSetId,currency` for the seven real
accounts. `instruments.csv` — the instrument universe. Rendered into the runtime image so the
producer reads them from the classpath directory rather than a mounted volume.

## Instrument-identifier column widths (changed)

YU14 made listed options tradeable, but every instrument-identifier column in the SQL schema was
sized for an equity ticker. An unpadded OCC symbol is 19 characters, so MariaDB's strict mode
rejected the insert and every option fill the ADR-048 trade bridge published was dropped by
`trade-processor` — the blotter, the positions read model, and the YU06 price chain silently
excluded every option. All of them are `VARCHAR(32)` here:

| Table | Column | Was | Now |
|---|---|---|---|
| `positions` | `security` | `VARCHAR(15)` | `VARCHAR(32)` |
| `trades` | `security` | `VARCHAR(15)` | `VARCHAR(32)` |
| `orderbook` | `security` | `VARCHAR(16)` | `VARCHAR(32)` |
| `eod_price_snapshot` | `security` | `VARCHAR(16)` | `VARCHAR(32)` |
| `eod_position_pnl` | `security` | `VARCHAR(16)` | `VARCHAR(32)` |
| `stocks` | `ticker` | `VARCHAR(16)` | `VARCHAR(32)` |
| `stocks_control_outbox` | `ticker` | `VARCHAR(16)` | `VARCHAR(32)` |

The JPA entities already declared `@Column(length = 50)` on `Trade.security` and
`Position.security`, so the schema was the only constraint and no Java changed.

`900-migrations.sql` carries seven matching `ALTER TABLE ... MODIFY COLUMN` statements. That block
is what the database Deployment's `schema-migrate` initContainer applies to an already-populated
PVC on every start, and `CREATE TABLE IF NOT EXISTS` is a no-op against a table that already
exists — so widening the `CREATE` statements alone would fix only freshly created databases. This
is the first migration in the lineage that modifies rather than only adds; re-running a `MODIFY`
against an already-widened column is a no-op.

## Read, not written

`eod_price_snapshot` and `eod_price_session` (YU06) are read for the stamped
`(session_date, version)` where `status = 'PUBLISHED'`. Rows with quality `MISSING` or a null price
are ignored, so those securities fall through to the cut's last trade. No table is written by this
state.
