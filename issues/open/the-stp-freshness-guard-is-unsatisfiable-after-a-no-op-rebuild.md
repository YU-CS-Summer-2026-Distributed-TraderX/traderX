# The stp freshness guard cannot be satisfied by rebuilding, when the rebuild is a no-op

> A record, not a rig you can query.

**Filed 2026-08-23**, reported by the feed-adapter lane after it cost them two cycles — once from a
peer's regenerate, once from their own copy into the tree.

## The trap

`scripts/proofs/yu13-stp-and-replace.sh` guards against running against a stale image by comparing
source mtime against the docker image's `Created` timestamp. Sound in principle: an image older than
the source it claims to be built from is a real hazard, and this project has been bitten by exactly
that.

**But docker's build cache defeats it.** A byte-identical rebuild produces the *same image ID* and
therefore the *same* `Created` timestamp. So when a regenerate touches the tree without changing
content — which is the common case — the mtime advances, the image's `Created` does not, and the
guard fails.

**The remedy the guard implies is "rebuild, it's cheap". That remedy cannot clear it.** The guard is
unsatisfiable by the action it asks for, which is worse than a guard that simply fires: the operator
does the prescribed thing, watches it not work, and has no signal telling them why.

One-off unblock used: `--no-cache` on the affected half.

## Why it is worth fixing rather than remembering

It fires on a *no-op*, so it fires most often when nothing is wrong. A guard whose false-positive rate
is highest on the healthy path teaches people to bypass it, and a bypassed guard is the wedge this
whole family of checks exists to prevent.

## Directions

1. **Compare content, not timestamps** — hash the source that goes into the image and record it as an
   image label; compare label to hash. Correct, and it makes a no-op rebuild trivially satisfying.
2. **Cache-bust in the build script** — cheapest, and it makes every rebuild genuinely expensive,
   which is the wrong incentive on a rig where image size already caused a disk exhaustion.
3. **Report the ambiguity instead of failing** — say "image is byte-identical to a previous build, so
   its Created predates your source; this is either fine or stale and this check cannot tell". Honest,
   and leaves the judgement with the reader.

Direction 1 is the only one that makes the check *mean* something. The others make it quieter.

## Related

- `.claude/skills/vacuous-pass-audit` — the inverse case is well covered there; this is a guard that
  cannot *pass*, and the same reasoning applies to why that is a defect.
- The disk-exhaustion incident of 2026-08-22/23, which is why direction 2 is unattractive.
