# Testing strategy

Which checks are automated, which are operator-run, and why each one sits where it does.

Fast tests check application behavior, container-backed suites check database and broker contracts, and end-to-end proofs inspect the effects of real requests. Cluster recovery and timing run separately on reserved hardware.

## Tier 1: in-process tests, in CI on every push

The in-process tier covers the engine, cluster codecs, gateways, risk and post-trade logic. The published YU17 baseline contains **698 cases**, plus **48 baseline-service cases**. YU18 adds order lifecycle, bundle validation, result intake, managed identity and UI tests; those additions are described in [Test coverage](test-coverage.md#yu18-and-component-coverage).

They assert the correctness properties directly: self-trade prevention, atomic replace, client-order-ID
idempotency, byte-identical consensus allocation, deterministic replay, and reproducible regulatory
and risk-extract exports.

YU18 checks exact numeric input, supported order-type/TIF combinations, trigger behavior, FOK liquidity, cross-account self-match groups and deterministic replay. EOD tests cover bundle hashes, terms, result identity, coverage, stored-byte tampering and uncertain submissions. Console and Desk tests cover account selection, stale replies, historical read-only state and accepted/refused/unknown outcomes.

The generated-output check verifies that the intended overrides reached the effective tree. `assert-suites-executed.sh` rejects missing test reports; a Gradle task reporting `NO-SOURCE` does not count as coverage.

## Tier 1.5: cross-service integration, in CI with containers

The original five suites run against real infrastructure rather than in-memory substitutes, isolated by tag into
their own task so the fast unit job needs no container runtime. Two of them cover the end-of-day
chain: the JetStream stream contract, and the snapshot read and P&L write against a MariaDB running
the schema read live from the deployed ConfigMap rather than a copied fixture that could drift.

This tier exists for properties that are enforced by infrastructure rather than by application
code. A mock can be made to return whatever the code under test expects, so a test built on one can
only confirm that the code asked for the right thing: never that the database agreed. Constraints,
foreign keys, transaction boundaries and startup wiring all live on the other side of that line.

`TradeProcessorPersistenceIT` drives the real booking path against a **real MariaDB** initialised
with the **deployed schema**, run the same way production runs it. It proves the persistence
contract a mocked unit test cannot:

- a buy books a position row and a trade row against the deployed DDL, including the
  enum-to-constraint mapping surviving a real round trip;
- subsequent trades accumulate onto the same position row;
- an order for an account that does not exist is **rejected by the foreign key and fails loudly**.
  Trades disappearing into a foreign-key rejection is a documented failure class here, so this test
  turns that silence into an assertion.

`AccountOutboxAtomicityIT` asserts the guarantee the **transactional outbox** exists to provide:
the business write and the outbox row commit as one unit, or neither does. That guarantee is
enforced by the database, so it is only observable against one: the unit-tier test in the same
package mocks the outbox repository, which makes it pass whether or not the two writes share a
transaction at all. Every assertion here reads back through an **independent connection**, outside
the application's pool and its transaction, because reading through the connection that did the
writing says nothing about what was committed. It covers:

- both rows present after the call, with the outbox row carrying its generated version;
- **neither row visible to an outside reader while the transaction is still open**, which is what
  rules out the two writes landing on separate connections;
- a **database-rejected** outbox insert: a real constraint violation, not a stubbed failure —
  leaving no account row behind.

It also runs MariaDB with the **same server flags the deployed database uses**, which turned out to
matter: the account path depends on one of them, and nothing else checks that it is still set.

`TradeProcessorContextIT` starts the composed application context against a **real MariaDB and a
real message broker**, covering startup wiring that dials the broker during bean creation and so
cannot be exercised without one.

Each case in this tier was **falsified before it was trusted**: the code was deliberately broken
to confirm the test fails, and fails for the stated reason. A test that has never failed is a test
whose failure mode is unknown, and on this tier that risk is real: infrastructure tests can pass by
never reaching the assertion they claim to make.

### YU18 persistence, recovery and packaging

The generated trade-processor integration task exercises retained SQL, managed run identity and archive catch-up against MariaDB and NATS. It checks that missing history, conflicting rows and wrong scope are refused, and that successful catch-up updates rows and its verification cursor together.

Container smoke tests check installed runtime entrypoints, schema validation and failure responses. They need explicit local images or an external engine checkout. A structurally valid response does not establish financial model accuracy; the synthetic Treasury-bill flow is a separate, bounded pricing example.

## Tier 2: end-to-end proofs, operator-run

The YU17 inventory contains **47 proof scripts**. They drive the system end to end: REST, FIX and binary ingress →
gateway → three-member Aeron cluster → asynchronous projection → SQL read model → egress, plus the
risk control plane.

What separates this tier from the one above is where it looks for its answer. A unit test can call a
method and inspect what it returns. These scripts cannot, and deliberately do not: they submit real
input to a running system and then read the outcome from the far end of the pipeline: the committed
sequence on a cluster member, the row in the read model, the message on the egress stream. An
acknowledgement is never accepted as evidence that something happened, because in a system that
sequences, replicates and projects asynchronously, a successful response and a completed effect are
different events that can disagree.

Each script is written so that it can fail. It states the outcome it expects before it acts, then
asserts against the system's own record rather than against its own assumptions, and prints an
explicit pass or fail line per step so a run reads as a verdict rather than a log.

They stay operator-run because they need a live cluster. That is an infrastructure constraint rather
than a reliability one, which is a meaningful difference: these scripts are dependable, they simply
require a deployed system to run against.

### YU18 order, risk and recovery flows

Typed-order proofs check admission, triggers, partial fills, cancellation, replacement and DAY expiry at the engine and SQL effect ends. Risk-flow proofs follow the portfolio cut through the external worker, immutable response custody and result intake, including uncertain submission recovery.

Managed-run proofs check retained restarts, archive catch-up, stale-scope refusal and read-only historical views. The automatic-recovery proof uses an isolated local installation and must be enabled explicitly. A single-member recovery result does not establish three-member HA.

## Auditing the proofs against a live writer

When historical tape replay is enabled, it writes orders alongside the proof. Venue-wide totals then include both sources, so a proof must identify its own orders or use a counter that excludes replay traffic.

That is a permanent change in what an end-to-end assertion can mean, and it does not announce itself.
A proof reading a venue-wide counter still runs, still prints a verdict, and is simply no longer
measuring its own work. So the proof tier is audited assertion by assertion against that writer, and
the taxonomy the audit produced is the durable part of it:

| The assertion… | The question that finds it |
|---|---|
| reads a venue-wide counter a third writer moves | **is this metric contaminated?** |
| is true, but cannot be sampled coherently while the venue moves | **how does it sample?** |
| rests on a precondition the system no longer satisfies | **is the premise still true?** |
| claims something that is not a property of the system at all | **is this even true?** |

The last two are the ones no metric sweep reaches. A contaminated reading can be re-pointed at a
better counter. **A falsified premise means the methodology has to change**: a quiet-venue guard is
a true statement about a property the venue no longer has, and what it guards is the verdict. And a
claim that was never a property of the system passes for the wrong reason until somebody reads the
step against the code beneath it rather than against its title.

### Why a widened tolerance is not one of the repairs

The scale is what makes this a design constraint rather than a tuning problem. In one recorded run
the venue took **1,172 foreign trade legs and 1,504 foreign order refs** while a proof asserted —
against **4 legs and 7 refs of the proof's own work.** That ratio is what the venue-wide readings
were being measured against.

The same assertion, on two consecutive runs of a failover that was entirely correct, then reported
**"3 DUPLICATED"** and **"46 LOST"**. **The contamination does not even have a consistent
direction:** the venue-wide delta lands either side of the true count depending on what the tape
happened to rest and fill inside the window, so the failure text accuses the system of opposite
defects on consecutive runs.

No tolerance covers a bias that changes sign, and one wide enough to try would no longer be able to
fail. **Widening a tolerance does not fix the assertion, it deletes it**: so it is not on the list
below.

### The repairs that are

- **Assert the identity of the thing itself**: an order's own row, a probe id, a book's tick size.
  "Were any orders lost or duplicated" becomes set equality between the refs clients were
  acknowledged for and the refs actually resting on a freshly minted ticker: a lost order is an acked
  ref not resting, a duplicate is a resting ref nobody was acked for, and both are named individually
  rather than summed into a number the tape contributes to.
- **Read the operator-scoped twin**, where the counter has one. Five of them do, and each excludes
  externally-generated flow by construction, so a proof can separate its work from replay traffic. Other operators still share the operator scope. Two are carried in the snapshot, which is what lets a scoped equality survive a
  member rebuild.
- **Scope the equality rather than deleting it.** Where the equality *is* the proof: "nothing
  changed across the rebuild", "the restore came back at the backup point": it survives, scoped to
  state the proof owns. Deleting it would leave the proof claiming less than its banner says.
- **Assert a labelled floor** where the quantity legitimately moves and only its lower bound is
  owned by the proof.

The twins turned out to fix a second defect nobody was hunting. Under a tape running at about six
prints a second, a four-quantity cross-member read was coherent in **5 of 20** unretried samples,
while the operator twins were coherent in **20 of 20**. A counter that does not move cannot be
sampled incoherently: so switching to a twin removes contamination and sampling skew together,
where a retry removes only the second.

### Destructive proofs refuse by default

Proofs that kill a leader, restart a member or wipe an epoch require **`DESTRUCTIVE=1`**. Without it
they print exactly what did not run and **exit 2 without touching the cluster.**

They refuse rather than running a reduced subset, and the reason is the same one that governs this
tier. Every one of these scripts ends in a banner claiming survival across a destructive event, and a
run that skipped the destructive part has not shown that. **A partial run must never be able to read
as the claim.**

The irreversible ones ask for more than consent. `DESTRUCTIVE=1` records that an operator accepted
destroying *a* cluster, not **this** one: and a context default is exactly the kind of setting that
rots quietly, while a wrongly-pointed client answers perfectly truthfully about the wrong cluster. So
those proofs also refuse unless the operator names the target context, which converts a forgotten
variable from an outage into a refusal.

## Tier 3: full-cluster and timing, on demand

Three-member failover, snapshot and replay, cold-follower rejoin, and wall-clock budgets run on
demand on idle hardware, and they are kept out of every-push CI deliberately.

The reason is that these tests assert on *time* and on *scheduling*, not only on values. A consensus
test runs three real cluster members with their own transport threads in one JVM; a wall-clock budget
asserts that an operation completes within a fixed number of milliseconds. Both depend on the
machine actually granting CPU when the code asks for it. On a shared runner that assumption does not
hold, and the failure is not a graceful slowdown: a starved member simply stops making progress, and
the test reports a timeout that looks exactly like a broken algorithm.

That gives this tier a different reading rule from the others. **A green result is trustworthy
wherever it runs**, because clearing a timing bar under contention is harder than clearing it on an
idle box. **A red result is only meaningful on quiet hardware**, and needs re-running there before it
is treated as a regression. Keeping these tests out of the push pipeline is what protects that rule:
a suite that cries wolf on a busy runner teaches people to ignore it, which costs more than the
coverage is worth.

Moving this tier onto dedicated hardware is a one-line runner change, at which point it can gate
merges like the others.

## Gates: the properties a test cannot see

The inherited **gates** include four allocation gates and two no-GC gates. YU18 also adds a typed-order allocation gate. They sit
outside the tiers, and are counted separately, because they are a different kind of check. A test
asks whether the code produced the right answer. A gate asks what the code *cost* to produce it: and
those are independent. A method can return a perfectly correct result while allocating on every call,
and no assertion about its return value will ever notice.

That distinction matters here because of how the matching engine runs: a single thread, processing
one order at a time, on the path every order takes. Garbage collection stops that thread. The damage
is not a wrong answer, it is an unpredictable pause: and pauses arrive under load, which is exactly
when the system is least able to absorb them. Allocation on the hot path therefore converts, quietly
and later, into latency spikes that no correctness test would have flagged.

So the gates run the hot path in steady state and assert that it allocates **exactly zero bytes**.
Zero rather than a budget, because a threshold is negotiable: each change that adds "just a little"
stays under the bar until one day it doesn't, and there is no principled place to draw the line. Zero
is the only number that cannot be argued down.

The two no-GC gates then run the same paths under a JVM configured with **no garbage collector at
all**. With nothing to reclaim memory, any allocation is no longer absorbed and forgotten: it
accumulates until the process dies. That converts a slow, invisible degradation into an immediate,
unmissable failure, which is the whole point: it removes the possibility of a small regression
sitting undetected because the collector was quietly cleaning up after it.

Together the gates cover the engine's own hot path, the same path with risk checks engaged, the
transport encode path, and the cluster apply path: the four places where an allocation would sit
inside the per-order critical section. Because they need their own JVM configuration, they run as
separately forked JVMs rather than inside the main suite, which is why their count is kept apart from
the test totals.

They are best understood as a ratchet rather than a performance claim. They do not assert that the
system is fast. They assert that a property the system already has cannot be lost by accident, which
is the kind of guarantee that is very cheap to keep and very expensive to recover once it has drifted.
The hosted engine runner includes the inherited allocation gates; YU18 also runs its typed-order gate through the test task. The two no-GC gates run on demand with the cluster tier. Results apply to the configured warmup, JVM and compilation profile.

## Proof-to-test map

Almost every end-to-end proof has an in-process test asserting the same property, already in CI:

| End-to-end proof | Property | In-process test in CI |
|---|---|---|
| self-trade prevention and replace | STP, atomic replace, replay-identical | `LimitOrderBookTest` |
| duplicate client order ID | duplicate suppressed idempotently | `ClOrdIdLedgerTest`, `IdempotencyEvictionDeterminismTest` |
| cancel ingress | cancel unlinks and is skipped by a cross | `LimitOrderBookTest` |
| FIX session and status | FIX 4.4 session, order and mass status | `FixSessionIntegrationTest`, `FixGatewayStatusTest` |
| reproducible regulatory export | journal-sourced export is byte-reproducible | `RegulatoryReportDeterminismTest` |
| reconciliation | journal against projection | `ReconciliationServiceTest` |
| settlement | T+N settlement lifecycle | `SettlementServiceTest` |
| risk gate and control plane | two-tier risk gate, kill switch | `BlpRiskStateTest`, `RiskControlControllerTest`, `ControlPlaneLimitRejectSeamTest` |
| durable control feeds | live delta and bootstrap catch-up | `ControlFeedSubscriberTest`, `ControlFeedBootstrapStateTest` |
| end-of-day risk extract | sequence-addressed, byte-identical cut | `RiskExtractTest`, `RiskReplayDeterminismTest` |
| failover | sub-second failover, zero order loss | `ThreeMemberClusterTest` (Tier 3) |
| order read model | place → new → cancel → canceled at the SQL effect end | `ProjectorHandlerTest` |
| end-of-day price chain | quality gate blocks a flagged publish; consumer halts fail-safe | end-of-day service and quality-checker tests |
| execution algo | a parent order slices into N children, all booked | `AlgoEventStoreReplayTest`, `AlgoOrderServiceTest` |
| cluster recovery | empty-disk rejoin to byte-identity | `ThreeMemberClusterTest`, `SnapshotRoundTripTest` (Tier 3) |
| failover under load | zero lost, zero duplicated | `ClOrdIdLedgerTest`, `InflightCorrelationTest` |
| cross-epoch ID reuse | no order-reference reuse across epochs | `SnapshotRoundTripTest` |
| distributed tracing | one order produces one trace across consensus | `OrderTraceTest`, `SpanSinkTest` |
| Typed order lifecycle | triggers, TIF, cancellation, replacement and replay | `OrderTypesEngineTest`, `OrderTypesServiceTest`, `OrderTypesBoundaryTest` |
| EOD bundle and result intake | immutable inputs, matching result identity, failure and restart paths | source and generated `eod-risk-bundles` suites |
| Managed run and projection recovery | scope refusal, retained rows and verified catch-up | generated `recovery-identity` tests and trade-processor integration task |
| Console and Trader Desk | account boundaries, stale replies, historical views and order feedback | Angular suites and Node server/reader tests |

The shell proofs are the end-to-end confirmation of properties CI already gates in-process. That is
the difference between having a script that shows something works and having the invariant enforced
on every change **and** demonstrated on a running system.

## What stays manual, and why

The order → match → egress → read-model → REST round trip stays operator-run deliberately.
Automating it means running the Aeron cluster tier inside CI, and a shared two-core runner is a rig
this project has repeatedly measured as unreliable for consensus. Trading a trustworthy manual proof
for an unreliable automated one is a poor exchange. Its properties are gated in-process by the
projector and order-book tests.

## Which tree the numbers describe

The detailed numeric baseline on [Test coverage](test-coverage.md) is the published YU17 run. YU18 extends those suites; its additions are listed separately rather than added to an old total without a complete combined run.

Integration events generate YU18 from the code under test. Historical branch events retain the YU13–YU17 matrix. Each state renders its own effective tree, which makes a shadowed override visible as a state-specific failure. Hosted runs keep the cluster/timing classes separate and check that expected suites actually executed.

## Running the checks

Use Java 21, Node 22/npm, Python 3.11+, Bash, `jq` and `rg`. Browser tests need Chrome; container tests need Docker. Install the EOD component's pinned requirements before its Python suites. Run from the repository root and generate states sequentially.

```sh
TRADERX_SKIP_LOCKFILE_REFRESH=1 bash pipeline/generate-state.sh YU18-risk-integration
bash scripts/ci/engine-tests.sh hosted
bash scripts/ci/service-tests.sh
bash scripts/ci/assert-suites-executed.sh
python3 scripts/ci/check-yu18-composition.py --junit
bash scripts/test-state-YU18-risk-integration.sh
bash scripts/test-state-YU18-risk-integration.sh generated/code/target-generated/eod-risk-bundles
python3 scripts/test-state-YU18-checkout.py
```

For the interfaces:

```sh
npm ci --prefix web-front-end-console
npm test --prefix web-front-end-console -- --watch=false --browsers=ChromeHeadless
npm ci --prefix web-front-end-console-combined-prototype
npm test --prefix web-front-end-console-combined-prototype -- --watch=false --browsers=ChromeHeadless
(cd web-front-end-console-combined-prototype && node --test test-desk-session.mjs test-server.mjs)
```

For database and broker integration:

```sh
(cd generated/code/target-generated/trade-processor && ./gradlew --no-daemon integrationTest)
```

Live proofs need a reserved environment and explicit target configuration. Read the prerequisites in `scripts/ri07/`, `scripts/ri03-acceptance.py`, `scripts/ri03-recovery.py` and generated `recovery-identity/test-live-automatic-recovery.sh` before running them. Keep JUnit XML, Node TAP and browser reports; filtered runs can overwrite full-suite results.
