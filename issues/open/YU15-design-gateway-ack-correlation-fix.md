# DESIGN: fixing the gateway's ack correlation — gateway-only vs engine-echo

**Status: DESIGN ONLY, not approved, nothing implemented. 2026-08-16.** Written by the GKE arm of
§5. The defect is diagnosed in `HANDOFF-issue-gateway-wedges-after-leader-kill.md` §5 and the raw
evidence is in `YU15-s5-gke-fifo-correlation-offset.md`.

**Recommendation: ship Option A, and schedule Option B as the only design that closes the class.**

**A is a mitigation with a known uncovered path — not a repair of the invariant.** It removes the
dominant observed trigger (leader change) for free: gateway-only, rolling restart, no epoch. It does
**not** reach the second trigger, which is deliberate and load-correlated — the egress ack is
best-effort by design and can be dropped with no election involved. See *A second strand trigger*
below. **B is the only design under which a stranded offer harms nothing**, because no order depends
on another's position.

This wording is deliberately stronger than the first draft's "scheduled, not rushed". That was right
when leader change was the only known trigger. It is not any more.

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
4. **`drain()` resets BOTH `ignoreAcksAtOrBelow` AND `highestInputSeqSeen` to -1.** A fresh session
   may be a fresh epoch in which `appliedSeq` restarts — the same reason it already resets
   `lastInputSeq`.

> **Step 4 resets two fields, not one, and review caught that the first draft reset only one.**
> `MatchingEngineClusteredService:258` does `this.appliedSeq = 0` on a fresh epoch (verified). If
> `drain()` clears the watermark but leaves `highestInputSeqSeen` at the old epoch's high value —
> say 5000 — then the *next* `onNewLeader` recomputes `ignoreAcksAtOrBelow = 5000`, every ack in the
> new epoch carries `inputSeq ≤ 5000` and is ignored, the FIFO never pops, no permit is released,
> and **every order 504s forever**. It is also **latent**: it does not fire on the reconnect, it
> fires on the first election *after* the epoch change, so a fresh-epoch smoke test passes and the
> landmine detonates later.
>
> **The invariant, and it belongs in a comment at the declarations:** *every field derived from
> `appliedSeq` resets together in `drain()`.* There are now three — `lastInputSeq`, the watermark,
> and the high-water mark — and the next person will add a fourth or, worse, merge them for tidiness.
> They answer different questions and must not be collapsed.
>
> **This also neutralises the `onNewLeader`-during-`connect()` ordering hazard**, which review
> raised and neither of us could settle from the jar. `connectCycling()` calls `drain()` *before*
> `AeronCluster.connect(...)`, so with both fields reset, a callback arriving inside `connect()`
> computes a watermark of -1 — harmless. The threading question is still worth answering, but
> correctness no longer depends on the answer.

Step 2 is the step an obvious implementation would miss, and it is load-bearing — **but not for the
reason the first draft of this document gave, which was wrong and is corrected here.**

**The wrong justification (retracted):** "it stops an ack that was in flight across the election from
landing after the drain and popping a newly-registered order." **It cannot do that.** A stranded
order's ack carries a seq *above* everything we had evidence of — never seeing it is what made the
order stranded — so `seq <= ignoreAcksAtOrBelow` is false and it would pass the gate. A genuinely
late old-leader ack is indistinguishable from the new leader's next one.

**What the watermark actually closes, and it is real: straggling continuation fills.** The resync
sets `lastInputSeq = -1`, deliberately forgetting the continuation boundary. A later fill of a
*pre-resync* order arrives carrying its entry ack's seq, which is `!= -1`, so without the gate it
would **pop a fresh head** — re-seeding the offset by one. That seq is `<= highestInputSeqSeen`, so
the gate catches it. Sound because `appliedSeq` is replicated state
(`snapshotBuffer.putLong(24, appliedSeq)`, restored on load) and monotonic across elections.

**This makes "observe every egress kind" necessary rather than merely safe.** Feeding
`highestInputSeqSeen` from resting updates, symbol acks and batch fences as well as direct acks
raises the evidence, and every extra kind catches more stragglers. It can never gate a legitimate
ack: any offer made after the resync is applied after everything already applied, so its ack carries
a seq strictly greater than anything observed before.

**The residual, stated rather than hidden:** a genuinely late stranded ack, above the watermark, is
uncovered. It is also **measured not to occur** — K never decreased across three elections — and
that is the identical fact on which A2 was rejected. If late at-risk acks were possible, A2 would be
viable *and* this gate would be insufficient; they stand or fall together.

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

**REJECTED — and the first reason is decisive on evidence already in this file.**

**1. A2's optimistic branch is unreachable. The at-risk acks never arrive, and it is measured.**
K went **21 → 36 → 51** across three leader kills and **never decreased** — not during the
elections, not across full member catch-up, not through thousands of later orders, and not across
30 s of total idle. If a single at-risk ack could land late, it would pop a stranded head and K
would drop by one. It never did, on any election. **So the at-risk set is destroyed, not delayed**,
and A2's "all `N` arrive" branch cannot occur. It is not that A2 helps only the 2-of-5 that stranded
nothing — **it helps none of them**, because those had nothing at risk in the first place. A2 is
strictly dominated by A: same outcome, one grace window later, with permits held throughout.

**The mechanism agrees, and it is verified.** A follower applies the same log the leader does
(`MatchingEngineClusteredService:168` states it), but its egress `session.offer()` at `:736` is
suppressed at the framework level rather than in the service — which is why, unlike the trade bridge
(`:678`), the kdb tap (`:686`) and the order bridge (`:703`), that line carries **no
`role == LEADER` guard**. A promoted follower does not re-apply entries it already applied, so the
egress it suppressed while following is never regenerated. **What the dying leader failed to deliver
is unrecoverable by design.**

**2. Buffering stops permit release, and the window is only 4096.** The binding cost is not the
conditional branch — it is that no permit is released while acks are held. Orders arriving in a
150 ms window against 4096 permits: ~3 at §5's 20/s (trivial), but ~28,500 at the 190k/s
four-gateway ceiling (figure from the reviewer's bench notes, not independently re-measured here) —
**window exhausted in ~21 ms**, after which every submitter blocks the full 10 s in
`inflight.acquire()`. On a loaded tier A2 converts a transparent failover into a **full stall**,
which is the outcome it exists to prevent. This holds even if the branch were free.

**3. The window is unsizable in principle.** A2 must wait for "no further at-risk acks are coming" —
**the absence of a message, which has no deadline.** No measurement can validate a guess. And the
bimodal figures I reached for (~85–180 ms fast, ~670–850 ms slow) are the wrong quantity anyway:
they are the *gateway's session-reconnect gap*, the 31× dead-endpoint penalty the rotating start
already fixed, not at-risk ack latency.

**4. Hot-path cost, confirmed but no longer the case against it.** All acks must be held, not just
at-risk ones, so the buffering sits in the owner loop — the path whose 50 ms blocking poll was
measured as a hard 1.2k/s ceiling and deliberately removed (`ownerLoop:406-411`). Real, and now a
supporting argument rather than the argument.

> **The falsifier, so this stays rejected for the right reason.** A2 becomes worth reconsidering
> **if and only if an at-risk ack is ever observed arriving after an election — i.e. K decreasing on
> its own.** Nothing else revives it. Anyone tempted to revisit A2 should look for that first, and
> it is cheap to look for: sample `inflight_orders` across an election and watch for a decrease.

**The product question is therefore not what the earlier draft said it was.** It is not "how much
does transparent failover cost in the owner loop". It is: **transparent failover across an election
is not purchasable at this layer at all** — the acks are destroyed by the promotion — **and B's
keyed correlation is the only thing that buys it**, because there a stranded offer harms nothing,
since no other order depends on its position.

### A second strand trigger, by design — this weakens A's residual and strengthens B

Found while verifying the above, and it is not hypothetical. The egress ack offer is **best-effort
by design**, with a bounded retry (`MatchingEngineClusteredService:730-740`):

```java
// Egress is best-effort BY DESIGN — a slow client gets drops, never the state machine's time;
// the committed log remains the source of truth.
for (int i = 0; i < 20; i++) {
    if (session.offer(ackBuffer, 0, EGRESS_ACK_LENGTH) > 0) return;
    idle.idle();
}
// falls through — the ack is DROPPED
```

**So an ack can be lost with no leader change at all**, whenever a client session is slow enough to
backpressure egress for ~20 attempts. Every dropped ack strands an offer and increments K exactly as
an election does.

This is A's residual made concrete: the document says "the only trigger we have seen is not the only
trigger", and here is a **second one, documented in the source and deliberate**. A does not detect
or repair it, because there is no `onNewLeader` to hang the drain on.

**What this does and does not establish.** It is a code reading, not a measurement — I have not
observed a drop-induced strand, and in the §5 hang the owner thread was RUNNABLE in `pollEgress`
throughout, so gateway HTTP saturation did **not** visibly starve egress. **No feedback loop is
claimed**, tempting as the shape is.

#### The consequence for A: a sawtooth, and it is weakest where the cluster is healthiest

A's repair fires on `onNewLeader`. Drop-induced strands accumulate **between** elections. So A does
not eliminate K — it converts a monotonic ratchet into a **sawtooth bounded by
(drop rate × inter-election interval)**.

**That inverts the usual reliability intuition, and it is the part to not let a reader miss.** A
stable cluster that never elects never drains, so drop-induced K grows with nothing to reset it.
**A's repair is triggered by the very event that is otherwise the problem, and a well-behaved
cluster starves it of repairs.** "A removes the known trigger" reads as though A degrades
gracefully; on this path it degrades *better on a bad cluster than on a good one*.

**And the self-heal does not cover the gap either.** The obvious objection is that
`offeredUnackedStreak` will catch drop-induced strands — a dropped ack does qualify (the offer
cleared, `p.offered` is true, the submitter times out, `r == null`). But the streak **resets on any
answered order**, verified in the operative layer:

```java
} else {
    noAckStreak.set(0);
    offeredUnackedStreak.set(0);      // ← any answered order clears it
}
```

Under an offset, **most orders are answered** — by foreign acks. That is exactly what the kind arm
measured: streak oscillating **1–9, never approaching 20, at K = 2**. So at small K the self-heal is
structurally prevented from firing, and it becomes reachable only once enough orders go unanswered —
which is around **K ≥ pool size, i.e. §5's cliff itself**.

**So both repair paths arrive at the cliff rather than before it.** Neither A's drain nor the
self-heal's drain is correlated with drop-induced K growth in the range where intervening would
help.

*Status of that composition: the streak oscillation is measured (kind), the drop-induced
accumulation is a code reading (above), and the combination of the two is inference. Recorded as
reasoning, not as a measured claim.*

**What this establishes for the recommendation:** A is not "repairs the only trigger". It is
"repairs the dominant observed trigger, leaves a deliberate load-correlated one uncovered, and its
repair engages least often on the healthiest clusters". That is why B's line above moved from
*desirable* to *the only design that closes the class*.

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
- **YU12: A does not transfer — and, correcting the first draft, YU12 largely does not have this
  defect.** A needs `onNewLeader`, and YU12's gateway has no `EgressListener`; `offerAndAwait` also
  collapses offer-cleared and ack-arrived into one boolean, the blocker already recorded for the
  self-heal. But the reason that matters less than it looks: **YU12 has no FIFO at all.** Verified in
  the *operative* 741-line gateway on the YU12 branch — not the 610-line shadowed copy the
  descendants carry — `grep -c 'class Inflight|fifo|pollFirst'` returns **0**. It has a single slot,
  `lastOrderAck`, cleared immediately before each offer (`:428`) and awaited synchronously (`:429`),
  with `:347` commenting *"first order-kind ack after the offer wins"*.

  So YU12's failure mode is **one misattributed ack per stranded event, self-correcting on the next
  request**. There is no K, nothing ratchets, and **§5's `K ≥ pool size` collapse is structurally
  impossible there** — the synchronous funnel holds one order in flight by construction.

  **The first draft of this document used YU12 as an argument FOR B. That was wrong and the argument
  is withdrawn.** Paying eight carrier layers, a gateway↔member wire break, the end of the
  YU15↔YU16 mixing window and a fresh epoch on every rig, partly to fix a bounded, self-correcting,
  depth-1 misattribution on a tier with no rig, is a *weak* case rather than a strong one. **B's
  justification is unconditional correctness against unknown second triggers, and that is the only
  one it needs.**

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

## Severity framing — the misattribution consequence is now CONFIRMED (kind, 2026-08-16)

**Measured, no longer predicted.** Gateway `:yu15prewedge`, members `:yu16`, rig verified quiet,
K = 20, **50 orders staggered 60 ms so offer order equals launch order**, both branches fixed in the
script before the run:

- **all 30 successes carried the ref of the order K positions later; zero carried their own;**
- **the last K clients got `504`**, where the innocent reading required the *first* K;
- launch index 1, true ref 1445, was told `{"orderRef":1465}`;
- offer order closed independently — returned refs strictly +1 monotonic with launch index, and
  `next_order_ref` ended at exactly `R0+50` on all three members.

So the defect is not only invisible orders. It is **silent cross-client wrong answers carrying HTTP
200**: a client cancelling "its" ref cancels a stranger's order.

**This design never depended on that claim and still does not** — both options were justified by the
measured facts alone (permanent invisible orders, total HTTP collapse at `K ≥ 64`). **What
confirmation changes is urgency, plus one thing more:** it makes B's *unconditional* correctness
materially more valuable than A's *trigger-specific* repair, because a silent cross-client wrong
answer is now a **live** exposure to the by-design second trigger that A cannot reach — not a
hypothetical one.

---

## Adversarial review — kind arm, 2026-08-16. CONCUR with A, conditional on three fixes

Reviewed against the code rather than this document. Its findings are folded in above; recorded here
is what it changed, because a design's review history is the evidence that its claims were attacked.

**Verdict: concur with shipping A over B today, conditional on the three conditions below.** It
independently confirmed the structural finding (`event.seq = ++appliedSeq` and
`event.orderRef = (int) nextOrderRef++` both member-assigned on apply; the 24-byte ack carries no
gateway-chosen field) and agreed that A is a resynchronisation design and should be named one.

**Condition 1 — BLOCKING, now fixed above.** `drain()` must reset `highestInputSeqSeen` as well as
the watermark, or an epoch change re-poisons it and every order 504s forever. **The epoch test must
assert on an election AFTER a fresh epoch**, not merely on a fresh epoch — a test that wipes,
reconnects and sends orders passes with the bug present. I verified `appliedSeq = 0` at
`MatchingEngineClusteredService:258` and confirm the defect was real in the first draft.

**Condition 2 — fix `yu12-gke-failover-transparency.sh` before or with A.** Review's finding, which
I verified: the proof's `GAVEUP` assertion survives A (drained orders return `null`, the client
retries the same `clientOrderId`, and the engine re-emits the original) — **but its "ZERO
DUPLICATED" check will fail**, and for the proof's own reason. The ref is consumed at
`MatchingEngineClusteredService:301`, *before* the engine's idempotency check, and the code's own
comment says so: *"A duplicate retry also consumes a value … the engine then answers from
idempotency."* So an idempotent retry still burns a ref, and every order A drains contributes **+2
to the `next_order_ref` delta and +1 to the acked count**. The proof reports that difference as
duplication that did not happen. A converts a latent unsoundness — already recorded in §4 of the
wedge issue — into a visible failure proportional to the in-flight window at election time. **The
proof must assert against a booking-grained quantity (open-order count or the read model), not the
ref counter.** If it is not fixed, A will be blamed for duplication it did not cause, which is worse
than the proof simply failing.

**Condition 3 — the YU12 argument for B is withdrawn.** See §5 of the constraints above; review was
right and I verified it in the operative 741-line gateway.

**Attacks that cleared.** `onNewLeader` is owner-confined: every `pollEgress` call site in the YU13
layer is owner-thread (`ownerLoop:419`, `offerPipelined:1012`, the batch paths, and `:206` states
the contract), and `AeronCluster.connect()` runs on the owner too. So the drain needs no
synchronisation. Whether Aeron can invoke `onNewLeader` from *inside* `connect()` remains
unverified — but with Condition 1 applied it is an ordering question with no correctness
consequence, as noted above.

**Review also asked for two things to be code-level rather than prose**, and I agree:

- a comment at the three `appliedSeq`-derived declarations saying they answer different questions and
  must not be merged;
- the "A's drain must NOT route through `connectCycling()`" constraint as a comment at the call site,
  with the reasoning — `connectCycling()` loops `while (running)` and would park the owner thread
  during a quorum loss, the exact hazard the `offeredUnacked` trigger exists to avoid. The next
  reader will see two drains and be tempted to unify them.

## Implementation review — GKE arm reviewing the kind arm's build, 2026-08-16

Role swap: this design's author reviewing its implementation. Concur, subject to one blocking fix.

**Improvement on the design, adopted:** the blocking `highestInputSeqSeen` reset was made
*structural* rather than a line — `resetSequenceSpace()` is the single place all three
`appliedSeq`-derived fields clear, declared together under one comment naming them as one sequence
space. A rule stated once beside the fields beats a line someone must remember to add. `drain()` and
`onNewLeaderResync()` then differ exactly where they should: both complete the at-risk set, only
`drain()` resets the numbering, because a new leader continues `appliedSeq` while a new session may
not.

**BLOCKING — `noAckStreak` must not be inflated by the resync, and must not be reset either.**

The resync completes N orders `null`; each submitter's `submitPipelined` then increments
`noAckStreak`, so the streak jumps by **N**. At N ≥ 20 (`READY_NO_ACK_STREAK`) `/ready` goes 503;
at `replicas: 1` the pod leaves the Service; **`noAckStreak` clears only on a successful order**,
which can no longer arrive because the pod is out of the Service. Liveness fires at 100, so **for
20 ≤ N < 100 nothing clears it at all** — a hang needing a human, not a flap. Measured strand sizes
were 21, 15, 15, so the first bracket is the likely one.

**No proof would catch it.** Every proof drives the **pod IP** deliberately, because a failing
readiness probe evicts the pod from the Service exactly when measurements matter — so the whole
suite bypasses the mechanism that breaks.

**Since measured (2026-08-17, kind — see the starvation section of the wedge handoff doc):** the
[20, 100) bracket is not a theoretical hang. A gateway with the streak frozen in it sat evicted
behind an empty Service for 5+ minutes with 0 of 182 requests arriving and no restart, while the
same wedge at the pod IP restarted in 41 s once the streak crossed 100. So this condition was the
difference between A shipping a repair and A shipping a trap: without it, every election with
N ≥ 20 in flight would have put a production gateway into exactly that filmed state.

**And resetting the streak in the resync is also wrong**: quorum restoration elects a leader and
fires `onNewLeader`, so a blanket reset would flip `/ready` to 200 during
`yu16-ready-tracks-commit`'s step 3, which asserts it stays 503 across a **restored** quorum —
failing that proof or, worse, laundering its verdict.

**Fix: do not COUNT resync-completed orders.** Mark the `PendingOrder` in the resync path and skip
the `noAckStreak` increment for those in `submitPipelined`. Quorum-loss orders fail via
`offerPipelined`'s deadline path, are not marked, and still count — so the readiness proof is
untouched; new post-resync failures still climb the streak, which is the honest signal. Those N
ambiguous answers are not evidence the gateway cannot commit: they are the resync's own deliberate
act, and post-resync it is *more* able to commit than a moment earlier. `offeredUnackedStreak.set(0)`
is correct as built.

## Still open, for yaakov rather than for review

1. **Transparent failover across an election is not purchasable at this layer.** This replaces the
   earlier framing of "how much does it cost". The promoted follower never regenerates the egress it
   suppressed, so the at-risk acks are destroyed rather than delayed — measured (K never decreased
   across three elections) and mechanistically verified. A2 was the design that would have bought it
   and it cannot. **B's keyed correlation is the only thing that buys it**, because there no order
   depends on another's position. The decision is therefore not *whether to pay for A2* but
   **whether transparent failover is worth B's price**: eight carrier layers, a gateway↔member wire
   break, the end of the YU15↔YU16 mixing window, and a fresh epoch on every rig.
2. **When to schedule B — and the case is stronger than the first draft made it.** A repairs the
   leader-change trigger. The egress ack is **best-effort by design** and can be dropped after 20
   attempts, which strands an offer with no election involved. So A repairs one of at least *two*
   known triggers, one of them deliberate. That is a scheduling input, not an argument against
   shipping A.
3. **Whether the drop-induced strand is reachable in practice.** Code-read only, and it is the one
   open question that could move B's schedule. **Two readings, and take the cheap one first:**

   - **Passive, rides along on any bench run.** On a healthy cluster under sustained load **with no
     kills**, watch `traderx_gateway_pipeline_total{stage="ack_completed"}` against `offer_success`.
     **If the gap opens at all without an election, drops are stranding in the wild** and this
     question is answered with no rig time of its own. *Caveat from this document's own instrument
     note: `drain()` never increments `ack_completed`, so the gap over-reports after any reconnect —
     the run must be reconnect-free for the number to mean anything, and if `gap` and `depth`
     disagree, **`depth` is the reading to trust**.*
   - **Active, if the passive reading is ambiguous.** Drive hard enough to backpressure egress and
     watch whether `depth` climbs with no leader kill.

   Same property that made the engine-resend oracle worth having: it can ride on work that is
   happening anyway rather than needing its own expedition.

---

## IMPLEMENTED — Option B landed on YU17, 2026-08-18

**Status of this section: B is BUILT, unit-proven with detonators, and rig-proven on
`kind-traderx-yu12-cluster` (results below). It lands on YU17's operative layers ONLY; every other
branch still runs A (positional + resync), which remains correct there.** This doc now exists on
YU15 (home), YU16 and YU17 so the same readers see the same record.

### The two places the design's pricing was wrong, both in B's favour

1. **The ingress costs NOTHING.** The design priced "the gateway cannot know a correlating value at
   offer time" and stopped at the egress. It missed that the ingress `InputEventMessage` carries an
   `inputSeq` field that is DEAD on the cluster tier: every gateway call site passes literal 0, and
   the member overwrites `event.seq = ++appliedSeq` on apply. The request id rides that slot. So:
   no SBE schema change, no generated-codec change, no `InputEvent.java` change, no committed-log
   format change — a pre-B log entry simply decodes as requestId 0, which the gateway never
   registers. The only wire break is the egress ack, 24 -> 32 (requestId appended at 24..31),
   exactly the break this design already priced.
2. **The snapshot does not move.** The id is carriage, not state: it is read by no decision and
   written to no snapshot, and `RequestIdEchoTest.requestIdsNeverEnterReplicatedState` asserts two
   services applying identical inputs that differ only in request ids write byte-identical
   snapshots. `SNAPSHOT_FORMAT` stays 7. The FX-rate precedent's fail-closed discipline therefore
   applies at the WIRE instead: the gateway refuses (loudly, `GATEWAY-ACK-FORMAT-MISMATCH` + a
   metric) any egress record whose length is not this build's ack length, because reading a request
   id off a 24-byte record would be silent adjacent-memory garbage and a permanent silent 504.
   The deterministic-core roll discipline is UNCHANGED by this good news: the egress break alone
   means members and gateways roll together — scale to zero, wipe PVCs, fresh epoch.

### What replaced what, on the YU17 layers

- `MatchingEngineClusteredService` (YU17 layer): `EGRESS_ACK_LENGTH` 32; apply-scoped
  `applyRequestId` captured after decode (before the sequencer overwrites `event.seq`) and echoed
  by every egress of that apply — order-lifecycle acks carry it at 24..31; symbol/extract acks
  carry 0 there (they keep their own requestId at 13); backpressure drains are the same apply and
  carry the same id.
- `ClusterGatewayMain` (YU17 layer): `Inflight` is now a map keyed by request id plus an
  offer-order reap queue. `onDirectAck(requestId)` is `pending.remove(requestId)` — id 0 never
  matches. Continuation fills find the entry already removed. **A's entire appliedSeq sequence
  space (`lastInputSeq`, `highestInputSeqSeen`, `ignoreAcksAtOrBelow`) is deleted with the
  positional pop**: ids are gateway-lifetime-monotonic and never reused, so there is nothing to
  reset at any boundary — the epoch detonator class of bug is structurally gone.
- **`onNewLeaderResync` is deleted, deliberately.** Under keyed correlation an election needs no
  repair: survivors (anything the new leader sequences) complete by their own key, and draining at
  `onNewLeader` would DESTROY those answers — the exact transparent failover B exists to buy. The
  destroyed-ack strands are reaped by a new owner-thread deadline sweep (`sweepOverdue`), which is
  also the ONLY thing that returns a stranded offer's permit — without it every drop-induced strand
  leaks a permit permanently, a leak the positional pop used to mask by misattributing. The
  resync-mark streak exemption went with the resync: nothing bulk-answers the window any more, so
  every submitter null is honest streak evidence again.
- **The design's open question 3 (is drop-stranding reachable in practice?) now has a direct
  instrument**: `traderx_gateway_pipeline_total{stage="reaped"}` counts exactly the stranded
  offers, on any run, kill or no kill — no `ack_completed`-vs-`offer_success` gap inference needed,
  and `drain()`'s known over-report of that gap is moot.

### Verification record

- Composed YU17 suite: 392 tests, 0 failures; all four allocation gates + both Epsilon-GC gates
  green (the apply-path edit is one long capture — allocation-free).
- Detonators (exact-inverse injections in the generated tree, full suite each): zeroing the echo
  failed exactly `RequestIdEchoTest`'s echo case; restoring the positional pop failed exactly the
  six keyed-semantics cases in the YU17 `InflightCorrelationTest` override; removing the sweep's
  `permits.release()` failed exactly the sweep test plus `ThreeMemberClusterTest` — the latter is
  an independent end-to-end witness of the permit leak, and the former proves no pre-existing test
  covered it.
- Rig: `scripts/proofs/yu17-keyed-ack-correlation.sh` — a PER-ITEM proof (every answered client is
  checked against the engine's own idempotency table, honouring the resting-guard/LRU/blank-key
  constraints this doc records) with an induction-retry loop so a non-stranding kill exits 2
  loudly instead of passing (rule 18), plus the deterministic mechanism reading: depth returns to
  0 with NO reconnect (restarts and `GATEWAY up` count asserted against preflight baselines) and
  `reaped` moves by the strand.
  - **GREEN ARM (kind, 2026-08-18, `traderx/cluster-node:yu17-ackB`, fresh epoch, in the full
    suite):** leader killed under a 50-order 60ms-staggered stream → 32 clients stranded;
    **18/18 answered clients carried their OWN ref** (engine idempotency oracle, 0 cross-wired);
    depth self-drained to 0 with restarts and `GATEWAY up` unchanged; `reaped` 0 → 20 (the sweep
    freeing exactly the stranded-and-offered set — the other 12 never cleared the election-window
    offer and released on the offer deadline); post-strand serial probe committed immediately with
    the oracle agreeing; all three members agreed on book and ref counter after quiesce. Under the
    positional build this same scenario produced 504-starving serial sends and a depth pinned
    through idle.
  - **RED ARM (same script, pre-B `:yu17-fx`):** exit 1, correctly attributed — A's election-time
    resync makes the per-item and depth arms pass HONESTLY (A does repair the election trigger),
    and the proof fails on the absent keyed mechanism (no `reaped` instrument): "depth 0 here came
    from a bulk election drain, not from per-request completion plus the deadline sweep".
  - Full suite on the B build: every proof PASS except `yu16-bond-position`, whose step-6 failure
    reproduced the PRE-EXISTING catalog-membership defect
    (`issues/open/catalog-additions-never-reach-a-deployed-environment.md`) after the fresh
    epoch's reseed — repaired through the supported `POST /stocks` path (10 keys, 201 each) and
    re-run green; unrelated to B (the bill BOOKS; the position write fails two services away).

### Where B is and is not

- **YU17: landed and rig-proven** (`traderx/cluster-node:yu17-ackB`, fresh epoch).
- **YU16, YU13/14/15 (via the YU13 gateway layer), YU12: NOT carried.** They keep A, which is
  verified on-rig for YU16/YU17-as-of-A and the YU13 layer. B on any of them is a per-branch
  gateway<->member wire break + fresh epoch, and shipping an unexercised deterministic-tier wire
  break into a branch is the exact trap the propagation skill names. Carrying B to YU16 is
  mechanical (patch-carry + its own test override + a re-pinned rig run) and is scheduled work,
  not done work. The YU13-layer test `InflightCorrelationTest` still locks POSITIONAL semantics
  for the branches that run it — that is correct and deliberate; YU17 shadows it with a keyed
  override.
