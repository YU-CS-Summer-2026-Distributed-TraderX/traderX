# HANDOFF — continuous portfolio risk engine, egress-side (kind-local)

**Status:** not started. **Rig:** kind only (`kind-traderx-yu12-cluster`) — no GCP credits.
**Depends on:** `HANDOFF-egress-consensus-sequence.md` for the central claim. The engine can be
built and run without it; it just cannot be *checked against the extract* without it.
**Related:** `HANDOFF-agent-flow-generator.md` gives it data worth computing on.

---

## The shape, and why it is not in the core

Pre-trade **limits** risk already runs continuously and inside the deterministic core: `BlpRiskState`
(YU03) checks every order against position, notional, restriction and kill-switch before it books,
inside a ~0.47µs apply. That part is done and is not what this handoff is about.

**Portfolio risk — exposure, VaR, greeks — must not go in the core.** Three hard reasons:

1. It would make risk math part of consensus. Every member must produce bit-identical results;
   floating-point revaluation across JVMs is a determinism hazard. This project has already paid for
   one permanently diverged cluster.
2. It needs external inputs — vol surfaces, curves, correlations — which cannot enter the replicated
   log without becoming sequenced state that every member must agree on.
3. The latency budget forbids it. Apply is sub-microsecond; a portfolio revaluation is milliseconds.

So it belongs on the **egress side**, as a projection — architecturally identical to the trade
bridge, the order read model and the kdb tap. Latency-decoupled, free to use floats, and able to
crash and rebuild without touching the cluster.

---

## What it consumes

| Input | Source | Notes |
|---|---|---|
| Fills | NATS `/trades`, from `TradeNatsPublisher` | at-least-once, leader-only, `id = tradeSeq + side` |
| Marks | NATS `pricing.*` (JSON) / `pricing-tick-bin.<TICKER>` (binary) | today a random walk — see the collar handoff |
| Positions | derived from fills | do not read SQL; that is a different projection with its own lag |
| Sequence | the egress envelope | **not there yet** — see the prerequisite handoff |

Reference data (counterparty, netting set) comes from the same place the extract gets it. Note the
extract **fails closed** if an account has no counterparty mapping; decide whether this engine should
do the same or degrade.

---

## Recovery: replay, don't checkpoint-and-pray

If the risk engine dies, it rebuilds by **replaying the Aeron Archive into its own state**, never
by asking the cluster to re-send. This pattern already exists in this repo — YU05's recon and
regulatory export replay the Archive into a shadow engine, with no snapshot bump and no impact on
the running cluster. Follow it rather than inventing a checkpoint scheme.

That gives the engine a useful property: it is fully rebuildable from the log, so its state is
never authoritative and never needs backing up.

---

## The claim worth building toward

Both the continuous engine and the EOD extract derive from the same totally-ordered log. So:

```
continuous engine state at sequence N   ==   risk extract cut at sequence N
```

should hold **exactly**, and should be a test. That is the sentence to aim for:

> *Our intraday risk and our end-of-day risk are computed from the same log and cannot disagree —
> and here is the test that fails if they ever do.*

This is why the sequence-on-egress prerequisite matters. Without it the engine cannot name the
instant its state corresponds to, and the comparison is not expressible.

Suggested proof, in the house style of `scripts/proofs/`: run flow, take an extract at N, ask the
engine for its state at N, assert row-for-row equality on `(accountId, security) -> quantity` and on
cost basis. Make it falsifiable — include an arm where a deliberately dropped fill makes it fail,
so "they matched" cannot mean "both were empty".

---

## Traps

- **Epoch conflation.** Trade numbering restarts on a wiped epoch, and delivery is at-least-once, so
  a failover can replay recent trades. Dedup by id alone will silently merge two epochs' trade 5.
  Use epoch-qualified ids — the order read model (YU13, brief 07) already does this; copy it.
- **Leader-only publishing** means the stream pauses across a failover and resumes from the new
  leader. The engine must tolerate a gap-then-catch-up without concluding positions changed.
- **At-least-once means duplicates are normal, not an incident.** Make idempotency the default path,
  not an error branch.
- **Do not read positions from MariaDB** as a shortcut. That projection has its own lag and its own
  failure modes, and it has silently dropped rows before (the `trades.accountid` foreign key and the
  `VARCHAR(15)` OCC truncation both did exactly that). Derive from the stream.
- **Marks are currently random.** Any VaR computed today is a number about a random walk. Build the
  machinery, but do not put a VaR figure on a slide until the collar/price work lands.
- **Bound every queue and every consumer.** This repo's recurring bug class is a bounded queue with
  an unbounded consumer behind it; ask what the side channel costs when the bad state lasts forever,
  including on the failure path.

---

## Acceptance

- [ ] The engine maintains live positions per `(accountId, security)` from `/trades` alone.
- [ ] It survives a kill and rebuilds by Archive replay, reaching the same state.
- [ ] It survives a cluster failover without losing or double-counting a fill.
- [ ] It can report its state **as of a given consensus sequence**.
- [ ] A proof asserts engine-at-N == extract-at-N, with a falsification arm.
- [ ] `bash scripts/yu15/run-proofs.sh` → still 19/19 (20/20 once the new proof is added).

## Explicitly out of scope

- Putting anything in the deterministic core.
- Choosing a risk model. Exposure and P&L first; VaR/greeks are a later conversation and the
  teammate's ORE work is a separate project, not a dependency.
- GKE. No credits; kind only.

## Conventions

Never `git push`. No `Co-Authored-By: Claude` trailer, no "Generated with Claude Code" — commit as
yaakov only. This handoff file stays **untracked**.
