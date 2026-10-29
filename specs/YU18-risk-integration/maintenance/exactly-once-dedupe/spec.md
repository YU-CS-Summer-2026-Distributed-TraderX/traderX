# Exactly-once dedupe specification

Status: implemented on `traderX-risk-integration` 2026-10-28; approved by yaakov 2026-10-28. Not deployed: a
format-14 core rolls only through a snapshot barrier and a whole-cluster restart (ADR-090 §3).

- **Scope:** the deterministic core's request dedupe (`BlpRiskState`, `MatchingEngine.answeredAsDuplicate`,
  `MatchingEngineClusteredService` snapshot/load), the gateway→cluster ingress message, the binary gateway frame
  flags, and the FIX gateway's ADR-086 store.
- **Decision:** [ADR-090](../../system/adr-090-exactly-once-dedupe.md).
- **Base requirements:** YU03 FR-IMRG14, YU10 FR-FIX10, YU18 FR-GFS-02/05.

## Requirements

- FR-XOD-01: An ORDER_NEW whose ingress flag `INPUT_FLAG_SEQUENCED` is set SHALL carry a `(streamId, seq)` pair,
  with `seq ≥ 1`. The engine SHALL keep `next` for each `(account, streamId)`. A stream it does not hold SHALL start
  with `next = 1`, so its first request with `seq > 1` falls under FR-XOD-04. A stream never adopts a later
  starting point, because a lost `seq` 1 would then look like a duplicate. CANCEL and REPLACE stay unsequenced:
  the binary CANCEL and REPLACE frames carry no account, so they cannot name an `(account, streamId)`.
- FR-XOD-02: A request with `seq == next` SHALL be processed exactly as an unsequenced NEW is. Its answer (order ref
  and decision) SHALL be recorded in the stream's answer ring, and `next` SHALL advance before the engine sees the
  request. A risk rejection is an answer.
- FR-XOD-03: A request with `seq < next` SHALL NOT execute.
  - If the ring holds an ACCEPTED answer for it, the engine SHALL answer it exactly as `answeredAsDuplicate`
    answers a key-table duplicate.
  - If the ring holds a rejection, the answer SHALL be REJECTED with that reason.
  - If the ring holds no answer (an order held in the PRE_OPEN queue), the key table and the queue SHALL answer it,
    as they do today.
  - Otherwise, including when `next − seq > W`, the answer SHALL be REJECTED / `DUPLICATE` with no ref
    (`orderRef` 0).
  - Client contract for that ref-less answer: it SHALL be read as the receipt for that `seq`. The request was
    committed earlier, exactly once, and its own answer was lost beyond the window. The client SHALL reconcile the
    order's state by order status, and SHALL NOT resend that `seq` again. It is never a failure of the request.
  - Every answer from the stream gate consumes no ref and changes no state.
- FR-XOD-04: A request with `seq > next` SHALL NOT execute and SHALL NOT change any state. It SHALL be answered
  REJECTED / `OUT_OF_SEQUENCE`, with `orderRef` set to the expected `next`.
- FR-XOD-05: The answer of a sequenced request SHALL be kept in its stream's ring and SHALL NOT enter the
  FR-IMRG14 key table. Unsequenced requests SHALL behave exactly as before this change.
- FR-XOD-06: W SHALL be `EXACTLY_ONCE_STREAM_WINDOW`, with a default of 256. The stream table SHALL hold at most
  `EXACTLY_ONCE_MAX_STREAMS` streams, with a default of 8,192. Both SHALL be presized at start: about 5 B per ring
  entry, so 10 MB at the defaults. Every `T_STREAM` record SHALL carry W. A member SHALL refuse a snapshot written
  with a different W, or one holding more streams than its `EXACTLY_ONCE_MAX_STREAMS`.
- FR-XOD-07: Only a new stream arriving while the table is full SHALL evict another stream. It SHALL evict the
  least-recently-used stream, ties broken by the lowest index, if that stream has been idle at least
  `EXACTLY_ONCE_STREAM_IDLE_MS` (default 3,600,000). Idle time SHALL be measured on the cluster log timestamp. If
  no stream qualifies, the new request SHALL be refused with `STREAM_LIMIT`. An evicted stream starts again at
  `next = 1`, so a client that returns after an idle hour SHALL use a new `streamId`.
- FR-XOD-08: No decision in FR-XOD-01..07 SHALL read `System.currentTimeMillis`, `System.nanoTime`, or the
  gateway-carried `eventTimeMillis`.
- FR-XOD-09: `SNAPSHOT_FORMAT` SHALL be 14 and SHALL add `T_STREAM` records in stream-index order, each restored at
  its own index. Snapshots of formats 9–13 SHALL load with an empty stream table. Two engines fed the same log
  SHALL write byte-identical snapshots, and a snapshot plus its log tail SHALL equal the full replay.
- FR-XOD-10: Templates 1 and 9 SHALL carry `(u64 streamId, u64 seq)` as a 16-byte little-endian tail after their
  block exactly when `INPUT_FLAG_SEQUENCED` is set. The schema and every unflagged message SHALL be unchanged. A
  member SHALL refuse a message whose flag and length disagree, or whose sequenced `seq` is 0.
- FR-XOD-11: In binary NEW, flags byte 2 bit 0 SHALL mark the request as sequenced. The `u64 clOrdId` of a
  sequenced frame SHALL be read as `streamId:u32 | seq:u32`, and a sequenced frame with `seq` 0 SHALL be refused
  PROTOCOL_ERROR. The gateway SHALL pass the pair through without interpreting it, and the idempotency key SHALL be
  the client's u64 exactly as before.
- FR-XOD-12: With `FIX_EXACTLY_ONCE=jetstream`, which requires `FIX_SESSION_STORE=jetstream`, the FIX gateway
  SHALL apply each of the following:
  - It SHALL store each new NewOrderSingle as `K.o.<account>.<seq>` in the session's ADR-086 bucket before it
    submits it. The store is create-if-absent, under the session lease. `seq` counts per account per FIX day.
  - It SHALL resubmit the stored `(account, seq)` when the same `ClOrdID` is resent, on any replica.
  - When the engine answers `OUT_OF_SEQUENCE(e)`, it SHALL first submit and answer the stored orders `e .. seq-1`,
    in order, then the order itself again. These are the orders a replica stored but never submitted.
  - The stream SHALL be the FNV-1a 64 hash of the session key and its FIX-day creation time, so the day's reset
    (which purges `K.o.*`) starts new streams.
  - A failed or fenced store write SHALL keep the order off the cluster and SHALL end the session, as in
    FR-GFS-02.
- FR-XOD-13: With FR-XOD-12 enabled, a FIX session's order records SHALL be a ring per account:
  - Order `seq` SHALL be stored at `K.<account>.<(seq-1) mod FIX_EXACTLY_ONCE_WINDOW>` (default 8,192) in KV
    bucket `FIX_ORDERS`, which discards its oldest entries at `FIX_ORDER_STORE_BYTES` (default 1 GiB). No order
    record SHALL be kept in `FIX_SESSIONS`.
  - A slot SHALL be overwritten only from the revision this gateway wrote or read; another gateway's write in
    between SHALL fence it. A slot discarded by a full bucket SHALL be created again.
  - The heap SHALL hold the ring's ClOrdIDs, plus the raw requests the engine has not answered. Each answer moves
    a per-account floor: `seq + 1`, or `e` for `OUT_OF_SEQUENCE(e)`. `STREAM_LIMIT` and an ambiguous answer do
    not move it.
  - Once any record of the account's day is gone from the ring (overwritten, or discarded by a full bucket), a
    NewOrderSingle with `PossResend=Y` or `PossDupFlag=Y` whose ClOrdID is not in the ring SHALL NOT be booked.
    It SHALL be answered with a BusinessMessageReject (`RefMsgType=D`, `BusinessRejectRefID` = the ClOrdID), with
    text beginning `EXACTLY_ONCE_AGED_OUT`.
- FR-XOD-14: When the engine answers a FIX NewOrderSingle REJECTED/`DUPLICATE` with no ref (FR-XOD-03, a resend
  more than W behind its stream's next), the FIX gateway SHALL answer it with a BusinessMessageReject
  (`RefMsgType=D`, `BusinessRejectRefID` = the ClOrdID). The text SHALL begin
  `EXACTLY_ONCE_COMMITTED_REF_UNKNOWN`. The gateway SHALL NOT send an ExecutionReport with `39=8`, which reads as
  "did not execute" (ADR-091 §3), for an order that executed once.
- FR-XOD-15 (ADR-093): The `/orders` event that books a NEW order SHALL carry `clientOrderKey`. The value is the
  engine's account-scoped key, written as an unsigned decimal string. No other order event SHALL carry it. The
  recon replay SHALL project the same bytes from the same log.
- FR-XOD-16 (ADR-093): The read model SHALL keep the key from the booking across later updates.
  - `GET /accounts/{id}/orders?clientOrderKey=<raw u64>` SHALL return the order booked under it, scoping the raw
    key by the account as the gateway does, or an empty list.
  - The key SHALL NOT enter the event digest or the recovery state comparisons. An event retained before the
    field existed SHALL verify unchanged.
- FR-XOD-17 (ADR-093): A FIX OrderStatusRequest whose order neither `OrderID` nor the gateway's ClOrdID map names
  SHALL be looked up in the read model by the key the gateway submits that ClOrdID under.
- FR-XOD-18 (ADR-090 §2a): A member SHALL restore, over the real snapshot channel, every snapshot it writes, in
  both framings, whatever a record's length.
  - A record larger than one snapshot message SHALL be reassembled on load. A full stream is the only such record
    today: 4 + 8 × (7 + 256) = 2,108 bytes against a 1,376-byte payload.
  - The packed writer SHALL send such a record unpacked, as its own message, in order.
  - A record the reader refuses SHALL fail the load. It SHALL NOT be skipped by the transport's error handler with
    the load completing at END.

## Non-functional

- NFR-XOD-01: At 400K/s on the hxb rig, the leader's snapshot halt SHALL NOT exceed that of the same-lane control
  with `CLUSTER_SNAPSHOT_RING` (measured medians: 14 ms on lane A, 18.5 ms on lane B).
- NFR-XOD-02: At 400K/s, sequenced p50 and p99 SHALL stay within the run-to-run range of a same-lane unsequenced
  control.
- NFR-XOD-03: The per-order JetStream write in FR-XOD-12 SHALL be measured with `fix-session-latency.mjs`
  against the ADR-086 baseline before FR-XOD-12 is enabled on any rig.

## Test plan

### Unit tests (CI)

`ExactlyOnceStreamTest` (12 cases) sits beside `IdempotencyEvictionTest`. Its cases, and what each one fails on:

| Case | Requirement | Fails if |
|---|---|---|
| **Negative control:** an unsequenced order filled, 262,145 other keys, then the same key again | (base) | the resend does NOT book twice (the control is broken) |
| The same, sequenced: the resend is one unpublished restatement of the original ref, and books nothing | 03, 05 | it books, or names another ref |
| Contiguous 1..4 book; a resend of seq 2 is answered with seq 2's ref | 01, 02, 03 | any double booking |
| A gap (3 before 2) is refused `OUT_OF_SEQUENCE(2)`, consumes no ref, and leaves the snapshot byte-identical; go-back-N then books | 04 | state changes |
| An unknown stream whose first request is seq 2 is refused `OUT_OF_SEQUENCE(1)`; 1 then 2 book | 01, 04 | the stream adopts 2 as its start |
| A resend older than W is refused `DUPLICATE` and nothing applies | 03 | it books |
| A rejected original's resend is answered with the same rejection | 03 | another answer |
| Snapshot round trip, then the same tail on both members: same answers, byte-identical snapshots | 09 | bytes differ |
| A format-13 snapshot restores with no streams, and sequenced orders book | 09 | the load fails |
| Table full: LRU idle eviction, `LIMIT` otherwise, an evicted stream restarts at 1, streams are per account | 07, 08 | eviction not as stated |
| A snapshot written with another W is refused | 06 | it restores |
| Flag and tail must agree; a reused slot keeps no tail | 10 | either shape is mis-decoded |

A/B: with the gate disabled in the rendered tree, the four behaviour cases fail. Those are the resend behind the
window (it names ref 262,148, not 1), the gap, the unknown start, and the beyond-ring duplicate. The other cases pin
behaviour that holds either way.

`BinarySequencedFrameTest` covers FR-XOD-11: the flag splits the key, the ack carries the expected sequence, an
unflagged frame is unchanged, and seq 0 is a protocol error.

`FixExactlyOnceIT` covers FR-XOD-12. It runs testcontainers NATS, two replicas, and a venue that applies
`StreamDedupe`. Its cases:

- a resend (`PossResend=Y`) on the other replica after a lost ack is answered with the original order and books
  once;
- an order a replica stored and never submitted is answered first, in order, when the next order meets the gap;
- negative control: unsequenced, the same resend reaches the venue twice.

It covers FR-XOD-14: a resend whose original executed, answered as the engine answers past its ring
(REJECTED/DUPLICATE, ref 0), gets `35=j EXACTLY_ONCE_COMMITTED_REF_UNKNOWN` and is booked once. The case fails on a
gateway that answers it `39=8`.

It also covers FR-XOD-13, with 30 orders through a ring of 8:

- `FIX_ORDERS` holds 8 keys for the session and `FIX_SESSIONS` holds none, and the heap holds 8 ClOrdIDs and no
  requests;
- a `PossResend` inside the ring is answered with the original order;
- a `PossResend` past the ring is refused with `35=j EXACTLY_ONCE_AGED_OUT` and not booked, both on the same
  replica and after another replica adopts the session;
- the test fails on the window-plus-index store in `FIX_SESSIONS` (92 keys for the session), and with the aged-out
  check disabled (booked again).

### Local triad (`tests/run_local_triad.sh`)

- **Arm and pass condition:** `xs` (= `xo` + `plan.sequenced`), with one gateway kill and one leader kill.
  HxNativeReplay (allowance 0) must report `duplicateKeys == 0`. Result: passed at 400/s and at 1,600/s; at
  1,600/s, 2,056 resends reached committed sequences and none booked twice (ADR-090 Evidence).
- **Negative control:** arm `xn` (unsequenced) on an image whose key window is cut short (4,096, then 64 keys). It
  must show `duplicateKeys > 0`, or the pair is INCONCLUSIVE. Result: INCONCLUSIVE on one Docker host, because
  the faults stall admission, so the window never moves between an original and its resend. Row 17 at 400K/s
  carries the control.
- The loadgen half alone: `tests/test_loadgen_sequenced.mjs` (stub engine with the stream rule; its negative
  control is an 8-id window, which double-books).

### Cloud proof: CLOUD-QUEUE row proposal

| # | What it proves | Owner | Prereq | Lane / est. | State |
|---|---|---|---|---|---|
| ? | **Exactly-once at 400K/s through a leader kill and a gateway kill (ADR-090).** See below. | exactly-once (stalls lane) | ADR-090 approved and implemented; images.tip built from it; controller image rebuilt | lane B, 2 arms ≈ 1 h at ~$18/h ≈ $20–25 | BLOCKED on ADR approval |

The row's full "What it proves" entry:

- **Arm and rig.** Arm `xs` is `xo` + `plan.sequenced`, using the hand-over's `exactly-once-client.patch` plus
  the sequenced loadgen. It runs on images.tip with members on dedicated `/data` and gateways on ZGC, with
  gateways ≥ 2.
- **Load and faults.** 400K/s open-loop. A leader kill and a gateway kill land inside the measurement window.
- **Passes if** all of the following hold:
  - the analysis is valid;
  - other, missing and duplicates are all 0, and trades == accepted;
  - a strict native replay with allowance 0 passes, with `duplicateKeys == 0`;
  - `resendsOfCommitted > 0`;
  - every client shows reconnects > 0;
  - NFR-XOD-01/02 hold against the arm's own pre-fault window.
- **Negative control.** Arm `xn` is the same arm with `plan.sequenced=false`. It must show `duplicateKeys > 0`,
  or it is reported INCONCLUSIVE.
