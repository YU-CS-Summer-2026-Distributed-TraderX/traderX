# RI35: the binary acceptor serialises a session, so throughput is sessions ÷ cluster RTT

Opened 2026-11-09 (trade-history-tiers lane, the GKE batching campaign, RI34 §1b). Status: **both halves fixed on `binary-acceptor-pipelining`, unit tests and kind, 2026-11-10: binary (FR-BSP-01) and FIX (FR-BSP-02, FR-GFS-19); not on GKE or production.** FIX on kind: 4 sessions carry the 8,000/s offer the pre-fix gateway's 4 hold to 2,004/s; one of two 64-session runs lost 187 acks at the load's start to the members' uncounted egress drop (below), so that drop is now reachable and needs its own fix before FIX runs pipelined at rate. NFR-BSP-01's rate holds on kind; its `queue` p99 < 50 µs does not on kind, for the pre-fix gateway either (below). The hour at 400K ran with 512 sessions as the workaround.

## What

`BinaryGatewayAcceptor` runs one thread per TCP connection: `readFully` a frame, `dispatch` it (which returns only
with the committed ack in hand, ~540 µs p50 at 300K), write the ack, read the next frame. A client's pipelining is
therefore queued in TCP: a session carries one order at a time and the gateway's throughput is the number of
sessions divided by the cluster round trip, whatever the gateway count or its CPU.

Measured on GKE (24 gateways, 3 members c4d-standard-8): 128 sessions 285–300K orders/s (the same with 18 gateways;
nothing CPU-bound; every load session at its in-flight cap; `GET /latency` p50 residence 553 µs of which `cluster`
541 µs and `queue` 0.45 µs); 256 sessions 344–374K; 512 sessions 400–405K inside the sending windows, with the
leader then at 6.6 of 7 cores. Evidence: shared notes `bridge-batching-gke-20261109/run4-24gw`, `run6-hyperdisk`,
`run7-256sessions`, `run8-512sessions`.

## Why it matters

A client with a few sessions cannot reach the engine's rate however hard it pipelines; the earlier campaigns' "member
ceiling" of 185–300K was this. FIX sessions have the same shape: QuickFIX/J's `fromApp` runs on one dispatcher thread per session and calls
`submitOrder` synchronously (`FixGatewayAcceptor.java:676`), so a FIX session also carries one order at a time.
The plan: issues/risk-integration/tasks/prod-450k-plan-20261110.md §3.

## Fix

Pipeline the acceptor: decode and submit without waiting; complete acks in submit order per connection from the
owner thread's egress (the `PendingOrder`/future already exists for the FIX path's pipelined submit). Keep the
request/response order on the wire per connection (the binary protocol's clients match acks by clOrdId, so
out-of-order completion is also possible if the spec allows it: decide in the spec). Prove with the 128-session load
reaching the 512-session rate, and `/latency` `queue` staying flat.

## Until then

Size the load's sessions: at a 0.5–0.7 ms cluster RTT, 400K orders/s needs about 512 binary sessions.

## Binary half: pipelined (2026-11-10, kind)

[FR-BSP-01](../../specs/YU18-risk-integration/maintenance/binary-session-pipelining/spec.md). The connection thread
decodes each frame and calls `OrderSubmitter.submitOrderAsync` (or the sequenced, cancel or replace form), which takes
the window slot and queues the order for the owner thread before it returns, so a connection's orders reach the log in
the order they arrived. One writer thread per connection runs the completion (reject log, root span, wedge accounting:
`ClusterGatewayMain.submitPipelinedAsync`, the synchronous path's pieces extracted unchanged) and writes the ack. At
most 4,096 frames per connection are read and not yet acked; past that the connection stops reading. Acks may leave
out of order; the clOrdId correlates (binary-load.mjs and bin-multi.mjs already match by it). A first cut that ran
each frame's synchronous submit on a virtual thread was dropped: a connection's virtual threads race into the owner
queue, so a sequenced stream could reach the log out of order and be refused as a gap.

**kind proof** (`scripts/proofs/kind-binary-pipelining.sh`, one partition, one gateway, `kind-yu18-rig.py --history
--binary --throughput`, one arm per fresh rig, every arm offered 15,000 orders/s with 2,048 in flight in total, 70 s;
rate = the gateway's accepted counter, median of 5 s windows inside the sending windows). New = `traderx/cluster-node:bsp`
(this branch), old = `:bat` (eeedef16, the same order-matcher source without FR-BSP-01). Every arm: the load ended clean
(0 rejected, 0 ambiguous, 0 unanswered, book balanced), G3h ok over every side it booked.

| Sessions | Gateway | Orders/s | queue p50 / p99 (µs) | cluster p50 (ms) | total p50 / p99 (ms) | Accepted |
|---|---|---|---|---|---|---|
| 4 | old | 3,349 | 5.8 / 90 | 0.71 | 0.77 / 9.2 | 196,228 |
| 4 | **new** | **14,778** | 5.0 / 280 | 1.71 | 1.82 / 90.6 | 873,194 |
| 16 | old | 13,332 | 1.2 / 84 | 0.71 | 0.78 / 14.6 | 724,862 |
| 16 | **new** | **14,573** | 1.4 / 359 | 1.78 | 1.96 / 128.3 | 863,882 |
| 64 | old | 14,181 | 1.3 / 272 | 0.99 | 1.09 / 23.8 | 776,496 |
| 64 | **new** | **14,294** | 1.2 / 211 | 1.47 | 1.60 / 123.1 | 846,646 |
| 16 at 5,000/s | new | 4,903 | 1.4 / 160 | 1.29 | 1.38 / 38.5 | 286,346 |

16 sessions reach the 64-session rate (14,573 vs 14,294/s, both at the offer). On kind the cluster round trip is
~0.7 ms, so 16 serialised sessions already carry ~13K: the discriminating control is 4 sessions, where the old gateway
is held to 3,349/s (~1.2 ms per order per session, the client's wire included) and the new one takes the whole offer (4.4×). The new gateway's
higher `cluster` p50 is Little's law: more orders in flight per session wait in the same pipeline.

`queue` p99 is 160–359 µs on the new gateway against the 50 µs target; p50 stays at 1.2–1.4 µs (5 µs at 4 sessions).
The old gateway misses the target too at the same rate: 272 µs at 64 sessions, where it never has more than 64 orders
in flight. So the tail is this rig's owner thread at ~14K/s (four CPUs shared with the connection, writer, Aeron and
egress threads, not pinned), not the pipelining depth. A third of the rate (5,000/s) still read 160 µs. Measure it on
GKE, where the gateway is a pinned guaranteed pod (FR-PRG-06), before calling NFR-BSP-01's `queue` half met or missed.

**Found on the way** (each made the first runs fail, and the script now refuses or records it):
- Member egress is best-effort (`offerEgress`/`flushCoalesced`: 20 attempts, then the ack is dropped, uncounted).
  Without egress coalescing one pipelined gateway session's ~14K acks/s overran it: 7 of 720,819 acks dropped, each
  reaped after 12 s and answered ambiguous (correctly). Production renders coalescing only with
  `prepare.py --throughput-members` (FR-PRG-15): a pipelined gateway needs it on, and the members have no drop counter.
- kind's gateway at a 1-CPU limit was CFS-throttled in 470 of 2,803 periods (queue p99 22.7 ms).
  `kind-yu18-rig.py --throughput` now gives it production's 4 guaranteed CPUs.
- kind's NATS (768Mi, file store, no `GOMEMLIMIT`) was OOM-killed at ~29K orders/s, and again ~1.3M orders into a
  second arm at 15K/s. With NATS down the gateway's wedge detector fired repeatedly and rebuilt the session, which
  drained the window as ambiguous. That is the downstream back-pressuring consensus, outside this change.

Evidence: shared notes `binary-pipelining-kind-20261110/` (per-arm load logs, 1 s samples, `/latency` dumps, G3h,
and the three superseded runs). kind cluster `traderx-bsp` deleted after the runs.

**For the FIX half.** Use the same seam: an asynchronous `submitOrder`/`submitTypedOrder`/`submitReplace` taking the
String clOrdId (not yet on `OrderSubmitter`), called from `fromApp` on QuickFIX/J's session thread, so arrival order
holds per session (FIX_SEQUENCED's stream is safe for the same reason the binary sequenced NEW is). The
ExecutionReport is sent from the completion executor; give each session one, so reports for one session are not sent
concurrently (sequence numbers are assigned at send, so ordering by completion is fine; interleaving is not).
`fromApp` returning before the commit means QuickFIX/J advances its inbound sequence before the order is decided:
check that against FR-XOD and the session store (ADR-086) before turning exactly-once on. Bound each session's
in-flight count as the binary connection does, by blocking `fromApp` (QuickFIX/J then stops reading that session).
Run FIX with egress coalescing on (above).

## FIX half: pipelined (2026-11-10, kind)

[FR-BSP-02](../../specs/YU18-risk-integration/maintenance/binary-session-pipelining/spec.md) and FR-GFS-19
([fix-session-persistence](../../specs/YU18-risk-integration/maintenance/fix-session-persistence/spec.md)). `fromApp`
submits each NewOrderSingle with `OrderSubmitter.submitOrderAsync(String clOrdId, ..., typed, executor)` on
QuickFIX/J's session thread, so arrival order holds, and one report thread per session runs `answer`: the reports,
the ClOrdID map, fill tracking and FR-XOC-06's resubmission across a leader change. A session has at most 1,024
NewOrderSingles undecided (half `FIX_MAX_QUEUED_WRITES`). When the window is full, `fromApp` blocks and QuickFIX/J
stops reading the session (ADR-092). F and G (by OrigClOrdID) and H (by ClOrdID) wait for the in-flight order they
name, and AF for all of them, so each finds what it did when orders were answered one at a time. Cancels, replaces
and exactly-once sessions stay serial.

**The inbound sequence (checked, as the handover asked).** QuickFIX/J 2.3.1 increments the target number after
`fromApp` returns: `Session.next` calls `verify` (which calls `fromCallback`), then `incrNextTargetMsgSeqNum`
(bytecode). A synchronous `fromApp` therefore decided the order before its number moved. With ADR-086's store, a
gateway that died mid-order resumed below it: the client resent the order and the engine answered the duplicate.
Pipelined, the number would move first, so a crash would leave the order unanswered with no resend to recover it.
That matters for every persistent session, not only exactly-once, and production requires the store (`prepare.py`
refuses a render without it). So the acceptor tells the store the lowest MsgSeqNum still undecided, and `K.seq`
stores no target above it (FR-GFS-19). QuickFIX/J's own number moves on. When the lowest undecided order is
decided, the store commits the target at once, so an idle session's reconnect does not replay orders already answered.
A resume with orders undecided replays from the lowest of them: duplicates are restated, and a later message already
answered is answered again, as ADR-099 already accepts for a crash before a batch. Exactly-once (FR-XOD-12) stays
serial, because its store-then-submit and catch-up are per order. Pipelining it means running the catch-up on the
report thread in stream order; not done.

**kind** (`scripts/proofs/kind-fix-pipelining.sh`):
- Rig: `kind-yu18-rig.py --history --binary --throughput --partitions 1`, one gateway at 4 guaranteed CPUs, egress
  coalescing on, FIX sessions in JetStream with group commit. One arm per fresh rig.
- Load: `fix-multi.mjs`, open loop at 8,000/s in total for 40 s. Sessions alternate buy and sell at one price, so
  every order trades.
- Images: under test `cluster-node:fsp`; pre-fix control `:bsp`, which is the binary-pipelined build with FIX
  still serial.

| sessions | gateway | rate (gateway accepted) | answered | `queue` p50 / p99 µs | `cluster` p50 ms | client p50 / p99 ms |
| --- | --- | --- | --- | --- | --- | --- |
| 4 | old | 2,004 | 84,064 of 320,020 (backlog) | 3.5 / 623 | 0.94 | 14,623 / 31,631 |
| 4 | **new** | **7,985** | all 320,016 | 1.6 / 10,011 | 5.39 | 45 / 1,047 |
| 16 | new | 7,982 | all 320,016 | 1.5 / 55,280 | 2.57 | 14 / 1,030 |
| 64 | old | 7,947 | all 320,128 | 1.3 / 1,397 | 1.83 | 26 / 1,934 |
| 64 | new, run 1 | 7,973 | 319,877; **187 ambiguous** | 1.5 / 77,201 | 2.19 | 15 / 1,930 |
| 64 | new, run 2 | 7,984 | all 320,064 | 1.4 / 34,341 | 1.73 | 11 / 1,506 |

- **Rate.** A serial FIX session here carries about 500 orders/s, roughly 2 ms per order with the store's write
  barrier. Pipelined, 4 sessions take the whole offer (4.0× the old gateway's 4), and 4, 16 and 64 sessions match.
  NFR-BSP-02's rate half is met on kind.
- **G3h.** ok on every arm that reached it (sides 1..N, each once). Run 1 of the new gateway at 64 stopped at its
  clean check; the members' trade count there equals the offer (320,064).
- **`queue` p99.** 10–77 ms on the new gateway, 0.6–1.4 ms on the old. The tail tracks how many orders arrive
  together, not the rate.

**The 187 ambiguous acks** (64 sessions, new gateway, run 1):
- Every one was committed (the members' trades equal the offer), but its ack never reached the gateway within 12 s.
  The deadline sweep reaped none. Each completed null, was answered ambiguous (no report, correct for FIX), and the
  run of nulls tripped the wedge detector five times.
- They are start-of-load orders: schedule positions 0.5–13.8 s, median 1.6 s, across 30 of the 64 sessions.
- A rerun with CPU sampling was clean, but showed the gateway CFS-throttled for 1.1 s in the first 10 s (JIT and
  64 logons), then about 2.3 of 4 CPUs with no throttling.
- The members' egress is best-effort. `offerEgress` and `flushCoalesced` try 20 times, then drop the ack (a whole
  coalesced batch), and count nothing. A gateway that lags while throttled therefore loses acks.
- A serial session lets in 1 order at start-up; a pipelined one lets in up to 1,024. So pipelining makes that drop
  reachable at a burst. The pre-fix gateway at 64 sessions lost none in its one run.
- The binary half met the same drop with coalescing off (7 of 720,819); coalescing on was not enough here.
- Fix (follow-up, member code): count every egress drop, then stop dropping committed acks to a live session.
  Either retry until the session is closed, or give the egress publication a term large enough for a burst.

**Found on the way.** `fix-multi.mjs` re-sliced its receive buffer for every message, which is quadratic. Against a
pipelined gateway it read too slowly, and the gateway dropped 3 of 4 sessions as slow consumers (ADR-092 working as
designed). It now slices once per chunk. Its ClOrdIDs are now unique per run: a rerun on the same rig had made
every order a duplicate, and about 100 restatement status queries to the read model put the 2 GiB gateway out of
heap. That is an existing exposure: a client replaying a day of ClOrdIDs can do the same.

Evidence: shared notes `fix-pipelining-kind-20261110/` (per-arm run and load logs, `/latency` dumps, G3h, the
64-session gateway log, the CPU samples, and the superseded first run). kind cluster `traderx-fsp` deleted at
1:56 pm ET.

## After the 450K acceptance run (2026-11-10): lost fills and a per-session FIX cap

The acceptance lane's stage 1 (`acceptance-450k-20261110/`, 600 FIX sessions on 18 gateways, build `8741452c`) failed
on two findings in the FIX half.

**1. Fill reports lost: fixed (FR-FFR-15, FR-FFR-16; the cause was in this lane's FR-BSP-02).**
- **The loss:** 33,310,198 fills reached their gateways and 4,501,180 were reported, with 0 gaps counted.
- **The cause:** a pipelined order is tracked when its answer runs, seconds after its fill when the session is
  behind. Fills waited in a 4,096-slot ring overwritten in arrival order, and each tracking scanned all of it.
- **Now:** early fills wait by ref, bounded by 65,536 refs and 30 s. Tracking takes an order's fills in one lookup.
  An order terminal at its ack drops them. A fill that still goes unreported is counted as `outcome="lost"` on the
  routed feed, with an ALERT.
- **Tests:** `FixGatewayFillReportTest` gains two. On the pre-fix acceptor they fail: 0 of 100 reported, and
  0 of 1,000 lost fills counted.

**2. A second serial step, found by measuring: every fill report went through one thread.**
- **What:** the feed thread built *and sent* each fill report under `feedLock`, through QuickFIX/J and the session
  store. The sends now go to the session's report thread (FR-FFR-16); the order's New is sent there first.
- **Measured** (`FixGatewayThroughputIT`, one gateway, 32 sessions × 1,024 window, every order filled, 1 ms venue,
  local NATS):

  | acceptor | answers/s | fill reports/s |
  | --- | --- | --- |
  | `8741452c` | 117,473 | 617 (the rest lost) |
  | FR-FFR-15 only | 130–136K | 4,327–4,658 (one feed thread) |
  | FR-FFR-15 + 16 | 80–88K | **73–83K, every fill** (the store's ~160K reports/s now shared by both) |

**3. The ~140 answers/s per session cap: not reproduced off the rig; instrumented instead.**
- **Off the rig, a session is 15–35× faster.** The same bench gives 2,000–5,000 answers/s per session. That holds
  with 2–5 ms of injected NATS latency each way, a 1 ms venue, and a 4,096-order venue window taken on the
  submitting thread, as `GATEWAY_MAX_INFLIGHT` is. Two and four gateways on one NATS change it little.
- **NATS latency is on a session's report path:** per-session answers fall to 1,406/s at 20 ms each way and 642/s
  at 50 ms, through the group-commit cycle (FR-GFS-18). Pure latency would need ~350 ms to reach 140/s, so the
  suspect is queueing in the store under production load, for example the one `FIX_SESSIONS` stream on one server.
  This is not proven.
- **The run's own shape:** every load pod reached the same per-pod rate whatever its gateway held. The Service's
  client-IP affinity put 0–3 pods (0–90 sessions) on each gateway and left 4 of 18 idle. So the cap is per session,
  not per gateway.
- **Instrumented:** FR-GFS-20 puts the group commit on `/metrics` (commits, bodies, seconds, max per scrape). The
  next stage-1 run, on GKE (yaakov's yes), reads whether a session's commit cycle is the seconds that 4–9 s answers
  imply.
- **Fills will cost more:** with FR-FFR-15/16 every fill is reported and stored, so each traded order costs the store
  two reports instead of ~1.1.


## After the 450K acceptance run 2 (2026-11-10): venue cancels unreported, FIX capped by the store

Run 2 (`acceptance-450k-20261110/fix/`, 600 FIX sessions on 18 gateways, on 316b269e's fixes) reported every fill and
0 lost. It found two more problems.

**1. Venue cancels never reached FIX clients: fixed (FR-FFR-17).**
- **The loss:** STP canceled 44,071,636 orders and none were reported.
- **The cause:** for an untracked order only fills waited in the early buffer, and a tracked order's CANCELED or
  REJECTED removed it without a report. With answers seconds late, STP's cancel nearly always came first.
- **Now:** an order the venue ends gets an unsolicited Canceled (or Rejected) report, sent after its New, with the
  `CumQty`/`AvgPx` reported so far. An early one waits by ref like a fill, and is counted `lost` if it ages out.
  A client's own cancel is answered once, by its request. A refused client cancel does not mute the venue's end.
  The count is `traderx_fix_end_reports_total`.
- **Tests:** `FixGatewayFillReportTest` gains three. The cancel that beats the answer fails on the 316b269e acceptor.
  The refused-cancel test fails without the reset.

**2. FIX capped near 85K orders/s, ~2 s per commit: the one session stream (FR-GFS-21, FR-GFS-22).**
- **Why a commit takes seconds:** every session's fenced bodies went to one stream, `KV_FIX_SESSIONS` on nats-1, one
  at a time. A commit waits behind its queue: 600 sessions × up to 1,024 uncommitted bodies. Locally the one stream
  also refused publishes (429).
- **Fix:**
  - The sessions are spread over four buckets per NATS server, placed as FR-DRS-44 places partitions
    (`FIX_SESSION_BUCKETS`, `FIX_SESSION_PLACEMENT`; `prepare.py` renders them).
  - A session may have 256 bodies uncommitted, not 1,024.
- **Measured** (`FixStoreThroughputIT`, 600 sessions on 18 store clients, 20 s, one local NATS process, two runs
  each):

  | buckets | uncommitted | bodies/s | commit max | 10071 drops |
  | --- | --- | --- | --- | --- |
  | 1 | 1,024 | 115–138K, 429s | 1.9–2.5 s | before FR-GFS-22 only |
  | 3 | 1,024 | 161–172K | 1.3–5.1 s | before FR-GFS-22 only |
  | 3 | 256 | **173–205K** | **0.58–0.76 s** | before FR-GFS-22 only |
  | 3 | 64 | 196K | 0.18 s | (before) |

  One process is the local ceiling, so this shows the stream cap removed, not GKE's rate.
- **Measured on GKE** (slot A, 9:04–10:05 pm ET, yaakov's yes): the p4k render's three NATS servers, 600 sessions on
  18 store clients in 6 pods on p4k-gateway, 120 s per arm, the store alone (no venue). The before arm reproduces
  run 2: ~2.2 s commits on the one stream on nats-1.

  | buckets | uncommitted | bodies/s | commit avg | commit max | busiest server CPU |
  | --- | --- | --- | --- | --- | --- |
  | 1 (before) | 1,024 | 140,099 / 144,080 | 2.21 / 2.16 s | 3.67 / 3.88 s | nats-1 0.6–1.3 of 12 |
  | 1 | 256 | 131,170 | 0.59 s | 0.97 s | nats-1 1.3 |
  | 3 | 1,024 | 389,723 | 0.79 s | 1.59 s | 1.9 each |
  | 3 | 256 | 378,353 / 375,220 | 0.20 s | 0.38 / 0.40 s | 1.8–1.9 each |
  | **12 (rendered)** | **256** | **652,673** | **0.12 s** | **0.67 s** | 3.4 each |
  | 24 | 256 | 623,702 | 0.12 s | 2.06 s | 3.5, uneven |

  A stream takes its fenced writes one at a time: throughput follows the number of streams, and the bound sets the
  wait. Neither NATS (≤3.5 of 12 CPUs) nor the clients (~0.35 CPU a pod) were busy. Twelve buckets, four per server,
  is the knee, so that is what `prepare.py` renders. No session failed and none was dropped in any GKE arm.
- **A third finding, from the bench (FR-GFS-22):** a lease that lapsed while its own commit was in flight was retaken
  by writing `K.seq`. That fenced the gateway's own batch out (10071) and dropped the session, which happened in every
  run. The commit in flight is now the retake's fence, and `confirm` waits for it. The sessions that still fail locally
  time out a commit at the one saturated process. Run 2 logged 7,122 "Logon state is not valid (MsgType=F)" errors.
  That fits sessions being dropped, but its lease WARNs were not kept, so the cause there is not shown.
- **ADR-086's invariants:** a session's `K.seq` and bodies stay in one bucket, so each body is fenced on `K.seq` in
  the same stream. Store-before-send (`FixWriteBarrier`) and the one lease bucket are unchanged. A retake is still
  confirmed only by a write fenced on the revision last seen. Every gateway runs the same bucket count, changed only
  across a FIX day reset.
- **Size:** at most `FIX_RESEND_WINDOW` (2,048) messages per session, so the store levels off at rate (run 2:
  557 MB). Each bucket keeps the 7 d / 1 GiB cap.
- **Not done:** end to end with 600 FIX clients over the venue (acceptance run 3), and a kind proof with slowed
  answers (the unit tests and the benches cover it).

## After the 450K acceptance run 3 (2026-11-10, `cfb748fb`): fills lost above ~150K/s, and the next cap

Run 3 confirmed run 2's fixes: 81,368,090 end reports matched the driver's STP cancels, and no lease lapsed. FIX
reached ~133–157K orders/s. Three items came back.

**1. 10,927,047 fills and ends lost from ~150K orders/s: fixed (FR-FFR-18).**
- **Cause:** a pipelined order was tracked when its answer ran on the session's report thread. That thread sends
  every report at the store's pace. From the 0.44 step the store was saturated (item 3), so acks waited behind it,
  and their fills and STP cancels aged out of the early buffer (30 s / 65,536).
- **Now:** the ack completes on one gateway thread (`fix-acks`), which tracks the order. Its updates wait on the
  order until its New is sent, then follow it in order.
  - What waits is bounded by the session's window (1,024 orders, a slot freed when the answer runs) × an order's
    few updates, with no time limit.
  - The early buffer now covers only the ack's own hop.
- **Tests:**
  - `FixGatewayFillReportTest#aFillWhoseAnswerWaitsBehindTheSessionsReportsIsReported`: the report thread is held,
    then the early buffer is overrun.
  - `#updatesForAnAnswerHeldPastTheEarlyAgeAreReported`: the age is scaled to 100 ms and the answer held 10×.
  - Both fail on the ff684979 acceptor.

**2. The duplicate answer and the resend past the ring: one 47 s outage of CLIENT386.**
- **What happened:** CLIENT386's socket closed at ~10:18:03 pm ET, and its Logon succeeded only at 10:18:50, 5 s
  after the load ended.
  - The gateway kept that session's messages meanwhile: the answers to its 1,024-order window, and the ends of its
    resting orders, which other sessions on the same account kept cancelling by STP. That was 14,134 messages.
  - Its ResendRequest therefore reached past the 2,048 kept. 2,048 were resent and 12,086 gap-filled, with an ALERT,
    as FR-GFS-10's ring specifies. The client reconciles those by OrderStatusRequest.
- **The resends and the duplicate:** the run-3 driver resent its 1,464 unanswered orders blindly. Past the
  engine's ~1.7 s duplicate window, that booked them again: the +427 NEW in the history store. One order's original
  answer then also arrived (resent from the ring): the 1 duplicate answer.
  - The acceptance lane has fixed the driver: it reconciles by H before resending, and records why each outage
    began. These are driver effects, not gateway bugs.
- **Not explained: why the socket closed, and why the Logon took 47 s.** Run 3 kept neither the gateway's INFO
  lines for the session nor the driver's outage cause.
  - A likely contributor: a gateway with no client keeps the lease "draining" while a commit is in flight, messages
    are uncommitted, or it wrote within 1 s (`tick()`). A Logon elsewhere waits 4 s for that, then is refused.
  - With the store saturated and STP ending the session's orders without pause, the drain could last until the venue
    went quiet, and the Logon did succeed 5 s after the load ended.
  - Against that: with 18 gateways and no ClientIP affinity, a retry every 500 ms should reach the draining gateway
    itself, which may reclaim the session, within seconds. So this is not shown.
  - The next run should keep, for any session that reconnects: the driver's outage cause, and the gateway lines
    that acquired, reclaimed or refused it.

**3. The next serial step is still the store: 3 buckets saturated from the 0.44 step.**
- **Run 3's gateway counters, per 30 s:**
  - at the 0.22 step: ~196K bodies/s, 3.6 bodies a commit, ~8 ms commits;
  - from 10:04 pm to the end: ~265K bodies/s flat, every commit 128 bodies (half the 256 bound) at ~290 ms.
  - The run-wide 37 ms mean averaged those two regimes.
- **Why that caps a session:** a session has one commit in flight, so its rate is 128 / 0.29 s ≈ 440 bodies/s,
  ≈ 220 orders/s at ~2 bodies an order. Over 600 sessions that is ~133K orders/s, the cap observed, with the
  1,024 window full. The gateway, cluster and NATS CPU were all idle because every session waited on its commit.
- **Already committed, not in run 3:** run 3's image `cfb748fb` rendered 3 buckets. 96ccc17c renders 12, measured
  at 653K bodies/s on GKE against 375–378K for 3, store alone.
  - Scaled by run 3's 265K / 378K, the same rig should carry ~460K bodies/s, about 230–270K orders/s.
- **Beyond that:** on the p4k NATS (c4d-standard-16), one stream takes ~126K bodies/s (3 buckets), and one server
  tops out near ~220K however many streams it holds (4 streams at ~54K/s each; 8 no faster). That was at 3.4 of
  12 CPUs, so it is not CPU; what limits it is not measured.
  - 450K orders/s needs ~850–900K bodies/s, about 4–5 such servers' worth. The proven lever is more NATS servers
    (FR-DRS-44 places by server). Other levers are fewer stored bodies per order, or more NATS connections per
    gateway; neither is tested.
  - Choosing a server count is a production render decision.

## NATS sizing for 450K FIX (slot B, 2026-11-10 11:34 pm – 2026-11-11 1:47 am ET, ≈ $15, yaakov's yes)

The FIX session store alone, and then with the capture at 301 MB/s (`TRADERX_FEED_P0..7`, S2), on 3, 4 and 5 of the
p4k render's NATS servers (c4d-standard-16). 600 sessions, 120 s per arm, 0 failed sessions in every arm. The target
is ~900K store bodies/s plus the capture. Evidence: shared notes `fix-store-sizing-20261111/`.

| servers | store alone, today | store + capture, today | store alone, pinned | store + capture, pinned |
| --- | --- | --- | --- | --- |
| 3 | 476–726K | 543K | 859K | 700K |
| 4 | 805K | 718K | 1.15M | **995K** |
| 5 | 964K | 594K / 838K | 1.45M | **1.26M** |

- **The limit is relaying, not CPU.** Every store client connects through the `nats` Service, so a server relays the
  publishes for streams it does not hold. Each server plateaued at ~450K msgs/s in, counting relayed copies, at ~3.3
  of 16 cores. Results also swing with where connections land.
- **"Pinned" is a bench option, not shipped code.** Each client keeps one connection per server and sends each
  bucket to its own server. Every server then takes an even ~290K msgs/s, and capture acks fall from 20–33 ms to
  0.2–0.3 ms.
- **Today's code** reaches the target on no count measured; it would need an estimated 6–7 servers.
- **Pinned:** 4 servers carry the target with ~10% headroom, 5 with ~40%.
- **Cost of the NATS tier (list, approximate):** 3 servers ≈ $2.5/h, 4 ≈ $3.4/h, 5 ≈ $4.2/h.
- **Open:** yaakov's choice of server count, and whether to build pinned connections in `FixSessionStore`
  (a connection per placement tag; `prepare.py` renders the URLs).

## Pinned store connections built (2026-11-11, FR-GFS-24)

`FixSessionStore` now writes and reads each bucket only on a connection to the server that holds it, as the slot-B
bench's `PIN` option did.
- **Loss:** losing one server's connection ends only the sessions whose bucket it holds, and refuses their Logons
  until it is back. Their writes have no other connection, so none goes to another server.
- **Render:** `prepare.py` renders `NATS_SERVERS` = 5 × 4 buckets and the per-server URLs. `rig.sh` sizes `nats-pool`
  from the same number. yaakov chose 5 on 2026-11-11 ("5 servers (40%)"); ADR-103 is amended.
- **Tests:** `FixStorePinnedIT` uses two independent servers, so a bucket's stream exists only where it was written.
  Its pinned and loss cases fail when every bucket is on the main connection.
- **Not done:** the real store on GKE, which needs yaakov's yes (≈ $10–15).

## The real pinned store on GKE (2026-11-11, 9:45–10:10 am ET, slot B, yaakov's yes)

`FixSessionStore` at 1c9b95a5 was configured as the production render configures it: `FIX_SESSION_SERVER_URLS`, 20
buckets, 5 servers. All 18 store clients logged "each bucket on its own server's connection of 5".

| arm | store bodies/s | commit avg / max | capture | NATS per server |
| --- | --- | --- | --- | --- |
| store alone | 1,416,100 | 0.054 / 0.144 s | — | ~286K msgs/s, 3.1–3.2 cores |
| + capture | 1,256,192 | 0.061 / 0.157 s | 301 MB/s, ack 0.3 ms avg / 15 ms max | ~285K, 3.6–4.2 cores |
| + capture, again | 1,260,914 | 0.061 / 0.163 s | 301 MB/s, ack 0.3 / 12 ms | ~288K, 3.8–4.2 cores |

- No session failed, and no capture publish failed.
- This matches the bench prototype (1.45M, and 1.26M with the capture): ~40% over the ~900K bodies/s that 450K FIX needs.
- Evidence: shared notes `fix-store-gke-real-20261111/`.
- Not done: end to end with FIX clients on 5 servers, which is the next acceptance run.

## Run 4's lost fills: the tracked bound, not the early buffer (2026-11-11, FR-FFR-19)

Run 4 (3a42e515, slot B) lost 171,167 fills and ends by the end of the 0.67 step (~300K orders/s), and 44M by the end.
The evidence is in the acceptance lane's `acceptance-450k-20261110/fix/`.

- **The trigger was upstream.** The routed feed comes from the read model's fills router (trade-processor, FR-FFR-14).
  - trade-processor-0 was a NATS slow consumer from 12:00:57 pm ET, and the first lost-fill ALERT came 2 s later.
  - Losses came in bursts, one at each of its connection resets.
  - It ran out of heap at 12:05:34 pm ET and never became Ready again.
  - From 12:10:34 pm ET no client received a fill.
- **The gateway made late fills lost.** The tracked set was an LRU of 65,536 orders: about 4 s of one gateway at 300K
  (16.7K orders/s each).
  - Every order acked while the feed is late is still working, so the LRU evicted working orders.
  - Their fills then found no order, waited in the early buffer, and aged out counted lost. The ALERT wrongly said
    "never tracked here".
  - With the feed on time, 776–869 s at 0.67 lost 0.
  - Per-session in flight stayed under 100 and commits under 63 ms, so it is neither the window nor `fix-acks`.
- **Fix (FR-FFR-19).** A working order stays tracked until its end.
  - `FIX_TRACKED_ORDERS` defaults to 524,288, measured at ~215 B an order (~113 MB of the 768 MB heap). That rides
    ~30 s of feed lag at 300K and ~20 s at 450K.
  - Past it, an eviction is counted (`traderx_fix_tracked_evicted_total`) and ALERTed.
  - The test fails at the old bound.
- **Not fixed here:** a feed later than the bound, or one that never delivers.
  - 84.4M orders still looked resting to the clients against 44.0M counted lost. The rest never reached a gateway,
    which points to the router's own loss after its out-of-memory crash.
  - That is the trade-processor's to fix.
