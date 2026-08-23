# ADR-067: Market data is derived from the book; the feed is a reference, not the price

## Status

**Partially implemented** (2026-08-23). Questions 2 and 3 below are **decided and shipped**
(`01318795`): the book now exports its own BBO and mark on each member's health server at `/bbo`,
beside consensus. Questions 1 and 4 — the ones that would change what the collar references and
what drives the demo — remain **open and unbuilt**. Nothing consumes `/bbo` yet. Drafted from a design question
raised while reviewing [ADR-066](adr-066-price-band-follows-the-market.md) and the feed adapter:
*if a real venue derives its price from resting and trading orders, why does this system inject an
artificial random walk instead?*

This ADR exists to be argued with on paper. It touches the collar, the console, the feed adapter and
the fixture seeder, and it is far cheaper to disagree about here than in four lanes.

## Context

### Three things currently claim to be "the price", and they disagree

| Source | Mechanism | Who consumes it |
|---|---|---|
| **The random walk** | `price-publisher` mutates a synthetic price and publishes `pricing.<ticker>` | The console, the algo engine, anything reading `/prices` |
| **The seeded tick** | `POST /seed` sequences a `PRICE_TICK`, which sets `BlpRiskState.lastPrice[]` | The risk gate, and — since ADR-066 — the collar band |
| **The last trade** | `lastPxBySecurity`, set on every print ([ADR-051](../../YU06-eod-price-production/system/adr-051-last-trade-is-the-mark.md)) | Marks, EOD pricing |

Nothing reconciles them. They are free to drift apart and routinely do.

### The disagreement is not theoretical — it is where this week's defects came from

- **NVDA is seeded at 200 by the fixtures and published at ~916 by the feed.** Both are "the price".
  After ADR-066 the collar follows the *seeded* one, so NVDA correctly refuses its own published
  price. The rule is working; the inputs contradict each other.
- **A whole break class — "seed price ≠ trade price"** — was found and repaired this week
  (`yu03-risk-proof` seeded BAC at 200 and traded it at 40). It had survived because the band ignored
  the seed entirely. The moment one price source started mattering, the others' disagreement surfaced.
- **`FeedAdapterMain` — described by [ADR-045](../../YU12-aeron-cluster/system/adr-045-feed-adapter.md)
  as "the ONLY market-data path into the deterministic core" — has never carried a single tick.** It
  reads `price` at the top level of a message the publisher wraps in `{topic, payload:{…}}`, and its
  own `catch (Exception ignore)` swallows the failure. Measured: 2,862 messages in, zero sequenced.

That last point is the strongest argument for deciding this **now**: the current market-data
architecture has never actually run. Nothing depends on its behaviour, because it has had none.

### What a venue actually does, and what is already right here

A venue does not receive its price. It **produces** it:

- the **last trade price** is the print — endogenous;
- the **best bid and offer** come from resting orders — endogenous;
- an **external reference** exists too, but for named jobs: opening auctions, price collars, halts,
  cross-venue sanity. It is not "the price"; it is the answer to *"has our book detached from the
  world?"*

Two thirds of that is already built and one third is unused:

- ADR-051 already makes the last trade the mark. **Correct as-is.**
- `LimitBook` already tracks `bestBidSlot()` / `bestAskSlot()`, and slot → price is
  `baseLevel + slot`. **A true BBO is arithmetic away, and is exported nowhere.**

## Decision (proposed)

**One hierarchy, three tiers, each with a defined fallback:**

1. **Last trade** — the mark, when the book has printed. Unchanged from ADR-051.
2. **Best bid / offer, derived from resting orders** — when the book is open but has not printed, or
   when a live two-sided quote is a better reference than a stale print.
3. **External reference** — when the book is neither trading nor quoted. Bootstraps an unopened
   security and answers the drift question.

**Publish market data derived from the book.** BBO, last trade and depth, from the engine that owns
them, on the existing per-member metrics/egress surfaces. This is new capability, not a rename: the
console today draws a synthetic price it was handed, not the market it is supposedly showing.

**Demote the external feed to a reference, in name and in role.** `price-publisher` keeps producing
an exogenous series — a demo needs *some* external information or it has none — but it stops being
called "the price", and consumers that want *the market* read the book instead.

## What this is explicitly NOT

**It is not "delete the random walk and derive everything from the book."** That is the intuitive
version and it is wrong, for three reasons that should be recorded so they are not re-derived:

- **An empty book has no price.** A security that has never traded and has no resting orders cannot
  produce one. Bootstrap requires something exogenous.
- **A closed loop carries no information.** The demo session driver prices its orders *off* the
  current price. Price → orders → price is self-referential: it would still random-walk, just driven
  by the order generator's noise rather than the publisher's. Arguably less realistic, not more.
- **A collar cannot police drift against itself.** The whole point of a price collar is catching an
  order that is wrong relative to *where the market actually is*. If the only notion of "the market"
  is this book, a book that has detached from the world looks perfectly healthy. This is precisely
  why venues consume external reference data as well as producing their own.

## Consequences

**Good**

- The three-way contradiction collapses by construction. "NVDA is 200 and also 916" stops being
  expressible.
- ADR-066 gets *more* correct, not obsolete: the band would follow the book's own price while the
  book is active and fall back to the reference when it is not — the shape it already has, with a
  better-defined reference.
- The console can show a real BBO and real depth. Today it shows a number generated for it.
- The fixture seeder's hardcoded crossings stop being a second, competing anchor.

**Costly / risky**

- **Touches four areas at once** — engine (BBO egress), console (consume it), feed adapter (role and
  the parse bug), seeder (stop asserting a price). That breadth is the main argument for staging it.
- **A tier boundary is a behaviour change in the deterministic core** if the collar's reference
  changes again: no gradual roll, fresh epoch. ADR-066 has just been proven; re-opening it needs the
  same proof burden.
- **Demo realism may drop before it rises.** A thinly-traded demo book produces a jumpy, wide,
  occasionally empty BBO. The random walk looks smoother precisely because it is fictional.

## Open questions — decide before building

1. **Does the collar's reference change, or only its inputs' clarity?** The cheapest version of this
   ADR changes *what publishes* and *what things are called*, and leaves ADR-066's reference exactly
   where it is. The most expensive version re-points the collar at a book-derived price. These are
   very different amounts of work and risk.
2. **What is the BBO when one side is empty?** — **DECIDED: report each side independently, omit the
   absent one, never synthesize a midpoint.** One-sided books are normal and a reference derived from a
   single side is not a midpoint. `/bbo` omits the missing field entirely rather than emitting a zero or
   a one-sided mid, so a consumer cannot mistake absence for a price. Verified on the rig: a bid-only
   book answers `{"ticker":...,"bid":99.5,"mark":100.0}` with no `ask` key at all.
3. **Does derived market data go through consensus, or beside it?** — **DECIDED: beside it.** It is a
   *product* of applying the log, so it is emitted on egress without being sequenced. Sequencing it would
   grow the log for data every member can already compute — the same trap [ADR-045](../../YU12-aeron-cluster/system/adr-045-feed-adapter.md)'s
   conflation exists to bound.
   Each member computes it independently and they agree: at one applied position all three answered
   byte-identically across 69 books. The response carries `applied` so a consumer can tell whether two
   scrapes are comparable. The read is unsynchronized and off-thread, the same posture as
   `recoveryDigest` — it is a sample, not a sequenced fact.
4. **Does `price-publisher` keep driving the demo?** If the book becomes the price, something must
   still generate order flow, and today the session driver takes its cue from the publisher.

## Related

- [ADR-045](../../YU12-aeron-cluster/system/adr-045-feed-adapter.md) — the feed adapter as the only
  market-data path into the core. Its premise is sound; its implementation has never worked.
- [ADR-051](../../YU06-eod-price-production/system/adr-051-last-trade-is-the-mark.md) — last trade is
  the mark. This ADR extends rather than supersedes it.
- [ADR-066](adr-066-price-band-follows-the-market.md) — the band follows the reference. This ADR is
  about making that reference mean something.
- `issues/open/the-feed-adapter-parses-the-wrong-level-of-the-pricing-envelope.md`
- `issues/open/a-live-feed-refuses-the-fixture-seeders-nvda-crossing.md`
