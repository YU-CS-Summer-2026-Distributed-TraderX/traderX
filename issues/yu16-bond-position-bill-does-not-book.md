# `yu16-bond-position` step 6: the zero-coupon bill does not book

**Filed** 2026-08-18 by the coordinator, from a full-suite run.
**RESOLVED the same day** — root cause was NOT in the bond path at all. See
`catalog-additions-never-reach-a-deployed-environment.md`, which carries the durable defect.

## The failure

Full suite on `traderx/cluster-node:yu17-fx`, 25 passed / 1 skipped / 2 failed. This is the only
product-side failure; the other (`yu13-otel-trace-join`) was an environment refusal, since fixed.

```
=== 6. a ZERO-COUPON bill trades the same way, and stays held for the accrual proof ===
[FAIL] buyer holds '' of UST-BILL-20270812, expected 100000 — the bill did not book
```

Steps 0 through 5 all passed, **including step 5's negative control**, so the coupon-bearing Treasury
path works end to end on this build: the cross books, the fill reaches SQL with the fraction intact,
and the position values as face x fraction with the multiplier at 1.

## What is already known, and what is NOT

**Known: both orders were accepted.** Step 6 fails at the position query, not at the order post. The
script fails with a different and very explicit message if either side returns non-200 ("a legal bill
order was refused... the ADR-060 grid did NOT extend to this ticker"). That message did not fire, so
the orders returned 200 and the failure is downstream of admission.

**Known: `yu16-accrued-interest` passed anyway**, immediately afterwards. It refuses on an extract with
no zero-coupon row, so its passing is worth understanding — either it found a zero-coupon row from
another source, or its refusal condition is narrower than the comment in `run-proofs.sh` implies. That
is a second question this investigation should answer, and it may be the more interesting one.

**NOT known: whether anything crossed.** The proof waits with a fixed `sleep 6` before querying SQL,
so a slow projection and a genuine non-cross look identical from the log.

**A post-hoc query is worthless — do not repeat the coordinator's mistake here.** After this failure
the suite minted **two fresh epochs** (`[stp-prep] ... fresh epoch minted ON traderx/cluster-node:yu15-pre`,
then `[stp-prep] restoring traderx/cluster-node:yu17-fx at a fresh epoch`). The engine state and the
projection from the moment of failure are gone. Querying `positions`/`trades` for the bill now returns
empty, and that is fully explained by the wipes rather than by anything the bill did.

## How to characterise it

1. **Run it standalone on the current epoch** and see whether it reproduces at all. It may be a
   fixed-`sleep 6` flake on a box still settling after a host reboot — the run happened shortly after
   one, with services showing 1 to 9 restarts.
2. If it reproduces, the discriminating question is **where it stops**: order accepted at the gateway
   (200, already established) → sequenced into the log → crossed by the engine → egress → projection
   into SQL. Read the members' `/health` trade counter directly rather than SQL, since SQL is the last
   link and the one the proof already told us about.
3. **Contrast with the coupon-bearing Treasury in steps 2-4, which works on this same build.** The
   difference is the instrument, so ADR-060's claim — that the fine book grid derives from the ticker
   prefix alone, which is what let bills be added with no engine change — is the specific thing under
   test. `UST-BILL-*` inheriting the grid "for free" is exactly the assumption step 6 exists to check.

## What it is probably NOT

The FX credit-gate change on this build converts notionals to USD **only in `onSwapBook`**; bonds and
bills do not take that path, and `BlpRiskState` was not modified. A bill is USD in any case. Rule that
in or out early, but do not start there.

## Provenance

`/tmp/proofrun/yu16-bond-position.log` from the 2026-08-18 suite run (that path is overwritten by the
next run of the same proof — copy it before re-running).

---

## Resolution, 2026-08-18

**The title of this issue is wrong, and being wrong about it cost the first hour.** The bill *did*
book: two trade rows in SQL at the correct price. What failed was the **position** write.

Root cause: `UST-BILL-20270812` was absent from the `stocks` table, which is what
`reference-data` uses for membership. `trade-processor` then correctly failed closed
(`Bond reference metadata is unavailable`). The instrument had been added to the CDM catalog after
the rig's DB was first seeded, and the seeder only runs when `stocks` is empty — so it never
appeared. Full account, including the rig repair through `POST /stocks` and why a raw SQL insert
would have been wrong (the seeder writes an outbox row per seed), in the linked issue.

`yu16-bond-position` now passes in full, including step 7, which had never been reached.

### Two method errors in the investigation, kept because both are recurring shapes

1. **A post-hoc SQL query was used as evidence and proved nothing** — two fresh epochs had been minted
   after the failure, so the empty result was fully explained by the wipes. This file warned against
   exactly that before it happened, in its own "how to characterise it" section.
2. **A staleness theory survived three checks it should not have.** The reference-data image was
   inspected, then the pod's digest, then the compiled `dist` — each time looking for a build that
   predated the bills. The bills were present at every layer. The actual answer was a **runtime
   membership filter**, which was the first hypothesis and was abandoned too early. Asking the running
   process what it held (`node -e` against its own `dist`) settled it in one call and should have been
   the second step, not the seventh.
