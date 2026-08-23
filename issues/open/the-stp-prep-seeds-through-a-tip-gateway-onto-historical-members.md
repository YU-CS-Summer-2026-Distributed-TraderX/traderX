# run-proofs' stp-prep seeds through a TIP gateway onto historical members, so nothing seeds

**Filed 2026-08-22**, measured on `kind-traderx-yu12-cluster`/`traderx` while closing
`issues/resolved/the-fixture-seeder-enables-only-equities-and-options.md`. **Open.** Pre-existing;
it became visible only because `run-proofs.sh` stopped discarding the seeder's exit status.

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
