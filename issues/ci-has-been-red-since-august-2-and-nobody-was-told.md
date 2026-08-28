# CI has been red since 2026-08-02, and nothing said so

**Status:** open
**Found:** 2026-08-27, by the coordinator, while adding YU16 and YU17 to the engine-tests matrix
**Class:** silent red — a pipeline reporting failure to nobody, while the docs it feeds claim zero failures

## The reading

`Engine Tests (JUnit + gates)` last passed on **2026-07-31**. Every run since **2026-08-02** has
failed — 13 consecutive failures over 25 days, from the workflow's own run history:

    gh run list -R YU-CS-Summer-2026-Distributed-TraderX/traderX \
      --workflow "Engine Tests (JUnit + gates)" -L 30

Every run in that history has `head_branch = YU15-eod-risk-extract`. That is not a coincidence and it
is the second finding: **`.github/workflows/engine-tests.yml` exists only on YU15, YU16 and YU17**,
and its push filter listed only YU13, YU14 and YU15. GitHub reads workflows from the pushed commit,
so the intersection — the only branch whose push could ever start a run — was YU15 alone. Pushes to
the tip states ran nothing.

## The three failures in the 2026-08-24 run

**1. YU15 leg — `EodPnlConsumerTest.metricsCountHaltedAndMarkedAccountsWithFixedCardinality`**
`expected: <3> but was: <4>`. A half-carried fix. On 2026-08-19 a durable-resubscribe change added a
fourth meter, `traderx_eod_pnl_subscribed`. The **production class reached every branch** — YU13
through YU17 are byte-identical. The **test update reached only YU17**, where the brittle
`assertEquals(3, registry.getMeters().size())` had been replaced by an assertion on the meter
*names*, with a comment explaining that a count says something changed and the set says what.
So four branches carried a production change and a test contradicting it.

FIXED 2026-08-27: the YU17 assertion carried to the operative layer
(`specs/YU15-eod-risk-extract/generation/runtime-overrides/…/EodPnlConsumerTest.java`, last-wins —
not the shadowed YU06 copy) on YU15 and YU16. All three files are now byte-identical. Proven by
re-running the four CI steps on YU16: green, 572 tests over 7 modules.

**2. YU13 and YU14 legs — `NatsReplicationPhase0Test.java` does not compile.** `cannot find symbol:
AckMode` (class and variable) and `no suitable constructor found for
ReplicationFollower(Connection,String,long,InMemoryOrderReadModel,…)`. A production signature moved
without its test. Because it is a **compile** failure the entire order-matcher suite never ran on
those two legs — and `assert-suites-executed.sh` failed alongside it, which is that guard doing
exactly the job its comment claims: a green build is not evidence a suite ran, and here a red one
was not evidence of *which* suites had even been attempted.

NOT REPRODUCIBLE 2026-08-27, and not fixed by anything here. The prune manifest at
`specs/YU11-aeron-replication/generation/prune-manifest.json` retires this test, and a fresh render
of YU13 drops it and compiles. Both halves of that prune fix were on **origin** by 00:04 EDT on
2026-08-24 — nine hours *before* the failing run — and `pipeline/`, `templates/` and every prune
manifest are byte-identical between local and origin on YU13 and YU14 today. So the run had the fix
and failed anyway, and why is unexplained. The next CI run is the test.

What WAS still red on those two legs, and is now fixed, is (1) below: they render the **YU06** copy
of `EodPnlConsumerTest`, and the corrected assertion had only been carried to the YU15 layer.

**3. composed-extras — `npm ci` on reference-data.** The composed `package-lock.json` is missing 7
declared dependencies: `@nestjs/schedule`, `mysql2`, `nats`, `@nestjs/testing`, `@types/jest`,
`jest`, `ts-jest`. A spec layer added them to `package.json` without refreshing the composed lock.

This one carries a trap worth writing down. `generate-state.sh:163` has a mitigation for exactly this
class: when `TRADERX_SKIP_LOCKFILE_REFRESH=1` is set it force-refreshes the manifests the
dependency-override sync touched, "so a skipped full refresh can never produce a generated tree that
fails `npm ci`". reference-data is not one of those manifests, so it falls through the gap the
mitigation was written to close. It **does** reproduce locally — checking a freshly rendered tree
shows the same 7 missing — so the natural assumption that CI's env flag hides it is wrong.

FIXED 2026-08-27, and the first fix was in the wrong place. The composed lock is settled from the
**template's** 10-dependency manifest, and the YU04 overlay adds the 7 *afterwards*: the manifest
moves after the lock is fixed. Correcting `templates/reference-data-specfirst/package-lock.json`
therefore changes only `components/`, which is not the tree `npm ci` runs against — a clean render
still produced the old lock. The lock now ships in the YU04 layer beside the package.json that adds
the dependencies, with a `.gitignore` exemption for runtime-override locks alongside the one
`templates/` already has. Verified by rendering into an empty generated root, which is CI's
condition; a developer's incremental tree cannot tell these two cases apart.

## Why it went unnoticed for 25 days

Nothing watches this. The badge is on a docs page, the failures land on a branch nobody re-reads, and
the only branch that can fire the workflow is not the branch anyone is working on. The two newest
states could not fire it at all, so the more active the tip became, the quieter CI got.

`docs/engineering/test-coverage.md` on main meanwhile reported these suites under a heading reading
"Tests that run on every push", with "Zero failures" beneath it. Both statements were assembled from
a local run, and neither was false about that run — they were just not about CI.

## Where it stands

All five matrix legs run green locally through the four CI steps, on 2026-08-27:

    YU13  495 tests    YU14  511    YU15  547    YU16  572    YU17  696

- Consider a notification on failure. A pipeline whose only reader is someone who thinks to look is
  indistinguishable from no pipeline, which is what the 25 days above demonstrate.
- Every job except `hosted` pins `ref: YU15-eod-risk-extract`, so baseline, composed-extras and the
  four integration jobs test YU15's tree whichever branch was pushed. Adding matrix legs did not
  change that, and it is a design question rather than a defect.
- The YU04-layer lock reaches YU13–YU17. YU04–YU12 carry the same latent gap and are not on the
  matrix, so nothing exercises them.
