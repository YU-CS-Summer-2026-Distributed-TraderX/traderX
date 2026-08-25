# `run-proofs.sh` wipes the epoch twice on an image change

**Measured 2026-08-25** during the format-8 mint, three times in a row (two detonator epochs and the
real one). Harmless and self-healing; filed because it doubles the downtime of every build change
and the cause is one missing assignment.

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
