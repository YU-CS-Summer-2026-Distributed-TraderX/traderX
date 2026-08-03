# ADR-042: Per-pod Archiving Media Driver sidecar under a one-core budget

Status: Accepted

## Context

Embedding the media driver and Archive in the Spring JVM couples their duty cycle to application
pauses and obscures their CPU, heap, and GC cost. A fully dedicated busy-spin topology competes
with the REST gateway, journaler, and BLP on a four-vCPU c2 node.

## Decision

Each order-matcher pod contains a small Java Archiving Media Driver sidecar.

- Media Driver and Archive use shared threading mode with backoff idle strategy.
- Resource contract: 750m CPU request, 1 CPU limit, 512Mi memory request, 1Gi memory limit.
- The application and sidecar share a memory-backed Aeron directory with 512Mi size limit.
- Archive catalog/segments use the persistent order-matcher volume; existing claims are expanded
  through the recorded StorageClass/PVC/StatefulSet procedure.
- Sidecar and application images carry the same SBE schema checksum and refuse mismatch.
- Sidecar health and counters expose duty-cycle stalls, recording/replay positions, retransmits,
  loss gaps, catalog errors, and disk free bytes.

## Consequences

Transport runtime cost is independently observable and restartable. The hard CPU cap prevents a
microbenchmark from borrowing application cores and presenting the result as an end-to-end gain.
Application readiness depends on sidecar continuity and Archive health.
