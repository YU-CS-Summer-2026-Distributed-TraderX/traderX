# The option/equity band selection in the EOD quality gate has no assertion

**Filed** 2026-08-17, by the coordinator, verified against the tree.
**Status: RESOLVED 2026-08-17**, same day, by the lane whose patch retired the detector. Step 6 of
`yu06-quality-gate.sh` now asserts band selection end-to-end, deterministically, exactly as specified
below — with one design addition: the control needs **no knowledge of current prices**. A planted
prior of 100000 makes any live mark a ~99.9% move (`|x−P|/P → 1` for `P ≫ x`), inside the option
band and past the equity band by construction. The prior lives in a sparse extra PUBLISHED version
of *yesterday's* session carrying only the two control rows — `priorPublishedClose` takes
strictly-earlier dates, latest version first, *per security* (`EodPriceSnapshotRepository.java:110`)
— so no other instrument's baseline moves; it is deleted inline and in the EXIT trap, and an
unplant-verify close proves the baseline did not leak. Run green on the kind rig 2026-08-17:
`v19 against the planted prior 100000: AAPL=SPIKE AAPL260918C00240000=OK`, then `v20 AAPL=OK` after
the unplant. Landed on YU15, carried to YU16/YU17 byte-identically; not carried
below YU15, where the checker has one band. The OccSymbols contamination item this detector guards
remains open — but it has a detector again: a demoted option flags SPIKE at ~99.9% and step 6 fails.

**Hardened same day, per the coordinator's kill-safety review** (YU15 `8a3ce3ce`, carries `0b75ceb9`/
`e08ac232`, md5 `1229c36b…` on all three): the proof now **unplants before planting**, by signature
(control securities at exactly 100000, any earlier date), unconditionally at step 0 — an EXIT trap
does not fire on SIGKILL or a dead kubectl session, and a surviving 100000 PUBLISHED prior would
flag the controls SPIKE on every later close, presenting as a product regression. The pre-clean is
the only cleanup that survives the run that was not tidy. Two scope notes now live in the comments:
the planted prior's load-bearing property is the **bound** (`|x−P|/P < 1` for `P ≫ x`), not the
magnitude; and the option leg proves band **selection** (wider than ~100%), not the 200 constant —
pinning the value is `EodQualityCheckerTest`'s job. Re-run green after hardening: v23/v24.

**Unified and kill-tested same day** (YU15 `609286bb`, carries `d9942a6f`/`80fdd45d`, md5
`742d6b00…`): the trap and inline deletes were still version-scoped to `CTL_V`, so strata from
killed runs would accumulate — each dead run's leftover surviving every later run's tidy. All three
cleanup sites now share one `unplant()`. Verified twice, escalating: first against hand-planted
sentinel strata on two dates plus a rowless header (green, audit 0/0); then — per the coordinator's
DECIMAL-equality concern, since a hand INSERT proves only that the predicate matches its own literal
— against a **genuine dead run**: the proof launched, SIGKILLed the moment its own INSERT path
planted (trap never fired, `EOD_UNIVERSE` left dirty), stored bytes audited as `100000.000000` with
the equality predicate matching exactly 1 row. The recovery run was green end-to-end (v29–v33), the
post-audit shows 0 sentinel rows and 0 rowless headers, the controls' **real** prior closes one
version below the poison (AAPL 246.195, option 11.335 at 2026-08-16 v1) survived untouched — the
rows a security-scoped delete would have destroyed — and the recovery run's own trap reset the
universe env the kill had left dirty. Every arm of the failure mode is now exercised, including the
one a green run on a clean rig cannot reach.

## What is unasserted

`EodQualityChecker` picks the tolerance band by instrument class:

```java
// specs/YU15-eod-risk-extract/generation/runtime-overrides/trade-processor/
//   src/main/java/finos/traderx/tradeprocessor/service/EodQualityChecker.java:62
return OccSymbols.isOption(security) ? maxMovePctOption : maxMovePct;
```

`max-move-pct` defaults to 20, `max-move-pct-option` to 200. **Nothing in the proof suite asserts
which band an instrument gets.** Grepped `scripts/proofs/`: no reference to `max-move` or `maxMove`
at all. `yu06-quality-gate.sh` proves the gate *mechanism* thoroughly and deterministically — flagged
close lands DRAFT, publish is refused 409, override mints a new version, that version publishes, and
the flagged version stays immutable — but it drives that with a **MISSING** price on a synthetic
`QLTY` instrument. MISSING never consults a band. The branch above is executed on every close and
asserted by nothing.

## How the last detector was removed, legitimately

**Corrected 2026-08-17, same day, after the implementing lane's failure-set analysis. The conclusion
stands; the mechanism in the first version of this file was wrong, and the correction is instructive.**

I originally named the deleted `CLOSE_OPTIONS_OK == CLOSE_OPTIONS` line as the lost detector. It was
not. Verified against the pre-patch file (`f379497b^:scripts/proofs/yu15-risk-extract.sh`):

```
66  [[ "${CLOSE_STATUS}" == "PUBLISHED" ]] || fail ...     <- the detector
69  [[ "${CLOSE_OPTIONS_OK}" == "${CLOSE_OPTIONS}" ]] || fail ...
```

The options-OK line sat **below** the PUBLISHED assertion. The gate publishes only at
`flaggedCount == 0`, and flagged is `STALE|SPIKE|MISSING`, so on any published session every quality
is already in `{OK, OVERRIDDEN}`. The deleted line's entire *reachable* failure set was therefore
"a published session containing an OVERRIDDEN option" — the legitimate state ADR-026's remedy
produces. It was a latent false alarm waiting for the first operator override, and deleting it was
strictly right.

**The detector was line 66 — and the patch did not delete it, it converted it.** Pre-patch, an option
demoted to the 20% equity band flags SPIKE on an ordinary move, the session stays DRAFT, and the
proof *fails there*. Post-patch, the same DRAFT is no longer a failure: it is the trigger to override
each flagged mark at its observed close, republish, and continue. The demoted option now **passes**.

The conversion was still correct. That detection was *incidental* and *nondeterministic* — option
marks come off a simulated random-walk underlying and genuine excursions clear even the 200% band on
roughly one close in five (measured 2026-08-17). It was a 4-of-5 flake whose detection value was an
accident of what else it happened to catch. Keeping a flake to preserve an accident is not coverage.

**But the coverage did leave, and the ledger should say so.**

## Why this matters more than it looks

There is an **already-open item** stating that `OccSymbols.isOption` silently demotes a contaminated
symbol to the equity band. That is exactly the defect the removed assertion would have caught, and
after this patch nothing in the suite would catch it. An open defect whose only detector was just
retired is the shape that gets rediscovered in production.

## The fix (deterministic, no random walk)

Extend `yu06-quality-gate.sh` with a control pair, in the proof whose subject is the gate:

1. **Positive**: an OCC option symbol moved between 20% and 200% must **not** flag. Fails if the
   option band is not selected, or if `max-move-pct-option` is not in force.
2. **Negative**: an equity moved past 20% must flag. Fails if the band is widened for everything,
   which is what would make the positive control pass vacuously.

Neither needs the price-publisher's random walk — `yu06-quality-gate.sh` already sets `EOD_UNIVERSE`
to a synthetic universe and writes prices directly, which is why it is the deterministic one. It is
already in the `PROOFS` array in `scripts/yu15/run-proofs.sh:41`, so the coverage lands in the
suite that runs, not beside it.

Land on YU15 (where the option band is introduced) and carry to YU16/YU17 per the lineage rule.
`yu06-quality-gate.sh` also exists on YU06–YU14, where `EodQualityChecker` has **no** option band at
all (`specs/YU06-…/EodQualityChecker.java:57` compares against the single `maxMovePct`), so the
control pair is a YU15-and-later addition. Do not carry it below YU15; there is nothing there to
assert.

## The general rule this is an instance of

**When you change an assertion, name what it was covering before deciding the change is neutral —
and check the assertions you CONVERT, not only the ones you delete.**

Both parties got half of this. I named the deleted line and never checked that it was reachable. The
implementing lane analysed the deleted line rigorously and correctly, and stopped there — the loss
was one line above, in the check the patch turned from a failure into a remedy. A converted assertion
is the easier one to miss precisely because it is still present in the file and still mentions the
same condition.

The durable form: an assertion's *stated* subject is not its *effective* failure set. Compute the
reachable set (what can actually arrive at this line, given everything above it), and do it for every
assertion the patch touches — including the ones that survive in altered form.
