# Handoff: BLP HA / Throughput Hardening (CLOUD-ARCHITECTURE.md §7 backlog)

> **STATUS 2026-07-14 — items 1 & 2 RESOLVED, deployed to prod, propagated to all 8 states.**
> The HA lease-starvation false-demote (item 1) and the `blp-role`-label bug (item 2) were fixed,
> deployed to `yaakovseif.dev`, and validated. GC was ruled out by measurement (max stop-the-world
> 157 ms vs the old 5 s renewal window) — the cause was renewal starvation + demote-on-first-failure,
> not the candidate fixes originally guessed here (longer lease / thread priority). Actual fix:
> **demote-on-proof** renewal state machine (foreign holder OR 10 s renew deadline; single optimistic
> PUT with cached resourceVersion; 2 s HTTP timeouts), **heartbeat split onto its own thread**,
> **pod-GET fast path** (real-kill failover ~2–3 s despite a 15 s lease), a **synchronous admission
> gate** (single-writer fencing, STW-wakeup-safe), **fail-closed replication** (NATS-down /
> JetStream-fail no longer self-appoint an unreplicated PRIMARY) + a **NATS backoff recovery loop**,
> and stamping `blp-role` at election start (item 2). Timing contract is env-tunable (15/10/2/2).
> Full design + validation: `LMAX-BLP-FAILOVER.md` (rewritten; §10 = incident) and
> `SPEC-blp-ha-lease-starvation-fix-FINAL.md`. Three independent model proposals were cross-reviewed
> to converge on it (`DESIGN-PROPOSAL-*`/`PROPOSAL-*`).
>
> **Still open:** item 3 (beyond-42k gateway path) and item 4 (broader crash/partition durability —
> real-kill + cgroup-freeze wedge are now tested, but multi-zone/partition is not). Two NEW
> production gaps surfaced during the fix's deploy: **NATS broker has no memory limit** (OOM'd under
> 22k/s HA load — real fix is JetStream file storage + stream/consumer limits, not a bigger cap),
> and the **replicator doesn't re-create the JetStream stream on NATS reconnect** (wedges the primary
> until restart). Throughput levers now live in `HANDOFF-ha-throughput-improvements.md` (item 5).

> Not one of the professor's 8 deck-idea handoffs — this one captures the "Known issues / next
> levers" already logged in `CLOUD-ARCHITECTURE.md` §7 that predate YU03-YU05 and remain
> unaddressed. None of YU03 (risk gateway), YU04 (durable control feeds), or YU05 (post-trade
> compliance) touched the BLP HA/replication layer or its gateway-path performance — they're all
> business-logic/data states layered on top of the `YU02-lmax-kubernetes` GKE baseline this
> backlog belongs to. Self-contained for a fresh chat.

## What this chat accomplished

Reviewed `CLOUD-ARCHITECTURE.md` §7 against everything landed in YU03–YU05 and confirmed none of
it overlaps — this is a distinct, still-open track sitting one layer below the spec-kit states
(BLP replication + raw gateway throughput, not order/risk/compliance business logic). Extracted
its four items into their own handoff so they're tracked as backlog rather than buried in an
architecture doc's tail section, and linked it from `HANDOFF-idea-INDEX.md`.

**⚠ Ownership note — check before picking this up:** per `CLAUDE.md`'s team split, **Tani
(`tanidiament@gmail.com`) is already actively working BLP performance** (snapshot improvements,
journal batch coalescing, bounded terminal-order retention) and has `clouddeploy.approver` access
to push/approve on this exact branch (`lmax-kubernetes-blp-ha`). The items below are adjacent to
but distinct from his current work (his is snapshot/journal-path; these are HA-replication and
gateway-CPU-path) — coordinate with him first so nobody duplicates effort or collides on the
shared cluster.

## Open items (from CLOUD-ARCHITECTURE.md §7, unaddressed as of 2026-07-07)

1. ~~**HA lease starvation under load** *(real bug, not yet fixed)*~~ **✅ FIXED 2026-07-14** (see
   status block above). The candidate fixes guessed here were wrong: GC was measured out (157 ms max
   STW), and lengthening the lease alone would only have made it rarer. Root cause was
   demote-on-first-ambiguous-failure + renewal starvation; fix is demote-on-proof + pod-GET fast path
   + split threads + admission fencing.
2. ~~**`blp-role` label not always set on the PRIMARY after redeploy**~~ **✅ FIXED 2026-07-14** —
   the label is now stamped at election start, not only on promotion/demotion, so a cleanly-elected
   pair that never flaps still populates `order-matcher-primary`. (This bug was actually *exposed* by
   fixing item 1: pre-fix, constant flapping always ran the promotion path and hid it.)
3. **Beyond 42k single-BLP booked/s**: the path is gateway-CPU-bound, not matching-engine-bound
   (the in-process ceiling is ~2.4M/s). Levers: a `c2-standard-8` (more cores), or reducing
   per-order gateway cost (async REST, fewer allocations) — **not** CPU pinning, which doesn't
   touch the actual bottleneck.
4. **Async-replication durability** is only benchmark- and single-failover-validated. A broader
   crash/partition durability test (kill PRIMARY mid-batch under load, partition PRIMARY from
   JetStream, etc.) is recommended before production trust in the HA path.
5. See `HANDOFF-ha-throughput-improvements.md` (if present locally — it's a handoff/scratch doc,
   deliberately untracked per this repo's hygiene rule) for further HA-replication levers (larger
   `batchRecords`, deeper pipelining).

## Key files

| Path | Why it matters |
|---|---|
| `CLOUD-ARCHITECTURE.md` §2, §4, §7 | HA/single-BLP mode details, throughput table, this backlog's source |
| `cluster-addons/order-matcher-statefulset.yaml` | Leader-election Lease config, resource/probe tuning |
| `LMAX-BLP-FAILOVER.md` | Design rationale for the current leader-election/replication scheme |
| `scripts/bench/run-gke-bench.sh` | In-cluster benchmark harness (see `bench-compare` skill) |

## Suggested first steps for next chat

1. **Talk to Tani first** — confirm what he's actively touching before starting, given the
   adjacency called out above.
2. Reproduce the lease-starvation bug at conc≥24 via `run-gke-bench.sh ha <runs> <secs> 1000 24+`
   to get a repeatable baseline before changing `leaseDurationSeconds`.
3. Bench-compare (see `bench-compare` skill) before/after any change near the hot path or
   replication path — this is exactly the kind of change that skill exists to gate.
4. Decide whether this becomes its own YUxx state (infra-hardening, parented on YU02 directly
   since it doesn't touch anything YU03/YU05 added) or stays as ad-hoc `cluster-addons`/manifest
   work outside the spec-kit lineage — infra-only changes with no new business requirement may not
   need a full spec pack; use judgment.
