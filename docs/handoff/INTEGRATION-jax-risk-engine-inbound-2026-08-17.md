# What the JAX engine can produce that TraderX will consume

For Alex, on `AlexNeugroschl/JAX_Risk_Engine`. Companion to
`INTEGRATION-jax-risk-engine-2026-08-17.md`, which covers the other direction.

That first document is entirely about what we send you. This one is the reverse, and it exists because
the things currently blocking our roadmap are **analytics**, which is your half of the system, not ours.
Everything below is something you can already compute or nearly can. None of it is a request to build a
trading system feature.

**One framing sentence, because it decides the shape of everything else.** You are the analytics tier
and we are the observation and booking tier. We publish what we observed and what we booked; you derive
what those things are worth and how risky they are. When a derived number comes back to us, it stops
being an analytics output and becomes **replicated state inside a consensus system**, and that imposes
requirements that have nothing to do with pricing. §1 is those requirements. Read it before designing
any artifact, because it constrains the format more than the finance does.

---

## 1. How anything reaches us: the delivery contract

Our order matcher is a deterministic replicated state machine on an Aeron cluster. Three members apply
the same totally-ordered input log and must reach byte-identical state. That produces four hard rules
for anything you send that touches the credit gate or the book:

### Rule 1: it arrives as a sequenced input event, never as a lookup

A member must never fetch your number at apply time. If member 1 reads your service and member 2 reads
it a millisecond later and gets a different answer, the cluster **diverges permanently** and does not
recover by restart. Your value enters the input log once, gets a sequence number, and every member
applies the identical bytes.

**This channel already exists and was built last week.** The currency-blind credit check fix
(`7256a33c` on YU17) added exactly this shape for FX rates:

| Layer | What it is |
|---|---|
| Ingress | `POST /risk/control/fxrate` on the authenticated control plane |
| Input event | `TYPE_FX_RATE` (14), carrying a USD-per-unit rate |
| Replicated state | Applied in the clustered service, written only by the sequenced apply |
| Snapshot | `T_FX_RATE` record, snapshot format 7 |
| Missing value | Booking refused `PRICE_MISSING`, never valued at par |

A discount curve or a per-contract valuation follows the same pattern with a different type code. You do
not need to design the transport; you need to design the **payload and its conventions**.

### Rule 2: it must be exactly reproducible, or it cannot be snapshotted

Whatever you send becomes part of a snapshot that members compare and that replays must reproduce. A
value that changes when you recompute it is not usable here. Practically: stamp what you read
(`sessionDate`, `priceSnapshotVersion`, your own build/model version) and make the same inputs produce
the same output bit for bit.

This is a real constraint on a Monte Carlo engine. **Anything with simulation noise in it cannot go on
this path** unless it is seeded and the seed travels with the value. Closed-form outputs (discounted
cashflow NPV, analytic DV01) are fine. A VaR number off 50,000 paths is not, unless it is pinned.

That is not a reason to avoid sending VaR. It is a reason to send it to the **reporting** path rather
than the credit gate, and the two have different requirements. Say which you intend.

### Rule 3: missing or stale must fail closed

An absent value refuses the action. It never falls back to a default, because a default is an assumption
that looks like a measurement. The FX fix has three layers of this and they are the model to copy:

- a snapshot from a build that does not know the state **refuses at the header** rather than guessing
- a snapshot without the state restores with it **empty**, and bookings that need it are refused
- a record the build cannot interpret **throws at restore** rather than being dropped

Applied to your outputs: an expired curve must refuse a booking, not price it at the last good value.
Tell us the validity window and we will enforce it.

### Rule 4: versioned, with roll-forward semantics

An unrecognised version is an error, never a best-effort interpretation. Our convention table throws
with "the contract was booked by a later build. Roll forward; do not reinterpret it." Adopt the same
rule for anything you version, in both directions.

---

## 2. What we would consume, ranked by what it unblocks

### 2.1 DV01 or key-rate durations per contract — the one to do first

**The problem it fixes exists today.** Our credit gate reserves against **notional**:

```
executedNotional[account] + reservedNotional[account] > creditLimit - notional
```

For swaps that is close to meaningless. A 30-year swap and a 1-year swap of the same notional consume
identical credit and carry wildly different risk. We fixed the currency dimension of this measure last
week; the tenor dimension is still wrong, and it is wrong on instruments we already book.

**What we would want:** a per-contract sensitivity, keyed by our `contractId` from the contracts file,
stamped to a session. DV01 in the contract currency (we will convert; we now have the FX machinery) or
in USD, your choice, but say which.

**Why you:** you already compute per-curve sensitivities by automatic differentiation. This is closer to
plumbing on your side than new capability.

**Why it matters beyond the fix:** it moves us from an OMS that counts notionals to one that measures
exposure. That is a meaningful claim about a sell-side system, and it is not one we can make alone.

**Caveat we would need from you:** DV01 is model-dependent in a way notional is not. If the number moves
because your calibration moved rather than because our position moved, a credit limit will trip for
reasons the trader cannot see. We would want the model/calibration version stamped alongside, so a limit
breach can be attributed.

### 2.2 NPV per contract — unblocks netting, which we have documented as impossible

Our contracts file states plainly that netting is `NONE` and none is possible, because netting an OTC
contract requires valuation we do not perform. That is an honest limitation and a hard ceiling: no
netting sets, no counterparty exposure, no XVA, all of which sit on your planned list.

The `nettingSetId` and `counterpartyId` columns already exist in both artifacts and are currently
carried by us and unused by both of us. They are there for this.

**What we would want:** NPV per `contractId`, stamped to `sessionDate` and `priceSnapshotVersion`, in a
stated currency, with the discounting convention named.

**One design question for you:** we would net by `nettingSetId` for reporting. Whether netted exposure
should also feed the credit gate is a policy question, not a technical one, and it is the kind of thing
that should be decided deliberately rather than by whoever wires it. Our instinct: reporting first, gate
later, because netting into a live credit check changes rejection behaviour in ways that need their own
proof.

### 2.3 Discount curves per currency, or forward rates directly

This unblocks the deferred FX-forward work (`issues/open/HANDOFF-fx-instrument-class.md`) and would
eventually let a EUR swap be discounted properly rather than approximately.

**If you send curves**, the artifact needs more than pillars and factors. It needs the conventions it
was built under, because two correct bootstraps disagree in the fourth decimal:

- pillar dates (not tenors — resolve the calendar on your side, we carry no holiday calendar)
- discount factors at those pillars
- **interpolation method** (linear on zero rates and log-linear on discount factors give different
  answers, and a consumer that guesses is wrong)
- day count and compounding convention
- which instruments it was bootstrapped from, and their marks' `sessionDate` / `priceSnapshotVersion`

**We would rather you sent forward rates directly.** An FX forward is spot times a ratio of discount
factors, and if you send curves we reimplement that ratio in Java, unvalidated, next to your validated
version. Same argument as the bootstrap: one implementation that can defend itself beats two where one
of them cannot. Send the derived number, keep the derivation in the engine that has ORE to check
against.

**This one is genuinely deferred on our side.** Nobody has asked to trade FX forwards, and the handoff
doc recommends leaving it. Do not build this ahead of 2.1 and 2.2.

### 2.4 VaR and Expected Shortfall — reporting path, not the gate

Your headline output, and we would happily surface it. But it belongs on the **reporting** path, not in
the credit gate, for the Rule 2 reason: simulation noise cannot live in consensus state unless it is
seeded and pinned.

That is not a limitation on your engine. It is a statement about where the number should land, and it is
worth agreeing on now rather than discovering when a member fails to reproduce a snapshot.

---

## 3. What not to send

- **Anything intraday.** Your roadmap scopes streaming risk out and commits to EOD batch cadence, which
  is fine. But it must be stated in whatever we consume: a gate fed by your numbers is **valuing at
  yesterday's close**. For our current purposes that is correct and defensible. Unstated, it is a
  landmine, because the first person to see a stale valuation will assume it is a bug.
- **Anything unvalidated.** The reason we want these from you rather than building them is that you have
  ORE to check against and we have nothing. A number you have not validated is one we can neither use
  nor check, and it inherits the worst property of both sides.
- **Anything with an unstated convention.** This is the failure mode that costs the most and shows the
  least. A discount factor with no interpolation method named is not a discount factor, it is a hint.

---

## 4. The loop that looks circular and is not

We publish the extract. You derive curves and valuations from it. We consume them to gate bookings. Read
quickly, that is a cycle with no base case.

It is not, because of the **one-session lag**: today's bookings are gated by numbers derived from
yesterday's close. That is exactly how real systems are laid out, with EOD marks feeding next-day risk.
But it is worth stating in the design, because the first person to notice the loop will assume it is a
mistake and try to fix it.

The practical consequence: every artifact you send us must carry the `sessionDate` and
`priceSnapshotVersion` it was **derived from**, not the wall-clock time it was computed. That is what
makes the lag legible instead of invisible, and it is what lets us refuse a value that is too old.

---

## 5. If you only do one thing

**DV01 per contract.**

Curves unblock an instrument class nobody has asked to trade. VaR is your best output but belongs on a
path with weaker requirements. NPV is the biggest structural unlock but comes with a policy question
about the credit gate that we should settle before wiring.

DV01 fixes a measure that is **wrong today**, on instruments we **already book**, using capability you
**already have**. It is the shortest path from your engine to a defensible claim about ours.

---

## 6. Questions back to you

1. **Can you produce a per-`contractId` DV01 stamped to a session**, and in which currency?
2. **Is your NPV closed-form** (discounted cashflows) or simulation-derived? Rule 2 turns on this.
3. **What is your model/calibration version identifier**, so a limit breach can be attributed to a
   position change rather than a recalibration?
4. **Would you rather send forward rates or curves?** We prefer forwards, for the reason in 2.3, but you
   own the derivation.
5. **What validity window should each artifact carry**, after which we refuse rather than use it?

---

## Appendix: where these would land in our tree

| Thing | Path (YU17, the operative layer) |
|---|---|
| The sequenced-input precedent to copy | `specs/YU17-otc-rates/.../lmax/InputEvent.java` (`TYPE_FX_RATE`) |
| Apply and snapshot pattern | `specs/YU17-otc-rates/.../cluster/MatchingEngineClusteredService.java` (`T_FX_RATE`, format 7) |
| Control-plane ingress pattern | `specs/YU17-otc-rates/.../cluster/ClusterGatewayMain.java` (`POST /risk/control/fxrate`) |
| The notional-based credit gate 2.1 would improve | `specs/YU17-otc-rates/.../risk/BlpRiskState.java` |
| Contract identifiers and netting columns | `specs/YU17-otc-rates/.../cluster/SwapContractCsv.java` |
| The deferred FX-forward scope | `issues/open/HANDOFF-fx-instrument-class.md` |
| The outbound direction | `docs/handoff/INTEGRATION-jax-risk-engine-2026-08-17.md` |
