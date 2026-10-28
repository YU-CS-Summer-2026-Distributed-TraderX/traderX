# ADR-091: Exactly-once order entry is the client's resend plus the engine's key

Status: accepted 2026-10-28 (coordinator queue). Pack: [exactly-once client](../maintenance/exactly-once-client/spec.md)
(FR-XOC-01..05). Builds on YU03 FR-IMRG14 and ADR-086.

## Context

A gateway kill or a leader kill leaves orders in flight whose outcome the client cannot know: the gateway died
before answering, or answered "ambiguous" (no committed decision within its timeout). The hxb loadgen treated the
first as fatal and booked the second as final, so every failover arm ended with orders counted "other" and a
native replay run with an allowance for them. The engine, meanwhile, already deduplicates by the client's key in
replicated state (FR-IMRG14), consulted before risk, identical on every member and across elections.

## Decision

1. **The client owns completion.** It keeps every un-acked order, and after a lost connection (reconnecting to
   any gateway) or an ambiguous answer it resends with the SAME key until it gets a committed answer. It never
   invents an outcome. A second answer naming a different order for one key is a violation, not a duplicate ack.
2. **The engine owns uniqueness.** A retry of an accepted key restates the original order and changes nothing
   (FR-XOC-02): one unpublished output, no trade, no reservation. Once the original has left terminal retention
   the answer is REJECTED/DUPLICATE naming its ref; a client reads that as the receipt for that ref. On an
   ADR-090 sequenced stream, a resend more than W behind the stream's next is answered REJECTED/DUPLICATE with no
   ref. That is the receipt for its `seq`: the client reconciles the order by status and never resends it
   (FR-XOD-03).
3. **FIX speaks FIX.** That DUPLICATE answer reaches a FIX client as an Order Status report (`150=I`) for
   `ord-<ref>`, its state from the read model when there is one. Before this it was `39=8` with `OrderID NONE`,
   which a FIX client would book as a reject of an order that had executed.
4. **The window is written down.** The table is 262,144 keys, so the time a resend may take is rate-dependent
   (FR-XOC-04). Failover proofs run at rates where it holds; a strict replay fails any that does not.

## Rejected

- **Gateway-side retry.** The gateway that died is the one that knew; a surviving gateway has no record of the
  dead one's in-flight orders. Only the client spans gateways.
- **Gateway-local dedupe ledgers.** Not replicated, gone with the gateway, and they disagree across replicas.
- **A larger count window to cover 400K/s failovers.** ~6M keys: a ~170 MB snapshot section and an apply-thread
  walk already measured as a tail source (hxb stall causes). Per-stream contiguous sequences are proposed as
  [ADR-090](adr-090-exactly-once-dedupe.md), a separate decision.

## Consequences

- Failover arms can be held to 0 ambiguous with a strict replay (allowance 0) when the client resends.
- Up to the window, exactly-once holds across any number of gateway and leader failures; past it, a resend can
  execute twice, visibly.
- FIX clients need global CompIDs (the key includes the CompID) and, to collect answers sent while disconnected,
  ADR-086 persistent sessions.
