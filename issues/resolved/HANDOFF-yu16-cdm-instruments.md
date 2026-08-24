# HANDOFF — YU16-cdm-instruments: fold Jack's 016 + 017 into a new YU state

**Status:** not started, planned in detail.
**Worktree:** `~/dev/lmax/traderX-YU15-eod-risk-extract` (branch `YU15-eod-risk-extract`, the tip).
**Source material:** Jack's fork, cloned at `~/dev/jack/traderx` (branch `main`, tip `808b683`).
**Rig:** kind only — no GCP credits. The rig is currently **stopped but not deleted** (see the end).

---

## The goal

Jack (another student on the project) built two states in his own fork of TraderX:

- **`016-cdm-generic-instruments`** — a focused slice of the FINOS Common Domain Model: a general
  instrument model with standardized security types and identifiers, FIGIs resolved via OpenFIGI and
  baked into seed data, plus five ETFs (SPY, QQQ, IWM, VTI, GLD). Gives CDM **Equity** and **Fund**
  instead of assuming every instrument is a stock.
- **`017-us-treasury-trading`** — CDM **Debt**: five fixed-rate US Treasuries with real FIGIs and
  TreasuryDirect auction prices seeding the simulation. Quantities are face amount, prices are clean
  % of par, value is `face × clean ÷ 100`, and the UI shows coupon, maturity, approximate YTM and
  clean value. Prices are maturity-sensitive rather than equity-shaped.

The professor wants this folded into our line. **Create one new state, `YU16-cdm-instruments`,
combining both of his packs — carrying only what we actually bring over, plus requirements of our
own for compatibility, integration and contracts.**

Do NOT name it YU17. Our tip is YU15 and there is no YU16; mirroring his numbering would leave a
hole in our sequence. This was decided.

---

## Three structural mismatches that drive everything

**1. Lineage.** His 016 parents from `009-order-management-matcher`, deliberately — his own spec
says *"The newest functional tip is 014 (previous: 012), not 009"* and picks 009 anyway for a
product reason. Our **YU02 declares `014-fdc3-intent-interoperability` as its single publish-lineage
parent**, so our stack sits on the full upstream chain 010→014 and his does not.

The practical consequence: **his runtime is docker-compose.** His 016 patch ships an entire
`cdm-generic-instruments/` compose stack with its own Grafana dashboards, Loki, Tempo, Prometheus
and NATS config. None of that ports — we are on Kubernetes with our own observability.

**2. Generation model.** He uses `generation/patches/0001-state-overlay.patch` (3,951 lines for 016,
6,787 for 017) plus `generation/frontend-overrides/`. We use `generation/runtime-overrides/<service>/…`
whole-file overrides composed last-wins. **His packs cannot be consumed; they must be translated.**
His patches are diffs against a 009 tree and our files at those paths are fifteen states downstream.

**3. His 016 removes `/stocks`.** FR-01602 says it SHALL be removed and SHALL NOT be aliased;
SC-01607 asserts `GET /stocks` returns 404. **We are keeping `/stocks`** — see below, and it is not
a matter of taste.

---

## What comes over

| His change | Verdict |
|---|---|
| `reference-data/` — new `instruments/` module (model, controller, service), data-loader, openapi, seed CSV | **Port.** Same NestJS service. The foundation; everything else depends on it landing first |
| `price-publisher/` — `treasury-pricing.js` (new, self-contained), `main.js` hooks, `snapshot-prices.json`, tests | **Port.** Node, outside the deterministic core |
| `trade-processor/` — `InstrumentMetadata`, `Trade`, `TradeOrder`, `TradeState`, `InstrumentMetadataClient`, `TradeService`, `RuntimeConfig` | **Port with merge.** We run this service and override it at several YU layers |
| `position-service/` — `Trade` model, `TradeRepository` | **Port with merge** |
| Frontend overrides — asset-class filter, bond display, models, services, specs | **Port.** His Angular work is good; screenshots confirm it |
| `database/initialSchema.sql`, `postgres-database-replacement/` | **Rework.** He is on Postgres; we are MariaDB, and our schema lives in `database-init-configmap.yaml` |
| `trade-service/TradeOrderController.java` | **Rework.** YU12 already overrides this file |
| `order-matcher/` — `OrderMatcherService`, `OrderRepository`, `OrderRecord`, `api/*`, `config/HttpClientConfig` | **Does not port.** These are the **State-009 Spring matcher**. YU01 replaced it with the LMAX BLP; YU12 replaced that with the Aeron cluster. The classes do not exist for us. Re-express the *intent*, not the diff |
| `cdm-generic-instruments/` compose stack + observability configs | **Drop** |

---

## The `/stocks` decision, and why it is not preference

`/stocks/control-snapshot` is what **YU04's durable control feed** uses, and it is load-bearing for
`yu04-live-delta`, `yu04-offline-catchup` and the suite runner. Removing `/stocks` breaks a shipped
feature and two proofs.

There is exactly one runtime consumer, and it is already config-driven:

```java
// YU04 layer, order-matcher/…/risk/ReplicaBootstrap.java
@Value("${risk.bootstrap.securities-snapshot-url:http://reference-data:18085/stocks/control-snapshot}")
```

A **default, not a constant** — repointing it is configuration, not code. There is a sibling
`/account/control-snapshot` on account-service; only the securities half is in scope.

### Decision: add `/instruments/control-snapshot`, keep `/stocks/control-snapshot`

Additive, not a rename. The general name is genuinely more correct — the feed carries the whole
security universe, the engine's command is already `SECURITY_CONTROL` not `STOCK_CONTROL`, and once
Treasuries are in it "stocks" is simply wrong. But a flag-day rename moves every consumer at once,
and **Jack's pack is the cautionary tale**: he removed `/stocks`, then had to add SC-01610 ("State
009's lifecycle scripts are untouched and still probe `/stocks`") to cope with the fallout.

So: add the general route, keep the old one serving, repoint the bootstrap default at the YU16
layer, migrate the two yu04 proofs, and declare the retention in YU16's spec with the reason
attached. Amend YU04's architecture model **by declaration in YU16's pack**, not by editing YU04.

**Leave the NATS subjects alone.** The outbox and JetStream subjects carry the same
securities-vs-instruments naming question, but renaming a durable stream subject IS a flag day — the
consumer's position is keyed to the stream. Note the inconsistency in the pack; do not fix it here.

---

## The deterministic engine: expect no change

Matching is price-time priority over integers. The core has no concept of "stock" — it has a symbol
table, per-security book geometry, and a per-security `contractMultiplier` that YU14 added for
options. `instrumentType` in the risk extract is **derived** (`OccSymbol.isOption(security)`), not
stored. Coupon, maturity and YTM are valuation attributes belonging in reference-data and the read
model, which is where Jack already put them.

**The one trap.** Risk computes notional as integers:

```java
notional = Math.multiplyExact(Math.multiplyExact((long) quantity, validationPrice), multiplier);
```

`contractMultiplier` is a `long` and `multiplier < 1L` **fails closed** by design. A bond's `÷100`
cannot be a multiplier.

**DECIDED — solve it by convention, not by code**: store bond prices internally as a **fraction of
par**, never as a percentage.

```
quoted 99.886%   ->   stored 0.998860   ->   998,860 ticks at the 1e6 scale
```

Worked against Jack's own position — 100,000 face of UST-20280630 at 99.886%, which his UI values at
$99,886.00:

```
percentage stored:  100,000 x 99.886    = 9,988,600   100x too big; needs multiplier 1/100, impossible
fraction  stored:   100,000 x 0.99886   =    99,886   correct, multiplier stays 1
```

So a bond becomes arithmetically identical in shape to an equity: an equity at $240.00 is
240,000,000 ticks and `qty x px` works; a bond at 0.99886 of par is 998,860 ticks and `qty x px`
works the same way. Nothing in the engine changes.

The "99.886%" is purely **display** — multiply the stored fraction by 100 and append the sign, which
Jack's frontend already does, so it is not new work. Precision holds: six decimals on the fraction
is four on the percentage (`0.998860` -> `99.8860%`), and his UI shows three.

This convention is binding on the price publisher (what it emits), the engine (what it stores), the
read model (what it persists) and the risk extract (what it renders). All four must agree.

Consequences worth stating as requirements: **no `SNAPSHOT_FORMAT` bump** (currently 4, with
`MIN_READABLE_SNAPSHOT_FORMAT` 3), **no fresh epoch, no PVC wipe, no mixed-version divergence risk.**

Limits to keep in view: `MAX_SECURITIES` is 1024 (plenty for five ETFs and five Treasuries) and
`MAX_ORDER_QUANTITY` is 1,000,000 — face amounts of 100,000 are fine, million-plus blocks are not.

---

## The pack: ported requirements PLUS four families of our own

Do not let the pack be only a filtered copy of his. It needs requirements that exist because of
*our* system, in four families:

**Compatibility.** `/stocks` and `/stocks/control-snapshot` retained and serving; the YU04 durable
control feed unchanged; the gateway's `ticker`→`security` fallback still resolves CDM identifiers.
Attach the *reason* to each — these are exactly the ones a future reader will try to tidy away.

**Contract supersession.** Explicit and traceable by his ids: YU16 supersedes **FR-01602**
(`/stocks` SHALL be removed) and does not adopt **SC-01607** (`GET /stocks` returns 404). Naming his
ids makes the divergence deliberate rather than looking like an oversight.

**Integration.** Bond prices stored as fraction of par so notional stays integer; instrument type
and bond static reach the EOD extract **by join** (the way `counterpartyId` already does), not by
changing the `.cut`; extract CSV schema version bump; `docs/engineering/risk-extract-consumer-guide.md`
updated, because that document currently tells Alex's risk engine the schema is `schema=1`.

**Non-regression, as falsifiable constraints.** These keep the work honest:

- YU16 SHALL NOT change `SNAPSHOT_FORMAT`
- YU16 SHALL NOT require a fresh epoch or a PVC wipe
- **All proofs SHALL remain green**

---

## Phases

**0 — Settled already, nothing to decide.** The name is `YU16-cdm-instruments`. Bond prices are
stored as a **fraction of par** (see the engine section). Both were decided before this handoff was
written; they are recorded here so they are not relitigated, and because everything downstream
depends on them. Start at phase 1.

**1 — Author the combined spec pack.** One `spec.md` folding 016 + 017, carrying only requirements
we will implement, his FR/SC ids remapped, `/stocks` inverted, plus the four families above. **Do
this first** — it is the filter that decides which code gets ported.

**2 — reference-data.** Instruments module, CDM types, FIGI seed data, ETFs and Treasuries. Keep
`/stocks`. Add `/instruments/control-snapshot`. Everything downstream depends on this.

**3 — price-publisher.** `treasury-pricing.js` plus the snapshot seeds. Self-contained, low risk.

**4 — Schema.** Translate his Postgres DDL into `database-init-configmap.yaml` (MariaDB). Watch the
`VARCHAR(15)` class of bug — OCC symbols already caused one silent truncation on this project.

**5 — trade-processor + position-service.** Merge his model and metadata changes into our overridden
versions. **This is where the time goes** — see below.

**6 — Frontend.** Port his Angular work onto our web-front-end.

**7 — Extract schema + docs.** New `instrumentType` values plus face/coupon/maturity. Bump the CSV
schema and update the consumer guide.

**8 — Proofs.** All existing proofs stay green, plus new ones for Treasury pricing and the bond
position math.

---

## Where the time actually goes

Not the engine. **Phase 5 and the propagation.** His diffs land in exactly the files the YU layers
override most — reference-data, price-publisher, trade-processor, the DB configmap — and generation
composes layers **last-wins**, so a fix applied to a shadowed layer is inert. That is the failure
mode that has cost this project the most: a CVE fix that landed in a dead layer, a `PubSubConfig`
that shadowed YU05's pricing.

Before editing any file, `grep` every `specs/*/` layer for a same-named file and patch the operative
(last) one — or all of them. `.claude/skills/propagate-spec-fix` is the playbook.

---

## Working constraints

- **kind only.** No GCP credits. Cluster `kind-traderx-yu12-cluster`, namespace `traderx`.
- **Never `git push`.** Commit with a real message; yaakov pushes.
- **Never add a `Co-Authored-By: Claude` trailer** or "Generated with Claude Code" to any commit or
  PR body. Commit as yaakov only. This applies to subagents and background tasks exactly as to the
  main session — pass it down when you delegate.
- **Handoff/scratch docs stay untracked** at the worktree root (`HANDOFF-*.md`, `STATE.md`,
  `LEARNING.md`). Durable project facts belong in the tracked `issues/` directory.
- **Stage explicit paths, never `git add -A`.** These worktrees are shared by several lanes.
- **Subagents: no more than 5 at a time.** Parallelise the propagation sweep if you like — it is
  mechanical and fans out well — but keep concurrency at or below five.

## Rig state

Stopped, not deleted. The four kind node containers were `docker stop`ped, so PVCs, the epoch and
the deployed stack are all intact. Bring it back with:

```bash
docker start traderx-yu12-cluster-control-plane traderx-yu12-cluster-worker \
             traderx-yu12-cluster-worker2 traderx-yu12-cluster-worker3
```

Allow a minute or two — the Aeron members recover from snapshot + log and hold an election.

Useful scripts that already exist and are verified:

- `scripts/yu15/build-cluster-image.sh` → `scripts/yu15/start-cluster-kind.sh` — full rebuild
- `scripts/yu15/start-frontend-kind.sh` — attaches the Angular UI to the Aeron rig (needed if you
  want to see instruments in the UI; it is what makes Jack's frontend work visible on our tier)
- `scripts/yu15/seed-proof-fixtures.sh` — accounts, securities, positions
- `scripts/sim/run-session.sh --minutes 10 --symbols 12` — a compressed trading day
- `scripts/yu15/run-proofs.sh` — the whole proof suite
- `scripts/proofs/README.md` — the by-hand run order, which forwards each proof needs, and what
  disrupts what. Read it before running proofs one at a time.
