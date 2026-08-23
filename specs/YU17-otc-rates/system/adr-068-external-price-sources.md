# ADR-068: Price sources are pluggable, and the synthetic one stays sufficient

## Status

**Proposed** (2026-08-23). **Not implemented.** Depends on
[ADR-067](adr-067-market-data-derived-from-the-book.md), which defines *where price comes from inside
the system*. This ADR is the narrower question underneath it: **where the external reference comes
from**, and what that costs in licensing.

Not legal advice. This records what published terms say, verbatim where it matters, so the decision
is made with the constraint visible. Anything shown publicly needs a real review before it ships.

## Context

`price-publisher` does two unrelated jobs today:

1. it **invents** prices — a random walk, `PRICE_PUBLISH_BATCH_RATIO` of the universe every
   750–1500ms;
2. it **publishes** them on `pricing.<ticker>`.

Job 2 is real infrastructure. Job 1 is a stand-in for a market.

ADR-067 demotes the external feed from "the price" to "the reference". That only means something if
the reference is worth referencing — and a random walk is not. NVDA seeded at 200 while the world
trades it near 900 is the shape of the problem: the number is *precise*, *deterministic*, and
*unrelated to anything*.

**The seam this needs already exists.** `FeedAdapterMain` consumes `pricing.>` and sequences whatever
arrives. Changing where prices come from touches **nothing** in the engine, the adapter or the collar.
That is a strong signal the change belongs in `price-publisher` and nowhere else.

## Decision (proposed)

**Split the service along the seam it already has: a `source` produces prices, `price-publisher`
publishes them.** Sources are selected **per instrument class**, not by one global switch, because no
single source covers this universe.

| class | candidate source | why |
|---|---|---|
| equities / ETFs | external vendor | real prices exist and are purchasable |
| treasuries, strips, corporates | **FRED** (H.15 / Treasury yield curves) | US-government-sourced, and the equity vendors do not carry it |
| listed options (OCC) | synthetic | no free source will price a chain |
| swaps, swaptions | computed | already derived from the curve; not quoted anywhere |

**Two rules that are the actual decision:**

1. **`synthetic` remains the default and remains sufficient.** The system must run, demo and pass its
   full proof suite with **no network, no API key and no account**. Real data is an upgrade, never a
   dependency. A conference demo on someone else's wifi is the case this rule exists for.
2. **Every source declares its redistribution posture**, and the system records the provenance of a
   price alongside its value. Right now nothing can say whether a number is real or invented, and
   that ambiguity is upstream of several defects fixed this week.

## Licensing — the binding constraint, and it is not price

Free tiers are priced for *consumption*. This project **displays** market data in a console and
demonstrates it publicly. Display and redistribution are the clauses that matter, and they are
usually the ones the pricing page does not mention.

### Pyth Network — verified, and it does not permit this

From the [Terms of Use](https://www.pyth.network/legal/terms-conditions):

> §1 — *"Association grants you a limited, nonexclusive license to display and otherwise use portions
> of the Site solely for your own **private, non-commercial** informational purposes only"*

> §7 — *"You shall not extract or copy Pyth Network price feed data… in amounts exceeding what a human
> could reasonably manually achieve, nor shall you use **automated processes**"*

An automated poll feeding a public demo is squarely outside that. Redistribution rights exist in
**Pyth Pro**, their paid institutional tier. **Do not build against the free path.**

*Caveat recorded because it may change the answer:* the on-chain feeds are a different distribution
path (public chain data), and were not assessed here.

### Massive — personal/non-commercial on the individual tier, and the governing document is STILL unread

Two documents reviewed, and **each one defers to another**:

**Website ToS** — explicitly not the answer:
> *"these Terms are in addition to any other terms of service or separately executed agreement between
> you and Massive that govern your use of the … **market data** provided by Massive (which, in the
> event of a conflict with these Terms and conditions, **shall govern**)"*

**Massive for Individuals ToS** (2025-07-18) — narrows the grant sharply:
> §2 — *"we grant you a … limited right to access and use the Services … solely for your own
> **personal, non-commercial, and non-business** purposes."*

> Preamble — *"If you are using the Services for business or commercial purposes, you may not use any
> of the Services labeled for individual or personal use."*

Also relevant: §6.1(f)(ix) bars use *"for any commercial or unauthorized purpose"* without written
consent, and §6.1(d)(iii) bars *"making unauthorized copies of any content made available on or
through the Services"*.

**And it defers again.** §1 makes Market Data subject to a separate **Market Data Terms of Service**
"incorporated herein by reference" — and **§15.2's order of precedence puts those Additional Terms
ABOVE these Terms**. So the document that actually governs market-data usage has still not been read,
and it outranks both documents that have been.

**Assessment:** a university teaching project is plausibly non-commercial, but a conference
presentation and a public repository are not obviously *personal* or *non-business*. That is the
clause to resolve before anything external is displayed publicly — not the rate limit.

### FRED — usable, with three concrete obligations

From the [FRED® API Terms of Use](https://fred.stlouisfed.org/docs/api/terms_of_use.html):

1. **A mandatory attribution string, placed prominently:**
   > *"This product uses the FRED® API but is not endorsed or certified by the Federal Reserve Bank of
   > St. Louis."*
   This is a UI requirement, not a footnote in a README.
2. **Per-series copyright is the caller's problem, not FRED's:**
   > *"Data series available through the FRED® API may be owned by third parties and subject to
   > copyright restrictions… **Before using data series owned by third parties for anything other than
   > your own personal use, you must contact the data owner to obtain permission.**"*
   Copyrighted series carry the word `Copyright` in their notes and are discoverable by searching for
   it. **Treasury yield curves are US-government-sourced and are the reason FRED is on this list** —
   but the check is per series, every time, not once.
3. **If others use the application, they must be told they are bound too** — display the link to the
   Terms of Use and say so. That applies directly to a public demo.

Also prohibited and easy to trip: `FRED` in a hostname, their marks or logo, implying endorsement,
and stripping proprietary notices.

### OpenFIGI

Free instrument identifiers — reference data, not prices. Worth having for the reference-data service
independently of this decision.

## Interim position (yaakov, 2026-08-23): use now, revert before anything public

**Decision: integrate against these services during development, and disable them before any public
demo or recorded talk.** That is a sound posture *only because rule 1 makes the revert real* — with
`synthetic` as a sufficient default, turning an external source off is a config change, not a
rewrite, and the system keeps working.

**The rule that makes "revert later" actually possible — and the way it fails:**

> **External data must never become durable.** It may live in memory and on the wire. It may not be
> committed to the repository, baked into fixtures or seed files, cached to disk in a tracked path, or
> embedded in recorded or published material.

Deleting an integration later removes *future* calls. It does not un-publish a recorded talk, and it
does not remove a price that got committed into a fixture six weeks earlier. **The reversible part is
the code; the irreversible part is anything that escaped.** If a vendor price ever needs to be
persisted, it must be replaced by a synthetic value first — the same shape as the fixture seeder
reading live prices at run time rather than hardcoding them.

Two consequences worth stating so they are not discovered later:

- **The demo must be rehearsed in synthetic mode**, not merely capable of it. A path that is only
  exercised at the moment it is needed is untested; this project has paid for that repeatedly.
- **Provenance is what makes the switch auditable.** Rule 2 already requires a price to carry whether
  it is real or invented. Without it, nobody can answer "was that number from a vendor?" after the
  fact — which is exactly the question that would matter.

## What this is explicitly NOT

- **Not a rewrite of `price-publisher`.** The publishing half is fine and stays.
- **Not a dependency on any vendor.** See rule 1. If a source is unavailable, unaffordable or its
  terms change, the system loses realism and nothing else.
- **Not a route for external data to reach the engine directly.** It reaches it the way everything
  does — published on `pricing.*`, sequenced by the adapter, one path
  ([ADR-045](../../YU12-aeron-cluster/system/adr-045-feed-adapter.md)). Two paths into the
  deterministic core is divergence.
- **Not exchange-calculated indices.** An index derived from *our own book* is
  [ADR-067](adr-067-market-data-derived-from-the-book.md)'s lineage — same rule-based aggregate, same
  determinism, computed on apply. It is a good follow-on and it is not this decision.

## Consequences

**Good**

- The reference tier becomes worth referencing, which is what ADR-067 assumes and cannot supply.
- Rates instruments get a real curve, which nothing else on offer provides.
- Provenance becomes explicit: the system can state whether a price is real or invented.
- The polling limit is a non-issue — measured this week, 15s of staleness moves the collar's reference
  by at most ~15% of its half-width, so a 10–12s free-tier poll is **already inside** a tolerance we
  proved rather than assumed.

**Costly / risky**

- **A licensing mistake is worse than a bug.** A defect gets fixed; a redistribution breach on a
  recorded conference talk does not get un-published.
- Per-class sourcing means several integrations and several failure modes, not one.
- Real prices move when nobody is looking. Proofs that assume a stable reference will need the same
  treatment the fixture seeder just got — and that class of break is already documented.

## Open questions

1. **Does the console display external prices, or only consume them internally?** Display rights are
   usually the expensive clause. Internal-only use is materially easier to license.
2. **Delayed or real-time?** Delayed is nearly always more permissive and is entirely sufficient here.
   Assume delayed unless something needs otherwise.
3. **What happens when a source is unreachable mid-run?** Rule 1 says degrade to synthetic — but a
   book anchored on a real price and then handed a synthetic one has a reference discontinuity, which
   is exactly what ADR-066 re-anchors on. Decide the transition, not just the fallback.
4. **Is the vendor's identifier space ours?** Tickers are not unique across venues and asset classes;
   this is where OpenFIGI stops being optional.

## Related

- [ADR-067](adr-067-market-data-derived-from-the-book.md) — market data derived from the book. This
  ADR supplies the reference tier that one defines.
- [ADR-066](adr-066-price-band-follows-the-market.md) — the band follows the reference.
- [ADR-045](../../YU12-aeron-cluster/system/adr-045-feed-adapter.md) — one sequenced path in.
- `issues/open/the-cluster-rig-sequences-no-live-ticks.md`
