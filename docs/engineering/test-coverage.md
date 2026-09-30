---
title: Test inventory and counting
---

# Test inventory and counting

Scope: integration revision `4c2c4eb2`, source inspection on 2026-09-30. This refresh does not manufacture a current executed-test total from file names or older reports.

## What counts mean

- **Source files** count tracked files matching a stated pattern. Overrides can shadow earlier copies; adding counts across state packs double-counts effective code.
- **Executed cases** come from one run's JUnit XML, Node TAP or browser report. Parameterized tests can produce several cases; disabled suites produce none.
- **Proof scripts** are entrypoints, not case counts. A script can contain many checks or exit before exercising anything.
- **Scenarios** are requirements or proof cases. They are not necessarily automated tests.

Record revision, generated state, suite selectors, runtime, skips/failures and excluded tiers alongside each count. Never add source and generated executions as if they were distinct behaviors.

## Coverage by mechanism

| Area | Evidence entrypoints | Important refusal or failure cases |
|---|---|---|
| Sequencing, matching, snapshots and risk | generated matcher JUnit; `scripts/ci/engine-tests.sh` | Replay equality, duplicate IDs, risk rejection, cancel/replace and snapshot restoration |
| Typed lifecycle | `OrderTypesEngineTest`, `OrderTypesServiceTest`, `OrderTypesBoundaryTest`; RI-07 live exercise | Exact numeric input, invalid TIF, FOK liquidity, absent peg/trail reference, stale business day |
| Self-match groups | matcher group tests, boundary tests and Desk server tests | Same group across accounts, different-group control, FOK exclusion and snapshot persistence |
| Control feeds and post-trade | composed service suites and integration tasks | Outbox atomicity, broker repair, retained schema, reconciliation and settlement |
| EOD bundles and external intake | source/generated EOD suite, fixture checkout, RI-03 acceptance/recovery | Hash/identity mismatch, missing input, unsupported calculation, uncertain submission and stored-byte tampering |
| Run identity and catch-up | generated recovery-identity tools; trade-processor SQL integration | Wrong scope, missing archive, retained conflicts, lease fencing, overlap with live delivery |
| UI and streams | both Angular suites; Node readers and Desk server tests | Scope/account changes, stale responses, history read-only, unavailable/unknown states and reset feedback |
| History and capture | `selfcheck.q`, `txselfcheck.q`, tick-store tests | Dataset counts, ordering, replay, capture identity and missing source prerequisites |
| Operational proofs | `scripts/ri07/`, `scripts/proofs/`, replication scripts | Readiness at effect end, restart, failover and explicit refusal of invalid test conditions |

## Historical totals, not current coverage

An older integration-copy page described a YU15 run as **335 engine + 164 service = 499 tests**, with **48 baseline cases**, **5 integration suites**, **6 allocation/no-GC gates**, **35 q checks**, **26 proof scripts**, **44 Node/Python cases** and **106 Java test classes**. Those values belonged to that report's selected tree and tiers. They are not totals for current YU18 and do not establish that those suites execute in another generated state.

The deployed site inspected on 2026-09-30 instead reports the newer YU17 snapshot: **489 engine + 209 services = 698 cases**, **130 Java classes** and **47 proof scripts**. It describes 696 hosted cases but also describes a YU13–YU15 CI matrix, so this historical prose is not sufficient to establish event-specific hosted coverage. Those figures are retained as reported totals, not freshly counted current YU18 results.

Later accepted integration reports described **563 matcher cases (557 passed, 6 skipped)**, **221 service cases**, **7 database/broker integration cases**, **152 EOD cases in each source/generated run**, **97 console cases** and **13 Node reader cases** for the September 24 combined integration milestone. Subsequent recovery and Desk changes changed the suites again. These reports are dated local evidence, not a new full run at `4c2c4eb2` or a current hosted-CI statement.

The current reproduction commands are in [Testing strategy](testing-strategy.md). Use `assert-suites-executed.sh` and `check-yu18-composition.py --junit` after a fresh full run. Preserve XML before filtered tests replace it. Inspect active Gradle source sets; a dormant `src/main/test/java` file must not be counted as compiled by assumption.

## Boundaries

Allocation gates constrain cost under their declared execution profile. Correctness tests do not establish latency; latency tests do not establish model validity. Disposable single-member SQL recovery does not prove cluster HA. The accepted synthetic bill result is a bounded pricing comparison, not validation of general portfolio risk. Production authentication and retained/cloud activation require their own evidence.
