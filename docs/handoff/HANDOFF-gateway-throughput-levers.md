# HANDOFF — Gateway throughput levers, then the compute rollover (the next 2×)

> Next-work handoff, created 2026-07-21. Self-contained for a fresh chat.
> **Home:** `traderX-YU13-limit-order-book` worktree, `docs/handoff/`. Untracked working note.
> **Lane ownership:** this lane owns **`ClusterGatewayMain`** and **the GKE cluster** (node pools,
> benching, machine types). A parallel lane owns the instrument/reference-data model
> (`HANDOFF-YU14-listed-equity-options.md`) — do not edit reference data or the risk gate here.
> **Source:** `RECAP-2026-07-21-osff3-taq-throughput-session.md` §"OPEN OPTION".

## The gap

The engine does **1,134,658 orders/s in-process**; REST books **~150k/s**. That gap is not the
matching engine — it is the gateway. One owner thread per gateway does
resolve → encode → offer → pollEgress per order, behind 64 JSON-parsing HTTP threads, with committed
acks returning on a **64k-term egress channel that drops under load**. A lossy batch then burns its
full ~10 s ack budget, which is why batch ≥500 collapses and why single-stream runs stall.

Estimated: **levers 1+2 alone plausibly clear 300k booked/s** on today's hardware. Each lever is a
clean measured before/after — ideal talk material.

## Phase 1 — gateway levers, in ROI order

1. **Ack high-water-mark batch completion.** Complete a batch when the ack stream's `appliedSeq`
   passes the last offered order, instead of requiring every individual ack. Removes the batch cliff
   entirely and unlocks batch 500–1000 (fewer HTTP round-trips per order). **One gateway-file change
   + live A/B.** Highest ROI on the board.
2. **Raise ingress/egress term buffers 64k → 1–4M** (channel URIs in `ClusterGatewayMain`). Cuts the
   ack drops at the root. Trivial change — **measure the drop rate before and after under load**, or
   you cannot attribute the gain.
3. **Scale gateways beyond 3 with guaranteed spread.** Measured ~linear today. Needs nodes (`std-pool`
   is 2× e2-standard-2 plus the default pool). Cheap nodes; expect linearity until consensus
   saturates — find that knee, it is a good slide.
4. **SBE/binary batch ingress** (drop per-order Jackson parsing). Secondary — only if CPU profiling
   says the JSON path is the constraint after 1–3.
5. **Machine flow bypasses HTTP entirely** (Aeron ingress client, or the FIX gateway). The REST hop is
   a convenience contract, not the fast path. Largest change, lowest priority.

Stop after 1–3 unless the numbers argue otherwise. 4 and 5 are real work with a much worse ratio.

## Phase 2 — compute rollover (only after Phase 1's numbers are banked)

Now has measured justification: a tuned, core-pinned host collapsed the match tail **7–13×**
(cross p99.99 83.6 µs → 12 µs). "The tail is the host, not the book."

- **Tier 1 (free, config):** CPU Manager static policy
  (`--kubelet_config_cpu_manager_policy=static`, needs Guaranteed QoS + integer CPU requests — members
  already qualify), `threadsPerCore=1` (no hyperthread sibling), and a **compact placement policy** on
  the member pool (cuts inter-node latency, which is consensus round-trip time).
- **Tier 2 (machine family):** current members are **C3** (3.0 GHz sustained all-core). **C4D** (AMD
  Turin) reports **4.1 GHz max boost** and a large L3 — and matching is irreducibly serial, so boost
  clock is the single most relevant spec. **Check C4D availability in `us-east1-b` first**; that
  five-minute check decides whether Tier 2 is even on the table.
- **Bundle Tier 1 and Tier 2 into ONE node pool rebuild.** All of it requires recreating the pool;
  doing them separately cycles the cluster twice for nothing.

## Measurement discipline (non-negotiable — this feeds a public talk)

- **One variable at a time.** Levers before machine change. If you swap hardware mid-phase you cannot
  attribute anything, and the talk slide becomes unsupportable.
- Sustained rates, never burst peaks. Reproduce ≥3 runs, 0 restarts, `booked=applied`.
- Tail percentiles, never means or maxima.
- The per-order synchronous path and the pipelined batch path are **different contracts** — never mix
  their numbers.
- The idle-cluster failover number (~200 ms) and the under-saturation recovery number (~8–12 s) never
  share a slide.

The `bench-live-cluster`, `aeron-cluster-live-ops`, and `bench-compare` skills encode most of the
traps below — let them fire rather than re-deriving.

## Traps that have each cost a session

- **Gateway topology was half the old ceiling.** With no anti-affinity the scheduler packed all three
  gateways onto one 2-vCPU node — that topology *is* the old ~75k. Spread one-per-node gives
  146k–165k. `podAntiAffinity` is added, but **`preferred` does not survive a rolling update** —
  recycle pods sequentially and verify placement before every run.
- **Batch 200 is the knee today**; ≥500 collapses on owner-thread queueing past the client timeout.
  That is precisely what lever 1 removes — so re-test the batch curve after landing it.
- **Credit wall**: per-account `executedNotional` accumulates gross fills forever; an account walls
  after ~30.7M orders at bench sizes. Tell: trades counter freezes while applied rises, HTTP stays
  2xx. **Rotate the 7 seeded accounts in long runs.**
- **Seed real accounts + reference universe first** — unseeded = silent reject, HTTP 200, nothing
  books.
- **Pin the image digest.** `imagePullPolicy: Always` silently swaps the running binary between runs.
- **Bench in-cluster, never through `kubectl port-forward`** (single-threaded proxy: 2,396 vs 9,383
  submit/s).
- **Odd ticker count** — the harness alternates sides by index, so an even count means nothing crosses.
- **`kubectl cp` silently truncates ~35 MB files** — gzip + `exec cat` + md5.
- Current sizing already applied: member heap 1536m, shm 1Gi, pod 4Gi.

## Proof / acceptance

- Before/after table per lever: booked/s sustained, failure count, and (for lever 2) egress ack drop
  rate. Each lever independently attributable.
- The batch curve re-measured after lever 1 — the cliff should be gone.
- The gateway-scaling knee identified in lever 3.
- After Phase 2: match-latency tail re-measured on the tuned/rebuilt pool, plus one clean node-clock
  failover run to confirm no regression.

## Dependencies & sequence

- **Parallel-safe with the YU14 options lane** — disjoint files, and that lane does not touch GKE.
  Coordinate only on this: if YU14 lands mid-campaign, **re-baseline before continuing**, or an
  instrument-model change will be silently attributed to a gateway lever.
- This lane is the sole owner of GKE. Nobody else recreates node pools.

## First steps for the chat that picks this up

1. Read `RECAP-2026-07-21-osff3-taq-throughput-session.md` (esp. §3 and §"OPEN OPTION") and
   `specs/YU13-limit-order-book/generation/implementation-status.md`.
2. Establish a clean baseline on the current build/hardware — 3 runs, pinned digest, gateways verified
   one-per-node, fresh epoch, accounts rotated. Everything after is measured against this.
3. Land lever 1, A/B it, then lever 2, then re-test the batch curve. One variable at a time.
4. Only once Phase 1 numbers are banked, do the single node pool rebuild for Phase 2.
