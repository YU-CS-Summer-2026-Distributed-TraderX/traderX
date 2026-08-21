# A fresh epoch silently drops whole instrument classes from the tradeable set

> **The values below are a record, not a rig you can query.** Order refs, trade ids, trace ids,
> security ids, pod names and run counts come from the epoch they were measured on. Those epochs
> have been rolled and will be rolled again — order refs restart at 1, the symbol table is
> renumbered. Read them as a worked example of the SHAPE. Do not look them up, and do not treat
> their absence on a current rig as evidence about this issue.

**Filed** 2026-08-19 by the coordinator, from the UI lane's finding, verified.
**RESOLVED 2026-08-21** for the listed-option class, both mechanisms, proved on the cluster rig by
an A/B across two fresh epochs. Not a code defect — a bring-up gap with a demo-shaped consequence.

The gap this file was opened about is closed. Two things it also carried are NOT, and were lifted
into their own files rather than left buried in a resolved one:

- `issues/open/the-fixture-seeder-enables-only-equities-and-options.md` — the bond, treasury and
  ETF classes have the identical gap and still do. Measured today.
- `issues/open/a-books-price-band-is-anchored-by-its-first-order.md` — MSFT untradeable at
  realistic prices, the containment rule for diagnosing it, and the unchecked
  `yu13-otel-trace-join.sh` hardcoded limit.

---

## What happened

`BlpRiskState` enables securities per epoch. A fresh epoch starts with **none** enabled, and
`scripts/yu15/seed-proof-fixtures.sh` — which `run-proofs.sh` re-runs on every fresh epoch — seeded
**equities only**. It contained no option contract.

So after any epoch roll, listed options were untradeable and every option order returned
`UNKNOWN_SECURITY` — a reason code that reads like a bad symbol, not like a missing enablement. The
whole YU14 instrument class silently left the demo.

### Why it hid

- `/resolve` **succeeds** — reference data knows the contract, so the symbol looks valid.
- The price publisher **marks it** — a price streams, so the instrument looks live.
- Only the *enablement* was missing, and its failure surfaced as `UNKNOWN_SECURITY`, which every
  reader parses as "wrong ticker".

The option-enabling script existed — `scripts/proofs/seed-option-chain.sh`, whose own header says
*"the engine silently rejects orders whose security is not enabled or has no price tick, and those
rejects surface nowhere on some paths"* — but `grep -rln seed-option-chain scripts/` returned that
script and the README, nothing else. It ran only when a human ran it by hand.

## The fix

`scripts/yu15/seed-proof-fixtures.sh` now enables the whole option chain in the same step that
enables equities. Three choices in it are load-bearing:

1. **The chain is read from the feed, not copied.** `price-publisher`'s `GET /prices` is the list of
   what the tier actually quotes; the contracts are those matching the OCC shape
   `[A-Z]+\d{6}[CP]\d{8}`. A second hardcoded copy of the chain is a copy that drifts —
   `seed-option-chain.sh` still carries one, and its `premium()` approximation had already drifted
   from the black-scholes feed by a factor of ~2 on some strikes.
2. **At the LIVE premium, not a round number.** `POST /seed` sends a `PRICE_TICK` at the price
   passed and that becomes the risk anchor. This is how `yu08-algo-slicing.sh`, passing 200
   unconditionally, left IBM's anchor at 200 while the feed had walked to ~185.
3. **Seeding only — deliberately no cross.** The price collar band is anchored by the first *limit*
   into a book (`slotFor()`), never by a price tick, so seeding cannot pin an option book. Crossing
   here would, and `yu15-option-persistence.sh` must cross `AAPL261218C00260000` at 2.40 against a
   live premium near 8.85.

An unreadable feed is `exit 1`, not a warning: a silently-skipped enablement is the entire defect,
and the alternative is a suite that runs green on a rig where the option class does not exist.

## The A/B that proved it

Cluster rig `kind-traderx-yu12-cluster`/`traderx`, members and gateway on
`traderx/cluster-node:yu17-jsrebind`, 2026-08-21. Fresh epoch minted the only safe way — members
scaled to zero, PVCs wiped, back to three — so order refs restart near 1 and the id ordering is
itself the evidence that nothing was repaired between the arms.

**Arm A — fresh epoch, the seeder as it was at `HEAD` (`git show HEAD:…` run verbatim):**

```
resolve AAPL261218C00260000  -> {"securityId":20}                 reference data knows it
price-publisher /prices/…    -> 7.919 (source black-scholes)      the feed is marking it
IBM       Sell 1 @ 200       -> {"orderRef":7,"kind":1}   HTTP 200   equity control: ACCEPTED
AAPL261218C00260000 @ 8.85   -> {"orderRef":8,  ... "UNKNOWN_SECURITY"} HTTP 422
AAPL261218C00260000 @ 8.85   -> {"orderRef":9,  ... "UNKNOWN_SECURITY"} HTTP 422
AAPL261218C00260000 @ 8.85   -> {"orderRef":10, ... "UNKNOWN_SECURITY"} HTTP 422
```

**Arm B — the SAME epoch, the patched seeder run once (`24 contracts enabled at their live
premiums`), same order repeated:**

```
AAPL261218C00260000 @ 8.85   -> {"orderRef":17,"kind":1}  HTTP 200
AAPL261218C00260000 @ 8.85   -> {"orderRef":18,"kind":1}  HTTP 200
AAPL261218C00260000 @ 8.85   -> {"orderRef":19,"kind":1}  HTTP 200
MSFT260918P00410000 @ 31.98  -> {"orderRef":20,"kind":1}  HTTP 200   far end of the chain
```

Refs 7→20 are monotonic on one epoch: no roll, no manual `/seed`, nothing between the arms but the
seeder. On a **second** fresh epoch the patched seeder ran first and the option cross was accepted
at refs 7/8 — the first orders of that epoch — so the class is tradeable with no manual step at all.

## The near-miss this measurement was designed against

When the coordinator first verified the report, an option order was **accepted**, which looked like
a refutation. It was not: another lane's manual `/seed` had landed between the report and the probe,
so the probe measured the *repaired* state. The order ids settled it — `2583/2584/2585` rejected,
`2586+` accepted. **Before concluding a reported defect never existed, establish whether a fix
already landed**; a timestamp or an id ordering will usually say. On a rig several sessions touch,
"I could not reproduce it" is a claim about the last hour, not about the defect.

---

## Second, independent way the option class disappeared: a suite run

A different mechanism from the epoch roll, with the same symptom. **Also fixed and proved.**

`scripts/proofs/yu15-option-persistence.sh` deletes the long-ticker rows to exercise the
`VARCHAR(15)`/`VARCHAR(16)` widen regression:

```
DELETE FROM trades    WHERE CHAR_LENGTH(security) > 15;
DELETE FROM positions WHERE CHAR_LENGTH(security) > 15;
DELETE FROM stocks    WHERE CHAR_LENGTH(ticker)   > 16;
```

Every OCC symbol is longer than 15 characters. Step 3b restored only the `stocks` catalog rows;
`trades` and `positions` were never restored, so **any full suite run emptied option trade and
position history**. The contracts stayed *tradeable* — engine-side enablement lives in the cluster's
deterministic state, not the DB — so it was invisible on the order path and visible only on read
surfaces. A UI option blotter goes empty with no error anywhere.

**The fix** is a new step 3c. Step 1 parks the doomed rows in wide-schema copies
(`yu15_parked_trades` / `yu15_parked_positions`, `CREATE TABLE … LIKE` taken before the narrowing);
step 3c puts them back after the widen and before step 4's cross, verifies the count round-tripped,
and only then drops the parked tables. `INSERT IGNORE` with no drop until the restore succeeds, so a
run that dies in between leaves the rows parked and the next run carries them back;
`seed-proof-fixtures.sh`'s `FRESH_EPOCH` clear drops the parked tables, because after a wipe those
rows belong to a log that is gone.

The rows are re-inserted rather than re-derived, and by raw SQL rather than through an outbox,
because unlike `stocks` they have no control feed and no consumer downstream — they **are** the
projection of a log that still holds the trades, so writing the same rows back is the whole repair.

**The proof's own two symbols are excluded from parking, and that exclusion is load-bearing:** step 2
proves `BEFORE_SYM` must not persist, and step 4 asserts *exactly* 2 trade rows for `AFTER_SYM` —
restoring an earlier run's rows would make that 4 and fail a proof about something else.

**Measured 2026-08-21.** A 5-lot cross on `AAPL260918C00240000` (a contract the proof does not
touch) placed before the proof:

```
                          before      after unpatched proof     after patched proof
trades   (2 rows)         19-S 20-B   0                         7-S 8-B   present
positions (2 rows)        +5 / -5     0                         +5 / -5   present
```

The unpatched run reproduced the filed loss exactly. The patched run reported
`3c. put the parked option blotter back → 4 option trade/position row(s) restored`, exited 0, and
left the blotter intact.

### An unrelated break found and fixed while proving this

The unpatched run failed at step 2 with `expected the narrow schema to reject the option, but
Defaulted container "mariadb" out of: mariadb, schema-migrate (init)\n0 row(s) landed`. `sql()`
execs `deploy/eod-price-db` **without `-c mariadb`**, and its deliberate `2>&1` folds kubectl's
defaulting warning into the value — so every `$(sql …)` capture held that sentence followed by the
answer, and every exact comparison was doomed. `seed-proof-fixtures.sh` and `run-proofs.sh` already
passed the flag; the two proofs did not. Fixed in `yu15-option-persistence.sh` **and** in
`yu16-bond-position.sh`, which has the same `sql()` and twelve captured comparisons behind it. That
sibling was found by grep, not by observation, and has not been run since — see the open file.

## Two collar facts this class keeps being misdiagnosed against

Kept here because they are what makes the fix's "seed but do not cross" choice correct. Both
established by reading `MatchingEngine`, not by inference:

- **A `/seed` price tick cannot move a mark once a trade has printed** (ADR-051). After the first
  print the mark is the last trade price, full stop. Re-seeding to "fix" a mark is a no-op.
- **The price collar is not anchored on the mark.** `slotFor()` anchors the band on the security's
  *first limit into that book* — engine state, per epoch, order-dependent on whichever proof
  submitted first. So a security's tradeable price range depends on proof execution order, and
  `/seed` never touches it.

The consequences of the second — MSFT, and the diagnostic rule for telling a band problem from
anything else — are live and moved to
`issues/open/a-books-price-band-is-anchored-by-its-first-order.md`.
