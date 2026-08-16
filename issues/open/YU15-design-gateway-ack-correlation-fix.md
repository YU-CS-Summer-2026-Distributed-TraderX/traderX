# DESIGN: fixing the gateway's ack correlation — gateway-only vs engine-echo

**Status: DESIGN ONLY, not approved, nothing implemented. 2026-08-16.** Written by the GKE arm of
§5. The defect is diagnosed in `HANDOFF-issue-gateway-wedges-after-leader-kill.md` §5 and the raw
evidence is in `YU15-s5-gke-fifo-correlation-offset.md`.

**Recommendation: ship Option A. Option B is the only unconditionally correct fix, and it should be
scheduled, not rushed.** A removes the known trigger and makes the failure self-limiting with no
wire change; B removes the *class* of failure and costs a coordinated member+gateway roll. They are
not alternatives so much as a sequence, and A does not make B harder.

---

## The fact that decides the shape of Option A

The brief asked whether the gateway can know, at or after offer time, which `inputSeq` each of its
offers received. **It cannot, and the reason is structural:**

```java
// MatchingEngineClusteredService, operative YU13 layer
event.seq      = ++appliedSeq;            // line 304 — cluster-wide, assigned ON APPLY
event.orderRef = (int) nextOrderRef++;    // line 301 — member-assigned, likewise
```

`appliedSeq` is a member-side counter incremented once per *applied input from any session* —
other gateways, price ticks, control events. A gateway's own offers therefore receive strictly
increasing but **sparse and unpredictable** `inputSeq` values. And for a NEW order the `orderRef`
is assigned member-side too, so it is not knowable at offer time either.

**Then I checked what the ack actually carries.** `EGRESS_ACK_LENGTH = 24`, and it is fully packed:

| offset | field | gateway-chosen? |
|---|---|---|
| 0–7 | `appliedSeq` | no — post-consensus |
| 8–11 | `orderRef` | **no for NEW** (member-assigned); yes for CANCEL/REPLACE |
| 12 | `kind` | no |
| 13–20 | `tradeSeq` | no |
| 21 | restingClass | no |
| 22 | `riskReason` | no |
| 23 | marketTrade | no |

**There is no gateway-chosen identity in the ack, and no spare byte.** So "correlate on what the ack
already carries" is **impossible for NEW orders**, which are the dominant path and the one that
strands. That closes the brief's open question, and it is why Option A below is a
*resynchronisation* design rather than a correlation design — the name in the brief is optimistic
and I am not going to pretend otherwise.

**One asymmetry worth recording:** CANCEL and REPLACE carry a gateway-known `orderRef` which the ack
echoes at offset 8. Those two paths *could* be correlated by identity today with no wire change. I
am not proposing it as a partial fix — a FIFO that is positional for NEW and keyed for
cancel/replace is harder to reason about than either pure design, and NEW is where the strand
happens.

---

## Option A — drain on leader change, plus a stale-ack watermark

**Gateway-only. No wire change, no epoch, no member roll.**

The FIFO invariant is "one direct ack per cleared offer, in offer order". It breaks at exactly one
observed moment: a leader change strands the offers the dying leader had sequenced but not yet
egressed to this session. So repair the invariant at that moment.

### The mechanism

1. **On `onNewLeader`, drain the FIFO.** Complete every registered pending as ambiguous (`null`) and
   release its permit. This is the existing `drain()` contract and the existing client contract —
   `null` already means "post-publish ambiguity, the caller must not claim rejection".
2. **Set a stale-ack watermark at the same instant:** `ignoreAcksAtOrBelow = highestInputSeqSeen`.
3. **`onDirectAck` ignores any ack with `inputSeq <= ignoreAcksAtOrBelow`.**
4. **`drain()` resets the watermark to -1**, because a fresh session may be a fresh epoch in which
   `appliedSeq` restarts — the same reason it already resets `lastInputSeq`.

Step 2 is the part that makes this correct rather than merely better, and it is the step an obvious
implementation would miss. Without it there is a residual race: an ack that was in flight across the
election arrives *after* the drain, finds newly-registered orders in the FIFO, and pops one — the
original bug, re-seeded. The watermark is sound because **`appliedSeq` is replicated state**
(`snapshotBuffer.putLong(24, appliedSeq)`, restored on load), so it is monotonic across elections;
an ack numbered at or below what we had already seen cannot belong to an offer made after the drain.

### What A fixes and what it does not — stated plainly

- **Fixes:** the known and only observed trigger. K stops ratcheting; the offset cannot become
  permanent; §5's cliff (`K ≥ HTTP pool size`) becomes unreachable by leader change.
- **Does not fix:** correlation is still positional. **Any other cause of lost egress would desync
  the FIFO again**, and the gateway would not detect it. Tonight's evidence says leader change is
  the trigger (3 of 5 kills stranded; quorum loss strands nothing because it reconnects and
  therefore drains) — but "the only trigger we have seen" is not "the only trigger".
- **Cost to clients:** the orders in flight at an election are answered ambiguous. That already
  happens today. The difference is that today they are answered ambiguous *and* they corrupt every
  subsequent order.

### A2 — the refinement that preserves transparent failover, considered and NOT recommended

A reviewer will ask why the drain is unconditional, and the failover-transparency question this
design raises deserves an answer rather than an open end. **If transparent failover turns out to be
a hard requirement, this is the design that keeps it. It is not the recommendation.**

**A2: hold acks across the election instead of draining immediately.** At `onNewLeader`, record
`N = fifo.size()` — the at-risk set. For a short grace window (an election is ~150 ms; size it from
`yu12-gke-failover-transparency`'s own measurements), **buffer arriving acks instead of applying
them** — a 24-byte copy each, since the egress `DirectBuffer` is only valid inside the callback.
Then:

- **All `N` arrive before the window expires** → nothing stranded. Apply them in order: every pop is
  correct, and the in-flight window is answered normally. **Transparent failover preserved.**
- **Window expires short** → something stranded. Drain the at-risk set as ambiguous and **discard
  the buffered acks**. Same outcome as A, one grace window later.

It is correct — misattribution is impossible in both branches, because acks are never applied
positionally while the set is in doubt.

**Why it is not the recommendation, in one line each:**

1. **All acks must be held, not just the at-risk ones.** Offers continue during the window, and a
   new order's ack would otherwise pop an at-risk head. So the buffering sits in the owner thread's
   hot path — the same path whose 50 ms blocking poll was already measured as a hard 1.2k/s ceiling
   and deliberately removed. Adding conditional buffering there is the highest-risk place in this
   program to add state.
2. **It buys the good case and not the bad one.** In the 3-of-5 elections that stranded, A2 ends up
   exactly where A does, one window later. It only helps the 2-of-5 that strand nothing.
3. **A's cost is small and honest.** The in-flight window at ~20 orders/s is ~20–50 orders answered
   ambiguous per election, and `null` already means "must not claim rejection" — the client contract
   does not change, only how often it is exercised.

**So the product question is genuinely a question, and it has a price tag:** if the answer is
"transparent failover is a promise we keep", A2 is the design and its cost is hot-path complexity in
the owner loop. If the answer is "an election may cost the in-flight window an honest ambiguous
answer", A is simpler, safer to review, and strictly easier to carry across four layers.

### Dependency worth flagging

A **requires the full `EgressListener`**, because `onNewLeader` on a method reference sits on the
interface's default no-op body — the original §3 defect. That landed in `9caab079` (YU13 layer) and
exists on YU16/YU17. **A is not implementable on YU12**, whose gateway has no listener; see below.

---

## Option B — echo a gateway-chosen request id in the ack

**The only design in which correlation is unconditionally correct.**

The gateway stamps a 64-bit `requestId` on the input; the engine carries it through and the member
writes it into the ack; `onDirectAck` becomes a map lookup instead of a `pollFirst()`. A stranded
offer then has no effect on any other order at all — it simply never gets its ack, and is reaped by
its own deadline.

### Its true cost is not what the brief assumed, in both directions

**The brief said the wire change is `blp-replication.xml` (carriers YU11/YU12/YU14/YU15). It is
not.** The egress ack is a **hand-rolled 24-byte layout** in `MatchingEngineClusteredService`
(`EGRESS_ACK_LENGTH`, carriers **YU12/YU13/YU14/YU15**) with the gateway decoding by literal
offsets. The SBE ingress schema is not involved. So:

- **Cheaper than assumed:** no SBE schema change, no generated-codec regeneration.
- **Not cheaper in the way that matters:** it is still a gateway↔member wire break. `EGRESS_ACK_LENGTH`
  goes 24 → 32, so **the YU15↔YU16 mixing window breaks** and members and gateways must roll
  together. Every rig re-pins.
- **Carrier count is 8, not 4:** four layers of `MatchingEngineClusteredService` *and* four of
  `ClusterGatewayMain` (the decode offsets), hand-merged, with the shadowed-layer trap live in both.

**And the carriage is already half-built.** `ingressNanos` is a gateway-chosen 64-bit value that the
engine already threads from `InputEvent` to `OutputEvent` on *every* emit path (`e.ingressNanos`
passed at MatchingEngine lines 540, 600, 623, 734, 769, 782, 788). So the engine already
demonstrates end-to-end carriage of a gateway value; B is "add a field beside it and put it in the
ack", not "invent a correlation channel". A dedicated `requestId` is still right — overloading
`ingressNanos` would collide with the latency histograms — but the plumbing pattern exists and is
proven deterministic.

### Why B is not the recommendation *today*

It cannot be rolled gradually. Per the project's own rule, a mixed-version window across the
deterministic core diverges members permanently — so B means scale to zero, wipe, fresh epoch, on
every rig. That is the right price for removing a defect class, and the wrong price to pay in a
hurry for a defect whose only known trigger A removes for free.

---

## The six things the brief required this design to address

**1. Continuation fills.** Preserved unchanged in A: `lastInputSeq` still short-circuits later
egress under the same `appliedSeq`, so "later fills belong to the already-answered order" still
holds. The new watermark check is a *separate and earlier* test — `inputSeq <= ignoreAcksAtOrBelow`
means "stale, from before the election"; `inputSeq == lastInputSeq` means "continuation of the
current input". They cannot be collapsed into one comparison and must not be. Under B the
continuation rule survives verbatim, keyed off the same field.

**2. Stranded entries — what releases them.** In A, the `onNewLeader` drain releases both the permit
and the answer, on the owner thread, which is the only thread allowed to touch the FIFO. **I am
deliberately not adding a submitter-side release**: the submitter's timeout does not release today
*because the owner owns the slot*, and that reasoning is correct — reintroducing a submitter release
is precisely the permit-leak shape, and the `kill -3` evidence shows the current design does not
leak. Under B, a stranded entry is reaped by a deadline sweep on the owner thread, same ownership
rule. **Neither design lets a submitter release a permit.**

**3. The self-heal's safety argument.** A **strengthens** it and does not weaken it. The self-heal
fires on `offeredUnackedStreak` — offers that *cleared* and were never acked — and the quorum-loss
hazard is that firing during quorum loss parks the owner inside `connectCycling()`'s
`while (running)` loop. Under A, "unacked" is unchanged in meaning: the streak still counts cleared
offers awaiting acks. What changes is that a leader-change drain **resets the streak to zero along
with the FIFO**, so the self-heal fires *less* often, never more. During quorum loss offers do not
clear (measured: `offer_attempt +1 / offer_success +0`), so the streak still cannot advance and A
adds no new path to `connectCycling()` — A's drain is called directly, not via reconnect.
**Explicit check required in review:** A's drain must NOT route through `connectCycling()`, or it
would introduce exactly the hazard the self-heal was designed to avoid.

**4. `drain()` semantics.** A adds one line to `drain()` — reset the watermark to -1 alongside
`lastInputSeq = -1` — for the identical reason: a fresh session may be a fresh epoch where
`appliedSeq` restarts from 0, and a stale watermark would then swallow every legitimate ack.
**This is A's most dangerous line.** Getting it wrong fails silently and totally: the gateway would
ignore all acks and every order would 504 forever. It needs a test that restarts the epoch.
Under B the watermark does not exist and `drain()` is unchanged except for clearing the map.

**5. The batch path and YU12.**
- **Batch:** untouched by both. `handleBatch` holds the owner thread for a whole batch and counts
  acks against `batchOutstanding` rather than using the FIFO, so it has no positional correlation to
  break. It does call `drain()`, which is why A's watermark reset must be correct there too.
- **YU12:** **A does not transfer.** YU12's gateway has no `EgressListener` (so no `onNewLeader`)
  and `offerAndAwait` collapses offer-cleared and ack-arrived into one boolean, discarding the local
  `offered` — the same blocker already recorded for the self-heal. A YU12 variant needs the listener
  carried first, which is its own change across four call sites (orders, trades, symbols, batch).
  **B transfers to YU12 more cleanly than A does**, because a keyed lookup does not depend on
  knowing which of the two failures occurred. That is a genuine point in B's favour and the only one
  where YU12 argues for the bigger change.

**6. Rollout.**
- **A:** four carrier layers of `ClusterGatewayMain` (YU12 excluded — see above; YU13, YU16, YU17
  operative, plus the shadowed copies left alone). Rolling restart, no epoch, no member change.
  **Mixed fleet mid-roll is safe**: old and new gateways differ only in when they drain their own
  private FIFO. They share no state and the members cannot tell them apart.
- **B:** members and gateways roll together. Scale to zero, wipe PVCs (or emptyDir), fresh epoch,
  re-pin every rig, re-seed accounts. Mixed fleet mid-roll is **unsafe by construction** — a 24-byte
  reader against a 32-byte writer misreads every ack. Needs the deterministic-core discipline in
  full.

---

## Severity framing

The misattribution consequence — a `200` carrying another order's `orderRef` — is **plausible and
unestablished**. **This design does not depend on it.** Both options are justified by the measured
facts alone: permanent invisible orders and a total HTTP collapse at `K ≥ 64`. If the
staggered-burst or engine-resend test confirms misattribution, **urgency changes and the design does
not** — except in one respect worth stating now: confirmation would make B's *unconditional*
correctness materially more valuable than A's *trigger-specific* repair, because a silent
cross-client wrong answer is not something to leave exposed to an unknown second trigger.

---

## What I want the reviewer to attack

1. **The watermark's monotonicity claim.** I assert `appliedSeq` is monotonic across elections
   because it is replicated snapshot state. If there is any path where a new leader can *lower* it,
   A is unsound and I want to know.
2. **`onNewLeader` timing.** I assume it fires on the owner thread's `pollEgress` before subsequent
   offers are processed. If Aeron can deliver it on another thread, the drain needs different
   synchronisation — the FIFO is owner-confined by contract.
3. **Whether drain-on-new-leader is too aggressive.** Every election now costs the in-flight window
   an ambiguous answer, including elections that would not have stranded anything (2 of the 5 I
   induced stranded nothing). That is a deliberate trade — bounded honest ambiguity in place of
   unbounded silent corruption — but it is a real regression in the transparent-failover case that
   `yu12-gke-failover-transparency.sh` asserts, and that proof may start failing. **Someone should
   check that proof against this design before it is written.**
4. **The YU12 argument.** I claim B transfers to YU12 better than A. If that is wrong, B loses its
   best non-correctness argument.
