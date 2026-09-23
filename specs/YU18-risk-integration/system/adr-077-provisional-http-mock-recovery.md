# ADR-077: Provisional HTTP mock and recoverable remote attempts

Status: Accepted for TraderX's local development tools, 2026-09-15.

## Context

Alex agrees that TraderX owns durable job coordination and that results need deterministic workload lookup. The final pricing wire contract remains under discussion. We can test network and process-failure handling without freezing his API or fabricating prices.

## Decision

Add a separate fixed HTTP mock profile and a loopback-only adapter/fake worker. Retain the existing bundle identity and non-pricing result semantics. Reject mixed adapter profiles within an existing coordinator state.

Lookup precedes submission. Pending/uncertain network outcomes retain the same durable RUNNING attempt, which later reconciles by workload identity. A locally published result can finish without the remote worker. Declared failure requires explicit retry. Hash both the accepted result and its worker provenance.

## Consequences

The original in-process mock remains the default; its workload IDs and recovery behavior are preserved. Tests require localhost socket permission but no cloud access. The fake worker durably stores accepted requests and results; it is deliberately neither a pricing engine nor an overnight scheduler. The real adapter still needs Alex's agreed schemas, authentication, artifact transport, capabilities and financial validation.

Local HTTP tests include dropped submission replies, timeouts, worker-store restart, coordinator process exit before/after local publication, profile separation, integrity failures and rejected numeric mock results. A CLI demonstration restarts an actual fake-worker process and retrieves the same persisted result from a new consumer.
