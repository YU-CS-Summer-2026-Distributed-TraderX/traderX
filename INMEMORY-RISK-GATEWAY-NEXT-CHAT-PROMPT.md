# Next Chat Prompt: In-Memory Risk / Gateway Replica Architecture

We are continuing TraderX architecture work in:

- `/Users/yaakov/Desktop/Summer 26/lmax/traderX-output-event-optimization`

Start from this branch/worktree on the `009b` line:

```bash
cd "/Users/yaakov/Desktop/Summer 26/lmax/traderX-output-event-optimization"
git checkout output-event-optimization
git pull --ff-only origin output-event-optimization
```

The next task is architecture-first, not implementation-first:

1. analyze the current producer-side validation path before the sequencer/BLP
2. identify remaining blocking/shared-state dependencies
3. propose an in-memory pre-trade validation / risk lane using established reference material rather than
   inventing from scratch
4. propose gateway-side replica cache architecture using established reference material rather than inventing
   from scratch
5. define the likely next state and how it should fit into the `009b` LMAX line
6. after architecture, generate specs
7. only after specs, move into implementation

Read these first:

- `/Users/yaakov/Desktop/Summer 26/lmax/traderX/LMAX-SEQUENCER-ARCHITECTURE.md`
- `/Users/yaakov/Desktop/Summer 26/lmax/traderX/LMAX-BLP.md`
- `/Users/yaakov/Desktop/Summer 26/lmax/traderX-output-event-optimization/INMEMORY-RISK-GATEWAY-HANDOFF.md`

Reference material to use:

- Martin Fowler, *Event Sourcing*:
  [https://martinfowler.com/eaaDev/EventSourcing.html](https://martinfowler.com/eaaDev/EventSourcing.html)
- Martin Fowler, *CQRS*:
  [https://martinfowler.com/bliki/CQRS.html](https://martinfowler.com/bliki/CQRS.html)
- SEC Rule 15c3-5 / Market Access Rule:
  [https://www.sec.gov/rules/final/2010/34-63241.pdf](https://www.sec.gov/rules/final/2010/34-63241.pdf)

Important boundaries:

- Do not start by coding a risk engine.
- Do not start by changing the output disruptor.
- Start by understanding what validations need fast local state and what data must be replicated into memory.

Questions to answer:

1. What validations currently happen before sequencing?
2. Which still depend on remote services?
3. What local replicas are required for fast validation?
4. What is the freshness model for each replica?
5. What belongs at the gateway and what belongs in the BLP?
6. How should a future risk model plug into this without reintroducing blocking hops?

Deliverables:

- current-path analysis
- target architecture proposal
- state-lineage recommendation
- spec-generation plan
- implementation plan after specs
