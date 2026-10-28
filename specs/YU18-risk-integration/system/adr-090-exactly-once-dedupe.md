# ADR-090: Exactly-once order entry at 400K/s: per-stream contiguous sequences

Status: approved by yaakov 2026-10-28 (option c) and implemented on `traderX-risk-integration`; not deployed. The
as-built notes in §4 record where the implementation narrowed the proposal and why. Amended 2026-10-28: the
FIX order records are a ring per account, in the store and in the heap, and a resend the ring may have lost is
refused, never booked (§4a, FR-XOD-13). At 400K/s (CLOUD-QUEUE row 17, arm `xs`, image `f815003d`), a key-level check
of the full member log found 0 keys booked twice among 14,679,944 acceptances (Evidence).
Requirements: [exactly-once dedupe pack](../maintenance/exactly-once-dedupe/spec.md), FR-XOD-01..13 and
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

The only bound is depth: the original ref comes back for resends within W of `next`. W only limits which resends
get their original ref back; no resend is ever booked twice, whatever W is.

How far a resend falls behind `next` is set by the stream's rate and the resend's delay, not by how many acks
were lost. An open-loop client keeps sending new requests on a stream after an ack is lost, and those commit. So
`next` runs ahead by about (per-stream rate × resend delay). At 400K/s over 1,152 streams that is about 347/s ×
12–42 s, or 4K–15K requests. CLOUD-QUEUE row 17 saw it: most post-failover resends landed past W = 256 (about
0.74 s of stream). (An earlier draft of this ADR argued from the 24 unreceived acks in an hour of `xp`. That
counts lost acks, not stream advance.)

**W = 256** (`EXACTLY_ONCE_STREAM_WINDOW`) is kept. A ring that covered the resend delay at 400K/s would need
W ≈ 16K, which is about 670 MB presized at 8,192 streams, or 170 MB at 2,048 with up to ~260 MB of snapshot.
That brings back the snapshot-walk tail this ADR removes. Past W, a resend's answer is the ref-less DUPLICATE,
and that answer is its receipt (FR-XOD-03).
- Alternative, not adopted: **W = 2,048** gives the original ref back for about 6 s of stream at 347/s. It costs
  about 40 MB presized at 2,048 streams and up to ~33 MB of snapshot. Adopt it only after its snapshot halt has
  been measured on hxb against W = 256.

State is `MAX_STREAMS` × (24 B + W × 5 B). The default of 8,192 streams × 256 is about 10 MB presized. The rig
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
3. The members are upgraded first because the ingress message gains `(streamId, seq)`. As built, the pair is a
   16-byte tail on templates 1 and 9, present exactly when the new input flag `INPUT_FLAG_SEQUENCED` is set. The
   schema and its checksum are unchanged, and every unflagged message is byte-for-byte the same, so old logs
   replay as they were. New members decode both shapes and refuse a message whose flag and length disagree.
4. Then roll the gateways, one at a time, so that no gateway ever sends the new length to an old member.
5. Rollback is a fresh epoch from the barrier snapshot. An old image is never started on a format-14 snapshot,
   or on a log that holds sequenced input, because it would replay that input under different rules.

### 4. Wire, gateway and FIX

- **Binary.** Frame sizes do not change.
  - Byte 2 of NEW, unused today, becomes a `flags` byte, and bit 0 means "sequenced".
  - A sequenced frame's `u64 clOrdId` is read as `streamId:u32 (high) | seq:u32 (low)`, and `seq` starts at 1.
    The idempotency key stays the client's u64.
  - As built, only NEW is sequenced. The binary CANCEL and REPLACE frames carry no account, so they cannot name the
    `(account, streamId)` a stream is keyed by. A cancel is idempotent by itself, and a replace keeps the key
    table, as today.
  - The ack is unchanged apart from the new `OUT_OF_SEQUENCE` risk reason, which carries the expected `next` in
    `orderRef`.
  - A client that never sets the bit sees no change.
- **Gateway.** The gateway passes `(streamId, seq)` through without interpreting it. Because the stream lives in
  the engine, a client can reconnect to any gateway, and two gateways can carry the same stream during a
  reconnect; the order of the log decides between them.
- **FIX** (opt-in, `FIX_EXACTLY_ONCE=jetstream`, on top of ADR-086). A `ClOrdID` is free-form, so the gateway
  assigns the sequence:
  - Under the session lease, before submitting a request, the gateway creates the record
    `K.o.<account>.<seq> = (ClOrdID, request)` in the session's ADR-086 bucket, create-if-absent. This is
    FR-GFS-02's store-before-send rule, applied to inbound. The sequence counts per account, because the engine's
    streams are per account.
  - The stream is the 64-bit FNV-1a hash of the session key and its FIX-day creation time. The day's reset purges
    `K.o.*`, so each FIX day starts new streams at 1.
  - A resend of that `ClOrdID`, on any replica and whether or not it carries `PossResend=Y`, resubmits the stored
    `(seq, request)`.
  - As built, a stored request that never reached the engine is caught by the engine itself. The next order's
    `OUT_OF_SEQUENCE(e)` makes the gateway submit and answer the stored requests `e .. seq-1` in order, then
    resubmit that order. This covers a gateway that stored a request and died before submitting it. Unlike a
    resubmission on adoption, it needs no "has this been answered" bookkeeping.
  - `MsgSeqNum` keeps its role: QuickFIX/J drops transport retransmits below the persisted target sequence
    (ADR-086 §4). It cannot identify an application resend, because that resend carries a new `MsgSeqNum`.
  - Until this is enabled, FIX uses the key table as it does today. The extra JetStream write per order gets its
    own latency A/B (NFR-XOD-03).

### 4a. Amendment: the FIX order records are a ring, and an aged-out resend fails safe (FR-XOD-13)

As first built, the FIX day's order records grew with the day in both places they live: in the store, one
`K.o.<account>.<seq>` key per order, and in the heap, every ClOrdID and raw request until the 17:00 reset. ADR-092
found the heap side. ADR-086 §6a then bounded the message log as a ring and set the bucket to discard its oldest
entries when full. Under that setting, a growing record set fills the bucket, and the bucket then drops the oldest
records. A resend of a dropped ClOrdID would be decided as new and booked twice. A first amendment (`82da1edb`)
bounded the heap with a ClOrdID window plus a per-order index key, `K.c.*`. That added a second key per order to
the bucket, so the same problem remained. This amendment replaces it.

- **A ring per account, in a bucket of its own.** Order `seq` is stored at
  `K.<account>.<(seq-1) mod FIX_EXACTLY_ONCE_WINDOW>` (default 8,192) in KV bucket `FIX_ORDERS`, overwriting
  `seq - window`. A session holds at most the window's keys per account, however long its FIX day. There is no
  index key.
  - The bucket discards its oldest entries when it reaches `FIX_ORDER_STORE_BYTES` (default 1 GiB). Each entry
    expires one day after it was last written. The 17:00 reset purges the session's keys first, so that expiry
    only clears a session abandoned past a reset.
  - The rings are kept out of `FIX_SESSIONS`, so they never push out a session's sequence record or its message
    ring (ADR-086 §6a). At about 0.5 KB a slot, a ring is roughly 4 MB per session and account at the default
    window.
- **The fence is kept.** A slot is overwritten only from the revision this gateway wrote or read (`kv.update`;
  `kv.create` for an empty slot). If another gateway wrote the slot in between, this gateway is fenced, as the
  create-if-absent record fenced it before. If a full bucket discarded the slot, the slot is created again; that
  is not a fence.
  - This fence holds only while the slot exists. A stale gateway can recreate a discarded slot, so the session
    lease remains the primary fence, as in ADR-086.
  - A discarded slot marks the ring as aged. A resend that needed the slot is therefore refused before a
    recreated slot could answer it.
- **The heap mirrors the ring.** It holds the ring's ClOrdIDs and the raw requests the engine has not answered.
  Every engine answer moves a per-account floor: `seq + 1` for an answer to `seq`, or `e` for
  `OUT_OF_SEQUENCE(e)`. A `STREAM_LIMIT` refusal and an ambiguous answer leave the floor where it is. Go-back-N
  only ever asks for `e .. seq-1`, so it is unaffected. A gateway that adopts the session reads only the rings,
  not the day, and holds their requests until the engine answers past them.
- **An aged-out ClOrdID fails safe.** A ring has *aged* once any record of today is gone from it, either
  overwritten by the ring or discarded by a full bucket. On adoption this shows as fewer records than
  `next - 1`. Then a NewOrderSingle carrying `PossResend=Y` or `PossDupFlag=Y` whose ClOrdID is not in the ring
  is neither booked nor rejected as an order:
  - It may have executed, so a `39=8` ExecutionReport would read as "did not execute".
  - It is answered with a BusinessMessageReject (`35=j`, `RefMsgType=D`, `BusinessRejectRefID=<ClOrdID>`, text
    `EXACTLY_ONCE_AGED_OUT: …`).
  - The client reconciles by OrderStatusRequest or OrderMassStatusRequest and, if the order is unknown there,
    sends it again under a new ClOrdID.
  - This also refuses a resend that a dying gateway received but never stored, once the ring has aged. Such an
    order was never submitted, so a refusal is safe, and the client recovers it the same way.
  - While a ring has not aged, the heap holds every ClOrdID of the account's day. A miss is then a new order, as
    before.
- **Unflagged reuse.** A ClOrdID reused without one of the flags breaks FIX's uniqueness rule. Inside the ring it
  is still answered with the original order. Past the ring it is taken as new.
- **The horizon.** A resend reaches its original while fewer than the window's orders of the same account have
  been stored since. That is window ÷ per-account rate: 0.8 s at 10K/s, 82 s at 100/s. Size the window to the
  client's resend horizon at its rate. Past the horizon, the answer is the refusal above, never a second booking.
- **Cost.** These are estimates, not measurements, per session and account at the default window:
  - `FIX_ORDERS` holds one key per ring slot, about 4 MB.
  - The heap holds about 300 B per ring slot for its ClOrdID entry, about 2.5 MB.

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
  would need is met with ≈ 10 MB presized instead of 0.6–1.1 GB, and the snapshot is smaller than today's.
- The leader snapshot halt stays in the measured 14–18.5 ms class instead of growing to about 1 s.
- Clients that want exactly-once number their requests per stream, which the loadgen already does. Unsequenced
  clients are unaffected.
- A request lost on a stream causes a short go-back-N burst of `OUT_OF_SEQUENCE` refusals after the fault. These
  refusals are never terminal answers.
- One new risk reason (`OUT_OF_SEQUENCE`) and one new refusal (`STREAM_LIMIT`) are added after the current last
  ordinal.

## Evidence

**CLOUD-QUEUE row 17, arm `xs`** (400K/s, 16 × 72 sequenced sessions, a gateway kill then a leader kill,
image `f815003d`):
- The strict native replay first reported `duplicateKeys` 2,431 and failed. That counter was the number of
  booked refs with no client receipt. It reads as "booked twice" only on a run that completes.
- This run was cancelled 1.7 s after the leader kill, with 256,923 orders still pending at the clients. A
  sequenced loadgen also took the ref-less DUPLICATE (FR-XOD-03) as fatal, or recorded it with no receipt.
- HxKeyCheck counted acceptances per key over the full member log, through `appliedSeq` 14,680,923:
  - **0 keys were booked twice** among 14,679,944 acceptances, all of them sequenced `ORDER_NEW` applies;
  - its negative control, one injected second acceptance, was caught;
  - 14,679,944 = 14,677,513 receipts + the 2,431, each a distinct key booked once whose ack had not arrived
    when the run was cancelled.
- A local triad on the same image with W = 4 reproduced the harness signature. The loadgen stopped on a
  ref-less DUPLICATE for an order already answered, and the replay reported 13 unreceipted refs. In a stub
  engine with a 4-answer window, all 600 orders executed exactly once, and the old loadgen recorded 16 of
  them as rejected with no receipt.

Implemented as `f815003d` (2026-10-28). Unit tests ran in an empty-root render:

- `ExactlyOnceStreamTest` 12/12. Its negative control is an unsequenced resend arriving 262,145 keys after the
  original, and that resend books twice. With the gate disabled in the rendered tree, the four behaviour cases
  fail.
- `BinarySequencedFrameTest` 1/1.
- `FixExactlyOnceIT` 3/3, including an unsequenced control that submits twice.
- Amendment (FR-XOD-13): `FixExactlyOnceIT` 4/4. The new case sends 30 orders through a ring of 8:
  - `FIX_ORDERS` holds 8 keys for the session, and `FIX_SESSIONS` holds no order or index keys. The heap holds
    8 ClOrdIDs and no requests.
  - A `PossResend` inside the ring is answered with the original order.
  - A `PossResend` of `o1` past the ring is refused with `35=j EXACTLY_ONCE_AGED_OUT`, and so is `o2` after
    another replica adopts the session. Every order is booked exactly once.
  - On the `b0bcd313` store (window plus index, in `FIX_SESSIONS`), the case fails with 92 keys for the
    session, against a ring of 8.
  - With the aged-out check disabled, it fails because `o1` is booked again (`35=8`).
- The full order-matcher suite: 840 run, 0 failed, 6 skipped.
- On the head merged with `39971df0`, the suite ran 842, with 1 failure: `ThreeMemberClusterTest` timed out at
  120 s in the full run. Run alone it passed 2 of 2 times, and it passed in the 840-test run.

**Local triad.** These runs used the hxb harness's own Docker triad, with the real controller, control, analysis
and strict HxNativeReplay (allowance 0). Arm `xs` (`plan.sequenced`) ran on an amd64 cluster-node built from this
change, through a gateway kill and a leader kill:

| Rate | Orders offered = accepted | Strict native replay | `duplicateKeys` | `resendsOfCommitted` | OUT_OF_SEQUENCE / go-backs |
|---|---|---|---|---|---|
| 400/s | 10,600 = 10,600 | pass; its negative control fails | 0 | 0 | 392 / 15 |
| 1,600/s | 40,600 = 40,600 | pass; its negative control fails | 0 | **2,056** | 1,607 / 24 |

At 1,600/s, 2,056 resends reached a sequence that had already committed. Each was answered with its original
order, and none was booked twice.

The unsequenced local arm `xn` is held to a key window cut to 4,096, then 64 keys, so that it would book the same
resends twice. It recorded `duplicateKeys` 0 in every run, with 0, 2 and 4 resends reaching a committed order. On
one Docker host the faults stall admission as a whole, so no new keys push a stranded key out of the window before
its resend arrives. The failing controls are therefore the unit-level one above and the loadgen stub
(`test_loadgen_sequenced.mjs`, whose 8-id window double-books 20). The 400K/s volume that does move the window
between an original and its resend is CLOUD-QUEUE row 17 (`xs` / `xn`).
