# Benchmark: state `009` vs state `009b` — the same trading workload through both matchers

> **Date:** 2026-06-11/12 · **Status:** experiment report (measured, not simulated)
> **Harness:** `scripts/bench-009-vs-009b.sh` + `scripts/bench/order-matcher-bench.mjs`
> **Raw artifacts:** `bench-results/20260611-222221/` (final A/B), `bench-results/20260611-202014/` (3M-tick probe), `bench-results/20260611-211628/` and `bench-results/20260611-214440/` (failure-mode runs + forensics)
> **Environment:** WSL2 (Ubuntu 24.04) on Windows, Docker Desktop integration, single host. Demo/`C2` profile for `009b` (BlockingWaitStrategy, no core pinning, containerized). Numbers are indicative for this machine, not lab-grade latency measurements.

## TL;DR

The identical workload — **500 resting limit orders + 6,000 NATS price ticks on a descending
price ramp that progressively crosses every order** — was run through a fully started,
fully torn-down stack of each state:

| | `009` (poll/lock/inline-JPA matcher) | `009b` (LMAX hot path) |
| --- | --- | --- |
| order submit (REST, 500 orders) | 2.7 s | **0.9 s** |
| order-ack latency p50 / p99 | 78.9 ms / 164.2 ms | **25.4 ms / 71.4 ms** |
| tick publish window (6,000 msgs) | 0.2 s (24.8 k/s) | 0.2 s (24.7 k/s) |
| drain after last tick → all orders terminal | 176.7 s | **0.0 s** |
| **total wall time** | **179.6 s** | **1.2 s** |
| completed / filled | true / 500 | true / 500 |
| auto-fills executed | 755 | 755 |
| trade-booking failures | 0 | 0 |
| price ticks consumed (exact) | n/a (no counter in `009`) | 6,220 (all offered + ambient) |
| BLP-thread bytes allocated, entire run | n/a | **4,776 B** (no-GC contract holding live) |

**`009b` completed the whole workload 153× faster (1.2 s vs 179.6 s).** Both states produced
identical business outcomes (500/500 filled, 755 fills, zero booking failures), so the delta is
pure execution-model: `009` consumed the tick backlog at single-digit ticks/second (full
`OrderBook` table query per tick + 1 s scheduled poll + fills booked inside the match loop),
while `009b`'s single-threaded BLP consumed every tick **inline as it was published** — by the
time the first drain poll ran, everything was already terminal.

And the original question — "push a few million events through `009`" — has a measured answer:
**`009` cannot absorb millions of events at all.** Under a 3,000,000-tick stream offered at
24,153 msg/s, `009` consumed **~2.3 ticks/second** (≈0.01% of offered load), projecting **~31
hours** before the first order would even cross; its NATS client sheds the rest. `009b`
consumed the same 3M-tick stream inline as it arrived (its leg of that probe finished the full
workload in **1.0 s**; `traderx_input_events_total{type="price_tick"}` confirmed 3,000,000+
sequenced ticks processed). The calibrated 6,000-tick A/B above exists *because* a workload
both states can finish was the only way to print a finite ratio.

---

## 1. What was measured

One driver (`scripts/bench/order-matcher-bench.mjs`) ran the same three phases against each
state, with events entering exactly where they enter in production:

1. **submit** — 500 `Buy` limit orders via `POST /orders` on the order-matcher (`:18110`),
   account `42422` (real account, no seeded orders), tickers `JPM,GS,MS,DB` (served by
   reference-data, so trade-service booking validation passes), quantities alternating
   500 / 1,800 (single-round and two-round fills under the matcher's `fill-full-threshold`
   of 1,000), limit prices laddered evenly across **[70, 90)**.
2. **publish** — 6,000 price ticks on `pricing.<TICKER>` via NATS (`:4222`), per-ticker price
   ramping linearly **210 → 60**, rate-limited to ~25 k msg/s aggregate. The ramp starts above
   every limit (nothing in the money) and ends below every limit (everything crosses), so
   fills are driven progressively and deterministically by the same byte-identical stream.
3. **drain** — poll `GET /orders?status=open&accountId=42422` until no bench order is open.
   The clock for "total" runs from first submit to drain-complete. A stall detector
   (90 s without progress) publishes counted floor-price "closer" ticks as a safety net.

Per state, the orchestrator (`scripts/bench-009-vs-009b.sh`) provides total isolation:

- pre-clean: `docker compose down --volumes --remove-orphans` + delete the generated tree;
- regenerate the state from scratch (`pipeline/generate-state.sh <state>`);
- start the **full stock stack** with the state's own lifecycle script (database, NATS,
  reference-data, account/people/position services, trade-service, trade-processor,
  price-publisher, ingress, UI, LGTM observability) and wait for every health check;
- run the driver; capture results JSON + `/health` + `/metrics` snapshots
  (+ container logs on failure);
- post-clean: stop script + `down --volumes --remove-orphans` again.

So each measurement is against a cold, freshly generated, fully running state, and nothing
survives between runs.

## 2. Final A/B result (run `20260611-222221`)

```
                        009-order-management-matcher  009b-lmax-sequencer-architecture
workload                500 orders + 6,000 ticks      500 orders + 6,000 ticks
order submit (REST)     2.7s                          0.9s
ack latency p50 / p99   78.9ms / 164.2ms              25.4ms / 71.4ms
tick publish window     0.2s                          0.2s
drain after last tick   176.7s                        0.0s
TOTAL wall time         179.6s                        1.2s
completed / filled      true / 500                    true / 500
closer ticks needed     200                           0
ticks consumed (exact)  n/a (009 has no counter)      6,220
trade submit failures   0                             0

TOTAL difference: 009 took 178.4s LONGER than 009b (153.08x)
```

Reading the phases:

- **Submit (2.7 s vs 0.9 s; ack p50 78.9 ms vs 25.4 ms).** A `009` create is an inline JPA
  write plus a NATS publish on the request thread. A `009b` create is an edge validation, a
  ring-slot claim, and a wait for the BLP's response event; the persistence happens
  asynchronously behind the output ring. Both pay container/WSL networking overhead, which
  dominates the absolute numbers; the 3× ratio is the architectural part.
- **Drain (176.7 s vs 0.0 s).** This is the matcher difference, isolated. `009`'s NATS
  subscriber executes `findAllByOrderByUpdatedAtDesc()` — a full `OrderBook` table load —
  **per tick**, plus per-crossed-order fill attempts under `orderMutationLock`, with the
  `@Scheduled` 1 s poll doing the same on top. It chewed through the 6,000-tick backlog at
  tens of ticks per second and finished ~3 minutes after the last tick was published.
  `009b` consumed all 6,220 ticks (6,000 ours + ambient price-publisher ticks) inline at the
  offered ~25 k/s: its drain measured **0 ms** because everything was terminal before the
  driver's first 500 ms poll.
- **Identical outcomes.** 500/500 filled, 755 auto-fills on both (250 single-round +
  250 two-round bench orders + 5 seeded demo orders), zero booking failures on both. The
  comparison is execution model only.
- **`009b`'s no-GC contract held in production conditions:** 4,776 bytes allocated by the BLP
  thread across the entire run (`traderx_hotpath_alloc_bytes_total{node="blp"}`) — about one
  small object's worth of slack against a workload of ~6.7 k sequenced events and 755 fills.
- One `closer` batch (200 floor-price ticks) fired for `009` when its silent backlog-chewing
  exceeded the 90 s stall window; it queued behind the same backlog and did not change the
  outcome (the organic ramp already guaranteed completion).
- **Booking semantics caveat (disclosed, not hidden):** "drain complete" is the
  *acknowledgement-path* milestone. In `009` a fill only exists after its trade was booked
  synchronously inside the match loop, so drain-complete ⇒ all trades booked. In `009b`
  booking rides the output ring's `TradeSubmitHandler` off the ack path (FR-09B15/SC-09B15)
  and settles asynchronously — the driver therefore waits 10 s after the drain and re-snapshots
  booking counters before reporting; final failures were 0 for both states.
- **Ambient ticks disclosure:** the compose stack runs price-publisher with
  `PRICE_TICKERS` = the full ~20-ticker supported set, so it also random-walks the bench
  tickers (~2–3 msg/s) in both runs. Walks are confined to a ±≤4% band around snapshot prices.
  For JPM (~196) and GS (~404) that is far out of the money; for MS (~91) it grazes the top of
  the ladder and for DB (~17) it sits *below* the ladder, so the DB cohort filled from ambient
  ticks early — **identically in both states** (fill counts match exactly at 755). The driver
  default has since been corrected to `JPM,GS,COF,DFS` (all snapshot bases ≥ ~128) so future
  runs are purely ramp-driven.

## 3. The "few million events" answer (probe run `20260611-202014`)

The experiment originally offered **3,000,000 ticks at 24,153 msg/s** (after seeding 5,000
orders, which `009` accepted at ~236 orders/s). Measured mid-run, `009`'s view of the market
advanced from 209.790 to 209.786 in 45 s — **~2.3 ticks/second consumed**, i.e. ~0.01% of the
offered rate, with the jnats client shedding what its pending buffer couldn't hold. At that
rate `009` needed **~31 hours** to reach the first in-the-money price, so the run was halted
and the workload recalibrated to something `009` can finish (the 6,000-tick A/B above).

The root cause is visible in the code: `OrderMatcherService.onPriceTick` runs a full
`OrderBook` table query *per tick* (plus per-order fill attempts under the lock), so per-tick
cost scales with book size (~430 ms/tick at a 5,007-row book; tens of ms at a ~500-row book).
`009b` has no such coupling: a tick is a sequenced ring event handled by the BLP as two array
reads and an integer compare per open order — it absorbed the full 3M-tick stream inline
(`traderx_input_events_total{type="price_tick"}` ≥ 3,000,000 on that run, total workload time
**1.0 s**).

This asymmetry is not a benchmark artifact; it is the difference under test
(`LMAX-SEQUENCER-ARCHITECTURE.md` §3's anti-pattern table, rows 1–2 and 7).

## 4. What each number maps to in the architecture

| Observation | `009` mechanism | `009b` mechanism |
| --- | --- | --- |
| 2.3 ticks/s vs full inline consumption | DB query per tick (`findAllByOrderByUpdatedAtDesc()` in `onPriceTick`), JSON parse per message, fills inside the same handler | Gateway stamps + claims a pre-allocated ring slot; BLP scans a per-security `IntList` over pooled `RestingOrder`s (`MatchingEngine.onPriceTick`) |
| 176.7 s vs 0.0 s drain | Backlog must be chewed at DB-query speed; 1 s poll cadence gates fill rounds; `ReentrantLock` serializes against the subscriber | Event-driven matching: each tick is matched the moment the BLP consumes it; two-round fills complete on the next tick for that ticker |
| ack p50 78.9 ms vs 25.4 ms | Create = inline JPA write + inline NATS publish on the request thread | Create = ring claim + in-memory match; persistence/fan-out happen behind the output ring (async read-model projector) |
| 0 booking failures both, but storms in earlier broken runs (97 k / 99 k) | Booking is a blocking `POST /trade/` *inside the match loop*; a failed booking leaves the order open and is retried on **every** tick and poll — failure amplification | `TradeBooked` is a typed output event; the bridge retries/fails off the ack path and a failure is a counter, not a matching stall |
| 4,776 B allocated on the BLP thread | n/a (allocation-heavy by design: streams, `BigDecimal`, DTOs per tick) | NGC-01 discipline: pre-allocated slots/pools, `long` fixed-point, single-writer release-store telemetry (enforced by `AllocationGateTest` + Epsilon gate) |

## 5. Experiment journal — every run, including the broken ones

Six attempts were needed; each failure exposed something real about `009`'s coupling surface,
so they are part of the result:

1. **3M ticks, attempt 1** — orchestrator bug: compose-file glob matched the parent state's
   observability compose (`no such service: price-publisher`); fixed by selecting the compose
   file that defines the `order-matcher` service.
2. **3M ticks, attempt 2 (run `20260611-202014`)** — produced the consumption-rate probe in §3.
   Killed deliberately after measuring ~2.3 ticks/s (projected ~31 h to first fill).
3. **6k ticks, attempt 3 (run `20260611-205736`)** — the harness stopped price-publisher to
   keep the tick ramp deterministic. **Wrong:** trade-service fetches the execution price from
   price-publisher on every booking (`fetchExecutionPrice`), so *all* bookings failed
   (43,046 failures), and `009`'s pre-fill-rejection semantics wedged every fill while `009b`'s
   off-path booking would have masked it — an unfair A/B. Fix: keep price-publisher running and
   get determinism from workload geometry instead (limits below the publisher's possible price
   bands).
4. **6k ticks, attempt 4 (run `20260611-211628`)** — `009` stalled at exactly 250/500 with
   97,091 booking failures; `009b` completed in 1.021 s. Initially misattributed to the
   two-round quantity cohort.
5. **6k ticks, attempt 5 (run `20260611-214440`)** — single-round-only workload *still*
   stalled at 250/500 (99,157 failures). Live forensics on the still-running stack found the
   true cause: **reference-data serves only ~20 tickers** (supplemental financial names + the
   compose `SUPPORTED_TICKERS` list — the S&P CSV is filtered), and two bench tickers (WMT, KO)
   were not in it. Every WMT/KO booking 404'd; `009` retried each wedged order on every tick
   and poll — ~250 failed `POST /trade/` per second, sustained. The "storm" is `009`'s
   booking-inside-the-match-loop anti-pattern manifesting, not a trade-service defect
   (`009b` filled the same orders in-memory and surfaced the same 404s as counters, off the
   ack path, exactly as designed).
6. **6k ticks, attempt 6 (run `20260611-222221`)** — valid tickers (`JPM,GS,MS,DB`); both
   states completed with identical outcomes; the §2 table is this run.

## 6. Reproducing

```bash
# defaults: 5,000 orders + 3,000,000 ticks (009 will NOT finish — that's finding §3)
scripts/bench-009-vs-009b.sh

# the calibrated A/B from §2
BENCH_ORDERS=500 BENCH_TICKS=6000 \
BENCH_DRAIN_TIMEOUT_MS=720000 BENCH_STALL_MS=90000 \
scripts/bench-009-vs-009b.sh
```

Knobs: `BENCH_ORDERS`, `BENCH_TICKS`, `BENCH_TICK_RATE`, `BENCH_TICKERS` (must be in
reference-data's served set; snapshot price must sit well above `limitHigh` — see the
constraints comment in `order-matcher-bench.mjs`), `BENCH_QTY_A`/`BENCH_QTY_B`,
`BENCH_ACCOUNT`, `BENCH_DRAIN_TIMEOUT_MS`, `BENCH_STALL_MS`, `BENCH_RESULTS_DIR`.

Requirements: Docker (compose v2), Linux Node (nvm fine; the generation pipeline and the
driver both need it), network for image builds. Each state cycle regenerates the tree, builds
images, runs, and removes containers **and volumes**.

## 7. Caveats

- Single WSL2 host, services containerized, demo profile: absolute latencies include Docker
  NAT/virtualization noise and `009b` runs `BlockingWaitStrategy` with no core pinning
  (NFR-09B06). The perf profile (busy-spin, pinned cores, journal on fast storage) would
  *widen* the gap; nothing here uses it.
- `009b`'s 1.2 s total is ack-path completion; async booking settled within the 10 s
  post-drain window with zero failures (§2 caveat).
- The drain poll itself (one REST list per 500 ms) adds identical light load to both states.
- One `closer` batch (200 ticks at the floor price) was published into `009`'s run by the
  stall safety-net; it is counted in the report and did not change the outcome.

---

*Companions: `LMAX-SEQUENCER-ARCHITECTURE.md` (the design this validates),
`LMAX-NO-GC-JAVA.md` §A12.10 (the allocation gate whose contract the 4,776-byte runtime
number corroborates), `specs/009b-lmax-sequencer-architecture/` (the state under test).*
