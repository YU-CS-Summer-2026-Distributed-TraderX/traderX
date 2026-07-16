# Implementation Status: YU10-fix-ingress

**Status**: Implemented and verified on kind (2026-07-16).

## Verification evidence

| SC | Evidence |
|---|---|
| SC-FIX01 (logon + order + fail-closed) | `FixSessionIntegrationTest` (real QuickFIX/J initiator, in-JVM): valid JWT logs on and receives ER New; an invalid JWT never gets a session. Confirmed live on kind: acceptor rejected a malformed token (`FIX logon rejected … 401`) and accepted a valid dev-token JWT. |
| SC-FIX02 (cancel round-trip) | `FixSessionIntegrationTest`: `OrderCancelRequest` on a resting order → ER Canceled; unknown order → `OrderCancelReject`. |
| SC-FIX03 (status) | `FixSessionIntegrationTest`: `OrderStatusRequest` → ER ExecType=I snapshot. |
| SC-FIX04 (restart reconciliation) | Ledger rehydration + duplicate detection across reopen unit-tested (`ClOrdIdLedgerTest.rehydratesAcrossReopen`, duplicate survives restart). Engine `clientOrderKey` idempotency (FR-IMRG14, session-namespaced) is the retry authority. Live pod-kill resend is exercised by `yu10-fix-session.sh --resume` / the QuickFIX/J store. |
| SC-FIX05 (no regression, NGC-01 holds) | Composed YU10 order-matcher suite: 148 tests, 0 failures. `allocationGateTest` + `riskAllocationGateTest` + `noGcTest` green with FIX code present — exact-zero preserved (ER handler is enqueue-only on the ring thread; QuickFIX/J allocates only on session threads). |
| SC-FIX06 (throughput) | `scripts/bench/results/yu10-fix-kind-2026-07-16.md`: ~8,109 completed order→ExecutionReport lifecycles/s sustained over 30s on kind, single session; the 10s proof grew the DB `orderbook` projection by exactly the completed count (FIX/REST equivalence). |

## Component status

| Item | Status |
|---|---|
| Correlation ledger (`fix/ClOrdIdLedger`) | Done — 5 unit tests |
| QuickFIX/J acceptor + identity (`fix/FixIngress`, `fix/FixIdentity`) | Done — live acceptor on :18130, fail-closed logon |
| Inbound translation (`fix/FixOrderApplication`) | Done — D/F/H, four-outcome admission, per-session amortized batch submit |
| ExecutionReport path (`fix/FixExecutionReportHandler`, `fix/FixMessages`) | Done — enqueue-only output handler + sender thread |
| LmaxEngine handler registration | Done — optional field injection, ancestor markers preserved |
| Manifests (deployment FIX port/env + NodePort service) | Done — verified on kind |
| Bench + proof (`fix-load.mjs`, `yu10-fix-session.sh`) | Done — 3/3 live proof |

## Known limitations (current behavior)

- ExecutionReports lost between the FIX store's sync cadence and a crash are recovered via
  `OrderStatusRequest`, not `ResendRequest` (TD-FIX01); the journal remains the authoritative
  fill record.
- FIX sessions terminate on the single configured replica; session state does not follow a BLP
  role change (TD-FIX02, single-BLP deployment).
- A matched-methodology REST completed-lifecycle control for the SC-FIX06 comparison is the
  remaining bench refinement (the FIX completed rate itself is recorded).
