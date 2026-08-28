# ADR-073 — Replay is a sandbox, not a writer on the live venue

**Status:** accepted 2026-08-28
**Supersedes the writer half of:** [ADR-072](adr-072-replayed-prints-become-order-flow.md)
**Depends on:** [ADR-068](adr-068-external-price-sources.md) (licensing), [ADR-070](adr-070-the-tape-is-the-reference.md) (the tape as reference)

## The decision

Replayed TAQ prints stop being order flow on the live venue. Replay moves into an **isolated
sandbox** that shares no book, no risk state, no sequence space and no read model with the live
system. The live venue is quiet until a person trades.

ADR-070 is untouched: the tape remains the market's reference for bands and tick sizes. What ends is
ADR-072's second half — turning recorded prints into `ORDER_NEW` commands against the live book.

## Why

A recorded print and a live order are different kinds of thing, and the venue could not tell them
apart. The consequences were not theoretical:

- **A 2025 print was judged against today's price collar.** The gateway logged a continuous
  `ORDER-REJECT ... reason=PRICE_COLLAR` loop — `taq-C-17999`, `taq-C-18000`, `taq-C-18001` —
  consuming order refs 21248 → 21300 in three prints and filling the reject log with flow no
  operator placed. The venue was not wrong; the question was.
- **Every venue-wide assertion became uninhabitable.** A second continuous writer is why five
  counters needed operator-scoped twins, why two of those twins had to enter the snapshot format,
  and why the whole Tier-2 proof suite needed a contamination audit. That work was correct given the
  design. It exists *because* of the design.
- **An operator's own order shared a book with 2025.** The account admitted at bring-up and the
  replay accounts (900001-3) traded the same instruments in the same sequence space.

Isolation removes the cause rather than compensating for it. The operator-scoped twins stop being
load-bearing — that is a consequence to plan for, not a reason to avoid this.

## What gets built

Three surfaces, deliberately separate, because they answer different questions.

**1. Sandbox replay (a second venue).** A throwaway session with a real matching engine: real book,
real fills, real risk decisions, entirely its own state. You choose a date range and symbols, and it
replays the tape into that engine.

**2. Sandbox results (what this session actually did).** A data view over **only what the sandbox
executed**. Replay three days in March and only those three days exist here. Replay half a day, or a
single symbol, and that is the whole extent of it. This view is derived from the sandbox's own
execution record, never from the corpus — which is what makes it honest about what was run.

**3. Corpus browser (all loaded TAQ data).** A read-only view with a time slider over the full
dataset. No replay, no engine, no orders — the prints are already the answer. This is the sibling of
the existing by-day opens/closes/gaps view, which stays.

## Settled parameters

| Question | Decision |
|---|---|
| One sandbox or one per viewer | **One**, behind the existing login — simpler to deploy, cheaper to run |
| Does sandbox state persist | **Yes**, with a reset control |
| Watch the past, or act in it | **Act** — place orders into historical liquidity and see them fill |
| Does anything reach the live system | **No** — not positions, not P&L, not the extract, not kdb |
| Live venue when the tape is gone | **Quiet until someone trades.** No synthetic flow to replace it |

## The isolation must be structural

"Nothing reaches the live system" is the whole value of this, so it cannot rest on a flag someone
forgets. The sandbox gets its own engine instance and its own state; it is not the live cluster with
a mode bit. Anything that writes — read model, extract, kdb tap, position feed — is either absent
from the sandbox or pointed at sandbox-only storage.

The test for any future change here: **if the sandbox were deleted mid-session, the live venue must
not be able to tell.**

## Open, and load-bearing

- **TAQ display rights (ADR-068 open question 1) are still unsettled** — *"Does the console display
  external prices, or only consume them internally? Display rights are usually the expensive
  clause."* Surface 3 is a display surface over the full corpus, and surface 2 displays executed
  prints. Building them commits an answer to that question. Resolve the licence before either ships
  publicly; neither is blocked for internal use.
- **kdb querying of the corpus is wanted and not yet possible in-cluster.** The console's current
  `/kdb` surfaces are a *capture tap* — they read leader-side CSVs off the members by `kubectl exec`
  — not a query engine, and there is no q process in the cluster. The TAQ corpus lives in the local
  KDB-X tick store. Putting real q querying behind surface 3 means running q where the console can
  reach it, and the KDB-X licence is account-gated to one person, which is a real constraint on a
  shared cluster rather than a packaging detail.
- **What the sandbox engine is** — a second cluster, a single-member engine, or an in-process one —
  is not decided here. Single-member is the cheapest thing that satisfies every parameter above;
  three-member would only be needed if the sandbox must also demonstrate consensus.
