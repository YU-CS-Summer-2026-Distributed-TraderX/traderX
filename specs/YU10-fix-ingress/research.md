# Research: YU10-fix-ingress

## Why FIX, and why now

The measured ingress tiers on this system are REST single-order ~9.2k/s, REST `/orders/batch`
~74k/s, and the in-process journaled BLP at ~1.26–1.59M/s (`scripts/bench/README.md`). Two
ingress-internal experiments (async MVC, gateway microbatching) both REGRESSED single-order
throughput — the ceiling is the per-order HTTP envelope and blocked round-trip, not the engine.
FIX's session model is the standard order-entry answer: orders stream in on a long-lived session,
ExecutionReports stream out asynchronously, and no per-order round-trip exists to block on. It is
also the protocol integration surface an order-management system is measured by.

## Engine selection: QuickFIX/J

The FIX session layer — logon, heartbeats, test requests, sequence management, resend windows,
gap fills, duplicate flags, persistent stores — is the part that is counterparty-visible and
unforgiving. QuickFIX/J implements all of it, is mature and widely deployed, and its
configuration surface (session settings, store implementations, threading models) covers this
state's needs without custom transport code.

Alternatives considered:

- **artio** (Aeron-based FIX engine): allocation-free session handling on the ingress threads.
  Rejected here because the ingress threads sit outside this system's measured no-GC boundary
  (the allocation gates cover producer/journaler/BLP threads; the REST edge already allocates
  per request and the gates pass), so artio's core advantage is spent where it isn't measured,
  while its cost — an Aeron media driver as a hard dependency of the front door and a much
  smaller configuration/documentation surface — is paid immediately. The acceptor, translator,
  and report handler are engine-agnostic seams; adr-034 records the boundary.
- **Hand-rolled FIX subset**: writing FIX frames is simple; the resend/recovery machinery is
  not, and it is exactly what a counterparty exercises during incident recovery. Hand-rolling is
  used only where it is safe: the throughput load generator, which only sends.

## Topology: in-process acceptor

A separate FIX gateway process must forward orders to the matcher over some internal transport,
re-introducing a per-order envelope and adding a second stateful process to recovery. In-process,
the session thread publishes translated events directly to the input ring
(`ProducerType.MULTI` — REST/Tomcat threads and the replication follower already share it), so a
FIX order is bit-identical to a REST order past the front door: same YU03 risk screen, same
journal, same replay. The trade-off accepted: a JVM-wide failure takes both front doors down
together — which is already true of REST, and is what the journal/recovery machinery exists for.
The state deploys in single-BLP mode and the FIX endpoint addresses the single replica directly;
session state does not follow BLP role movement (TD-FIX02).

## Correlation: the ledger and `inputSeq`

`OutputEvent` carries `inputSeq`, correlating every lifecycle output back to the input-ring
sequence of the command that caused it — the same mechanism the REST gateway uses for its
acknowledgements. FIX correlation therefore needs no new field on pooled events, no snapshot or
wire-format change: the FIX layer records (session, ClOrdID, inputSeq, orderRef) durably BEFORE
publishing, and the report handler joins output events to sessions through that ledger. The
ledger doubles as the duplicate-ClOrdID detector and survives restarts by rehydration; adr-035
records why the alternative designs (a session/origin field on OutputEvent; journaling the FIX
identity in the input record) were rejected.

## Identity: fail-closed at logon

`EntitlementGate.resolve` validates a JWT `Authorization` value and returns a `ResolvedPrincipal`
that is resolved once and reused — the exact pattern the REST batch path uses per request. FIX
Logon carries the JWT in `Password(554)`, so the existing gate API is used unchanged, once per
session. CompID→account mapping is committed configuration; there is no fallback account. The
REST `risk.entitlement.enforced` flag (default false) governs the REST rollout only: a brand-new
ingress surface has no installed base to keep compatible, so it starts fail-closed. The endpoint
itself is cluster-internal (NodePort on kind for test clients); the deployed session profile is
plaintext TCP inside the cluster boundary.

## Ambiguous outcomes

The REST gateway's acknowledgement wait demonstrates the problem: once a command is published to
the ring, a timeout no longer proves rejection — the journaler and BLP may still process it.
FIX makes the resolution natural: the eventual ExecutionReport is the outcome, `OrderStatusRequest`
is the standard pull-based reconciliation, and duplicate-ClOrdID detection makes a client retry
safe and deterministic. The four-outcome model (FR-FIX12) never converts an ambiguous state into
a definitive reject; adr-037 records it.

## Store durability

QuickFIX/J's file store persists sequence numbers and sent messages; `PersistMessages` enables
resend-window recovery of ExecutionReports. The store's sync cadence bounds a small crash window
(TD-FIX01) whose compensating control is `OrderStatusRequest` — the journal remains the
authoritative fill record, and the YU05 regulatory export reconstructs fills independently of any
session store.
