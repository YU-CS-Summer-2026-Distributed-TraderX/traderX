# The gateway's HTTP executor stops serving and never drains

**Split out of `HANDOFF-issue-gateway-wedges-after-leader-kill.md` on 2026-08-18**, when that issue was
resolved. **This half was NOT resolved with it** and is extracted so it does not get buried: the parent
covered ack correlation, which Option B closed on every branch YU13 and up. This is a different defect
with a different mechanism.

**Status: OPEN.** The probe half is fixed (probes moved to their own `HttpServer` on
`GATEWAY_PROBE_PORT` 18111 with a single-thread executor). The main HTTP server's behaviour is not.

**The unexplained part is the important part.** A bounded ~12s wait per request should drain thousands
of queued requests in minutes. Measured: eight minutes with **zero load offered**, polling every 30
seconds, still no response at all — connection accepted, never served — while the JVM was alive, the
Aeron side kept applying and logging, and TCP kept accepting. Only a restart cleared it. Raising the
pool 8 → 64 moved the cliff; it did not remove it, and it does not explain the non-drain.

**Note on what changed underneath this since it was written:** Option B replaced positional ack
correlation with keyed correlation and deleted A's `onNewLeaderResync` drain. In-flight orders now
complete by key or are reaped by the deadline sweep, so the specific "all 64 threads parked for the
full `ACK_TIMEOUT_MS` because no ack ever matches" pathway may no longer arise the same way. **That is a
hypothesis, not a finding — nobody has re-run this repro on a B build.** Doing so is step one, and it
may close the issue outright or narrow it usefully.

---

## §5. A worse failure hiding inside it: the gateway stops serving HTTP entirely

Found 2026-08-13 while building the readiness proof. Drive a wedged gateway hard enough — an
unbounded generator, roughly 20 orders/sec — and it stops answering **any** HTTP request, `/ready`
and `/health` included. Not 503. No response at all, connection accepted and never served.

**It does not recover.** Measured: eight minutes with zero load offered, polling every 30 seconds,
still 000 every time. The JVM was alive (PID 1, 23 minutes uptime), the Aeron side was alive (the
control feed kept applying and logging), and TCP kept accepting. Only a restart cleared it.

The mechanism is almost certainly the HTTP executor: every in-flight order parks one of the 64 pool
threads for the full `ACK_TIMEOUT_MS` (10s) plus slack, and under a wedge none of them complete
early. `gateway.yaml` already carries a comment about exactly this shape — the pool was raised from
8 to 64 because "the readiness probe starved behind them and k8s pulled the gateway out of the
Service mid-bench". 64 only moves the cliff; it does not remove it. What is NOT explained is why it
never drains after load stops, which is the part worth investigating: a bounded 12s wait per request
should clear thousands of queued requests in minutes, and it did not clear in eight.

**Why this matters more than the wedge.** It defeats any probe-based fix. A readiness signal the
server cannot serve is not a signal — the pod does go NotReady, but by probe *timeout*, which is
what the old build did too, so nothing is gained and nothing is diagnosable. Under load the honest
503 from §1's fix never gets sent.

**The probe half is fixed, 2026-08-13.** `/ready`, `/health` and `/live` are now also served by a
separate `HttpServer` on `GATEWAY_PROBE_PORT` (18111) with its own single-thread executor, and every
probe in `gateway.yaml` reads that port. They stay registered on 18110 too, so the proofs and
benches that curl it are untouched. Asserted directly rather than assumed: step 2 of
`yu16-liveness-restarts-wedge.sh` takes the reading *while* 80 concurrent orders (the pool is 64)
are parked on acks that will never arrive, and the probe port answered 200/503 throughout. So the
verdict Kubernetes now acts on is the gateway's own, not a timeout.

**The hang itself is NOT fixed and is still not diagnosed** — the order path still fills up and
still does not drain after load stops. What changed is that it is now survivable without a human:
liveness fails on the streak (or, if the JVM itself is gone, on timeout) and the kubelet restarts
the container, which is the only known cure. The open question is unchanged and still worth
answering: why a bounded 12s wait per request never clears in eight minutes.

**One candidate mechanism has since been eliminated by measurement** — the in-flight permit window
is NOT leaked by the wedge, established independently on kind and on GKE. What replaced it is a
**permanent FIFO correlation offset**: `Inflight.onDirectAck` pops the head positionally with no id
matching, so a leader change strands N offers and every later ack thereafter pops a head belonging
to an abandoned request. One pop per push — nothing drains and nothing accumulates. See *The
permit-leak mechanism* and *The FIFO correlation offset* at the end of this section before proposing
a cause. (An earlier reading of the kind data as "late acks" was retracted by its author the same
day; the retraction is kept there because the reasoning error is instructive.) Note also that the
"bounded 12s wait" in the sentence above is two **serial** bounded waits — up to 10s in
`inflight.acquire(ACK_TIMEOUT_MS)` and then up to 12s in `future.get(ACK_TIMEOUT_MS + 2_000)` — so
the worst case per request is ~22s and every drain-time estimate built on 12s is about half what it
should be.

**And the liveness proof does not answer it — do not read it as if it did.** Its step 4 commits an
order after a 160-order drive, which looks like evidence the backlog drains; it is not, because the
restart under test kills the owner queue first and step 4 always meets a fresh JVM. §6 has the
reasoning.

### THE WEDGE REPRODUCED ON KIND, 2026-08-14 — and it is NOT a session close

Run on `traderx/cluster-node:yu17wedge`, the build carrying the full `EgressListener`, so a session
event would now be visible if one arrived. Route: `scripts/proofs/yu12-gke-failover-transparency.sh`
against kind with the gateway at `replicas: 1` — i.e. a leader kill UNDER a live order stream, which
is the one condition neither of the other two scenarios covers.

The proof failed at its own assertion, which is the wedge arriving:

```
stream done: 739 acked, 0 needed retries, 1 gave up
[FAIL] 1 orders were never acknowledged even after retries — the outage was not transparent
```

and the gateway was left in §1's signature exactly — all three members `1/1`, a new leader elected,
and:

```
/ready : {"connected":true,"noAckStreak":1,"noAckLimit":20}
POST /orders -> {"error":"no committed ack"}
```

**The session was never closed.** The full listener logged only:

```
CLUSTER-SESSION-EVENT code=OK session=3 leader=2 term=12
CLUSTER-NEW-LEADER leader=2 term=12 session=3
```

**non-OK session event count: 0**, across the kill and after it.

#### What this settles

1. **The wedge is not a closed or errored session.** With the events now visible, none arrived. So
   the `sessionLost` reconnect trigger added above **cannot cure this wedge** — it is wired for a
   condition that does not occur here. The logging half of that change is what earned its keep: it
   is how this was measurable at all.
2. **§1's divergence, confirmed at 1:1 with the cluster's own witness.** One HTTP request, measured
   cleanly:

   ```
   code 504, body 28 bytes {"error":"no committed ack"}
   traderx_cluster_next_order_ref 881 -> 882   (delta 1)
   ```

   One request, one ref consumed, one client told its order failed. The book moves while the client
   is told nothing happened.
3. **It does NOT support §4's internal-resubmission hypothesis** — at least not under a full wedge.
   §4 speculated that "the gateway resubmits internally when an ack does not arrive", from 55
   unexplained refs. Under a total wedge the ratio is exactly one ref per client request. (Caveat:
   §4's reading was taken during a *transparent-failover* run, not a wedge, so this measures a
   different regime rather than refuting it.)
4. **The remaining direction is §2, narrowed.** Ingress still works — the cluster sequences and
   consumes a ref for every order. The session is open and `code=OK`. Aeron's `onNewLeader` fires
   and recreates the ingress publication. So the break is specifically the EGRESS path to this
   client after a leader change, which is precisely what §1 describes and what §2 asked about.

#### The cure, and why it was not attempted here

A `rollout restart` still clears it instantly, which means a fresh session is sufficient. The
obvious next move is to trigger `connectCycling()` from the no-ack STREAK rather than from a session
event — the streak is already computed for readiness and liveness.

**That was deliberately not done, and the reason is a real hazard rather than caution.**
`connectCycling()` loops `while (running)` until it connects. Firing it on streak during a QUORUM
LOSS — where the streak also climbs, and where the cluster is unreachable by construction — would
park the owner thread inside the reconnect loop and make a recoverable outage permanently worse.
Any streak-triggered reconnect needs a bounded attempt count and a way to distinguish "my session is
bad" from "the cluster is down", and it must be re-proven against `yu16-ready-tracks-commit` (whose
step 3 asserts `/ready` stays 503 across a RESTORED quorum) and `yu16-liveness-restarts-wedge`.

### Quorum loss does NOT close the session — measured 2026-08-14, and it narrows §5

Run on `traderx/cluster-node:yu17wedge` (the build carrying the full `EgressListener`, so a session
event would now be visible if one arrived). Members 3 → 1, 40 concurrent orders driven into a
cluster that cannot commit, then quorum restored.

| | reading |
|---|---|
| `/ready` under quorum loss | `{"connected":true,"noAckStreak":30,"noAckLimit":20}` — correctly failing |
| `/live` under quorum loss | `{"noAckStreak":30,"noAckLimit":100}` — correctly not yet restarting |
| **session events, during** | **none. non-OK count 0** |
| **session events, after** | **none. non-OK count 0** |
| commit after quorum returned | `{"orderRef":69,"kind":1}` — recovered unaided |

**The session stays OPEN through quorum loss.** Nothing closes, nothing errors, and the gateway
recovers on its own once quorum returns — so `sessionLost` correctly never fires here, and this
route cannot be used to exercise the reconnect trigger.

**What that rules out for §5.** Three scenarios now measured on this build:

| scenario | session event | outcome |
|---|---|---|
| plain leader kill, no load | `CLUSTER-NEW-LEADER` + `code=OK` | recovers, commits normally |
| quorum loss under load | none at all | recovers when quorum returns |
| leader kill UNDER SUSTAINED LOAD | — | §5's wedge |

The wedge is therefore **not** a closed or errored session, and not merely "the cluster cannot
commit" — both of those recover unaided. That leaves §2's hypothesis as the live one: the egress
subscription after a leader change. The gateway follows the new leader for INGRESS (Aeron's
`onNewLeader` recreates the ingress publication, and orders demonstrably still commit), so the
asymmetry is on the EGRESS side — which is exactly what §1 describes: "the cluster sequences and
books it. Only the ack path back to the gateway is gone."

### The drain experiment, run 2026-08-13 — and what it does and does not settle

Run on kind with `LIVE_NO_ACK_STREAK=100000` so no restart could intervene: lose quorum, drive
2 × 80 concurrent orders (streak reached exactly 160), restore quorum, then stop **all** load and
watch. Result:

> **First committed order at +0s after quorum returned, `restarts=0`, streak already back to 0.**

So on the pipelined tier, under quorum loss, **the abandoned-task backlog is not self-sustaining**.
160 abandoned tasks did not cost 160 × `ACK_TIMEOUT_MS` of owner-thread time, because the per-task
10s is spent only while the cluster refuses the offer — once quorum is back each queued task offers
in microseconds and the queue evaporates. The "queue that cannot drain" hypothesis is dead for this
shape.

**It is NOT an answer to §5, and must not be read as one.** §5's hang was the leader-kill WEDGE
under a sustained generator, in which the gateway stopped answering *all* HTTP and had not
recovered after eight minutes with zero load offered. Quorum loss induces the same *property*
(nothing can commit) but evidently not the same *mechanism* — this run recovered instantly where
that one never did. §5's mechanism remains unreproduced and unexplained. What is now known is
narrower and still worth having: whatever §5 is, it is **not** simply a deep owner queue.

### The side finding, which is worth more than the drain answer: §1 has a DETERMINISTIC repro

The same run reproduced the invisible-orders defect exactly, in about 90 seconds, with no leader
kill and no race:

| witness | reading |
|---|---|
| submits that got no committed ack | **160** (the gateway's own `noAckStreak`, so every one of those clients was answered 504) |
| `traderx_book_open_orders` | 51 → **211** |
| `traderx_cluster_next_order_ref` | **212** |
| member agreement | all three identical: `applied=7568 open=211 nextRef=212 hash=-734721819140448701` |

**159 of the 160 orders every client was told had failed are resting in the book.** (One did not
consume a ref — its offer never cleared before the deadline.) That is §1, on demand, without the
1-in-4 wedge race the rest of this document is built around. Anyone working on §1 should use quorum
loss to produce the divergence and stop hunting the wedge for it; the wedge is only needed for §5.

### The permit-leak mechanism — PROPOSED, TESTED, AND ELIMINATED, 2026-08-16
<!-- The elimination stands (both arms, independently). One conclusion drawn from it — "late acks" —
     was RETRACTED by its author the same day; see the retraction below and the offset model that
     replaced it. -->


A candidate cause for the non-drainage was read out of the code and then killed by measurement. It
is recorded because it is the most plausible mechanism anyone has produced for §5, and because
eliminating it narrows what is left.

**The hypothesis.** `Inflight` is a `Semaphore(GATEWAY_MAX_INFLIGHT)`, default **4096** and set in
no manifest. `release()` is owner-thread-only. When an offer CLEARS, `offerPipelined` calls
`inflight.register(p)` and holds the permit until the ack arrives; the submitter's own timeout
(`submitPipelined0`: `future.get(ACK_TIMEOUT_MS + 2_000)` → `TimeoutException` → `return null`)
does **not** release it. The wedge is precisely "offer clears, ack never arrives" — so every wedged
order would burn a permit permanently, the window would be gone in ~205s at 20 orders/sec, and
every later request would block the full acquire timeout. That would explain non-recovery exactly,
and it would explain why only a restart cleared it.

**It is wrong.** Measured on `traderx/cluster-node:yu15prewedge` (the pre-self-heal build) against
`:yu16` members, on a rig verified quiet first — `next_order_ref` stationary at 4023 over 10s, every
process of the experimenter's killed:

| witness | reading |
|---|---|
| 5 serial orders, wedged gateway | **5 × 504** `no committed ack` |
| `traderx_cluster_next_order_ref` | 4023 → 4028 — **delta exactly 5**, one ref per order |
| `traderx_gateway_inflight_orders`, sampled every 0.5s for 40s | **3, then 2** for every remaining sample |

The ref delta confirms these are genuine §1 wedge orders: the offers cleared and the cluster booked
what the client was told had failed. **And the depth is flat — permits are acquired and released,
order by order.**

**What that leaves, by elimination.** A permit is freed only by (a) an arriving ack in
`completePipelinedHead`, (b) one of `offerPipelined`'s three failure paths, or (c) `drain()`. The
offers demonstrably cleared, so (b) is excluded. No reconnect occurred — `GATEWAY up` count 0,
`restarts=0` — so (c) is excluded. **Therefore the acks are arriving, just later than the
submitter's 12-second deadline.**

> ~~**The reproducible wedge is LATE acks, not ABSENT acks.**~~ **RETRACTED 2026-08-16, same day,
> by the author.** The permit-leak elimination above stands. This conclusion drawn from it does not.

**Why it was wrong, precisely.** The argument ran: the offer cleared, no failure path ran, no
reconnect occurred, therefore the only remaining release is an arriving ack, therefore *this order's*
ack arrived late. The first three steps hold. The fourth does not follow, because
`Inflight.onDirectAck` pops the FIFO head **positionally, with no id matching** — so an arriving ack
releases *a* permit, not *that order's* permit. "A permit was released" is not "this order was
acknowledged", and collapsing the two is the whole error. The GKE arm measured the distinction
directly (an ack counter incrementing while the gap stayed pinned); this arm never sampled an ack
counter at all and inferred the ack from the permit release.

**The correct mechanism is a permanent correlation offset**, established on GKE and written up in
*The FIFO correlation offset* below. What follows here is the kind-side evidence **for** that model,
re-filed as supporting data rather than as a competing result.

**And §5's total hang is a DIFFERENT STATE from the wedge this route produces.** Four induction
attempts via `yu12-gke-failover-transparency` on kind; two produced a wedge, and **both were
partial** — roughly one order in ten still committed, `noAckStreak` oscillated between 1 and 9
rather than running away, and HTTP never stopped answering. That is the partial-degradation regime
§1b already records, not the eight-minute total outage §5 describes. **Do not assume the two are
reachable by the same route.**

#### The kind evidence FOR the offset, and the reading that settles it

The single strongest observation from this arm was taken **before** the experiment and was not used
against its own hypothesis until the GKE result arrived. On the freshly rolled `:yu15prewedge` pod,
before any induction:

```
traderx_gateway_inflight_orders 0
```

Depth was **0**. It became **2** only after leader kills, and then stayed pinned at 2 — through long
idle stretches with zero load offered.

> **A non-zero depth that persists while the gateway is idle cannot be lateness.** Late acks still
> arrive, so permits still return, so an idle window empties to 0. A window that idles at 2 requires
> entries that will *never* be completed — which is the stranded FIFO, at K=2 here against K=51 on
> GKE. The two arms measured the same curve at different magnitudes: `0 → 21 → 36 → 51` across three
> leader kills there, `0 → 2` here.

The fast sample `3 2 2 2 2 …` across a single wedged order is the same model seen per-request: the
order pushes depth to K+1, an arriving ack pops the *stranded head* back to K, and the order itself
becomes the new tail and times out at 504.

**This also explains why every kind wedge was PARTIAL, which had been recorded as noise.** Under an
offset, order *i* is completed by the ack of order *i+K*. Sent **serially**, nothing follows within
the 12-second window, so every order 504s — this arm's 5-for-5 and 9-of-10. Sent in a **burst**, ack
*i+K* can land inside order *i*'s window, so a fraction return 200. Success is therefore a function
of in-flight depth against K, not of luck, and it is a second independent signature of the
`K > pool size` cliff.

**A prediction from the offset that neither arm has measured.** `completePipelinedHead` builds the
response from the **arriving** ack, not from the popped order:

```java
final int ref = p.type == InputEvent.TYPE_ORDER_NEW ? buffer.getInt(offset + 8) : p.orderRef;
//                                                    ^^^^^^ the ACK's ref, not p's
```

So a client whose request is popped by a foreign ack receives **another order's `orderRef` with HTTP
200** — not a timeout, a confident wrong answer cross-wired between clients. If that holds, the 200s
observed during a wedge are not successes, and it is worse than §1: there a client under-counts its
own exposure, here it records someone else's.

**UNMEASURED on both rigs, and the obvious test does not work.** A concurrent burst with distinct
`clientOrderId`s, checking returned refs against the assigned block, **cannot discriminate** — an
all-at-once burst destroys offer order, so "the first K got nothing and the rest got their own refs"
and "every 200 carried the ref of the order K later" predict the *same* split, the *same* contiguous
block and the *same* K missing. The GKE arm measured exactly that at K=13 (37×200 over a contiguous
ref block, 13×504) and correctly retracted it as ambiguous. Two ways to break the tie:

- **Stagger the burst ~60 ms** so offer order equals launch order while all requests still land
  inside the 12s window. The models then split cleanly: under the offset the **last** K clients are
  the ones answered 504; under the innocent reading the **first** K are.
- **Better — use the engine's own idempotency table as an oracle, which needs no offer-order control
  and works on a single order.** `BlpRiskState` holds `clientOrderKey -> (decision, orderRef)`
  *inside the replicated state machine*, and `MatchingEngine.onNewOrder` answers a repeat key by
  **re-emitting the ORIGINAL order** rather than creating a second one. So: send one order during
  the wedge with a unique key and record the ref the client is given; then resend **the same key**
  once the wedge has cleared, and the engine returns the authoritative ref for that key. Mismatch =
  the wedge response was cross-wired. The table is replicated and survives a gateway restart and a
  reconnect onto a different replica, so clearing the wedge between the two sends is safe.

  **Three constraints, and the third fails in the dangerous direction.**
  1. The table is bounded and LRU-evicted — resend before eviction.
  2. A blank or absent `clientOrderId` hashes to 0, the engine's "no key" sentinel. (A real hash of
     0 is remapped to 1 so it can never collide with it.) A test that forgets to set a key measures
     nothing and **reads as a clean pass**.
  3. **The re-emit is guarded on the original still being RESTING**, and if it is not, the engine
     silently creates a new order with a new ref:
     ```java
     int originalRef = risk.existingOrderRef(e.clientOrderKey());
     if (originalRef >= 0) {
         RestingOrder original = lookup(originalRef);
         if (original != null) {        // ← original must still be resting
             out.emitOrderUpdate(original, …);
             return;
         }
     }
     // falls through: takeFromPool() → a BRAND-NEW order with a NEW ref
     ```
     So if the original filled or was cancelled between the sends, the resend returns a different
     ref **for an entirely innocent reason** — and "the resend returned a different ref" is exactly
     what this test treats as proof of cross-wiring. **The failure mode manufactures a false
     positive on the severe claim.** Mitigate in the method, not in the reader's head: use a limit
     that cannot cross (a far-off-market resting buy), and confirm `traderx_book_open_orders` still
     holds it immediately before the second send.

  Verified on the **operative** layers rather than the first file that matched, since "the engine
  already does this" is the shape of claim that gets read off a shadowed copy: `MatchingEngine` is
  carried by YU01, YU02, YU03, YU12 **and YU13** — so YU13–YU15 run the YU13 copy and the YU12 one
  is a corpse for them — and `BlpRiskState` is carried by YU03 **and YU14**, so YU15 runs YU14's.
  Both carry the path.

`GATEWAY_MAX_INFLIGHT` was deliberately **not** shrunk to force exhaustion: with depth provably
flat, a smaller window changes the number and not the mechanism, and forcing saturation would
manufacture a failure that does not occur naturally and then explain it.

#### Two permit-holding states, not one — the second was not previously identified

`inflight.acquire()` runs **before** `tasks.add(new FutureTask<>(() -> offerPipelined(p), null))`,
so a permit is held from before the owner task is even queued. `drain()` iterates `fifo` only.

| state | in FIFO? | freed by `drain()`? |
|---|---|---|
| acquired, task queued, **owner thread blocked in `connectCycling()`** | no | **no** — freed only when the owner resumes and the task runs |
| offered and `register`ed, ack outstanding | yes | yes |

Measured under quorum loss (40 concurrent orders): depth held at **38** for as long as quorum was
absent, then **38 → 0 within 10s** of quorum returning, with commits resuming (`orderRef 2417`).
That is the drain experiment's "+0s recovery" seen from the permit side, and it confirms the
accounting is sound in the recoverable regime.

#### The instrument for this question is unreadable when the question arises

`traderx_gateway_inflight_orders` and `traderx_gateway_inflight_capacity` are exported on
`/metrics`, and `/metrics` is registered **only on the order port (18110)** — the port that hangs.
The probe server on `GATEWAY_PROBE_PORT` carries `/ready`, `/health` and `/live` and nothing else.
So the gauge needed to confirm or refute a saturation hypothesis is unavailable at the moment of
saturation, whichever way the answer goes. This is the same shape as a probe served by the pool it
reports on, which §5 already fixed one port over. Workaround used here, needing no rebuild: the
runtime image is `eclipse-temurin:21-jre` (no `jcmd`/`jstack`), but `kill -3` makes the JVM dump all
threads to stdout, where `kubectl logs` picks them up — the JVM serves that, not the HTTP server.
**Take a healthy-load baseline dump first**: on this build it read 0 `tryAcquire` frames and 2
`future.get` frames across 66 pool threads, and the wedged dump is uninterpretable without it.

### The FIFO correlation offset — GKE arm, 2026-08-16. §5 IS DIAGNOSED

**The ack correlation is positional. Nothing names the order an ack completes.**

```java
PendingOrder onDirectAck(final long inputSeq) {
    if (inputSeq == lastInputSeq) return null;   // continuation fill of the already-answered order
    lastInputSeq = inputSeq;
    return fifo.pollFirst();                     // ← pops the HEAD. Unconditionally.
}
```

The invariant this needs is "one direct ack per cleared offer, in offer order". **A leader change
breaks it**: the offers the dying leader had already sequenced never produce egress to this session,
those entries stay in the FIFO forever, and the FIFO is thereafter permanently **K** ahead. Every
later ack pops a head belonging to an abandoned request. One pop per push, so **K is exactly
conserved — nothing accumulates and nothing drains.** The comment above the pop reasons about
precisely this failure for the *continuation-fill* case and guards it; the stranded-offer case is
unguarded, which is how it survived review.

**Everything below was measured on the GKE rig** (`traderx-bench`, `us-east1-b`, gateway
`replicas: 1`) on gateway image `cluster-node:yu15prewedge-amd64` @ `sha256:f28b8cfa…`, built from
`YU15-eod-risk-extract@9caab079^` — pre-self-heal, so nothing could rebuild the session mid-run.
Members and producers untouched on `:yu16`. Build identity confirmed *on the rig*: zero
`CLUSTER-SESSION-EVENT` / `CLUSTER-NEW-LEADER` / `GATEWAY-WEDGE-SUSPECTED` lines all run, `restarts=0`.
The mixed-version window was checked first — `InputEvent` (YU03), `OutputEvent` (YU13) and
`AeronReplicationCodec` (YU15) are byte-identical operative layers across YU15 and YU16 — then
confirmed empirically with `{"orderRef":2,"kind":1}` on a verified-quiet rig.

**K is a ratchet.** Sustained ~20 orders/s, leader killed repeatedly: `0 → 21 → 36 → 51` over three
kills, and **two further kills stranded nothing** — the strand is probabilistic, which is the
"~1 run in 4" folklore seen from the other side. K never went down. It survived member catch-up, a
new leader, and thousands of subsequent healthy orders.

**K is an offset, not a queue.** Raising the offered rate from ~20/s to **~95/s left `depth` at
exactly 51**. A queue grows with offered load; an offset cannot.

**The acks are not absent and not late — they arrive and answer the wrong request.** Zero load, 30 s,
every field frozen: `depth=51 off_ok=3744 ack=3693 gap=51 refs=3695/3695/3695`, `/ready` 503 with
`connected:true`. Then **one** order, distinct `clientOrderId`:

| | before | after |
|---|---|---|
| client | — | `504 no committed ack` at **12.000 s** |
| member `next_order_ref` | 3695 | **3696** (+1) |
| `offer_success` | 3744 | **3745** (+1) |
| `ack_completed` | 3693 | **3694** (+1) ← **an ack arrived and was consumed** |
| `gap` / `depth` | 51 / 51 | **51 / 51** |

Repeated twice more, identical. **`ack_completed` incrementing while the requesting client gets
nothing is the reading that separates "late" from "misaligned"**, and it is the one the kind arm
did not take — it inferred an arriving ack from a released permit, which the positional pop
invalidates. Its own retracted "late acks" numbers fit this model once that step is removed.

#### §5's cliff is at K = HTTP pool size

A client's answer needs **K more acks** to pop through, so its latency ≈ `K / offered-rate` and the
threads occupied at equilibrium ≈ **K**. Three regimes, all measured:

| regime | what the client gets |
|---|---|
| rate < K/12 | **every request 504s** — at rest, all 504 |
| rate > K/12 | **200 again**, inside the deadline. At ~20/s `noAckStreak` fell back to 0 while `gap` stayed pinned at 51 |
| **K ≥ 64** | **§5. Total HTTP collapse** |

That is what `gateway.yaml`'s own *"64 only moves the cliff; it does not remove it"* has always been
describing. **Raising the pool raises the K at which collapse begins and changes nothing else.**

#### The positive arm, reproduced twice, and the discriminator read

Drive ~95–300 orders/s, kill the leader, push K past 64:

```
22:25:44  mrc=28  ready=503 {"connected":true,"noAckStreak":64}   refs=10360/x/10360
22:25:54  mrc=28  ready=503 {"connected":true,"noAckStreak":128}  refs=10424/x/10424
22:26:05  mrc=28  ready=503 {"connected":true,"noAckStreak":192}  refs=10488/10488/10488
```

`mrc=28` is `/metrics` on **18110 timing out** — §5's "no response at all". `/ready` on **18111
answered 503 throughout** (the probe-port split earning its keep), members kept committing, and
`noAckStreak` climbed in **exact steps of 64** — the pool retiring one full batch at a time, which
is pool saturation measured rather than inferred.

`kill -3` to PID 1 during a live hang (no `jcmd`/`jstack` in `eclipse-temurin:21-jre`; SIGQUIT dumps
to stdout where `kubectl logs` picks it up). All **64** order-pool threads, one identical stack:

```
"pool-1-thread-N"  TIMED_WAITING (parking)
    java.util.concurrent.CompletableFuture.timedGet / .get
    ClusterGatewayMain.submitPipelined0(:855) → submitPipelined(:814) → submitOrder(:728) → handleOrder(:1100)
```

Threads anywhere in `Inflight.acquire` / `Semaphore.tryAcquire`: **0**. Two further frames matter as
much: **`cluster-client-owner` was RUNNABLE inside `ownerLoop`, not blocked** — so this is not a deep
owner queue either; and **`HTTP-Dispatcher` was RUNNABLE in `EPoll.wait`** — still accepting.
Connections are accepted, handed to the fixed pool's *unbounded* queue, and never served. That is
§5's "connection accepted and never served", mechanically.

#### The open question is answered: the backlog always drained, at 5 requests per second

With `LIVE_NO_ACK_STREAK=100000` so the kubelet could not intervene, all load stopped, member refs
frozen, `restarts=0`:

| time | `noAckStreak` |
|---|---|
| 22:35:52 | 448 |
| 22:36:04 | 512 (+64 in 12 s) |
| 22:36:17 | 576 (+64 in 13 s) |
| 22:36:31 | 640 (+64 in 13 s) |

**+64 every ~12.5 s** — one pool batch per `ACK_TIMEOUT_MS + 2s`. 18110 recovered at **+69 s**,
`restarts=0`, a genuine drain. Service rate under the wedge is **64 / 12.5 ≈ 5.1 requests/second**.
Eight minutes of that is ~2,450 requests, and an unbounded ~20/s generator queues more than that in
under three minutes. **"It never drains" was a finite drain nobody had measured the rate of.**

> **This supersedes the "~22s worst case, so halve every drain estimate" note earlier in this
> section.** The two waits are serial only when the semaphore is *exhausted*: `inflight.acquire`
> blocks solely when no permit is free, and under this wedge `depth` sat at 51–64 against a
> `MAX_INFLIGHT` of 4096 (read off the running pod — no overlay sets it). So `acquire` returned
> immediately and only `future.get` was ever paid. The thread dump is the proof: **0 threads in
> `Semaphore.tryAcquire`, 64 in `CompletableFuture.get`.** The measured 12.5 s per batch matches 12 s,
> not 22 s. The ~22 s figure is a true statement about the code's worst case and a false one about
> this failure.

#### Negative control — quorum loss, same image

| phase | reading |
|---|---|
| quorum lost (3→1), under load | `connected:false`, `offer_success` frozen, **`depth` climbed 256 → 288** |
| quorum restored | **`depth` back to 0**, `/ready` 200, orders commit normally |

Permits climbed and **returned**; they did not pin. And it explains the cure: `connectCycling()`
calls `inflight.drain()`, which empties the FIFO and resets `lastInputSeq = -1` — i.e. **sets K back
to 0**. That is why a `rollout restart` and the self-heal both work, and why quorum loss (which
forces a reconnect) leaves no standing offset while a leader kill (which does not) leaves one
forever. **The whole difference between a recoverable outage and the permanent wedge is whether the
gateway reconnects and therefore drains.**

**Instrument trap:** `drain()` releases permits and completes futures but **never increments
`ack_completed`**, so after any reconnect `gap` over-reports by the number drained (the control sat
at `gap=64` with `depth=0`). **`depth` is the live offset; `gap` equals it only when nothing has
drained** — and the two agreeing exactly (51/51) through the wedge runs is itself the evidence that
no reconnect occurred.

#### The liveness probe does cure §5 — measured, and it is not a diagnosis

On the first hang (default `LIVE_NO_ACK_STREAK=100`), streak reached 192, `/live` failed, and the
kubelet restarted the container: `restartCount 0 → 1`, event `Container gateway failed liveness
probe, will be restarted`, serving again **26 s** later. §1b's mitigation converts an unbounded
outage into a ~26 s one on the tier where it matters. It neither prevents the wedge nor explains it,
and the offset rebuilds on the next leader kill.

#### BUT THE CURE ONLY WORKS WHEN TRAFFIC BYPASSES THE SERVICE — measured 2026-08-17, kind

**The §1b liveness restart cannot fire for Service-routed traffic, because readiness starves it.**
Every reading in the paragraph above — and every green run of `yu16-liveness-restarts-wedge` —
drives the **pod IP**, deliberately (a failing readiness probe evicts the pod from the Service at
exactly the moment a measurement matters). Production traffic arrives through the LoadBalancer →
Service. The two paths differ by one hop, and the hop is the defect.

**The chain, each link verified in the operative YU13 layer:** `noAckStreak.set(0)` exists at
exactly one site — the success branch. Readiness is `connected && streak < 20`; liveness is
`streak < 100`; **both read the same counter, and the counter advances only on submits.** Readiness
failing empties the Service endpoints, and at `replicas: 1` that is all of them. So eviction at 20
stops the very traffic the streak needs to reach 100.

**Measured, both arms on one wedge** — gateway `traderx/cluster-node:yu15prewedge`, members
`:yu16`, kind rig, K=20 induced by a leader kill, verdict branches written before the run:

| | **Service arm** (`svc/order-matcher`) | **pod-IP arm** (same wedge, same build) |
|---|---|---|
| requests | 182 over 6 min, **0 reached the gateway** (`rc=7`, endpoints empty) | serial, every one reached it and 504'd |
| endpoints | `ready=[]`, pod in `notReadyAddresses`, throughout | n/a — bypassed |
| streak | **frozen at 80** for the whole hold | climbed 80 → 101 at +1/~14 s |
| `/live` | **200** (`{"noAckStreak":80,"noAckLimit":100}`) | 503 at 101 |
| restart | **never** (`restarts=0`, hold > 5 min) | **41 s after crossing 100** — `restarts 0 → 1`, kubelet event `Container gateway failed liveness probe, will be restarted` |

One delta from the prediction, stated rather than smoothed: the streak froze at **80**, not "near
20" — the induction's own concurrent drivers had pushed it past 20 before eviction landed, and the
freeze value is simply wherever the counter stood when traffic stopped. What matters is the bracket:
**any freeze in [20, 100) is permanent**, and eviction at 20 guarantees entry into it.

**The precise claim, and its bounds.** No **automatic, in-band** recovery exists: the mechanism
designed to provide it cannot engage. Out-of-band recovery still works — a rolling update, node
drain, or any unrelated restart clears it. A client that addresses the pod directly (as every proof
does) also un-freezes it, which is exactly why the suite cannot see the defect: **the proofs'
pod-IP choice is correct** — through the Service they would lose their readings at the moment of
eviction — so this is a blind spot inherent in the only correct way to measure, and it needs a
*separate* instrument asserting on **endpoint membership**, not a "fixed" proof.

**§6's anti-storm observation is this same mechanism wearing its good face.** The GKE note that
liveness did *not* fire on an idle gateway that genuinely could not commit for ~10 minutes was
recorded as the anti-storm property confirmed. Same coin: no traffic → no streak progress. Whether
that is a virtue (idle, nothing to restart for) or this defect (evicted, restart impossible) depends
solely on *why* the traffic stopped — and readiness is what decides that.

**The fix constraint, for whoever designs it** (filed as a design question, not built): **liveness
must key on a signal the gateway generates itself**, because any counter fed by client submits is
starvable by the probe that gates client submits. Two tempting wrong answers, named so they stay
dead: *time-since-last-success* restarts idle gateways and reintroduces the §1b storm; *lowering the
liveness bar under the readiness bar* does the same. An active self-check (the gateway periodically
round-trips something it can commit) is the shape that survives both constraints.

This defect predates A, is independent of A, and is not a reason to hold A.

#### The cross-wired `orderRef` — PLAUSIBLE, UNESTABLISHED, and the trap in testing it

`completePipelinedHead` reads a NEW order's ref out of the **ack buffer**, not the pending request:

```java
final int ref = p.type == InputEvent.TYPE_ORDER_NEW ? buffer.getInt(offset + 8) : p.orderRef;
```

So under the offset a client would receive **another order's `orderRef` with an HTTP 200** — worse
than §1, which at least errs toward the client under-counting. **This is a code-read prediction. It
is not established, and an earlier write-up of the GKE arm wrongly claimed it as measured.**

The measurement taken: at K=13, 50 concurrent orders over refs 20955–21004 gave **37 × 200 carrying
refs 20968–21004 (contiguous), 13 × 504, and refs 20955–20967 reported to nobody.** That fits the
offset — and fits **equally well** an innocent reading in which the first 13 orders in offer order
simply went unanswered and the other 37 received their *own* correct refs, which are exactly
20968–21004. Same split, same block, same 13 missing. **An all-at-once burst destroys offer order,
and without offer order the aggregate has two causes.**

> **The lesson worth more than the finding.** The underspecified experiment produced the most
> alarming claim in this document, and produced it *because* it was underspecified. A test whose
> failure mode manufactures its own worst result is more dangerous than no test, because someone
> will report it and be believed. Before believing a number that matches a prediction, enumerate
> what *else* produces that exact number. Both arms of this investigation failed this on the same
> day — the kind arm measured against its own background load, the GKE arm read a specific
> correlation out of an aggregate that did not license it.

**Two methods that would settle it, neither yet run** (both rigs stood down):

1. **Preferred — two sends of one order, using the engine's own idempotency table as the oracle.**
   `BlpRiskState` holds a replicated, snapshot-persisted `clientOrderKey → orderRef` table and
   `MatchingEngine.onNewOrder` answers a repeat key by re-emitting the **original** order. Send one
   order during the wedge and record the ref the client is handed; clear the wedge; resend the same
   key; **mismatch = cross-wired**. Verified present on the layers that actually run — the **YU13**
   `MatchingEngine` (operative for YU13–YU15) and the **YU14** `BlpRiskState` (operative for
   YU14–YU15). Three constraints:
   - the table is **LRU-evicted** — resend before eviction;
   - a blank/absent `clientOrderId` hashes to **0**, the engine's "no key" sentinel, so a forgetful
     test is silently vacuous and reads as a clean pass;
   - **the original must still be RESTING.** The re-emit is guarded by `lookup(originalRef) != null`;
     if the original filled or was cancelled the guard drops through and the engine mints a
     **brand-new order with a new ref** — a different ref for an innocent reason, indistinguishable
     from the defect. **This is the constraint that fails toward confirming the severe claim.** Use a
     limit that cannot cross and confirm the order is still resting before the second send.
2. **Gateway-only rigs — stagger the burst ~60 ms** so offer order equals launch order while all
   orders still land inside the window. Launch index *i* then has true ref `R0+i-1`: under the offset
   client 1 is told `R0+K`; under the innocent reading client 1 is told nothing and client `K+1` is
   told `R0+K`, its own.

#### The defect is live on the TIP

`onDirectAck` is **byte-identical on each branch's operative `ClusterGatewayMain` layer** — the YU13
layer (2173 lines, run by YU13–YU15), the YU16 layer (2221), and the YU17 layer (2403). The
self-heal **resets K rather than preventing it**. Anything planned on YU17 that assumes this is clean
is wrong.

**What must NOT be built:** admission control, a bigger HTTP pool, or a shorter `ACK_TIMEOUT_MS`. The
backlog is not deep, it is **misaligned**; all three move the cliff and leave the misalignment. The
repair is *correlation* — an ack that names the request it answers — which is a contract change with
real blast radius and is yaakov's call, not something to slip in behind a diagnosis.

*The GKE arm's raw working record — full timelines, thread dumps and the retraction history — is
`issues/open/YU15-s5-gke-fifo-correlation-offset.md`, which exists on the `YU15-eod-risk-extract`
branch only. This section is self-contained and does not depend on it.*
