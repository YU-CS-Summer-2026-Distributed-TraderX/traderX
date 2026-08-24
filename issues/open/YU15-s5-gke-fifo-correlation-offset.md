# §5 diagnosed on GKE: the gateway's ack correlation is POSITIONAL, and a leader kill puts it permanently out of step

**Status: DIAGNOSED, not fixed. 2026-08-16.** This is the GKE arm of §5 of
`HANDOFF-issue-gateway-wedges-after-leader-kill.md`. It answers that section's standing open
question — *"why a bounded 12s wait per request never clears in eight minutes"* — and it retires
the permit-leak hypothesis by measurement rather than by argument.

**No fix is proposed here on purpose.** Three mitigations already sit on this failure (HTTP pool
8→64, the probe-port split, the liveness restart) and none of them diagnosed it. Admission control
in particular **must not ship**: the backlog is not deep, it is *misaligned*, and throttling a
misaligned queue leaves it misaligned.

## The build every reading below came from

| | |
|---|---|
| gateway | `us-east1-docker.pkg.dev/traderx-505400/traderx/cluster-node:yu15prewedge-amd64` @ `sha256:f28b8cfa5278…` |
| built from | `YU15-eod-risk-extract@9caab079^` — the commit **before** the self-heal carry, i.e. no `EgressListener`, no `GATEWAY-WEDGE-SUSPECTED`. 2070-line YU13 layer |
| members / producers | unchanged on `:yu16` |
| rig | GKE `traderx-bench`, `us-east1-b`, project `traderx-505400`, gateway `replicas: 1` |

Build identity confirmed **on the rig, not from the Dockerfile**: across the whole run the gateway
logged `CLUSTER-SESSION-EVENT` / `CLUSTER-NEW-LEADER` / `GATEWAY-WEDGE-SUSPECTED` exactly **0**
times, which is the pre-heal build's signature.

**The mixed-version window was checked both ways before anything was trusted.** The three wire
files' *operative* layers are byte-identical between the YU15 and YU16 branches — `InputEvent`
(YU03, `cce6092d…`), `OutputEvent` (YU13, `46fb0679…`), `AeronReplicationCodec` (YU15,
`fb1bfe7d…`) — and then confirmed empirically on a verified-quiet rig: `{"orderRef":2,"kind":1}`.

## The mechanism, in one line of code

```java
PendingOrder onDirectAck(final long inputSeq) {
    if (inputSeq == lastInputSeq) return null;
    lastInputSeq = inputSeq;
    return fifo.pollFirst();          // ← pops the HEAD. Unconditionally.
}
```

**An ack does not name the order it completes.** Correlation is purely positional: the *n*-th
direct ack completes the *n*-th offered order. The invariant that makes this sound is "one direct
ack per cleared offer, in offer order" — and **a leader change breaks it**, because the offers the
dying leader had already sequenced never produce egress to this session.

Those orders stay in the FIFO forever. The FIFO is thereafter permanently **K** entries ahead, and
because every later ack pops one head while every later order pushes one tail, **K is exactly
conserved**. Nothing accumulates, and nothing drains.

The author reasoned about this exact failure for the *continuation-fill* case — the comment above
the method says popping again "would shift every later order onto the wrong request" — and guarded
that one. The stranded-offer case is unguarded.

## K is a ratchet: measured

Sustained ~20 orders/s, single gateway, leader killed repeatedly. `depth` is
`traderx_gateway_inflight_orders`; `gap` is `offer_success − ack_completed`.

| leader kill | K before | K after |
|---|---:|---:|
| 1 | 0 | **21** |
| 2 | 21 | **36** |
| 3 | 36 | **51** |
| 4 | 51 | 51 (stranded nothing) |
| 5 | 51 | 51 (stranded nothing) |

So a kill strands roughly the orders in flight at the instant egress is lost, and it is
probabilistic — 3 of 5 kills stranded, which is the "~1 run in 4" folklore seen from the other side.
**K never went down.** It survived full member catch-up, a new leader, and thousands of subsequent
healthy orders.

### K is an offset, not a queue — the cleanest single reading

Raising the offered rate from ~20/s to **~95/s left `depth` at exactly 51.** A queue grows with
offered load. An offset cannot.

## The acks are not absent and not late. They arrive, and they answer the wrong request

Rig verified quiet first (member refs stationary), zero load offered, held 30 s — every field
frozen: `depth=51 off_ok=3744 ack=3693 gap=51 refs=3695/3695/3695`, `/ready` 503 with
`connected:true`. Then **one** order, distinct `clientOrderId`:

| | before | after |
|---|---|---|
| client | — | `504 no committed ack` at **12.000 s** |
| member `next_order_ref` | 3695 | **3696** (+1) |
| `offer_success` | 3744 | **3745** (+1) |
| `ack_completed` | 3693 | **3694** (+1) ← **an ack arrived and was consumed** |
| `gap` / `depth` | 51 / 51 | **51 / 51** |

Repeated twice more, identical. One request in, one ref consumed, one ack consumed, and the client
told it failed. **The egress path is intact.** This also reconciles the kind arm's result: its
"permits released order by order, so the acks must be late" is the same phenomenon at K=2–3, where
one pop per push likewise pins depth at a small constant.

## What the client sees, by regime — and the cliff

A client's answer needs **K more acks** to pop through, so its latency is ≈ `K / offered-rate`, and
the number of HTTP threads occupied at equilibrium is ≈ **K**. Three regimes follow, all measured:

| regime | what the client gets |
|---|---|
| rate < K/12 | **every request 504s.** At rest, all 504 |
| rate > K/12 | **200 again** — inside the deadline, carrying a *different order's* `orderRef`. At ~20/s `noAckStreak` fell back to 0 while `gap` stayed pinned at 51 |
| **K ≥ HTTP pool size (64)** | **§5. Total HTTP collapse** |

That last line is what `gateway.yaml`'s own comment — *"64 only moves the cliff; it does not remove
it"* — has always been describing without knowing it. **The cliff is at K = pool size.** Raising
the pool raises the K at which collapse begins and changes nothing else.

## §5's positive arm, reproduced twice

Drive ~95–300 orders/s, kill the leader, push K past 64:

```
22:25:44  mrc=28  depth=NA  ready=503 {"connected":true,"noAckStreak":64}   refs=10360/x/10360
22:25:54  mrc=28  depth=NA  ready=503 {"connected":true,"noAckStreak":128}  refs=10424/x/10424
22:26:05  mrc=28  depth=NA  ready=503 {"connected":true,"noAckStreak":192}  refs=10488/10488/10488
```

`mrc=28` is `/metrics` on **18110 timing out** — §5's "no response at all". Meanwhile `/ready` on
**18111 answered 503 throughout** (the probe-port split earning its keep), the members kept
committing, `restarts=0`, and `noAckStreak` climbed in **exact steps of 64** — the pool retiring one
full batch at a time, which is pool saturation measured directly rather than inferred.

### `kill -3` during the hang settles the hypothesis

No `jcmd`/`jstack` in `eclipse-temurin:21-jre`, but SIGQUIT to PID 1 dumps every thread to stdout
where `kubectl logs` picks it up. All **64** order-pool threads, one identical stack:

```
"pool-1-thread-N"  TIMED_WAITING (parking)
    java.util.concurrent.CompletableFuture.timedGet
    java.util.concurrent.CompletableFuture.get
    ClusterGatewayMain.submitPipelined0(ClusterGatewayMain.java:855)
    ClusterGatewayMain.submitPipelined(814) → submitOrder(728) → handleOrder(1100)
```

Threads anywhere in `Inflight.acquire` / `Semaphore.tryAcquire`: **0**. By the hypothesis's own
discriminator table, that is **permit leak REFUTED**, read in the exact state the hypothesis was
written for. Two further frames from the same dump matter as much:

- **`cluster-client-owner` is RUNNABLE inside `ownerLoop`, not blocked** → the owner queue is not
  backed up either. This is not a deep owner queue.
- **`HTTP-Dispatcher` is RUNNABLE in `EPoll.wait`** → still accepting. Connections are accepted,
  handed to the fixed pool's *unbounded* queue, and never served. That is §5's "connection accepted
  and never served", mechanically.

## The answer to §5's open question: the backlog always drained, at 5 requests per second

With `LIVE_NO_ACK_STREAK=100000` so the kubelet could not intervene, all load stopped, member refs
frozen (verified), `restarts=0`:

| time | `noAckStreak` |
|---|---|
| 22:35:52 | 448 |
| 22:36:04 | 512 (+64 in 12 s) |
| 22:36:17 | 576 (+64 in 13 s) |
| 22:36:31 | 640 (+64 in 13 s) |

**+64 every ~12.5 s** — one pool batch per `ACK_TIMEOUT_MS + 2s`. 18110 recovered at **+69 s**,
`restarts=0`, a genuine drain.

So the measured service rate under the wedge is **64 / 12.5 ≈ 5.1 requests per second**, against
5.33 predicted from first principles. Eight minutes of that is ~2,450 requests — and an unbounded
~20/s generator queues more than that in under three minutes. **§5's "it never drains" was a finite
drain nobody had measured the rate of.** The queue was never stuck; it was moving at five
requests a second while the condition that made every request cost 12 s never lifted.

## The negative control: quorum loss, same image

Required precisely so this cannot round toward the hypothesis.

| phase | reading |
|---|---|
| quorum lost (members 3→1), under load | `connected:false`, `offer_success` frozen, **`depth` climbed 256 → 288** |
| quorum restored (3) | **`depth` back to 0**, `/ready` 200, orders commit normally |

Permits climbed and **returned**. They did not pin. So the accumulation seen under quorum loss is
the ordinary owner-queue backlog and is self-clearing — the mechanism is not an unconditional leak.

**And the control explains the cure.** `connectCycling()` calls `inflight.drain()`, which empties
the FIFO and resets `lastInputSeq = -1` — i.e. it sets **K back to 0**. That is why a `rollout
restart` cures the wedge instantly, why the YU13/YU16/YU17 self-heal cures it, and why quorum loss —
which forces a reconnect — leaves no standing offset while a leader kill, which does not reconnect,
leaves one forever. **The whole difference between a recoverable outage and the permanent wedge is
whether the gateway reconnects and therefore drains.**

### Instrument caveat, learned the hard way

`drain()` releases permits and completes futures but **never increments `ack_completed`**, so after
any drain `gap` over-reports by the number drained. In the negative control `gap` sat at 64 with
`depth` at 0. `depth` is the live offset; `gap` is only equal to it when no drain has occurred —
and in the wedge runs the two agreed exactly (51/51), which is itself the evidence that no reconnect
happened.

## The liveness probe does cure §5 on GKE — measured, and it is not a diagnosis

On the first hang (default `LIVE_NO_ACK_STREAK=100`), `noAckStreak` reached 192, `/live` failed, and
the kubelet restarted the container: `restartCount 0 → 1`, event `Container gateway failed liveness
probe, will be restarted`, 18110 serving again **26 s** later. §1b's mitigation converts an
unbounded outage into a ~26 s one on the tier where it matters. It neither prevents the wedge nor
explains it, and the offset rebuilds on the next leader kill.

## CONFIRMED 2026-08-16 on kind: a 200 carrying another order's `orderRef`

**The claim retracted below is now measured.** Gateway `traderx/cluster-node:yu15prewedge`
(pre-self-heal, `restarts=0`), members `:yu16`, kind rig `kind-traderx-yu12-cluster`, rig verified
quiet before the reading (`next_order_ref` stationary at 1445 across three samples, `depth` stable).

Induced **K = 20**, with `depth` and `gap` agreeing exactly — the evidence that no reconnect drained
anything. Then **50 orders staggered 60 ms apart so offer order equals launch order**, distinct
`clientOrderId`s. Launch index *i* therefore has true ref `R0+i-1` = 1445…1494. **Both branches were
fixed in the script before the run:**

| | OFFSET predicts | INNOCENT predicts | **measured** |
|---|---|---|---|
| which clients get `504` | **last** 20 (i 31–50) | first 20 (i 1–20) | **i 31–50** |
| 200s carrying the ref of the order K later | 30 | 0 | **30** |
| 200s carrying their own ref | 0 | 30 | **0** |
| unexplained | — | — | **0** |

**Launch index 1, whose true ref is 1445, was told `{"orderRef":1465}`** — the ref of the order 20
positions later. All 30 successes were off by exactly K; not one carried its own ref.

**The offer-order assumption is closed, not assumed** — that was precisely the loophole that made
the earlier all-at-once burst worthless. The returned refs are strictly **+1 monotonic** with launch
index across all 30 successes, and `next_order_ref` ended at **1495 = R0+50** on all three members,
so exactly 50 refs were consumed. Shuffled offer order cannot produce refs monotonic in launch index.

**Both harms land in a single burst:**

- **refs 1445–1464 were assigned, booked, and reported to nobody** — §1's invisible orders, exactly K
  of them;
- **refs 1465–1494 were each reported to the client of a different order** — HTTP 200, no error, no
  timeout. A client cancelling "its" 1465 cancels the order that really is 1465, which is someone
  else's.

This is worse than §1 in the direction that matters: §1's client under-counts its own exposure,
which is unsafe but conservative. This client records an exposure **belonging to somebody else**.

### Rig collision during this run — what survives it, and what is withdrawn

**Another session was running `yu12-gke-failover-transparency.sh` on the same rig, unannounced, and
I rolled the gateway underneath it.** Two valid grants of one rig. The contamination is real and is
recorded here rather than left for someone to find in two transcripts.

| time | event |
|---|---|
| **20:17:18** | the other session kills member 0 (its proof, 865-order stream) |
| 20:26:16 | its proof writes its final line and exits |
| 20:24:44 | I capture the gateway spec |
| ~20:25–20:26 | I roll to `:yu15prewedge` — **this is where I broke its baseline** |
| ~20:26–20:28 | my K induction (my own kill of member 1) — **overlaps its tail by ~10 s** |
| **20:28:41** | R0=1445, K=20 locked in, after a 3-sample quiet check |
| **20:29:07** | burst complete |

**The discriminator is clean, excluded three independent ways — any one suffices:**

1. the 3-sample pre-burst quiet check held `ref=1445` stationary and `depth=20` stable across ~10 s,
   which an 865-order stream cannot pass through;
2. `next_order_ref` moved 1445 → 1495, **exactly 50 for 50 orders** — foreign traffic would have
   consumed extra refs;
3. returned refs are **contiguous 1465–1494 and strictly +1 monotonic** — interleaved foreign orders
   would punch holes in that contiguity.

Plus the burst confirms its own parameter: 30 successes = N−K ⇒ K=20, matching the `depth` read
before it.

**What is withdrawn from this run.** **K's parentage is unknown** — the induction window overlapped
the other session's tail, so the 20 stranded entries may mix its election with mine. That does not
touch the verdict, which needs a K *known and measured at burst time*, not a K of known parentage.
But it does mean **no ratchet claim may be drawn from this run**: not the per-election increment, not
the 3-of-5 strand rate. Those need clean provenance and this window cannot supply it. The GKE ratchet
figures (21 → 36 → 51) are unaffected — different rig, different day, uncontaminated.

**An opportunistic reading was available and declined**: the other session's election was itself a
K-inducing event, so a depth reading would have been a free 3-of-5 data point. Its load profile was
not mine, and a labelled-but-dirty number in this record invites exactly the aggregation error the
rest of this file was written to avoid.

*The retracted history below is kept deliberately — the reasoning error that produced a
non-discriminating experiment is more instructive than the eventual confirmation.*

## The retracted first attempt — why the all-at-once burst could NOT establish this

`completePipelinedHead` reads the ref for a NEW order out of the **ack buffer**, not out of the
pending request:

```java
final int ref = p.type == InputEvent.TYPE_ORDER_NEW ? buffer.getInt(offset + 8) : p.orderRef;
//                                                    ^^^^^^ the ACK's ref, not p's
```

So if the ack popping the head belongs to a different order, the client is handed **that** order's
engine reference with an HTTP 200 — a confident wrong answer rather than a timeout. Under the
offset model that is forced, and it would be materially worse than §1: §1's client under-counts its
own exposure, this one records an exposure belonging to somebody else, and a client that then
cancels or replaces "its" ref acts on a stranger's order.

**The measurement taken does not establish it, and an earlier version of this file wrongly said it
did.** Offset rebuilt small (**K = 13**), then 50 concurrent orders with unique `clientOrderId`s
into a quiet rig; the cluster's next ref was 20955, so the burst took refs 20955–21004. Result:
**37 × `200` carrying refs 20968–21004 (contiguous), 13 × `504`, and refs 20955–20967 reported to
nobody.**

That is consistent with the offset — and **equally consistent with an innocent reading**: that the
first 13 orders in offer order simply went unanswered and the other 37 received their *own* correct
refs, which are exactly 20968–21004. Both models predict the same 37/13 split, the same contiguous
block, and the same 13 missing refs. **An aggregate ref-gap cannot distinguish them.**

### The test that would settle it, which has not been run

**Preferred method: two sends of ONE order, using the engine's own idempotency table as the
oracle.** No burst, no stagger, no offer-order control, no read-model schema. Verified present on
the layers this build actually runs — `MatchingEngine.onNewOrder` in the **YU13** layer (operative
for YU13–YU15) and `BlpRiskState.existingOrderRef` in the **YU14** layer (operative for YU14–YU15):

```java
if (risk != null && e.clientOrderKey() != 0L) {
    int originalRef = risk.existingOrderRef(e.clientOrderKey());
    if (originalRef >= 0) {
        RestingOrder original = lookup(originalRef);
        if (original != null) {
            out.emitOrderUpdate(original, e.seq, 0, false, …);   // re-emits the ORIGINAL ref
            return;
        }
    }
}
```

1. During the wedge, send **one** order with a unique `clientOrderId`; record the ref the client is
   handed.
2. Clear the wedge (`rollout restart`), resend **the same key**; the engine re-emits the original
   and the now-correctly-correlated gateway returns the authoritative ref.
3. **Mismatch = cross-wired.** Match = the offset is real but benign for live clients.

The table lives inside the replicated state machine and is written to every snapshot, so the two
sends can straddle the cure — which is what makes one order enough.

**Three constraints, and the third is the one that can manufacture a false positive:**

- the table is **bounded and LRU-evicted** — resend before eviction;
- a blank or absent `clientOrderId` hashes to **0**, the engine's "no key" sentinel
  (`clientOrderKey` returns 0 only for null/empty, and maps a real hash of 0 to 1 so it can never
  collide with it). A test that forgets to set a real key measures nothing and looks like a clean
  pass;
- **the original must still be RESTING at resend time.** `lookup(originalRef)` returning null —
  because the original filled or was cancelled — drops through the guard and the engine creates a
  **brand-new order with a new ref**. The resend then returns a different ref for a completely
  innocent reason, which is indistinguishable from the defect. Use a limit that cannot cross (a
  far-off-market resting buy) and check `traderx_book_open_orders` still holds it.

**Alternative, for a gateway-only rig where the resend path is unavailable:** fire the burst
**staggered ~60 ms** rather than all at once, so offer order equals launch order and stays
recoverable while all orders still land inside the 12 s window. Launch index *i* then has true ref
`R0+i-1`: under the offset client 1 is told `R0+K`, under the innocent reading client 1 is told
nothing and client `K+1` is told `R0+K`, its own.

**Until one of those runs, treat this as a code-read prediction with suggestive but non-decisive
supporting data.** It is the most severe claim in this file and it deserves the strictest evidence,
not the loosest reading of a number that happened to match.

## What this does NOT establish

- **Why a given leader kill strands and another does not.** 3 of 5 stranded; the trigger inside
  Aeron's leader transition is not identified here.
- **Whether the synchronous YU12 tier has the same defect.** `offerAndAwait` is a different funnel
  and was not exercised.

## Why a fix is not proposed

The repair is *correlation* — an ack that names the request it answers, so a stranded offer cannot
shift every later client onto the wrong response — and that is a contract change with real blast
radius across the pipelined funnel. It is yaakov's design call, not something to slip in behind a
diagnosis. What can be said is what must **not** be built: admission control, a bigger pool, and a
shorter `ACK_TIMEOUT_MS` all move the cliff without touching the misalignment.
