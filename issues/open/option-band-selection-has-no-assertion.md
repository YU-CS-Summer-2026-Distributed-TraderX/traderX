# The option/equity band selection in the EOD quality gate has no assertion

**Filed** 2026-08-17, by the coordinator, verified against the tree.
**Status** open. Small, deterministic, needs a rig only to run the existing proof.

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

Until `f379497b` / `97b03d70` / `d3fd70b3` (2026-08-17), `yu15-risk-extract.sh` asserted
`CLOSE_OPTIONS_OK == CLOSE_OPTIONS` — every option priced clean. An option wrongly demoted to the
20% equity band would flag SPIKE on an ordinary move, and that assertion would fail. That was the
only end-to-end detector of a band-selection regression.

It was removed correctly. It detected the bug only *incidentally* and only *nondeterministically*:
the option marks come off a simulated random-walk underlying, and genuine excursions clear even the
200% band on roughly one close in five (measured 2026-08-17). The assertion was a flake whose
detection value was an accident of what else it happened to catch. Keeping it to preserve that
accident would have been keeping a 4-of-5 failure.

**But the coverage did leave, and the ledger should say so.** After the patch a flagged option is
overridden at its own observed close and published, so a demoted option now *passes*.

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

**When you delete an assertion, name what it was covering before you decide the deletion is neutral.**
The lane that landed the patch stated the removal and its rationale in the code comment, which is why
this was findable at all. What was missing was the second step: an assertion's *stated* subject
("all options priced clean") is not the same as its *effective* failure set, and the coverage that
leaves with it is usually the incidental part, not the stated part.
