# Handoff: Multi-Venue Simulation + Smart Order Router + NBBO

> One of 8 idea-handoffs produced from the professor's slide deck
> (`Combined_Financial_Systems_Deck` — deck 01 slides 19–26, deck 02 slides 40–41, deck 03
> slides 12–13 & 44). Each idea is a service the deck describes that TraderX does not have.
> Self-contained for a fresh chat. **This is the most ambitious of the 8 — read "Open
> questions" before committing.**

## What this chat accomplished

- Compared the deck's market-structure material against TraderX. The deck is emphatic that
  modern markets are **fragmented** (16+ US equity venues), which creates two legally mandated
  systems TraderX cannot have as a single-venue system: **NBBO computation** (Reg NMS — best
  bid/ask across all venues, recomputed on every quote update; deck 02 slides 40–41 give the
  O(1) array-indexed algorithm) and the **Smart Order Router** (deck 03 slide 44 gives the full
  cost-function pseudocode: maker/taker fees, queue-depth impact estimate, fill-probability
  timing risk, hard Reg NMS trade-through constraint).
- TraderX today is one venue: a single BLP matching engine. There is no venue concept, no
  routing decision, no consolidated quote.
- Noted the prior decision context: "market data dissemination (L2 book)" was explicitly
  deferred from YU05 (see `HANDOFF-combined-yu05-state.md`) because nothing consumed it. **An
  SOR is precisely the consumer that justifies it** — if this idea proceeds, it likely pulls
  quote/book dissemination in with it.

## Branch / repo state

- Repo: `/Users/yaakov/Desktop/Summer 26/lmax/traderX`, on `YU04-durable-control-feeds`
  (HEAD `5701f38`). Production: `YU02-lmax-kubernetes-blp-ha`. No code changes this session.

## Goal for next chat

Evaluate feasibility first (this doc is a proposal, not a commitment), then if the user says go,
design a new YUxx state:

1. **Second venue**: run a second order-matcher instance as an independent venue
   ("TRDX2") with its own journal, fee schedule (e.g. maker-rebate vs taker-fee vs flat), and
   liquidity profile (a small market-maker bot per venue posting quotes creates the divergent
   books that make routing interesting).
2. **Top-of-book quote publication per venue**: each venue publishes best bid/ask on NATS
   (this is the minimal "dissemination" slice — L1, not full L2 depth).
3. **NBBO service**: consumes all venue quotes, maintains the deck's array-indexed best-bid/ask
   computation, publishes consolidated NBBO. (Also unblocks pegged orders from the
   advanced-order-types idea, and gives YU03's price-reasonability control a proper reference
   price.)
4. **SOR service**: sits between order ingress (trade-service / future FIX gateway) and the
   venues; implements the deck's cost function + Reg NMS trade-through check; routes (and
   optionally splits) each order; records its routing decision for best-execution evidence
   (nice tie-in to YU05 regulatory reporting).

## Key files

| Path | Why it matters |
|---|---|
| `cluster-addons/order-matcher-statefulset.yaml` | Template for the second venue's StatefulSet |
| `specs/YU02.../runtime-overrides/order-matcher/` | BLP source both venues share |
| `trade-service/` | Current ingress that would start submitting via the SOR |
| `HANDOFF-idea-advanced-order-types.md` | Pegged orders blocked on NBBO from this state |
| `HANDOFF-combined-yu05-state.md` | Records the deferred dissemination/surveillance decision |

## Architecture / context the next chat needs

- **Do not confuse this with the existing BLP HA replicas** — StatefulSet replicas are
  primary/follower copies of ONE venue. A second venue is a separate deployment with its own
  identity, journal, and book. Keep the HA machinery orthogonal.
- The deck's SOR cost function needs per-venue inputs: fee schedule (static config), visible
  depth at price (needs at least L1+size published), historical fill rate (start with a
  constant, refine later), volatility (constant per instrument v1).
- Sequencing insight from this analysis: this state *supersedes* part of the deferred "market
  data dissemination" idea (L1 slice) and *unblocks* the deferred "market surveillance" idea
  (cross-venue data is what surveillance monitors). If the team wants the deferred pair
  eventually, this is the gateway state.
- Resource cost: a second BLP wants CPU. It does NOT need the c2 `blp-pool` treatment — a demo
  venue on the default pool at lower throughput is fine; say so in the manifests to avoid
  someone "fixing" its performance.
- YU-state conventions: spec pack under `specs/YUxx-<name>/`, same-named branch, parent lineage,
  **commit but never push**; staging CI/CD only with user approval; bench-compare the primary
  venue afterward to confirm no regression from the added NATS traffic.

## Decisions already made (don't re-litigate)

- L1 (top-of-book + size) publication only in this state — full L2 depth stays deferred unless
  surveillance actually gets scheduled.
- The SOR is a normal warm-path service; the deck's "SOR on the hot path" applies to HFT firms,
  not to this system's altitude. Do not attempt in-process SOR-in-the-BLP.

## Open questions / known issues

- **Is two venues worth the operational surface?** It roughly doubles matcher infrastructure to
  demo routing. Honest alternative: a *stub* second venue (a tiny in-memory book, not a full
  BLP) gives the SOR/NBBO story at 10% of the cost — put both options in research.md and let
  the user choose.
- Fills from two venues → trade-processor/position-service currently assume one source; check
  event schemas for a venue-id field (likely missing — schema change ripples).
- Who provides liquidity on venue 2 — market-maker bot per venue (new small service) or mirrored
  synthetic flow from price-publisher?
- Cross-venue instrument universe: same reference data for both, presumably — confirm.

## Suggested first steps for next chat

1. Read this doc + `HANDOFF-combined-yu05-state.md` (deferred-pair context).
2. Put the full-BLP-vs-stub-venue tradeoff to the user before any scaffolding.
3. Confirm state id/name (e.g. `YUxx-multi-venue-sor`).
4. If go: NBBO service first (needs only quote publication from venue 1 + a stub), SOR second,
   real second venue last.

---

## Addendum 2026-07-20 — outbound routing is the missing arrow in the internalizer story

Reframing added after mapping TraderX against a published HFT-firm reference architecture. The
routing gap is the same feature described above, but the *justification* and the *available
building blocks* have both changed since this doc was written (it predates YU08 and YU10).

### The framing

TraderX is best described as a **broker-dealer operating an internalizing venue** — the LMAX
model (LMAX Exchange = MTF, LMAX Global = broker), or equivalently an ATS operator / systematic
internaliser. That resolves the apparent contradiction between the venue-side states (YU02 BLP,
YU06 official closing prices, YU11/YU12 consensus) and the broker-side states (YU05 settlement
and TCA, YU08 algos).

The flow that framing implies is: client FIX order (YU10) → 15c3-5 pre-trade risk (YU03) →
parent order sliced into children (YU08) → children hit **our own book first** (YU02) → whatever
crosses internally is ours to settle (YU05). Every state has a coherent seat.

**But the last leg is missing.** A real internalizer routes the *unfilled residual* out to a lit
venue. Today a child order either crosses against other client flow or rests on our book forever
— there is no egress. That is the single arrow this state adds, and it is what turns the system
from a pure receiver of order flow into a sender of it.

### What changed since this doc was written

| Then (YU04) | Now (YU12) | Effect on this state |
|---|---|---|
| No FIX anywhere | **YU10** ships a QuickFIX/J **acceptor** with session mgmt, durable ClOrdID ledger, fail-closed auth | Egress needs a QuickFIX/J **initiator** — same library, opposite role. Session/ledger machinery is largely reusable |
| No parent/child order concept | **YU08** execution algo engine slices parent orders into children | The SOR's natural input already exists — YU08 emits exactly what a router consumes |
| Single BLP, hand-built HA | **YU11/YU12** Aeron + Raft consensus | Routing decisions are state that must survive failover — see below |

The stub-vs-full-second-venue question above is now *less* important, because there is a third
option that did not exist before: **route out over FIX to an external simulated venue** rather
than standing up a second BLP at all. A FIX initiator pointed at a small external mock venue is
cheaper than a second BLP and tells a more honest story (real markets route over the wire, not
in-process).

### New design constraints from YU11/YU12

- **A routing decision is durable state.** If a child order is sent out to venue B and the leader
  dies, the promoted node must not re-send it. This is the same class of bug as the
  `nextOrderRef` ID-reuse issue — an outbound side effect keyed off state that must be captured
  in the snapshot. Check `traderx-snapshot-completeness-audit` before designing the egress path.
- **Egress must sit off the replicated hot path**, like the SOR itself. The consensus log records
  the *decision*; the FIX initiator performs the *side effect* on the leader only. Do not put
  socket I/O inside the ClusteredService.
- Best-execution evidence (already flagged above as a YU05 tie-in) is now more valuable: with
  YU05 TCA live, a routing decision log makes TCA cross-venue rather than single-book.

### Open questions this addendum adds

- Does the external venue mock live in-repo (a small QuickFIX/J acceptor, mirroring YU10's) or is
  it a second BLP after all? Lean mock — cheaper, and it exercises the wire.
- Does the residual route out, or does the parent order get *split* up front by the SOR? Real
  internalizers do both (ping internal, sweep external). Pick one for v1; pinging internal first
  is simpler and matches the internalizer narrative.
- Fills now arrive from two sources (internal match, external ExecutionReport). The venue-id
  schema gap noted above becomes mandatory rather than optional.
