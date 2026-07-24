# 07 — Risk-component integration with Alex

> *"Start planning how and where Alex's risk component is going to be integrated. First pass: Alex's
> component runs in overnight batch mode and generates numbers that set risk limits for intraday
> trading; those limits are loaded into some low-latency component, which the relevant parts of the
> end-to-end system cache locally in memory. Second pass: some event triggers a calculation intraday,
> and the new numbers are distributed to the relevant components."*
> **Prioritise this — it blocks another person.** Lane: design session, then a small integration.
> See [[00-INDEX]].

## The good news: both halves already exist

This reads like a new subsystem. It isn't — the architecture already anticipated it, which is a strong
thing to say in the talk.

**Outbound (us → Alex) is DONE and proven on GKE.** YU15 produces a **sequence-addressed** EOD risk
extract: a marker is injected into the replicated log so the cut is defined by a consensus position, not
a wall clock; it renders **byte-identically on all three members and again on replay**; and it's
delivered **write-once** to `gs://` (the service account is objectCreator+objectViewer only — an
overwrite attempt returns 403). Options rows carry multiplier-aware valuation. That's exactly the
"overnight batch input" the professor describes, with reproducibility guarantees most systems don't have.

**Inbound (Alex → us) is the "low-latency component" — and it already exists too.** YU03 gives a
two-tier in-memory risk gateway with a control plane (`/risk/control/{policy,restriction,security}`,
token-gated). YU04 makes those feeds **durable**: a live JetStream delta stream, a transactional outbox,
and watermarked-snapshot bootstrap so a restarted replica catches up correctly. Limits live in memory in
`BlpRiskState` and are checked per order with no DB round-trip.

**So the first pass is mostly wiring, not building:** Alex's batch output → the existing risk control
plane → in-memory limits. And the professor's second pass (event-triggered intraday recalculation) is
**the same control feed with a different trigger** — the durable delta stream was designed for exactly
this. That's the headline for this workstream.

## The job

**Step 1 — a design session with Alex (do this first, it's the only cross-person dependency).** Settle:
- **The contract**: what numbers his engine emits (per account? per security? per book?), their units,
  and their semantics (hard limit vs. advisory vs. multiplier).
- **The mapping**: which of his outputs map onto which existing risk controls (position quantity,
  concentration notional, credit limit, restrictions), and what — if anything — needs a *new* control type.
- **Cadence and authority**: overnight only at first; who owns the number; what happens if a limit
  arrives that would reject currently-resting orders.
- **Failure policy**: if his numbers are late, malformed, or absent, does the system hold the previous
  limits, fall back to defaults, or fail closed? **Decide this explicitly** — it's the question a
  reviewer will ask, and "fail safe" is a strong answer.

**Step 2 — first pass (overnight batch).** Wire his output into the control plane. Sequenced control
events so every member applies the same limits at the same position; durable so a restart doesn't lose
them; in-memory so the per-order check stays sub-microsecond.

**Step 3 — second pass (intraday, if time).** An event triggers a recalculation and the new numbers
distribute over the same feed. Because the feed is already durable and sequenced, this should be a
trigger + a publish, not new plumbing.

## Traps

- **Limits are replicated state — this is NOT an off-consensus tap.** Unlike the trade/order bridges,
  risk limits must be applied as **sequenced control events** so every member agrees. Applying them
  out-of-band would diverge the cluster. This is the one integration in the project that touches the
  deterministic path, so it follows the `prove-cluster-engine-change` discipline.
- **`BlpRiskState` is re-declared at the YU14 layer** (multiplier-aware options gate). A YU03-layer edit
  is a **dead layer** on YU14/YU15 — hand-merge at the highest carrier and verify two ways.
- `risk.entitlement.enforced` stays **false** (standing project decision).
- Watch the silent-drop class: a limit that fails to apply must be **loudly signalled**, never dropped.

## Deliverable

A written integration design agreed with Alex (contract, mapping, cadence, failure policy), then the
first-pass wiring with a falsifiable proof: publish a limit → assert it's in memory on all three members
→ assert an order that violates it is rejected with the right reason → assert it survives a restart.

## Conventions

Commit per capability; propagate verifying two ways; `git push` goes to yaakov.
