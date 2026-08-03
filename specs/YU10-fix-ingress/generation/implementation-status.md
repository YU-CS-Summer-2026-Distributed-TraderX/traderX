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

## Performance characteristic: parallel-session scaling

**Completed-lifecycle throughput does not scale with parallel FIX sessions**, and the ceiling is
the output/ExecutionReport path — not the matching engine. Measured on kind 2026-07-16
(`scripts/bench/results/yu10-fix-scaling-2026-07-16.md`), in-cluster load (no port-forward), 256
in-flight per session: total ~1,600/s at 1 session, flat-to-declining through 4, host-CPU-exhausted
at 7. The matching engine is idle throughout (~1.5M/s core capacity vs ~1,600/s measured — the
`process_cpu_usage` ~1 core is the LMAX busy-spin wait strategy, not real work).

Root cause: a completed lifecycle round-trips through two shared **single-threaded output-side**
stages — the output disruptor (`ProducerType.SINGLE`) and the ExecutionReport delivery (one
fix-report sender thread + QuickFIX/J `PersistMessages=Y` store writes). Parallel ingress sessions
queue behind these, so ingress parallelism raises throughput only when ingress is the bottleneck
(REST's per-connection block); FIX already pipelines past that with one session. This is the LMAX
single-writer principle applying to the output path, and it caps both FIX and REST
completed-lifecycle rates — FIX's ~1.5x advantage is purely on the ingress side.

Levers (deferred, for the GKE re-run — a natural piggyback on YU11's pool scale-up, since kind
cannot isolate client from server on one host): (1) the QuickFIX/J store sync mode
(`FileStoreSync`/cached-store durability-vs-throughput trade, flagged in the design critique);
(2) a multi-threaded ER sender if the single sender thread is confirmed as the serial choke on
dedicated cores. Neither is a correctness gap; both are throughput tuning on a path that today
comfortably serves the completed-lifecycle proof and the SC-FIX06 comparison.
