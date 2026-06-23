# In-Memory Risk / Gateway Replica Handoff

## Purpose

This handoff is for the next architecture thread after the output-disruptor optimization work on branch
`output-event-optimization`.

The next target is not more output-disruptor tuning. The next target is the producer-side path before the
sequencer/BLP:

1. in-memory pre-trade validation / risk lane
2. gateway-side replica cache architecture

These should be treated as follow-on work to the `009b-lmax-sequencer-architecture` line.

So the next architecture work should start from the current branch/worktree that already contains the latest
LMAX output-path changes:

- repo/worktree: `/Users/yaakov/Desktop/Summer 26/lmax/traderX-output-event-optimization`
- current branch: `output-event-optimization`

## Why this is the next step

The matcher/output side has already been tightened materially:

- direct output-disruptor consumers
- handler-local allocation removed or reduced to effectively zero
- no-GC gate extended for output handlers
- output fan-out latency brought down to tens to low hundreds of nanoseconds in local handler benchmarks

The next likely bottleneck and architecture gap is before the sequencer:

- gateway/trade submission still depends on validation inputs
- account/reference/price/risk style checks need fast local state
- any blocking lookup or slow cross-service dependency before sequencing undermines the LMAX architecture

## Reference direction

There is not one single canonical paper for this target in the same way the LMAX paper cleanly anchors the
sequencer/disruptor work.

The next thread should not invent the producer-side validation architecture from scratch. It should anchor on
three reference ideas:

1. **LMAX-style single-writer/event-fed state**
   - Keep the hot decision path on local memory, not remote calls.
   - Treat state replicas as event-fed and warmable at startup.
2. **CQRS / Event Sourcing**
   - Command-side validation uses local replicas.
   - Read models and projections remain separate from the write path.
3. **Pre-trade risk controls as requirements**
   - Risk/limit checks are constraints the architecture must satisfy.
   - They are not a reason to reintroduce blocking service hops.

Good reference material for the next thread:

- Martin Fowler, *Event Sourcing*:
  [https://martinfowler.com/eaaDev/EventSourcing.html](https://martinfowler.com/eaaDev/EventSourcing.html)
- Martin Fowler, *CQRS*:
  [https://martinfowler.com/bliki/CQRS.html](https://martinfowler.com/bliki/CQRS.html)
- SEC Rule 15c3-5 / Market Access Rule as a control baseline:
  [https://www.sec.gov/rules/final/2010/34-63241.pdf](https://www.sec.gov/rules/final/2010/34-63241.pdf)

The working direction is:

- keep external API contracts stable where possible
- move pre-trade validation inputs toward event-fed, in-memory replicas
- avoid blocking REST/database calls on the producer path
- define where a future risk model would run without reintroducing network hops into the critical path

This does not mean "build a separate risk microservice first."

The better first step is to define the in-memory substrate:

- what data must be local at the gateway/receptionist
- how it is fed
- how fresh it must be
- what happens on cache miss/staleness
- what becomes a request/response event versus what must be synchronously available

## Questions the next thread should answer

1. What validations currently happen before a trade/order enters the LMAX flow?
2. Which of those validations still depend on remote services or slow shared state?
3. What data structures should hold account/reference/price/risk inputs locally?
4. Which updates should be fed from NATS/event streams versus startup preload?
5. What staleness model is acceptable for each datum?
6. What is the exact boundary between:
   - gateway/receptionist validation
   - sequenced input event creation
   - BLP-side deterministic validation
7. If a true risk engine is added later, should it be:
   - embedded in the same process as the gateway
   - a separate service feeding replicas
   - partly precomputed and partly enforced in-process

## What to inspect first

- `/Users/yaakov/Desktop/Summer 26/lmax/traderX/LMAX-SEQUENCER-ARCHITECTURE.md`
- `/Users/yaakov/Desktop/Summer 26/lmax/traderX/LMAX-BLP.md`
- `/Users/yaakov/Desktop/Summer 26/lmax/traderX/catalog/learning-paths.md`
- `/Users/yaakov/Desktop/Summer 26/lmax/traderX/catalog/state-catalog.json`
- current generated/runtime code for `009b-lmax-sequencer-architecture`
- current `trade-service`, `account-service`, `position-service`, and `reference-data` responsibilities

## Suggested starting workflow

From the worktree with the current LMAX output work:

```bash
cd "/Users/yaakov/Desktop/Summer 26/lmax/traderX-output-event-optimization"
git checkout output-event-optimization
git pull --ff-only origin output-event-optimization
```

Then create a new branch for the next architecture state off this branch and continue the `009b` line.

## Scope for the next thread

The next thread should be architecture-first, then spec generation, then implementation:

1. map the current producer-side validation path
2. identify remote dependencies and shared-state dependencies
3. propose the in-memory validation/risk substrate using the reference direction above
4. define how gateway-side replicas are populated and warmed
5. decide what belongs in the gateway versus the BLP
6. define the next state/spec pack
7. generate specs
8. only after that move into implementation

Do not start by coding a risk engine.
Do not start by changing the output disruptor again.

## Expected deliverables from the next thread

- clear diagram of the producer-side path
- current-state versus target-state validation flow
- explicit local replica inventory
- freshness/staleness model per replica
- failure/degraded-mode rules
- proposed next state name and lineage
- spec pack plan
- generated spec scope before implementation
