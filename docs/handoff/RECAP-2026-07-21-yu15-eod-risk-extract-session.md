# RECAP — YU15 EOD risk extract session (2026-07-21/22)

> Session recap, untracked working note. Home: `traderX-YU14-listed-equity-options` worktree,
> branch `YU15-eod-risk-extract`, parented on YU14.
> **6 commits, tree clean, NOT pushed.** 80 files changed, ~8.4k insertions.
> GKE never touched. `ClusterGatewayMain` never touched.

## What was built

The end-of-day risk extract — the portfolio fixture Rich & Alex's JAX/ORE pricing-and-risk engine
consumes. This is the project's only external deadline (their engine arrives ~2026-08-21).

`eod.pnl.done` triggers it → a sequenced marker names a consensus sequence N → every cluster member
renders the identical position cut at N and the leader publishes it → the producer joins that cut
with the published closing prices and the counterparty reference data → one immutable,
byte-reproducible CSV, written write-once and announced on `risk.extract.ready`.

### Alex's requirements, and where each landed

| Requirement | How |
|---|---|
| Official closing marks that tie out with our P&L | Published `eod_price_snapshot` version; market value recomputed with YU06's own formula and multiplier — ties exactly, for options too |
| Snapshot on demand, not a stream | Durable JetStream consumer on `eod.pnl.done`; one object per batch |
| A clean point-in-time cut | Sequenced marker → state at N on the replicated log, never the SQL read model |
| Un-netted, account + security | Row grain is `(accountId, security)`; counterparty and netting set ride as attributes |
| Current state only, no history | Positions at N; no history anywhere in the artifact |
| Byte-identical reproducible fixture | Pure function of the cut; proven across members, across rebuild, across replay |
| Counterparty ID for netting | From `counterparties.csv`; an unmapped account aborts the extract |

## The two design decisions

**ADR-055 — a sequenced marker names the cut.** The alternative was parsing the Aeron Archive
snapshot, but a snapshot's sequence is whatever the consensus module chose (possibly hours stale,
never "as of now"), and reading it duplicates the record codec outside the state machine. A command
that mutates nothing still *occupies* a sequence, and that sequence is agreed by consensus rather
than sampled by a reader. The cut is published leader-only over the ADR-048 SPSC-queue/daemon-thread
shape, as one self-counting NATS message — cluster egress was rejected for the same reason ADR-048
rejected it: a truncated cut arrives looking complete.

The nicest part: **quiescence is witnessed, not assumed.** A second marker after the join must land
at exactly `N+1`. Nothing else can have been sequenced in between, so the consensus log is its own
witness that no trading occurred mid-build. One extra log entry turns the brief's "verify `applied`
is unchanged" into an exact, in-band check needing no new endpoint.

**ADR-056 — mark sourcing.** Published close where one exists, the cut's own last trade at N
otherwise, stamped per row. Originally forced by a blocker the brief didn't name; after the feed fix
below, the published close is the normal path for options too and the fallback is a genuine fallback
again.

**Reproducibility is decomposed into two provable halves**, which is what makes each testable: the
cut is a pure function of replicated state (provable across members and replay), and the fixture is
a pure function of the cut (provable by `--rebuild`). Operational facts — the witness sequence, the
sink URI, timestamps — deliberately live in the *announcement*, never in the fixture body; in the
file they would break rebuildability from the cut alone.

## Three bugs found and fixed

### 1. Listed options could not reach the database at all

Every `security`/`ticker` column was `VARCHAR(15)`/`VARCHAR(16)`; an unpadded OCC symbol is 19
characters. MariaDB's strict mode rejected the insert, so every option fill the ADR-048 trade bridge
published was dropped by `trade-processor` — the blotter, the positions read model and the whole
YU06 price/P&L chain silently excluded every option. All seven columns are now `VARCHAR(32)`.

The subtle part: widening the `CREATE` statements only fixes *freshly created* databases. The
migrations block is what the `schema-migrate` initContainer applies to a populated PVC, and
`CREATE TABLE IF NOT EXISTS` is a no-op against a table that already exists — so it gained seven
explicit `ALTER TABLE ... MODIFY COLUMN` statements. Same reason YU05's `settlementdate` needed an
explicit `ADD COLUMN`; this is the first migration in the lineage that *modifies* rather than adds.
The JPA entities were already `@Column(length = 50)`, so the schema was the only constraint and no
service code changed.

### 2. A member restarting into an idle cluster never rejoined the Service

Readiness compared `engine().blpSeq()` against peers. That counter only advances when the engine
applies an event, and a member restored from a snapshot has applied none — so it reported `-1` while
fully caught up, and stayed NotReady until trading resumed. Any member restarting into a quiet
cluster hit this; **an EOD window is precisely when the cluster is quiet**, which is why this state
surfaced it. `/health` and `/ready` now report the consensus-log position, with `blpSeq` kept visible
as `engineApplied`. A rolling restart of all three members now completes, which it could not before.

### 3. A shared JetStream stream is fixed by whoever creates it first

The producer created `TRADERX_EOD` carrying only `eod.pnl.done`, which left trade-processor's
`eod.prices.ready` publish with **no responder** and broke the batch chain *upstream* of us. Only
visible once the real chain ran. `ensureStream` now declares the whole subject family and repairs an
existing stream that is missing one — it repaired the damaged stream on redeploy.

## The price feed: options now get a published close

`PriceHistoryStore` is fed only from `pricing.*`, and price-publisher quoted equities only. An
option was therefore `MISSING` in every EOD snapshot, and YU06's fail-safe halts an **entire
account** when any holding is unpriced — so no account with an option position was ever marked.

**Why the feed and not our own trade prints:** the architecture is explicit that `pricing.*` is
*market data* and the EOD close derives from market data; executions are a different kind of
observation. It also fails for the instrument that matters most — a contract held but not traded
today has no print, and the staleness window that correctly detects a dead equity feed would flag
it and block the session. Marking a position requires a price whether or not it traded.

`price-publisher` now quotes the listed chain, each contract **derived from its underlying's current
tick** (Black-Scholes, flat implied vol, floored at intrinsic) rather than walked independently.
Walking would let a call and a put on the same strike drift into contradicting each other — no
pricing engine can reconcile that. Flat vol is deliberate: a consumer running the same inputs
reproduces our mark exactly, and a smile would be a modelling opinion this venue has no business
asserting. Inputs are reported on `/health` so they can. Nothing downstream changed.

Two consequences had to be handled, and they are the interesting part:

- **The spike gate would have taken down the whole EOD chain.** One flagged instrument blocks
  publication of the entire session. 20% day-over-day is an alarm for an equity and unremarkable for
  a leveraged contract, so every option would have flagged — killing *equities'* publication too.
  The band is now instrument-aware. Only the band: staleness, missing-price detection and the
  account-level fail-safe are unchanged for every instrument.
- **`eod_position_pnl.market_value` was 100x wrong for options** — `quantity × price` with no
  multiplier, i.e. a hundredth of the real exposure, on exactly the number Alex reconciles base NPV
  against. Now multiplier-aware; equities unchanged bit for bit.

Result: **every row of the delivered fixture reads `markSource=EOD_SNAPSHOT`**, and the tie-out
closes exactly — `eod_position_pnl` `market_value 4751.500000` == fixture `marketValue 4751.500000`
for the same row.

## Proof

| Suite | Result |
|---|---|
| order-matcher | **258 / 0** (YU14 carried 242; +16 YU15 tests) |
| trade-processor | **63 / 0** (+6 instrument-aware spike cases) |
| position-service | **10 / 0** (+2 multiplier cases) |
| price-publisher | **11 / 0** (`node:test`, no framework dependency added) |
| Epsilon gates | `noGcTest` + `riskNoGcTest` pass |
| Allocation gates | all four, executed fresh (`--rerun-tasks`) |
| Clean generation | `pipeline/generate-state.sh YU15-eod-risk-extract` → **EXIT=0** |
| Shared-override check | YU14, YU13, YU06 and YU02 markers all still present in the generated tree |

### Live proof 1 — `scripts/proofs/yu15-risk-extract.sh` (7 steps)

Runs the **real** chain, nothing hand-seeded and nothing hand-published:

1. `/eod/session/close` published a version with **44 instruments, 0 flagged, all 24 option
   contracts at quality OK**; position-service marked `accounts=3 halted=0 rows=8` and emitted
   `eod.pnl.done`; the extract fired off that event.
2. All 3 members logged the identical cut `sha256` at the stamped sequence.
3. `quiesceWitnessSequence` == stamped sequence + 1.
4. `--rebuild` from the stored `.cut` byte-compared equal to the delivered `.csv` (18 rows).
5. The delivered object is present and write-once.
6. `order-matcher-cluster-2` deleted → replayed to the stamped sequence → re-rendered the identical
   `sha256`.
7. The restarted member rejoined the Service.

### Live proof 2 — `scripts/proofs/yu15-option-persistence.sh` (4 steps)

Demonstrates the schema bug before fixing it, on the real bridge → NATS → trade-processor chain:
narrows the columns back to an older state's widths → books an option cross and shows **0 rows**
with `Data too long for column 'security'` while the cluster books the fill regardless → applies the
shipped `900-migrations.sql` read from the applied ConfigMap → books another cross that persists
both trade rows and both position rows with the 19-character symbol intact.

### The delivered fixture

```
22214,AAPL,EQUITY,13,1,241.800000,241.477000,EOD_SNAPSHOT,OK,3139.201000,-4.199000,USD,CPTY-CASCADE-AM,NS-CASC-ISDA-01
22214,AAPL260918C00240000,OPTION,10,100,3.900010,10.709000,EOD_SNAPSHOT,OK,10709.000000,6808.990000,USD,CPTY-CASCADE-AM,NS-CASC-ISDA-01
22214,AAPL261218C00260000,OPTION,5,100,2.400000,9.503000,EOD_SNAPSHOT,OK,4751.500000,3551.500000,USD,CPTY-CASCADE-AM,NS-CASC-ISDA-01
```

10 contracts × $10.709 × 100 = $10,709 — the YU14 multiplier proving itself end to end, from
cluster state through the format-3 snapshot into the consumer's file.

## Traps hit / notes for the next lane

- **The producer's pod label must stay in the cluster NetworkPolicy ingress allowlist.** Without it
  the Aeron client cannot reach any member and it surfaces as an ack timeout, not a connection
  error (the T-LOB16 signature).
- **Two files sit OUTSIDE the kind kustomization** and are applied by the start script:
  `gateway.yaml` (inherited from YU12) and `database-init-configmap.yaml` (kustomize cannot
  reference a file outside its root; referencing the schema in place keeps exactly one copy).
  Applying the kustomization alone leaves the gateway missing and the database unschema'd.
- **A batch producer must not die on a cold dependency** — it simply is not there when the batch
  fires. NATS connect retries forever; the stream is ensured idempotently at both ends.
- **The cluster session is opened per batch, not held.** One opened at startup would be hours stale
  and possibly pointed at a former leader by the time the batch runs.
- **The `nats` image carries no `nats` CLI.** Publishing over the wire protocol directly (~10 lines
  of Python) beat pulling `nats-box`.
- **A contract whose underlying is absent from `PRICE_TICKERS` is skipped, not quoted.** Adding
  contracts on a new underlying means adding the underlying too, or they silently never price — and
  an unpriced holding halts its whole account.
- **The option-persistence proof clears option rows before narrowing the columns.** Now that the
  feed quotes the chain and fills persist, real 19-character rows exist and MariaDB correctly
  refuses to shrink a column under them.
- **`grep -v` exits non-zero on empty output**, which under `set -e` silently killed a proof script
  mid-step. Guard shell helpers whose commands legitimately produce nothing.
- **Feed bootstrap spots were mis-centred:** `snapshot-prices.json` had AAPL at ~192 while
  `instruments.csv`, the seed script and the chain strikes were all designed around 241.80, so every
  listed contract was deep out of the money the moment it was quoted. Aligned in the YU15 layer.
- **`ThreeMemberClusterTest` is host-load sensitive.** It timed out once with the kind cluster
  saturating the machine (load avg 20, Docker VM at 686% CPU) and passed on re-run and in isolation.
  A contention flake, not a regression — check load before believing a failure.
- **Only the seven real SQL accounts** are in `counterparties.csv`, and an unmapped account aborts
  the extract by design.

## Known debt

- **TD-RXT01** — the cut is one NATS message, bounded at ~15k position rows by the 1MB default
  payload. The declared row count detects an overrun; chunking or writing the cut straight to the
  object store is the path past it.
- **TD-RXT02** — option closes are *modelled* quotes (flat IV), not settlement prices from a
  listed-options venue. The one place where Alex reconciles against a number we invented rather than
  observed.
- **TD-RXT04** — the OCC predicate now exists in three modules: canonical in order-matcher's
  `OccSymbol`, plus copies in trade-processor and position-service. Separate Gradle builds and an
  unchanging industry format make it defensible, but they are copies.
- **GCS delivery is built but proven only against `file://`.** The GKE overlay needs the `gs://`
  sink URI and the HMAC secret pair; the parallel lane owns that cluster.

## Open questions with the consumer (surfaced, not resolved — per the brief)

- Whether `accountId → counterparty` suffices for their netting/CSA logic, or whether they need a
  counterparty entity with its own attributes. The mapping is built and the assumption is flagged.
- P&L methodology alignment — our cost-basis and valuation conventions are written into the
  fixture's own header, so any discrepancy hunt starts from a stated convention rather than a guess.
- Whether computed results flow back for a UI/Grafana surface. The subject family is named so
  `risk.analytics.*` slots alongside `risk.extract.*` with nothing renamed.

## Commits (none pushed)

| Commit | What |
|---|---|
| `4057fd81` | Sequenced extract marker (SBE template 8), canonical cut + SHA, leader-side NATS bridge, `RiskExtractCsv`/`RiskExtractMain`/`RiskExtractGcsSink`, 15 unit proofs |
| `ed348472` | kind tier (NATS + price DB + producer), 7-step acceptance proof, idle-cluster readiness fix |
| `0da7126a` | Spec pack in house style, ADR-055/056, generated architecture, doc sync |
| `91fe89e1` | Widen instrument-identifier columns + `ALTER ... MODIFY` migrations; trade-processor on kind; option-persistence proof |
| `d0d2e371` | Quote listed options in the price feed; instrument-aware spike gate; multiplier-aware EOD P&L; full EOD chain on kind; JetStream subject-family fix |
| `4f284deb` | A real delivered fixture + its cut saved as the sample contract, so the deliverable survives the cluster teardown |

## The kind cluster

A parallel lane is scaling the cluster down, so treat it as gone. Nothing depends on it: all commits
are on the branch, and a real delivered fixture plus the cut it was built from are saved at
`specs/YU15-eod-risk-extract/contracts/sample/` — byte-exact copies, self-verifying via the
`cutSha256` recorded in the fixture's own preamble. The contract can be read and loaded against
without standing anything up.

To reproduce from cold (image builds dominate; budget ~15 minutes):

```bash
bash pipeline/generate-state.sh YU15-eod-risk-extract
bash scripts/yu15/build-cluster-image.sh

# the EOD chain needs three more images, built from the generated tree
(cd generated/code/target-generated/trade-processor  && ./gradlew -q bootJar && docker build -q -t traderx/trade-processor:yu15 .)
(cd generated/code/target-generated/position-service && ./gradlew -q bootJar && docker build -q -t traderx/position-service:yu15 .)
(cd generated/code/target-generated/price-publisher  && docker build -q -f Dockerfile.compose -t traderx/price-publisher:yu15 .)
for i in trade-processor position-service price-publisher; do
  kind load docker-image traderx/$i:yu15 --name traderx-yu12-cluster
done

bash scripts/yu15/start-cluster-kind.sh

# seed instruments and take positions, then run both proofs
kubectl --context kind-traderx-yu12-cluster -n traderx port-forward svc/order-matcher 18110:18110 &
MATCHER_URL=http://localhost:18110 bash scripts/bench/seed-option-chain.sh
bash scripts/proofs/yu15-risk-extract.sh
bash scripts/proofs/yu15-option-persistence.sh
```

`stop-cluster-kind.sh` does `kind delete cluster` — it removes the whole cluster including the
YU12/YU13/YU14 state on its PVCs, not just this state's tier.
