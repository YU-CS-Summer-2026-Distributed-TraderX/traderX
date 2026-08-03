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
| Feed | `price-publisher` quotes the listed chain off its underlyings — Black-Scholes at a flat IV, derived every tick (T-RXT17) |
| Quality | Instrument-aware spike threshold, so an option's ordinary move does not block the session (T-RXT18) |
| P&L | Multiplier-aware `eod_position_pnl.market_value`, so an option row agrees with the extract (T-RXT19) |
| Runtime | NATS, a database running the state's own DDL, the full EOD chain (`price-publisher`, `trade-processor`, `position-service`), producer Deployment, NetworkPolicy allowlist entry |
| Proof | `RiskExtractTest` (16 tests), `scripts/proofs/yu15-risk-extract.sh` (7 steps), `scripts/proofs/yu15-option-persistence.sh` (4 steps) |

## Verification

| Proof | Result |
|---|---|
| order-matcher suite | **258 / 0** (YU14 carried 242; +16 YU15 tests) |
| trade-processor suite | **63 / 0** (+6 instrument-aware spike cases) |
| position-service suite | **10 / 0** (+2 multiplier cases) |
| price-publisher suite | **11 / 0** (`node:test`, no framework dependency added) |
| Epsilon gates | `noGcTest` + `riskNoGcTest` pass |
| Allocation gates | all four, executed fresh (`--rerun-tasks`) |
| Clean generation | `bash pipeline/generate-state.sh YU15-eod-risk-extract` → **EXIT=0** |
| Shared-override check | YU14 (`ticker32`, `multiplierFor`, format 3) and YU13 (`FLAG_RESTING_UPDATE`, `CLUSTER_READY_MAX_LAG`, election counters) markers all present in the generated tree alongside the YU15 delta |

### Live kind proof (`scripts/proofs/yu15-risk-extract.sh`, all 7 steps)

Cluster `kind-traderx-yu12-cluster`: 3 members + gateway + NATS + database + the full EOD chain
(price-publisher, trade-processor, position-service) + producer.

| Step | Result |
|---|---|
| Trigger | The **real** chain: `/eod/session/close` published v6 with **44 instruments, 0 flagged, including all 24 option contracts at quality OK**; position-service marked `accounts=3 halted=0 rows=8` and emitted `eod.pnl.done`; the extract fired off that event. Nothing hand-seeded, nothing hand-published |
| Consistent cut | All 3 members logged `sha256=9272038c…` at sequence 1544715 |
| Quiescence | `quiesceWitnessSequence` 1544716 == 1544715 + 1 |
| Reproducible | `--rebuild` from the stored `.cut` byte-compared equal to the delivered `.csv` (18 rows) |
| Immutable | Object present at `file:///data/risk-extracts/2026-07-22/v6/seq-1544715.csv`, write-once |
| Recovery | `order-matcher-cluster-2` deleted → replayed to the stamped sequence → re-rendered the identical `sha256` |
| Readiness | The restarted member rejoined the Service; a rolling restart of all three members completed, which it could not before T-RXT07 |

### Live option-persistence proof (`scripts/proofs/yu15-option-persistence.sh`, all 4 steps)

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

**Every row now carries `markSource=EOD_SNAPSHOT` and `markQuality=OK`** — options included. The
`CLUSTER_LAST_TRADE_AT_N` fallback is no longer exercised in the normal path, which is exactly what
ADR-056 intends now the feed covers the chain:

```
22214,AAPL,EQUITY,13,1,241.800000,241.477000,EOD_SNAPSHOT,OK,3139.201000,-4.199000,USD,CPTY-CASCADE-AM,NS-CASC-ISDA-01
22214,AAPL260918C00240000,OPTION,10,100,3.900010,10.709000,EOD_SNAPSHOT,OK,10709.000000,6808.990000,USD,CPTY-CASCADE-AM,NS-CASC-ISDA-01
22214,AAPL261218C00260000,OPTION,5,100,2.400000,9.503000,EOD_SNAPSHOT,OK,4751.500000,3551.500000,USD,CPTY-CASCADE-AM,NS-CASC-ISDA-01
```

The option row is the multiplier proof end to end: 10 contracts × $10.709 × 100 = $10,709 of
notional, from a multiplier that lives in cluster state and rides the format-3 snapshot.

**The tie-out closes exactly.** For the same row, `eod_position_pnl` records
`AAPL261218C00260000 qty 5 close 9.503 market_value 4751.500000` and the fixture records
`marketValue 4751.500000`. Before the multiplier fix these differed by exactly 100x — on the very
number the consumer reconciles its base NPV against.

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
- **A shared JetStream stream is fixed by whoever creates it first.** The producer originally
  created `TRADERX_EOD` carrying only `eod.pnl.done`, which left trade-processor's
  `eod.prices.ready` publish with no responder and broke the batch chain *upstream* of us —
  visible only once the real chain ran. `ensureStream` now declares the whole subject family and
  repairs an existing stream that is missing one. Watch for this pattern wherever two services
  ensure the same stream.
- **Option quotes are modelled, not observed.** Flat implied vol (`PRICE_OPTION_IV`, default 0.25)
  and rate (`PRICE_OPTION_RATE`, default 0.04), reported on price-publisher's `/health` so a
  consumer can reproduce our marks exactly. A real venue would publish settlement prices.
- **A contract whose underlying is absent from `PRICE_TICKERS` is skipped, not quoted.** Adding
  contracts on a new underlying means adding the underlying too, or they silently never price —
  and an unpriced holding halts its whole account.
- **The option-persistence proof clears option rows before narrowing the columns.** Now that the
  feed quotes the chain and fills persist, real 19-character rows exist and MariaDB correctly
  refuses to shrink a column under them.
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

## Lineage reconciliation with YU13 (2026-07-22)

**Code** — cherry-picks `cdb62dc0` (complete gateway batches on the applied high-water fence) and
`77ab0b15` (1 MiB gateway Aeron term buffers) landed on 2026-07-21 but were never regenerated or
tested. Verified 2026-07-22: `generate-state.sh YU15-eod-risk-extract` EXIT=0, generated-tree suite
**258 / 0** (the recorded baseline, unchanged), all four allocation gates and both Epsilon gates
(`noGcTest` + `riskNoGcTest`) green.

**Manifests — nothing to merge here, and not for the reason the brief assumed.** The 7-22 brief
expected YU15 to inherit YU14's `gke/` copies "through the layer chain". It does not.
`pipeline/render-state-<state>.sh` copies `generation/kubernetes` with a plain per-state `cp -R`
from that state's own spec dir — there is **no cross-layer overlay for the kubernetes tree at all**.
Consequence: `generated/.../YU15-eod-risk-extract/runtime/kubernetes/` contains only `cluster/`
(the kind tier) and **no `gke/` directory whatsoever**.

**This blocks the `gs://` delivery proof (brief 04).** A GKE deploy of YU15 has no manifests to
apply. Applying YU14's `specs/YU14-.../generation/kubernetes/cluster/gke/` would run the
`cluster-node:yu14` image, not YU15. YU15 needs its own `generation/kubernetes/cluster/gke/`
directory — the YU14 set retagged `:yu15`, plus the `gs://` sink URI and the HMAC secret pair —
before that proof can run. Not built here: it is brief 04's scope and needs the cluster to verify.

The kind path (`generation/kubernetes/cluster/`) is unaffected by the YU13 GKE campaign and was
correctly left alone.

## Journal-sourced reconciliation on the cluster tier (2026-08-02)

YU05's reconciliation and regulatory-audit contract now has a source on this tier. It did not
before: `TradeNatsPublisher` drains its queue and keeps only a count, and the gateway's egress acks
carry a kind byte and a sequence rather than trade detail, so nothing here could answer
`/recon/trades/blotter`, `/recon/full-history/*` or `/regulatory/report`. Both proofs were honest
stated skips for exactly that reason, and `POST /recon/orphan-sweep` 500'd as a consequence
(`IOException: full-history reindex trigger failed: HTTP 404`), never as a fault of its own.

**The source is the Aeron Archive's cluster-log recording, replayed on demand** into a shadow
instance of `MatchingEngineClusteredService` (`ClusterRecon`). The members serve the routes because
that is where the log is; the gateway forwards to the first member that answers.

**Why not a retained trade list in the clustered service.** It is wrong, not merely expensive.
`ReconciliationService.runOrphanSweep()` flags every local trade id ABSENT from the full-history
index as `ORPHAN_IN_PROJECTION`, so a bounded list turns every trade older than its bound into a
FALSE orphan, while an unbounded one is new replicated state that every snapshot pays for forever.
No bound is both safe and correct. It could not serve `/regulatory/report` at all — that reports
order accept/reject/cancel/fill events over an input-sequence range, which no trade list holds.
This is also what the Spring tier already concluded: its live `TradeBlotter` is explicitly outside
the snapshot, and `LmaxEngine.reindexFullHistory()` replays the whole journal into a shadow engine.

**Snapshot impact: none. `SNAPSHOT_FORMAT` stays 3 and no `T_*` record was added.** Nothing here is
replicated state. The live forward blotter and the full-history index have exactly the status of the
NATS bridges and the kdb tap already in the same drain loop: read-side projections of committed
output, rebuilt by replay. The one core edit is a nullable output sink, null in production unless
`RECON_BLOTTER_CAPACITY` is set, which exists so the replay drives the REAL apply path rather than a
reimplementation that could drift from it.

**Retention bound = the archive's own log retention.** The snapshot trigger does not purge log
segments, so "full history" is genuinely full for the epoch; the lever is an explicit ops purge.
The index and the report are additionally capped (`RECON_FULL_HISTORY_MAX`,
`REGULATORY_MAX_RECORDS`) and REFUSE past the cap rather than truncate — a truncated index
manufactures orphans, and a member is a consensus participant that an admin query must not OOM.

**Proven on the kind cluster rig, 2026-08-02** (fresh epoch, `traderx/cluster-node:yu15`):

- 1337 committed log messages replayed from position 0 reproduce the live engine's trade population
  exactly (indexed 6, live counter 6..6), on all three members independently.
- `matched=6`, `field_mismatch=0`, `missing_in_projection=0` from the scheduled sweep — so
  `RECON_POLL_INTERVAL_MS` went back to YU05's own 10s default, having been backed off to daily
  only because the endpoint did not exist.
- All 6 projection rows have journal provenance, AND a planted projection-only row is named as
  `ORPHAN_IN_PROJECTION` — without that positive control, `orphanCount=0` is indistinguishable from
  a check that does nothing.
- The regulatory export is byte-identical across calls over a CLOSED range, and carries 12
  order-lifecycle records the `trades` table has no column for. The range must be closed: with the
  control feed and price publisher committing, "toSeq = to the end" answers over a longer log on
  the second call and reports a MISMATCH about a system that is behaving as specified.
- Snapshot round-trip and member re-identity: with the tap live, snapshots continue and all three
  members hold identical applied / trades / orderHash / positionHash / nextOrderRef; a member
  restarted with its PVC intact (snapshot + tail recovery) rejoins byte-identical and still replays
  its own archive to the same 1337 messages / 6 trades.

Trade ids on this tier are `<tradeSeq>-<B|S>` (what `TradeNatsPublisher` publishes and
trade-processor keys on), NOT the Spring tier's `trd-09b-<seq>`; order ids are
`<CLUSTER_EPOCH>-<orderRef>`. Minting the Spring scheme here would orphan every projection row —
pinned by `ClusterReconTapTest`.

**GKE manifests: present but OFF, and unverified there.** `gke/statefulset-emptydir.yaml` (the one
that actually runs) and the reference PVC variant carry the recon env with
`RECON_BLOTTER_CAPACITY=0`, following the same house rule as `KDB_TAP_DIR` and `OTEL_TRACES` on that
tier: the live forward blotter allocates per booked trade on the apply thread, and the banked GKE
throughput numbers were taken without it. Turning it on is a one-value edit plus
`RECON_POLL_INTERVAL_MS=10000` on `gke/trade-processor.yaml` — no rebuild. None of this has been
exercised on GKE; there was no cluster to verify against when it landed (the GKE cluster was deleted
2026-08-01). Everything above was proven on the kind cluster rig.
