# Exactly-once dedupe specification

Status: proposed 2026-10-28. ADR-090 is awaiting yaakov's approval, and nothing is implemented yet.

- **Scope:** the deterministic core's request dedupe (`BlpRiskState`, `MatchingEngine.answeredAsDuplicate`,
  `MatchingEngineClusteredService` snapshot/load), the gateway→cluster ingress message, the binary gateway frame
  flags, and the FIX gateway's ADR-086 store.
- **Decision:** [ADR-090](../../system/adr-090-exactly-once-dedupe.md).
- **Base requirements:** YU03 FR-IMRG14, YU10 FR-FIX10, YU18 FR-GFS-02/05.

## Requirements

- FR-XOD-01: A request whose sequenced flag is set SHALL carry a `(streamId, seq)` pair, with `seq ≥ 1`. The
  engine SHALL keep `next` for each `(account, streamId)`. A stream it does not hold SHALL start with `next = 1`, so its
  first request with `seq > 1` falls under FR-XOD-04. A stream cannot adopt a later starting point, because a lost
  `seq` 1 would then look like a duplicate.
- FR-XOD-02: A request with `seq == next` SHALL be processed exactly as today. Its answer (order ref and
  decision) SHALL be recorded in the stream's answer ring, and then `next` SHALL be incremented. A risk rejection
  is an answer and SHALL advance `next`.
- FR-XOD-03: A request with `seq < next` SHALL NOT execute. If `next − seq ≤ W`, it SHALL be answered with the
  recorded ref and decision, in the same form `answeredAsDuplicate` uses today. Otherwise it SHALL be answered
  status 0 / `DUPLICATE` with no ref.
- FR-XOD-04: A request with `seq > next` SHALL NOT execute and SHALL NOT change any state. It SHALL be answered
  REJECTED / `OUT_OF_SEQUENCE`, with `orderRef` set to the expected `next`.
- FR-XOD-05: Sequenced requests SHALL NOT enter the FR-IMRG14 key table. Unsequenced requests SHALL behave
  exactly as before this change.
- FR-XOD-06: W SHALL be `EXACTLY_ONCE_STREAM_WINDOW`, with a default of 256. The stream table SHALL hold at most
  `EXACTLY_ONCE_MAX_STREAMS` streams, with a default of 8,192. Both SHALL be presized at start, and both SHALL be
  recorded in the snapshot. A member whose configured values differ from its snapshot's SHALL refuse to start.
- FR-XOD-07: Only a new stream arriving while the table is full SHALL evict another stream. It SHALL evict the
  least-recently-used stream if that stream has been idle at least `EXACTLY_ONCE_STREAM_IDLE_MS` (default
  3,600,000). Idle time SHALL be measured on the cluster log timestamp. If no stream qualifies, the new request
  SHALL be refused with `STREAM_LIMIT`. A stream that has been evicted starts again at `next = 1`, so a client that returns to it after an idle hour
  SHALL use a new `streamId`.
- FR-XOD-08: No decision in FR-XOD-01..07 SHALL read `System.currentTimeMillis`, `System.nanoTime`, or the
  gateway-carried `eventTimeMillis`.
- FR-XOD-09: `SNAPSHOT_FORMAT` SHALL be 14 and SHALL add `T_STREAM` records. Snapshots of formats 9–13 SHALL load
  with an empty stream table. Two engines fed the same log SHALL write byte-identical snapshots, and a snapshot
  plus its log tail SHALL equal the full replay.
- FR-XOD-10: The ingress message SHALL carry `(streamId, seq)` as a tail, versioned by block length. A member
  SHALL decode both the old and the new length. A message of the old length is unsequenced.
- FR-XOD-11: In binary NEW, flags byte 2 bit 0 SHALL mark the request as sequenced; in CANCEL and REPLACE, byte 1
  bit 0 SHALL. The `u64 clOrdId` of a sequenced frame SHALL be read as `streamId:u32 | seq:u32`. The gateway
  SHALL pass these values through without interpreting them.
- FR-XOD-12: With `FIX_EXACTLY_ONCE=jetstream`, the FIX gateway SHALL apply each of the following:
  - It SHALL compare-and-set `ClOrdID → (seq, request)` into the session's ADR-086 bucket, under the session
    lease, before it submits the request.
  - It SHALL resubmit the stored `(seq, request)` when the same `ClOrdID` is resent, on any replica.
  - On adopting a session, it SHALL resubmit every stored request that has no answer.
  - The stream SHALL be the FNV-1a 64 hash of the session's CompID pair.
  - A failed store write SHALL keep the request off the cluster and SHALL end the session, as in FR-GFS-02.

## Non-functional

- NFR-XOD-01: At 400K/s on the hxb rig, the leader's snapshot halt SHALL NOT exceed that of the same-lane control
  with `CLUSTER_SNAPSHOT_RING` (measured medians: 14 ms on lane A, 18.5 ms on lane B).
- NFR-XOD-02: At 400K/s, sequenced p50 and p99 SHALL stay within the run-to-run range of a same-lane unsequenced
  control.
- NFR-XOD-03: The per-order JetStream write in FR-XOD-12 SHALL be measured with `fix-session-latency.mjs`
  against the ADR-086 baseline before FR-XOD-12 is enabled on any rig.

## Test plan

### Unit tests (CI): `ExactlyOnceStreamTest` beside `IdempotencyEvictionTest`

| Case | Requirement | Fails if |
|---|---|---|
| Contiguous 1..N accepted; `next` = N+1 | 01, 02 | any accepted request does not advance `next` |
| Resend of k < next returns the original ref and decision, for an accepted and a risk-rejected original | 03 | it books, or returns a different ref or decision |
| Resend older than W returns DUPLICATE with no ref, and never books | 03 | it books |
| A gap (k+2 before k+1) is refused `OUT_OF_SEQUENCE(next=k+1)`; a snapshot taken before and after it is byte-identical | 04 | state changes |
| An unknown stream whose first request is seq 2 is refused `OUT_OF_SEQUENCE(next=1)`; seq 1 then books, and seq 2 books after it | 01, 04 | the stream adopts 2 as its start |
| Two interleaved "gateways" send the same stream: each seq books exactly once | 01–04 | any double booking |
| Unsequenced traffic yields the same output and snapshot bytes as today's build on a fixed corpus | 05 | any byte differs |
| Table full: the LRU idle stream is evicted; with no idle stream the request gets `STREAM_LIMIT`; eviction by the log timestamp only | 07, 08 | eviction depends on anything but the log |
| A format-13 snapshot loads; format-14 round trip; two engines produce identical bytes; snapshot + tail = full replay | 09 | load fails or bytes differ |
| Old-length and new-length ingress both decode | 10 | either is refused |
| Binary decode of the flags byte and of `streamId`/`seq`; the gateway does not rewrite them | 11 | the gateway alters the pair |
| **Negative control:** today's key table with 262,144 keys, a 400K/s synthetic log, one resend 12 s late | (base) | the resend does NOT double-book (the control is broken) |

`FixExactlyOnceIT` (testcontainers NATS, as `FixSessionPersistenceIT` does) covers FR-XOD-12. Its cases:

- a store-then-kill before submit, followed by adoption and resubmission, books once;
- a `PossResend=Y` resend on another replica is answered with the original order;
- a failed store write ends the session.

### Local triad (`tests/run_local_triad.sh`)

- **Arm and pass condition:** `xo` with `plan.sequenced` at 5K/s, plus one leader kill and one gateway kill.
  HxNativeReplay must report `duplicateKeys == 0` and `resendsOfCommitted > 0`.
- **Negative control:** the same arm with `plan.sequenced=false` and `IDEMPOTENCY_CAPACITY` forced to 4,096. It
  must show `duplicateKeys > 0`.

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
