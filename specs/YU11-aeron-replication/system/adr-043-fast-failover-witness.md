# ADR-043: Direct heartbeat plus atomic NATS KV witness for opt-in fast failover

Status: Accepted

## Context

Direct peer heartbeat can detect silence in tens of milliseconds, but a two-replica pair cannot
distinguish process death from an asymmetric partition. Epoch rejection protects replication
streams; it does not prevent both isolated applications from admitting orders. Optimistic
promotion therefore creates a split-writer window.

## Decision

`BLP_FAILOVER_MODE=lease|fast-witness` keeps Lease as the default. Fast-witness mode uses:

1. Aeron control heartbeat at approximately 10 ms cadence and a configured 30–50 ms staleness
   threshold;
2. an atomic compare-and-set record in NATS KV bucket `TRADERX_BLP_FAST_WITNESS`;
3. the successful KV revision and assigned epoch in the synchronous order-admission fence;
4. asynchronous Kubernetes Lease reconciliation after admission opens.

A contender that cannot reach the witness, receives a CAS conflict, or has an ambiguous update
does not promote. A foreign witness revision/epoch or confirmed foreign Lease holder closes
admission and demotes before another ring claim. Witness renewal uses the same exact-revision
contract.

## Consequences

The fast path removes Kubernetes API latency from promotion while retaining one atomic external
tiebreaker. NATS witness availability becomes a failover dependency only in this opt-in mode;
witness loss preserves safety by refusing promotion. Default Lease behavior and proof remain
unchanged.
