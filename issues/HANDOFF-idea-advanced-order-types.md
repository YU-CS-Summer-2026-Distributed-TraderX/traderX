# Handoff: Advanced Order Types + Time-in-Force State Machines in the BLP

> One of 8 idea-handoffs produced from the professor's slide deck
> (`Combined_Financial_Systems_Deck` — deck 01 slides 12–17, deck 03 slide 9). Each idea is a
> capability the deck describes that TraderX does not have. Self-contained for a fresh chat.

## What this chat accomplished

- Compared the deck's order-type taxonomy against the TraderX matching engine. Deck 01 slide 12
  lists Market, Limit, Stop, Stop-Limit, Iceberg, Pegged, Trailing Stop; slide 13 lists
  time-in-force (Day, GTC, IOC, FOK); slide 14's key line: **"each combination of order type and
  time-in-force creates a distinct state machine that the OMS must implement correctly — a bug in
  any state transition can result in orders being lost, double-executed, or remaining active when
  they should have been cancelled."**
- TraderX's `MatchingEngine` supports essentially limit-style orders with price-time priority
  plus cancels/force-fills. No stop/trigger orders, no iceberg, no time-in-force semantics —
  orders rest indefinitely. (Verify exact current set in `MatchingEngine.java` before writing the
  spec; the risk-gateway work may have touched it.)

## Branch / repo state

- Repo: `/Users/yaakov/Desktop/Summer 26/lmax/traderX`, on `YU04-durable-control-feeds`
  (HEAD `5701f38`). Production: `YU02-lmax-kubernetes-blp-ha`. No code changes this session.

## Goal for next chat

Design and scaffold a new YUxx state extending the BLP matching engine with, in priority order:

1. **Time-in-force**: IOC and FOK (pure matching-time logic, no monitoring needed), then Day/GTC
   (needs a session-clock/market-close event into the BLP).
2. **Stop and Stop-Limit orders**: trigger on price ticks — the BLP *already receives a binary
   price-tick feed* (added in YU03: price-publisher → order-matcher), so the monitoring input
   exists; add a trigger book scanned on each tick.
3. **Iceberg orders**: displayed quantity + hidden reserve, replenished on fill.
4. (Stretch) Trailing stop — same trigger book plus high/low-water tracking per order.

All inside the BLP's single-threaded, allocation-free discipline, journaled and snapshot-safe so
recovery replays produce identical trigger behavior.

## Key files

| Path | Why it matters |
|---|---|
| `specs/YU02.../runtime-overrides/order-matcher/` | BLP Java source overrides (MatchingEngine, LmaxEngine, Journaler) |
| `LMAX-BLP.md`, `LMAX-NO-GC-JAVA.md` | Hot-path constraints any new order type must obey |
| `LMAX-INPUT-DISRUPTOR.md` / `LMAX-OUTPUT-DISRUPTOR.md` | Event flow the new order events ride on |
| `scripts/bench/` + `bench-results/` | Must re-run bench-compare — this touches the hottest code |
| `specs/YU03-in-memory-risk-gateway/` | Spec-pack shape; also where the binary tick path landed |

## Architecture / context the next chat needs

- This is the **only idea of the 8 that modifies the BLP hot path itself**. Constraints that are
  non-negotiable in this codebase: single writer, no allocation on the hot path (there are
  allocation-gate tests — extend them), deterministic replay (a stop trigger must fire
  identically during journal replay — meaning triggers must be driven by *journaled* input
  events, i.e. the price ticks must be journaled or the trigger evaluation must be
  reconstructible), and snapshot v3 compatibility (the trigger book and iceberg reserves must be
  in the snapshot).
- Deck 01 slide 17 gives the complexity targets: cancel O(1) via order-id hash map, add
  O(log P) price levels — a trigger book is typically two heaps/sorted structures (buy-stops
  ascending, sell-stops descending) scanned against the last price per tick.
- IOC/FOK first is deliberate: zero new state, pure matching logic, easy wins that immediately
  make the order API look real. Stop orders are the headline feature (they exercise the tick
  feed). Pegged orders need an NBBO, which TraderX doesn't have (see the multi-venue/SOR idea) —
  leave pegged out.
- The state machines belong in the spec: enumerate (type × TIF) combinations and their
  transitions explicitly in data-model.md; the deck frames this as the core correctness risk.
- YU-state conventions: spec pack under `specs/YUxx-<name>/`, same-named branch, parent lineage,
  **commit but never push**; bench-compare after changes (throughput regression here is a
  release blocker); generation-pipeline dead-override gotcha applies doubly to order-matcher
  sources (`HANDOFF-durable-control-feeds.md`).

## Decisions already made (don't re-litigate)

- Implement inside the BLP, not a side service — trigger evaluation is matching-engine business
  and anything else breaks the single-source-of-truth event ordering.
- Order of delivery: IOC/FOK → Stop/Stop-Limit → Iceberg → (maybe) Trailing. Pegged is out of
  scope (NBBO dependency).
- Deterministic replay is a hard requirement, not a nice-to-have — it's the LMAX architecture's
  whole premise.

## Open questions / known issues

- Are price ticks currently journaled by the order-matcher, or consumed transiently? This
  determines the replay design for stop triggers — check `Journaler`/`LmaxEngine` first.
- Day-order expiry needs a market-close event source — synthetic clock event via the input
  disruptor is simplest, but who emits it?
- API surface: trade-service request schema + web front end order ticket need new fields
  (orderType, tif, displayQty, stopPrice) — coordinate the JSON schema early.
- GTC + snapshots: terminal-order retention is already bounded (Tani's work) — confirm resting
  GTC orders aren't swept by that retention logic.

## Suggested first steps for next chat

1. Read this doc, then `MatchingEngine.java` and `LmaxEngine.java` under the YU02 runtime
   overrides — establish exactly what order semantics exist today.
2. Confirm state id/name with the user (e.g. `YUxx-advanced-order-types`).
3. Write data-model.md's state-machine tables first (type × TIF) — they drive everything else.
4. Implement IOC/FOK with tests + allocation gates, bench-compare, then design the trigger book.
