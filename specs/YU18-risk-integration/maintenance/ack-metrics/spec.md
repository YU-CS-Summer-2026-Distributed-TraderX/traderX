# Gateway acknowledgement metric contract

Status: implemented locally; verification/review results are recorded in tasks.md.
Owner: Codex RI28. State: YU18-risk-integration. Updated: 2026-10-07.

## Requirements

- FR-AM01: Preserve the legacy `traderx_gateway_pipeline_total{stage="ack_unmatched"}` counter.
  It counts nonzero direct lifecycle acknowledgement records with no eligible live pending,
  including expected continuations. It is not an anomaly, lost-trade or unique-request count.
- FR-AM02: Add `traderx_gateway_ack_unmatched_total{reason="..."}` with six fixed reasons.
  Each legacy unmatched increment enters exactly one reason bucket. At rest their sum equals
  the legacy total; concurrent endpoint reads are racy and can temporarily differ.
- FR-AM03: `continuation` requires a retained completion in the current cluster session with
  equal request ID, positive applied sequence, positive order reference and zero risk reason.
  Only ACCEPTED → PARTIALLY_FILLED/FILLED and PARTIALLY_FILLED → FILLED establish a fill transition.
  It counts these provable transitions, not every fill, every trade leg or every order.
- FR-AM04: `duplicate` requires the same retained completion tuple and repeated kind, except
  PARTIALLY_FILLED. Lifecycle fills carry no ordinal and tradeSeq is zero: repeated partial
  records can be byte-identical across distinct match steps and must remain `unknown`.
  A replay of an earlier status after a later transition is also conservatively unknown.
- FR-AM05: `late_reaped` and `late_drained` require a retained retirement for that request.
  Repeated late records stay in those buckets; they do not imply unique late requests.
  `other_session` means the incoming session differs from the active Aeron session, covering
  stale and foreign sessions without asserting which. Unrecognized current-session request
  IDs, conflicting tuples, unsupported transitions and unavailable evidence are `unknown`.
  The wire supplies no independent producer identity, so an unrecognized ID cannot be proved foreign.
- FR-AM06: Preserve current-session keyed completion, first-response outcome, one permit release,
  queue/cluster latency samples, readiness/self-heal signals, resting filtering and batch handling.
  Noncurrent-session acknowledgements cannot consume a live pending, even with the same numeric ID.
- NFR-AM01: History has exactly `GATEWAY_MAX_INFLIGHT` direct-mapped primitive slots. Successful
  completions and retirements overwrite colliding evidence, counted by
  `traderx_gateway_ack_history_evictions_total`. No resize or per-request allocation occurs in this
  diagnostic history. `traderx_gateway_ack_history_capacity` reports slots, not bytes or occupancy.
  It gives no minimum time or count retention guarantee: a collision can evict recent evidence.
- NFR-AM02: Reconnect's existing drain returns outstanding permits and clears diagnostic identity
  history; cumulative reason/eviction counters remain. `traderx_gateway_ack_history_resets_total`
  counts this reset boundary, including startup. A same-session batch drain retains retirement
  evidence. There is no TTL: evidence expires by replacement, registration of the same ID, or reset.
  Production IDs remain gateway-lifetime-monotonic. This does not support same-session ID recycling.
- NFR-AM03: Keep all counters cumulative per gateway process, with one owner-thread writer and
  volatile endpoint reads. No request/session labels or unbounded collections. Metrics do not feed
  admission, readiness or repair guards. No deployed-profile performance claim follows from this work.

## Executable scenarios

`GatewayAckMetricsTest` drives the real public submit → owner task → offer → SBE encode → service
apply → encoded egress → gateway completion path with a synthetic transport. It covers sequential
two-account crossing, same-account STP, noncrossing, repeated partial bytes, direct first fill,
duplicates, unknown/foreign requests, zero/resting records, reaping/draining, reconnect and numeric
ID reuse across sessions, tuple conflicts, history collisions, unsupported transitions, latency,
readiness, cancel/not-found and batch controls. Injected acknowledgements use the actual onEgress
method; assertions read the real metrics HTTP handler. Inherited InflightCorrelationTest guards
keyed correlation and permits. GatewayConsensusSubmissionTest checks actual disposable Aeron sessions.

## Limits

Unknown is evidence uncertainty, not confirmed failure. IOC cancellation after an initial response
can remain unknown; broad lifecycle classification is not promised. Foreign and stale requests
cannot always be separated from dropped/evicted evidence. The old counter's historical console
impact claim was retracted; this component introduces no UI or monitoring policy change.
Shared wire/engine/snapshot changes, deployment, cloud work and financial validation are outside scope.
