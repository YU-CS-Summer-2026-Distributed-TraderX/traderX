# FIX gateway memory specification

Status: new 2026-10-28. Scope: what a FIX session can make a gateway hold on its heap. Decision:
[ADR-092](../../system/adr-092-fix-gateway-memory-bound.md).

## Requirements

- FR-FGM-01: A session SHALL have at most `FIX_MAX_QUEUED_WRITES` (default 2,048) outbound messages queued that its
  client has not read. The next send SHALL end the session as a slow consumer (ERROR "Disconnecting: Slow consumer")
  instead of queueing it. A client that reads its answers SHALL NOT be dropped. With ADR-086 sessions, a message
  that was not sent SHALL still be stored, and resent on the client's ResendRequest.
- FR-FGM-02: A session SHALL have at most `FIX_INBOUND_QUEUE` (default 1,024) received messages waiting for its
  dispatcher, plus at most one socket read. Above that the gateway SHALL stop reading the session's socket, and
  resume at a quarter, so a client that sends faster than its orders are answered is held back by TCP. Every
  message read SHALL still be processed and answered. A full queue SHALL NOT block a MINA I/O thread that other
  sessions share.
- FR-FGM-03: Taking an ADR-086 session's lease SHALL read its stored log one message at a time. It SHALL keep
  only the session's open orders, at most 65,536 as `tracked` does. Heap SHALL NOT be proportional to the FIX
  day's message count.
- FR-FGM-04: A gateway's FIX heap SHALL be at most ~30 MB fixed plus ~5 MB per connected session, independent of
  session length, order count and the time since the daily reset. A session dropped by FR-FGM-01 SHALL release
  its queued messages once its client reads them, or within MINA's 60 s write timeout.

## Acceptance

| Case | FR | Where | Expected |
| --- | --- | --- | --- |
| client reads nothing, 16,384 orders | 01 | `FixGatewayMemoryBoundTest.aClientThatStopsReading…` | session dropped as slow consumer; connection reaches EOF |
| client reads, same 16,384 orders | 01 | same test, control | all answered; still logged on |
| client sends 20,000 orders, venue not answering | 02 | `FixGatewayMemoryBoundTest.aClientThatOutruns…` | queued ≤ 1,024 + 512; then all 20,000 answered once; still logged on |
| tip acceptor (before) | 01, 02 | same tests | slow consumer never dropped; 10,000-capacity queue fills, blocks MINA, `stop()` hangs |
| adoption of a stored day | 03 | `FixSessionPersistenceIT` | open orders tracked, fills after takeover reported once (unchanged behaviour) |
