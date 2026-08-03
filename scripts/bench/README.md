# Benchmarks & measurement scripts

Everything here **produces a number** (throughput, latency, rows/sec) — as opposed to the
falsifiable ✔/✘ correctness proofs, which now live in [`../proofs`](../proofs). Organised into:

| Dir | What it holds |
|---|---|
| [`load/`](load) | Order-entry **load generators** (REST/binary/FIX) and the orchestrators that drive them: `max-load` / `avg-max-load` / `batch-load` / `batch-experiment` (REST + batch), `fix-load` / `fix-multi` (FIX), `bin-multi` (binary), `rest-completed-control`, `order-matcher-bench`, the `run-*` ladders, `yu13-two-account-bench`, `measure-trade-processor-db-rate`, `reset-order-matcher-k8s` (the `RESET_CMD` helper), `spike-demo`. Kept together because the orchestrators spawn the generators by relative path. |
| [`latency/`](latency) | Latency & failover **probes**: `rest-latency-probe`, `failover-client-probe`, `failover-bimodal-probe`. |
| [`replay/`](replay) | Market-data replay: `taq-replay` (NYSE TAQ prints → book) and its `taq-curate` data-prep. |

`package.json` (the shared `nats` dep) stays at this root; `node` resolves `node_modules` by
walking up, so the scripts in the subdirs still find it.

## Order-matcher benchmark: state `009` vs state `009b`

`../bench-009-vs-009b.sh` runs the identical order + price-tick workload through a fully
started, fully torn-down stack of each state and prints the timing difference. The driver is
[`load/order-matcher-bench.mjs`](load/order-matcher-bench.mjs) (REST orders to `:18110`, NATS
ticks to `:4222`, drain to all-terminal). Full experiment report and measured results:
[`LMAX-BENCHMARK-009-VS-009B.md`](../../LMAX-BENCHMARK-009-VS-009B.md) at the repo root.

```bash
# defaults: 5,000 orders + 3,000,000 ticks — 009 will NOT finish (that is a finding,
# not a bug: it consumes ~2.3 ticks/s at a 5k-row book; 009b absorbs the stream inline)
scripts/bench-009-vs-009b.sh

# calibrated A/B both states complete (009 ≈ 3 min, 009b ≈ 1 s)
BENCH_ORDERS=500 BENCH_TICKS=6000 \
BENCH_DRAIN_TIMEOUT_MS=720000 BENCH_STALL_MS=90000 \
scripts/bench-009-vs-009b.sh
```

Results land in `bench-results/<timestamp>/`: per-state JSON + `/health` + `/metrics`
snapshots, stack/bench logs, failure forensics, and `REPORT.txt`.

## Workload constraints (violating any of these invalidates the run)

These cost several broken benchmark attempts to discover — see the experiment report's
journal section for the forensics:

1. **Tickers must be in reference-data's SERVED set.** It filters the S&P 500 CSV down to
   ~20 tickers (a supplemental financial-sector list + the compose `SUPPORTED_TICKERS`).
   Anything else 404s at trade-service booking validation. `009` then wedges: it rejects the
   fill pre-booking and retries it on every tick and poll (a ~250-failed-POST/s storm),
   while `009b` keeps its in-memory fill and just counts the failures — asymmetric semantics
   that make the comparison unfair. Check with `curl :18085/stocks`.
2. **price-publisher must stay running.** trade-service fetches the execution price from it
   on every booking (`fetchExecutionPrice`); stopping it fails *all* bookings.
3. **Limit prices must sit well below every bench ticker's snapshot price.** The compose
   stack runs price-publisher with `PRICE_TICKERS` = the full supported set, so ambient
   random-walk ticks for bench tickers are unavoidable; they stay within ±≤4% of the
   snapshot price. Defaults (`JPM,GS,COF,DFS`, snapshot bases ≥ ~128, limit ladder
   [70, 90)) keep ambient ticks permanently out of the money so only the driver's
   descending ramp (210 → 60) crosses limits. Do not use DB (~17) or MS (~91).

Knobs (env): `BENCH_ORDERS`, `BENCH_TICKS`, `BENCH_TICK_RATE`, `BENCH_TICKERS`,
`BENCH_QTY_A`/`BENCH_QTY_B` (1,800 makes two-round fills; 500 single-round),
`BENCH_ACCOUNT` (default 42422 — a seeded account with no seeded matcher orders),
`BENCH_DRAIN_TIMEOUT_MS`, `BENCH_STALL_MS`, `BENCH_RESULTS_DIR`, `BENCH_SKIP_GENERATE`.

Requirements: Docker compose v2, Linux Node (nvm fine — generation pipeline and driver both
need it), network for image builds. Every state cycle removes containers **and volumes**
before and after.

## Average peak throughput — `avg-max-load.mjs`

Drives the **already-running** 009b order-matcher to saturation N times and reports the
AVERAGE peak trades/sec (median / min / max / stddev) to the console and a text file under
`results/` (git-ignored). It spawns `max-load.mjs` as the load generator.

```bash
# 10 cold-start runs (restarts the matcher between each to reset the peak gauge)
node scripts/bench/load/avg-max-load.mjs --runs 10 --secs 25

# single WARM steady-state ceiling (no restart, one long run)
node scripts/bench/load/avg-max-load.mjs --no-reset --runs 1 --secs 60
```

**Measurement gotcha — why it does NOT just read Prometheus `irate`:** the matcher's REST
listener is both the load target *and* the `/metrics` source, so under saturation every
scrape is starved and `irate(fills)` mostly samples the quiet gaps and reads ~0 (you get a
~7/10-zero sawtooth, not a peak). The only trustworthy peak is the in-process gauge
`traderx_trades_per_second_peak` (a 100 ms event-time high-water mark that, per its own HELP
text, "resets on matcher restart" *only*). So the driver **restarts the matcher before each
run** and reads the gauge *after* the load (when HTTP is free again). Sustained `booked/s` is
a fill-counter delta across the run (both counter reads happen while HTTP is idle), and
`submit/s` is max-load's accepted-201 rate. Each restart is **cold** — the LMAX engine warms
(JIT) during the run, so the warm steady-state ceiling (`--no-reset`) is higher than any
single cold run.

Knobs: `--runs`, `--secs`, `--cooldown`, `--out`, `--no-reset`, `--no-preflight`; env
`RESET_CMD` / `MATCHER_COMPOSE_FILE` / `MATCHER_COMPOSE_PROJECT` / `MATCHER_SERVICE` to
override how the gauge is reset; plus all `max-load.mjs` flags/env pass straight through.

## Batch-ingress experiment — `batch-load.mjs` + `batch-experiment.mjs`

Tests whether batching the HTTP ingress (`POST /orders/batch`, K orders + one ack-block per
request) extracts more end-to-end throughput than the single-order path (`POST /orders`, one
order + one ack-block per request).

```bash
node scripts/bench/load/batch-load.mjs --batch 100 --conc 8 --secs 20   # one batch loader
node scripts/bench/load/batch-experiment.mjs --secs 15                   # single-vs-batch sweep
```

**Finding (see `results/batch-experiment-findings.md`): batching works at the ingress but does
NOT raise end-to-end throughput.** It lifted raw order *acceptance* ~5× (≈8,000/s bursts vs
≈1,400/s single), but booked/s stayed ~1–1.8k with rising HTTP 504s. The real ceiling is the
**output-side projector → Postgres** write path at **~1,060 persisted rows/s**: it is the
slowest of the 5 parallel output-ring consumers, so it gates the whole single-writer disruptor
(`min(consumer sequences)`) — the output ring fills (`out_free`=0), the BLP stalls, acks time
out. It is *not* DB CPU (Postgres ~0.57 cores) — it's per-row JPA `merge` round-trips
(assigned `@Id`, no `hibernate.jdbc.batch_size`). The lever that would help is decoupling the
projector from the ring and/or bulk/async persistence — not ingress batching.

## The credit-limit pitfall (why a REST bench "hits a wall" at ~50k orders)

Every order's notional is reserved against the account's **credit limit**
(`RISK_CREDIT_LIMIT_TICKS`, default 5e15 ticks) and **executed notional accumulates for the
process lifetime** — so a sustained one-sided burst of 500-share/$200 orders exhausts the
account after ~50,000 orders, and every subsequent POST returns `422 CREDIT_LIMIT` (or
`POSITION_LIMIT`/`CONCENTRATION_LIMIT`, whichever trips first). Pre-fix `max-load.mjs` counted
those 422s as `failed (← over the ceiling)`, which misreads the 15c3-5 gateway doing its job as
a throughput ceiling. It now reports them separately as `RISK-REJECTED (policy, not capacity)`
with a per-reason tally. For a sustained bench, raise `RISK_CREDIT_LIMIT_TICKS` on the
order-matcher **in a bench environment only** (env knob; requires restart), and lift the
position/concentration policy via `POST /risk/control/policy`.

## Measured tiers (2026-07-16, YU09)

| Tier | Where | Result |
|---|---|---|
| In-process journaled BLP (`POST /system/benchmarks/journaled-blp/run`, 2M orders) | GKE blp-pool (c2-standard-4), single-BLP | **1.26–1.59M orders/s sustained** (blpPeak ~2M/s) |
| REST per-order `POST /orders` (max-burst, in-cluster client) | kind (laptop) | **~9.2k/s**, zero failures — input/journal/BLP watermarks in lockstep, both rings ~empty: the ceiling is HTTP thread-per-request + the per-order ack-future block, not the engine |
| REST batch `POST /orders/batch` (batch=500, conc=16) | kind (laptop) | **~74k orders/s booked** — output ring becomes the next constraint (`outFree` touched 0). Use `max-load.mjs --batch N`. |

GKE REST per-order runs (~4k/s) were **credit-capped, not throughput-capped** — they died at
43k/51k ≈ 50k cumulative orders. The GKE per-order and batch ceilings are untested with credit
lifted; expect the same shape as kind (engine idle, ingress-bound), scaled by pod networking.
