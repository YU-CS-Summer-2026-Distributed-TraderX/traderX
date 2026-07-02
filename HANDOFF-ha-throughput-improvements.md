# HA Throughput Improvement Brainstorm — lmax-kubernetes-blp-ha

## Baseline numbers (macOS M-series, local SSD, batchRecords=1024)

| Tier | Scenario | Measured orders/sec |
|------|----------|-------------------|
| 0 | Journaling only (no replication gate) | ~2,700,000 |
| 1 | RTT = 1 ms | ~919,000 |
| 2 | RTT = 2 ms | ~459,000 |
| 3 | RTT = 5 ms | ~203,000 |
| 4 | RTT = 10 ms | ~101,000 |

**Ceiling model:** `throughput = batchRecords / RTT_ms * 1000`  
At GKE intra-zone NATS RTT of 1–5 ms, the HA branch ceiling is **200K–920K orders/sec BLP-internal**.  
The E2E ceiling is ~1K/sec (DB projector/Postgres). All HA improvements below target the BLP internal path; none move the E2E bottleneck.

---

## The core constraint

At every journal fsync boundary (`batchRecords` events), the `NatsJournalReplicator` publishes
the batch to NATS JetStream and **spin-waits at `endOfBatch` until the follower ACKs**. This is
intentional durability: the BLP cannot advance past unacknowledged events. The ceiling is:

```
throughput ≤ batchRecords / NATS_RTT
```

Improvement vectors fall into three categories:
1. **Raise the numerator** — bigger effective batch before each ACK wait
2. **Lower the denominator** — faster or fewer ACK round-trips
3. **Remove the bottleneck entirely** — async replication or transport replacement

---

## Vector 1 — Larger batchRecords (raise numerator, zero infra cost)

**Idea:** Increase `batchRecords` from 1024 to 4096 or 16384. The journaler coalesces more
events per fsync, and the replicator waits for ACK less often.

**Expected gain:** Linear. `batchRecords=4096` at 2ms RTT: 4096/2ms ≈ 2M orders/sec (vs 459K today).

**Tradeoff:** Recovery replay time grows (larger atomic chunk). Failover durability window grows:
under a crash, up to `batchRecords` events written to the follower but not yet ACKed may need
to be replayed from the follower's journal, not the primary's. With `batchRecords=4096`, the
replay window is 4× larger.

**Verdict:** Best first lever. Cheap — change one env var (`JOURNAL_BATCH_RECORDS`). Measure
replay time impact in a chaos test.

---

## Vector 2 — Pipelined / lagged ACK (raise effective numerator, moderate complexity)

**Idea:** Allow the primary to run N batches ahead of the follower's ACK. Instead of:
```
publish batch → wait ACK → publish batch → wait ACK
```
do:
```
publish batch1 → publish batch2 → wait ACK(batch1) → publish batch3 → wait ACK(batch2) → …
```

The effective throughput ceiling becomes `N × batchRecords / RTT` — the pipeline depth multiplies
the numerator. This is the standard technique used in DRBD, Raft pipelines, and LMAX's own
multi-producer topologies.

**Implementation sketch:**
- Replace the spin-wait on follower ACK with a semaphore with `N` permits.
- Replicator acquires a permit before publishing each batch; follower ACK releases a permit.
- MatchingEngine is still gated behind the replicator sequence, but the replicator can run
  up to N batches ahead.

**Risk:** Under a primary crash, the follower may have received batches it hasn't yet applied to
its own matching engine. The follower's recovery needs to re-apply those pipelined batches from
its own journal. This is safe if the follower journals every received event before ACKing.

**Verdict:** High-value, moderate complexity. Likely 2–5× gain over Vector 1 alone. Implement
after validating Vector 1 numbers.

---

## Vector 3 — Replace NATS with Aeron for replication (lower denominator, high complexity)

**Idea:** NATS JetStream RTT on GKE intra-zone is 1–5ms due to TCP overhead, JetStream protocol
framing, and kernel scheduling jitter. Aeron (`io.aeron:aeron-all`) uses UDP with busy-spin
polling, achieving sub-microsecond intra-process and ~200ns–1µs intra-host RTT.

**Expected gain:** At 200ns RTT with `batchRecords=1024`: 1024/0.0002ms ≈ **5 billion orders/sec**
ceiling. In practice the journaling fsync becomes the ceiling again (~2.7M/sec on SSD).

**Tradeoff:**
- Aeron requires dedicated polling threads (busy-spin media driver). Adds CPU overhead even
  when idle.
- Much larger deployment footprint: Aeron media driver as a sidecar or embedded.
- Loss of JetStream persistence: Aeron doesn't persist messages by default, so follower recovery
  would rely entirely on its own journal replay rather than NATS replay.
- GKE networking: Aeron UDP may be throttled by VPC MTU or GKE network policy.

**Verdict:** Justified only if production load genuinely exceeds 500K orders/sec AND Aeron's
operational complexity is acceptable. Not a near-term recommendation.

---

## Vector 4 — Follower batch-coalescing ACK (lower denominator without transport change)

**Idea:** Today the follower ACKs every received batch individually. If the follower processes
multiple received batches per `endOfBatch` (because it's faster or because the primary sent
several in flight), it can coalesce those into a single ACK for the highest sequence. This
reduces NATS publish count on the follower→primary path.

**Expected gain:** Reduces effective RTT by batching ACKs. Hard to quantify without profiling
the follower path, but likely 10–30% improvement in ACK throughput at high load.

**Implementation:** In `NatsJournalReplicator` on the follower, track the highest applied
sequence and publish ACK once per `endOfBatch` rather than once per received batch.

**Verdict:** Low complexity, likely incremental gain. Worth layering on top of Vector 1.

---

## Vector 5 — Pod co-location (lower denominator, operational tradeoff)

**Idea:** The StatefulSet currently has `requiredDuringSchedulingIgnoredDuringExecution`
pod anti-affinity (different nodes). Relaxing this to `preferredDuringScheduling` allows GKE
to place both replicas on the same node, cutting NATS RTT to intra-host (<100µs) or even
intra-process if using NATS with embedded server.

**Expected gain:** At 100µs intra-host RTT: 1024/0.1ms ≈ **10M orders/sec** ceiling — well above
the journaling ceiling, making replication free.

**Tradeoff:** Loses node-level HA. A node failure takes down both primary and follower
simultaneously, defeating the purpose of HA. This is only useful for performance benchmarking,
NOT for production HA.

**Verdict:** Useful for isolating the network component of RTT in benchmarks. Do NOT deploy
as a production HA strategy.

---

## Vector 6 — Async journal, sync replication only (architectural trade)

**Idea:** Decouple journaling from replication. The primary replicates synchronously (waits for
follower ACK) but journals asynchronously (background writer, no fsync on the hot path). On a
crash, the primary recovers from the follower's journal rather than its own.

**Expected gain:** Removes the journaling fsync from the hot path entirely. The ceiling becomes
`batchRecords / NATS_RTT` where `batchRecords` is now unconstrained by fsync latency.

**Tradeoff:** More complex recovery: primary must always recover from follower (or require
follower to be available). The journaler on the primary becomes write-ahead-log-style with no
durability guarantee. This is effectively the Raft model.

**Verdict:** Sound architectural direction, but significant complexity. Requires redesigning the
recovery path. Defer until replication is proven stable.

---

## Summary and recommended sequence

| Priority | Vector | Effort | Expected gain at 2ms RTT |
|----------|--------|--------|--------------------------|
| 1 | Larger `batchRecords` (4096) | 1 env var change | 2M/sec (4× today) |
| 2 | Pipelined ACK (N=4) | ~2 weeks | 8M/sec |
| 3 | Follower ACK coalescing | ~1 week | +20% on top of pipeline |
| 4 | Aeron transport | ~4 weeks + ops | Removes ceiling entirely |
| 5 | Co-location (bench only) | 1 YAML change | Removes network RTT from benchmarks |
| 6 | Async journal | ~3 weeks + redesign | Removes fsync from ceiling |

**Start with Vector 1.** Set `JOURNAL_BATCH_RECORDS=4096` in the StatefulSet and rerun the
`ReplicationThroughputBenchmarkTest` to confirm the 4× gain. Check failover replay time
(run `kubectl delete pod order-matcher-0` and time until readiness). If replay is acceptable,
ship it.

**Vector 2 next** if 2M/sec is still not enough. The pipelined ACK implementation lives
entirely in `NatsJournalReplicator` — no topology changes.
