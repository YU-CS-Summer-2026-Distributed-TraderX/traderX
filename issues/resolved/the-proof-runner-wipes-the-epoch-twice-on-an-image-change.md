# `run-proofs.sh` wipes the epoch twice on an image change

**Measured 2026-08-25** during the format-8 mint, three times in a row (two detonator epochs and the
real one). Harmless and self-healing; filed because it doubles the downtime of every build change
and the cause is one missing assignment. **Fixed 2026-08-25** — see *Resolution*.

## What happens

`scripts/yu15/run-proofs.sh`, the baseline-pin block:

```bash
NEED_FRESH=0
if [[ "$(current_image)" != "${BASELINE_IMAGE}" ]]; then
  echo "[baseline] cluster is on $(current_image); rebuilding on ${BASELINE_IMAGE} at a fresh epoch"
  rebuild_fresh_epoch "${BASELINE_IMAGE}"      # <-- wipes, but NEED_FRESH stays 0
  echo "[baseline] cluster now on ${BASELINE_IMAGE}, fresh epoch"
fi
```

`NEED_FRESH` is never set to `1` here. So the projection-staleness check below it still runs, and on
the brand-new epoch it necessarily fires — the engine's `tradeCounter` is 0 while SQL still holds the
previous epoch's trade ids:

```
[baseline] cluster is on traderx/cluster-node:yu17-markwait2; rebuilding on ...:yu17-format8-detonator at a fresh epoch
[baseline] cluster now on traderx/cluster-node:yu17-format8-detonator, fresh epoch
[baseline] gateway is on ...; repinning
[epoch] engine tradeCounter 0 < highest trade id in SQL 3627126: projection is from a dead epoch; rebuilding
```

and `rebuild_fresh_epoch` runs a **second** time: scale to 0, wait for delete, wipe the PVCs, scale
to 3, await rollout, restart the gateway. The check is correct — the projection genuinely *is* from a
dead epoch — it is just answering a question the block above already resolved.

## Why it is not merely cosmetic

The second wipe is a full member roll: two `rollout status` waits with 600s timeouts, plus the
gateway restart. On this rig it is a few minutes of avoidable downtime on every image change, and an
image change is exactly when someone is watching the log for a signal. It also makes the log read as
though something went wrong between the two lines, which is the opposite of what happened.

## The fix

Set `NEED_FRESH=1` in the baseline block, beside the `rebuild_fresh_epoch` call — the same thing the
projection-staleness branch and the symbol-table branch both do. The `if [[ "${NEED_FRESH}" == "1" ]]`
block that clears and reseeds the projection afterwards is already the right follow-up and would
still run exactly once.

Not done here: this is proof-runner behaviour, not format-8 mint scope, and the runner is shared by
every lane. Deliberately left for a chip that can re-run a suite to confirm the clear-and-reseed
still happens exactly once afterwards.


## Resolution

`NEED_FRESH=1` set in the baseline block beside the `rebuild_fresh_epoch` call, with the reasoning
recorded at the line.

### Verified on the rig, 2026-08-25

The condition the issue describes had to be genuinely present for the run to prove anything, so it
was made present: the members were pinned to `traderx/cluster-node:yu17-format8-armcheck` (a second
tag on byte-identical bits) while the manifests declared `:yu17-format8`, and the suite was run
with `ALLOW_IMAGE_CHANGE=1`. Before the run, the staleness check's other operand was recorded:

```
SQL max trade id before the run: 6
```

so after the mint the check's predicate — engine `tradeCounter` 0 (fresh epoch) `<` SQL max 6 —
was **true**, and the pre-fix code would necessarily have called `rebuild_fresh_epoch` a second
time. The run's own log:

```
[baseline] cluster is on traderx/cluster-node:yu17-format8-armcheck; rebuilding on ...:yu17-format8 at a fresh epoch
[epoch] cluster sequenced a write 0s after the roll
[epoch] feed adapter sequencing: 69 symbols round-tripped through consensus (20s)
[baseline] cluster now on traderx/cluster-node:yu17-format8, fresh epoch
[epoch] projection cleared and reseeded for this epoch
```

One wipe. No `projection is from a dead epoch; rebuilding` line, and the clear-and-reseed the issue
asked to keep happening ran **exactly once** — which was the open question that made this a chip
rather than a footnote.

### What the assignment can still get wrong

`NEED_FRESH=1` suppresses three checks that follow it — the book-divergence comparison, the
projection-staleness check and the symbol-table probe. All three are answering "is the epoch this
runner INHERITED usable?", and on this path the runner has just minted the epoch itself, so all
three are answered by construction. The one it does not suppress is the `if NEED_FRESH == 1` block
that clears and reseeds the projection, which is the follow-up a fresh epoch actually needs; that
is what the run above confirms still fires.

### The full suite, 2026-08-25

`DESTRUCTIVE=1 bash scripts/yu15/run-proofs.sh` on `kind-traderx-yu12-cluster` /
`traderx/cluster-node:yu17-format8`: **38 passed, 0 skipped, 1 failed**. The one failure is
`yu17-session-opens-from-close`, a proof landed by a concurrent chip an hour earlier whose
`/eod/session/previous` route is committed in source but not yet on the running trade-processor
image — it fails at step 1 with a 404, has nothing to do with the epoch procedure, and is not this
chip's to make green. Everything the procedure change touches ran and passed, including the whole
rolling tail (`yu13-stp-and-replace`, `yu13-cancel-ingress`, `yu16-book-grid`,
`yu16-liveness-restarts-wedge`, `yu17-halt-survives-failover`, `yu17-closed-survives-restart`).
