# Non-Functional Delta: YU13-limit-order-book

Parent: `YU12-aeron-cluster`

| ID | Delta |
|---|---|
| ND-LOB01 | Capture a per-order match-latency histogram for the crossing engine — the match operation measured directly around the engine apply on the BLP thread — reporting p50/p99/p99.9/p99.99/max in nanoseconds under closed-loop load with no coordinated omission. |
| ND-LOB02 | Keep the steady-state hot path allocation-free through the crossing book: the base and risk-gated allocation gates and the Epsilon no-GC run exercise crossing, partial fills, market-order remainder cancel, and terminal-retention eviction, and assert exactly zero bytes on the producer, journaler, and BLP threads. |
| ND-LOB03 | Re-measure booked throughput on the crossing engine with genuinely two-sided marketable flow against the inherited NFR-AC02 baseline (25,149 booked/s); the number reported for this state comes from the crossing engine, never from a prior auto-fill measurement. |
| ND-LOB04 | Keep the banned-API gate green with `LimitBook` added to the hot-path scan set — no runtime string concatenation, boxing, or other banned APIs on the crossing path. |
| ND-LOB05 | Bound book memory: per-security level arrays allocate lazily on the security's first order, sized by the configured band; band width and grid are config-identity values identical on every member and carried in the snapshot header. |
