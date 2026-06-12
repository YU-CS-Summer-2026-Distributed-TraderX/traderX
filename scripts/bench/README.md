# Order-matcher benchmark: state `009` vs state `009b`

`../bench-009-vs-009b.sh` runs the identical order + price-tick workload through a fully
started, fully torn-down stack of each state and prints the timing difference. The driver is
`order-matcher-bench.mjs` (REST orders to `:18110`, NATS ticks to `:4222`, drain to
all-terminal). Full experiment report and measured results:
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
