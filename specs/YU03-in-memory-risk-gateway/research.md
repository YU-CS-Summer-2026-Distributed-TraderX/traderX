# Research: YU03 In-Memory Risk Gateway

## Control baseline

**SEC Rule 15c3-5 (Market Access)** is used as a control-requirements baseline only — pre-set
credit/capital limits, erroneous-order (size/notional/price-collar) checks, restrictions, and
kill switches applied *before* an order is executable. This state does not claim regulatory
compliance; it implements the control shape, not a supervisory/legal program.

## Why in-memory admission (not a synchronous risk service)

The inherited LMAX design already removed blocking work from the hot path. A synchronous remote
risk microservice on the command path would re-introduce the latency, timeout, and availability
coupling the architecture exists to avoid. Event-fed local replicas + an authoritative in-BLP
decision keep admission on memory only (FR-IMRG01) while remaining correct under concurrency,
because the single-writer BLP owns exact aggregate exposure (ADR-018).

## Why control events must be journaled (determinism)

Replaying a historical order while querying *today's* account/security/policy state produces a
different decision — breaking the replay determinism the journal exists to guarantee. Sequencing
control changes into the same global input stream (ADR-020) makes each decision a pure function of
(snapshot + journal), so incidents reproduce and warm standbys agree. Alternatives (query-on-replay,
snapshot-only, out-of-band cache feed, policy-id-only) were rejected — see ADR-020.

## Forward-port constraints that shaped the design

The stale `in-memory-risk-gateway` branch was based on `YU01` (pre-k8s). The `YU02` base diverged:

- **Journal + replication share a fixed 64-byte record**, and snapshot recovery is keyed to journal
  *byte offsets*. The stale branch's 96-byte CRC'd record + legacy upcasters would orphan existing
  journals and break offset recovery. Decision: reuse unused payload slots per event type instead of
  growing the record (verified old journals replay unchanged).
- **orderRef is monotonic/unbounded** here (bounded terminal retention + array doubling), so the
  stale branch's dense orderRef-indexed reservation arrays would exhaust at pool size and
  permanently CAPACITY-reject. Decision: reservations ride the pooled order entry; aggregates
  rebuilt from open orders on restore.
- **SymbolTable persists security ids** across restarts (`symbols.tab`). The stale replica minted
  its own ids. Decision: SymbolTable is the single id authority; the replica aligns to it at startup.
- **Snapshot is a single `snapshot.dat`** with atomic rename. Decision: extend it to v3 in place
  (v1/v2 still load) rather than add separate risk snapshot files.
- **`TYPE_SNAPSHOT = 6`** was already journaled on `YU02`. Decision: control event ids start at 7.

## Deferred source-side work

The durable, watermarked account-service/reference-data control feeds (ADR-019) are a source-side
change (outbox + snapshot-watermark APIs on other services) and are out of scope for the first
order-matcher-only slice. The journal-sequenced one-shot bootstrap is a safe interim because there
is no live external delta stream yet for a snapshot/subscribe handoff to race against.

## Zero-allocation discipline

The Tier-2 decision path is preallocated primitive arrays with open-addressing probing and integer
fixed-point math (Px ticks) — no boxing, iterators, lambdas, clock reads, or randomness on the BLP
thread (NFR-IMRG02/04). The stale branch recorded gateway p99 ≈ 625 ns and BLP p99 ≈ 459 ns for the
same pipeline shape; a YU03 perf-profile acceptance run is deferred (NFR-IMRG01).
