# Plan: YU10-fix-ingress

## Goal

Add a FIX 4.4 order-entry front door to the order-matcher that is a pure producer onto the
existing LMAX input ring: QuickFIX/J owns the session layer on its own threads, a translator maps
D/F/H to the same input events REST produces, a durable correlation ledger binds ClOrdIDs to ring
sequences, and a dedicated output-disruptor handler streams ExecutionReports back to the owning
session. The matching core, risk screen, journal, and recovery machinery are untouched; the new
surface is fail-closed on identity and honest about ambiguous outcomes.

## Workstreams

1. **Correlation ledger** (`fix/ClOrdIdLedger`): append-only on-PVC record
   (session key, ClOrdID, inputSeq, orderRef), batched force, startup rehydration, duplicate
   detection, fail-closed admission when unavailable. Built and unit-tested before any FIX
   wiring — it is the load-bearing recovery piece.
2. **Session + identity** (`fix/FixSessions`, `fix/FixIdentity`): QuickFIX/J acceptor lifecycle
   bound to Spring startup/shutdown; logon resolves `Password(554)` JWT through
   `EntitlementGate.resolve` and `SenderCompID` through `FIX_SESSION_ACCOUNTS`; the
   `ResolvedPrincipal` pins to the session; every failure mode rejects logon.
3. **Inbound translation** (`fix/FixOrderApplication`): D → order-new (ledger append → ring
   publish on the session thread), F → cancel (ledger lookup + account ownership check),
   H → read-model lookup → ExecType=I report; the four-outcome admission model of FR-FIX12.
4. **Outbound reports** (`fix/FixExecutionReportHandler`): output-disruptor handler
   (enqueue-only) joining `OutputEvent.inputSeq` to the ledger, building 8/9 messages, delivered
   via the session send path backed by the persistent file store.
5. **Runtime**: acceptor port + FIX data dir in the kind manifests (Service + NodePort),
   QuickFIX/J session/store configuration, disk-watermark coverage for the FIX directory.
6. **Proof + bench**: `yu10-fix-session.sh` (logon/order/cancel/status/pod-kill-resend against
   kind), a QuickFIX/J initiator conformance harness in the test suite, `fix-load.mjs` raw
   sender for throughput, and the regression ladder (allocation gates, REST/batch/BLP/HA
   bench-compare).

## Key decisions

Recorded as ADRs: adr-034 (QuickFIX/J, in-process topology), adr-035 (correlation ledger joined
on `inputSeq` rather than widening OutputEvent or the wire record), adr-036 (fail-closed session
identity via JWT-in-Password(554) + CompID map; cluster-internal endpoint), adr-037 (the
four-outcome admission model; OrderStatusRequest as the reconciliation mechanism).

## Exit Criteria

All SC-FIX01…06 verified on kind with evidence recorded in
`generation/implementation-status.md`; generation exits 0 with every ancestor marker intact on
shared files; the state's docs enumerate it everywhere states are listed.
