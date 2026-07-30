# HANDOFF-YU14 — Listed equity options + the instrument-model fields the risk engine needs

> Next-work handoff, created 2026-07-21. Self-contained for a fresh chat.
> **Home:** `traderX-YU13-limit-order-book` worktree, `docs/handoff/`. Untracked working note.
> **Lane ownership:** this lane owns the **instrument / reference-data model** and the risk-gate
> notional math. It must NOT touch `ClusterGatewayMain` — a parallel lane owns the gateway
> (`HANDOFF-gateway-throughput-levers.md`).

## Why this exists

Two drivers, one surface.

**1. The risk engine's methodology requires optionality.** Rich & Alex's JAX/ORE engine analyses
quantization error across **PV, Greeks, and VaR** as precision drops fp64 → fp8. On a cash-equity
book **Greeks are degenerate**: delta is exactly 1, gamma/vega/theta/rho are all zero. There is no
non-linearity for reduced precision to compound, so the central claim of their work cannot be
exercised by the portfolio we'd hand them today. Listed options give them real optionality to price.

**2. Alex's extract needs three small fields** that live on the same surface: counterparty ID,
currency, and notional. Folding them in here avoids two lanes editing reference data concurrently.

## The key architectural fact

**Listed options trade on an order book exactly like stocks do.** That is how every options exchange
works: an option contract is a security identifier with a two-sided book and price-time priority.
`LimitBook` does not care what an instrument *means* — it matches orders on a `securityId` at a
price. So the matching engine needs **essentially no change**. This is a reference-data and
notional-math state, not a matching-engine state.

## What "done" looks like

**In scope:**
- Instrument model gains: type (equity | option), underlying, strike, expiry, call/put, **contract
  multiplier** (100 for standard US listed options).
- Option contracts are registerable and tradeable as ordinary securities — they cross on the book
  with the same price-time priority, partial fills, and cancel semantics as equities.
- **Risk-gate notional math becomes multiplier-aware**: `qty × price × multiplier`. Today an option
  at $2.50 would be treated as $2.50 of notional when it actually controls $250.
- **Counterparty ID** — mapping from `accountId` → counterparty identifier (+ netting set / CSA id
  if cheap). Reference data; no engine change.
- **Currency** field on instruments, populated `USD`.
- **Notional** exposed as a derived field wherever positions are surfaced.

**Out of scope — resist:**
- Exercise, assignment, expiry processing (expiry = stop accepting orders; nothing settles).
- Greeks, pricing, implied vol, margining — **that is the risk engine's job**, and duplicating it
  here would undercut the point of the collaboration.
- American vs European semantics (a pricer concern, not a venue concern).
- Anything OTC: swaps, fixed income, SOFR. Those do not trade on a book and are a different system.
- FX / multi-currency conversion. Add the field, not the machinery.

## The one real design decision: what enters the consensus log

Minimize it. The cluster needs only what **matching and risk** require:

| Attribute | In cluster state? | Why |
|---|---|---|
| `securityId` | **yes** (already, via `SymbolRegister`) | matching |
| contract multiplier | **yes** | the risk gate's notional math is in-cluster |
| currency | probably yes (cheap, 1 field) | consistency of the risk gate |
| underlying, strike, expiry, call/put | **no** | not needed to match or to cap notional |
| counterparty / netting set | **no** | reference data; joined at extract time |

Anything that enters cluster state must also be **in the snapshot** (ADR-046: snapshot completeness
covers every future-output generator) and must be deterministic. Keeping strike/expiry out of the log
keeps the snapshot small and the hot path unchanged.

## Invariants that cannot break

Same three as YU13 — treat this as a hot-path change even though it looks like reference data:
1. **Determinism** — no clocks, no map-iteration-order dependence. Every member computes identically.
2. **Zero-alloc / zero-GC** — `noGcTest` and all four allocation gates stay green. A multiplier is an
   extra field, not an excuse to allocate.
3. **Snapshot completeness** — if the multiplier is in cluster state it must round-trip, and restored
   rows must fail closed on unknown/invalid values, as format 2 already does for off-grid prices.

## Traps carried forward (these will bite)

- **The silent-reject gate.** The engine rejects any order whose account isn't control-enabled, whose
  security isn't enabled, or whose security has no price tick — and market-trade rejects historically
  surfaced nowhere. **Every new option contract must be seeded and enabled** (reference universe +
  price tick) or it silently books nothing. Seed first, smoke-test one cross, then build.
- **Band anchoring.** The book anchors a security's price band on its **first limit order** in the
  epoch. Options trade at very different absolute prices from their underlying ($2.50 vs $150), and
  each `securityId` anchors independently — so that is fine, but a fresh epoch matters: seeding a
  contract at a bench-default price will collar its real prices later.
- **Grid.** Prices sit on a 0.001 grid; off-grid → `INVALID`. Standard option increments ($0.01 /
  $0.05) are fine, but confirm before assuming.
- **Rejects still consume orderRefs**, and per-account `executedNotional` accumulates forever
  (credit wall ~30M orders at bench sizes). Rotate accounts in long runs.

## Proof / acceptance

- Options cross correctly on the book: price-time priority, partial fills, cancel — the same suite
  YU13 has, extended to option contracts.
- Notional cap fires at the correct level for a contract with multiplier 100 (i.e. a $2.50 option
  consumes $250 of notional, not $2.50). This is the substantive behavioural test.
- Snapshot round-trip preserves multiplier and the book across a restart; fail-closed on bad values.
- `test`, `noGcTest`, all four allocation gates green.
- A short bench confirming no throughput regression (instrument count shouldn't matter, but prove it
  rather than assume — the parallel lane is chasing throughput and must not be handed a confound).

## Dependencies & sequence

- **Parallel-safe with the gateway lane** — disjoint files. Do not edit `ClusterGatewayMain`.
- **Blocks the risk-extract state** (Alex's EOD positions/marks/P&L feed): that extract should be
  built once against the final instrument model rather than retrofitted. Land this first.

## Open questions

- Is `accountId → counterparty` sufficient for Alex's netting/CSA logic, or does he need a separate
  counterparty entity with its own attributes? (Asked; answer pending.)
- Which contracts to seed — a small chain (a few strikes × 2 expiries × call/put on 2–3 underlyings)
  is plenty for a demo and keeps the reference universe manageable. Avoid seeding a full chain.
- Does the risk gate need per-instrument-type position caps, or is one notional cap enough? Start
  with one; add only if the demo needs it.

## First steps for the chat that picks this up

1. Read `specs/YU13-limit-order-book/generation/implementation-status.md` (engine + traps), then this.
2. Scaffold the state with `new-yu-state` (parent on YU13, same-named branch, commit-but-never-push).
3. Model first: decide the in-cluster vs reference-data split per the table above, and write it into
   the spec pack as an explicit decision before coding.
4. Seed a small option chain and smoke-test one option cross **before** building anything else — that
   flushes out the enablement/price-tick gate immediately rather than three days in.
