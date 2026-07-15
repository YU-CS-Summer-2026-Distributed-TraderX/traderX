# Handoff: Making TraderX production-realistic (risk gateway → post-trade → …)

## What this chat accomplished

This session was mostly infra/ops (previous topics), ending in an **architecture consultation** on
what's missing to make the system production-realistic. Concrete outcomes of that discussion:

- **Confirmed the current system has ZERO pre-trade risk control.** Grepped `MatchingEngine.java`:
  the only "limit" is the order's limit *price* (buy crosses at ≤ limit price). No credit,
  buying-power, exposure, entitlement, restriction, or kill-switch checks. An order with a valid
  ticker just matches and fills.
- **Reviewed the `in-memory-risk-gateway` branch** (started 2026-06-22, branched from `009b`, now
  ~a dozen states stale). Its `specs/in-memory-risk-gateway/spec.md` is a genuinely production-grade
  design — see "Architecture / context" below. It solves BOTH of the user's stated goals (a risk
  engine AND getting prices/risk into memory faster) in one design.
- **Agreed a prioritized roadmap** (see "Goal for next chat"). Recommendation: do the risk gateway
  first because it's the highest-leverage missing tier AND already designed.

Earlier in the same session (already committed + pushed on `YU02-lmax-kubernetes-blp-ha`): fixed the
MariaDB projector/HdrHistogram/DB-port bugs, tuned order-matcher throughput (dedicated c2 node pool
→ ~42k single-BLP), async-pipelined HA replication (~1.7k → ~22k), and stood up a Cloud Build →
Cloud Deploy CI/CD pipeline for order-matcher. Those are done; this handoff is about *new* feature
work on top of them.

## Branch / repo state

- **Current branch:** `YU02-lmax-kubernetes-blp-ha` (the active/deployed branch). HEAD `6005c71`,
  fully pushed to `origin` — nothing of ours is unpushed.
- **`in-memory-risk-gateway`** — the stale branch to mine for the design/spec. HEAD `a75aca6`.
  Its merge-base with `YU02-lmax-kubernetes-blp-ha` is `58e2ae1` (way back, pre-k8s). Do **not** try to
  merge it; forward-port the design, not the code.
- Uncommitted: only `specs/lmax-kubernetes/quickstart.md` (minor, incidental — not ours to worry
  about) plus the usual pile of untracked local dirs/handoff docs. Working tree is otherwise clean.
- **Git commands that read many object files are SLOW — but the cause is an endpoint security
  filter on this machine intercepting per-file I/O, NOT the repo (the git object store is only
  ~18 MiB / 1,345 tracked files, and is healthy per `git fsck`).** Diagnosis: `git show --stat`
  burns seconds at ~0% CPU (waiting on I/O), and `git gc` SIGBUS-crashes under the filter.
  Mitigations: prefer low-object-count commands — `git show <ref>:<path>` for a single file,
  `--name-only` instead of `--stat`, `git diff-tree` plumbing, scope diffs with `-- <subdir>`.
  `core.commitGraph`/`core.untrackedCache` are already enabled (help log/status). The real fix is
  to exclude `/Users/yaakov/Desktop/Summer 26/lmax/` from the filter's real-time scan, or toggle
  the filter off for heavy git work and run `git gc` once to pack the 1,456 loose objects.

## Goal for next chat

Make the system more production-realistic, working through the agreed roadmap **in order**. Per the
user: **create a new branch off `YU02-lmax-kubernetes-blp-ha`** and do all the work there; **after each
roadmap item is completed, `git commit` — but do NOT push.**

**IMPORTANT — packaging as a new spec state (added after the roadmap was agreed):** each roadmap
item must be implemented as its own **new spec state under `specs/`** (its own directory, following
the spec-kit pattern used by states 001–014, 009b, and `in-memory-risk-gateway`), authored as a
**delta off the `lmax-kubernetes` state** — NOT as inline edits to existing `runtime-overrides`.
So the risk gateway becomes e.g. `specs/015-in-memory-risk-gateway/` (or similarly named) with its
own `spec.md`, `requirements/`, generation overrides, etc., whose parent state is `lmax-kubernetes`.
Regenerate with the pipeline against the new state id. Mine the old `in-memory-risk-gateway` branch's
`specs/in-memory-risk-gateway/` for the design to forward-port into this new state.

Roadmap (agreed priority order):

1. **In-memory risk gateway** (forward-port + finish) — the pre-trade admission tier. Covers the
   user's two goals: real risk engine + prices/risk into memory faster. START HERE.
2. **Post-trade: settlement + reconciliation** — T+N settlement state machine + recon between the
   journal (source of truth) and the MariaDB read-model projection.
3. **Real auth + entitlements** — OIDC/SSO instead of hardcoded account IDs; feeds the gateway's
   entitlement replicas.
4. **Market data dissemination** — publish the order book (L2), not just last-trade prints.
5. **Regulatory reporting** — CAT/TRACE-style audit reporting off the journal.
6. **Ops hardening** — secrets out of plaintext manifests, DR/multi-region, journal archival.

## Key files

| Path | Why it matters |
|---|---|
| `specs/in-memory-risk-gateway/spec.md` (on the `in-memory-risk-gateway` branch) | **The design to forward-port.** Full FR-IMRG/NFR-IMRG requirements. Read via `git show in-memory-risk-gateway:specs/in-memory-risk-gateway/spec.md`. Also look for `requirements/`, `INMEMORY-RISK-GATEWAY-ARCHITECTURE.md`, `INMEMORY-RISK-GATEWAY-HANDOFF.md` on that branch. |
| `.../order-matcher/.../lmax/MatchingEngine.java` | Current matching engine — where the BLP's authoritative pre-trade decision (exposure reserve in sequence order) must be added. Confirmed today it has no risk logic. |
| `.../order-matcher/.../lmax/LmaxEngine.java` | Wires the input Disruptor (journaler + replicator → matching engine). Gateway/BLP risk checks slot into this topology. |
| `.../order-matcher/.../service/PricingNatsSubscriberService` | How prices already flow into BLP memory over NATS (`pricing.*`). The gateway replica pattern generalizes this to accounts/limits/restrictions/etc. |
| `specs/lmax-kubernetes/generation/runtime-overrides/` | Where all source overrides live. New services/specs for this branch go under a new `specs/<new-state>/` following the spec-kit pattern (see existing states 001–014, 009b). |
| `CLAUDE.md`, `CLOUD-ARCHITECTURE.md` | Current project + cloud architecture. Read both before building. |
| `pipeline/generate-state.sh` | Regenerates `generated/` from `specs/`. Run `bash pipeline/generate-state.sh lmax-kubernetes` after spec changes. `generated/` is gitignored. |

## Architecture / context the next chat needs

**The system today:** LMAX BLP (`order-matcher`) = single-threaded in-memory matching, journaled to
disk, snapshots, HA via k8s-Lease leader election + NATS JetStream replication. MariaDB is an async
read-model projection (NOT source of truth — the journal is). NATS for all messaging. Supporting
services: trade-processor, account-service, position-service, price-publisher, reference-data,
people-service, trade-service, web-front-end. Deployed on GKE; CI/CD via Cloud Build → Cloud Deploy.

**Why the risk-gateway design is the right shape (don't reinvent it):** it's a **two-tier** model —
1. **In-memory admission Gateways** do fast *preliminary* screening with NO synchronous REST/DB
   lookups (FR-IMRG01). They hold event-fed local replicas of security status, account status,
   entitlements, restrictions, risk limits, kill switches, and price-freshness metadata
   (FR-IMRG02). Bootstrap = subscribe+buffer deltas → atomic snapshot at watermark W → apply deltas
   > W (FR-IMRG04). Ready only when every mandatory replica hit the durable-stream high watermark;
   fail **closed** on staleness/version-gap/epoch-change (FR-IMRG03/05).
2. **The BLP makes the authoritative, deterministic pre-trade decision** — checks and *reserves*
   exact aggregate exposure **in global sequence order** before an order becomes executable
   (FR-IMRG07: gateway pass is preliminary; aggregate checks repeat in the BLP). Rejected commands
   stay journaled for audit/replay but never enter the book or emit a market-facing event.

That determinism-in-sequence-order is *why* it belongs in the single-writer BLP and not a bolt-on
service — it's the only way rejections replay identically from the journal. Control baseline is
**SEC Rule 15c3-5 (Market Access)** — pre-set limits, erroneous-order checks, restrictions,
kill switches. Requirement namespace is `IMRG` (`FR-IMRGxx`, etc.); it extends the inherited
`009b` no-GC and latency gates.

**Prices-into-memory:** prices already reach the BLP via NATS today, but the surrounding validation
(account/position/entitlement) still does synchronous DB/REST lookups. The gateway replica pattern
moves *all* of that in-memory and adds freshness/fail-closed semantics.

## Decisions already made (don't re-litigate)

- **Do the roadmap in order, risk gateway first** — highest leverage, already designed, covers both
  user goals.
- **New branch off `YU02-lmax-kubernetes-blp-ha`**; commit after each roadmap item; **do not push**
  (user will handle pushing).
- **Forward-port the risk-gateway *design*, not merge the branch** — it's too far back (pre-k8s).
- **Keep the two-tier gateway+BLP model and the SEC 15c3-5 baseline** from the existing spec — it's
  sound. Don't redesign it into a synchronous bolt-on risk service.
- **NATS→Aeron was already considered and rejected** for throughput; not relevant here, don't revisit.
- **Repo hygiene:** never commit HANDOFF-*/scratch docs (this file included) — leave untracked.

## Open questions / known issues

- **Scope of the forward-port:** the full IMRG spec is large. Next chat should first *assess* what
  the port entails (which requirements are must-have for a meaningful first cut vs. later) rather
  than porting everything at once. A thin vertical slice (one real limit check reserved in the BLP +
  one event-fed replica) may be the right first commit.
- **How gateways deploy:** the original design predates the k8s/HA topology. Decide whether the
  admission Gateway is a new service, a sidecar, or logic folded into the existing REST gateway on
  order-matcher. Not yet decided.
- **HA lease starvation under load** (from earlier this session) is still unfixed — unrelated to
  risk work but worth knowing if load-testing the new path.
- Git slowness on this repo (see Branch/repo state) — budget for it.

## Suggested first steps for next chat

1. `git checkout -b <new-feature-branch> YU02-lmax-kubernetes-blp-ha` (pick a name, e.g.
   `production-realism` or `risk-gateway-forward-port`).
2. Read `specs/in-memory-risk-gateway/spec.md` in full via
   `git show in-memory-risk-gateway:specs/in-memory-risk-gateway/spec.md`, plus its `requirements/`
   dir and any `INMEMORY-RISK-GATEWAY-*.md` architecture/handoff docs on that branch.
3. Read `CLAUDE.md` + `CLOUD-ARCHITECTURE.md` to ground in the current base.
4. **Assess the forward-port**: map IMRG requirements onto the current `YU02-lmax-kubernetes-blp-ha`
   topology; identify the smallest meaningful vertical slice (one enforced, journaled, replayable
   pre-trade risk check in the BLP + its event-fed replica) as the first deliverable.
5. Build that slice, then `git commit` (no push). Then proceed to roadmap item 2 (post-trade), etc.
