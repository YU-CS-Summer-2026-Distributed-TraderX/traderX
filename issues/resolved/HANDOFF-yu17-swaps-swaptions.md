# Handoff — YU17: interest-rate swaps and swaptions

**New state**, cut from `YU16-cdm-instruments`. Branch + worktree `YU17-otc-rates`, spec pack at
`specs/YU17-otc-rates/`. Read `.claude/skills/new-yu-state` before scaffolding.

This is the first instrument class that does **not** fit the position model, and the first that does
**not** match. Both facts are the state, not obstacles to it.

---

## 1. Why this is different from YU16

YU16 added bonds and ETFs and needed one engine change (ADR-060's derived price grid). It worked
because a Treasury fits our shape: the instrument exists in reference data *before* any trade, and
many trades net into one position. Swaps break that in three places.

**1. The trade creates the instrument.** A 5Y swap traded today matures 2031-08-13; traded next
month, 2031-09-13. Identity includes the trade date and the agreed rate. Our `security` is a ticker
that pre-exists the trade.

**2. Netting is lossy — this is the important one.** Our position is
`(accountId, security) → (signed quantity, averageCostBasis, multiplier)`. For anything fungible
that is exact: buy 100 AAPL @ 150 and 100 @ 160, hold 200 @ 155, and mark-to-market is right. Now
the same arithmetic on swaps:

> Receive fixed 4.2% on 10mm, then pay fixed 4.3% on 10mm. Net quantity **zero**, average rate
> meaningless, position gone. Economically you are locked into paying 10bp on 10mm for five years —
> roughly 10k/year, ~50k undiscounted. Netting **deleted a real position.**

For a fungible instrument the price is what you paid. For a swap **the rate is what the contract
is**. It breaks in the same-direction case too: receive-fixed 4.2% 5Y and receive-fixed 4.2% 3Y have
the same rate and direction and still cannot be averaged.

Listed options are the useful contrast, and the reason they fit: an OCC symbol encodes underlying,
expiry, strike and type, so `AAPL 250117C00150000` is a fixed identity that pre-exists the trade and
genuinely nets.

**3. There is no order book.** Swaps trade OTC/RFQ, bilaterally. There is no CLOB to build that
corresponds to anything real.

---

## 2. Decisions already taken — do not relitigate these

**D1 — Swap bookings are SEQUENCED THROUGH CONSENSUS.** They need no matching, but they go through
the replicated log as a booking command the clustered service applies. This is the load-bearing
decision. The EOD extract's own header claims every row is *"the replicated state machine's state at
consensusSequence on the totally-ordered consensus log, not a read-model query."* Booking swaps
straight into trade-processor and the DB would put them outside the cut and make that line false —
quietly retiring the strongest claim YU15 made. Sequencing keeps deterministic replay, byte-identical
rendering across all three members, the quiescence witness, and reproducibility from the stored cut.

It is also the better story: the architecture absorbs an instrument class that cannot match, without
giving up determinism.

**D2 — No matching, no book, no price grid for swaps.** No swap CLOB. The booking command creates a
contract; it never crosses.

**D3 — Two artifacts from one cut, one stamp.** The netted position extract stays exactly as it is
(schema 3). Swaps get a **second, per-trade artifact** rendered from the same cut at the same
consensus sequence, with the same `cutSha256`. Netting is *correct* for equities, ETFs, bonds and
options — do not give it up to accommodate the one class that cannot net. A single polymorphic file
was rejected: it would force every consumer to branch on `instrumentType` before reading a column,
and the "non-bond rows carry empty bond columns" convention does not stretch to a row that shares
almost nothing.

**D4 — Swaps do NOT get symbol-table entries.** `MAX_SECURITIES` is 1024 and every swap trade is a
new identity — a swap book would exhaust it in an afternoon, and a book with no resting orders is
dead weight in the snapshot. Contracts get deterministic epoch-qualified ids, the way order refs
already do.

**D5 — We publish TERMS and observed rates. Alex values.** No NPV, no discounting, no curve in our
extract. We are authoritative for what was booked and what the market was; he is authoritative for
what it is worth. If we start pricing swaps we are duplicating his half of the boundary rather than
integrating with it.

**D6 — No contract lifecycle in this state.** No resets, no coupon payments, no accrual over time,
no cash-flow generation. Book the contract, carry the terms. Lifecycle is a later state if it is
ever wanted.

---

## 3. The design that fits the existing constraints

Verify each of these against the code before committing to them; they are the shape that appears to
fit, not measurements.

### The booking command

`InputEvent` types run to 11 (`TYPE_ORDER_REPLACE`), so `TYPE_SWAP_BOOK = 12`. YU13 established that
a new type can ride SBE template 1 without a new template — `AeronReplicationCodec` copies
`commandType` through without interpreting it.

The constraint is that `InputEvent`'s fields are fixed and few: `accountId, side, qty, limitPx,
priceTicks, securityId, orderRef, eventTimeMillis`. A swap needs notional, fixed rate, pay/receive,
effective date, maturity, payment frequency, day count and float index — more than there are slots.
The shape that fits without a new template:

| Swap field | Rides |
|---|---|
| pay/receive fixed | `side` |
| notional | `qty` (int — caps near 2.1bn, adequate; check before assuming) |
| fixed rate | `limitPx` (already a 1e6 fixed-point long) |
| effective + maturity date | `priceTicks` — two 32-bit epoch-days packed into the long |
| float index + frequency + day count | `securityId` → an index into a **conventions table** |

That last row is the trick worth taking seriously. The variable economics of a vanilla fixed-float
IRS are a small enum in practice (SOFR/3M/ACT-360, etc.). Putting conventions in a table and passing
an index keeps the per-trade payload inside the existing slots — and it follows the pattern this line
already uses twice: YU14 derives the option multiplier from the ticker (ADR-052) and YU16 derives the
bond grid from the ticker prefix (ADR-060), both rather than storing anything.

If it genuinely does not fit, a new SBE template is legitimate — but note template 8 is already
YU15's `RiskExtractMessage`, so claim an id deliberately.

### State and snapshot

A contract store keyed by contract id, holding terms + booking account. `SNAPSHOT_FORMAT` goes
**4 → 5** with a new record type `T_CONTRACT = 12` (types currently run 1–11). Keep
`MIN_READABLE_SNAPSHOT_FORMAT` at 3 so a format-4 snapshot still restores — the forward-roll path
exists and is proven; the message on a newer-than-build snapshot deliberately says *"roll the members
FORWARD; do not wipe the epoch."*

### The risk gate — the one place engine work is genuinely needed

The gate computes `notional = qty × px × multiplier`. For a swap that formula is simply wrong: the
notional is the notional, and the rate is not a price. This needs either an explicit swap path in
`BlpRiskState` or a documented, deliberate bypass with a stated reason.

Treat it with the deterministic-core discipline: read `.claude/skills/prove-cluster-engine-change`,
and remember a core change **cannot be rolled gradually** — a mixed-version window diverges the
members permanently. Safe roll is scale to zero, wipe the PVCs, fresh epoch.

### The extract

A second rendered artifact from the same cut. Announce both in `risk.extract.ready` with both
hashes, sharing `consensusSequence`, `sessionDate` and `cutSha256`. Per-contract columns:
contract id, account, pay/receive, notional, fixed rate, float index, effective date, maturity,
frequency, day count, currency, counterparty, netting set. **No NPV, no mark** (D5) — the observed
par rate for the tenor is legitimate if the publisher produces one; a valuation is not.

---

## 4. Suggested phasing

1. **Vanilla fixed-float IRS, booked and extracted.** Command, contract store, snapshot bump, second
   artifact, risk-gate decision. This is the whole architectural claim; everything else is breadth.
2. **Swaptions.** Option on a swap: underlying swap terms + strike rate + expiry + payer/receiver +
   exercise style. Same per-trade contract path with the underlying terms embedded. Decide early
   whether exercise is modelled at all — under D6 it probably is not, and a swaption is then a
   contract record like any other.
3. Stop. Resist lifecycle.

---

## 5. Out of scope, explicitly

- Valuation of any kind (D5). No curve, no discounting, no NPV, no Greeks.
- Contract lifecycle (D6).
- A swap CLOB or any matching path for swaps (D2).
- Changing the netted position extract's schema (D3) — it stays at 3.
- Anything in an ancestor spec layer. Layers compose last-wins; an edit to a layer YU17 overrides is
  silently inert.

---

## 6. Traps this project has already paid for

- **Manifests are copies, not overlays.** Both within a state (`cluster/` vs `cluster/gke/`) and
  across states (a state's `kubernetes/` directory is a full `cp -R`, never an overlay on its
  parent's). Two separate incidents on 2026-08-13, one of which left the GKE tier without a fix that
  had "landed".
- **Check for shadowing before editing.** `grep` every `specs/*/` layer for a same-named file and
  patch the operative (**last**) one.
- **A proof's timeouts, waits and string matches are claims about the ENVIRONMENT**, and passing
  gives them no falsification. Four such assumptions were true on kind and false on GKE in one week.
  See `.claude/skills/port-proofs-to-another-tier` §6.
- **Write proofs that can fail.** Every new assertion carries a negative control. Read
  `.claude/skills/vacuous-pass-audit`, and `scripts/proofs/yu16-bond-position.sh` for the house shape.
- **The rig can be a commit behind its own tree.** Verify the running image carries the code you are
  testing — read a marker string out of the built class if you have to. A proof asserting
  reproducibility cannot tell you it ran against a stale build.

---

## 7. Definition of done

1. `bash pipeline/generate-state.sh YU17-otc-rates` exits 0; the pack passes `spec-pack-audit`.
2. A swap booked through the gateway is **sequenced**, and all three members agree on the contract
   store — same hash, same applied sequence.
3. A member destroyed to an empty disk rebuilds and reproduces the contract store byte-identically
   (the format-5 snapshot restores, and a format-4 snapshot still restores).
4. The EOD extract emits **both** artifacts at one consensus sequence, each byte-reproducible from
   the stored cut alone, and the netted artifact is **unchanged** for equities/bonds/options.
5. The receiver-4.2% / payer-4.3% case is a **proof**: both legs booked, and the contracts artifact
   shows two contracts — demonstrating the position that netting would have deleted. That single
   assertion is the state's headline; write it first and let it drive the design.
6. Every YU16 proof still passes. A swap must change nothing for the instruments that already work.

## 8. Conventions

- **Never `git push`.** Commit with real messages; yaakov pushes.
- **No `Co-Authored-By`** trailer, no "Generated with…" line — including from subagents.
- Stage explicit paths, never `git add -A`; these worktrees are shared.
- Root-level `HANDOFF-*.md` stays untracked; durable project facts go in tracked `issues/`.
- Jack is adding instrument breadth (bills, STRIPS, corporates, an accurate bond model) on a branch
  off YU16 — see `HANDOFF-jack-instrument-breadth.md`. Nothing here should depend on that work, and
  nothing there anticipates this.
