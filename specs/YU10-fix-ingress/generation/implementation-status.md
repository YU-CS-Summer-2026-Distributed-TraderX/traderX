# Implementation Status: YU10-fix-ingress

**Status**: Implemented and verified on kind (2026-07-16).

## Verification evidence

| SC | Evidence |
|---|---|
| SC-FIX01 (logon + order + fail-closed) | `FixSessionIntegrationTest` (real QuickFIX/J initiator, in-JVM): valid JWT logs on and receives ER New; an invalid JWT never gets a session. Confirmed live on kind: acceptor rejected a malformed token (`FIX logon rejected … 401`) and accepted a valid dev-token JWT. |
| SC-FIX02 (cancel round-trip) | `FixSessionIntegrationTest`: `OrderCancelRequest` on a resting order → ER Canceled; unknown order → `OrderCancelReject`. |
| SC-FIX03 (status) | `FixSessionIntegrationTest`: `OrderStatusRequest` → ER ExecType=I snapshot. |
| SC-FIX04 (restart reconciliation) | Ledger rehydration + duplicate detection across reopen unit-tested (`ClOrdIdLedgerTest.rehydratesAcrossReopen`). **Confirmed live**: after an order-matcher restart the ledger rehydrated 336,575 entries and correctly rejected every re-used ClOrdID as a duplicate (a re-run with the same ids produced all-duplicate rejections — the idempotent-retry safety property). Engine `clientOrderKey` idempotency (FR-IMRG14, session-namespaced) is the retry authority. |
| SC-FIX05 (no regression, NGC-01 holds) | Composed YU10 order-matcher suite: 148 tests, 0 failures. `allocationGateTest` + `riskAllocationGateTest` + `noGcTest` green with FIX code present — exact-zero preserved (ER handler is enqueue-only on the ring thread; QuickFIX/J allocates only on session threads). |
| SC-FIX06 (throughput vs REST) | **Matched-methodology control** (`scripts/bench/results/yu10-fix-vs-rest-matched-2026-07-16.md`): identical 256-in-flight window, "completed = outcome learned", same account/workload/cluster. FIX **5,213 completed lifecycles/s** (one session) vs REST POST /orders **3,479/s** (256 connections) — FIX ~1.5x, on a single connection vs REST's 256 and with port-forward latency handicapping FIX. The FIX/REST path equivalence (orders land in the same ring/journal/risk/DB projection) is separately proven by the 10s `yu10-fix-session.sh` proof growing the DB `orderbook` by exactly the completed count. |

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
- (Resolved 2026-07-16) The matched-methodology REST completed-lifecycle control is recorded:
  FIX 5,213/s vs REST 3,479/s at a 256-in-flight window.
