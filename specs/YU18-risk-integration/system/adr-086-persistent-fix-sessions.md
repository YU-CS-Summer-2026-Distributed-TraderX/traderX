# ADR-086: FIX sessions persist in JetStream and resume on any gateway replica

Status: implemented and proven on GKE (2026-10-28); ships with the next production rollout, which yaakov approves separately. Requirements: YU10 FR-FIX04, FR-FIX11, FR-FIX12(d) on the YU12+ gateway
tier (ADR-047). Retires TD-AC01. Found by the spec-driven proof audit, round 2, suspects 7 and 9
(`scripts/proofs/COVERAGE.md`). Seam with ADR-084 (FIX fill reports).

## Context

`FixGatewayAcceptor` runs with `MemoryStoreFactory`, `ResetOnLogon=Y` and `PersistMessages=N` (TD-AC01).
A reconnect starts the acceptor at `MsgSeqNum 1`, so a counterparty cannot `ResendRequest` the reports it
missed, even on the same pod (suspect 9: the acceptor had sent 7, the reconnect Logon came back at 1). The
gateway Deployment runs several replicas behind one Service, so "the same pod" is not even guaranteed.

FR-FIX11 asked for a QuickFIX/J file store on the in-process acceptor's PVC (YU10). The gateway tier has
no PVC and is meant to be stateless: a file store would pin each session to one pod and die with it.

Separately, `OrderStatusRequest (35=H)` on GKE answers "order status unavailable (no read model
configured)" (suspect 7): `ClusterGatewayMain.orders()` reads `ORDER_READMODEL_URL`, and no manifest sets
it. A recovering client needs status (FR-FIX12(d) reconciles through it).

## Decision

### 1. Where session state lives: a JetStream key-value bucket

NATS JetStream is already on this tier (on a PVC since FR-DRS-25) and the gateway already links jnats.
A MariaDB `JdbcStore` would add a driver, credentials and a schema to a tier that has none of them.

`FixSessionStore` implements QuickFIX/J's `MessageStore` over bucket `FIX_SESSIONS`. A session's key `K`
is its SessionID with every character outside `[A-Za-z0-9-]` written as `_XX` (hex), so any CompID maps
to a legal key and two CompIDs never share one.

| key | value | written |
|---|---|---|
| `K.seq` | `<nextSender> <nextTarget> <creationMillis>` | compare-and-set on its KV revision, every sequence change |
| `K.m.<n>` | outbound message `n`, exactly as sent | after `K.seq` claims `n` |

`set(n, msg)` first moves `K.seq` to `n+1` with `update(key, value, lastRevision)`, then writes `K.m.<n>`.
QuickFIX/J stores before it sends and does not send when the store throws (`Session.sendRaw`), so a
message is on the wire only if its number is claimed. A crash between the two writes leaves `n` claimed
with no body; it was never sent, and a resend gap-fills it, which is the FIX answer for a number that
carries nothing. `refresh()` rereads `K.seq`; `get(a, b)` reads `K.m.a..b`. `reset()` purges `K.m.>`
from the bucket's stream and writes a fresh `K.seq`.

Settings when the store is on: `PersistMessages=Y`, `ResetOnLogon=N`, `RefreshOnLogon=Y`. A client's
`ResetSeqNumFlag=Y` still resets, as FIX requires.

### 2. One live owner per session: a lease, and the seq record as fencing token

Bucket `FIX_SESSION_LEASE` (history 1, max age 10 s) holds key `K` with the owning pod's name. A gateway
takes it with `create` and renews it every second with `update` on its revision. Expiry is the bucket's max
age, which JetStream has had since 2.6 (the manifests run `nats:2.14`); per-key TTL (2.11+) is not used.

- **Logon.** `fromAdmin(Logon)` takes the lease, waiting up to 5 s for a previous owner to let go, then
  rereads the store. If another gateway still holds it, the Logon is refused with
  `RejectLogon("session is live on another gateway")`, which QuickFIX/J answers with Logout. So two
  gateways never run the same session.
- **Every shared write** needs the lease. Without it, a session that is logged on here has lost its
  lease, so the write fails and QuickFIX/J does not send. A session that is not logged on here writes
  *transiently*: its cache moves, nothing is stored, and the next acquisition rereads the store. That
  covers the Logout that refuses a Logon (QuickFIX/J stores every message before sending it, so a hard
  failure would turn the refusal into a silent disconnect), a rejected Logon's bookkeeping, and every
  other replica's timer at midnight.
- **On demand.** Two writes take a free lease without a Logon: the daily reset (a Logon after midnight
  resets before `fromAdmin` runs, and some replica must purge yesterday's log) and an ADR-084 fill for a
  session that is live nowhere (§3).
- **In flight.** A request still being worked when its client drops keeps the lease, so its answer is
  stored.
- **Release.** After Logout or disconnect, once no request from that session is still in flight and
  the lease has been idle 1 s, it is deleted with its revision, so a lapsed lease is never deleted out
  from under its new owner.
- **Freshness.** A lease last renewed more than 5 s ago (half the TTL, on the monotonic clock) is
  re-confirmed with NATS before the gateway acts on any application message or writes the store. A gateway
  that was paused past the TTL (GC, SIGSTOP, a frozen node) and resumes finds the lease gone, closes the
  connection, and never submits the message it was holding.
- **Fencing.** Every `K.seq` write is a compare-and-set on the revision this gateway last saw, so a write
  from a gateway that lost the session fails on the new owner's revision: nothing is stored, nothing sent,
  and the client is disconnected. Freshness keeps a resumed owner from acting; fencing keeps it from
  writing even if it did.

A gateway that dies holding a lease blocks its session's Logon on other replicas until the lease ages
out (at most 10 s); the client's normal logon retry covers it.

### 3. Reports generated while the client is disconnected

- **The response to a request in flight at the disconnect** (an ack that arrives after the socket died) is
  sequenced and stored by the gateway that took the request, which still holds the lease while the request
  is in flight. The client sees the gap on its next Logon and gets it back on `ResendRequest` with
  `PossDupFlag=Y` and `OrigSendingTime`, like every other stored report.
- **Unsolicited fills (ADR-084)** follow the same rule: a fill is stored only under the session's lease.
  The gateway that tracks the order takes the lease on demand while the session is live nowhere, so the
  fill is sequenced when it happens and resent like any other report. While the session is live on
  another gateway the write is refused, and the live gateway reports it, because...
- **...a gateway adopts a session's orders when it takes the lease.** The session's outbound log is its
  order index: every accepted order's reports (`OrderID ord-<ref>`, `ClOrdID`, `Symbol`, `Side`,
  `CumQty`, `AvgPx`, `OrdStatus`) are in `K.m.*`. On every acquisition the gateway scans `K.m.*` and
  rebuilds `ClOrdID → orderRef` (so cancel by `OrigClOrdID` works on any replica) and ADR-084's tracked
  entry, with the reported `CumQty`, for each order whose latest report is still open. It then replays
  its early-fill ring (the venue's last 4,096 fills on orders it did not track) for those orders. A fill
  it finds there that the log does not hold is stored at once. A fill already in the log is not reported
  again, because the tracked `CumQty` came from the log.

Every fill is therefore stored once, under the lease, by whichever gateway holds it when the fill
arrives, and the reported `CumQty` always equals the engine's. A fill can be missed only when no gateway
tracked the order when it arrived and it has left every early ring by the next acquisition (a long,
busy disconnect). `OrderStatusRequest` and `OrderMassStatusRequest` (§5) answer for that case.

### 4. ClOrdID idempotency

The engine's dedup key is the CompID-qualified `ClOrdID` (FR-FIX10), replicated in the cluster, so it does
not depend on the replica. A client that resends a `NewOrderSingle` to another replica after a reconnect
gets the duplicate answer, not a second order. The persisted target sequence lets QuickFIX/J discard an
inbound resend it has already processed (`PossDupFlag=Y` below the expected number) before the
application sees it.

`ExecID` was a per-gateway counter from 0, so two replicas serving one session would repeat ExecIDs and a
client that deduplicates on ExecID would drop real reports. It becomes `<gateway boot id>-<counter>`.

### 5. OrderStatus on GKE (suspect 7)

The GKE gateway manifest sets `ORDER_READMODEL_URL=http://trade-processor:18091`, the service that serves
`GET /accounts/{id}/orders` (YU13 brief 07; the in-memory `OrderView` under ADR-080). `prepare.py`
refuses a render whose FIX gateway has no read model.

### 6. Retention

- **Daily.** The FIX day ends at `FIX_SESSION_RESET_TIME` in `FIX_SESSION_TIME_ZONE`, default and GKE manifest
  `17:00:00` `America/New_York`. That is after the 16:00 ET close and its marks, so a FIX day is a US trading day.
  The old fixed 00:00 UTC fell at 8 pm ET (7 pm in winter), mid-evening. The first Logon or timer tick after
  it purges `K.m.>` and returns the sequence to 1. A day is the resend horizon.
- **Backstop.** `FIX_SESSIONS` has max age 7 days (a session idle a week starts fresh, as the daily reset
  would have made it anyway) and a 1 GiB cap with discard-new: a full bucket fails the write, so the send
  fails and is logged. A sequence number is never dropped to make room.
- **Lease.** `FIX_SESSION_LEASE` keeps one revision per session for at most 10 s.

### 7. NATS outage: no FIX without its store

QuickFIX/J sends nothing it could not store, so without NATS no FIX message can go out. The gateway makes
that deterministic instead of a hang:

- On a NATS disconnect it logs `ALERT FIX session store unavailable` and closes every live session it serves
  at once. It closes TCP rather than sending Logout, because a Logout would take a sequence number the store
  cannot record and the counterparty's next Logon would then be out of step.
- It drops every local lease claim and acts on no application message, so no order reaches the cluster for a
  session whose answer could not be stored.
- Every Logon is answered with Logout `FIX session store unavailable; retry later`, written transiently.
- A 5 s client ping finds a silent partition in about 10 s.
- When NATS is back, Logons are accepted and continue the stored sequence. A lease still naming this gateway
  is taken back rather than waited out.
- A gateway that starts with NATS down starts anyway (REST and binary at once) and serves FIX when NATS
  arrives. The first rig run found the opposite: the store's constructor threw from `main`, so the gateway ran
  Ready with no FIX acceptor at all.

An order submitted in the instant before the disconnect was seen may have no stored answer. That is
FR-FIX12(d)'s ambiguous outcome, and the counterparty reconciles with `OrderStatusRequest`. REST and binary
order entry do not depend on this store.

### 8. Where it is on

`FIX_SESSION_STORE=jetstream` turns it on, using `NATS_ADDRESS` like the control feed. Unset keeps
today's in-memory sessions, for kind and for the throughput rigs. The GKE gateway manifest sets it, and
`prepare.py` refuses a CompSci render without it.

## Consequences

- No engine, wire, snapshot or consensus change. A gateway rolls on its own; the first roll leaves every
  existing session to reset once, because there is no stored state yet.
- **Measured cost (NFR-GFS-01).** Every outbound message costs two synchronous KV writes (claim, then body)
  inside QuickFIX/J's sender lock, and every inbound message costs one (target). An order that crosses produces
  two reports, so it costs about five round trips to NATS. Measurement: traderx-demo, side namespace, 2026-10-28,
  `cluster-node:fp-6ee5da02`, client `scripts/bench/fix-session-latency.mjs` in a pod beside the gateways, one
  session, Sell 1 / Buy 1 pairs crossing at 50. Three runs per arm, ordered ON, OFF, ON. Latency is
  NewOrderSingle to its first ExecutionReport. SEQ is 1,000 orders one at a time; BURST is 1,000 orders written
  back to back.

  | arm | SEQ p50 | SEQ p99 | BURST one session | BURST p99 |
  |---|---|---|---|---|
  | store off | 1.7–2.2 ms | 2.4–4.3 ms | 885–909 orders/s | 1.06–1.09 s |
  | store on (first block) | 3.4–4.5 ms | 5.4–8.4 ms | 291–316 orders/s | 3.1–3.4 s |
  | store on (closing block) | 3.9–4.8 ms | 6.6–8.8 ms | 213–240 orders/s | 4.1–4.6 s |

  The store adds about 2 ms at p50 and about 4 ms at p99 per order, and cuts one session's sustained rate about
  3×. A burst beyond that rate queues in the session, in order (QuickFIX/J works one session's messages on one
  thread). Nothing is dropped, and latency grows with the queue: a 1,000-order burst's last answer arrives
  3–4.6 s after it was sent, against about 1.1 s without the store. Sessions are independent, so the gateway's
  total rate scales with sessions. That is ample for the console's FIX ticket; the throughput rigs leave the store
  off (FR-GFS-11). Upgrade path if one session must go faster: write the body asynchronously behind the claim,
  and batch target updates.
- Acquiring a session scans its log for the day. That is fine at console volume. A session with a
  heavy daily volume would need a checkpoint of its order state.
- A GTC order placed on an earlier day is not in today's log, so after a replica change its fills are
  reported only by the gateway that accepted it. `OrderStatusRequest` and `OrderMassStatusRequest` answer
  for it.
- FR-FIX11's "file store on the order-matcher PVC" described the YU10 in-process acceptor. On the
  gateway tier the store is JetStream, which is what lets any replica resume the session.

## Evidence

- `FixSessionPersistenceIT`: two acceptors in one JVM (two replicas) on a real JetStream (Testcontainers).
  (1) A client drops with RST while an answer is in flight on A, resumes on B continuing its numbers, and
  ResendRequests: exactly the two missed reports at their original numbers, PossDupFlag=Y with
  OrigSendingTime; cancel by OrigClOrdID resolves on B. (2) B refuses a Logon for the session live on A.
  (3) A fill while the session is live nowhere is stored by A and resent from B; a fill while it is live
  on B, for an order placed on A, reaches the client exactly once. Negative control: with the in-memory
  store, (1) fails at "B's Logon at 1, want 6" and (2) fails with B hosting a second copy.
- GKE, cluster `traderx-demo`, side namespace `traderx-fixpersist` on throwaway pools (3+2 e2-standard-8),
  2 gateway replicas, `cluster-node:fp-7ea8db5c` (`sha256:25fe0a7c…`), other images `pa-40d93f6b`
  (2026-10-27, 11:43 pm to 12:01 am ET):
  - `scripts/proofs/yu18-fix-resume.sh` PASS: Logon on B continues at 9; ResendRequest from 6 returns S2 New
    (6), S3 New (7) and S3's fill (8), stored by A while the session was live nowhere, all PossDupFlag=Y; every
    report exactly once across both replicas (no ExecID repeated); cancel by OrigClOrdID on B; OrderStatus
    from the read model (Canceled, Filled); a second Logon on A refused with "session is live on another
    gateway"; members and read model agree. `NEGATIVE=1` with `FIX_SESSION_STORE` removed: B answers the
    resumed Logon at MsgSeqNum 1 (suspect 9), so the proof can fail.
  - `scripts/proofs/yu18-fix-session.sh` PASS, every check, for the first time: FR-FIX04 status answers
    New then Filled (suspect 7), FR-FIX11 reconnect continues the sequence and re-delivers with
    PossDupFlag=Y (suspect 9), FR-FIX12(c) unknown symbol answered `OrdStatus=8 rejected: UNKNOWN_SECURITY`
    (suspect 10).
- The stored log was read back directly (nats-box `kv get`) during the first live run: one report per
  sequence number, the fills with their engine `CumQty`, nothing duplicated.
- Round 2 (2026-10-28, 12:18 am to 1:05 am ET; coordinator's conditions), same rig shape, final image
  `cluster-node:fp-533ad3c7` (`sha256:62c59ce9…`):
  - `yu18-fix-store-faults.sh` PASS. Owner A was SIGSTOPped through a `kubectl debug node` pod while an order
    was queued on its connection; after the TTL, B accepted the resumed Logon. On SIGCONT, A logged
    `lease not confirmed (store up); disconnecting without acting on 35=D`. The order never reached the cluster
    (3 orders on the ticker, not 4), and B's reports run 5 then 6 with nothing from A. With NATS scaled to 0,
    B logged the ALERT and closed the live session in 1.6 s; a Logon was refused with
    `FIX session store unavailable; retry later`. When NATS was back the Logon continued at 7, after 6.
  - `yu18-fix-resume.sh` and `yu18-fix-session.sh` PASS on the same image.
  - Gateway start log: `schedule is daily, 21:00:00-UTC (daily, 17:00:00-EDT)`. NATS `2.14.9`;
    `FIX_SESSIONS` max age 7 d, 1 GiB, discard New; `FIX_SESSION_LEASE` max age 10 s.
  - Latency A/B: the table under Consequences.
  - Two defects the rig found, both fixed with a regression test. First, a gateway started before NATS ran
    Ready with no FIX acceptor: the store's constructor threw from `main`, and `connectReconnectOnConnect`
    blocks its caller. Second, after an outage, a refused Logon's transient write moved the cached target, so
    the next Logon was dropped as `MsgSeqNum too low` instead of refused by name.
- `FixSessionPersistenceIT` grew to 6 cases (adds: expired owner fenced, store outage, gateway started before
  NATS). `cluster.*` suite on the build VM: 511 tests, 0 failures (the IT itself needs Docker and ran locally).
