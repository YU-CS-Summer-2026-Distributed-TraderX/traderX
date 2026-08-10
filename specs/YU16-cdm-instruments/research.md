# Research: CDM Instruments

## Why bond prices are a fraction of par, not a percentage

The engine's risk gate computes notional as
`Math.multiplyExact(Math.multiplyExact((long) quantity, validationPrice), multiplier)` with a
`long` contract multiplier that fails closed below 1. A bond quoted the market's way — 99.886, a
percentage of par — priced against a face quantity gives `100,000 × 99.886 = 9,988,600`: one
hundred times the real value, and the correction factor (÷100) cannot be expressed as a `long`
multiplier. Every escape from that leads into the deterministic core: a per-security divisor is a
snapshot field, a special-cased instrument class is a matching change, and either one is a
format bump and a fresh epoch.

Storing the price as a **fraction of par** dissolves the problem instead of solving it:
`99.886% → 0.998860 → 998,860 ticks` at the 1e6 scale. Now
`100,000 × 0.998860 = 99,886` — correct, with the multiplier at 1. A bond becomes arithmetically
identical in shape to an equity: an equity at $240.00 is 240,000,000 ticks and `qty × px` works;
a bond at 0.998860 of par is 998,860 ticks and `qty × px` works the same way. Nothing in the
engine changes, so nothing rolls, so no epoch is minted.

Worked against the source pack's own seed position — 100,000 face of `UST-20280630` at 99.886%,
valued there at $99,886.00:

```
percentage stored:  100,000 × 99.886   = 9,988,600   100× too big; needs multiplier 1/100 — impossible
fraction   stored:  100,000 × 0.99886  =    99,886   correct; multiplier stays 1
```

The percentage is purely display: multiply the stored fraction by 100 and append the sign, which
the ported UI already does. The convention is binding on four surfaces that must agree — the
price publisher (what it emits), the engine (what it stores), the read model (what it persists),
and the risk extract (what it renders). A disagreement between any two is a silent 100× error.

## Why bond marks need six decimals — and where three would lose them

Six decimals on the fraction is four on the percentage (`0.998860 → 99.8860%`), and the source
UI displays three, so precision holds — but only if nothing on the path rounds early. Two of the
three ingestion paths already preserve six decimals: the cluster gateway converts
`Math.round(limitPrice × 1e6)` and the extract renders ticks at scale 6. The third does not: the
price publisher's inherited `toPriceTicks` applies the 3-decimal HALF_UP contract from the equity
feed *before* scaling, which would turn `0.998860` into `0.999000` — one decimal of percentage
precision, a $114 error on a 100,000-face position. Treasury payloads therefore scale at six
decimals (`round(fraction × 1e6)`), and the 3dp contract remains exactly what it always was: the
equity feed's. The same reasoning fixes the SQL columns: a `DECIMAL(18,3)` price column holds a
fraction of par to one percentage decimal, so every column that can carry a bond price widens to
six decimals.

## Why `/stocks` stays

`/stocks/control-snapshot` is what YU04's durable control feed bootstraps from, and it is
load-bearing for `yu04-live-delta`, `yu04-offline-catchup` and the proof-suite readiness gate.
There is exactly one runtime consumer and it is already config-driven — a `@Value` default, not a
constant — so the *general* route can be adopted by configuration while the old route keeps
serving. The source pack removed `/stocks` outright and then had to add a success criterion
(SC-01610) asserting that its parent state's lifecycle scripts — which still probe `/stocks` —
were untouched, which is the cautionary tale in one sentence: a flag-day rename moves every
consumer at once or it strands them.

So this state adds `/instruments/control-snapshot` alongside, serving the identical contract over
the same store and watermark; repoints the bootstrap default at its own layer; migrates the two
YU04 proofs to the general route; and leaves the suite readiness gate probing
`/stocks/control-snapshot` — which turns the gate into a standing regression check that the
retention requirement holds. The durable stream and subject (`TRADERX_CONTROL_SECURITY`,
`traderx.control.security.deltas`) are not renamed: a consumer's position is keyed to the
stream, so that rename is a real flag day and is recorded as TD-CDM02, not fixed here.

## Why the deterministic engine does not change

Matching is price-time priority over integers. The core has a symbol table, per-security book
geometry, and a per-security `contractMultiplier` that YU14 added for options — it has no concept
of "stock", and `instrumentType` in the extract is *derived* (`OccSymbol.isOption`), not stored.
Coupon, maturity and YTM are valuation attributes: they belong in reference-data and the read
model, which is where the source pack already put them. With prices as fractions of par
(multiplier 1) there is no bond-shaped arithmetic left for the engine to learn. The ten new
instruments enter the engine the way every instrument does — as securities registered through
the existing `SECURITY_CONTROL` feed — well inside `MAX_SECURITIES` (1024), with face amounts of
100,000 well inside `MAX_ORDER_QUANTITY` (1,000,000).

## Why the extract joins instrument static rather than carrying it

The `.cut` is the replicated state machine's own state at a consensus sequence — quantities,
cost ticks, multipliers, last trades. Coupon and maturity are not replicated state and never
will be; putting them in the cut would mean teaching the engine reference data so the cut could
carry it back out. The extract already solves this exact problem for `counterpartyId`: the
producer joins the cut with immutable reference data at render time, and reproducibility holds
because the fixture is a pure function of cut + static. `instrumentType: TREASURY`, `coupon` and
`maturityDate` take the same road — the instrument static CSV gains the columns, the producer
joins on `security`, the cut bytes do not change, and the CSV schema bumps to 2 because the
column set is different for every consumer.

## Why treasuries book asynchronously, unlike the source pack

The source pack routed Treasury executions synchronously from its Spring matcher to
trade-processor, with pending-trade persistence, tick-driven reconciliation, striped locks and
pessimistic position locking — roughly 1,000 patch lines whose job is to not lose or double-book
a fill between two services with no durable channel between them. This system already has the
durable channel: the cluster's leader-side trade egress publishes every booked fill to `/trades`
at-least-once, keyed so the processor's JPA identity dedups redeliveries. Adding a synchronous
special path for one asset class would re-solve a solved problem and fork the trade flow in two.
Treasuries ride the same path as equities; what *is* ported from that machinery is its
fail-closed core — the `Rejected` trade state, metadata-before-transaction, and face-weighted
average cost.

## Why FIGIs are baked offline

Identifiers were resolved once against OpenFIGI v3 (keyless tier) and committed as seed data;
the runtime never calls a symbology provider, holds no credential, and starts with the network
down. `ISIN` and `CUSIP` remain valid identifier types but stay unpopulated — both are licensed;
FIGI is openly licensed. CUSIPs used as transient auction-lookup inputs were never persisted.
Two quirks worth keeping: OpenFIGI's `securityType2` calls SPY a "Mutual Fund", so classification
keys off `securityType` (`ETP → Fund/ExchangeTradedFund`); and delisted-but-supported tickers are
baked explicitly rather than re-resolved.

## Source-pack traceability

The source packs live in Jack's fork (`016-cdm-generic-instruments`,
`017-us-treasury-trading`, fork tip `808b683`); their generation model (patch overlays against a
state-009 compose tree) cannot be consumed by this line's last-wins layer composition, so
requirements were re-expressed and code re-based rather than applied. Verdicts: **adopted**
(carried, possibly renumbered), **adapted** (intent carried, mechanics changed for this stack),
**superseded** (explicitly reversed), **dropped** (not carried, reason given).

### 016-cdm-generic-instruments

| Source id | Verdict | Where / why |
|---|---|---|
| FR-01601 | adopted | FR-CDM01 (path param renamed `instrumentKey` per 017) |
| FR-01602 | **superseded** | FR-CDM09 — `/stocks` retained; removal reversed with reason |
| FR-01603 | adopted | FR-CDM02 (`displayName` on the CDM view; `companyName` stays on legacy surfaces, TD-CDM01) |
| FR-01604 | adopted | FR-CDM03, extended with `Debt` |
| FR-01605 | adopted | FR-CDM05 |
| FR-01606 | adapted | FR-CDM05 — equity/fund only; Treasuries carry `Other`+`FIGI`, never `BBGTICKER` |
| FR-01607 | adopted | FR-CDM05 |
| FR-01608 | adopted | FR-CDM04, extended to `debtEconomics` |
| FR-01609 | adopted | FR-CDM07 |
| FR-01610 | adopted | FR-CDM08 |
| FR-01611 | adopted | FR-CDM06 |
| FR-01612 | adopted | FR-CDM05 |
| FR-01613 | adopted | folded into the seed classification map (data-model.md) |
| FR-01614 | adapted | trade-service validation resolves `/instruments/{key}` — merged with 017's richer validation into our overridden controller |
| FR-01615 | adopted | `instrumentKey` stays the transactional key; no surrogate ids (data-model.md) |
| FR-01616 | adopted | universe alignment: seed row + deployment env lists + price snapshot entry |
| FR-01617 | adopted | inherited flows compatible except where declared |
| NFR-01601/-02 | adopted | NFR-CDM05 |
| NFR-01603 | adopted | FR-CDM04 |
| NFR-01604 | adopted | NFR-CDM07 |
| NFR-01605 | adopted | NFR-CDM06 |
| NFR-01606/-07 | adopted | this pack's `runtime-topology.md` / `architecture.model.json` |
| NFR-01608/-09 | dropped | compose-line publish workflow and API-explorer assets; this line publishes via the state catalog's `code/generated-state-*` branches |
| NFR-01611 | adopted | NFR-CDM07 |
| SC-01601/-02 | adopted | pipeline hook + `test-state-YU16-cdm-instruments.sh` registration |
| SC-01603–06 | adopted | SC-CDM01/02; supplemental FIGIs carried in the loader |
| SC-01607 | **not adopted** | inverted to SC-CDM03 — `GET /stocks` returns 200 |
| SC-01608 | adopted | universe-alignment assertion in the state test |
| SC-01609 | adopted | SC-CDM06 |
| SC-01610 | adopted trivially | ancestor lifecycle scripts still probe `/stocks` — which still serves |
| SC-01611 | adopted | catalog `publish` entry |
| TD-01402/-01601 | dropped | fork-specific patchset mechanics on the C3/compose lines |
| TD-01602/-01603 | adopted | folded into TD-CDM01 |

### 017-us-treasury-trading

| Source id | Verdict | Where / why |
|---|---|---|
| FR-01701 | adopted | `instrumentKey` = transactional key (data-model.md) |
| FR-01702 | adapted | `displayName` on `/instruments` only; `companyName` kept on `/stocks` + snapshots (TD-CDM01) |
| FR-01703 | adopted | FR-CDM07 |
| FR-01704 | adopted | literal config names, general values |
| FR-01705 | adopted | FR-CDM18/20 — with emission converted to fraction of par (FR-CDM14/15) |
| FR-01706 | adopted (scope-cut) | NFR-CDM09 — reference-data + price-publisher + the ported post-trade clock beans; no matcher to clock |
| FR-01707 | adapted | FR-CDM21 — boundary-enforced; maturity cash flows likewise out of the model |
| FR-01708 | adapted | FR-CDM16 — face rules identical; value is `face × fraction` (no ÷100) per the storage convention |
| FR-01709 | adopted | FR-CDM22 |
| FR-01710 | **dropped** | long-only + derived reservations + pessimistic locking are Spring-matcher machinery; the engine's risk gates are uniform across asset classes and changing them is an engine change this state refuses |
| FR-01711 | adopted | FR-CDM23 |
| FR-01712 | **dropped** | synchronous processor routing — see "Why treasuries book asynchronously" |
| FR-01713 | dropped | `<orderId>-exec-<n>` ids belong to the matcher's fill loop; the cluster already emits deterministic epoch-qualified ids; the 50-char DB width check is kept in phase-4 schema review |
| FR-01714 | dropped | pending-trade persistence/reconciliation — same machinery |
| FR-01715 | dropped | fill sizing — the matcher simulates fills; this book crosses real orders |
| FR-01716 | adopted | seed account 17017, three users, five settled positions/trades — prices stored as fractions (`0.998780`, not `99.878`) |
| FR-01717 | adopted | FR-CDM27 |
| FR-01718 | adopted | FR-CDM23 — prefix routes, metadata confirms |
| FR-01719 | dropped | matcher lock stripes — no matcher |
| FR-01720 | partially adopted | idempotency-before-metadata and in-transaction ordering carried in the processor merge; the 256 booking stripes are not — the NATS consumer dispatches serially, and the JPA primary key stays the final duplicate guard |
| FR-01721 | adopted | FR-CDM21 |
| NFR-01701/-02 | adopted | sequential generation off YU15; ancestor layers untouched, everything in this pack's layer |
| NFR-01703 | adopted | FR-CDM23/24 fail-closed |
| NFR-01704 | adopted | ported HTTP clients keep 2 s / 5 s |
| NFR-01705 | dropped | matcher reconciliation counters — no matcher |
| NFR-01706 | adopted | NFR-CDM07 |
| NFR-01707 | adopted | clean regeneration + suites before commit |
| NFR-01708 | adopted | FR-CDM24 |
| NFR-01709 | adopted | FR-CDM24 |
| NFR-01710 | dropped | matcher histogram — no matcher |
| NFR-01711 | adapted | the same-volume idempotency spirit lands as NFR-CDM03: proofs on the standing rig, no fresh epoch, no PVC wipe |
| SC-01701–05 | adapted | SC-CDM09/10/11 — generation reproducible, coverage, proofs on the standing rig |

### Dropped wholesale (both packs)

- The `cdm-generic-instruments/` and `us-treasury-trading/` compose runtime stacks with their
  Grafana/Loki/Tempo/Prometheus/NATS configs — this line runs Kubernetes with its own
  observability.
- The `order-matcher` Spring-matcher diffs (`OrderMatcherService`, `OrderRepository`,
  `OrderRecord`, `api/*`, `config/HttpClientConfig`) — those classes were replaced by the LMAX
  BLP at YU01 and the Aeron cluster at YU12; the *intent* is re-expressed above.
- The Postgres DDL as written — translated into the MariaDB `database-init-configmap.yaml` at
  this state's layer, with the price columns widened to six decimals rather than copied at
  `DECIMAL(18,3)`, and the `OrderBook` `Pending*` columns omitted (they exist for the dropped
  pending-reconciliation machinery).
