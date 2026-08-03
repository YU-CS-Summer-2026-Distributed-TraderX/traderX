# Non-Functional Delta: YU11-aeron-replication

Parent: `YU10-fix-ingress`

| ID | Delta |
|---|---|
| NFD-AR01 | Primary encode/claim and follower poll/decode/inject are exact-zero after warm-up under isolated `-Xbatch`; inherited allocation/Epsilon gates stay exact-zero. |
| NFD-AR02 | Archiving Media Driver sidecar stays within 750m requested/1 CPU limited and 512Mi requested/1Gi limited. |
| NFD-AR03 | Three comparable GKE Aeron HA runs reach ≥35k booked/s and ≥25% above the immediately preceding File-backed NATS HA mean, with zero failed/risk-misclassified submissions. |
| NFD-AR04 | Same-day single-BLP REST/batch and journaled-BLP controls remain within 5% or measured noise; risk p99/output topology do not materially regress. |
| NFD-AR05 | Default healthy-follower primary-kill failover is ≤3s p95; fast-witness phase timestamps measure the 30–60 ms target independently. |
| NFD-AR06 | Loss/partition/restart/DNS/empty-volume/corruption/disk-full/schema tests never expose readiness or promotion across a sequence gap. |
| NFD-AR07 | Every performance record includes exact source/image/schema/config/node identities, per-run values, comparator, and same-day controls. |
