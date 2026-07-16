# ADR-035: Durable ClOrdID correlation ledger, joined on inputSeq

**Status**: Accepted · **State**: YU10-fix-ingress

## Context

FIX requires bidirectional correlation: inbound, a cancel or status request names an order by
(Orig)ClOrdID; outbound, every lifecycle event must reach the owning session with the client's
ClOrdID. The correlation must survive restarts (a fill can land after a reconnect), must detect
duplicate ClOrdIDs, and must not touch the matcher's pooled state or wire formats.

## Decision

An append-only binary ledger on the order-matcher PVC records
(sessionKey, ClOrdID, inputSeq, orderRef) BEFORE the order is published to the ring, with the
journal's amortized-force durability discipline, full rehydration at startup, and fail-closed
admission when the ledger cannot accept writes. Outbound correlation joins
`OutputEvent.inputSeq` — already present on every lifecycle output event, the same field the
REST gateway acknowledgement path correlates on — through the rehydrated in-memory maps.

## Alternatives considered

- **A session/origin field on OutputEvent (and the pooled order state)**: rejected — it widens
  pooled hot-path objects and the snapshot surface for data that is session-scoped, and
  `inputSeq` already provides a complete join key without touching any pooled type.
- **Journaling the FIX identity inside the input record**: rejected — the 64-byte journal and
  replication records are fixed-layout hot-path formats shared with HA replication; carrying a
  variable-length client string there is a wire-format change serving a need the ledger meets
  off the hot path.
- **In-memory map only (no durability)**: rejected — a restart would orphan every live order's
  correlation: fills after recovery could not be reported to the owning session, and duplicate
  detection would silently reset, allowing a replayed ClOrdID to execute twice.

## Consequences

- Ledger write precedes ring publish: an order can exist in the ledger without having reached
  the ring (pre-publish failure) — harmless, the entry simply never receives events; the reverse
  (an order on the ring with no ledger entry) cannot happen, which is the invariant that matters.
- Live orders are never evicted; ledger capacity exhaustion fails closed (FR-FIX10) and the FIX
  data directory participates in disk-watermark alerting (NFR-FIX04).
- Rehydration cost is linear in ledger size and runs alongside journal replay inside the
  existing startup budget.
