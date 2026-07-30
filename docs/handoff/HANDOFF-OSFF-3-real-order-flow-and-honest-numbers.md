# HANDOFF-OSFF-3 — Real order flow + honest benchmark/failover numbers

> One of the OSFF-NY direction handoffs (OSFF-1..4), created 2026-07-20. **This is work 3 — the
> realism + slide-numbers pass.** Two goals: (a) drive the real book with real market order flow so
> the demo looks genuine, and (b) lock down honest, reproducible throughput + failover numbers for
> the Nov talk. Self-contained for a fresh chat.
> **Home:** `traderX-YU12-aeron-cluster` worktree, `docs/handoff/` — beside the YU12 recaps; the
> `HANDOFF-issue-yu12-*.md` docs it references are in that worktree's `issues/`. Untracked working note.

## STATUS (2026-07-21 EOD) — OSFF-3 COMPLETE except the tuned-host tail run. Authoritative.

Full detail + method + traps in the YU13 lane's `implementation-status.md` (sections "Real TAQ
order flow", "T-LOB16 RESOLVED", "Failover presentation framing"). Summary:

| Item | Result |
|---|---|
| **Real TAQ order flow (was #1)** | ✅ **DONE.** 1,138,793 real NYSE prints (7 symbols, 2025-03-03 09:30–10:00, $18.2B notional) replayed as passive+aggressor limit pairs through the crossing book on GKE. Paced 5×: pacing held exactly (360.2 s), 0.019% policy rejects (incl. two 2.1M-share NVDA blocks vs the 1M order-size cap), book returned to exactly empty, all 3 members byte-identical after 2.4M trades. Booked rate traced the real open: ~20k/s → ~4k/s. Max-rate: 14.6k prints/s / 29.2k orders/s (client-bound; the 75k ceiling stands). Harness: `scripts/bench/taq-replay.mjs`; slice: `scripts/bench/results/taq/`. |
| **Failover framing (was #3)** | ✅ Decided + documented: idle client-observed ~200 ms median / ~450 ms worst is THE number; the 8–12 s saturated-drain figure is a different question, backup-slide only, never on the same slide, never averaged. No FIRST-APPLY / ROLE-CHANGE numbers as client-facing. |
| **T-LOB16 (was #4)** | ✅ **RESOLVED — it was our config, not Aeron and not stale PVCs.** The kind wedge was the NetworkPolicy silently dropping replication traffic to the ephemeral (`:0`) replication/response channels. Pinned to in-block offsets 6/7/8; empty-disk rejoin into an aged cluster now converges <30 s on kind with the policy ON. The 2026-07-19 GKE ArchiveException/term-poisoning wedge is a distinct mechanism (distinct signature) and its doc carries the split. GKE redeployed on the fixed image; failover spot-check 124/348/223 ms — no regression. |
| **Async snapshot (was #5)** | Still deprioritized; nothing pointed at a snapshot stall all session. |

**Tuned-host tail (was #2) — ✅ MEASURED (dedicated pinned GCE c3-standard-8, 2 runs):**
the p99.9+ tail collapsed ~7–13× vs the shared laptop (limit-cross p99.9 15.4 µs → 4.4 µs,
p99.99 83.6 µs → 12 µs, max 272 µs → 20–55 µs) while p50 ~doubled (M-series core is faster
per-op than a c3 Xeon vCPU — say so honestly). The tail IS the host, not the book. Full table
in the YU13 implementation-status. Kernel-bypass stays literature-cited: no ef_vi on GCP, and
Aeron's ef_vi/DPDK media driver is commercial premium — the talk line remains *"nanoseconds in
the engine, microseconds wire-to-wire in software, nanoseconds only in silicon."* Deferred
rungs if ever needed: Epsilon on the bench JVM, `isolcpus`/`nohz_full`, AOT.

The historical brief below is kept for context. **The priority list in "Latency & failover
track" is superseded by this section.**

**Done — do not redo:**

| Item | Result |
|---|---|
| GKE sustained throughput (T-LOB15) | **62–75k booked/s** vs the 25,149 bar, 0 failures, 3 runs |
| Clean node-clock failover | **median ~200 ms, worst ~450 ms** — bimodality root-caused and fixed |
| Engine match latency, full tail | p50 167 ns insert / 542 ns cross, p99 < 2 µs |
| Wire-to-wire REST latency (GKE) | p50 1.42 ms @100/s, constant-arrival-rate (omission-safe) |
| 40 ms Nagle/delayed-ACK artifact | found + fixed (`sun.net.httpserver.nodelay`), flag in manifests |

**Remaining, in priority order:**

1. **Real TAQ order flow** from the YU07 tick store — completely untouched, and it is the demo
   realism beat. Curate a slice of liquid symbols; do not stream the full ~650 GB. **Use an odd
   ticker count** — the harness alternates sides by index, so an even count means nothing crosses.
2. **Tail latency on a tuned host.** The p99.99 tail is JIT, safepoints, and OS scheduling on a
   shared dev box — not the book. Attack with AOT/GraalVM (or Zing ReadyNow), `isolcpus`/`nohz_full`
   /core pinning, Epsilon. For wire-to-wire, Aeron on an ef_vi/DPDK media driver first.
3. **Decide how to present failover under saturation** (~8–12 s while all gateways reconnect and a
   backlog drains) versus the ~200 ms idle number. Both real, different questions, never mixed.
4. **T-LOB16 — retest against a new hypothesis (see below) before accepting it as blocked.**
5. **Async snapshot off the hot thread — DEPRIORITIZED, likely unnecessary.** The original brief made
   this the #1 failover unblock. The data overtook it twice: failover is already ~200 ms, and the
   remaining variance was a client reconnect bug, not an election-timeout or snapshot-barrier limit.
   Revisit only if a measurement actually points at a snapshot stall.

### T-LOB16 — the "empty-disk rejoin" defect may be misnamed (hypothesis, 2026-07-21)

T-LOB16 records that a wiped member wedges in log replication (`applied=-1`) rejoining an aged
cluster, attributed to an inherited Aeron 1.51 defect and treated as a blocker. **Contradicting
evidence:** GKE members use `emptyDir`, so *every pod restart there is already an empty-disk rejoin*
— and they have rejoined cleanly dozens of times. The kind reproduction used **PVCs**.

So the trigger may be **stale PVC state plus accumulated term history**, not empty-disk rejoin per
se. That would downgrade it from "inherited blocker" to "don't reuse a stale PVC", which is a config
rule, not a defect. **Retest against this hypothesis before accepting the acceptance line as blocked**
— and note the diagnostic asymmetry: it is the *PVC* path, not the *empty* path, that has never been
proven clean.

## The gap

- **Synthetic one-sided flow.** Once OSFF-2 ships a real crossing book, feeding it hand-generated
  one-sided orders undersells it. You already have the raw material: YU07 historical **TAQ tick
  store** on GCS, and yaakov has Feb (364GB) + March (286GB) TAQ data. Replaying real historical
  order/quote flow crossing on a Raft-consensus engine is a killer demo beat.
- **The headline throughput number is a fragile burst, not sustained.** Per
  `RECAP-2026-07-20-yu12-bridge-bench-session.md` §5, the 134,755 booked/s peak did **not reproduce**
  this session (`batch=1000` clean-first-load gave ~2k); leading suspect is the image rebuild
  changing the member binary (`imagePullPolicy: Always` silently swaps the running build). Sustained,
  cleanly measured today was **~64k submit / ~45k booked, 0 restarts** — which already clears the
  25,149 NFR-AC02 bar. Don't present 134k as reproducible until it is.
- **No clean leader-kill failover number.** Only a *spontaneous* election under load was caught
  (4/654 failed, 99.4%); the planned `kill -9` test never ran. Prior clean numbers (653–716ms
  system-facing, 5 kills) are from earlier sessions on the earlier build.

## What "done" looks like

- The book is fed **real TAQ order flow** (replayed from YU07/GCS) via the feed adapter as cluster
  ingress (ADR-044: consensus log is the only input — market data enters as cluster ingress, not a
  side channel). Fills are driven by real crossing, not a static last-price.
- One **sustained** throughput number you can defend on a slide: reproduced ≥3 runs, pinned image,
  0 restarts, `booked=applied`, sub/appl ≈ 0.99. Either 135k made sustainable, or an honest lower
  sustained figure — measured, not a burst peak.
- One **clean node-clock leader-kill failover** number on the current (post-OSFF-2) build, plus an
  explanation of the spontaneous-election-under-load behaviour.

## How

- **Throughput reproduction:** pin the image digest (not `:yu12` + `Always`) so the member binary is
  fixed across runs; understand the batch/qty interaction (a real book changes the fill cascade math
  vs the old half-fill model); use distributed load gen across all 3 gateways (needs the OSFF-1
  sessionAffinity split, or bypass the Service per-pod) and sum per-pod metrics. See
  `HANDOFF-issue-yu12-sustained-throughput.md`.
- **Failover:** the node-clock-precise method — `kubectl exec` into the leader, print a millisecond
  timestamp, `kill -9` the JVM in one shot; read the new leader's role-change log; NTP-synced nodes.
  This is the `aeron-cluster-live-ops` skill's measurement recipe. Distinguish system-facing (new
  leader committing) from client-facing (first order accepted) — present system-facing as the
  headline, note the client floor honestly. See `HANDOFF-issue-yu12-failover-measurement.md`.
- **TAQ flow:** replay real trades/quotes as order ingress through the existing SBE feed adapter;
  map TAQ symbols → the cluster's securityId; keep tick scaling consistent with OSFF-2's price rep.

## Proof / acceptance

- Recorded bench run: sustained throughput, 3× reproduced, 0 restarts, on a pinned image.
- Recorded failover: node-clock timestamp delta across a real `kill -9`, one promotion, 0 reuse.
- Demo clip: real TAQ flow crossing on the book with live Grafana (throughput, failover timeline,
  lag, snapshots) at grafana.yaakovseif.dev.

## Latency & failover track — HFT techniques on commodity hardware (honest floors)

Remove what can't be acquired: **colocation** (speed-of-light geography, ~5 µs/km of fiber — a
real-estate cost, not engineering) and **FPGA/ASIC** (the only thing that buys sub-µs wire-to-wire).
What's left is a **single-digit-microsecond wire-to-wire software floor** with a nanosecond-scale
matching core inside it (OSFF-2). That's the honest target — do not claim ns wire-to-wire in software.
You already made the hard choices right (Aeron/Disruptor/SBE/zero-GC); the wins below are *tuning and
transport*, not rearchitecting.

**Kernel bypass — the #1 lever** (the Linux net stack costs ~5–30 µs/hop in syscalls, copies,
interrupts, context switches), ranked by ROI:
1. **Aeron on a kernel-bypass media driver FIRST** — you're already on Aeron; its premium/C media
   driver can drive **ef_vi (Solarflare/AMD) or DPDK** directly. Least new code, biggest single cut.
2. **DPDK** — userspace poll-mode driver, ~1 µs NIC→app, commodity Intel/Mellanox NIC, open source.
3. **Solarflare/AMD NIC + ef_vi / TCPDirect** — ~1 µs or below; the NIC is a ~$1–3k acquirable part.
4. **AF_XDP** — cheap middle ground: no special NIC, far easier than DPDK, a few µs.
5. **io_uring** — not bypass, but kills syscall overhead on any non-hot path.

**OS / CPU isolation** (free; cuts tail jitter AND unblocks tighter failover):
- `isolcpus` + `nohz_full` + `rcu_nocbs` — evict the scheduler and timer ticks from the hot core so
  nothing preempts the matching/heartbeat threads; thread affinity / core pinning; IRQ affinity off
  the hot cores.
- C-states/P-states off, `governor=performance`, keep turbo (HFT wants *fast cores, few of them* —
  never "supercomputers"); NUMA-local (thread + NIC + RAM on one socket); explicit huge pages;
  consider disabling the hot core's hyperthread sibling.

**Measurement (do FIRST, or you tune blind):** HdrHistogram, **p50/p99/p99.9/p99.99/max**, wire-to-wire
via **NIC hardware timestamps**, non-backing-off load gen (coordinated-omission trap). Mean is useless.

**Failover — drive toward the Raft floor.** Current **653–716 ms** is heartbeat-bound (shipping
400 ms hb / 200 ms election) and *tighter configs false-fire at snapshot barriers*. That's the whole
blocker. To tighten safely, kill what makes a healthy leader look dead:
- **Unblock #1 — async / copy-on-write snapshot OFF the hot thread**, so the apply loop never stalls
  (the ~8 s apply-stall on a 30 s snapshot is exactly what a tight detector reads as death).
- Zero-GC (have it) + `isolcpus` remove the other two false-death sources (GC pause, scheduler jitter).
- **Then** tighten toward ~50 ms hb / ~100–150 ms election → **system-facing failover ~100–200 ms**
  (the realistic Raft floor).
- **Client-facing (separate, cheap, do regardless):** native `AeronCluster.newLeaderEvent`
  leader-follow instead of the test client's endpoint-cycling → **client-facing ~200 ms**, independent
  of election time. This is the number a counterparty actually feels.
- **The fork:** sub-~50 ms failover is NOT a Raft story — it's **active-active / hot-hot** (the YU11
  warm-standby model YU12 replaced), which reopens the split-brain fencing you solved with consensus.
  Don't chase it without deciding to take that trade back.

**Where to stop for OSFF (a POC talk, not an HFT desk):**
- **Do:** Aeron-on-bypass + `isolcpus`/pinning + keep zero-GC + an HdrHistogram tail-latency slide +
  the async-snapshot failover-tightening pass. Each is a clean measurable before/after — ideal talk
  material.
- **Don't:** FPGA, custom kernels, microwave links, sub-µs chasing, or the active-active rewrite. The
  honest *"µs in software, ns in silicon"* line is a stronger slide than a hollow nanosecond claim.

**Acceptance additions:** (1) wire-to-wire tick-to-trade histogram (full percentiles) before/after
kernel bypass + isolation; (2) system-facing failover re-measured after async-snapshot + tightened
timeouts; (3) client-facing failover re-measured after the `newLeaderEvent` fix.

## Dependencies & sequence

- **Depends on OSFF-2** (real book) for the flow to be meaningful, and on **OSFF-1** (sessionAffinity
  split) for REST scale-out numbers.
- Reuses the YU07 tick store as-is (no changes expected there).

## Open questions

- How much TAQ to stage for the demo — a curated slice of liquid symbols is enough; do NOT try to
  stream 650GB live. Pick a representative window.
- Is the 134k burst genuinely sustainable, or is the honest headline a lower sustained number? Let
  the measurement decide; the slide must match reality.
- Snapshot interval under real flow: 60s measured optimal (30s cost ~8s apply-stall). Re-confirm
  once the book is real and snapshots are larger.

## First steps for the chat that picks this up

1. Read the two YU12 recaps + the three referenced YU12 issue docs.
2. Pin the image digest, re-run the pipelined bench, and settle the sustained number first (it's the
   slide anchor) before touching TAQ.
3. Run one clean node-clock leader-kill on the current build; record it.
4. Wire a curated TAQ slice through the feed adapter; capture the demo clip with Grafana.
