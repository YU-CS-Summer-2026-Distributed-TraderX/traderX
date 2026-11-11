# FIX session persistence specification

Status: implemented on `traderX-risk-integration`, proven on a GKE side namespace; on production `traderx` since roll 4 (2026-10-28, `00b31d33`; rollout record §13).
Scope: the cluster gateway's FIX acceptor (`FixGatewayAcceptor`, `FixSessionStore`) on the YU12+ gateway tier,
its GKE manifest and the CompSci render. Decision: [ADR-086](../../system/adr-086-persistent-fix-sessions.md).
Base requirements: YU10 FR-FIX04, FR-FIX11, FR-FIX12. Retires YU12 TD-AC01 where enabled.

## Requirements

- FR-GFS-01: With `FIX_SESSION_STORE=jetstream`, the acceptor SHALL keep each session's next sender and target
  sequence numbers, its creation time and every outbound message in the JetStream key-value bucket
  `FIX_SESSIONS`, and SHALL run `ResetOnLogon=N`, `PersistMessages=Y`, `RefreshOnLogon=Y`. A Logon without
  `ResetSeqNumFlag`, on any gateway replica, SHALL continue the session's sequence, and a `ResendRequest`
  SHALL re-deliver the stored messages at their original numbers with `PossDupFlag=Y` and `OrigSendingTime`
  (admin messages as `SequenceReset-GapFill`).
- FR-GFS-02: No outbound message SHALL be transmitted unless its sequence number and body were stored first.
  A store write that fails SHALL prevent the send and SHALL end that session (TCP close).
- FR-GFS-03: At most one gateway SHALL serve a session at a time, through the lease bucket `FIX_SESSION_LEASE`
  (one key per session, value = owning gateway, max age 3 s, renewed every 500 ms while in use; FR-GFS-16). A
  Logon on a replica while another serves the session SHALL be answered, within about one renewal (~0.5 s) plus
  one round trip, with Logout `session is live on another gateway`, and the live session SHALL be unaffected.
- FR-GFS-04: A gateway whose lease was last renewed more than half the TTL ago SHALL re-confirm it with NATS before
  acting on any application message. If another gateway holds it, or NATS cannot answer, it SHALL close the
  connection without submitting the message to the cluster and without writing the session. A lease that only
  lapsed (nobody served the session meanwhile, shown by its sequence record still at the revision this gateway
  last saw) SHALL be taken again (FR-GFS-16). Every sequence-record write SHALL be a
  compare-and-set on the revision the writer last read, so a gateway that lost the lease cannot write.
- FR-GFS-05: A report generated while the counterparty is disconnected SHALL be stored under the session's
  lease and delivered by `ResendRequest`: the answer to a request in flight at the disconnect, and an
  ADR-084 fill for an order the gateway tracks (the gateway takes a free lease on demand for it). A gateway
  that takes a session's lease SHALL rebuild `ClOrdID → orderRef` and each open order's reported `CumQty`
  from the stored log, so `OrderCancelRequest` by `OrigClOrdID` resolves on any replica and no fill is
  reported twice, reading the log one message at a time and keeping only open orders (ADR-092, FR-FGM-03).
- FR-GFS-06: The GKE gateway SHALL set `ORDER_READMODEL_URL=http://trade-processor:18091`, and
  `OrderStatusRequest`/`OrderMassStatusRequest` SHALL answer from that read model (FR-FIX04). `prepare.py`
  SHALL refuse a render whose FIX gateway lacks it or lacks `FIX_SESSION_STORE=jetstream`.
- FR-GFS-07: `ExecID` SHALL be `<boot id>-<n>`: the gateway's start time in base 36 and two random base-36
  characters, a hyphen, and that gateway's counter. No ExecID SHALL repeat within a session across replicas
  or gateway restarts.
- FR-GFS-08: The FIX day SHALL end at `FIX_SESSION_RESET_TIME` (`HH:MM:SS`, default `17:00:00`) in
  `FIX_SESSION_TIME_ZONE` (default `America/New_York`). The first Logon or timer tick after it SHALL purge the
  session's stored messages and return its sequence numbers to 1. The GKE manifest SHALL pin both values.
- FR-GFS-09: When the gateway loses NATS, it SHALL log `ALERT FIX session store unavailable`, SHALL close every
  live session it serves at once (TCP close, no Logout: a Logout would take a sequence number the store cannot
  record), SHALL accept no application message, and SHALL answer every Logon with Logout
  `FIX session store unavailable; retry later`. A silent partition SHALL be detected within about 10 s
  (5 s ping). When NATS returns, Logons SHALL be accepted and the stored sequence SHALL continue. The same
  SHALL hold for a gateway that starts while NATS is unreachable: it starts its acceptor, refuses Logons by
  name, and serves FIX once NATS is reachable (the store never blocks or aborts gateway start).
- FR-GFS-10: `FIX_SESSIONS` SHALL have max age 7 days and a 1 GiB cap with discard-OLD (amended 2026-10-28,
  FR-GFS-15; it was discard-new). Lease expiry SHALL use the bucket max age, which every JetStream server since
  2.6 supports, not per-key TTL (2.11+). The daily reset (FR-GFS-08) is the resend horizon; max age only clears
  sessions abandoned for a week; the byte cap is a backstop the window (FR-GFS-14) keeps out of reach.
- FR-GFS-11: Unset `FIX_SESSION_STORE` SHALL keep in-memory sessions (`ResetOnLogon=Y`, TD-AC01), for kind and
  the throughput rigs.
- FR-GFS-12: A NewOrderSingle whose `Symbol` the gateway cannot resolve SHALL be answered with an
  ExecutionReport `OrdStatus=8` and `Text` `rejected: UNKNOWN_SECURITY`, not as an ambiguous outcome
  (FR-FIX12(c)). The same order on REST SHALL get its reject code, not 504, and SHALL not count toward the
  gateway's no-ack streak.
- FR-GFS-14: A session SHALL keep its last `FIX_RESEND_WINDOW` (default 2,048) outbound messages, message `n`
  at `K.m.<n mod FIX_RESEND_WINDOW>` overwriting `n - FIX_RESEND_WINDOW`, so the store holds at most
  sessions × (window + 1) keys whatever the traffic. A ResendRequest SHALL get every retained message it asks for,
  exactly as sent and in order (a slot is used only if its MsgSeqNum is the one asked for); older ones SHALL be
  gap-filled, logged as an ALERT naming the range, and counted in `traderx_fix_resend_gapfilled_total`.
  Sequence numbers are not windowed.
- FR-GFS-15: A full `FIX_SESSIONS` SHALL NOT end sessions. The store SHALL set discard-OLD on the bucket's
  stream when it connects (also on a bucket that already exists: the migration), so a full bucket drops its oldest
  entries instead of refusing writes, and SHALL ALERT once a minute's check finds it at 90 % of its byte cap. The
  oldest entries are ring slots and the sequence records of idle sessions (one rewritten on every send survives):
  the worst case is one idle session resuming at 1, never every gateway dropping every session.
- FR-GFS-16: A dead gateway's sessions SHALL be resumable on another gateway about one lease TTL
  (`FIX_SESSION_LEASE_MS`, default 3,000) after its last renewal, on the client's first Logon. The Logon is held
  while the holder is not seen renewing as live, up to TTL + 1 s, and accepted as soon as the lease is free. A
  live owner stalled past the TTL SHALL keep its sessions unless another gateway took one meanwhile, whether or
  not that gateway still holds it, and whether or not it moved a sequence number: every acquisition SHALL move
  the sequence record before the session is served, with or without group commit. An existing
  lease bucket SHALL be moved to the TTL when a gateway connects.
- FR-GFS-17: A Logon with ResetSeqNumFlag=Y SHALL be answered at MsgSeqNum 1 and expect 2 next, whatever the
  stored day and whether the lease was held elsewhere, free, or released while it waited. The reset SHALL be stored
  under the lease (messages and order rings purged, the sequence record written as 1/1 by compare-and-set) before
  the Logon is answered. A Logon without the flag SHALL resume the stored sequence as before (ADR-086 §6c).
- FR-GFS-18: Where the server enforces a write fenced on another subject's sequence, outbound messages SHALL be
  stored by group commit (ADR-099). Bodies are published without waiting, each fenced on the session's `K.seq`
  revision, and one compare-and-set on `K.seq` per batch, sent once every body of the batch is stored, claims them
  with the inbound number. A message SHALL NOT
  reach the client before its number is committed; once its commit can no longer happen (lease or store lost, commit
  failed) it SHALL NOT be sent at all, and the connection SHALL end. Taking a lease SHALL rewrite `K.seq` before
  storing anything.
  Group commit SHALL be on only if the server accepts a probe write fenced on another subject's current revision
  and refuses the same write at a stale revision with wrong-last-sequence (10071). Otherwise, or with
  `FIX_STORE_GROUP_COMMIT=false`, the store SHALL stay synchronous with an ALERT. At most `FIX_STORE_MAX_UNCOMMITTED` (256 since FR-GFS-21; 1,024 before) bodies per session are uncommitted, the batch in flight included.
- FR-GFS-19: With pipelined NewOrderSingles (FR-BSP-02) the store SHALL NOT store an inbound number above the lowest
  one whose order is undecided. QuickFIX/J's own expected number moves on; only the target in `K.seq` is held. When
  the lowest undecided order is decided, the held number SHALL be stored without waiting for the session's next
  message (group commit: the next batch; synchronous: one write). A session resumed with orders undecided (its
  gateway died, or the client reconnected first) resumes at the lowest of them: the client resends from there, the
  engine answers a committed order as a duplicate (§4 of ADR-086), and a later message that was already answered is
  answered again, as ADR-099 already accepts for a crash before a batch.
- FR-GFS-20: The gateway's `/metrics` SHALL report its session store's group commits (ADR-099):
  `traderx_fix_store_commits_total`, `traderx_fix_store_commit_bodies_total`, `traderx_fix_store_commit_seconds_total`
  and `traderx_fix_store_commit_max_seconds` (the longest since the previous scrape). A session's reports leave only
  once committed and at most `FIX_STORE_MAX_UNCOMMITTED` wait, so a store that caps sessions shows as a long commit:
  the 450K acceptance run capped every FIX session near 140 answers/s with the gateways, members and NATS disks idle,
  and nothing measured the store. `FixGatewayThroughputIT` prints them.
- FR-GFS-21 (2026-11-10, the 450K acceptance run 2): The store SHALL spread its sessions over `FIX_SESSION_BUCKETS`
  buckets (`FIX_SESSIONS`, `FIX_SESSIONS_1`, ...), each its own stream, a session in bucket `hash(K) mod n`, bucket i
  placed on NATS server tag i mod N of `FIX_SESSION_PLACEMENT` (as FR-DRS-44 places partitions).
  `scripts/gcp-compsci/prepare.py` SHALL render four buckets per NATS server. Default 1: the layout before.
  - Why: run 2 capped FIX near 85K orders/s with commits averaging ~2.0 s, every session in the one
    `KV_FIX_SESSIONS` stream on nats-1. A commit's wait is its queue: 600 sessions × up to 1,024 bodies each,
    taken one stream at a time. Locally that one stream refused publishes (429) and capped 600 sessions on 18 store
    clients at 133–138K bodies/s with ~2.5 s commits (`FixStoreThroughputIT`, `FIX_STORE_BENCH_GATEWAYS=18`).
  - `FIX_STORE_MAX_UNCOMMITTED` SHALL default to 256: a smaller queue is a shorter commit, and 256 still covers a
    commit's round trip at one session's rate. Same bench, one NATS process (the ceiling is that process, not the
    streams), two runs each: 1 bucket 115–138K/s with 429s; 3 buckets 161–172K/s (commit max 1.3–5.1 s); 3 buckets
    and 256, 173–205K/s (0.58–0.76 s) and no 10071 after FR-GFS-22; 64 was no faster (196K/s). The sessions that
    still fail there time out a commit (5 s) at that one saturated process.
  - ADR-086's invariants hold as before: all of a session's keys (`K.seq` and its bodies) are in its one bucket, so
    each body is fenced on `K.seq` in the same stream (ADR-099), and store-before-send (`FixWriteBarrier`) is
    unchanged. Leases stay in the one `FIX_SESSION_LEASE` bucket. Every gateway SHALL run the same
    `FIX_SESSION_BUCKETS`, changed only across a FIX day reset: a session whose bucket moves starts the next day
    there, as every session does after the reset.
  - Size: a session holds at most `FIX_RESEND_WINDOW` (2,048) messages, so the store levels off instead of growing
    with time at rate (run 2: 557 MB for 600 sessions); each bucket keeps FR-GFS-10's 7 d and 1 GiB cap.
  - On GKE (2026-11-10, slot A: the p4k render's three NATS servers on c4d-standard-16, 600 sessions on 18 store
    clients in 6 pods on p4k-gateway, 120 s per arm, the store alone): a stream takes its fenced writes one at a
    time, so throughput follows the number of streams and the bound sets the commit's wait. Four per server is the
    knee; 24 buckets were no faster.

  | buckets | uncommitted | bodies/s | commit avg | commit max | busiest server CPU |
  | --- | --- | --- | --- | --- | --- |
  | 1 (before) | 1,024 | 140,099 / 144,080 | 2.21 / 2.16 s | 3.67 / 3.88 s | nats-1 0.6–1.3 of 12 |
  | 1 | 256 | 131,170 | 0.59 s | 0.97 s | nats-1 1.3 |
  | 3 | 1,024 | 389,723 | 0.79 s | 1.59 s | 1.9 each |
  | 3 | 256 | 378,353 / 375,220 | 0.20 s | 0.38 / 0.40 s | 1.8–1.9 each |
  | **12 (rendered)** | **256** | **652,673** | **0.12 s** | **0.67 s** | 3.4 each |
  | 24 | 256 | 623,702 | 0.12 s | 2.06 s | 3.5, uneven |

  - `test_prepare.Rendering.test_fix_sessions_are_spread_over_the_nats_servers`; the bucket count is printed by
    `FixStoreThroughputIT`. Not yet measured end to end with FIX clients (acceptance run 3).
- FR-GFS-22 (2026-11-10): A lease that lapsed while the session's own commit was in flight SHALL be retaken with that
  commit as its fence (FR-GFS-16): fenced on the `K.seq` revision this gateway last saw, it lands only if nobody served
  the session meanwhile, and `confirm` SHALL wait for it (at most the 5 s commit timeout) before acting for the
  session. Retaking used to write `K.seq` itself, which refused this gateway's own batch (10071) and dropped a session
  nobody else had: in the FR-GFS-21 bench renewals delayed past the 3 s TTL did that in every run (132–586 refusals);
  after, none. `FixStoreGroupCommitIT.aRetakeWithItsOwnCommitInFlightKeepsTheSession` (the old store confirms at once and its
  commit then fails); the refusing case stays `#aStalledOwnerIsNotConfirmedAfterAnotherServedTheSessionAndLeft`.
- FR-GFS-23 (2026-11-10): A resend read (`get`) reaching an uncommitted number on a store whose group commit has
  failed SHALL fail with that failure, whether the failure arrived before the read or while the read waited. A
  refused commit releases the lease, and the read waited for commits only under a held lease, so a read made after
  the refusal had arrived neither waited nor failed: on a replaced owner's dropped session it returned the new
  owner's body for that number. The write barrier still dropped that body (FR-GFS-18), so nothing reached a client;
  the store's answer depended on arrival order, and `FixStoreGroupCommitIT.aReplacedOwnersLateWriteNeverOverwritesTheNewOwners`
  failed about one run in five. `FixStoreGroupCommitIT.aResendReadAfterTheRefusalHasArrivedStillFails` makes the read
  only after the refusal is back; it fails on the previous store.
- FR-GFS-24 (2026-11-11, slot B): With `FIX_SESSION_SERVER_URLS` (each server's own client URL, in
  `FIX_SESSION_PLACEMENT`'s order), the store SHALL write and read each bucket only on a connection to the server
  that holds it, bucket i on server i mod n. That covers its bodies, `K.seq` compare-and-sets, synchronous writes,
  loads and resend reads. The lease and order buckets, and bucket creation's checks, stay on the main connection;
  the lease, the fence and the `K.seq` CAS are unchanged.
  - **Why:** through the one Service URL a server relayed the publishes for the streams it does not hold, and each
    server plateaued at ~450K msgs/s in, counting relayed copies, at ~3.3 of 16 cores.
    - Slot B, 600 sessions, store alone: 3/4/5 servers took 476–726K/805K/964K bodies/s through the Service, and
      859K/1.15M/1.45M pinned.
    - With the capture at 301 MB/s: 543K/718K/594–838K through the Service, and 700K/995K/1.26M pinned, with capture
      acks at 0.2–0.3 ms instead of 20–33 ms.
    - 450K FIX is ~900K bodies/s plus the capture: 4 pinned servers carry it with ~10% headroom, 5 with ~40%.
  - **One server's connection lost:** the store SHALL fail closed for that server's buckets only, as ADR-086 §7 does
    for all of NATS. Their live sessions are dropped and their Logons refused until it reconnects; the other buckets'
    sessions are served on. A dropped bucket has no other connection, so none of its writes goes to another server.
    Every server's connection SHALL be up before the store first serves.
  - **Render:** `scripts/gcp-compsci/prepare.py` SHALL render the gateway's URLs (`nats-<i>.nats-headless`), four
    buckets per server and the server count from one parameter, `NATS_SERVERS`: 5 pending yaakov's choice of 4 or 5
    (FR-PRG-41). `rig.sh` sizes `nats-pool` from the same number. `validate()` SHALL refuse URLs not naming every
    server in placement order, and a bucket count that is not a multiple of the servers.
  - **Tests:**
    - `FixStorePinnedIT`, two independent servers: each bucket's stream exists only on its own server; the
      unpinned control writes both buckets through the main server; losing one server's connection ends only its
      sessions, which are taken again once it returns. The first and the last fail when every bucket is on the
      main connection.
    - `test_prepare`: the per-server URLs, the drift check, a 3-server render through the parameter, and
      `rig.sh`'s pool size.
- FR-GFS-13: A fresh cluster epoch SHALL purge `FIX_SESSIONS` (every bucket, FR-GFS-21) with the rest of the venue's state
  (`docs/deployment/compsci-gke.md`), because stored reports name orders by `ord-<orderRef>` and a new epoch
  reuses refs.
- NFR-GFS-01: The store's cost SHALL be measured on GKE and recorded in ADR-086: new order → first
  ExecutionReport, p50 and p99, store off vs on, sequential and as a burst on one session.

## Acceptance

| Case | FR | Where | Expected |
| --- | --- | --- | --- |
| drop mid-request on A, Logon on B, ResendRequest | 01, 02, 05 | `FixSessionPersistenceIT`, `yu18-fix-resume` | B continues A's numbering; exactly the missed reports, original numbers, PossDupFlag=Y |
| Logon on B while live on A | 03 | `FixSessionPersistenceIT`, `yu18-fix-resume` step 6 | Logout `session is live on another gateway`; A's session still answers |
| owner paused past the TTL, taken over, resumed | 04 | `FixSessionPersistenceIT` (renewal stall), `yu18-fix-store-faults` step 1 (SIGSTOP) | the resumed owner closes the connection; its queued order never reaches the cluster; B's numbers have no gap |
| fill while live nowhere; fill while live elsewhere | 05 | `FixSessionPersistenceIT`, `yu18-fix-resume` steps 2-3 | stored then resent PossDupFlag=Y; reported once, by the live replica |
| cancel by OrigClOrdID on the other replica | 05 | `FixSessionPersistenceIT`, `yu18-fix-resume` step 4 | Canceled |
| OrderStatus on GKE | 06 | `yu18-fix-session` step 3, `yu18-fix-resume` step 5, `test_prepare` | ExecType=I with the read model's state; render without the URL refused |
| ExecIDs across replicas | 07 | `yu18-fix-resume` step 3 | no ExecID repeats |
| schedule | 08 | gateway start log, `test_prepare` | `daily, 17:00:00 America/New_York`; manifest pins both |
| order undecided while its number is passed | 19 | `FixStoreGroupCommitIT` (both modes) | `K.seq` holds the target at the undecided order; decided, it is stored at once |
| NATS stopped mid-session | 09 | `FixSessionPersistenceIT`, `yu18-fix-store-faults` step 2 | ALERT; session closed promptly; Logon refused by name; after NATS returns, Logon continues the sequence |
| gateway started with NATS down | 09 | `FixSessionPersistenceIT` | acceptor starts; Logon refused `store unavailable`; accepted once NATS is up |
| unknown symbol | 12 | `yu18-fix-session` step 5 | `OrdStatus=8 rejected: UNKNOWN_SECURITY` |
| store off | 11 | `yu18-fix-resume NEGATIVE=1` | B answers the resumed Logon at MsgSeqNum 1 |
| 3 × window messages through one session | 14 | `FixSessionStoreBoundIT.aLongSession…` | ≤ 2,048 message keys; a full ResendRequest returns the last 2,048 in order; 4,096 gap-filled, counted (before: 6,144 keys) |
| owner dies (no renewals, client cut off); client's first Logon on B | 16 | `FixSessionPersistenceIT.aDeadOwners…` | accepted at 3,147 ms (TTL 3 s); before: refused after 5.2 s, TTL 10 s |
| Logon on B while A serves the session | 03, 16 | `FixSessionPersistenceIT.aSecondReplicaRefuses…` | Logout `live on another gateway` in < 1.5 s (0.66 s); before: 5,167 ms |
| A stalls 4 s, nobody takes over | 04, 16 | `FixSessionPersistenceIT.aStalledOwner…` | A keeps the session and answers; before: session closed |
| reset Logon on B while A still drains the lease from the last connection | 17 | `FixSessionPersistenceIT.aResetLogonWinsOverALease…` | Logon answered at 1, next order answered; before: answered at 34=6, the stored day, then "MsgSeqNum too low" |
| reset Logon on B, lease free, A's own reset Logon started the day | 17 | `FixSessionPersistenceIT.aResetLogonWinsWithTheLeaseFree` | Logon answered at 1, next order answered; green before too (QuickFIX/J's refresh runs first) |
| Logon without reset on B through the same handover | 17 | `FixSessionPersistenceIT.aLogonWithoutReset…` | B continues A's numbering after the Logout (control, unchanged) |
| NATS paused after Logon; an order sent | 18 | `FixSessionPersistenceIT.anAnswerReaches…` | no answer while paused; after, the answer arrives and is the stored message (barrier removed: arrives while paused) |
| a taken message reaches the barrier after the lease was cleared, before the disconnect | 18 | `FixBarrierLossTest` | not sent, connection closed, nothing after it sent (first build: sent); controls send untaken and committed, hold then send with the lease |
| NATS paused longer than the commit timeout | 18 | `FixSessionPersistenceIT.anAnswerWhoseCommit…` | no answer, session ended; on resume the answer is resent from the store or its number reused |
| A replaced while its lease still looks fresh; A writes 6 after B stored 6 | 18 | `FixStoreGroupCommitIT.aReplacedOwners…` | A's commit refused, A's session dropped, slot 6 holds B's (fence header removed: A's overwrites it) |
| NATS 2.10 (skips the cross-subject check), bucket already holding messages | 18 | `FixStoreGroupCommitIT.aServerWithout…` | group commit off, ALERT, messages stored synchronously |
| probe vs a simulated server: honours / checks the destination; r = 1 and 8; stale refused with 10077 | 18 | `FixFenceProbeTest` | on only for the server that checks the named subject (first probe: on for the destination checker at r = 8, and on any API error) |
| one gateway, 33 sessions, window 64, venue answers at once | 18 | `FixGatewayThroughputIT` (FIX_GW_BENCH=1) | 101,479 / 114,608 / 81,543 execution reports/s; synchronous store 16,587 / 16,117 / 20,857 |
| A stalls, B takes over, A resumes | 04 | `FixSessionPersistenceIT.anExpiredOwnerIsFenced…` | unchanged: A answers nothing, submits nothing |
| A stalls; B takes over, moves the numbers to 7/9 and leaves; A resumes to a free lease (group commit and synchronous) | 04, 16 | `FixStoreGroupCommitIT.aStalledOwnerIsNotConfirmed…` | A not confirmed, lease left free, `K.seq` stays 7/9 (first retake: confirmed at 1/1) |
| Synchronously, A stalls; B takes over, records a sequenced order (or only holds the session) and leaves without moving a number; A resumes | 16 | `FixStoreGroupCommitIT.aStalledOwnerIsNotConfirmedAfterAnother…Synchronously` | A not confirmed (first fence ran only under group commit: confirmed) |
| batch 2 (requests 1-2, answer 2) with answer 2 refused by the stream; control accepted | 18 | `FixStoreGroupCommitIT.aRefusedBody…`, `.aBatchWhoseBodies…` | B resumes at target 1, sender 2: none of batch 2 durable (first build: 3/3, answer 2 missing); control 3/3 with answer 2 |
| NATS paused; 1,025 messages set | 18 | `FixStoreGroupCommitIT.theUncommittedBound…` | 1,024 taken, the next waits and times out (first build: 1,025 taken) |
| bucket created discard-new at 64 KiB, 2,000 messages | 15 | `FixSessionStoreBoundIT.aFullBucket…` | moved to discard-old; no write fails, session not lost (before: `maximum bytes exceeded [10077]`, the f15s cliff) |
| f15s, 864 sessions at 20K/s, store on | 14, 15 | GKE arm (FIX-tail lane) | before: bucket full at 12 min (3,999,940 keys), every gateway dropped every session |
