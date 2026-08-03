# Issue: YU02's NATS replication test does not compile from YU11 up

**Status:** open, not fixed. Stops the YU publish walk at `YU11-aeron-replication`; YU01–YU10 all
publish clean. Pre-existing; first reachable 2026-08-03 once the publish wiring let a compile
preflight run against a YU11 tree.
**Related:** `HANDOFF-issue-yu02-trade-processor-zero-tests.md` — same layer boundary, and the
`propagate-spec-fix` skill's multiple-carriers rule is exactly what this violates.

---

## Symptom

```
> Task :compileTestJava FAILED
order-matcher/src/test/java/finos/traderx/ordermatcher/lmax/NatsReplicationPhase0Test.java:49:
  error: cannot find symbol
      assertEquals(ReplicationFollower.AckMode.ONRING, ReplicationFollower.AckMode.parse("onring"));
  symbol:   variable AckMode
  location: class ReplicationFollower
```

Six errors, all the same missing nested enum, at lines 49, 50, 124 and 125.

## Cause: two carriers of the class, one carrier of the test

| file | carried by | has `AckMode` |
|---|---|---|
| `ReplicationFollower.java` | YU02, **YU11** | YU02 yes, **YU11 no** |
| `NatsReplicationPhase0Test.java` | YU02 only | — |

YU11 is the higher carrier, so from YU11 up its `ReplicationFollower` wins. That rewrite is
deliberate: YU11 moves replication from NATS to Aeron, drops the NATS-era `AckMode`, and ships its
own `AeronReplicationPhase0Test`, `AeronReplicationRoundTripTest`, `AeronShadowRoundTripTest`,
`AeronMdcReplicationTest` and `AeronTransportAllocationGateTest`.

What it did not do is retire the YU02 test that asserts the enum it removed. Because no layer above
YU02 overrides `NatsReplicationPhase0Test.java`, that file is inherited unchanged into YU11–YU15,
where it references a symbol that no longer exists. At YU02–YU10 the YU02 class still wins, so the
test compiles and the walk sails through — which is why the boundary is exactly YU11.

## Why it has never been seen

Publishing was the first thing to compile a YU11 tree's `order-matcher` test sources through this
path; `publish-generated-state-branch.sh` refused every YU state at the `generation.mode` gate until
`8032f629`. Whatever suites run on the YU13/YU14/YU15 development worktrees are not exercising this
file, or it would have surfaced there — worth confirming, because a test that cannot compile is not
a test that is passing.

## Not fixed here, and why

The choice is the owner's, and it is about intent rather than mechanics:

- **Retire it at YU11.** Correct if the NATS phase-0 behaviour is genuinely gone at YU11. The
  spec-kit has no per-layer file deletion other than `generation/prune-manifest.json`, which only
  `004` and `006` currently use — so this means adding a prune manifest to YU11, which is a new
  pattern for the YU lineage and deserves a deliberate decision.
- **Override it at YU11** with an Aeron-shaped equivalent. But YU11 already ships five Aeron tests
  covering that ground, so this would mostly duplicate them.
- **Restore `AckMode` on YU11's `ReplicationFollower`.** Almost certainly wrong — it would re-add a
  NATS concept to the Aeron implementation to satisfy a test that should not be running.

The first is the recommendation. YU11 is the aeron-replication state and this is its layer, so it
belongs with whoever owns that work.

## Where the walk stopped

```
OK   YU01  213s     OK   YU06  237s
OK   YU02  219s     OK   YU07  231s
OK   YU03  220s     OK   YU08  254s
OK   YU04  232s     OK   YU09  250s
OK   YU05  231s     OK   YU10  245s
FAIL YU11  111s  <- here
```

Ten of fifteen states published, each on its own `code/generated-state-YU*` branch. YU12–YU15 are
untried: they sit above YU11 in the lineage and inherit the same file, so they will fail identically
until this is resolved.
