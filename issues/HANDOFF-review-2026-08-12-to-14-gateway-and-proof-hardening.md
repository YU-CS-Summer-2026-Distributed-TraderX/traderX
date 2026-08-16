# Review: the 2026-08-12 → 08-14 gateway and proof-hardening work

**Status: RECORD ONLY, written 2026-08-16.** Three chats worked YU15/YU16/YU17 over these three
days and are now closing; this is the durable version of what they found, so the knowledge does not
close with them. It opens nothing and fixes nothing. The open defects it names are tracked in their
own files — `HANDOFF-issue-gateway-wedges-after-leader-kill.md` (§1 wedge, §5 HTTP hang) and
`HANDOFF-issue-historical-gateway-images-fail-the-probe-port.md`.

**The question this answers.** The work was reviewed against yaakov's concern that it might be "a
bandaid on a bandaid on a bandaid". The short answer: **true of exactly one thing — the §5 HTTP
hang, which now carries three stacked mitigations and no diagnosis — and false of the rest.** The
larger and less obvious problem is not duplicated effort or symptom-patching; it is **propagation**.
Almost nothing was done twice. Fixes repeatedly landed on one layer of four, or one branch of five.

**Provenance is marked per claim** and means exactly this:

| mark | meaning |
|---|---|
| **[rig]** | measured on a live rig by the chat that did the work; the reading is quoted from its commit or issue doc |
| **[tree]** | re-derived from the checked-out tree on 2026-08-16 while writing this document |
| **[relayed]** | asserted by the coordinator's review and not independently re-checked here |

Scale of the window, **[tree]**: **70 unique commit subjects across 125 commits** on
`YU15-eod-risk-extract`, `YU16-cdm-instruments` and `YU17-otc-rates`, 2026-08-12 00:00 → 2026-08-15
00:00. The multiplier is the lineage rule, not rework — most subjects land once per carrying branch.

---

## 1. The wedge: a gateway that books what it denies

Kill the cluster leader while a single gateway serves live traffic. The cluster does everything
right — new leader elected, all three members in lockstep, applied sequence advancing — and every
subsequent order returns

```
HTTP 504 {"error":"no committed ack"}
```

The order was **not refused**. Ingress is intact; the cluster sequences and books it. Only the ack
path back to the gateway is gone.

Measured on kind 2026-08-13 with the cluster's own `next_order_ref` as witness **[rig]**:

| test | client saw | `next_order_ref` delta | resting orders created |
|---|---|---:|---:|
| 5 orders, 5 distinct `clientOrderId`s | **5 × 504 failed** | +5 | **5** |
| 3 orders, the SAME `clientOrderId` | **3 × 504 failed** | +3 | **1** |

**The failure mode is not lost orders, it is invisible orders.** The client's view and the book's
view diverge silently and permanently, in the direction of the client **under-counting its own
exposure**. For an OMS that is worse than an outright refusal, which is at least honest. The ClOrdId
ledger still protects a retrying client from duplication — three sends of one key produced one
resting order — but each attempt still burns a ref.

**It is a production defect, not a local-rig artifact.** Found on GKE first — YU15 `:bench`
2026-08-12, YU16 `:yu16` 2026-08-13 — and only then reproduced on kind **[rig]**.

**Two conditions hide it.** Sustained load during the kill, and a **single gateway**. With several
gateways the survivors keep serving and the wedged replica's share of traffic reads as an elevated
error rate rather than an outage — very likely why the four-gateway ceiling campaign never surfaced
it. That makes the single-gateway rig the *useful* configuration for this bug, not a degraded one.

### Ruled out by measurement, not by argument **[rig]**

| hypothesis | what killed it |
|---|---|
| the cluster | all three members agreed on `applied` throughout; `yu12-gke-recovery` and `yu12-gke-cross-epoch-idreuse` both PASS minutes either side, and both kill leaders themselves |
| the YU16 build | first seen on YU15 `:bench`, reproduced on YU16 `:yu16`; nothing in the bond work touches session handling |
| the load balancer | the 504 body is the gateway's own (`ClusterGatewayMain.handleOrder`), so the request reached it and it answered |
| slow recovery | persisted ~8 minutes across repeated requests, no self-heal |
| a closed or errored session | **non-OK session event count = 0** across a reproduced wedge, on the build that logs them |
| quorum loss | the session stays open through it and the gateway recovers unaided once quorum returns |

### The cause is still not diagnosed

The live hypothesis is **the egress subscription after a leader change**. Aeron's `onNewLeader`
fires and recreates the *ingress* publication — orders demonstrably still commit — so the asymmetry
is on the **egress** side, which is exactly what §1 of the issue describes.

**The `EgressListener` method-reference bug explains the silence, not the wedge.**
`AeronCluster.Context.egressListener(this::onEgress)` satisfied the interface's single abstract
method and left `onSessionEvent` and `onNewLeader` on their default no-op bodies (verified against
`aeron-cluster-1.51.0` with `javap`) **[rig]**. That accounts for "no exception, no reconnect
attempt, no log line at any level". It does not account for the wedge: with the events now visible,
none arrive.

**What ships today is a bounded mitigation, and it works.** `2d36fa7b` triggers a session rebuild on
a streak of orders whose offer **cleared into the log** and whose ack never came
(`offeredUnackedStreak`, default 20). The trigger choice is the safety argument, and it is measured
rather than assumed **[rig]**:

| regime | `offer_attempt` | `offer_success` |
|---|---|---|
| healthy cluster | +10 | **+10** |
| quorum loss | +1 | **+0** |

During quorum loss the offer never clears, so the streak physically cannot advance and the self-heal
cannot fire — which matters, because firing there would park the owner thread inside
`connectCycling()`'s `while (running)` loop and turn a recoverable outage permanent. Verified end to
end on kind, `traderx/cluster-node:yu17heal`: wedge reproduced, 13 orders denied while the streak
climbed, then `GATEWAY-WEDGE-SUSPECTED` and `#14 COMMITTED` — **with no pod restart**, one container
lifetime continuous through the kill, the wedge and the cure **[rig]**.

The residual is inherent to a streak trigger and is stated in the issue: the first ~20 clients after
a wedge are still told their orders failed while the book takes them.

---

## 2. The three-layer stack — where the "bandaid on a bandaid" reading is correct

Over the **§5 HTTP hang** there are now three stacked mitigations and no diagnosis.

**The defect** **[rig]**: drive a wedged gateway at roughly 20 orders/sec and it stops answering
**any** HTTP request — `/ready` and `/health` included. Not 503; no response at all, connection
accepted and never served. **It does not recover**: eight minutes with **zero load offered**,
polling every 30 seconds, still `000` every time. The JVM was alive (PID 1, 23 minutes uptime), the
Aeron side was alive and still applying, TCP kept accepting. Only a restart cleared it.

| layer | what it does | what it does not do |
|---|---|---|
| HTTP pool 8 → 64 | buys headroom so the probe is not starved behind parked order threads | does not bound anything; moves the cliff |
| probe port split (18111, own `HttpServer`, own single-thread executor) | makes the probe answerable *while* the order path is saturated — asserted directly, with 80 concurrent orders parked against a 64 pool, probe answered 200/503 throughout **[rig]** | says nothing about why the order path never drains |
| liveness restart on the streak | converts an unbounded outage into a ~60s one via the kubelet | is a restart, not a fix |

**None of the three explains why a bounded 12-second wait per request never drains in eight
minutes.** That is the open question, and it is the one worth answering. One hypothesis is already
dead: the drain experiment (2026-08-13, `LIVE_NO_ACK_STREAK=100000` so no restart could intervene)
showed 160 abandoned tasks evaporating the instant quorum returned — first committed order at +0s,
`restarts=0`, streak already 0 **[rig]**. So whatever §5 is, it is **not merely a deep owner queue**.

### The proposed real fix: refuse at admission

Return **503 at accept time when the pool is saturated**, so the backlog cannot grow past what the
consumer can retire. A refused order is honest and retryable; a silently queued one that outlives
its own timeout is neither.

**Provenance: proposed, not implemented, and not recorded anywhere else in the tree** — grepping
`issues/` and `specs/` for it returns nothing **[tree]**.

### This is the same pattern this project keeps paying for

`feedback_bound_the_consumer` records **three instances in one night, 2026-07-28** — the kdb capture
tap's uncapped writer, the OTel `SpanSink` exporter thread, and both caps then repeating the mistake
one level down. Its rule: *bounding a queue is not bounding its consumer; ask what a best-effort
side channel costs when the bad state lasts **forever**, including on the failure path.*

**Correction to the relayed review, which called §5 the fourth instance.** By count it is at least
the fourth and by the tree it is the fifth: `cf40084d` (2026-08-13) is a same-week, same-family case
— risk-extract's `[SUB-90012]` durable-bind retry was `while (true)` with no deadline, so a stuck
durable left the pod Running and Ready with no producer and the EOD extract silently never running
**[tree]**. It was found and bounded inside this very window. The ordinal matters less than the
frequency, which is the actual finding: **five instances of one pattern in nineteen days.**

---

## 3. What is NOT a bandaid

The review would be unfair without this section. Four pieces of genuine root-cause work landed in
the same window.

| change | why it is root-cause |
|---|---|
| `EgressListener` registration (`e76603fd`) | a named defect in the code — a method reference silently accepting two default no-op bodies — verified with `javap` against the actual jar, not from memory **[rig]** |
| probe-port split (`142faa54`, `86552494`, `a2781db7`) | architecturally correct independent of the wedge: it separates the **control plane** from the **data plane**. A probe served by the pool it is meant to report on cannot report on that pool |
| the `--context` fixes (`db758d01`, and the YU15 carry-back) | removes a whole class of proof lying about which of two rigs it ran against |
| STP control-feed race (`52dcf264`, `42ec8fac`) | see below — the model case |

### The STP story, in full, because it is the model

This is what root-causing looks like when the first fix appears to work.

1. `4ae2f533` restored `initialDelaySeconds: 5` to the historical probe form, which YU15's own
   gateway manifest carries and the proofs' installed form had dropped. The seed failures stopped.
   Measured and real: `yu13-stp-and-replace` had failed 5/5 seed attempts whenever
   `yu13-cancel-ingress` ran before it, and passed standalone **[rig]**. **It looked like the fix.**
2. The same chat then noticed the failure was still intermittent, and wrote the note titled
   *"correcting my own note"* rather than letting the earlier claim stand (`a6dac41f`).
3. `9af4ca1f` instrumented the failure **at the failure point** instead of reasoning about it —
   capturing the members' `applied` count at the moment the seed returned 422.
4. That reading root-caused it (`52dcf264`): **`kubectl set env` starts a rollout it does not
   finish.** Nothing waited for it, so the old gateway pod — still `CONTROL_FEED_SUBSCRIBER=1` — was
   alive while `rebuild_fresh_epoch` wiped the PVCs and brought the members back, and it replayed the
   YU04 control feed's **510-security universe** straight into the brand-new epoch. On the historical
   builds this prep exists to serve, `MAX_SECURITIES` is **64** against 1024 today, so the symbol
   table is exhausted in the first 13% of the replay and every registration after that is refused
   with `id = -1`. Measured at the failure: `applied=655` on all three members against ~130 for
   `seed-proof-fixtures` alone — the ~510 excess is the universe **[rig]**.

**`initialDelaySeconds` had changed the flake's frequency without touching its cause.** It is
intermittent precisely because it is a race on whether the old pod is still up when the members
return, and a delay moves the race without deciding it. The fix is one `rollout status` before
minting the epoch.

Two lessons the chat wrote down and that generalise past this proof:

- **"When a standalone reproduction disagrees with an in-suite failure, enumerate what the suite has
  that the reproduction does not."** Capacity was ruled out on a standalone repro that structurally
  could not contain the control feed.
- **A control that silently controls for the variable under test proves nothing** (`1612fe50`). The
  isolation experiment that appeared to exonerate the gateway build could not reproduce the failure
  because its own helper slept ~7s between the rollout and the seed — accidentally supplying the
  very delay the patch had removed.

---

## 4. The systemic finding: propagation, not duplication

Almost no work was done twice. What repeatedly happened is a fix landing on **one layer of four** or
**one branch of five**.

**The structural cause is measurable.** `ClusterGatewayMain.java` has **four carriers** — the YU12,
YU13, YU16 and YU17 spec layers — so every gateway fix is a four-layer edit where the last carrier
present on a branch wins **[tree]**:

| branch | operative `ClusterGatewayMain` layer |
|---|---|
| YU12 | YU12 |
| YU13, YU14, YU15 | **YU13** |
| YU16 | YU16 |
| YU17 | YU17 |

Measured 2026-08-16 by grepping a marker in each branch's **operative** layer, not its newest one
**[tree]**:

| | probe 18111 (`GATEWAY_PROBE_PORT`) | `EgressListener` (`onSessionEvent`) | wedge self-heal (`GATEWAY-WEDGE-SUSPECTED`) | `scripts/proofs/` `--context` |
|---|---|---|---|---|
| YU12 | ✓ | **✗** | **✗** | no proofs dir |
| YU13 | ✓ | ✓ *(2026-08-16)* | ✓ *(2026-08-16)* | no proofs dir |
| YU14 | ✓ | ✓ *(2026-08-16)* | ✓ *(2026-08-16)* | no proofs dir |
| YU15 | ✓ | ✓ *(2026-08-16)* | ✓ *(2026-08-16)* | ✓ *(2026-08-16)* |
| YU16 | ✓ | ✓ | ✓ | ✓ |
| YU17 | ✓ | ✓ | ✓ | ✓ |

The YU13/YU14/YU15 row changed on 2026-08-16: one edit to the **YU13 layer**, which all three run,
carried both mechanisms and was verified before-and-after on the kind rig. **YU12 alone still books
what it denies** — its `offerAndAwait` collapses *offer-cleared* and *ack-arrived* into one boolean,
so the signal the self-heal's safety argument depends on does not exist there; fixing it is a
contract change across four call sites, scoped in the wedge issue rather than deferred.

**A method warning that came out of that carry**, because it is the same family as everything else in
this section: the YU12 layer was first measured at 610 lines with no `submitPipelined` — in a
*descendant* worktree, where that layer is shadowed and never runs. YU12's own branch carries 741.
The YU13 layer shows it too: 2173 lines on YU13/YU14/YU15 against **1941** on YU16/YU17. Measure a
layer in a worktree where it is **operative**, or you are reading a copy that never executes.

> **Correction to the relayed review.** It recorded `EgressListener` as ✓ on YU12–YU15. It is **✗**
> on all four. `e76603fd`'s own commit body says so — "YU12's and YU13's layers are deliberately not
> carried: no rig here for those tiers, and YU12 is a different program (no `submitPipelined`)" —
> and the marker grep against each operative layer confirms it **[tree]**. The omission is
> deliberate and defensible; recording it as done was not.

**State this plainly, because it is the sentence that matters to anyone deploying:**

> ~~**"The wedge self-heals" is true of YU16 and YU17 and false of the four branches below them.**~~
> **Closed for three of the four on 2026-08-16.** It is now true of YU13, YU14, YU15, YU16 and YU17.
> **On YU12 the gateway still books what it denies**, and the only cure there is a `rollout restart`.

The probe-port work is the counter-example that shows propagation *can* be done: it was written once
and carried to YU12–YU15 and the GKE tier, and — the part that is usually skipped — the carry was
then **exercised** rather than assumed. YU12's funnel is a different program (no `submitPipelined`;
the streak hooks into the synchronous `submitOrder` instead) and had never been run anywhere; it was
run on kind on YU12's own image, and both YU16 proofs passed **[rig]**.

---

## 5. Two corrections worth preserving

### (a) The `blp-c3-pool` node-pool pin is dead, and shadowed

The pin names a GKE node pool that a compact-placement experiment deleted; a fresh `apply -k` against
it leaves members Pending on *"didn't match Pod's node affinity/selector"*, which reads like a quota
problem.

It survives only in the **YU12 layer**, in two files — `gke/statefulset-emptydir.yaml` and
`gke/gateway.yaml`. YU13, YU14, YU15 and YU16 each carry **their own copies of both**, so the pin is
**shadowed and inert on every branch above YU12**. YU17 has no copy of either and inherits YU16's,
which is also clean. It bites only if YU12 itself is generated **[tree]**.

### (b) An `issues/` document was withdrawn by its own author — and the trap that caused it

`issues/HANDOFF-issue-suite-verdicts-under-load.md` was added in `f72f2f8f` and removed in
`3b36225f`, both 2026-08-13 **[tree]**. It was written off a run whose six failures turned out to be
self-inflicted:

> **`run-proofs.sh` was edited while bash was executing it.** Bash reads a script by byte offset, so
> the running invocation was half-old and half-new. All six failures pass in the clean run.

Worth one line in anyone's head: **never edit a shell script that is currently running.** The
withdrawal is also the right precedent — the author replaced the document and recorded what happened
in `implementation-status.md` rather than quietly dropping it.

### (c) A smaller one, since this document is the record

The §5 write-up attributes the HTTP-pool comment to `gateway.yaml`. It is in
**`ClusterGatewayMain.java`**, at the `newFixedThreadPool(64)` call, on all four carriers, and reads:

> *"64: under pipelined-batch load every in-flight batch parks one HTTP thread on its owner-queue
> future (up to ~12s each); with only 8 threads the readiness probe starved behind them and k8s
> pulled the gateway out of the Service mid-bench."*

The judgement *"64 only moves the cliff; it does not remove it"* is the issue document's own, not the
comment's **[tree]**. The substance is unaffected; the pointer was wrong, and a wrong pointer in a
660-line issue costs the next reader a grep. Note also that the same `64` is the listen backlog —
`HttpServer.create(addr, 64)` — which is a second place the admission-refusal fix in §2 would land.

---

## 6. Suite numbers, with their provenance

| reading | when | where | source |
|---|---|---|---|
| **23 passed / 0 skipped / 3 failed** | 2026-08-13 | YU17 build, kind | `3b36225f` **[rig]** |
| **24 passed / 3 failed** | 2026-08-14 | YU17 phase 2, kind | `2da56235` **[rig]** |
| **27 passed / 0 skipped / 0 failed** | 2026-08-14 19:22 | YU17, kind | `42ec8fac` **[rig]** |

**Do not write 27/0/0 as a settled number.** It is **a single run, on YU17, on kind**, taken
immediately after the STP root-cause fix on the branch where that proof had been failing about one
run in three. It has not been repeated.

Two readings that keep the headline honest and belong beside it:

- The three failures behind 23/0/3 were all diagnosed and **none is a property of YU17**: one was a
  drifted `OTEL_SAMPLE_MASK` on the rig (127 against the manifest's 0), and two were the probe-port
  blocker — a pre-YU16 gateway image serves probes on 18110 only while the manifest's startup probe
  points at 18111, so the kubelet crash-loops it forever **[rig]**.
- A **YU15** suite on a genuine YU15 build read **18 passed / 3 failed** (`54afcd21`) **[rig]**. One
  of those three was `yu10-fix-session` reporting "FIX port refused" — the failure shape whose
  *reporting* the 2026-08-16 carry-back addresses, by refusing on a dead tunnel instead of printing a
  precondition failure as a verdict about FIX ingress.

---

## Open items

1. **§5's HTTP hang — undiagnosed.** Why a bounded 12s wait per request never drains in eight
   minutes with zero load offered. Three mitigations, no cause. Proposed fix: refuse at admission.
2. **The wedge's root cause — undiagnosed.** Live hypothesis: the egress subscription after a leader
   change. Still true: everything shipped for it is a *mitigation*. Now mitigated on YU13–YU17 by
   the streak-triggered session rebuild; **not mitigated on YU12**.
3. ~~**The `EgressListener` and self-heal gap on YU12–YU15**~~ — **NARROWED TO YU12 on 2026-08-16.**
   One edit to the YU13 layer carried both mechanisms to YU13, YU14 and YU15, verified before and
   after on the kind rig (before: 5 orders → 5×504 with `next_order_ref` +5 and **zero** log lines;
   after: `GATEWAY-WEDGE-SUSPECTED` at streak 20, orders committing, `restarts=0`) **[rig]**. YU12
   remains open for a specific, now-understood reason — `offerAndAwait` discards the offer-cleared
   fact the safety argument needs — and wants scoping as a four-call-site contract change.
4. ~~**`yu13-otel-trace-join.sh` defaults `KCTX` to empty**~~ — **CLOSED 2026-08-16.** It omitted
   `--context` entirely when neither `KCTX` nor `CTX` was set, falling through to the ambient
   current-context. Fixed by making it **refuse** rather than inherit — deliberately not by
   defaulting it to kind, which would only point the same bug the other way and be silently wrong
   against GKE. Verified on the live kind rig, both arms: unset → exit 1 naming the ambient context
   it declined to use, `CTX` set → the proof runs to `[PASS]`, exit 0 **[rig]**. The two
   operator-facing hint strings that omitted `--context` went with it.
5. **`yu12-gke-failover-transparency.sh` still reports the wrong cause** (issue §4). Its retry loop
   cannot distinguish a dead connection from a cluster refusal, and its "55 DUPLICATED" reads
   `next_order_ref` deltas as bookings when a ref is consumed by orders that never rest. It should
   assert against a booking-grained quantity — open-order count, or the read model.
