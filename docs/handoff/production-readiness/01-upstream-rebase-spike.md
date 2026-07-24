# 01 — Upstream TraderX rebase: SPIKE FIRST

> FINOS TraderX has changed since we forked it. The professor wants us to rebase our states onto the
> updated baseline **and document the experience well — it is an important part of the November talk.**
> **This is a SPIKE first, not a migration.** It gates brief 03, and it is the worst schedule risk on
> the board. Lane: investigation → then a decision. See [[00-INDEX]].

## Why this is first

Brief 03 unit-tests the *plain vanilla* TraderX we forked. This brief **rebases that exact code**.
Doing 03 first means rewriting those tests afterwards. So we need the *size* of the rebase before
committing anyone to baseline tests. **Timebox the spike to 1–2 days.** The output is a decision, not a
migration.

## Why it could be big (and why that's the interesting part)

Our lineage is a **layered spec-kit**: `specs/YUxx-*/generation/runtime-overrides` compose cumulatively
(last-wins), and every state carries spec packs for itself and all ancestors. A change in the baseline
can therefore ripple through **every one of ~14 states**. Worse, `generation/kubernetes` does **not**
overlay at all — it's a per-state `cp -R`, so manifest changes must be hand-carried to each state.

That difficulty is *itself the story the professor wants documented*: what it actually costs a real
downstream consumer to track an upstream open-source baseline. Capture the friction honestly as you go —
that narrative is presentation material whether the rebase is easy or brutal.

## The spike

1. **Identify the fork point and the upstream delta.** Which upstream TraderX commit did we fork from,
   what has changed since (services, schema, APIs, build, container images), and how much of it touches
   code we override.
2. **Classify the delta into three buckets:**
   - **Clean** — upstream changed files we never touched → takes automatically.
   - **Conflicting** — upstream changed files we override in a `runtime-overrides` layer → needs a
     hand-merge at the **highest carrying layer on every branch** (see the dead-layer trap in the INDEX).
   - **Structural** — upstream changed the shape (new/renamed/removed services, DB schema, message
     contracts) → the expensive category; size it carefully.
3. **Estimate the blast radius per state.** Which of YU02–YU15 are affected and how badly. Note that our
   own engine/cluster/gateway code is *ours* — upstream can't touch it — so the damage is concentrated
   in the inherited services (account-service, reference-data, position-service, trade-processor,
   people-service, web-front-end).
4. **Recommend a strategy.** Options to weigh: rebase everything; rebase the baseline layer only and let
   descendants inherit; cherry-pick selected upstream improvements; or defer with a documented rationale.
   **A defensible "we deliberately did not rebase, and here's the cost/benefit" is an acceptable outcome
   for the talk** — but only if it's an argued decision, not a dodge.

## Deliverable

A short written finding: the delta classified into the three buckets, per-state blast radius, a
recommended strategy with effort estimate, and a **go/no-go for brief 03** (can baseline unit tests
start now, or must they wait?). Plus running notes on the *experience* — the friction, the surprises,
what a layered downstream fork costs — because that's the talk content.

## Traps

- **Do not start migrating during the spike.** The deliverable is a sized decision.
- **Dead layers:** a clean cherry-pick into a shadowed layer applies to git and is **inert at
  generation**. Verify two ways (spec md5 **and** a re-rendered, marker-grepped tree).
- **Ancestor-layer edits need the render chain re-run from that ancestor forward**, not just from the tip.
- `generation/kubernetes` has no overlay — manifest deltas are per-state, by hand.
- Our test suites (YU13 269 / YU14 283 / YU15 300) are the regression net — **re-run them per branch
  after any merge**; that's how you'll know a rebase silently broke something.

## Conventions

Commit the findings doc; `git push` goes to yaakov. Nothing on GKE is needed for the spike.
