# Non-Functional Delta: YU13-limit-order-book

Parent: `YU12-aeron-cluster`

| ID | Delta |
|---|---|
| ND-LOB01 | Capture a per-order match-latency histogram for the crossing engine — the match operation measured directly around the engine apply on the BLP thread — reporting p50/p99/p99.9/p99.99/max in nanoseconds under closed-loop load with no coordinated omission. |
| ND-LOB02 | Keep the steady-state hot path allocation-free through the crossing book: the base and risk-gated allocation gates and the Epsilon no-GC run exercise crossing, partial fills, market-order remainder cancel, and terminal-retention eviction, and assert exactly zero bytes on the producer, journaler, and BLP threads. |
| ND-LOB03 | Re-measure booked throughput on the crossing engine with genuinely two-sided marketable flow against the inherited NFR-AC02 baseline (25,149 booked/s); the number reported for this state comes from the crossing engine, never from a prior auto-fill measurement. |
| ND-LOB04 | Keep the banned-API gate green with `LimitBook` added to the hot-path scan set — no runtime string concatenation, boxing, or other banned APIs on the crossing path. |
| ND-LOB05 | Bound book memory: per-security level arrays allocate lazily on the security's first order, sized by the configured band; band width and grid are config-identity values identical on every member and carried in the snapshot header. |

## Added later — tracing across consensus, and the KDB-X capture tap

| ID | Delta |
|---|---|
| ND-TR01 | Never block the trade path for telemetry: a producer copies a fixed record into a pre-allocated ring buffer and returns — no lock, no allocation, no I/O, and no backpressure path back to the caller. A full ring drops and counts; dropping telemetry under load is the designed behaviour, not a degradation. |
| ND-TR02 | Keep every expensive step off the order threads: hex formatting, JSON assembly, HTTP and retries all run on one daemon thread, so a collector outage costs a counter rather than a millisecond. |
| ND-TR03 | Hold the allocation gates and the Epsilon no-GC run with tracing compiled in. The OpenTelemetry SDK is not used — its API allocates per span — so OTLP/HTTP is emitted directly with a JSON body, adding no dependency. |
| ND-TR04 | Keep the derivation one-way: it consumes a committed field, is never written back, never encoded into an output event, and never branched on by the engine, so deleting the tracing code leaves every member's output byte-identical. |
| ND-TR05 | Keep the KDB-X capture tap out of the apply path and bounded on disk: non-blocking offer, daemon-thread I/O, loud drop counters, and a `KDB_TAP_MAX_MB` ceiling so analytics can never consume the disk the Aeron Archive needs. |
