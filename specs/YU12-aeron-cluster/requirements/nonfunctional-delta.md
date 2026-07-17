# Non-Functional Delta: YU12-aeron-cluster

Parent: `YU11-aeron-replication`

| ID | Delta |
|---|---|
| NFD-AC01 | The clustered service application thread is exact-zero-allocation after warm-up under the inherited isolated `-Xbatch` gates; inherited base/risk/Epsilon gates stay exact-zero. |
| NFD-AC02 | Three comparable 30-second GKE runs (label `aeron-cluster`, inherited harness) meet or exceed the stored YU11 Aeron HA baseline (25,149 booked/s cleanest run; single-BLP parity) with zero failed/risk-misclassified submissions. |
| NFD-AC03 | Client-observed failover — leader kill to first accepted order through the gateway — completes in under 1,000 ms. |
| NFD-AC04 | The strict no-ID-reuse recovery proof passes: post-snapshot orders, recovery from snapshot + log tail, promotion, next ID strictly greater than every ID ever issued. |
| NFD-AC05 | Three members preserve commit availability through one member failure; snapshot/log disk usage stays bounded by post-snapshot log management. |
| NFD-AC06 | Every performance record stores exact source/image/schema/config/node identities, per-run values, means, and same-day comparators. |
