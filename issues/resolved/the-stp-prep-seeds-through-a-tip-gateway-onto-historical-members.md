# run-proofs' stp-prep seeds through a TIP gateway onto historical members, so nothing seeds

**Filed 2026-08-22**, measured on `kind-traderx-yu12-cluster`/`traderx` while closing
`issues/resolved/the-fixture-seeder-enables-only-equities-and-options.md`. Pre-existing;
it became visible only because `run-proofs.sh` stopped discarding the seeder's exit status.

**RESOLVED 2026-08-22** in `bf7b26c2`, by option 2 scoped to the caller rather than to
`rebuild_fresh_epoch`. See "How it was resolved" at the bottom.

## Measured

`run-proofs.sh`'s `yu13-stp-and-replace` prep mints a fresh epoch on the historical members
(`rebuild_fresh_epoch "${STP_IMAGE_PRE}"`) and then runs `seed-proof-fixtures.sh`. Every single seed
is refused:

```
[stp-prep] control feed off + fresh epoch minted ON traderx/cluster-node:yu15-pre-1k
   22214    seed HTTP 422
   52355    seed HTTP 422
   42422    seed HTTP 422
   62654    seed HTTP 422
   11413    seed HTTP 422
   10031    seed HTTP 422
   44044    seed HTTP 422
[fail] seed SPY @ 522.570000: {"seeded":false}
```

Reproduced on two consecutive suite runs (`seed-1.log`, `seed-2.log`), both call sites.

## Why

`rebuild_fresh_epoch` repins **the members only**. That is deliberate and correct — there is a long
comment on it, because repinning the gateway there once cost two proofs (`yu15-pre`/`yu15-stp`
predate the gateway's probe server on 18111 and crash-loop against the manifest's probes). But it
leaves the seeding step talking to a **tip gateway in front of historical members**, and that pair
cannot complete a `/seed`.

It is not a readiness race and not a capacity refusal. Proved by controlled comparison — same
seeder, same feed, same fresh-epoch procedure, the only variable being what sits in front of the
members:

| members | gateway | seeding result |
|---|---|---|
| `yu15-pre-1k` | `yu17-jsrebind` (tip) | every seed 422 / `{"seeded":false}` |
| `yu15-pre-1k` | `yu15-pre-1k` + pre-probe-server probes | **all 68 enabled**, accounts all HTTP 200 |
| `yu17-jsrebind` | `yu17-jsrebind` | all 68 enabled |

The proof itself is unaffected *as a proof*, which is why this hid: `yu13-stp-and-replace`'s own
`roll_to` patches the gateway to the same historical image before it does anything, so by the time
the proof seeds its own ticker the pair matches and the seed succeeds first time (no retry lines in
its log).

## What it costs

The stp epoch has **never** carried the fixture universe — not the 20 equities, not the 44 with
options, not the 68 now. `yu13-stp-and-replace` runs against an epoch holding only the single ticker
it mints itself.

Nothing it asserts is wrong: its assertions are all about that one ticker's trades, the replicated
book digest and `/replace` status codes. But the suite's own comment on this block —
"Seeding after this registers ~20 tickers, comfortably inside the historical 64 limit" — describes
something that does not happen, and any future proof placed after the stp arm that expects a seeded
rig would find an empty one.

It also means the historical builds' capacity is **not** exercised by the suite. The 68-instrument
result in the resolved issue above comes from a hand-run measurement, not from a suite run.

## Options

1. **Seed after the proof's own `roll_to`**, i.e. move the universe seeding inside
   `yu13-stp-and-replace.sh` step 1, where the gateway already matches the members. Puts fixture
   setup inside a proof, which the suite otherwise avoids.
2. **Repin the gateway in the prep too**, with the pre-probe-server probes that
   `yu13-stp-and-replace.sh` already defines (`GW_HISTORICAL_PROBES`). This is exactly what the
   proof does moments later and what the measurement above used, so it is known to work — but it
   re-treads the ground the "THE MEMBERS ONLY" comment warns about and would need that comment
   rewritten rather than contradicted.
3. **Accept it and say so**: drop the "registers ~20 tickers" claim from the prep comment and record
   that the stp epoch is deliberately bare. Cheapest, and honest, but leaves the capacity of the
   historical builds untested by the suite.

Not taken here because the choice touches the prep's gateway policy, which has its own history.


---

# How it was resolved — 2026-08-22

Option 2, but **not** by changing `rebuild_fresh_epoch`. The two arguments in its `THE MEMBERS ONLY`
comment needed separating, because only one of them had gone stale:

* **The probe-port argument is stale.** `yu15-pre`/`yu15-stp` crash-loop against the manifest's
  18111 probes — but `GW_HISTORICAL_PROBES` now exists and both `yu13-stp-and-replace.sh` and
  `yu13-cancel-ingress.sh` already roll historical gateways with it. The warning predates its own
  remedy.
* **The scoping argument stands, and is the whole shape of the fix.** The gateway is stateless and
  holds no epoch; giving a general-purpose function a side effect exactly one caller wants is the
  original bug, which announced itself only as "fresh epoch minted ON …" while dragging the gateway
  historical. A runner repinning a component a *proof* makes claims about is how a change becomes
  invisible.

So the **prep block borrows the gateway around its own seeding step and returns it**, in view in the
log, and `rebuild_fresh_epoch` is untouched:

```
[stp-prep] borrowing the gateway onto traderx/cluster-node:yu15-pre-1k for the seeding step only
  [seed] feed census: {'option': 24, 'bond': 19, 'etf': 5}
  [seed] 48 instruments enabled at their live prices (68 securities this epoch)
[stp-prep] returning the gateway to traderx/cluster-node:yu17-jsrebind and the probes it had
```

**Returning it before the proof runs is load-bearing, not tidiness.** `yu13-stp-and-replace`
captures the gateway's image on entry and restores that on EXIT, so handing it a historical gateway
would make it faithfully restore a historical gateway at the end of the suite — the
restore-what-you-found latch that `temporarily-mutate-shared-cluster-state` names as the most
expensive shape here. The capture is also validated against a degenerate reading (a stripped probe
means an earlier run died mid-borrow) and falls back to the manifest form.

**A fact that makes the borrowed window safer than expected:** the historical builds contain **zero**
references to `CONTROL_FEED_SUBSCRIBER` (`javap` on their `ClusterGatewayMain`). They predate the
subscriber entirely, so during the borrow the 510-security replay hazard is *absent*, not merely
disabled.

`GW_HISTORICAL_PROBES` was **not** copied a third time. The proofs in `scripts/proofs` are
deliberately standalone and source nothing; the runner is not a proof, so it reads the proof's own
literal via `gw_probe_form`, which fails loudly if the string moves. The copies cannot drift.

## The second instance, found by the fix

The **generic per-proof seed** runs after the prep in the same iteration — by which point the
gateway is correctly back on the baseline while the members are still historical, so it hit the
identical mismatch. The epoch was fully seeded, yet it printed `[warn] yu13-stp-and-replace runs
against a partly-seeded rig`. A false alarm in the one place a reader checks for a real one, and on
a warning added hours earlier to catch under-seeding. It is now gated on the prep's actual result:

```
  [seed] already seeded by the stp prep through a matched gateway; not re-running
```

If the prep's borrow fails, the flag stays clear and the generic seed still runs and still fails
loudly.

## Verified

Full `bash scripts/yu15/run-proofs.sh yu13-stp-and-replace`: all seven account seeds HTTP 200 and 68
securities enabled **on `traderx/cluster-node:yu15-pre-1k`**, `yu13-stp-and-replace PASS`,
`1 passed, 0 skipped, 0 failed`. The suite now exercises the historical builds' capacity for the
first time — the 68-on-historical result in
`the-fixture-seeder-enables-only-equities-and-options.md` no longer rests on a hand-run.

Restore verified after the run: members and gateway both `:yu17-jsrebind`, `startup=/live:18111`,
`liveness=/live`, `CONTROL_FEED_SUBSCRIBER=1`.

Side evidence the epoch is genuinely seeded now: the proof's trade counters open at `[6 6 6]`
instead of `[0 0 0]`, because the fixture positions are booked by real crossings. Its assertions are
deltas, so nothing it claims depends on that.
