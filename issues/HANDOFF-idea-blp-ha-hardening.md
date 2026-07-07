# Handoff: BLP HA / Throughput Hardening (CLOUD-ARCHITECTURE.md §7 backlog)

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

1. **HA lease starvation under load** *(real bug, not yet fixed)* — at conc≥24 the CPU-saturating
   load starves the leader-election Lease renewal, the PRIMARY false-demotes (409 lease conflict),
   and writes fail transiently until it re-settles. Candidate fixes: longer
   `leaseDurationSeconds`, and/or run the election thread at higher priority / off the saturated
   cores.
2. **`blp-role` label not always set on the PRIMARY after redeploy** — `order-matcher-primary`
   Service can end up empty. Not currently unsafe (the plain `order-matcher` Service still routes
   correctly since FOLLOWER reports not-ready), but worth fixing for anything that depends on the
   primary-only Service specifically.
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
