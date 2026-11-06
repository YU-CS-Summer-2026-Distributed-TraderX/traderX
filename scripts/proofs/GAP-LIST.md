# Live-provable requirements with no live proof (tip `b9415268`, 2026-11-06)

Re-derived from the spec packs at the branch tip against [COVERAGE.md](COVERAGE.md)'s appendix matrix, same
rules (a `LIVE` row is covered when a proof drives the deployed system and asserts the effect end; SC-* and NFR-*
rows are not counted).

## The number, and why it is not 82

**115 live-provable requirements have no live proof, of 397 (282 covered, 71%).** Since retired (2026-11-06, rows mode and RI06 recovery, FR-THT-01): 18 rows marked RETIRED below, leaving 97 of 379 (74% covered). Round 3 said 82 of 352.

| Delta | Rows | Why |
|---|---|---|
| Round 3's list | 82 | unchanged: no proof added since round 3 covers any of them |
| Rows added to the matrix after round 3 as `LIVE` with no proof | +5 | FR-PRG-04, 08, 09 (rig lifecycle), FR-XOD-02, 03 (exactly-once dedupe; evidence is a local triad run, not a proof) |
| Requirement IDs added to the specs after round 3 that have **no matrix row at all** | +28 | ADR-092..102 work: FR-DRS-29..35, FR-FFR-13, FR-FGM, FR-FTL-03/06, FR-GFS-14..17, FR-HST, FR-THT, FR-XOC-03..05 |
| New IDs that are live and already covered by an existing proof | (10) | FR-DRS-26 (yu05-recon), FR-DRS-27 + FR-FFR-12 (yu18-fix-resume step 5), FR-FTL-01/04/05 (yu18-fix-tls-entry), FR-IMRG36 (yu18-control-republish), FR-TR08 (yu13-otel-*), FR-XOC-01/02 (yu13-clordid-suppression, PARTIAL: one replica, no election) |
| New IDs classed CI (impl/perf/static) | (11) | FR-DRS-28, 31, 34, FR-FGM-03, 04, 06, FR-FTL-02, FR-GFS-18, FR-HST-02, 04, FR-THT-05 |
| NFR-XOD-01..03 | excluded | NFR rows are bench-owned by the rules; the matrix lists them as LIVE, the count does not |

**One round-3 rationale is now stale and raises a priority.** FR-DRS-19..23 were left as "snapshot-mode only; the
production render is rows". Since ADR-097 the production render IS snapshot mode
(`scripts/gcp-compsci/prepare.py`: `TRADE_PROJECTION_MODE=snapshot`, `ORDER_FEED_PERSIST=false`,
`ORDER_VIEW_SNAPSHOT_MS=5000`, `PROJECTION_FEED_STREAM_LAYOUT=partition`). So production's own read-model path
(FR-DRS-19..23, 29, 30, 32, FR-FFR-13) has no live proof, and every live read-model proof so far ran rows mode.

## Columns

- **Tier**: `local` = in-process cluster (JUnit, no Docker); `kind` = a kind rig on this Mac ($0); `GKE` = needs
  something kind cannot have (GCP LB/ILB/HPA, GCS tier, the KDB-X VM licence, production rig.sh). `kind*` = kind,
  but needs a rig shape no proof rig has had (managed run, snapshot mode, partition layout, ClickHouse, Desk).
- **Risk**: what goes wrong if the property is false. `money` = positions/settlement/recon wrong; `order` = order
  lost or duplicated; `risk-gate` = a risk decision wrong; `recovery` = failover/restart/restore; then `eod`,
  `security`, `ops` (diagnostics, UI).
- **In-process test**: a test that names the ID (by ID in the test source, or in the spec/tasks line). `—` means
  none found by ID, not proof of absence; the SC-ERH-01 suite covers FR-ERH-F* without citing ids.

Ordered by risk, then tier (local/kind before GKE).

| # | FR | Property | Tier | Risk | In-process test |
|---|---|---|---|---|---|
| 1 | FR-DRS-19 | snapshot mode: a fill the bond rule rejects appears in its partition's rejected set | kind* (snapshot) | money | — |
| 2 | FR-DRS-20 | snapshot mode: `/v2/projection-feed/settlement?asOf=` settles from the stream | kind* (snapshot) | money | LedgerSettlementReconTest |
| 3 | FR-DRS-21 | snapshot mode: live-blotter recon reads the stream | kind* (snapshot) | money | — |
| 4 | FR-DRS-23 | snapshot startup: a cursor above the stream's last seq refuses | kind* (snapshot) | money | — |
| 5 | FR-DRS-29 | snapshot: per-partition count/max/sums with the cursor; a non-increasing tradeSeq is not booked | kind* (snapshot) | money | SnapshotReadModelIT, TradeViewTest |
| 6 | FR-DRS-30 | order-view snapshot: open orders upserted, closed deleted, cursor moved in one txn; restart refuses a gap | kind* (snapshot) | money | SnapshotReadModelIT |
| 7 | FR-DRS-35 | a database failure is a barrier on every feed: nothing after it applied or acked until it applies | kind | money | FeedBarrierTest, JetStreamFeedAckGapIT |
| 8 | FR-DRS-32 | partition layout: one stream per partition, every feed and reader uses its partition's stream | kind* (partition) | money | SnapshotReadModelIT, HistoryFeedKillIT |
| 9 | FR-DRS-15 | an ingest rejection logs `ALERT <bridge> partition <p>` | kind (forced 429) | money | — |
| 10 | FR-DRS-16 | the trade bridge names each rejected side | kind (forced 429) | money | — |
| 11 | FR-DRS-18 | a rejected side's repair source is the member archive in consensus order | kind* (managed run) | money | — |
| 12 | FR-APR01 | RETIRED 2026-11-06 (FR-THT-01): catch-up boundary B = applied seq captured before the page | kind* (managed run) | money | — |
| 13 | FR-APR02 | RETIRED 2026-11-06 (FR-THT-01): a page holds exact production wire bytes | kind* (managed run) | money | — |
| 14 | FR-APR03 | RETIRED 2026-11-06 (FR-THT-01): coordinates are (descriptor hash, seq, output-chain digest) | kind* (managed run) | money | — |
| 15 | FR-APR04 | RETIRED 2026-11-06 (FR-THT-01): afterSeq == B answers idle; afterSeq > B refuses | kind* (managed run) | money | — |
| 16 | FR-APR05 | RETIRED 2026-11-06 (FR-THT-01): cursor row per scope with descriptor hash and protocol version | kind* (managed run) | money | — |
| 17 | FR-APR06 | RETIRED 2026-11-06 (FR-THT-01): one atomic page under the write lock and fenced lease | kind* (managed run) | money | — |
| 18 | FR-APR07 | RETIRED 2026-11-06 (FR-THT-01): a new cursor starts at genesis and re-verifies every row | kind* (managed run) | money | — |
| 19 | FR-APR08 | RETIRED 2026-11-06 (FR-THT-01): one lease row; takeover increments the fence | kind* (managed run) | money | — |
| 20 | FR-APR09 | RETIRED 2026-11-06 (FR-THT-01): the worker never freezes, seals, selects or activates a run | kind* (managed run) | money | — |
| 21 | FR-APR10 | RETIRED 2026-11-06 (FR-THT-01): triggers: startup, NATS reconnect edge, periodic | kind* (managed run) | money | — |
| 22 | FR-APR11 | RETIRED 2026-11-06 (FR-THT-01): `GET /v2/projection-completeness` is read-only and separates the states | kind* (managed run) | money | — |
| 23 | FR-ER01 | RETIRED 2026-11-06 (FR-THT-01): operator recovery binds the registered managed descriptor and scope | kind* (managed run) | money | — |
| 24 | FR-ER02 | RETIRED 2026-11-06 (FR-THT-01): archive events at a stable boundary; refuse gaps/truncation | kind* (managed run) | money | RunIdentityConsensusTest |
| 25 | FR-ER03 | RETIRED 2026-11-06 (FR-THT-01): validate every event identity before any change | kind* (managed run) | money | — |
| 26 | FR-ER04 | RETIRED 2026-11-06 (FR-THT-01): restore missing trades and final order states idempotently | kind* (managed run) | money | — |
| 27 | FR-ER05 | RETIRED 2026-11-06 (FR-THT-01): recovery rows + position correction + checkpoint in one transaction | kind* (managed run) | money | — |
| 28 | FR-ER06 | RETIRED 2026-11-06 (FR-THT-01): operator API additive; scope revalidated under the lock | kind* (managed run) | money | — |
| 29 | FR-ER07 | RETIRED 2026-11-06 (FR-THT-01): positions equal the signed sum of retained trades before any write | kind* (managed run) | money | — |
| 30 | FR-THT-01 | MariaDB holds current state only; each side applied to positions exactly once | kind* (ClickHouse) | money | — |
| 31 | FR-THT-06 | restore gate verifies the history store per partition (count + ordered hash) | kind* (ClickHouse) | money | — |
| 32 | FR-THT-07 | either store rebuilds from cursor 0 equal to the one it replaces | kind* (ClickHouse) | money | — |
| 33 | FR-HST-01 | every published side/event stored as exactly one row with part and seq | kind* (ClickHouse) | money | RowsTest |
| 34 | FR-HST-03 | exactly once across `kill -9`: the store is the cursor | kind* (ClickHouse) | money | HistoryFeedKillIT |
| 35 | FR-HST-07 | a lane opens only on the stream incarnation its cursor binds | kind* (ClickHouse) | money | HistoryFeedIncarnationIT |
| 36 | FR-HST-05 | fills/TCA/EOD/position queries answered from the store | kind* (ClickHouse) | money | HistoryFeedKillIT |
| 37 | FR-HST-06 | history-feed reads capture streams only; `/health` per lane; HTTP routes | kind* (ClickHouse) | ops | — |
| 38 | FR-THT-02 | ClickHouse is the GKE system of record, rendered and started by `rig.sh up` | GKE | money | — |
| 39 | FR-THT-03 | hot days on local SSD, older on a GCS disk; same rows before and after a move | GKE | money | — |
| 40 | FR-XOC-03 | FIX `DUPLICATE` answered ExecType=I, OrderID ord-<ref>, never OrdStatus=8 | kind | order | FixGatewayFillReportTest |
| 41 | FR-ERH-F9 | a PRE_OPEN retry of a key the engine decided is not queued | kind | order | — |
| 42 | FR-ERH-F6 | an untyped replace of a stop/iceberg/peg/trailing order is refused | kind | order | — |
| 43 | FR-XOD-03 | a sequenced request with `seq < next` does not execute | kind (binary) | order | ExactlyOnceStreamTest |
| 44 | FR-XOD-02 | `seq == next` is processed as an unsequenced NEW | kind (binary) | order | ExactlyOnceStreamTest |
| 45 | FR-XOC-05 | binary ack status 2 = ambiguous; status 0 + DUPLICATE is the receipt | kind (binary) | order | — |
| 46 | FR-ERH-F1 | an evicted idempotent retry is answered DUPLICATE, never admitted | local | order | — |
| 47 | FR-XOC-04 | the engine dedupes the last 262,144 accepted keys | local | order | — |
| 48 | FR-ERH-F11 | FIX ClOrdID ledger capped at 65,536, eldest evicted | kind | order | — |
| 49 | FR-FFR-13 | fill feed reads every per-partition stream, in order, once each | kind* (partition) | order | — |
| 50 | FR-FFR-06 | a fill before the direct ack is still reported, after it | local | order | — |
| 51 | FR-FFR-10 | tracked set capped at 65,536 | local | order | — |
| 52 | FR-GFS-02 | no outbound FIX message transmitted before its seq and body are stored | kind | order | — |
| 53 | FR-GFS-14 | resend window of 2,048: retained messages resent exactly, older gap-filled with ALERT | kind | order | FixSessionStoreBoundIT |
| 54 | FR-GFS-15 | a full `FIX_SESSIONS` bucket does not end sessions (discard-old) | kind | order | — |
| 55 | FR-GFS-16 | a dead gateway's session resumes on another ~one lease TTL later | kind | recovery | FixSessionPersistenceIT |
| 56 | FR-GFS-17 | Logon with ResetSeqNumFlag=Y answered at 1, expects 2, whatever the stored day | kind | order | FixSessionPersistenceIT |
| 57 | FR-FTL-06 | TLS sessions keep ADR-086 lease/resume behaviour | kind | recovery | — |
| 58 | FR-IMRG07 | gateway screening is preliminary; the BLP repeats every check | local | risk-gate | — |
| 59 | FR-IMRG19 | gateway/BLP disagreement: BLP wins, disagreement counted | local | risk-gate | OrderMatcherRiskMismatchTest |
| 60 | FR-IMRG09 | stale price classified per security (fixed-point price + source time) | kind | risk-gate | — |
| 61 | FR-IMRG26 | risk table exhaustion rejects `CAPACITY` | local | risk-gate | — |
| 62 | FR-ERH-F5 | the price band re-anchors only for an order the gate accepts | kind | risk-gate | — |
| 63 | FR-ERH-F3 | a book keeps its grid while stops pend; STOP_LIMIT never floored at trigger | kind | risk-gate | — |
| 64 | FR-ERH-F7 | account-table overflow refused deterministically, never thrown in apply | local | risk-gate | — |
| 65 | FR-ERH-F2 | exposure table sized so it cannot fill on the cluster tier | local | risk-gate | — |
| 66 | FR-ERH-F10 | out-of-range securityId on a tick ignored, on a market trade refused | local | risk-gate | — |
| 67 | FR-AE03 | VWAP slices across buckets by volume profile | kind | risk-gate | — |
| 68 | FR-AE09 | empty volume profile falls back | kind | risk-gate | — |
| 69 | FR-MAR03 | risk readout carries memberId/source/sampling/freshness fields | kind | risk-gate | — |
| 70 | FR-RCM01 | member `/metrics` risk-capacity gauges | kind | risk-gate | RiskCapacityMetricsTest |
| 71 | FR-RCM02 | unknown state is "unavailable" with monetary samples absent; empty is a real 0 | kind | risk-gate | — |
| 72 | FR-RCM03 | series bounded by occupied account slots | kind | risk-gate | — |
| 73 | FR-AE08 | algo engine rebuilds parents by replaying JetStream on startup | kind | recovery | — |
| 74 | FR-ERH-F8 | full-history replay is strict; a purged archive refuses | kind | recovery | — |
| 75 | FR-GMR-08 | member `/ready` refuses until election CLOSED | kind | recovery | ReadinessDiagnosticsTest |
| 76 | FR-GMR-09 | member JVM never caches a failed name lookup | kind | recovery | MemberDnsCacheTest |
| 77 | FR-RD01 | `/ready` keeps legacy fields; gated on a CLOSED election | kind | recovery | ReadinessDiagnosticsTest |
| 78 | FR-RD02 | readiness reports peer-slot coverage | kind | recovery | — |
| 79 | FR-RD03 | readiness reports observed lag and tolerance | kind | recovery | — |
| 80 | FR-RD04 | readiness sample timestamps; cold engine nulls | kind | recovery | — |
| 81 | FR-GFS-13 | a fresh epoch purges `FIX_SESSIONS` | kind | recovery | — |
| 82 | FR-RXT06 | the extract cut travels as one message with its row count | local | eod | — |
| 83 | FR-RXT09 | a row with no close and no last trade (or no account) is handled per spec | local | eod | — |
| 84 | FR-EB29 | console distinguishes EOD unavailable/pending/running/failed/mock | kind (browser) | eod | — |
| 85 | FR-ERH-S5 | gateway and member refuse to start on an unset or known dev secret | kind | security | — |
| 86 | FR-FTL-03 | TLS logon still needs the CompID password; a wrong one is refused | kind | security | — |
| 87 | FR-CS-02 | hosted Desk session carries the desk user's own name | kind* (Desk) | security | — |
| 88 | FR-CS-03 | Desk sign-out accepts an empty body and clears both cookies | kind* (Desk) | security | — |
| 89 | FR-CUI-03 | historical Desk views never submit/replace/cancel | kind* (managed run) | order | — |
| 90 | FR-CUI-18 | gateway order commands carry the expected descriptor hash and scope | kind* (managed run) | order | — |
| 91 | FR-FGM-01 | a slow-reading FIX client is dropped at 2,048 queued writes; unsent still stored | kind | ops | FixGatewayMemoryBoundTest |
| 92 | FR-FGM-02 | inbound queue 256: socket reads pause, every message still answered | kind | ops | FixGatewayMemoryBoundTest |
| 93 | FR-FGM-05 | fill feed connect retry 1 s→60 s, one WARN per outage, metrics | kind | ops | FillFeedRetryTest |
| 94 | FR-CFR-03 | a stream ahead of the outbox is logged, not republished | kind | ops | — |
| 95 | FR-CFR-05 | a failed audit comparison is logged and retried | kind | ops | — |
| 96 | FR-AM01 | legacy `ack_unmatched` pipeline counter preserved | kind | ops | — |
| 97 | FR-AM02 | `traderx_gateway_ack_unmatched_total{reason}` with six reasons | kind | ops | — |
| 98 | FR-AM03 | `continuation` needs a retained completion in this session | local | ops | — |
| 99 | FR-AM04 | `duplicate` needs the same completion tuple and repeated kind | local | ops | — |
| 100 | FR-AM05 | `late_reaped`/`late_drained` need a retained retirement | local | ops | — |
| 101 | FR-AM06 | keyed completion, first outcome, one permit release preserved | local | ops | — |
| 102 | FR-CDM01 | `GET /instruments` and `/instruments/{key}` served | kind | ops | — |
| 103 | FR-CDM02 | instrument record field set | kind | ops | — |
| 104 | FR-CDM03 | `securityType` uses CDM literals | kind | ops | — |
| 105 | FR-CDM04 | exactly one sub-type discriminator, agreeing with securityType | kind | ops | — |
| 106 | FR-CDM09 | `/stocks` shapes unchanged | kind | ops | — |
| 107 | FR-IMRG44 | UI shows BLP and edge rejection reasons | kind (browser) | ops | — |
| 108 | FR-MUI10 | Admin trade list follows the scoped-reader rules | kind (browser) | ops | — |
| 109 | FR-TS12 | own order lifecycle captured off the running cluster into kdb | GKE (KDB-X VM) | ops | — |
| 110 | FR-TS13 | captured rows qualified by cluster epoch | GKE (KDB-X VM) | ops | — |
| 111 | FR-TS14 | an unregistered ticker captured under a synthetic id | GKE (KDB-X VM) | ops | — |
| 112 | FR-THT-04 | KDB-X lane consumes the streams exactly once, heap-guarded q | GKE (KDB-X VM) | ops | — |
| 113 | FR-PRG-04 | the rig's smoke order goes through a gateway over FIX from the console | GKE | ops | test_rig.py |
| 114 | FR-PRG-08 | `order-matcher-gw-ilb` is an internal passthrough LB to the gateway pods | GKE | ops | — |
| 115 | FR-PRG-09 | `AUTOSCALE=on`: HPA scales `cluster-gateway` on CPU 60 % up to 18 | GKE | ops | test_rig |

## Tally

| Tier | Rows |
|---|---|
| local (in-process cluster) | 16 |
| kind, on the existing kind rig shape | 51 |
| kind*, needs a rig shape no proof rig has had: managed run 21, ClickHouse 8, snapshot 6, partition 2, Desk 2 | 39 |
| GKE only | 9 |
| **total** | **115** |

By risk: money 38, order 18, risk-gate 15, recovery 11, eod 3, security 4, ops 26.

## What remains (updated as proofs land)

See "Live-proof gap closure" at the top of [COVERAGE.md](COVERAGE.md) for what landed, and the GKE batch
proposal at the end of this file.
