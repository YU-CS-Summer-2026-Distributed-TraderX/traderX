# Implementation Status: YU15-eod-risk-extract

Status: implemented and verified on kind.

## What was built

| Area | Artifact |
|---|---|
| Wire | `RiskExtractMessage` (SBE template 8) + codec encode/decode |
| Cut | `RiskExtractCut` — canonical render + SHA-256 |
| Cluster | Marker branch, per-member render/hash/stamp, leader-only publish, consensus-position accessor |
| Bridge | `RiskExtractCutPublisher` — leader-side SPSC queue + daemon thread (ADR-048 shape) |
| Fixture | `RiskExtractCsv` — pure function of cut + marks + reference data |
| Producer | `RiskExtractMain` — trigger, marker, join, witness, delivery, announcement, `--rebuild` |
| Delivery | `RiskExtractGcsSink` — write-once over the YU09 S3-over-GCS transport |
| Readiness | `ClusterNodeMain` reports the consensus-log position (T-RXT07) |
| Schema | Instrument-identifier columns widened to `VARCHAR(32)` with `ALTER ... MODIFY` migrations (T-RXT13) |
| Runtime | NATS, a database running the state's own DDL, `trade-processor`, producer Deployment, NetworkPolicy allowlist entry |
| Proof | `RiskExtractTest` (16 tests), `scripts/bench/yu15-risk-extract.sh` (7 steps), `scripts/bench/yu15-option-persistence.sh` (4 steps) |

## Verification

| Proof | Result |
|---|---|
| Generated-tree suite | **258 / 0** (YU14 carried 242; +16 YU15 tests) |
| Epsilon gates | `noGcTest` + `riskNoGcTest` pass |
| Allocation gates | all four, executed fresh (`--rerun-tasks`) |
| Clean generation | `bash pipeline/generate-state.sh YU15-eod-risk-extract` → **EXIT=0** |
| Shared-override check | YU14 (`ticker32`, `multiplierFor`, format 3) and YU13 (`FLAG_RESTING_UPDATE`, `CLUSTER_READY_MAX_LAG`, election counters) markers all present in the generated tree alongside the YU15 delta |

### Live kind proof (`scripts/bench/yu15-risk-extract.sh`, all 7 steps)

Cluster `kind-traderx-yu12-cluster`, 3 members + gateway + NATS + price DB + producer.

| Step | Result |
|---|---|
| Trigger | Publishing `eod.pnl.done` — and nothing else — produced a delivered object and a `risk.extract.ready` announcement |
| Consistent cut | All 3 members logged `sha256=f10a554d…` at sequence 1544685 |
| Quiescence | `quiesceWitnessSequence` 1544686 == 1544685 + 1 |
| Reproducible | `--rebuild` from the stored `.cut` byte-compared equal to the delivered `.csv` (14 rows) |
| Immutable | Object present at `file:///data/risk-extracts/2026-07-22/v1/seq-1544685.csv`, write-once |
| Recovery | `order-matcher-cluster-2` deleted → replayed to 1544685 → re-rendered `sha256=f10a554d…` |
| Readiness | The restarted member rejoined the Service; a rolling restart of all three members completed, which it could not before T-RXT07 |

### Live option-persistence proof (`scripts/bench/yu15-option-persistence.sh`, all 4 steps)

Runs the real chain — cluster books the fill, the ADR-048 leader-side bridge publishes to NATS
`/trades`, `trade-processor` persists Trade + Position — and demonstrates the bug before fixing it.

| Step | Result |
|---|---|
| Narrow the columns back | Database set to the pre-YU15 widths (15/16), i.e. any database created by an older state |
| Book an option cross | **0 rows** for `AAPL260918C00260000`; trade-processor logged `MariaDbDataTruncation: Data too long for column 'security' at row 1`, while the cluster booked the fill regardless — only SQL lost it |
| Apply `900-migrations.sql` | Read from the applied ConfigMap (what the initContainer mounts); its 7 `MODIFY COLUMN` statements widened all 7 columns to 32 **in place, on a populated volume** |
| Book another option cross | Both trade rows and both position rows persisted, symbol intact at 19 characters |

```
1544409-S  42422  AAPL261218C00260000  Sell  5  2.400
1544410-B  22214  AAPL261218C00260000  Buy   5  2.400
22214  AAPL261218C00260000   5  2.400
42422  AAPL261218C00260000  -5  2.400
```

The `CREATE TABLE IF NOT EXISTS` half of the migrations block cannot widen a table that already
exists, so without the explicit `MODIFY` statements this step would have silently left an upgraded
deployment broken. That is why the proof narrows a populated database rather than testing a fresh
one.

### The delivered fixture

Mark sourcing behaved exactly as ADR-056 specifies — equities on the published close, options on
the cluster's last trade at N, each stamped:

```
22214,AAPL,EQUITY,10,1,241.800000,241.500000,EOD_SNAPSHOT,OK,2415.000000,-3.000000,USD,CPTY-CASCADE-AM,NS-CASC-ISDA-01
22214,AAPL260918C00240000,OPTION,10,100,3.900010,4.000000,CLUSTER_LAST_TRADE_AT_N,LAST_TRADE,4000.000000,99.990000,USD,CPTY-CASCADE-AM,NS-CASC-ISDA-01
```

The option row is the multiplier proof end to end: 10 contracts × $4.00 × 100 = $4,000.00 of
notional, from a multiplier that lives in cluster state and rides the format-3 snapshot.

## Notes for the next lane

- **One `/seed` per contract, fresh epoch, before anything else.** The engine silently rejects
  orders on a security that is not enabled or has no price tick; `seed-option-chain.sh` runs that
  gate first and fails loudly with the triage hint.
- **Only the seven real SQL accounts** appear in `counterparties.csv`, and an account with no
  mapping aborts the whole extract by design. Positions taken on arbitrary account ids will stop
  the batch.
- **The producer's pod label must stay in the cluster NetworkPolicy ingress allowlist.** Without
  it the Aeron client cannot reach any member and fails as an ack timeout, not a connection error.
- **Two files sit outside the kind kustomization** and are applied by the start script:
  `gateway.yaml` (inherited from the YU12 layout) and `database-init-configmap.yaml` (kustomize
  cannot reference a file outside its root, and referencing the schema in place keeps exactly one
  copy of it). Applying the kustomization alone leaves the gateway missing and the database
  unschema'd.
- **The in-process `ThreeMemberClusterTest` is sensitive to host load.** It timed out once while a
  busy kind cluster saturated the machine (load average 20, Docker VM at 686% CPU) and passed on
  re-run and in isolation. It is a contention flake, not a regression.
- **The EOD price feed still has no option contracts.** The schema half of the options blocker is
  fixed, but `PriceHistoryStore` is fed only by the synthetic `pricing.*` publisher, so options
  still have no published close and YU06's fail-safe still halts any account holding one. That is
  why ADR-056's fallback exists and why it is still needed.
- **GCS delivery is built but proven only against `file://`.** The GKE overlay needs the `gs://`
  sink URI and the HMAC secret pair; the parallel lane owns that cluster.

## Open questions with the consumer

- Whether `accountId → counterparty` is sufficient for their netting and CSA logic, or whether a
  counterparty entity with its own attributes is needed. The mapping is built and the assumption is
  flagged (TD-RXT03).
- P&L methodology alignment. Our cost-basis and valuation conventions are stated in the fixture's
  own header so any discrepancy hunt starts from a written convention rather than a guess.
- Whether computed risk results flow back for a UI or dashboard surface. The subject family is
  named so `risk.analytics.*` slots alongside `risk.extract.*` with nothing renamed.
