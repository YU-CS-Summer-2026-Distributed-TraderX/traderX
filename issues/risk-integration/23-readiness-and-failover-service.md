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
