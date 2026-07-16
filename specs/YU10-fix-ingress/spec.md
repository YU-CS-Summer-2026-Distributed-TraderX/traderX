# Feature Specification: FIX Order-Entry Ingress

**Feature Branch**: `YU10-fix-ingress`
**Created**: 2026-07-16
**Status**: In implementation
**Input**: YU10 transport design round (two-proposal synthesis), parented on `YU09-ops-hardening`

## User Stories

- As an integrating counterparty, I want to submit orders and cancels over a standard FIX 4.4
  session so that I can connect my existing order-entry stack without writing to a bespoke REST
  API.
- As an integrating counterparty, I want ExecutionReports for every order-state transition
  (accepted, partially filled, filled, canceled, rejected) delivered asynchronously on my session,
  so my order state converges without polling.
- As an integrating counterparty, I want to reconcile after a disconnect using the standard FIX
  resend window, and to recover any individual order's state with an OrderStatusRequest, so an
  outage never leaves me permanently unsure of a business outcome.
- As the platform operator, I want every FIX session authenticated at logon (JWT) and bound to
  exactly one trading account (CompID mapping), failing closed when either is absent, so the new
  ingress surface adds no anonymous path to the matcher.
- As the platform operator, I want FIX ingress to reuse the existing input ring, risk screen,
  journal, and recovery machinery unchanged, so the matching core's correctness and no-GC
  guarantees are unaffected by the new front door.

## Functional Requirements

- FR-FIX01: The order-matcher SHALL run an in-process FIX 4.4 acceptor listening on a dedicated
  TCP port (default 18130, env `FIX_ACCEPTOR_PORT`), started with the application and stopped on
  shutdown.
- FR-FIX02: The acceptor SHALL accept `NewOrderSingle (35=D)` and translate it to the same
  order-new input event the REST path produces: `Account(1)`-bound account from the session
  principal, `Symbol(55)` resolved through the existing symbol table, `Side(54)`,
  `OrderQty(38)`, `Price(44)`, published to the input ring from the session thread.
- FR-FIX03: The acceptor SHALL accept `OrderCancelRequest (35=F)`, resolve the target order via
  the correlation ledger (`OrigClOrdID(41)`), verify the session principal owns the order's
  account, and publish the same cancel input event the REST cancel path produces.
- FR-FIX04: The acceptor SHALL accept `OrderStatusRequest (35=H)` and answer with an
  `ExecutionReport (35=8, ExecType=I)` reflecting the order's current state from the
  order-matcher's in-memory read model, without touching the input ring.
- FR-FIX05: A dedicated output-disruptor handler SHALL translate order-lifecycle output events
  originating from FIX sessions into `ExecutionReport (35=8)` messages (New, PartialFill, Fill,
  Canceled, Rejected — risk rejections carry the risk reason in `Text(58)`), delivered to the
  owning session via the QuickFIX/J session send path; the handler performs no network or disk
  I/O on the output-ring thread.
- FR-FIX06: Cancel requests that cannot be honored (unknown order, foreign account, terminal
  state) SHALL be answered with `OrderCancelReject (35=9)` carrying the reason.
- FR-FIX07: Logon SHALL authenticate the session: `Password(554)` carries a JWT resolved once
  through the existing `EntitlementGate.resolve`, and `SenderCompID(49)` SHALL map to exactly one
  trading account via committed configuration (env `FIX_SESSION_ACCOUNTS`,
  `COMPID:accountId[,COMPID:accountId...]`). A logon with a missing/invalid JWT or an unmapped
  CompID SHALL be rejected. This applies regardless of the `risk.entitlement.enforced` value,
  which continues to govern only the REST path.
- FR-FIX08: The resolved principal SHALL be pinned to the session at logon and reused for every
  message on that session; no per-message JWT resolution occurs.
- FR-FIX09: Every accepted order SHALL be recorded in an append-only correlation ledger
  (session key, `ClOrdID(11)`, input-ring sequence, order reference) written to the FIX data
  directory on the order-matcher PVC before ring publish; the ledger SHALL be rehydrated at
  startup, and the ExecutionReport handler SHALL correlate output events to sessions and
  ClOrdIDs through it via the output event's `inputSeq`.
- FR-FIX10: A `ClOrdID` already present in the ledger for the same session SHALL be rejected as a
  duplicate; ledger entries for live orders are never evicted; when the ledger cannot accept new
  entries (disk failure, capacity), order admission over FIX SHALL fail closed with a session
  reject rather than accept an uncorrelatable order.
- FR-FIX11: FIX session state (sequence numbers, sent messages for resend) SHALL persist in a
  QuickFIX/J file store on the FIX data directory of the existing `lmax-runtime-data` volume,
  surviving pod restarts; `PersistMessages` is enabled so `ResendRequest` re-delivers stored
  ExecutionReports after a reconnect.
- FR-FIX12: Admission outcomes SHALL be distinguished: (a) pre-publish failure (ring claim
  timeout, ledger unavailable) → session-level reject, no order exists; (b) malformed or
  unsupported message → session `Reject (35=3)`; (c) application rejection (risk screen, unknown
  symbol) → `ExecutionReport` with `OrdStatus=Rejected`; (d) a timeout AFTER ring publish
  produces NO reject message — the outcome is reported by the eventual ExecutionReport, and the
  counterparty reconciles with `OrderStatusRequest` or a same-`ClOrdID` retry, which the
  duplicate rule answers deterministically.
- FR-FIX13: The kind runtime SHALL expose the acceptor port on the order-matcher Service plus a
  NodePort for external test clients; no ingress or public exposure of the FIX port exists in
  this state, and the deployed session profile is plaintext TCP inside the cluster boundary.

## Non-Functional Requirements

- NFR-FIX01: No FIX code executes on the BLP, journaler, or output-ring threads except the
  enqueue-only ExecutionReport handler; the existing allocation gates (producer, journaler, BLP —
  exact zero) pass unchanged with the FIX acceptor idle and under FIX load.
- NFR-FIX02: FIX session threads are input-ring producers with the same backpressure semantics as
  REST: a full ring blocks that session's thread (surfacing as TCP flow control to that
  counterparty) without stalling the BLP or other sessions; a bounded claim timeout converts a
  pathological stall into outcome (a) of FR-FIX12.
- NFR-FIX03: Ledger writes are append-only sequential I/O off the hot path, batched with the same
  durability discipline as the journal (amortized force); ledger rehydration completes within the
  existing startup-probe budget alongside journal replay.
- NFR-FIX04: The FIX data directory participates in the disk-watermark alerting pattern
  (WARN ≥80%, ERROR ≥90% of volume) established for the journal.
- NFR-FIX05: Benchmarked throughput is reported as completed order lifecycles — NewOrderSingle
  accepted through ring/journal/risk AND its ExecutionReport durably stored and received by the
  client — alongside submitted/accepted/completed splits, per the repository benchmark
  discipline (recorded HEAD, image identity, configuration, three or more stored runs, same-day
  REST and batch controls).

## Technical Debt Register

- TD-FIX01: ExecutionReports generated between the last message-store flush and a process crash
  are re-deliverable only via `OrderStatusRequest` reconciliation, not via `ResendRequest` — the
  store's sync cadence bounds a small loss window that gap-fills on resend. The journal remains
  the authoritative record of every fill.
- TD-FIX02: FIX sessions terminate on the single configured order-matcher replica. The endpoint
  is not routed through the HA primary-election Service, and session state does not follow a BLP
  role change; the state is deployed in single-BLP mode.

## Success Criteria

- SC-FIX01: A QuickFIX/J initiator logs on with a mapped CompID + valid JWT, submits
  `NewOrderSingle`, and receives `ExecutionReport` New (and Fill when crossed); an unmapped
  CompID or absent/invalid JWT is rejected at logon.
- SC-FIX02: Cancel round-trip: `OrderCancelRequest` on a resting order produces
  `ExecutionReport` Canceled; on an unknown order, `OrderCancelReject`.
- SC-FIX03: `OrderStatusRequest` returns the current state of any order admitted on that
  session, including after an order-matcher restart.
- SC-FIX04: Kill the order-matcher pod mid-session under load; on reconnect after readiness, the
  session reconciles through the resend window with correct sequence handling and no duplicate
  order admission (same-`ClOrdID` retry is answered, not re-executed).
- SC-FIX05: The allocation gates and `noGcTest` pass exact-zero with FIX active; `bench-compare`
  shows no regression on REST single, REST batch, journaled-BLP, and HA replication tiers with
  the FIX acceptor idle.
- SC-FIX06: Completed-lifecycle FIX throughput on kind exceeds the same-day REST single-order
  control by at least 3x, with the submitted/accepted/completed split recorded.
