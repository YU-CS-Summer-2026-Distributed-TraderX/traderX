# HANDOFF — agent-based order flow generator (kind-local)

**Status:** not started. **Rig:** kind only (`kind-traderx-yu12-cluster`) — no GCP credits.
**Depends on:** nothing. This is the first of the four and the least coupled.
**Unblocks:** the collar/price-sourcing handoff, and makes the EOD risk extract worth showing.

---

## Why this exists

The order matcher is supposed to be a live machine executing all day, with an EOD snapshot taken of
its state. Today it is neither live nor varied: the only things that drive it are throughput
benchmarks and a 120-order demo script. That produces an EOD extract with flat, symmetric,
meaningless content — every `costBasis` is `200.000000`, two accounts hold exact mirror images, and
firm-wide everything nets to zero.

Separately, prices are exogenous and random (see `HANDOFF-collar-price-sourcing.md`), so the UI's
positions and P&L move for no reason. A viewer cannot follow any causality.

**The claim this work makes possible** is *not* "our prices are real" — it cannot be, because no
real market data is involved. It is "our price **formation** is realistic": the price moves because
someone lifted the offer, depth got consumed, a large order pushed through levels. For a matching
engine that is the more relevant claim, and it is one we can make honestly.

Be careful not to overstate it in any doc or slide. Price discovery aggregates the information in
the order flow; it does not create information. Synthetic agents produce a synthetic price. What
changes is that it is now *internally coherent* — fills, marks and P&L all derive from one
consistent market.

---

## What exists today

| Thing | Where | What it does |
|---|---|---|
| Throughput load gens | `scripts/bench/load/*.mjs` | `max-load`, `fix-load`, `batch-load`, `order-matcher-bench`, `bin-multi` — all answer "how fast", none produce a day |
| Two-account bench | `scripts/bench/load/yu13-two-account-bench.sh` | Crosses two accounts; the shape the current extract inherits |
| Demo traffic | `scripts/yu15/demo-otel-traffic.sh` | 120 orders at ~2/s across 7 accounts, for tracing demos |
| TWAP slicer | `execution-algo-engine` (YU08) | **Reuse this.** Real parent→child slicing on a schedule, proof `scripts/proofs/yu08-algo-slicing.sh` |
| Fixtures | `scripts/yu15/seed-proof-fixtures.sh` | Accounts 22214/52355/42422/62654/11413/10031/44044 + positions |
| Option chain | `scripts/proofs/seed-option-chain.sh` | Listed options on the same book |

Gateway is `svc/order-matcher` on 18110; `POST /orders` takes
`{accountId, ticker, side, quantity, limitPrice, clientOrderId}`.

---

## The work

Build a flow generator that runs a **compressed trading session** against the live book.

### Agents (suggested minimum set)

1. **Market maker** — quotes two-sided around its own inventory, widens as inventory grows, pulls
   quotes on large adverse moves. This is what creates a *spread* and makes the book look like a
   book. Without it there is nothing to trade against and everything else degenerates.
2. **Momentum taker** — buys after upticks, sells after downticks. Creates trends and impact.
3. **Mean-reversion taker** — fades moves. Provides the other side and stops the price walking off.
4. **Institutional order** — one large parent per session sliced through the **existing YU08 TWAP
   engine**. Do not reimplement slicing; scale `execution-algo-engine` to 1 and submit a parent.

Each agent should be its own account so positions land in different places. Use the seeded accounts.

### Session shape

Intensity should vary — an open hump, a quiet midday, a close hump. A flat Poisson arrival rate all
session produces a flat-looking day and defeats the point. Make the compression factor a parameter
(e.g. a 6.5-hour session in 10 minutes) so a demo can run in a coffee break and a soak can run long.

### Where it lives

`scripts/bench/load/` is throughput tooling and this is not that. Suggest `scripts/sim/` with a
single entrypoint, e.g. `bash scripts/sim/run-session.sh --minutes 10 --symbols 30`. Node (`.mjs`)
matches the existing load gens and the price-publisher; nothing here needs the JVM.

---

## Acceptance

The session is good enough when, after a run and an EOD cut:

- [ ] The extract has **asymmetric** positions — not N accounts holding exact mirrors.
- [ ] `costBasis` values **differ per account and per security** and are not the seeded constant.
- [ ] At least one account is net short and one net long in the same security.
- [ ] The book has genuine depth at multiple levels at the cut (check `traderx_book_open_orders`).
- [ ] `scripts/proofs/yu15-risk-extract.sh` **still passes** — byte-identical cut across all three
      members, and reproducible after a member kill. Realism must not cost determinism.
- [ ] The full suite still passes: `bash scripts/yu15/run-proofs.sh` → 19/19.

---

## Traps, all of them observed in this repo

- **`MAX_SECURITIES` is 1024** at the YU15 layer (it is 64 at YU12/13/14 — the YU15 layer wins).
  Pick a basket well under that. The symbol table is replicated state; a member that fills it cannot
  make progress, and a consumer that will not silently drop securities wedges (this is exactly what
  blocked the YU04 control feed until the capacity was raised).
- **`execution-algo-engine` poisons counter-exact proofs.** `yu13-readmodel-effect-end` asserts
  `next_order_ref` moves by *exactly 2*; the algo engine has been observed moving it by 24
  mid-proof. It is scaled to 0 on the rig by default. Scale it back to 0 after any sim run, or the
  suite fails on a system that is fine.
- **ADR-051**: a price tick seeds a security's mark only while no trade has printed; afterwards the
  **last trade price is the mark**. Your agents' trades therefore become the marks. This is
  desirable — but it means a runaway agent can walk a mark somewhere absurd and then collar every
  subsequent order. Bound agent aggression.
- **`PRICE_COLLAR` rejections** are the most common way a script fails for reasons unrelated to its
  subject. Observed live: a limit of 100 against a live IBM of ~187 (46% deviation) rejected
  everything. Agents must quote relative to the prevailing price, never a hardcoded constant.
- **Re-seed after long runs.** `seed-proof-fixtures.sh` re-anchors marks. `yu10-fix-session` once
  rejected 1410 of 1426 orders purely because earlier proofs had drifted IBM's mark; re-seeding took
  it to 1463/1463.
- **kind idle CPU** is the rig's known weak point — three busy-spinning Aeron members on a Docker VM.
  A sim that saturates the box makes the cluster look like it is failing when it is starving. There
  is an opt-in `CLUSTER_IDLE_SLEEP_MS` for this.

---

## Explicitly out of scope

- Real market data of any kind. Different problem, needs a vendor and a licence.
- TAQ replay. Considered and dropped — TAQ has no account identifiers, so it cannot produce
  ownership, and the corpus holds unfiltered prints.
- Anything touching the deterministic core. This is all client-side order submission.
- GKE. No credits; kind only.

## Conventions

Never `git push`. No `Co-Authored-By: Claude` trailer and no "Generated with Claude Code" in commit
or PR bodies — commit as yaakov only. This handoff file itself stays **untracked**.
