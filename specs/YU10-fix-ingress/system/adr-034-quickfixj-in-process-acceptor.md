# ADR-034: QuickFIX/J engine, in-process acceptor

**Status**: Accepted · **State**: YU10-fix-ingress

## Context

The FIX front door needs a session layer (logon, heartbeats, sequence management, resend windows,
gap fills, persistent stores) and a topology decision: where do sessions terminate relative to
the order-matcher process, whose input ring (ProducerType.MULTI) already accepts producers from
REST/Tomcat threads and the replication follower?

## Decision

1. **QuickFIX/J** implements the session layer. The session machinery is the counterparty-visible,
   unforgiving part of FIX; QuickFIX/J provides all of it, mature and configurable (threading
   models, store implementations, session schedules). Hand-rolled FIX exists in this state only
   as the one-way bench sender, where no resend machinery is exercised.
2. **In-process acceptor** inside the order-matcher. The session thread translates and publishes
   directly to the input ring: no internal forwarding hop, no second stateful process in the
   recovery story, and a FIX order is bit-identical to a REST order past the front door (same
   risk screen, journal, replay).

## Alternatives considered

- **artio**: allocation-free session handling — but on the ingress threads, which sit outside
  the measured no-GC boundary (the allocation gates cover producer/journaler/BLP; the REST edge
  allocates per request today and the gates pass). Its price — an Aeron media driver as a hard
  dependency of the front door, and a far smaller configuration/documentation surface — buys
  nothing measurable at this system's ingress tiers. The acceptor/translator/report-handler
  seams are engine-agnostic, so this decision is confined to the session layer.
- **Separate FIX gateway process**: keeps session state alive across order-matcher restarts, at
  the cost of an internal per-order hop (over some transport with its own envelope) and a second
  process with its own recovery/fencing model. An earlier framing that a separate gateway "must"
  re-create the HTTP envelope tax was too strong — a compact binary IPC is possible — but the
  operational property it buys is session survival across engine restarts, which this state does
  not claim (TD-FIX02: single-BLP deployment, sessions reconnect and reconcile via the resend
  window). Paying the second-process cost for an unclaimed property is not justified here.

## Consequences

- QuickFIX/J allocates per message on its own session threads — outside the no-GC boundary, like
  Tomcat. NFR-FIX01 pins the boundary and the gates verify it.
- A JVM-wide failure takes both front doors down together; this is already true of REST and is
  what journal recovery exists for.
- The acceptor's threading model (ThreadedSocketAcceptor vs SocketAcceptor) is selected with a
  measured note in the implementation: blocking a session callback for ring backpressure must
  not starve that session's heartbeat duties.
