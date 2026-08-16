# Issue: YU01's allocation gates have never run, and the ProjectorHandler budgets are unmet

**Status:** RESOLVED 2026-08-03 by `YU01 projector: buffer primitive columns so the ring thread
allocates nothing`. All eight handlers now measure 0 bytes per 1,000 events and `noGcTest` passes —
the first time in this state's history. The history below is kept because the *reason* it went
unnoticed is the reusable lesson, and because §3 records a judgement that was deliberately not taken.
**Related:** `HANDOFF-issue-yu01-home-vs-tip-divergence.md` (the reconciliation itself).

## Resolution

| handler | budget | before | after |
|---|---:|---:|---:|
| ProjectorHandler (order) | 512 | 864,056 | **0** |
| ProjectorHandler (trade) | 512 | 239,688 | **0** |
| ProjectorHandler (position) | 512 | 216,000 | **0** |
| the other five | 0 / 512 | 0 | **0** |

`onEvent` now writes primitive columns into pre-allocated arrays and coalesces on the event's own
integer keys; every object it used to build per event — the entities, the id Strings, the
`BigDecimal` prices, the `Timestamp` stamps — is constructed at flush time on the drain thread.
Coalescing (last-write-wins) and the fold-under-newer retry are unchanged.

**The gate's `WARMUP` went 500 → 20,000, budgets untouched.** At 500 a JIT event landed inside the
measured window and charged a one-off ~576 bytes to whichever handler was measuring at the time — it
moved between `PositionUpdateHandler` and `TradeSubmitHandler` across consecutive runs, which is how
it was identified as an artifact and not a per-event cost, and it appears as ~80 bytes on pristine
home too. It was invisible until the projector's 864 KB failure stopped dominating the same test.
Option 2 in §3 — re-baselining the budgets — was **not** taken.

---

## 1. The gates could not compile, so they never ran

YU01's stated purpose is the LMAX sequencer hot path, and the way that claim is tested is three
allocation gates in its own layer: `AllocationGateTest`, `OutputHandlerAllocationGateTest`,
`OutputHandlerAllocationAttributionTest`, plus the latency/topology benchmarks.

On the home branch they cannot be built. `ProjectorHandler` declares exactly one constructor:

```java
public ProjectorHandler(OrderRepository, PositionRepository, JdbcTemplate,
                        SymbolTable, int batchSize, int queueCapacity, HotPathMetrics)   // 7 args
```

and four of its own test files call a six-argument form:

```java
new ProjectorHandler(null, null, null, symbols, Integer.MAX_VALUE, new HotPathMetrics());
```

`compileTestJava` therefore fails on `OutputHandlerAllocationGateTest`,
`OutputHandlerAllocationAttributionTest`, `OutputHandlerLatencyBenchmarkTest` and
`OutputTopologyBenchmarkTest`. Nobody noticed because YU01's worktree was only created 2026-07-31,
its generation was separately broken on the tip (see the overlay-patch note in the sibling issue),
and the tip had dropped two of the three gates from its own copy of the layer.

The reconciled pack fixes this incidentally: it carries the tip's five-argument `JdbcTemplate`
constructor **and** a six-argument delegating overload, which is the form the tests want. The gates
now compile and run for the first time.

## 2. What the gates say now

`OutputHandlerAllocationGateTest` measures per-handler allocation with hard budgets. Measured on the
reconciled pack, per 1,000 events:

| handler | budget | reconciled | pristine home* |
|---|---:|---:|---:|
| MarshallerHandler | 0 | **0** | 0 |
| NatsBridgeHandler | 0 | **0** | 0 |
| AccountTradeHandler | 0 | **0** | 0 |
| PositionUpdateHandler | 512 | **0** | 0 |
| TradeSubmitHandler | 512 | **0** | 80 |
| ProjectorHandler (order) | 512 | 864,056 | 920,000 |
| ProjectorHandler (trade) | 512 | 239,688 | 280,000 |
| ProjectorHandler (position) | 512 | 216,000 | 224,000 |

\* pristine home measured with **only** the missing six-arg constructor added, so its own tests
compile — nothing else changed. That isolates the question "could home ever have met this budget?"

Five of the eight handlers are exactly allocation-free. The three `ProjectorHandler` paths miss
their budget by three orders of magnitude — **on both sides**, with home worse than the reconciled
pack on every one of them. So this is not a regression introduced by the merge; it is a budget
neither development thread has ever satisfied, in a test that has never been able to run.

## 3. The decision

Do **not** simply relax the numbers to whatever is measured today — that converts a gate into a
description. The options are:

1. **Make the projector allocation-free.** `onEvent` allocates a `ProjectionItem` per event before
   queueing (`toItem(e, sequence)`), and the flush path builds SQL with a fresh `StringBuilder` per
   batch. Both are poolable. This is the option consistent with the state's spec.
2. **Re-baseline the ProjectorHandler budgets deliberately**, with a recorded rationale — the
   projector runs on the `projector-drain` thread, not the BLP ring, so a case can be made that it
   is not on the no-GC hot path at all and never should have carried a 512-byte budget. If so, the
   budget should say that in a comment rather than be silently raised.

The other five handlers should stay at their current budgets: they are met exactly.

## 4. Second, unrelated finding: the suite cannot be green on H2

`LmaxHotPathParityTest` fails with `no persisted trade matched within timeout`, because the read
model never writes: H2 rejects the projector's MariaDB `ON DUPLICATE KEY UPDATE … VALUES(col)` as
bad SQL grammar, even in `MODE=MySQL`.

This is lineage-wide and predates the reconciliation. Checked directly: YU02's
`LmaxHotPathParityTest` fails the same way, at context load, with
`org.h2.jdbc.JdbcSQLSyntaxErrorException` → `BadSqlGrammarException`, and **zero** projection
failures of its own. Moving YU01's three test classes to `MODE=MySQL` (matching YU02's `573b070e`)
was still correct — the state's runtime is `jdbc:mariadb` with `MariaDBDialect`, so PostgreSQL-mode
tests contradicted it — but it is not sufficient.

Worth noting the failure shape: projection failures are caught and logged at `WARN`, so the read
model silently stops writing. Only an assertion that reads the projected row catches it. A suite
that merely loaded the context would have gone green over a completely broken read model.
