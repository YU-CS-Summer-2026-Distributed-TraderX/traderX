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
  - The sessions are spread over one bucket per NATS server, placed as FR-DRS-44 places partitions
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

  One process is the local ceiling, so this shows the stream cap removed, not GKE's rate. With three servers each
  bucket has its own server.
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
- **Not done:** GKE at 600 sessions over three servers (needs yaakov's yes), and a kind proof with slowed answers
  (the unit tests and the benches cover it).
