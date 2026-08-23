# yu05-recon's forward-sweep control cannot pass on a rig whose members have been rolled

**Found 2026-08-23** while verifying an unrelated change (the OTC regulatory projection). Confirmed
**pre-existing** — it reproduces identically on the untouched `:yu17-jsrebind` image.

## Symptom

`scripts/proofs/yu05-recon.sh` fails its planted-mismatch negative control:

```
matched                        2 (fresh classification)
planted field mismatch         row 1-S qty 25 -> 26
✘ a PLANTED persistent mismatch was NOT caught by a fresh classification
```

and, when the window is empty, the louder form:

```
matched                        0 (fresh classification)
✘ the sweep classified 0 trades — matched=0 is a clean reconciliation of NOTHING
```

## Cause — a hidden dependency on rig history, not on the code under test

The forward sweep classifies the member's **live forward blotter** against the SQL projection. That
blotter is a bounded window that is deliberately **not in the snapshot**: a restarted member rebuilds
it from the log tail it replays (documented in `ClusterRecon`'s own javadoc, and correct).

The control plants its mismatch on `SELECT id FROM trades ORDER BY id LIMIT 1` — the **oldest** row
in the projection. After any member restart the window holds only trades crossed *since* that
restart, so the oldest row is structurally outside it. The sweep cannot see the planted row, reports
`fieldMismatch=0`, and the proof correctly refuses to trust its own clean verdict.

Measured on the rig: projection held `1-S … 8-B`; the blotter held `['7-S','8-B']`. Crossing fresh
trades does not help — it only adds newer ids, and `LIMIT 1` still picks `1-S`.

So the proof silently requires **a rig whose members have not restarted since the epoch's first
trade**. Nothing says so, and the failure reads as a reconciliation defect.

## What is NOT affected

Both other arms pass, on both images, throughout:

- full-log replay reproduces the live engine's trade population ✔
- both members replay their own archive to the same history ✔
- the orphan sweep: all projection rows have journal provenance, and a planted projection-only row IS
  named `ORPHAN_IN_PROJECTION` ✔

Those are the arms served by `reindexFullHistory`, so this is specifically the live-window arm.

## Discriminated, not assumed

Ran the proof on the new image (fails), rolled the StatefulSet back to `:yu17-jsrebind` and ran it
again — **fails the same step, worse** (`matched 0`, because the roll-back restart emptied the window
outright). The image is not the variable; the restart is.

## Fix directions (none taken)

- Plant on a row the window actually holds — e.g. the newest id, or intersect the projection with the
  blotter page and plant on the intersection. Keeps the control discriminating without assuming rig
  history.
- Or cross a trade first and plant on THAT trade's id, so the control creates its own subject.
- Either way the proof should **say** when the window does not span the projection, instead of
  letting a structural precondition surface as a failed verdict.

Belongs to the same family as [[the-suite-gate-cannot-tell-reduced-from-complete]]: a check whose
scope silently narrows and whose output does not admit it.
