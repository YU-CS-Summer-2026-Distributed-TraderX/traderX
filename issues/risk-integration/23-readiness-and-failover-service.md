# RI-23 — Readiness, gateway saturation and feed reconnect need stronger contracts

Updated: 2026-10-07. Status: in progress (diagnostics integrated; readiness policy/failover remains). Maintainer: coordinator. Integrated source revision: `b0b4c33276c93c9aae545019320d606f7f0d0c2d`.

## Current finding

Member readiness accepts up to 5000 entries of lag and no reachable peers. TCP liveness fixed a prior correlated HTTP probe failure, but quorum-safe restart behavior is not proved. Gateway HTTP queueing and feed restart backoff can delay availability.

Verified by current-source review; local reproductions and limits are recorded in [October 7 triage](open-issue-triage-2026-10-07.md). Historical runtime observations remain historical.

## Work

- [ ] Specify readiness versus convergence and quorum availability before changing lag defaults.
- [ ] Evaluate gateway admission/backpressure and owner-thread service behavior under election; do not quote old outage timings as current.
- [ ] Define bounded feed-adapter recovery from transient member/DNS failures while retaining visible cold-start failures.
- [ ] Review guard interactions and simultaneous restart hazards rather than adding independent watchdogs.

## Acceptance

- [ ] Local fixtures distinguish no peers, lagging peers, convergence and quiescent service.
- [ ] Disposable HA/partition proofs and RI13 deployment measurements establish actual recovery boundaries.

## Original reports

- [member-readiness-tolerates-5000-entries-of-lag.md](../open/member-readiness-tolerates-5000-entries-of-lag.md)
- [a-per-member-liveness-probe-fires-on-a-global-condition.md](../open/a-per-member-liveness-probe-fires-on-a-global-condition.md)
- [gateway-http-executor-never-drains.md](../open/gateway-http-executor-never-drains.md)
- [the-feed-adapter-does-not-come-back-after-the-cluster-rolls.md](../open/the-feed-adapter-does-not-come-back-after-the-cluster-rolls.md)
- [HANDOFF-issue-yu12-failover-measurement.md](../open/HANDOFF-issue-yu12-failover-measurement.md)

Next: Define readiness/quorum and gateway/feed outage contracts before live fault testing.

Scope: local source/test work only when assigned. No push, deployment, retained-rig mutation or broad worktree propagation.

2026-10-07 class-window dispatch: observed-peer/lag/caught-up diagnostic fields only, existing readiness and liveness policies unchanged. Gateway/failover/feed policy remains queued. Shared HANDOFF-CODEX-READINESS-DIAGNOSTICS-20261007.md; exact checkout CLAIM pending.

2026-10-07 readiness diagnostics locally reviewed at415b4a3c +4cf078b6:43 independent actualHTTP/sampler/gauge tests pass afterR1 invalidstarted correction. Sequence/peercoverage only, notquorum/bookconvergence/recovery proof. Controlledintegration pending; broaderRI23 staysopen.

## October 7 integration outcome

Observed peer counts/coverage, sequence lag/tolerance and nullable catch-up comparisons are integrated. Invalid started fields cannot certify observations. Existing routing-ready expression, 5000 tolerance and timing are preserved. Quorum/routing policy, gateway election outage and feed reconnect/failover work remain open.

Combined validation: 213 generated Java cases (including five allocation gates), 142 generated publisher/console cases, 171 operational-script cases, 13 OOM checks and 16 memory checks passed. The console production build and five repository gates passed. These are scoped local/source/generated results, not live HA, deployment-profile performance or financial acceptance. Evidence: shared coordinator `review-evidence/class-window-integration-20261007`.

## Evening queued owner-task lifecycle slice

Owner: Codex RI23. Status: locally verified; coordinator review pending. Accepted base22d4c906. The operative YU18 onOwner path reproduces queued callable mutation after actual caller timeout/interruption; a dequeued-but-unstarted reference remains executable too. New atomic start-versus-retirement guard retires/removes only unstarted work. Begun work stays uncertain and retains its original future/execution; no owner interrupt/rollback or new timeout/capacity/policy. Direct pipelined order submissions bypass this path and are unchanged.

See [owner-task-deadline component](../../specs/YU18-risk-integration/components/owner-task-deadline/README.md). All broader RI23 checklists and integration history above remain unchanged. Coordinator owns shared indexes/review/integration.

Owned evening evidence: source12/12, generated48/48 focused lifecycle/encoding/ACK/correlation,0failures/0errors/0skips; original baseline5 reproduced failures and two discriminating negative controls. Four repository gates/25 component packs pass. Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri23-owner-deadline-20261007/`. No real cluster/election/HA/performance/financial validation.

## Evening pipeline queued retirement follow-up

Owner: Codex RI23. Base/dependency: locally-reviewed76071dc8, accepted22d4c906. Status: locally verified; review pending. Direct pipeline bypass was reverified: actual default timeout/interruption returns ambiguous null, keeps an unregistered permit, and later sends an encoded offer. Follow-up fences only atomically unstarted work and releases only its proven acquired slot once; begun offer/ACK/reaper paths retain uncertainty and ownership. No p.offered=false or cancel(false) inference, capacity/timeout/finance/core/wire change. See owner-task-deadline pipeline section; shared indexes and integration remain coordinator-owned.

Pipeline follow-up evidence: source22/22, generated58/58,0failures/0errors/0skips, default-budget old timeout/interruption2 expected failures, omission and duplicate-release controls1 intended failure each. Four repository gates/25 component packs pass. Evidence: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri23-pipeline-deadline-20261007/`. Synthetic mock transport/code paths only; no cluster/performance/financial acceptance.

October 7 evening integration: the local milestones above passed coordinator review; delivery-specific historical pending notes are superseded by the final integration record. Broader unchecked deployment, mapping, sizing and recovery work remains open.

Final local outcome: synchronous and pipelined queued tasks use an atomic start/retire claim. A terminated waiter can retire only work that has not started; pipelined retirement returns its acquired permit once. Started/offered requests retain ACK/reaper ownership and an uncertain caller outcome. No election, failover or deployment-performance acceptance is inferred.
