# RI-02 — Containerize the risk engine

Updated: 2026-10-07. Status: done for the original local container packaging scope (September23 accepted delivery). Engine upgrade/activation and wider readiness remain in RI04/RI15/RI16.

Authoritative draft is outside TraderX: `/Users/yaakov/dev/jax_risk_engine/risk-engine-container-spec/`. Do not copy its financial/API contracts into a second implementation here.

- [x] Inspect the pinned original engine and qualify packaging in the isolated assigned worktree; original Alex checkout preserved.
- [x] Build a reproducible Linux CPU image with pinned dependencies and narrow build context.
- [x] Document loopback-only startup, input/result mounts and startup validation.
- [x] Verify identical host/container requests and outputs for both supported APIs.
- [x] Exercise shutdown, child-process cleanup and interrupted execution; record limitations.
- [x] Record image digest, platform, engine/spec revisions, resource use and executable acceptance evidence.

Next: Maintain the qualified packaging. Current-engine/container migration belongs to RI15, contract/activation to RI04/RI16; no new packaging milestone is missing.

2026-09-23T18:27:49.242810+00:00: User authorized starting RI-02. Codex task `01a0cf85-d1f0-7463-808e-29ae66caaf54` dispatched with separate sibling engine worktree, local-only scope and existing spec. Board assignment `20260923T182650Z-coordinator-risk-containerization.txt`. Await exact claim and acceptance evidence; no completion claimed.

September 23 review accepted implementation `8de78f08` and qualification `d55a93f8` on `codex/risk-containerization` in the sibling engine containerization worktree. Linux/amd64 image exercised under ARM/Rosetta emulation. Supplied evidence: 750 passed/1 skipped, 11 operational groups passed; coordinator verified 66 evidence hashes and seven external spec hashes and reviewed source, without repeating heavy Docker execution. Build/run/qualification checklist requirements above are covered; bilateral branch handoff remains open, with active portfolio shutdown tested and interrupted EOD publication limitation retained. Original Alex checkout untouched; no merge/push/deploy. Full API readiness remains blocked by four contract failures; native performance, durable portfolio/retry semantics and multi-worker pipeline remain open. Next: agree engine-branch handoff and RI-03 single-portfolio pipeline scope. Review: `/Users/yaakov/dev/lmax/coordination/eod-integration/review-evidence/ri01-ri02-20260923/review.md`.

October 7 archive decision: this delivered local scope is complete; its documented production/performance/financial limitations are preserved and do not become new unfinished implementation here.
