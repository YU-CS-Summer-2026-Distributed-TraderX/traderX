# In-Memory Risk Gateway Remaining Work

This file is the explicit list of work that is still open on branch `in-memory-risk-gateway`.

## 1. Finish the full container smoke

The largest immediate blocker is the inherited smoke path.

Current state:

- the full runtime builds and starts
- health and recovery checks were demonstrated
- the smoke reaches the order-create journey
- the smoke still reports `400 invalid order payload`
- the exact same expanded request, when issued manually, returns `201`

What still needs to be done:

- trace which script and payload path the full smoke is actually executing
- reconcile the smoke fixture with the request shape that manually succeeds
- remove any temporary diagnostic edits once the discrepancy is resolved

After that, complete the remaining smoke stages:

- cancel/fill compatibility
- relational projector convergence
- NATS order/trade/position events
- WebSocket delivery
- UI checks
- risk-specific metrics and snapshot assertions

## 2. Verify durable control propagation end to end

The durable sources and consumer paths were implemented, but the full end-to-end proof is still open.

Still required:

- prove a policy outbox event reaches the Gateway and BLP
- prove a reference-data status mutation reaches both
- prove restriction updates cancel applicable resting orders
- prove the kill switch blocks risk-increasing admission
- prove unauthorized NATS identities cannot publish control subjects

## 3. Rerun clean generation after late changes

Late changes landed after the last clean generation run. A fresh generation pass is still required.

These late changes include:

- matcher bootstrap retry
- reference-data `.dockerignore`
- messaging catalog inheritance and subject-map related edits
- smoke fixture changes
- account-service runtime proxy/finality fix

## 4. Rerun the full post-generation acceptance gates

After clean generation, rerun:

- generated order-matcher tests
- generated account-service tests
- reference-data production build
- `noGcTest`
- output latency benchmark
- output topology benchmark
- full container smoke

The implementation should not be treated as final until those gates are rerun against the regenerated state.

## 5. Close performance acceptance

What is already known:

- risk-specific stages are comfortably within budget
- Gateway p99 was previously measured at `625 ns`
- BLP p99 was previously measured at `459 ns`

What remains open:

- controlled perf-profile rerun for end-to-end admission latency
- same-host `009b` baseline capture for comparison
- explicit recording of whether the final in-memory-risk-gateway path meets the `<150 us` target

The last noted demo-profile end-to-end p99 was `271,625 ns`, so this remains an open acceptance item.

## 6. Final publication and cleanup

Still required before handoff is considered closed:

- refresh `tasks.md` and `implementation-status.md`
- recapture the overlay patchset
- run `git diff --check`
- stop the Docker environment if desired
- stage/commit only if explicitly requested

## 7. Partial-work caution

This branch was interrupted due to usage limits while work was still in flight.

That means the next teammate should explicitly verify:

- whether every touched file reflects final intended behavior
- whether any temporary smoke/debug edits remain
- whether the docs overstate anything that was coded but not fully re-verified

Do not treat file presence as proof that the feature is complete.
