# RI-01 — Order types and lifecycle

Updated: 2026-09-18. Status: queued. Owner: unassigned. Dependencies: agreed semantics and reference-feed choices; RI-06 recovery and RI-07 verification.

## Baseline

YU13 defines market and limit matching; the current YU17 matching engine retains those paths. Audit API/UI exposure and tests before calling anything missing. Source: [YU13 specification](../../specs/YU13-limit-order-book/spec.md). Professor deck: `01-Intro-to-Financial-Markets.pptx`, slides 13–15, supplied locally in Downloads; it is educational input, not an implementation contract.

## Queued work

- [ ] Inventory order-type × time-in-force support through API, engine, snapshots and UI.
- [ ] Specify and implement stop and stop-limit first; define trade/quote trigger, gap behavior, trigger ordering and revalidation of reserved risk.
- [ ] Specify iceberg display/reserve quantities and replenishment priority.
- [ ] Specify trailing-stop high/low watermark, trail units and deterministic trigger updates.
- [ ] Specify pegged orders, reference, offsets, caps and repricing priority. Decide genuine consolidated NBBO versus an explicitly labeled local-book reference; do not conflate them.
- [ ] Define Day, GTC, IOC and FOK eligibility per order type, trading-session clock, expiry and atomic FOK behavior. Existing market remainder cancellation alone does not prove general IOC/FOK support.
- [ ] Cover partial fills, cancel/replace, self-trade prevention, credit reservation and release, replay, snapshots, failover and UI states.
- [ ] Expand the component pack at specs/YU18-risk-integration/components/order-types; use a task worktree if needed, not a new state ID.

## Acceptance and next action

Next: produce a support matrix and stop/stop-limit state diagrams before coding. Completion requires deterministic tests for triggers and competing events, recovery preserving pending orders, no double fill, and a controlled live proof when authorized. A market order cannot guarantee a fill without eligible liquidity; correct the slide shorthand in our own specification.

2026-09-23: User selected component-based development within YU18-risk-integration; this supersedes the prior new-state plan. Lane dispatch remains pending.
