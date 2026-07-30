# HANDOFF-OSFF-4 — Risk-engine seat (PARKED — await Rich & Alex specs)

> One of the OSFF-NY direction handoffs (OSFF-1..4), created 2026-07-20. **This is work 4, and it is
> deliberately PARKED.** Risk is not yaakov's department; Rich & Alex own the pricing/risk engine
> (Python + JAX) and no integration specs exist yet. yaakov will relay details as they arrive. This
> doc exists so nothing is lost and so the platform stays plug-ready — **do not build risk-specific
> code until specs land (YAGNI).** Self-contained for a fresh chat.
> **Home:** `traderX-YU12-aeron-cluster` worktree, `docs/handoff/` — beside the YU12 recaps; the
> `HANDOFF-issue-yu12-*.md` docs it references are in that worktree's `issues/`. Untracked working note.

## Why it's parked, and why that's safe

The OSFF talk's point #2 is "TraderX provides the full end-to-end context for a pro-grade component
(the risk engine)." The key insight: **OSFF-1 (un-break the spine) already delivers the feed the
risk engine needs** — positions/trades flowing over NATS deltas + the CQRS read model + the YU04
snapshot-bootstrap pattern. So parking this costs the talk nothing, *as long as the position/read-model
feed is kept generic* (not create-order-specific). When specs come, this is a wiring job against a
pipe that already exists, not build-from-scratch.

## The contract to build WHEN specs arrive (not before)

Maps to the professor's three sub-questions for point #2:

1. **Feed out (their input).** Position/portfolio state to the risk engine: a snapshot bootstrap
   (YU04 pattern) + live deltas over NATS. Reuse position-service's existing feed; do not invent a
   new one.
2. **Results back + a surface (make it visible on stage).** The risk engine publishes risk metrics
   (VaR/ES/greeks — TBD by them) per account/portfolio; TraderX surfaces them — a UI panel, a
   `/risk/analytics` endpoint, and/or a Grafana row. This is what makes point #2 *demoable*.
3. **Reproducible fixture (their accuracy testing, sub-q iii).** Their accuracy work spans GPU/CPU/TPU
   and fp64…fp8 and needs the **same** portfolio scored every time. TraderX's journal already gives
   deterministic replay: expose "replay this trade set → this exact position state → score it." That
   single harness answers sub-q iii from the TraderX side. The tolerance methodology (golden
   reference = fp64-on-CPU, per-metric tolerance bands, which figures compared) is **theirs**, not
   ours — keep the boundary clear.

**Deployment/CI/CD (sub-q ii):** their engine is a container image deployed into the same GKE
cluster; each new build flows through the same GitOps pipeline (Cloud Build → Cloud Deploy,
approval-gated) that ships every TraderX service. A new image tag is the whole integration per build.
Nothing TraderX-side to build for this beyond a Deployment manifest slot when they're ready.

## What to do NOW (the only non-parked action)

- In OSFF-1, keep the position/trade feed (NATS deltas + read model) **generic and documented**, so
  it's consumable by an external service without create-order-specific coupling. That's it.

## Open questions (for yaakov to relay from Rich & Alex)

- What does the engine consume — end-of-period positions, live per-fill deltas, full portfolio
  snapshots, reference/market data too?
- What does it emit, at what cadence (on-demand request/response, or a streaming risk feed)?
- Is there a round-trip need (risk output feeding a portfolio-level pre-trade gate on top of YU03),
  or is it read-only analytics for the demo? (yaakov's steer so far: no info yet — assume read-only
  one-way until told otherwise.)
- Deployment shape: does the JAX engine need GPU/TPU nodes in the GKE cluster, or does accuracy
  testing run off-cluster with only the *fixture* pulled from TraderX?

## First steps for the chat that picks this up (once specs exist)

1. Get the answers to the open questions above from yaakov.
2. Confirm the feed contract against what OSFF-1 actually exposes; extend only if needed.
3. Add the results surface + the reproducible-fixture harness; add their Deployment manifest slot.
4. Do NOT claim any integration in talk/deck material until it actually runs end-to-end.
