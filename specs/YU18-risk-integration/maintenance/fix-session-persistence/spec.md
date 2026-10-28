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
  (one key per session, value = owning gateway, max age 10 s, renewed every second while in use). A Logon on
  a replica while another holds the session SHALL be answered, within 5 s plus one round trip, with Logout
  `session is live on another gateway`, and the live session SHALL be unaffected.
- FR-GFS-04: A gateway whose lease was last renewed more than 5 s ago SHALL re-confirm it with NATS before
  acting on any application message. If it cannot, it SHALL close the connection without submitting the
  message to the cluster and without writing the session. Every sequence-record write SHALL be a
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
- FR-GFS-13: A fresh cluster epoch SHALL purge `FIX_SESSIONS` with the rest of the venue's state
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
| NATS stopped mid-session | 09 | `FixSessionPersistenceIT`, `yu18-fix-store-faults` step 2 | ALERT; session closed promptly; Logon refused by name; after NATS returns, Logon continues the sequence |
| gateway started with NATS down | 09 | `FixSessionPersistenceIT` | acceptor starts; Logon refused `store unavailable`; accepted once NATS is up |
| unknown symbol | 12 | `yu18-fix-session` step 5 | `OrdStatus=8 rejected: UNKNOWN_SECURITY` |
| store off | 11 | `yu18-fix-resume NEGATIVE=1` | B answers the resumed Logon at MsgSeqNum 1 |
| 3 × window messages through one session | 14 | `FixSessionStoreBoundIT.aLongSession…` | ≤ 2,048 message keys; a full ResendRequest returns the last 2,048 in order; 4,096 gap-filled, counted (before: 6,144 keys) |
| bucket created discard-new at 64 KiB, 2,000 messages | 15 | `FixSessionStoreBoundIT.aFullBucket…` | moved to discard-old; no write fails, session not lost (before: `maximum bytes exceeded [10077]`, the f15s cliff) |
| f15s, 864 sessions at 20K/s, store on | 14, 15 | GKE arm (FIX-tail lane) | before: bucket full at 12 min (3,999,940 keys), every gateway dropped every session |
