# RI-24 — Venue breadth and projector work need measured memory bounds

Updated: 2026-10-07. Status: in progress (OOM exit and memory accounting integrated; sizing/liveness/recovery remains). Maintainer: coordinator. Integrated source revision: `b0b4c33276c93c9aae545019320d606f7f0d0c2d`.

## Current finding

Current empty LimitBook arrays require 4227072 payload bytes assuming compressed references. Trade-processor manifests still lack OOM-exit/liveness protection. The old in-memory dedup-set hypothesis is inconsistent with current SQL-backed dedup.

Verified by current-source review; local reproductions and limits are recorded in [October 7 triage](open-issue-triage-2026-10-07.md). Historical runtime observations remain historical.

## Work

- [ ] Measure book population/order depth against heap and evaluate bounded/sparse storage without sacrificing apply-path behavior.
- [ ] Measure projector and reconciliation memory using current SQL-backed code, including full-history materialization.
- [ ] Design OOM recovery and liveness so a failed projector cannot remain permanently alive but unusable.
- [ ] Keep throughput/allocation checks meaningful; higher heap alone does not remove unbounded growth.

## Acceptance

- [ ] Heap and allocation measurements record exact runtime configuration, symbols, orders and retained history.
- [ ] Fault tests demonstrate visible OOM/process recovery; restart must not silently lose unrecovered events.

## Original reports

- [a-book-costs-4mb-so-member-heap-caps-venue-breadth.md](../open/a-book-costs-4mb-so-member-heap-caps-venue-breadth.md)
- [trade-processor-limps-through-heap-oom-and-no-probe-fires.md](../open/trade-processor-limps-through-heap-oom-and-no-probe-fires.md)

Next: Measure full-engine/projector growth and choose deployment-profile limits with RI13; define event recovery.

Scope: local source/test work only when assigned. No push, deployment, retained-rig mutation or broad worktree propagation.

## Class-window assignment, October 7

Heap-OOM fail-fast subsection assigned to Sol6.1 Medium chat `01a11824-0e8a-7b12-8d08-84cf7a53c705` from accepted a0d6da0b. Bounded local JVM/configuration proof only; memory sizing, liveness and event recovery remain open. Coordinator review/integration pending.

2026-10-07 class-window update: Heap-OOM exit locally reviewed at365f9e14:13 coordinator-run bounded real host JVM/effective config tests pass. Controlled integration pending; no original application/container/live restart or event recovery proof. Broader memory, liveness and durability remain open.

2026-10-07 local book-memory accounting assigned to Sol6.1 Medium chat `01a11848-bde9-7d53-8230-133c1aee23c2`. Bounded current-code/JVM measurements only; no sizing/core/rig changes. Existing OOM-exit milestone remains reviewed/pending integration.

2026-10-07 book-memory accounting locally reviewed at `bd2950d2`:16 independent real Java21 checks pass;44 hashes match. Compressed-reference default131072-level book plus8arrays measures4227280 shallow bytes. Shared external fixture pool separate; retained/full-engine heap and production sizing not measured. Controlled integration pending.

## October 7 integration outcome

Container JVM launchers and the demo env patch now exit on heap OOM while preserving existing options (13 combined checks). The bounded direct-source instrumentation tool passed 16 checks and measured 4227280 book-owned shallow bytes at 131072 levels on Java21/compressed references. Shared fixture orders are separate. Full-engine/projector growth, production sizing, liveness and event recovery remain open.

Combined validation: 213 generated Java cases (including five allocation gates), 142 generated publisher/console cases, 171 operational-script cases, 13 OOM checks and 16 memory checks passed. The console production build and five repository gates passed. These are scoped local/source/generated results, not live HA, deployment-profile performance or financial acceptance. Evidence: shared coordinator `review-evidence/class-window-integration-20261007`.
