# TraderX-LMAX — Throughput & Latency Improvement Levers

**Purpose:** the performance track — where E2E throughput and latency are actually limited, and the
ranked levers to close the gap. (This file was referenced by CLAUDE.md / CLOUD-ARCHITECTURE.md /
HANDOFF-FOR-TEAMMATE.md before it existed; now it does.)
**Status:** living doc. Created 2026-07-14. Untracked working note — do not commit.
**Parent:** `ROADMAP-production-readiness.md` (Tracks B + D). See also `LMAX-BLP-FAILOVER.md`.

---

## 1. The measured reality (know this before optimizing anything)

| Layer | Measured | Note |
|---|---|---|
| **Matching core alone** (BLP thread, no journal/replication/DB/REST) | **~6M ops/s** | 009b bench; consistent with LMAX's published design. NOT the E2E number. |
| **E2E single-BLP** (REST→match→journal→DB) | **~42k booked/s** | dedicated c2 node |
| **E2E HA** (adds NATS replication + ACK) | **~12.7k–22k booked/s** | replication tax |
| **Projector→DB write path** | **~1,060 rows/s** (one config) | the real E2E ceiling |

**The core is ~100–300× faster than the pipeline around it.** Every improvement below is about the
*edges*, not the engine. The bench also confirmed: ingress batching lifted raw order *acceptance* to
~8k/s bursts but did NOT raise booked/s — proving the ceiling is on the **output** side.

---

## 2. Ranked levers

### Lever 1 — Decouple / batch the projector→DB path (HIGHEST leverage)

**Problem:** the output ring fans out to N consumers; the single-writer disruptor advances at
`min(consumer sequences)`. The projector (per-row JPA `merge`, assigned `@Id`, no
`hibernate.jdbc.batch_size`) is the slowest consumer at ~1k rows/s, so it gates the entire ring —
`out_free` hits 0, the BLP stalls, gateway acks time out.

**Fix options (in order of preference):**
1. **JDBC batch inserts** — set `hibernate.jdbc.batch_size`, batch by flush window. Cheapest win.
2. **Fully decouple** — the projector reads from a *bounded queue* that the ring writes to
   non-blocking; on overflow, drop-to-log (the DB is a downstream view, not source of truth, so a
   lagging/rebuildable projection is acceptable). The ring must never backpressure on the DB.
3. **Log-structured sink** — append events to a fast log; a separate process materializes the
   read-model. Cleanest CQRS shape.

**Expected:** removes the 22k ceiling; plausibly 100k+ E2E with no engine change.
**Validate:** `traderx_projector_lag_seq` stays ~0 under load; `booked/s` tracks `submit/s`.

### Lever 2 — Binary ingress (SBE over TCP/Aeron) alongside REST

**Problem:** REST + JSON per order — HTTP parse + object allocation dominate ingress cost, and it's
the single biggest reason E2E is 22k not 500k. Also the latency floor (HTTP + GKE networking = ms).

**Fix:** a binary order-entry path using **SBE (Simple Binary Encoding)** framing over raw TCP or
**Aeron** (UDP, reliable, low-latency — the transport LMAX itself moved to). Keep REST for the demo
UI; add binary for the throughput/latency path. This is also a prerequisite for the FIX gateway
(Track A / candidate YU10).

**Expected:** large throughput gain + drops ingress latency from ms toward µs.

### Lever 3 — Real matching workload + latency instrumentation (do BEFORE latency tuning)

The bench uses synthetic deep-in-the-money auto-fills (~100% fill ratio). That inflates "booked/s"
into something not comparable to any real venue, and it never exercises the real book.
- **Real two-sided bench**: resting orders, price-time priority, partial fills, modifies/cancels.
- **Tick-to-trade HdrHistogram** (HdrHistogram is already in the codebase for match latency): you
  cannot improve a latency number you don't measure, and we currently don't know ours.

### Lever 4 — Latency / mechanical sympathy (HFT track, LATER)

Only once Levers 1–3 land. The LMAX playbook:
- **Zero-allocation hot path** — steady-state ~0 GC; `traderx_hotpath_alloc_bytes_total` measures it.
- **Low-pause GC** — ZGC / Generational ZGC or Shenandoah (we're on G1). Also the cheapest defense
  if a GC regression ever threatens the lease renewal (see the failover doc's escalation ladder).
- **BusySpinWaitStrategy** on the disruptor (demo uses BlockingWaitStrategy — trades CPU for latency).
- **CPU pinning + `isolcpus` + NUMA awareness** — dedicate cores to the BLP thread, keep the OS off
  them. The `blp.pin.cpu` hook already exists.
- **Kernel bypass** — Aeron / DPDK / Solarflare Onload for the network path.
- **PTP clock sync** — sub-µs timestamping; NTP isn't enough (and MiFID II mandates PTP-grade).

### Lever 5 — HA-replication path (the 12.7k→42k HA gap)

The HA tax is the NATS publish + follower-ACK round-trip per batch. Levers (originally the reason
this file was referenced):
- **`ORDER_MATCHER_JOURNAL_BATCH_RECORDS`** coalescing — already tuned to 1024; revisit under the
  real-matching bench.
- **Pipelined ACK** — the primary spins to `followerAckedSeq >= batchEndSeq` once per Disruptor
  batch (one round-trip per batch, not per event). Larger batches amortize it; verify batch
  formation under sustained load.
- **NATS→Aeron for replication** — considered and rejected for the current single-c2-node HA (needs
  spare cores it doesn't have); revisit if the node budget grows.
- **NATS broker sizing** — it OOM'd under 22k/s HA load 2026-07-14 (no memory limit, 1.8 GiB used).
  Real fix: JetStream file storage + stream/consumer limits, not just a bigger memory cap.

---

## 3. Do-this-order summary

1. **Lever 1** (projector decoupling) — removes the current ceiling. Biggest bang, lowest risk.
2. **Lever 3** (real bench + latency histogram) — so every subsequent number is honest.
3. **Lever 2** (binary ingress) — throughput + latency + FIX-gateway prerequisite.
4. **Lever 5** (HA replication) — close the HA-vs-single-BLP gap.
5. **Lever 4** (mechanical sympathy) — the HFT track, last.
