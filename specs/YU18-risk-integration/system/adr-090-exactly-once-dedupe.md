# ADR-090: Exactly-once order entry at 400K/s: per-stream contiguous sequences

Status: proposed 2026-10-28; awaiting yaakov's approval. No engine change is made until it is approved.
Requirements: [exactly-once dedupe pack](../maintenance/exactly-once-dedupe/spec.md), FR-XOD-01..12 and
NFR-XOD-01..03. Base requirements: YU03 FR-IMRG14 (the idempotency retention frontier), YU10 FR-FIX10
(CompID-qualified ClOrdID), and ADR-086 §4 (FIX resend after a reconnect). Evidence: the hxb rig
(`independent-baseline/results-20261025-open/STALLS.md` §4, the 1-hour 400K/s `xp` arm) and the
exactly-once client hand-over (loadgen `plan.exactlyOnce`, arm `xo`).

## Context

The engine recognises a resent order by its key: either the binary client's `u64 clOrdId`, or the gateway's
FNV-1a 64 hash of `TargetCompID \001 ClOrdID`. Each key is scoped by account (`scopeKey`, ENGINE-REVIEW G1).
`BlpRiskState` keeps the last `IDEMPOTENCY_CAPACITY` = 262,144 keys in a FIFO retention ring (FR-IMRG14), so it
covers 262,144 / rate seconds. It was sized for ~3K/s, about 90 s of cover. At 400K/s it covers **0.66 s**.

A resend arrives much later than that after the faults it exists for. In the 1-hour 400K/s `xp` arm:

| Stage | Measured | Source |
|---|---|---|
| Leader election (heartbeat timeout 200 ms) | INIT→CLOSED ≈ 0.36 s | `xp`, STALLS.md §4 |
| Gateway answers stranded orders AMBIGUOUS | 1,995 orders at 11,998–12,006 ms (12 s reap deadline) | `xp` |
| Committed but unreceipted at the client | 24 orders | `xp` strict replay |
| Client reconnect after a gateway kill | first ack 711 ms after the kill; full recovery 4.0 s; reconnect budget 30 s | exactly-once client, local proof |

Those 24 committed-but-unreceipted orders are the case exactly-once must handle. The client resends each one
either on its AMBIGUOUS answer (12 s at the earliest) or after its gateway dies (up to 30 s later). A key window
must therefore hold every key for **B = 12.006 s + 30 s + 0.36 s ≈ 42.4 s, stated as 45 s**, which is
18,000,000 keys at 400K/s. Today's window holds 262,144. Inside the window, the engine answers a resend with the
original order (`MatchingEngine.answeredAsDuplicate`). Outside it, the resend books a second order.

## Options

### (a) A time- or sequence-sized key window

This option keeps today's mechanism but evicts by cluster time: every key younger than 45 s of log time stays in
the table. A count-sized ring is not enough, because its cover in time shrinks as the rate rises. Eviction reads
only the log timestamp, so it stays deterministic. The cost scales linearly from the measurements:

| | Today (262,144 keys) | 45 s at 400K/s (18M keys, ×68.7) |
|---|---|---|
| Snapshot bytes (28 B/entry) | 7.3 MB | **504 MB per member per snapshot** |
| Heap (presized table + ring) | ~8.5 MB | ~0.6 GB; ~1.1 GB with power-of-two slot rounding |
| Leader snapshot halt (ring walk; measured medians 14 ms and 18.5 ms) | 14–18.5 ms | **≈ 0.96–1.27 s**, linear in entries |

A 1-second halt is five times the 200 ms heartbeat timeout. Every snapshot would then trigger an election, the
very event the window is there to absorb. Memory would also scale with the peak rate rather than with the bound.
The snapshot format would be unchanged (T_IDEMPOTENCY) and there would be no wire change. **Rejected.**

### (b) A per-stream high-water mark

Each client stream numbers its requests. The engine keeps one high-water mark per stream and treats any
`seq ≤ HWM` as a duplicate. The state is O(streams), with no time bound.

The flaw is that a high-water mark cannot tell "seen" from "lost". A fault can lose a range of a stream while
later requests on it still commit:

- an election truncates the uncommitted tail, and the gateway then sends newer requests to the new leader;
- during a reconnect, the old and the new gateway can both be sending the same stream.

A lost request below the mark is then refused as a duplicate: it never executes, yet the client is told it did.
There is also no stored reference to answer a resend with. **Rejected as stated**; it is the core of (c).

### (c) A contiguous per-stream sequence with a bounded answer ring (recommended)

This is (b) made sound by **contiguity**, the same rule FIX applies to `MsgSeqNum`:

1. A request on a sequenced stream carries `(streamId, seq)`. The engine keeps `next` for each
   `(account, streamId)`. A new stream starts at `next = 1`. It never adopts its first `seq`, because a lost
   `seq` 1 would then look like a duplicate.
2. If `seq == next`, the engine processes the request and records its answer in the stream's answer ring, which
   holds the last W answers. An answer is the order ref and the decision, accepted or risk-rejected. Then
   `next` is incremented.
3. If `seq < next`, the request is a duplicate. The engine answers it from the ring with the original ref and
   decision, exactly as `answeredAsDuplicate` does today. A `seq` older than `next − W` gets DUPLICATE without a
   ref. A duplicate never executes.
4. If `seq > next`, there is a gap: something earlier on the stream was lost. The engine answers REJECTED /
   `OUT_OF_SEQUENCE`, with the expected `next` in the ack's `orderRef` field. It records nothing and does not
   advance `next`. The client resends its pending requests from `next`, in order (go-back-N).

Because nothing below `next` is ever skipped, `seq < next` really means "already answered". A resend is caught
**for as long as the stream exists**, with no time bound, no wall clock and no rate assumption.

The only bound is depth: the original ref comes back for resends within W of `next`. A resend can fall behind
`next` only by the requests on its stream that committed but were never received. In `xp` that was 24 in an hour,
across all 1,152 streams. **W = 256** (`EXACTLY_ONCE_STREAM_WINDOW`) leaves two orders of magnitude of margin.
W only limits which resends get their original ref back; no resend is ever booked twice, whatever W is.

State is `MAX_STREAMS` × (24 B + W × 8 B). The default of 8,192 streams × 256 is about 16 MB presized. The rig
runs 1,152 binary and 864 FIX streams, 2,016 in all. A snapshot writes only filled ring entries: at most
2,016 × 256 × 8 B ≈ 4.1 MB plus headers, smaller than today's 7.3 MB. Each ring is a contiguous array, so it is
copied straight through without the per-entry hash probe that STALLS.md §2 measured. Sequenced requests do not
enter the key table, which is left with only the unsequenced traffic.

**Unsequenced traffic** (legacy binary clients, and FIX until §4 below is enabled) keeps today's 262,144-key
table unchanged. Its cover is 262,144 / (unsequenced rate) seconds.

## Decision

Adopt (c).

### 1. Determinism

Every decision is a pure function of the log: the request's `(account, streamId, seq)`, and the stream table that
earlier log entries built.

Stream retirement is the only step that depends on time:

- A stream idle for longer than `EXACTLY_ONCE_STREAM_IDLE_MS` (default 3,600,000) may be evicted when a new stream
  needs its slot.
- Idle time is measured on the cluster log timestamp passed to `onSessionMessage`. It never uses
  `System.currentTimeMillis()` or the gateway-carried `eventTimeMillis`.
- Eviction happens only when a new stream arrives and the table is at `MAX_STREAMS`. The least-recently-used
  stream is evicted if it is idle past the threshold; otherwise the new request is refused with `STREAM_LIMIT`.

One hour is 80 times the 45 s bound. An evicted stream starts again at `next = 1`, so a client that returns to it after an idle hour opens a new
`streamId`. Resends never trail by an hour, so they are unaffected (FR-XOD-07).

### 2. Snapshot format and migration

- `SNAPSHOT_FORMAT` goes from 13 to **14**. Format 14 adds `T_STREAM` records: account, streamId, next,
  lastUsedMs, and the filled ring entries in sequence order.
- `MIN_READABLE_SNAPSHOT_FORMAT` stays 9. A format 9–13 snapshot loads with an empty stream table and today's
  key table. Migration is therefore a restart from the last format-13 snapshot, with no conversion step.
- Two members fed the same log must write byte-identical format-14 snapshots, and a snapshot plus its log tail
  must equal the full replay (FR-XOD-09).

### 3. Roll: stop the whole cluster at a snapshot barrier, then roll the gateways

This changes the deterministic core. A mixed-version window has diverged a cluster permanently before (YU13), so
members are never rolled one at a time:

1. Take a snapshot and confirm `traderx_cluster_snapshots` advanced on all three members.
2. Stop all three members, deploy the new image to each, and start them; they load the format-13 snapshot.
3. The members are upgraded first because the ingress message gains `(streamId, seq)` as a tail versioned by
   block length. New members decode both lengths, and an old-length message counts as unsequenced.
4. Then roll the gateways, one at a time, so that no gateway ever sends the new length to an old member.
5. Rollback is a fresh epoch from the barrier snapshot. An old image is never started on a format-14 snapshot,
   or on a log that holds sequenced input, because it would replay that input under different rules.

### 4. Wire, gateway and FIX

- **Binary.** Frame sizes do not change.
  - Byte 2 of NEW, unused today, becomes a `flags` byte, and bit 0 means "sequenced". CANCEL and REPLACE carry
    the same bit in byte 1.
  - A sequenced frame's `u64 clOrdId` is read as `streamId:u32 (high) | seq:u32 (low)`, and `seq` starts at 1.
  - Every request on a sequenced stream (NEW, CANCEL, REPLACE) consumes the next `seq`.
  - The ack is unchanged apart from the new `OUT_OF_SEQUENCE` risk reason, which carries the expected `next` in
    `orderRef`.
  - A client that never sets the bit sees no change.
- **Gateway.** The gateway passes `(streamId, seq)` through without interpreting it. Because the stream lives in
  the engine, a client can reconnect to any gateway, and two gateways can carry the same stream during a
  reconnect; the order of the log decides between them.
- **FIX** (opt-in, `FIX_EXACTLY_ONCE=jetstream`, on top of ADR-086). A `ClOrdID` is free-form, so the gateway
  assigns the sequence:
  - Under the session lease, the gateway compare-and-sets a record `ClOrdID → (seq, request)` into the session's
    ADR-086 bucket before it submits the request. This is FR-GFS-02's store-before-send rule, applied to inbound.
  - The stream is the 64-bit FNV-1a hash of the session's CompID pair.
  - A resend of that `ClOrdID`, on any replica and whether or not it carries `PossResend=Y`, resubmits the stored
    `(seq, request)`.
  - A gateway that adopts a session resubmits every stored request that has no answer. This covers a gateway
    that stored a request and died before submitting it.
  - `MsgSeqNum` keeps its role: QuickFIX/J drops transport retransmits below the persisted target sequence
    (ADR-086 §4). It cannot identify an application resend, because that resend carries a new `MsgSeqNum`.
  - Until this is enabled, FIX uses the key table as it does today. The extra JetStream write per order gets its
    own latency A/B (NFR-XOD-03).

### 5. How the 400K/s proof shows it

- **Loadgen.** The hand-over's `plan.exactlyOnce` gains `plan.sequenced`. Each session is one stream
  (`streamId = mix32(nonce, client)`) whose `seq` counts up from 1; the per-session pending map is already keyed
  by `seq`. On `OUT_OF_SEQUENCE(next)` the client resends its pending frames from `next`, in order.
- **HxNativeReplay** gains a check that the receipts file cannot express: in the replayed log, no
  `(account, streamId, seq)` and no unsequenced key is booked twice (`duplicateKeys == 0`). It also counts the
  resends that reached an already-committed request (`resendsOfCommitted`).
- **The arm** runs at 400K/s with a leader kill and a gateway kill. The run counts as valid only if
  `resendsOfCommitted > 0`, which shows the risky path was actually taken.
- **The negative control** is the same arm with `plan.sequenced=false`. It must show `duplicateKeys > 0`;
  otherwise it is reported INCONCLUSIVE, never as a pass.

## Consequences

- At 400K/s, a resend is caught for the life of its stream instead of for 0.66 s. The 45 s that a key window
  would need is met with ≈ 16 MB presized instead of 0.6–1.1 GB, and the snapshot is smaller than today's.
- The leader snapshot halt stays in the measured 14–18.5 ms class instead of growing to about 1 s.
- Clients that want exactly-once number their requests per stream, which the loadgen already does. Unsequenced
  clients are unaffected.
- A request lost on a stream causes a short go-back-N burst of `OUT_OF_SEQUENCE` refusals after the fault. These
  refusals are never terminal answers.
- One new risk reason (`OUT_OF_SEQUENCE`) and one new refusal (`STREAM_LIMIT`) are added after the current last
  ordinal.

## Evidence

The implementation fills this in: the unit tests (FR-XOD-01..10), the local triad replay, and the CLOUD-QUEUE row
"exactly-once at 400K/s" (the pack spec's Cloud proof section).
