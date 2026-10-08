# RI23 task — Bounded feed reconnect recovery

Updated: 2026-10-08. Status: reviewed; awaiting user integration approval. Author: codex-readiness-diagnostics. Coordinator: current TraderX coordinator.

Parent: [RI23](../23-readiness-and-failover-service.md). Outcome: reconnect after an established cluster connection, visible cold-start/exhaustion failure, safe session/symbol mappings and resource ownership. Gateway/core/financial semantics, retained rigs and cloud deployment are excluded.

## Authorization and routing

User approved the two recommendations and said `ok start those`. Implementation and bounded owned local proofs authorized; integration approval pending. Target after approval: `traderX-risk-integration`, preserved index/user edits; no push/deploy.

Chat `01a1180b-0b33-7651-999b-968a30518263`, verified active; gpt-6.1-sol High. Own status `codex-readiness-diagnostics.txt`. Parent coordination handoff `HANDOFF-CODEX-FEED-RECONNECT-20261008.md`; current routing `ACTIVE-LANES-20261008.json`.

Accepted base bf1d13e9; owned `/Users/yaakov/dev/lmax/traderX-ri23-feed-reconnect`, branch `codex/ri23-feed-reconnect`, clean candidate `459de827326cf9189fd1472ae516d549c1f2e954`. Original FeedAdapterMain inherited from YU12; author must identify effective extension owner and generated parity. Do not extend/reset the stale queued-task candidate.

## Progress and review

Author implementing and testing. Reconnect is not replay: already-offered prices need a new upstream quote; pending unaccepted values remain separately handled. Virtual-time retry matrix is independent of host timing. Reported native Aeron cold-start child is a failure/termination observation, not established reconnect/price application or performance proof. Coordinator recorded overlap with CPU container qualification. Candidate/final review verdict pending; read current board before updating this record from a delivered commit.

## Live-rig disposition

[LR02](../live-rig/lr-02-feed-reconnect.md) remains open for actual member/session/endpoint interruption, safe symbol re-registration and price sequencing. Source/generated/mock tests and an actual cold-start refusal cannot alone close it. Exact required completion scope versus later live deployment gate must be recorded at delivery; no rig startup follows from this task record.

## Integration and closeout

Pending candidate review/corrections, concrete user report and integration approval. No integration/done claim. Once approved and the task's gates pass, record target commit/parity/tests, remaining LR02 obligations and cleanup, move this bounded record to resolved and update parent/index/spec/live documentation. Do not close broader RI23 quorum/gateway/deployment acceptance.

## October 8 review outcome

Coordinator independently ran52focused exact-source Java21 tests:0failure/error/skip. Verified72author evidence hashes and reviewed432virtual retry scenarios plus three discriminating mutation controls. Supplied52generated cases/native cold-failure and parity evidence reviewed. No blocking source-level issue found. Proposed target: TraderX-risk-integration; user integration approval pending. No source merged or task archived.

Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri23-feed-coordinator-20261008/review.md`. Native cold refusal establishes FAILED_START/oneattempt/exit1/cleanup only. Required LR02 established live recovery/price effects remain open; even an approved code integration must retain acceptance-pending status until that scenario passes or the user explicitly defers it. Local10s/100ms/1s retry settings are provisional for deployment, not GKE recovery measurements.
