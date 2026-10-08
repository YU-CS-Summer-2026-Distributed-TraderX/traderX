# RI-16 — Reliable risk-job submission and portfolio worker scaling

Updated: 2026-10-07. Status: in progress: lifecycle/recovery foundation reviewed in isolated engine clone; engine handoff/activation, backpressure and worker scaling remain. Maintainer: coordinator; original delivery notes below retain their dates.

## Existing foundation

Alex has SQLite WAL jobs, atomic claims, one process-held worker lock per queue, interrupted status, restart supervision and compilation caching. Jobs run one at a time per queue. Scenario sharding distributes one job over local devices; it is not a multi-host portfolio scheduler. Reuse this design where appropriate rather than independently implementing another engine.

## Original work checklist (historical)

- Enforce client submission identity before cache lookup, bind it to request content/config/version, and ensure overlapping retries share execution ownership. Existing EOD I-57/I-58/I-59 remain open and owned through RI-04; do not mark them solved by the new queue.
- Fix accepted-but-unacknowledged portfolio submission when worker launch fails after durable enqueue (October7 audit R2): return/recover durable identity, explicit availability/status, safe lost-response reconciliation and no blind retry.
- Define validated calculation/currency selections, terminal versus interrupted/unknown semantics, restart reconciliation, execution fencing and replay policies; avoid unsupported exactly-once claims.
- Add queue readiness, bounded admission/backpressure, result retention/cleanup and request/result limits, with monitoring. Distinguish process liveness from a worker that can drain the queue. Agree cancellation/timeout behavior before advertising it.
- Design independent portfolio-job workers and scheduling for multiple portfolios. One worker owns its configured device resources; validate memory/isolation and avoid multiple processes unintentionally preallocating the same GPU. Do not share a local SQLite WAL file across machines as a substitute for a distributed queue. Decide coordinator/dispatch/store architecture against measured workloads.
- Define per-portfolio/account isolation, durable inputs/results and correlation/provenance. Multi-host workers must be authorized and tested separately from local-device sharding.
- Prove crash before/after enqueue, lost acknowledgment, duplicate submissions, interrupted work, result publication and API/worker restart. Local fault tests first; cloud horizontal scaling deferred until credits.

Next: Activate the reviewed service changes under agreed engine ownership, then bound worker capacity/backpressure.

2026-10-07: Local implementation authorized and dispatched on Sol6.1 High, task 01a116fd-2fce-77f1-a561-3b03472f0722. Exact isolated checkout CLAIM pending. Scope per dated coordinator handoff; cloud scaling remains deferred.

2026-10-07 review reconciliation: RI-16 local service foundation accepted for its documented compatibility scope at engine clone `796ca5dfb9d9d95ffb125fdd29ee041e678e910e`. Submission binding, overlapping ownership, request validation and lost-response recovery are source-tested locally; coordinator reran 49 distinct focused cases. This supersedes earlier statements that those exact gaps remain unimplemented. Engine changes remain in the separate clone; no original-checkout integration, deployment or general financial/worker-fleet acceptance. Full-envelope calculation subsets and USD numerical admission are explicitly documented. Broader readiness, retention, backpressure, mixed-currency analytics and scheduling remain open.

October 7 status reconciliation: the top status and next action supersede earlier queued/implementation-held headers. Historical unchecked planning lists are not a claim that implemented milestones are absent. Remaining acceptance is not marked complete.

October 7 new direct assignment: Current reviewed service container/restart qualification in same RI16 chat; no worker fleet or original-engine activation. CLAIM/checkouts pending; coordinator reviews before integration. No cloud, push or retained rig activation.

October 7 container milestone reviewed: packaging077ffac/image24206883 based on service796ca5df. Coordinator repeated14actual-container groups successfully; scoped LR03StageA passed. Original-engine incorporation, TraderX connected adapter/contract, general financial acceptance and fleet/readiness policies remain open. See live-rig/lr-03-risk-container-pipeline.md; no deployment/self-integration.
