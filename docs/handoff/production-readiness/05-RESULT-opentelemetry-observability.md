# 05 — RESULT: OpenTelemetry across the consensus boundary — traces, log correlation, error sampling

> Brief: [05-opentelemetry-observability.md](05-opentelemetry-observability.md). Board: [[00-INDEX]].
> Status: **DONE.** Traces, log correlation and error sampling are all proven live on kind, and the
> before/after cost benchmark ran on GKE — see [07-RESULT-gke-cost-benchmarks.md](07-RESULT-gke-cost-benchmarks.md).

## The headline

**One order produces one distributed trace spanning the gateway and the cluster member, and not one
byte about tracing enters the replicated log.**

```
order                (traderx-cluster-gateway)   root — the residence the client experiences
├── gateway.queue    (traderx-cluster-gateway)   submit → offer cleared into the log
└── cluster.consensus(traderx-cluster-gateway)   THE BLACK BOX: offer → committed ack
    ├── cluster.commit (traderx-cluster-member)  sequenced → apply start, leader clock
    └── cluster.apply  (traderx-cluster-member)  match + emit
```

The same derivation then buys two things it was not designed for, both for free and both without a
byte entering the log: **a log line joins its own trace by computing the id**, and **a rejected order
is force-sampled by both tiers independently**, so the orders a supporter needs are the ones head
sampling can no longer lose.

## The genuinely hard part, and the answer

The brief named it: propagate trace context across consensus without putting trace ids into
replicated state. The obvious move — a `traceparent` field in the sequenced message — is the one
thing we may not do. Bytes in the log are replicated state, so adding them is a schema change, a
member roll, and a standing determinism risk taken on behalf of a debugging feature. It is also a
correctness hazard: a resend carrying a fresh trace id would no longer be byte-identical to the
original, so replay would stop reproducing.

**So we derive instead of carry.** Every order already carries a client idempotency key through the
log — `InputEvent.priceTicks`, set by the gateway from the ClOrdID and read by the engine for
duplicate suppression. It is business data that is already replicated, already unique per order, and
already identical on every member and on replay. Both sides run the same pure function over it:

```
traceId       = splitmix64(key), splitmix64(key ^ TRACE_SALT)
sampled?      = (splitmix64(key ^ SAMPLE_SALT) & mask) == 0
clusterSpanId = splitmix64(key ^ CLUSTER_SALT)      ← the member's parent span
```

The member reaches the same trace id, the same parent span id and the same sampling verdict as the
gateway — with zero bytes added, zero schema change, and nothing new for the state machine to read.
Sampling is still decided at ingress in the sense that matters (it is a property of the order, fixed
before it is offered); it simply needs no carriage.

The member derives the key **before** the sequenced generator overwrites `orderRef`, so both sides
hash identical inputs. Delete the tracing code and every member emits byte-identical output.

## Never blocking the trade path

A producer — a REST/FIX submit thread, the gateway owner thread, or a member's apply thread — does
exactly one thing: copy 8 longs into a pre-allocated Agrona `ManyToOneRingBuffer` and return. No
lock, no allocation, no I/O, and **no backpressure path back to the caller**. A full ring drops the
span and increments a counter. Hex formatting, JSON, HTTP and collector outages all happen on one
daemon thread no order ever touches; a dead collector costs a counter, not a millisecond.

**Not the OTel SDK.** Its `BatchSpanProcessor` has the right shape, but the API above it allocates
per span (`SdkSpan`, `Attributes`, String ids) on a path under an allocation gate that runs under
Epsilon GC in the no-GC proofs. We emit OTLP/HTTP with a JSON body to the same `/v1/traces`
endpoint any SDK would — zero new dependencies, since Agrona and `java.net.http` are already here.

## What already existed (and what actually was missing)

The brief listed "distributed traces, a collector, and a platform" as missing. Two of the three were
already deployed: the Kubernetes runtime has shipped an **OTel Collector, Tempo, Loki, Prometheus,
Grafana and promtail since state 007**. What was genuinely missing:

1. **Nothing emitted to the collector.** Zero OTLP emitters existed anywhere in the tree.
2. **Prometheus never scraped the Aeron cluster tier at all** — so `traderx_cluster_next_order_ref`,
   the committed ground truth, existed on `/metrics` and reached no dashboard.

Both are now wired. Members are scraped **per pod** through the headless service, because role,
applied and next_order_ref are per-member facts and "which one is leader" is unanswerable if a
Service-level scrape round-robins the three.

**Metrics are scraped directly rather than routed through the collector, on purpose.** The collector
owns traces, where a dropped span costs nothing. Putting the ground-truth counter behind it would
mean a collector outage silently blanks the number an operator uses to decide whether the cluster is
committing. The brief's requirement — preserve `next_order_ref` semantics, never swap in a booked
counter — is met, with one less hop between truth and the dashboard.

## The support dashboard

`traderx-cluster-support` answers the questions a supporter actually asks: where is the order
(Tempo, searchable by `traderx.order_ref`), **why did it not go through** (the *Rejected orders*
panel, joined to Tempo in both directions), which member is leader, what is committed, and what is
dropping. It states plainly that span *durations* are exact and cross-host *offsets* are not, that a
rising drop count is the design working rather than a fault, and that a *slow* order is deliberately
not force-sampled while a rejected one always is.

## Proof

`scripts/proofs/yu15-otel-trace-join.sh`, cataloged in the proofs README. It is built to be able to
fail: it derives the expected trace id **and** the expected parent span id from the ClOrdID alone —
in Python, with no input from either server, reimplementing the gateway's FNV-1a key hash and
`OrderTrace`'s splitmix64 — then demands Tempo return exactly that trace, joined across both
services, with the member's spans parented to the gateway's predicted span id. A build that
smuggled a `traceparent` through the log would still show spans appearing; only this pins the claim
being made.

Run live on kind, 3/3 orders, reproducible across re-runs:

```
predicted parent 4c1a07875de57968  ==  observed 4c1a07875de57968
5 spans, 2 services, both member spans children of cluster.consensus
gateway sink 9 emitted / 9 exported / 0 dropped
member  sink 6 emitted / 6 exported / 0 dropped
member role=1 (leader), next_order_ref advanced by exactly the orders submitted
```

Originally proven on a **single-member** cluster: only the leader emits member spans, so a
three-member rig exercises no additional tracing code, and the machine was already carrying another
lane's live three-member kind cluster which that run deliberately did not disturb.

**Re-run 2026-07-29 on a live three-member kind cluster** with the log-correlation and error-sampling
follow-ups in, to confirm they did not disturb the original claim — 3/3 orders, each predicted parent
span id confirmed, both sinks clean:

```
[ok] otel-...-1: trace 6c3817ceed915cbbfcc7e17d4056a402 joined across
     ['traderx-cluster-gateway', 'traderx-cluster-member']; predicted parent c08f66c2699fe037 confirmed
[ok] otel-...-2: predicted parent 0b46621efd9a13b6 confirmed
[ok] otel-...-3: predicted parent 5dca6e12779b5e73 confirmed
[ok] gateway sink clean (0 dropped, 0 export failures) · member sink clean
[PASS] one order, one trace, across the consensus boundary
```

## Suites

Numbers below are the gradle `test` task, measured 2026-07-29 with the follow-ups in. +11 tests for
this round (4 in `OrderTraceTest`, 7 in the new `RejectLogCapTest`).

| Branch | Tests | Failures | Gates |
|---|---|---|---|
| YU15 | 336 | 0 | allocation gates + noGcTest green |
| YU14 | 320 | 0 | allocation gates + noGcTest green |
| YU13 | 306 | 0 | allocation gates + noGcTest green |

22 tests cover this deliverable. `OrderTraceTest` (11) pins the consensus-boundary agreement —
including that an order with no key is never sampled, that a reject is escalated by both tiers or
neither, that only rejections escalate, and that the id a log line prints is character-for-character
the id the exporter writes. `SpanSinkTest` (5) pins drop-don't-block under a saturated ring plus the
OTLP wire format. `RejectLogCapTest` (6) pins the reject line's per-second cap — including under
concurrent submit threads, and that no input to it returns "unbounded".

The apply path is byte-identical when tracing is off (`traces == null`), which is why the allocation
gates and the Epsilon-GC proofs are unaffected. Escalation does not change that: it adds one
`nanoTime` per order to each tier *while tracing is on*, and nothing at all when it is off.

One scope note worth stating so nobody looks for the wrong thing: the reject line and the trace
escalation live on the per-order ingress path (`submitPipelined`), which is what REST, FIX and the
binary fast path all route through. The `/batch` bench path holds the owner thread and does its own
ack accounting, so a load-generation flood produces neither. That is the right split — `/batch` is a
harness, not a client — but it means a bench is not the place to look for either signal.

## Configuration

| Env | kind | GKE | Meaning |
|---|---|---|---|
| `OTEL_TRACES` | `1` | `0` | Master switch. Unset/0 = a null reference and zero cost — also the "off" arm of the cost benchmark. |
| `OTEL_ENDPOINT` | `http://otel-collector:4318` | same | OTLP/HTTP. |
| `OTEL_SAMPLE_MASK` | `0` (all) | `127` (1 in 128) | **Must match between gateway and members** — both derive the verdict independently, so a mismatch yields member spans whose parent was never emitted. |
| `OTEL_RING_BYTES` / `OTEL_BATCH_SPANS` / `OTEL_FLUSH_MS` | 1 MiB / 512 / 1000 | same | Sink shape. |
| `OTEL_MIN_INTERVAL_MS` | `10` | same | Exporter duty-cycle cap — see below. |
| `REJECT_LOG_PER_SEC` | `20` (default) | same | Cap on the gateway's `ORDER-REJECT` log line, process-wide. Set in no manifest — the built-in default is the intended value everywhere, and it is an env var only so an operator can turn it down mid-incident without a rebuild. Independent of `OTEL_TRACES`: the line is emitted either way, so the cost A/B keeps comparing tracing and nothing else. Refusals are counted in `traderx_gateway_reject_logs_suppressed_total`. |

## The bug this shipped with, found by checking a sibling lane's finding against it

The kdb lane reported that its capture tap could fill the volume the Aeron Archive writes to.
Checking whether the span sink had the analogous defect showed that **it did, in a different
bounded resource.**

The sink bounded *memory* (a fixed pre-allocated ring) and bounded what the *trade path* pays (drop
on full, never block). Neither bounded what the exporter **thread** costs. With a permanently
non-empty ring — trace-everything at a rate the collector cannot absorb — `exportLoop` posted
back-to-back forever and burned a core. Members run on tainted, core-pinned nodes where CPU is the
scarce reserved resource, so an exporter spinning flat out competes with the Aeron agents it exists
to observe: **the analytical path degrading the authoritative one, which is exactly what this design
is supposed to make impossible.**

Not hypothetical. kind ships `OTEL_SAMPLE_MASK=0` (trace everything, correct at demo rates) and GKE
ships `127`. The cost benchmark still owed on GKE flips `OTEL_TRACES` to 1 — and "copy the kind
config so we get more data" is exactly how the mask travels with it. At 190k orders/s, mask 0 asks
for 570k spans/s.

Fixed (`4435d16b`, `c7f85ea6`, `09629695`): every batch is followed by at least
`OTEL_MIN_INTERVAL_MS`, capping the thread at ~100 wakeups/s and export at
`batchLimit * 1000 / interval` = 51.2k spans/s. Sized so a correct sample fits comfortably under
(~4.5k spans/s at 1-in-128 and 190k orders/s) and trace-everything comfortably exceeds it, so the
overflow valve engages and drops are counted at the ring exactly as before. **The exporter's cost is
now bounded by configuration rather than by how fast the collector answers.**

The generalisation worth keeping: *bounding the queue is not the same as bounding the consumer.*
Both lanes bounded the buffer and left the drain unbounded, in disk and in CPU respectively.

### …and the cap had the same defect one level down

The kdb lane took that generalisation back to its own freshly-shipped cap, found it there, and
reported it. Checking this one the same way found it here too (`0790c66b`, `1a4bcbe2`, `3a209ae0`).

The cap slept **after** `post()`. A throwing `post()` jumps straight to the catch, so the sleep was
skipped entirely. An unreachable collector — a routine state, a restarting pod, and precisely the
one this class advertises as *"costs a counter, not a millisecond"* — fails its connection fast, so
the loop became a tight read-build-fail-repeat spin on a core-pinned member node for as long as the
outage lasted. **The happy path was bounded and the failure path, the one that actually persists,
was not** — the same mistake as the original bug, one level in.

Pacing is now unconditional and outside the `try`, and backs off to the idle interval while failing
(~1 attempt/s per process during an outage instead of ~100). The decision is extracted as a pure
`pauseMillis(hadWork, failing, …)` so the test asserts it directly rather than inferring it from
wall-clock timing — including that **no** combination of inputs returns zero, since zero is the spin.

The producer side was checked for the same question and is already correct — but structurally, not
by care. A saturated ring makes `tryClaim` fail, so the producer learns of saturation from the very
object it writes to and cannot keep working past it. The kdb tap's cap was *separate* from its queue,
which is exactly why its producer kept allocating past the limit and this one does not. **Where the
limit lives decides whether the producer can honour it.**

That rule has a boundary, and the kdb lane supplied it: it only works when the limit is expressible
in the queue's **own units**. A ring that counts records can refuse a record; it cannot enforce a
*byte* cap, because the byte cost is only known after formatting — which happens on the consumer. So
the honest general form is two rules, not one: put the cap in the structure the producer already
touches **when you can**, and when you cannot, **publish the consumer's verdict as a flag the
producer reads before it allocates**. Every derived limit — bytes, wall-clock, cost, quota — lands in
the second case. Without that second half, a reader whose limit is derived bounces off the first rule
and falls back to "be careful in the producer", which is not a property anything can enforce.

The review question this leaves behind, worth asking of any best-effort side channel:
*what does this cost when the bad state lasts forever — including on the path that fails?*

Collector placement needed no new config: members carry a nodeSelector plus tolerations for the
tainted core-pinned pool and the observability workloads carry neither, so a taint that repels every
pod without a toleration already makes scheduling a collector beside the pinned Aeron cores
impossible.

## Logs joined to traces — and the two answers that were not the expected ones

The support workflow needs both directions: from a span to the order's log lines, and from a log
line back to its trace. Both now work, and neither needed anything plumbed, because **the trace id
is derived rather than generated**: any code holding the idempotency key can compute the exact id
the spans were emitted under. That is the derive-don't-carry property paying off a second time, in a
place it was not designed for.

**The first surprise: there was nothing to stamp.** The open item said promtail ships logs but they
are not stamped with the trace id. That undersold it — the cluster tier had **no per-order log lines
at all**. Every line in the gateway and the member is startup, role change, election phase or
snapshot; grep confirms it. So this was never a formatting change. The question was *which line is
worth having*, and the answer is the **reject** — the line a supporter is actually looking for, and
one that costs nothing per order in a system that is working.

**The second surprise: the mechanism the brief expected does not exist here.** "Log pattern vs MDC
vs a structured field" presumes a logging framework is in play. The cluster tier writes to
`System.out` — every line in `ClusterGatewayMain`, `MatchingEngineClusteredService` and
`ClusterNodeMain` is a `println` — and there is **no logback or log4j configuration anywhere in the
tree**, so "extend the log pattern" has no pattern to extend. (slf4j is on the fat jar's classpath
for the inherited Spring services, which do not run in this deployment; it is not used by a single
cluster-tier class.) Reaching an MDC would mean introducing a logging configuration and rewriting
these call sites to get the same characters onto the same line. So the id goes **in the line**, as
`trace=<32 hex>`, and the choice of *in the line* over the two real alternatives was decided on the
deployed platform, not on taste:

| Where | Verdict |
|---|---|
| Loki **label** | Ruled out. One label value per trace id is one log *stream* per order, and Loki indexes per stream — at the 190k orders/s ceiling that is 190k new streams a second. It would take Loki down long before it helped anyone. |
| **Structured metadata** | The right modern home, and unavailable. The deployed Loki is 2.9.8 and its config ships `allow_structured_metadata: false`; enabling it needs a limits change plus a promtail stage, and buys nothing over a substring filter on a stream already narrowed to one namespace. Named as the upgrade path, not done. |
| **In the line** | Chosen. Zero Loki config change, zero cardinality, and the only form that works in *both* directions — `derivedFields` links a line to its trace, `tracesToLogsV2` line-filters a span back to its lines. |

One thing had to be bounded that the span path already was. **`System.out` is the only sink in this
design with no overflow valve.** A span meets a full ring and is dropped and counted; the exporter's
duty cycle is capped so an outage costs about one attempt a second. A log line goes straight to the
node's disk and on to promtail and Loki with nothing in between that can refuse it — and a reject
storm is a demonstrated state of this system, not a hypothetical: a 30-second bench once had the
engine reject 296,000 orders on `CREDIT_LIMIT` while every request came back 2xx (the finding that
put `riskReason` on the ack in the first place). At ten thousand rejects a second an unbounded log
line makes the telemetry the outage. The line is therefore capped at
`REJECT_LOG_PER_SEC` (20) process-wide, with the suppressed count exported as
`traderx_gateway_reject_logs_suppressed_total` so the gap is visible rather than silent. That is the
same review question this document already asked twice, answered before it was asked a third time:
*what does this cost when the bad state lasts forever?*

**Also fixed here: "Logs for this span" was doubly broken.** The first reason is the one above —
there were no per-order log lines, so there was nothing for it to find whatever it queried. The
second is that the Tempo datasource carried the deprecated v1 `tracesToLogs` block naming the Loki
datasource and *nothing else*: no `tags`, no query. v1 builds its stream selector from the span tags
listed in `tags`, so with that list empty there is nothing to build a selector from. Same class as
the trace panel that had shipped with the wrong panel type: wiring present in a file, absent from
the running system, never exercised. It is now `tracesToLogsV2` with an explicit query, and the
proof asserts the *running* Grafana has it — reading it back over the API rather than trusting the
file, because trusting the file is how both of these survived.

## Error sampling: the honest answer was not a tail sampler

Head sampling is what makes the gateway and the member agree without carriage — and it is also what
loses the interesting orders. The verdict is a pure function of the key, fixed before the order is
offered, so a rejected order was sampled at the same 1-in-128 as any other: **127 of every 128
rejects were missing**, which is precisely the set a supporter needs.

**A collector-side `tail_sampling` processor cannot fix this, and adding one would have been
theatre.** A tail sampler chooses among the spans it *received*. An unsampled order emits nothing at
all — no span leaves either process — so there is nothing at the collector to keep. A
`tail_sampling` policy on this pipeline would only re-filter the 1-in-128 that head sampling already
kept: it would look exactly like error sampling on the collector config, and it would never surface a
single reject that had been dropped. The only place the decision can be taken is the head.

**So the answer is: always sample rejects at the head — which is possible here for the same reason
the trace joins at all.** "How it turned out" is the committed ack kind. That is not a wall-clock or
host-local fact; it is deterministic output of the replicated state machine. The member produces the
byte and the gateway reads the identical byte off the egress ack, so both sides evaluate the same
predicate on the same input and escalate *together*. Neither tells the other. Nothing is added to
the log. The emission points already sit after the decision is known — the gateway closes its spans
on the committed ack, the member emits after apply and drain — so the only cost is that the span
timestamps must now be recorded for every order rather than the sampled fraction: **one extra
`nanoTime` per order per tier, and only while `OTEL_TRACES=1`.** With tracing off, `traces` is null,
the key is zero, and both paths are byte-for-byte what the allocation gates and the Epsilon-GC
proofs have always measured.

**What deliberately stays out of reach: "slow".** A slow order is the other thing a supporter wants,
and it fails the exact test rejects pass. Latency is per-host and non-deterministic: the gateway's
"slow" (client residence) and the leader's "slow" (apply duration) are different numbers on
different clocks, and the members do not agree with each other either. Escalating on it would have
one side emit and the other not — a half-trace, which is worse than no trace, and the one outcome
this design must never produce. The microsecond-accurate tail belongs to the `/latency` histograms,
which is where this deliverable has always pointed for timing. That is the boundary of the whole
technique, stated plainly: **derive-don't-carry can escalate on anything the log decides, and on
nothing the wall clock decides.**

Cost when the bad state lasts forever: a reject storm turns the sample into 1-in-1, which the ring's
drop-on-full and the exporter's duty-cycle cap already bound — the cost is bounded, only *which*
spans survive degrades. A second key-derived mask for rejects would restore the baseline sample's
share and is the upgrade path if that ever bites; it is not shipped, because it would redistribute
drops rather than prevent any.

## Proof for the follow-ups

`scripts/proofs/yu15-otel-reject-trace-log-join.sh`. Falsifiable on the same principle as the
original: every id is computed in Python from the ClOrdID alone, and Tempo and Loki are asked for
exactly those. It runs with head sampling genuinely on (mask 127 on **both** tiers, restored on
exit) and submits two orders that both *fail* the head verdict — one rejected, one accepted:

- the **rejected** one must come back from Tempo as a whole 5-span, 2-service trace with the member's
  spans parented to the **predicted** `cluster.consensus` id — so both tiers escalated
  independently, and a one-sided escalation fails here rather than looking fine in a span list;
- the **accepted** one must **404** — the negative case, without which a build that quietly started
  tracing everything would pass;
- Loki must return that order's own `ORDER-REJECT` line for the trace id predicted from its ClOrdID;
- and Grafana must actually have the join provisioned **both** ways.

Run live on the three-member kind rig, 2026-07-29:

```
[run] reject arm: {"orderRef":60413,"kind":2,"reason":"UNKNOWN_ACCOUNT"}
[run] accept arm: {"orderRef":60414,"kind":1}
[ok] REJECTED order ...-1: full 5-span trace c002d5d9d3c3fbc355db47d85743d80b across
     ['traderx-cluster-gateway','traderx-cluster-member'], member spans parented to the
     PREDICTED consensus span 2e0a272052728d1a
[ok] accepted order ...-2 outside the head sample is NOT in Tempo
[ok] Loki returns the order's own log line for the trace id PREDICTED from its ClOrdID:
     ORDER-REJECT trace=c002d5d9d3c3fbc355db47d85743d80b clordid=...-1 account=987654
     ticker=AAPL orderRef=60413 kind=2 reason=UNKNOWN_ACCOUNT
[ok] Grafana has the join provisioned BOTH ways
```

One rig note the script now handles, because it cost a run: the script rolls the gateway (the mask is
read once at startup), and a `kubectl port-forward` binds one pod for its lifetime — so the
operator's forward dies the instant the roll begins and every probe after it reads as "the gateway
never came back". It did; the tunnel went. The script brings up its own forward on a private port for
the rolled half of the run and says so on exit.

## Open items

- **`SnapshotBarrierPerformanceTest` is marginal on the YU13 branch** — a 50 ms wall-clock budget
  that fails in the full suite and passes in isolation. **Ruled out as anyone's regression on two
  independent grounds.** Statistical (this lane): stashing the change out and re-rendering still
  failed, at 58.08 ms, *worse* than the 50.02/50.80 ms with it in. Structural (the kdb lane): the
  measured region is `service.writeSnapshot(writer)`; the capture tap touches only `onStart` and the
  `drainOutputs` loop, and `kdbTap` is null in any test that does not inject it — so neither change
  adds a single instruction inside the measured window. It is a contention-sensitive pre-existing
  benchmark; three forced `cleanTest` runs at a HEAD carrying both changes passed 3/3.
