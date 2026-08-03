# ADR-060: Derive Trace Context Across the Consensus Boundary Instead of Carrying It

**Status:** Accepted, implemented
**Date:** 2026-07-29
**State:** `YU13-limit-order-book` (added after the state's original implementation)

## Context

A useful trace of an order has to span gateway → sequence → consensus commit → apply → egress. The
gateway and the members are different processes joined only by the replicated log, so the trace has
to cross a consensus boundary — and the standard answer to that (propagate a `traceparent` with the
message) is the one thing this system may not do.

Bytes in the log are replicated state. Adding a field is a schema change, a member roll, and a
standing determinism risk taken on behalf of a debugging feature. It is also a correctness hazard
in its own right: a resend carrying a *fresh* trace id would no longer be byte-identical to the
original, so replay would stop reproducing — the property the whole architecture is built to hold.

The platform side was already in place. The Kubernetes runtime has shipped an OTel Collector,
Tempo, Loki, Prometheus, Grafana and promtail since state `007`. What was missing was anything
emitting to it — there were zero OTLP emitters anywhere in the tree — and any Prometheus scrape of
the Aeron cluster tier at all, so `traderx_cluster_next_order_ref`, the committed ground truth,
existed on `/metrics` and reached no dashboard.

## Decision

1. **Derive, don't carry.** Every order already carries a client idempotency key through the log,
   set by the gateway from the ClOrdID and read by the engine for duplicate suppression. It is
   business data that is already replicated, already unique per order, and already identical on
   every member and on replay. Both tiers run the same pure function over it:

   ```
   traceId       = splitmix64(key), splitmix64(key ^ TRACE_SALT)   (128-bit)
   sampled?      = (splitmix64(key ^ SAMPLE_SALT) & mask) == 0
   clusterSpanId = splitmix64(key ^ CLUSTER_SALT)
   ```

   A member independently arrives at the same trace id, the same parent span id and the same
   sampling verdict the gateway did, with zero bytes added to the log, zero schema change, and
   nothing new for the state machine to read. The member derives the key *before* the sequenced
   generator overwrites `orderRef`, so both sides hash identical input.

   Sampling is still decided at ingress in the sense that matters — it is a property of the order,
   fixed before the order is offered. It simply needs no carriage.

2. **The derivation is one-way and read-only.** It consumes a committed field and produces an id
   that is never written back, never encoded into an output event, and never branched on by the
   engine. Delete the tracing classes and every member still emits byte-identical output. This is
   what makes the whole approach admissible: it is not telemetry in replicated state, it is a
   function of replicated state computed outside it.

3. **Two capabilities fall out for free, and both are consequences of the choice rather than
   features designed alongside it.** A rejected order is force-sampled by both tiers
   independently, because "was it rejected" is likewise a committed, deterministic fact read off
   the same ack — error sampling is only possible *at the head* here, and a collector's tail
   sampling processor cannot recover a span head sampling never emitted. And a log line joins its
   trace by computing the id rather than being handed one, so the id lives in the line itself, not
   in a label on the stream.

4. **Never block the trade path.** A producer — a REST or FIX submit thread, the gateway's owner
   thread, a member's apply thread — copies eight longs into a pre-allocated Agrona ring buffer and
   returns. No lock, no allocation, no I/O, and deliberately no backpressure path back to the
   caller. A full ring drops the span and increments a counter. Hex formatting, JSON assembly,
   HTTP, retries and collector outages all happen on one daemon thread that no order ever touches.

5. **Not the OpenTelemetry SDK.** Its batching processor has the right shape — bounded queue, drop
   on full — but the API above it allocates per span, on a path that runs under an allocation gate
   and under Epsilon GC in the no-GC proofs, where a single allocated byte fails the build. OTLP
   over HTTP with a JSON body is emitted directly to the same `/v1/traces` endpoint any SDK would
   use: about a hundred lines, and no new dependencies, since Agrona and `java.net.http` are
   already here.

6. **Members are scraped per pod through the headless service.** Role, applied sequence and next
   order reference are per-member facts; "which one is the leader" is unanswerable if a
   Service-level scrape round-robins the three.

## Alternatives Considered

- **A `traceparent` field in the sequenced message** — rejected, and it is the decision this ADR
  exists to record. Schema change, member roll, permanent determinism risk, and a resend that is
  no longer byte-identical to the original.
- **Trace only the gateway, stopping at the offer** — rejected. The consensus segment is exactly
  the black box worth instrumenting; a trace that ends at the boundary answers none of the
  questions that motivated it.
- **Carry the context out-of-band in a side map keyed by order reference** — rejected. It needs
  cross-process shared state with its own lifetime, eviction and failure modes, and it would be
  wrong precisely when it is needed most: after a failover, when the new leader has no such map.
- **Tail sampling in the collector instead of head sampling** — rejected as the mechanism for
  errors. A tail processor can only select among spans it received; a rejected order whose spans
  head sampling never emitted is unrecoverable downstream.
- **The OpenTelemetry SDK with a `BatchSpanProcessor`** — rejected on allocation, as above. The
  shape was right; the API was not.
- **Route metrics through the collector alongside traces** — rejected. Metrics are scraped
  directly, so a collector outage costs traces and leaves the numbers that answer "is the cluster
  healthy" untouched.
