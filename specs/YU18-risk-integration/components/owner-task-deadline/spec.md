# Queued owner-task deadline specification

Status: implemented and locally verified; coordinator review pending. Owner: Codex RI23. Interface: internal synchronous owner callable lifecycle. Dependencies: existing Java FutureTask/LinkedBlockingQueue/AtomicInteger; no new service, wire or state format.

- FR-OD01: If timeout/interruption retirement wins an atomic WAITING-to-RETIRED claim, the user callable must never execute later. Remove the future from the queue when still resident; a previously dequeued reference must also remain inert. Cancellation success is not the proof: the separate start claim is.
- FR-OD02: Owner `run` may invoke the callable only after winning WAITING-to-STARTED. A start claim is irreversible and conservatively includes the interval before entry into the callable. If start wins, waiter termination must leave the future and execution/completion path intact; no cancellation, owner interrupt, rollback or automatic retry is introduced. Already-started/offered outcomes remain unknown to the former waiter.
- FR-OD03: Preserve successful/null values, exactly-once execution, callable exceptions via ExecutionException, and original waiter TimeoutException/InterruptedException behavior, including existing interrupt clearing. A callable's own InterruptedException remains a cause inside ExecutionException and is not mistaken for waiter termination.
- NFR-OD01: Preserve existing timeout values/start-of-wait semantics, ACK/correlation, owner polling/queue type/count/capacity, HTTP pool, probe/guard thresholds, routing, consensus, core, wire, snapshot, venue and financial behavior. Lifecycle state applies to synchronous and pipeline enqueue tasks; extra gateway task state/allocation is not matching-engine allocation acceptance. Make no performance or hot allocation claim from it.
- NFR-OD02: Preserve integrated ACK classification, member readiness diagnostics/gauges and the pipeline public/wire/ACK/reaper semantics. No claim that broader HTTP saturation, elections, feed recovery, quorum or gateway availability is repaired.

## Scope and caller paths

The accepted source has12 `onOwner` sites in11 methods:

| Caller | Interface | Scope |
| --- | --- | --- |
| handleBatch | POST /orders/batch | Whole queued batch callable; no per-order retirement after start |
| handleTrade | POST /trades | Direct trade callable |
| handleRunControl | /run/control | Queued managed run control |
| handleSandboxReset | POST /sandbox/reset | Queued reset callable; venue policy unchanged |
| handleBusinessDay | /session/business-day, /session/day-end | Queued day-control callable |
| handleSession | POST /session | Queued phase control |
| handleOtcBooking | POST /swaps, /swaptions | Queued OTC booking callable; contracts unchanged |
| applyControlDelta | Control-feed subscriber | Queued security-control application; existing redelivery/ACK behavior unchanged |
| handleRiskControl (two sites) | /risk/control | Self-match-group ACK and generic policy/security/account/restriction/FX controls |
| handleSeed | POST /seed | Whole queued seed callable; no partial-start rollback |
| handleResolve | POST /resolve | Queued lookup/registration |

The initial synchronous slice excluded `submitPipelined0`; the authorized pipeline extension below now covers its queued start boundary. It waits on PendingOrder futures. New/cancel/replace, typed orders and corresponding REST/FIX/binary ingress use that path; they now receive the queued fence described below. Member forwarding/probes/readouts also receive no new lifecycle. Callers' existing HTTP exceptions/unavailable responses are retained. A timeout response is not a committed business rejection; started work may still execute/commit, and no definitive nonexecution status is exposed to clients.

The wait budget still starts in FutureTask.get after enqueue. This is not a new absolute wall-clock/offer deadline. Timeout-versus-start races are decided at cleanup/start CAS, not by consulting a timestamp. If start claims the task before retirement—even after get's budget has elapsed—the result remains uncertain. Queue removal can lose to dequeue; it does not replace the start fence.

## Guard interaction boundary

Retiring a synchronous task does not create a PendingOrder, consume/release an inflight permit, fake an egress ACK, drain inflight orders, advance/reset noAckStreak/offeredUnackedStreak, reconnect, or change connected. All corresponding source sites remain unchanged. Pipeline ACK/reap/wedge/probe guards retain their signals and actions. Cancellation applies only after this task's WAITING claim wins; started batch drains/offers and control-feed work retain their original continuation, including existing redelivery behavior. Broader shared-counter/starvation concerns remain open.

## Acceptance

SC-OD01: actual production timeout then late queue drain/stale-reference run never mutates unstarted callable. SC-OD02: interrupted and previously interrupted waiter equivalence. SC-OD03: owner has already polled but not run when timeout wins; held reference stays inert. SC-OD04: successful value/null and exception/inner-interruption preserve original FutureTask behavior. SC-OD05: callable entry is latched before deadline/interruption termination; it remains uncanceled/uninterrupted, completes once, and the original future returns its value after former waiter has left. SC-OD06: canceled work then a healthy callable on the same queue. SC-OD07: actual JDK cancel(false) control demonstrates a running callable continues mutating despite successful cancellation. SC-OD08: negative omission/removal-only and naive running-cancel controls fail intended runtime assertions; source/generated parity and focused typed submission/ACK correlation regression.

Fixtures instrument enqueue visibility with the actual backing queue type, invoke production onOwner through reflection, and execute the exact produced task.run path. Explicit latches force both ordering outcomes; deadline0 is a deterministic get-timeout trigger, not a production setting. These local simulations do not run a live/retained cluster or prove elections, HA, transport latency or financial correctness.

## Pipeline queued retirement extension

- FR-OD04: Track successful permit acquisition explicitly. An abandoned queued pipeline task may complete PendingOrder.future with compatible ambiguous null and return exactly its acquired slot only if it wins the independent unstarted retirement claim. Remove a resident task, fence a held/dequeued reference, and never encode/offer it. Successful acquisition followed by failure before task creation releases only that known slot. Failed/interrupted acquisition must release no slot.
- FR-OD05: If start wins, p.offered=false cannot authorize cleanup: encoding, resolution or offer may already be running. The existing offer failure, ACK, reaper and drain paths retain exclusive future/permit ownership. No duplicate release, callable/owner interruption, rollback or automatic resubmission. Default acquire/ACK/wait budgets, correlation/history and metrics/guard behavior remain unchanged.

All OrderSubmitter new/cancel/replace and typed/string/numeric variants reach submitPipelined/submitPipelined0. Their REST/FIX/binary adapters retain public ambiguous-null behavior and validation. The private explicit wait-budget overload is a fixture seam; the existing entrypoint always delegates with the unchanged ACK_TIMEOUT_MS+2000. Acquire and transport/reap budgets use their original defaults. No new configuration/default/capacity is chosen.

The waiter retires only a never-started task; it never touches the owner-only pending map or byOffer queue. Semaphore release is thread-safe and belongs to the retirement winner. An ACK cannot exist for that never-offered request; a reaper cannot see it because registration follows offer. Started requests retain their original correlation/removal/release paths. submitPipelined's noAck/offeredUnacked/reconnect behavior is byte-identical: ambiguous null still increments the existing no-ACK streak; no fake ACK/success, reset or new reconnect signal is introduced.

SC-OD09: actual queued timeout/interruption then late run cannot encode/offer; nonempty acquire/enqueue witnesses, scratch-buffer sentinel, request-id and actual mock client.offer verify the boundary. Real semaphore depth/availability recovers once and a later healthy offer receives a real keyed ACK. SC-OD10: already-polled unclaimed reference stays inert. SC-OD11: started offer with p.offered=false survives timeout/interruption; ACK or reaper returns the slot once, duplicate/late ACK and sweep cannot over-release. SC-OD12: ACK wins before wait and returns committed value; enqueue and after-acquire/pre-task failures conserve owned slots; interrupted admission preserves other requests' occupied slots. SC-OD13: omission and unconditional extra-release controls fail; owner+pipeline+typed/ACK/correlation regression and exact source/generated parity.

Transport fixtures use the real pipeline methods/encoder/onEgress/reaper with mock Aeron offers and explicit latches. Original baseline waits the actual default12s for timeout; corrected fixtures use the private explicit budget0 trigger. Two-slot Inflight fixtures test conservation without changing production capacity. This is not actual consensus, gateway performance or financial validation. New queued-task lifecycle allocation is explicit; existing core/codec gates do not measure the full gateway enqueue path, and no unchanged gateway allocation/throughput claim is made.
