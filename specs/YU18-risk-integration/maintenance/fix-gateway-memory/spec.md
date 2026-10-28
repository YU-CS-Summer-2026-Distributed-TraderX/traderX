# FIX gateway memory specification

Status: new 2026-10-28. Scope: what a FIX session can make a gateway hold on its heap. Decision:
[ADR-092](../../system/adr-092-fix-gateway-memory-bound.md).

## Requirements

- FR-FGM-01: A session SHALL have at most `FIX_MAX_QUEUED_WRITES` (default 2,048) outbound messages queued that its
  client has not read. The next send SHALL end the session as a slow consumer (ERROR "Disconnecting: Slow consumer")
  instead of queueing it. A client that reads its answers SHALL NOT be dropped. With ADR-086 sessions, a message
  that was not sent SHALL still be stored, and resent on the client's ResendRequest.
- FR-FGM-02: A session SHALL have at most `FIX_INBOUND_QUEUE` (default 256) received messages waiting for its
  dispatcher. With the queue full, the gateway SHALL stop reading the session's socket until there is room, so a
  client that sends faster than its orders are answered is held back by TCP. Every message read SHALL still be
  processed and answered. The pause SHALL NOT be implemented by toggling MINA read interest from another thread
  (QuickFIX/J watermarks), which can lose OP_READ on MINA 2.1.4 and wedge the session.
- FR-FGM-03: Taking an ADR-086 session's lease SHALL read its stored log one message at a time. It SHALL keep
  only the session's open orders, at most 65,536 as `tracked` does. Heap SHALL NOT be proportional to the FIX
  day's message count.
- FR-FGM-04: A gateway's FIX heap SHALL be at most ~100 MB fixed (the capped maps, plus jnats's 64 MiB pending limit
  on the fill feed) plus ~2 MB per connected session, independent of session length, order count and the time since
  the daily reset. A session dropped by FR-FGM-01 SHALL release its queued messages once its client reads them, or
  within MINA's 60 s write timeout.
- FR-FGM-05: A fill feed that cannot connect SHALL retry with backoff from 1 s doubling to 60 s, log one WARN per
  outage (not one per attempt, and no per-attempt jnats record before it first subscribes), count each failure in
  `traderx_fix_fill_feed_connect_failures_total`, and report `traderx_fix_fill_feed_subscribed` 0/1 in `/metrics`.

## Acceptance

| Case | FR | Where | Expected |
| --- | --- | --- | --- |
| client reads nothing, 16,384 orders | 01 | `FixGatewayMemoryBoundTest.aClientThatStopsReading…` | session dropped as slow consumer; connection reaches EOF |
| client reads, same 16,384 orders | 01 | same test, control | all answered; still logged on |
| client sends 20,000 orders, venue not answering | 02 | `FixGatewayMemoryBoundTest.aClientThatOutruns…` | 128 < queued ≤ 256; then all 20,000 answered once; still logged on |
| tip acceptor (before) | 01, 02 | same tests | slow consumer never dropped; 10,000 queued for one session |
| watermarks 64/256 instead (rejected) | 02 | same tests, 3 runs | 1 of 3 wedged: "reads resumed" logged, nothing read for 30 s |
| bounded queue, 5 runs | 01, 02, 05 | both classes | 5 of 5 pass |
| fill feed, no NATS, 10 retries | 05 | `FillFeedRetryTest.anUnreachableFeed…` | sleeps 1, 2, 4 … 32, 60, 60 s; 10 counted; one WARN |
| fill feed comes up on attempt 3 | 05 | `FillFeedRetryTest.aFeedThatComesUp…` | subscribed; 2 counted |
| jnats per-attempt logging | 05 | `FillFeedRetryTest.aFailedConnectAttempt…` | 0 records (before: 5 for 5 attempts) |
| adoption of a stored day | 03 | `FixSessionPersistenceIT` | open orders tracked, fills after takeover reported once (unchanged behaviour) |
